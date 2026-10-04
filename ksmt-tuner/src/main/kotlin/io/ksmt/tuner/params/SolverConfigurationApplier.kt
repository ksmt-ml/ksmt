package io.ksmt.tuner.params

import io.ksmt.solver.KSolverConfiguration

/**
 * Applies tuning parameters to an [io.ksmt.solver.KSolverConfiguration] via its
 * typed setters, dispatching on the parameter type.
 *
 * The solver is free to reject a given type (e.g. cvc5/Yices accept only
 * string parameters). Such rejections surface as exceptions from the
 * underlying setter's contract and are intentionally propagated, never swallowed.
 */
fun SolverTuningParams.applyTo(config: KSolverConfiguration) {
    params.forEach { param ->
        when (param) {
            is TuningParam.IntParam -> config.setIntParameter(param.name, param.value)
            is TuningParam.BoolParam -> config.setBoolParameter(param.name, param.value)
            is TuningParam.StringParam -> config.setStringParameter(param.name, param.value)
            is TuningParam.DoubleParam -> config.setDoubleParameter(param.name, param.value)
        }
    }
}
