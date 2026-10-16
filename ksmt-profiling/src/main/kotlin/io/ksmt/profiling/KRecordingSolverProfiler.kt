package io.ksmt.profiling

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Transparent in-memory [KSolver] decorator.
 *
 * The decorator never serializes expressions and never invokes solver operations that the caller
 * did not request. Result-access events refer to the last successfully completed check without
 * calling any additional solver operation.
 */
@Suppress("LongParameterList")
class KRecordingSolverProfiler<Config : KSolverConfiguration>(
    private val delegate: KSolver<Config>,
    private val solver: KSolverDescriptor,
    private val profileId: String = UUID.randomUUID().toString(),
    private val runId: String = UUID.randomUUID().toString(),
    private val parentProfileId: String? = null,
    private val initialConfiguration: KSolverConfigurationSnapshot =
        KSolverConfigurationSnapshot(KSolverConfigurationBase.UNKNOWN),
    private val profileAttributes: Map<String, String> = emptyMap(),
    private val maxRecordedEvents: Int = DEFAULT_MAX_RECORDED_EVENTS,
) : KSolverProfiler<Config> {
    private val startedAt = Instant.now()
    private val startedAtNanos = System.nanoTime()
    private val nextSequenceNumber = AtomicLong()
    private val nextCheckId = AtomicLong()
    private val eventsLock = Any()
    private val events = arrayListOf<KSolverProfileEvent>()
    private val assertionCountsByScope = arrayListOf(0L)
    private val currentTask = ThreadLocal<KSolverTask?>()

    private var totalAssertionCount = 0L
    private var droppedEventCount = 0L
    private var lastSuccessfulCheckId: Long? = null
    private var state = KSolverProfileState.ACTIVE

    init {
        require(maxRecordedEvents > 0) { "Maximum recorded event count must be positive" }
    }

    override fun snapshot(): KSolverProfile = synchronized(eventsLock) {
        KSolverProfile(
            schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
            profileId = profileId,
            runId = runId,
            parentProfileId = parentProfileId,
            state = state,
            solver = solver.copy(attributes = solver.attributes.toMap()),
            startedAt = startedAt,
            observedDuration = elapsedDuration(System.nanoTime()),
            initialConfiguration =
                initialConfiguration.copy(overrides = initialConfiguration.overrides.toMap()),
            eventLimit = maxRecordedEvents,
            events = events.sortedBy { it.metadata.sequenceNumber }.toList(),
            droppedEventCount = droppedEventCount,
            attributes = profileAttributes.toMap(),
        )
    }

    override fun <T> withTask(task: KSolverTask, block: KSolverProfiler<Config>.() -> T): T {
        val previousTask = currentTask.get()
        currentTask.set(task)
        return try {
            this.block()
        } finally {
            currentTask.set(previousTask)
        }
    }

    override fun configure(configurator: Config.() -> Unit) {
        recordUnitCall(
            event = { metadata -> KSolverConfigureEvent(overrides = null, metadata) },
        ) {
            delegate.configure(configurator)
        }
    }

    override fun assert(expr: KExpr<KBoolSort>) {
        recordUnitCall(
            onSuccess = { addAssertions(1) },
            event = { metadata -> KSolverAssertEvent(expr, tracked = false, metadata) },
        ) {
            delegate.assert(expr)
        }
    }

    override fun assert(exprs: List<KExpr<KBoolSort>>) {
        recordUnitCall(
            onSuccess = { addAssertions(exprs.size) },
            event = { metadata -> KSolverAssertBatchEvent(exprs.toList(), tracked = false, metadata) },
        ) {
            delegate.assert(exprs)
        }
    }

    override fun assertAndTrack(expr: KExpr<KBoolSort>) {
        recordUnitCall(
            onSuccess = { addAssertions(1) },
            event = { metadata -> KSolverAssertEvent(expr, tracked = true, metadata) },
        ) {
            delegate.assertAndTrack(expr)
        }
    }

    override fun assertAndTrack(exprs: List<KExpr<KBoolSort>>) {
        recordUnitCall(
            onSuccess = { addAssertions(exprs.size) },
            event = { metadata -> KSolverAssertBatchEvent(exprs.toList(), tracked = true, metadata) },
        ) {
            delegate.assertAndTrack(exprs)
        }
    }

    override fun push() {
        recordUnitCall(
            onSuccess = { assertionCountsByScope.add(0L) },
            event = { metadata -> KSolverPushEvent(metadata) },
        ) {
            delegate.push()
        }
    }

    override fun pop(n: UInt) {
        recordUnitCall(
            onSuccess = {
                val removedScopeCount = minOf(n.toLong(), assertionCountsByScope.lastIndex.toLong()).toInt()
                repeat(removedScopeCount) {
                    assertionCountsByScope.removeAt(assertionCountsByScope.lastIndex)
                }
            },
            event = { metadata -> KSolverPopEvent(n, metadata) },
        ) {
            delegate.pop(n)
        }
    }

    override fun check(timeout: Duration): KSolverStatus {
        val checkId = nextCheckId.getAndIncrement()
        return recordResultCall(
            onSuccess = { lastSuccessfulCheckId = checkId },
            onFailure = { lastSuccessfulCheckId = null },
            event = { status, metadata ->
                KSolverCheckEvent(
                    checkId = checkId,
                    timeout = timeout,
                    status = status,
                    metadata = metadata,
                )
            },
        ) {
            delegate.check(timeout)
        }
    }

    override fun checkWithAssumptions(
        assumptions: List<KExpr<KBoolSort>>,
        timeout: Duration,
    ): KSolverStatus {
        val checkId = nextCheckId.getAndIncrement()
        return recordResultCall(
            onSuccess = { lastSuccessfulCheckId = checkId },
            onFailure = { lastSuccessfulCheckId = null },
            event = { status, metadata ->
                KSolverCheckEvent(
                    checkId = checkId,
                    assumptions = assumptions.toList(),
                    timeout = timeout,
                    status = status,
                    metadata = metadata,
                )
            },
        ) {
            delegate.checkWithAssumptions(assumptions, timeout)
        }
    }

    override fun model(): KModel =
        recordResultCall(
            event = { _, metadata ->
                KSolverResultAccessEvent(KSolverResultAccessKind.MODEL, lastSuccessfulCheckId, metadata)
            },
        ) {
            delegate.model()
        }

    override fun unsatCore(): List<KExpr<KBoolSort>> =
        recordResultCall(
            event = { _, metadata ->
                KSolverResultAccessEvent(KSolverResultAccessKind.UNSAT_CORE, lastSuccessfulCheckId, metadata)
            },
        ) {
            delegate.unsatCore()
        }

    override fun reasonOfUnknown(): String =
        recordResultCall(
            event = { _, metadata ->
                KSolverResultAccessEvent(
                    KSolverResultAccessKind.REASON_OF_UNKNOWN,
                    lastSuccessfulCheckId,
                    metadata,
                )
            },
        ) {
            delegate.reasonOfUnknown()
        }

    override fun interrupt() {
        recordUnitCall(
            event = { metadata -> KSolverLifecycleEvent(KSolverLifecycleEventKind.INTERRUPT, metadata) },
        ) {
            delegate.interrupt()
        }
    }

    override fun close() {
        recordUnitCall(
            onSuccess = { state = KSolverProfileState.CLOSED },
            onFailure = { state = KSolverProfileState.CLOSE_FAILED },
            event = { metadata -> KSolverLifecycleEvent(KSolverLifecycleEventKind.CLOSE, metadata) },
        ) {
            delegate.close()
        }
    }

    private fun addAssertions(count: Int) {
        val countAsLong = count.toLong()
        assertionCountsByScope[assertionCountsByScope.lastIndex] += countAsLong
        totalAssertionCount += countAsLong
    }

    private inline fun recordUnitCall(
        crossinline onSuccess: () -> Unit = {},
        crossinline onFailure: (Throwable) -> Unit = {},
        crossinline event: (KSolverCallMetadata) -> KSolverProfileEvent,
        block: () -> Unit,
    ) {
        recordResultCall(
            onSuccess = { onSuccess() },
            onFailure = onFailure,
            event = { _, metadata -> event(metadata) },
            block = block,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> recordResultCall(
        crossinline onSuccess: (T) -> Unit = {},
        crossinline onFailure: (Throwable) -> Unit = {},
        crossinline event: (T?, KSolverCallMetadata) -> KSolverProfileEvent,
        block: () -> T,
    ): T {
        val call = beginCall()
        val result = try {
            block()
        } catch (error: Throwable) {
            runCatching {
                synchronized(eventsLock) {
                    onFailure(error)
                    addEvent(
                        event(
                            null,
                            call.metadata(
                                outcome = KSolverCallOutcome.FAILURE,
                                failureType = error.javaClass.name,
                                failureMessage = error.message,
                            ),
                        ),
                    )
                }
            }
            throw error
        }

        runCatching {
            synchronized(eventsLock) {
                onSuccess(result)
                addEvent(event(result, call.metadata(KSolverCallOutcome.SUCCESS)))
            }
        }
        return result
    }

    private fun beginCall(): ProfiledCall =
        ProfiledCall(
            sequenceNumber = nextSequenceNumber.getAndIncrement(),
            startedAtNanos = System.nanoTime(),
            task = currentTask.get()?.let { it.copy(attributes = it.attributes.toMap()) },
        )

    private fun ProfiledCall.metadata(
        outcome: KSolverCallOutcome,
        failureType: String? = null,
        failureMessage: String? = null,
    ): KSolverCallMetadata {
        val finishedAtNanos = System.nanoTime()
        return KSolverCallMetadata(
            sequenceNumber = sequenceNumber,
            startedAfterSession = elapsedDuration(startedAtNanos),
            duration = (finishedAtNanos - startedAtNanos).coerceAtLeast(0).nanoseconds,
            outcome = outcome,
            task = task,
            stackDepth = assertionCountsByScope.lastIndex,
            activeAssertionCount = assertionCountsByScope.sum(),
            totalAssertionCount = totalAssertionCount,
            failureType = failureType,
            failureMessage = failureMessage,
        )
    }

    private fun elapsedDuration(now: Long): Duration =
        (now - startedAtNanos).coerceAtLeast(0).nanoseconds

    private fun addEvent(event: KSolverProfileEvent) {
        if (events.size < maxRecordedEvents) {
            events += event
        } else {
            droppedEventCount++
        }
    }

    private data class ProfiledCall(
        val sequenceNumber: Long,
        val startedAtNanos: Long,
        val task: KSolverTask?,
    )

    companion object {
        const val DEFAULT_MAX_RECORDED_EVENTS = 1_000_000
    }
}

/** Reference factory for the experimental in-memory profiler implementation. */
object KRecordingSolverProfilerFactory : KSolverProfilerFactory {
    override fun <Config : KSolverConfiguration> wrap(
        delegate: KSolver<Config>,
        request: KSolverProfilerRequest,
    ): KSolverProfiler<Config> =
        KRecordingSolverProfiler(
            delegate = delegate,
            solver = request.solver,
            profileId = request.profileId,
            runId = request.runId,
            parentProfileId = request.parentProfileId,
            initialConfiguration = request.initialConfiguration,
            profileAttributes = request.attributes,
            maxRecordedEvents = request.maxRecordedEvents,
        )
}
