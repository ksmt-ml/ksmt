package io.ksmt.profiling

import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort
import java.time.Instant
import kotlin.time.Duration

/** Identifies the solver implementation whose workload was observed. */
data class KSolverDescriptor(
    val name: String,
    val version: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/**
 * Application-defined context attached to solver calls made for one logical unit of work.
 *
 * A task does not affect solver behavior. It lets consumers group calls by an application
 * concept such as a symbolic state, method, request, or fork without adding that concept to the
 * generic KSMT API.
 */
data class KSolverTask(
    val id: String,
    val kind: String? = null,
    val attributes: Map<String, String> = emptyMap(),
)

/** Describes what configuration existed when the profiled solver session was created. */
enum class KSolverConfigurationBase {
    /** Native defaults of the solver version declared in [KSolverDescriptor]. */
    SOLVER_DEFAULTS,

    /** The complete base configuration cannot be reconstructed. */
    UNKNOWN,
}

/** An observed solver configuration value. It has no application or merge semantics. */
sealed class KSolverConfigurationValue {
    data class BoolValue(val value: Boolean) : KSolverConfigurationValue()
    data class IntValue(val value: Int) : KSolverConfigurationValue()
    data class StringValue(val value: String) : KSolverConfigurationValue()
    data class DoubleValue(val value: Double) : KSolverConfigurationValue()
}

/** Native defaults or an unknown base, followed by the explicit overrides observed by KSMT. */
data class KSolverConfigurationSnapshot(
    val base: KSolverConfigurationBase,
    val overrides: Map<String, KSolverConfigurationValue> = emptyMap(),
)

/** Whether the observed solver call returned normally or threw. */
enum class KSolverCallOutcome {
    SUCCESS,
    FAILURE,
}

/** Timing, state, task, and failure metadata shared by every recorded solver event. */
data class KSolverCallMetadata(
    val sequenceNumber: Long,
    val startedAfterSession: Duration,
    val duration: Duration,
    val outcome: KSolverCallOutcome,
    val task: KSolverTask?,
    val stackDepth: Int,
    val activeAssertionCount: Long,
    val totalAssertionCount: Long,
    val failureType: String? = null,
    val failureMessage: String? = null,
)

/** One ordered operation in an incremental solver session. */
sealed interface KSolverProfileEvent {
    val metadata: KSolverCallMetadata
}

/** An inspectable configuration change. Null overrides denote an opaque backend-specific change. */
data class KSolverConfigureEvent(
    val overrides: Map<String, KSolverConfigurationValue>?,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

/** A formula added by one single-expression solver call. */
data class KSolverAssertEvent(
    val expression: KExpr<KBoolSort>,
    val tracked: Boolean,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

/** Formulas added by one bulk solver call. */
data class KSolverAssertBatchEvent(
    val expressions: List<KExpr<KBoolSort>>,
    val tracked: Boolean,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

data class KSolverPushEvent(
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

data class KSolverPopEvent(
    val levels: UInt,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

/**
 * A satisfiability check over the active assertion stack and optional assumptions.
 *
 * The active formula can be reconstructed by replaying assertion and scope events up to this
 * event. [assumptions] are used only for this check. [status] is null when the call failed before
 * returning a solver status.
 */
data class KSolverCheckEvent(
    val checkId: Long,
    val assumptions: List<KExpr<KBoolSort>> = emptyList(),
    val timeout: Duration = Duration.INFINITE,
    val status: KSolverStatus? = null,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

enum class KSolverResultAccessKind {
    MODEL,
    UNSAT_CORE,
    REASON_OF_UNKNOWN,
}

/** Access to a result associated with [checkId], or null when no check could be associated. */
data class KSolverResultAccessEvent(
    val kind: KSolverResultAccessKind,
    val checkId: Long?,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

enum class KSolverLifecycleEventKind {
    INTERRUPT,
    CLOSE,
}

data class KSolverLifecycleEvent(
    val kind: KSolverLifecycleEventKind,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

/** Whether the observed solver session can still receive calls. */
enum class KSolverProfileState {
    ACTIVE,
    CLOSED,
    CLOSE_FAILED,
}

/**
 * In-memory snapshot of one solver session, from profiler construction through solver close.
 *
 * Expressions remain KSMT AST objects. Persistence and consumer-specific feature tensors are
 * outside this contract and belong to profile consumers. Implementations of [KSolverProfiler]
 * must return snapshots whose collection structure cannot change when recording continues.
 */
data class KSolverProfile(
    val schemaVersion: String,
    val profileId: String,
    val runId: String,
    val parentProfileId: String? = null,
    val state: KSolverProfileState,
    val solver: KSolverDescriptor,
    val startedAt: Instant,
    val observedDuration: Duration,
    val initialConfiguration: KSolverConfigurationSnapshot,
    val eventLimit: Int,
    val events: List<KSolverProfileEvent>,
    val droppedEventCount: Long,
    val attributes: Map<String, String> = emptyMap(),
) {
    val isTruncated: Boolean
        get() = droppedEventCount != 0L

    companion object {
        const val CURRENT_SCHEMA_VERSION = "ksmt-solver-profile-v0"
    }
}
