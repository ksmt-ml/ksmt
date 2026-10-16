package io.ksmt.profiling

import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration

/**
 * Contract for a transparent [KSolver] decorator that records a bounded incremental workload.
 *
 * Implementations must preserve delegate behavior, retain asserted expressions and assumptions,
 * and report calls in execution order.
 */
interface KSolverProfiler<Config : KSolverConfiguration> : KSolver<Config> {
    /** Returns an immutable snapshot of the observations recorded so far. */
    fun snapshot(): KSolverProfile

    /** Attaches application-level task context to calls made by [block]. */
    fun <T> withTask(task: KSolverTask, block: KSolverProfiler<Config>.() -> T): T
}
