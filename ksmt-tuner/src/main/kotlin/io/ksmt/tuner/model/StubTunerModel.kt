package io.ksmt.tuner.model

import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.params.SolverTuningParams
import io.ksmt.tuner.params.TuningParam

/**
 * Fixed-behavior [TunerModel] used to exercise the pipeline without a trained model.
 *
 * It ignores the input features and always returns a static parameter set that is
 * valid for the Z3 solver, e.g. `IntParam("random_seed", 42)`.
 */
class StubTunerModel : TunerModel {
    private companion object {
        const val RANDOM_SEED: Int = 42
    }

    override val name: String = "stub"

    override fun predict(features: FormulaFeatures): SolverTuningParams {
        return SolverTuningParams(
            listOf(
                TuningParam.IntParam("random_seed", RANDOM_SEED)
            )
        )
    }
}
