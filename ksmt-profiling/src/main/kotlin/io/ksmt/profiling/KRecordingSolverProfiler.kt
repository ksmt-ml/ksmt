package io.ksmt.profiling

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.time.Duration

/**
 * Transparent in-memory [KSolver] decorator.
 *
 * The decorator never serializes expressions and never invokes solver operations that the caller
 * did not request. In particular, the reason for `UNKNOWN` is attached to the preceding check only
 * when the caller invokes [reasonOfUnknown].
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
    private val startedAtEpochMillis = System.currentTimeMillis()
    private val startedAtNanos = System.nanoTime()
    private val nextSequenceNumber = AtomicLong()
    private val nextCheckId = AtomicLong()
    private val eventsLock = Any()
    private val events = arrayListOf<KSolverProfileEvent>()
    private val assertionCountsByScope = arrayListOf(0L)
    private val currentTask = ThreadLocal<KSolverTask?>()

    private var totalAssertionCount = 0L
    private var droppedEventCount = 0L
    private var state = KSolverProfileState.ACTIVE

    init {
        require(maxRecordedEvents > 0) { "Maximum recorded event count must be positive" }
    }

    override fun snapshot(): KSolverProfile = synchronized(eventsLock) {
        val duration = elapsedNanos(System.nanoTime())
        val attributes =
            if (droppedEventCount == 0L) {
                profileAttributes
            } else {
                profileAttributes +
                    mapOf(
                        PROFILE_TRUNCATED_ATTRIBUTE to "true",
                        DROPPED_EVENT_COUNT_ATTRIBUTE to droppedEventCount.toString(),
                    )
            }

        KSolverProfile(
            schemaVersion = KSolverProfile.CURRENT_SCHEMA_VERSION,
            profileId = profileId,
            runId = runId,
            parentProfileId = parentProfileId,
            state = state,
            solver = solver,
            startedAtEpochMillis = startedAtEpochMillis,
            observedDurationNanos = duration,
            initialConfiguration = initialConfiguration,
            events = events.sortedBy { it.metadata.sequenceNumber }.toList(),
            attributes = attributes,
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
            event = { status, metadata ->
                KSolverCheckEvent(
                    checkId = checkId,
                    timeoutNanos = timeout.toProfileNanos(),
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
            event = { status, metadata ->
                KSolverCheckEvent(
                    checkId = checkId,
                    assumptions = assumptions.toList(),
                    timeoutNanos = timeout.toProfileNanos(),
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
            event = { _, metadata -> KSolverResultAccessEvent(KSolverResultAccessKind.MODEL, metadata) },
        ) {
            delegate.model()
        }

    override fun unsatCore(): List<KExpr<KBoolSort>> =
        recordResultCall(
            event = { _, metadata -> KSolverResultAccessEvent(KSolverResultAccessKind.UNSAT_CORE, metadata) },
        ) {
            delegate.unsatCore()
        }

    override fun reasonOfUnknown(): String {
        val reason =
            recordResultCall(
                event = { _, metadata ->
                    KSolverResultAccessEvent(KSolverResultAccessKind.REASON_OF_UNKNOWN, metadata)
                },
            ) {
                delegate.reasonOfUnknown()
            }

        synchronized(eventsLock) {
            val checkIndex = events.indexOfLast {
                it is KSolverCheckEvent && it.status == KSolverStatus.UNKNOWN && it.reasonOfUnknown == null
            }
            if (checkIndex >= 0) {
                val check = events[checkIndex] as KSolverCheckEvent
                events[checkIndex] = check.copy(reasonOfUnknown = reason)
            }
        }
        return reason
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
        return try {
            val result = block()
            synchronized(eventsLock) {
                onSuccess(result)
                addEvent(event(result, call.metadata(KSolverCallOutcome.SUCCESS)))
            }
            result
        } catch (error: Throwable) {
            synchronized(eventsLock) {
                onFailure(error)
                addEvent(
                    event(
                        null,
                        call.metadata(
                            outcome = KSolverCallOutcome.FAILURE,
                            failureType = error.javaClass.name,
                        ),
                    ),
                )
            }
            throw error
        }
    }

    private fun beginCall(): ProfiledCall =
        ProfiledCall(
            sequenceNumber = nextSequenceNumber.getAndIncrement(),
            startedAtNanos = System.nanoTime(),
            task = currentTask.get(),
        )

    private fun ProfiledCall.metadata(
        outcome: KSolverCallOutcome,
        failureType: String? = null,
    ): KSolverCallMetadata {
        val finishedAtNanos = System.nanoTime()
        return KSolverCallMetadata(
            sequenceNumber = sequenceNumber,
            startedAfterSessionNanos = elapsedNanos(startedAtNanos),
            durationNanos = (finishedAtNanos - startedAtNanos).coerceAtLeast(0),
            outcome = outcome,
            task = task,
            stackDepth = assertionCountsByScope.lastIndex,
            activeAssertionCount = assertionCountsByScope.sum(),
            totalAssertionCount = totalAssertionCount,
            failureType = failureType,
        )
    }

    private fun elapsedNanos(now: Long): Long = (now - startedAtNanos).coerceAtLeast(0)

    private fun Duration.toProfileNanos(): Long? =
        if (this == Duration.INFINITE) null else inWholeNanoseconds

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
        const val PROFILE_TRUNCATED_ATTRIBUTE = "ksmt.profile.truncated"
        const val DROPPED_EVENT_COUNT_ATTRIBUTE = "ksmt.profile.dropped-event-count"
    }
}
