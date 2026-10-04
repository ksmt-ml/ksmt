package io.ksmt.tuner.params

/**
 * A single typed tuning parameter produced by a [io.ksmt.tuner.model.TunerModel].
 *
 * Each variant maps onto one of the four typed setters of
 * [io.ksmt.solver.KSolverConfiguration].
 */
sealed interface TuningParam {
    /** Solver option name, e.g. `"random_seed"`. */
    val name: String

    data class IntParam(override val name: String, val value: Int) : TuningParam
    data class BoolParam(override val name: String, val value: Boolean) : TuningParam
    data class StringParam(override val name: String, val value: String) : TuningParam
    data class DoubleParam(override val name: String, val value: Double) : TuningParam
}
