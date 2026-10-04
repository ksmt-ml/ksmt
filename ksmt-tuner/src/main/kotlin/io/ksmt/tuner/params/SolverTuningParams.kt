package io.ksmt.tuner.params

/**
 * Immutable set of tuning parameters to be applied to a solver configuration.
 */
data class SolverTuningParams(val params: List<TuningParam> = emptyList()) {
    fun isEmpty(): Boolean = params.isEmpty()
}
