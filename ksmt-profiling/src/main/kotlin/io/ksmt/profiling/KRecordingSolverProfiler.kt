package io.ksmt.profiling

import io.ksmt.expr.KExpr
import io.ksmt.solver.KModel
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Transparent in-memory [KSolver] decorator.
 *
 * The decorator never serializes expressions and never invokes solver operations that the caller
 * did not request. Result-access events refer to the last successfully completed check without
 * calling any additional solver operation.
 *
 * Ordering guarantees:
 * - Every solver call except [interrupt] holds [eventsLock] from the moment its sequence number is
 *   assigned until its event is stored. Calls from several threads therefore run one after another
 *   and events are stored in sequence order. When the event limit is reached, the retained events
 *   are an exact prefix of the call history.
 * - [interrupt] forwards to the delegate without waiting for the lock, so it can stop a check that
 *   is running in another thread. Its event is numbered and stored after that check has finished.
 * - [snapshot] waits for a running solver call to finish.
 *
 * The observed duration stops growing once a [close] call has finished. A repeated [close] never
 * turns a closed profile into a failed one.
 *
 * Recording problems ([Exception]s thrown while building or storing an event) are ignored and
 * never change what the caller of the solver sees.
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
    private val eventsLock = Any()
    private val currentTask = ThreadLocal<KSolverTask?>()

    // Everything below is guarded by [eventsLock]. The helper objects have no locks of their own.
    private val eventLog = EventLog(maxRecordedEvents)
    private val assertionStack = AssertionStack()
    private var nextSequenceNumber = 0L
    private var nextCheckId = 0L

    // Deliberately not reset by assert/push/pop: backends such as cvc5 keep the previous result
    // readable until the next check, so result accesses stay linked to the last successful check.
    private var lastSuccessfulCheckId: Long? = null
    private var state = KSolverProfileState.ACTIVE
    private var closedAtNanos: Long? = null

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
            observedDuration = elapsedSinceStart(closedAtNanos ?: System.nanoTime()),
            initialConfiguration =
                initialConfiguration.copy(overrides = initialConfiguration.overrides.toMap()),
            eventLimit = maxRecordedEvents,
            events = eventLog.toList(),
            droppedEventCount = eventLog.droppedCount,
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

    /**
     * Forwards the configuration change and records it as an opaque event (`overrides = null`):
     * the [configurator] lambda cannot be inspected without a solver-specific wrapper.
     *
     * Options that must be visible to profile consumers (for example the ones applied to a fresh
     * solver before any assertion) belong to [initialConfiguration].
     */
    override fun configure(configurator: Config.() -> Unit) {
        recordUnitCall(
            event = { metadata -> KSolverConfigureEvent(overrides = null, metadata) },
        ) {
            delegate.configure(configurator)
        }
    }

    override fun assert(expr: KExpr<KBoolSort>) {
        recordUnitCall(
            onSuccess = { assertionStack.add(1) },
            event = { metadata -> KSolverAssertEvent(expr, tracked = false, metadata) },
        ) {
            delegate.assert(expr)
        }
    }

    override fun assert(exprs: List<KExpr<KBoolSort>>) {
        recordUnitCall(
            onSuccess = { assertionStack.add(exprs.size) },
            event = { metadata -> KSolverAssertBatchEvent(exprs.toList(), tracked = false, metadata) },
        ) {
            delegate.assert(exprs)
        }
    }

    override fun assertAndTrack(expr: KExpr<KBoolSort>) {
        recordUnitCall(
            onSuccess = { assertionStack.add(1) },
            event = { metadata -> KSolverAssertEvent(expr, tracked = true, metadata) },
        ) {
            delegate.assertAndTrack(expr)
        }
    }

    override fun assertAndTrack(exprs: List<KExpr<KBoolSort>>) {
        recordUnitCall(
            onSuccess = { assertionStack.add(exprs.size) },
            event = { metadata -> KSolverAssertBatchEvent(exprs.toList(), tracked = true, metadata) },
        ) {
            delegate.assertAndTrack(exprs)
        }
    }

    override fun push() {
        recordUnitCall(
            onSuccess = { assertionStack.push() },
            event = { metadata -> KSolverPushEvent(metadata) },
        ) {
            delegate.push()
        }
    }

    override fun pop(n: UInt) {
        recordUnitCall(
            onSuccess = { assertionStack.pop(n) },
            event = { metadata -> KSolverPopEvent(n, metadata) },
        ) {
            delegate.pop(n)
        }
    }

    override fun check(timeout: Duration): KSolverStatus = synchronized(eventsLock) {
        // The lock is entered again inside recordResultCall (allowed for the same thread). Taking it
        // here makes checkId and the sequence number come from the same critical section.
        val checkId = nextCheckId++
        recordResultCall(
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
    ): KSolverStatus = synchronized(eventsLock) {
        val checkId = nextCheckId++
        recordResultCall(
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

    /**
     * Forwards to the delegate without taking [eventsLock], so it can stop a check that is running
     * in another thread. The event is numbered and stored after the delegate call returns.
     */
    override fun interrupt() {
        val startedAtNanos = System.nanoTime()
        val task = currentTaskSnapshot()
        val failure = runCatching { delegate.interrupt() }.exceptionOrNull()
        synchronized(eventsLock) {
            // Unlike other calls, the number is assigned after the delegate call returns.
            val call = ProfiledCall(nextSequenceNumber++, startedAtNanos, task)
            recordSafely {
                addEvent(
                    KSolverLifecycleEvent(
                        KSolverLifecycleEventKind.INTERRUPT,
                        call.metadata(
                            outcome = if (failure == null) KSolverCallOutcome.SUCCESS else KSolverCallOutcome.FAILURE,
                            failureType = failure?.javaClass?.name,
                            failureMessage = failure?.message,
                        ),
                    ),
                )
            }
        }
        if (failure != null) throw failure
    }

    override fun close() {
        recordUnitCall(
            onSuccess = { markClosed(KSolverProfileState.CLOSED) },
            onFailure = { markClosed(KSolverProfileState.CLOSE_FAILED) },
            event = { metadata -> KSolverLifecycleEvent(KSolverLifecycleEventKind.CLOSE, metadata) },
        ) {
            delegate.close()
        }
    }

    /**
     * A successful close always wins. A failed close only marks a session that is still active,
     * so a later repeated close cannot turn [KSolverProfileState.CLOSED] into a failure.
     * The observed duration stops at the first finished close.
     */
    private fun markClosed(newState: KSolverProfileState) {
        if (newState == KSolverProfileState.CLOSED || state == KSolverProfileState.ACTIVE) {
            state = newState
        }
        if (closedAtNanos == null) closedAtNanos = System.nanoTime()
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
    ): T = synchronized(eventsLock) {
        val call = beginCall()
        val result = try {
            block()
        } catch (error: Throwable) {
            recordSafely {
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
            throw error
        }

        recordSafely {
            onSuccess(result)
            addEvent(event(result, call.metadata(KSolverCallOutcome.SUCCESS)))
        }
        result
    }

    /**
     * Runs a recording step and drops its [Exception] so profiling never changes the outcome of
     * a delegate call. [Error]s such as OutOfMemoryError are not recording problems and propagate.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private inline fun recordSafely(step: () -> Unit) {
        try {
            step()
        } catch (_: Exception) {
            // Intentionally ignored, see the function documentation.
        }
    }

    /**
     * Gives a number, remembers the start time and the current task
     */
    private fun beginCall(): ProfiledCall =
        ProfiledCall(
            sequenceNumber = nextSequenceNumber++,
            startedAtNanos = System.nanoTime(),
            task = currentTaskSnapshot(),
        )

    private fun currentTaskSnapshot(): KSolverTask? =
        currentTask.get()?.let { it.copy(attributes = it.attributes.toMap()) }

    private fun ProfiledCall.metadata(
        outcome: KSolverCallOutcome,
        failureType: String? = null,
        failureMessage: String? = null,
    ): KSolverCallMetadata {
        val finishedAtNanos = System.nanoTime()
        return KSolverCallMetadata(
            sequenceNumber = sequenceNumber,
            startedAfterSession = elapsedSinceStart(startedAtNanos),
            duration = (finishedAtNanos - startedAtNanos).coerceAtLeast(0).nanoseconds,
            outcome = outcome,
            task = task,
            stackDepth = assertionStack.depth,
            activeAssertionCount = assertionStack.active,
            totalAssertionCount = assertionStack.total,
            failureType = failureType,
            failureMessage = failureMessage,
        )
    }

    /** Time between the profiler creation and [nowNanos], a value of [System.nanoTime]. */
    private fun elapsedSinceStart(nowNanos: Long): Duration =
        (nowNanos - startedAtNanos).coerceAtLeast(0).nanoseconds

    private fun addEvent(event: KSolverProfileEvent) = eventLog.add(event)

    private data class ProfiledCall(
        val sequenceNumber: Long,
        val startedAtNanos: Long,
        val task: KSolverTask?,
    )

    /**
     * Bounded list of events. Once [limit] events are stored, later events are only counted,
     * so the stored events are always an exact prefix of the call history.
     * Not thread-safe: the owner guards it with its own lock.
     */
    private class EventLog(private val limit: Int) {
        private val events = arrayListOf<KSolverProfileEvent>()

        /** Number of events that did not fit into the limit. */
        var droppedCount = 0L
            private set

        fun add(event: KSolverProfileEvent) {
            if (events.size < limit) {
                events += event
            } else {
                droppedCount++
            }
        }

        /** Returns a copy, so later [add] calls do not change it. */
        fun toList(): List<KSolverProfileEvent> = events.toList()
    }

    /**
     * Assertion counters that mirror the push/pop stack of the solver.
     *
     * The first element of [countsByScope] belongs to the base level, so [depth] is the number of
     * open scopes. [active] always equals the sum of [countsByScope]. [total] only grows.
     * Not thread-safe: the owner guards it with its own lock.
     */
    private class AssertionStack {
        private val countsByScope = arrayListOf(0L)

        /** Number of open scopes (zero when only the base level exists). */
        val depth: Int get() = countsByScope.lastIndex

        /** Assertions that are still in effect. */
        var active = 0L
            private set

        /** Assertions added since the session started, including already removed ones. */
        var total = 0L
            private set

        fun push() {
            countsByScope += 0L
        }

        /** Counts [count] assertions in the innermost scope. */
        fun add(count: Int) {
            val countAsLong = count.toLong()
            countsByScope[countsByScope.lastIndex] += countAsLong
            active += countAsLong
            total += countAsLong
        }

        /** Removes up to [n] scopes; the base level is never removed. */
        fun pop(n: UInt) {
            val removedScopeCount = minOf(n.toLong(), countsByScope.lastIndex.toLong()).toInt()
            repeat(removedScopeCount) {
                active -= countsByScope.removeAt(countsByScope.lastIndex)
            }
        }
    }

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
