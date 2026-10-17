package io.ksmt.profiling

import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration

/** Immutable construction data for one bounded profiling decorator. */
data class KSolverProfilerRequest(
    val profileId: String,
    val runId: String,
    val parentProfileId: String? = null,
    val solver: KSolverDescriptor,
    val initialConfiguration: KSolverConfigurationSnapshot,
    val maxRecordedEvents: Int,
    val attributes: Map<String, String> = emptyMap(),
)

/** Creates transparent profiling decorators without exposing an implementation constructor. */
interface KSolverProfilerFactory {
    fun <Config : KSolverConfiguration> wrap(
        delegate: KSolver<Config>,
        request: KSolverProfilerRequest,
    ): KSolverProfiler<Config>
}

/**
 * Contract for a transparent [KSolver] decorator that observes one solver session.
 *
 * KSMT currently defines this boundary but does not provide an implementation. A future
 * implementation must preserve delegate behavior, retain asserted expressions and assumptions,
 * report calls in execution order, and never turn a successful delegate call into a failure
 * because recording or snapshot construction failed.
 */
interface KSolverProfiler<Config : KSolverConfiguration> : KSolver<Config> {
    /**
     * Returns a structurally independent snapshot of the observations recorded so far.
     *
     * Recording may continue after the snapshot is returned, but its collections must not change.
     */
    fun snapshot(): KSolverProfile

    /**
     * Attaches observational application context to calls made by [block].
     *
     * Nested blocks use the innermost task and restore the previous task even when [block] throws.
     */
    fun <T> withTask(task: KSolverTask, block: KSolverProfiler<Config>.() -> T): T
}
