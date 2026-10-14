package io.ksmt.profiling

import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolverStatus
import io.ksmt.sort.KBoolSort

/** Identifies the solver implementation whose workload was observed. */
data class KSolverDescriptor(
    val name: String,
    val version: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "Solver name must not be blank" }
    }
}

/** Application-level context attached to calls made while a logical task is active. */
data class KSolverTask(
    val id: String,
    val kind: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Task id must not be blank" }
    }
}

/** Describes what configuration existed before the profiled workload run. */
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
    data class DoubleValue(val value: Double) : KSolverConfigurationValue() {
        init {
            require(value.isFinite()) { "Solver configuration value must be finite" }
        }
    }
}

/** Native defaults or an unknown base, followed by the explicit overrides observed by KSMT. */
data class KSolverConfigurationSnapshot(
    val base: KSolverConfigurationBase,
    val overrides: Map<String, KSolverConfigurationValue> = emptyMap(),
) {
    init {
        require(overrides.keys.none { it.isBlank() }) { "Solver parameter names must not be blank" }
    }
}

enum class KSolverCallOutcome {
    SUCCESS,
    FAILURE,
}

/** Timing, state, task, and failure metadata shared by every recorded solver event. */
data class KSolverCallMetadata(
    val sequenceNumber: Long,
    val startedAfterSessionNanos: Long,
    val durationNanos: Long,
    val outcome: KSolverCallOutcome,
    val task: KSolverTask?,
    val stackDepth: Int,
    val activeAssertionCount: Long,
    val totalAssertionCount: Long,
    val failureType: String? = null,
) {
    init {
        require(sequenceNumber >= 0) { "Sequence number must not be negative" }
        require(startedAfterSessionNanos >= 0) { "Call start must not be negative" }
        require(durationNanos >= 0) { "Call duration must not be negative" }
        require(stackDepth >= 0) { "Stack depth must not be negative" }
        require(activeAssertionCount >= 0) { "Active assertion count must not be negative" }
        require(totalAssertionCount >= activeAssertionCount) {
            "Total assertion count must not be smaller than active assertion count"
        }
        require(outcome == KSolverCallOutcome.FAILURE || failureType == null) {
            "A successful call must not have a failure type"
        }
    }
}

/** One ordered operation in an incremental solver session. */
sealed interface KSolverProfileEvent {
    val metadata: KSolverCallMetadata
}

/** An inspectable configuration change. Null overrides denote an opaque backend-specific change. */
data class KSolverConfigureEvent(
    val overrides: Map<String, KSolverConfigurationValue>?,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent {
    init {
        require(overrides?.keys?.none { it.isBlank() } != false) { "Solver parameter names must not be blank" }
    }
}

/** A formula added to the current assertion scope. */
data class KSolverAssertEvent(
    val expression: KExpr<KBoolSort>,
    val tracked: Boolean,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

data class KSolverPushEvent(
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent

data class KSolverPopEvent(
    val levels: UInt,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent {
    init {
        require(levels > 0u) { "Pop level count must be positive" }
    }
}

/**
 * A satisfiability check over the active assertion stack and optional assumptions.
 *
 * The active formula can be reconstructed by replaying assertion and scope events up to this
 * event. [assumptions] are used only for this check.
 */
data class KSolverCheckEvent(
    val assumptions: List<KExpr<KBoolSort>> = emptyList(),
    val timeoutNanos: Long? = null,
    val status: KSolverStatus? = null,
    val reasonOfUnknown: String? = null,
    override val metadata: KSolverCallMetadata,
) : KSolverProfileEvent {
    init {
        require(timeoutNanos == null || timeoutNanos >= 0) { "Timeout must not be negative" }
        require(reasonOfUnknown == null || status == KSolverStatus.UNKNOWN) {
            "Unknown reason requires UNKNOWN solver status"
        }
    }
}

enum class KSolverResultAccessKind {
    MODEL,
    UNSAT_CORE,
    REASON_OF_UNKNOWN,
}

data class KSolverResultAccessEvent(
    val kind: KSolverResultAccessKind,
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

/**
 * Immutable, in-memory profile of a bounded incremental solver workload.
 *
 * Expressions remain KSMT AST objects. Persistence and consumer-specific feature tensors are
 * outside this contract and belong to profile consumers.
 */
data class KSolverProfile(
    val schemaVersion: String,
    val solver: KSolverDescriptor,
    val startedAtEpochMillis: Long,
    val observedDurationNanos: Long,
    val initialConfiguration: KSolverConfigurationSnapshot,
    val events: List<KSolverProfileEvent>,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(schemaVersion.isNotBlank()) { "Profile schema version must not be blank" }
        require(observedDurationNanos >= 0) { "Observed duration must not be negative" }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = "ksmt-solver-profile-v0"
    }
}
