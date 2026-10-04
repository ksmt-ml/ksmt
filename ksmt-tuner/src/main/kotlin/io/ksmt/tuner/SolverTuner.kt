package io.ksmt.tuner

import io.ksmt.expr.KExpr
import io.ksmt.solver.KSolver
import io.ksmt.solver.KSolverConfiguration
import io.ksmt.sort.KBoolSort
import io.ksmt.tuner.feature.FormulaFeatureExtractor
import io.ksmt.tuner.model.TunerModel
import io.ksmt.tuner.params.SolverTuningParams
import io.ksmt.tuner.params.applyTo

/**
 * Glue between feature extraction, model prediction and solver configuration: the
 * "model outputs parameters -> solver proceeds as usual" boundary.
 */
class SolverTuner(
    private val featureExtractor: FormulaFeatureExtractor,
    private val model: TunerModel
) {
    /**
     * @return the parameters predicted for [exprs].
     */
    fun selectParams(exprs: List<KExpr<KBoolSort>>): SolverTuningParams =
        model.predict(featureExtractor.extract(exprs))

    /**
     * Configures [solver] with the parameters predicted for [exprs].
     */
    fun <C : KSolverConfiguration> configure(solver: KSolver<C>, exprs: List<KExpr<KBoolSort>>) {
        val params = selectParams(exprs)
        solver.configure { params.applyTo(this) }
    }
}
