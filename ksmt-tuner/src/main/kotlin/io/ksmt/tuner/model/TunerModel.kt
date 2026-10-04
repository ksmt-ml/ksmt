package io.ksmt.tuner.model

import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.params.SolverTuningParams

/**
 * Predicts tuning parameters from formula features.
 */
interface TunerModel {
    val name: String

    /**
     * @param features schema-aligned feature vector.
     * @return parameters to apply to a solver configuration.
     */
    fun predict(features: FormulaFeatures): SolverTuningParams
}
