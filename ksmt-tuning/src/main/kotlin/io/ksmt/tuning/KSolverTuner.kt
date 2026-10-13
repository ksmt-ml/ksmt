package io.ksmt.tuning

import io.ksmt.solver.KSolverConfiguration

sealed class KSolverParameterValue {
    data class BoolValue(val value: Boolean) : KSolverParameterValue()
    data class IntValue(val value: Int) : KSolverParameterValue()
    data class StringValue(val value: String) : KSolverParameterValue()
    data class DoubleValue(val value: Double) : KSolverParameterValue() {
        init {
            require(value.isFinite()) { "Solver parameter value must be finite" }
        }
    }
}

/** A portable set of solver parameters, represented through the existing KSMT configuration API. */
data class KSolverConfigurationPatch(
    val parameters: Map<String, KSolverParameterValue>,
) {
    init {
        require(parameters.keys.none { it.isBlank() }) { "Solver parameter names must not be blank" }
    }

    fun applyTo(configuration: KSolverConfiguration) {
        parameters.forEach { (name, value) ->
            when (value) {
                is KSolverParameterValue.BoolValue -> configuration.setBoolParameter(name, value.value)
                is KSolverParameterValue.IntValue -> configuration.setIntParameter(name, value.value)
                is KSolverParameterValue.StringValue -> configuration.setStringParameter(name, value.value)
                is KSolverParameterValue.DoubleValue -> configuration.setDoubleParameter(name, value.value)
            }
        }
    }

    fun merge(other: KSolverConfigurationPatch): KSolverConfigurationPatch =
        KSolverConfigurationPatch(parameters + other.parameters)

    companion object {
        val EMPTY = KSolverConfigurationPatch(emptyMap())
    }
}

sealed class KSolverTuningDecision {
    data class UseConfiguration(
        val configuration: KSolverConfigurationPatch,
        val tunerId: String,
        val attributes: Map<String, String> = emptyMap(),
    ) : KSolverTuningDecision() {
        init {
            require(tunerId.isNotBlank()) { "Tuner id must not be blank" }
        }
    }

    data class KeepCurrentConfiguration(val reason: String) : KSolverTuningDecision() {
        init {
            require(reason.isNotBlank()) { "Fallback reason must not be blank" }
        }
    }
}

/**
 * Converts an immutable workload profile into a configuration recommendation.
 *
 * A tuner never owns or mutates a solver. The application decides when profiling is complete and
 * applies a successful patch while constructing a solver for a subsequent workload run.
 *
 * The contract does not describe training, search, evaluation budgets, or solver replay. The first
 * production implementation performs inference with a pre-trained cvc5 model outside KSMT.
 */
fun interface KSolverTuner {
    fun tune(profile: KSolverProfile): KSolverTuningDecision
}
