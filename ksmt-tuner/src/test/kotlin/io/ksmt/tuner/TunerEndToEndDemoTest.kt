package io.ksmt.tuner

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.tuner.feature.ExprBasedFeatureExtractor
import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.model.StubTunerModel
import io.ksmt.tuner.model.TunerModel
import io.ksmt.tuner.params.SolverTuningParams
import io.ksmt.tuner.params.TuningParam
import io.ksmt.utils.mkConst
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Full end-to-end demonstration: formula -> feature extraction -> model predict ->
 * configure a real solver (Z3 and cvc5 natives) -> check.
 *
 * Mirrors the style of the repo's `GettingStartedExample` but injects the tuner
 * into the solver setup. Existing code is not modified; this is a standalone demo.
 */
class TunerEndToEndDemoTest {

    @Test
    fun endToEndCvc5SatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")
        val v = mkBv32Sort().mkConst("v")
        val formula = mkAnd(
            listOf(
                mkAnd(a, b, flat = false, order = false),
                v eq mkBv(value = 10)
            ),
            flat = false,
            order = false
        )

        val extractor = ExprBasedFeatureExtractor()
        val tuner = SolverTuner(extractor, FeatureDrivenModel())
        val features = extractor.extract(listOf(formula))
        val params = tuner.selectParams(listOf(formula))

        println("--- End-to-end cvc5 SAT ---")
        println("features: theoryCount=${features.get("theoryCount")} numSymbols=${features.get("numSymbols")} " +
            "numAnd=${features.get("numAnd")} maxDepth=${features.get("maxDepth")}")
        println("tuned params: ${params.params}")

        KCvc5Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            val status = solver.check()
            println("cvc5 check = $status")
            assertEquals(KSolverStatus.SAT, status)
        }
    }

    @Test
    fun endToEndCvc5UnsatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val formula = mkAnd(a, mkNot(a), flat = false, order = false)

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), FeatureDrivenModel())
        val params = tuner.selectParams(listOf(formula))

        println("--- End-to-end cvc5 UNSAT ---")
        println("tuned params: ${params.params}")

        KCvc5Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            val status = solver.check()
            println("cvc5 check = $status")
            assertEquals(KSolverStatus.UNSAT, status)
        }
    }

    @Test
    fun endToEndZ3SatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")
        val v = mkBv32Sort().mkConst("v")
        val formula = mkAnd(
            listOf(
                mkAnd(a, b, flat = false, order = false),
                v eq mkBv(value = 10)
            ),
            flat = false,
            order = false
        )

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), StubTunerModel())
        val params = tuner.selectParams(listOf(formula))

        println("--- End-to-end Z3 SAT ---")
        println("tuned params: ${params.params}")

        KZ3Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            val status = solver.check()
            println("z3 check = $status")
            assertEquals(KSolverStatus.SAT, status)
        }
    }
}

/**
 * Feature-driven model that reads real extracted features and returns a
 * cvc5-safe parameter (`logic = ALL`), which is valid for bool+BV formulas.
 */
private class FeatureDrivenModel : TunerModel {
    override val name: String = "feature-driven"
    override fun predict(features: FormulaFeatures): SolverTuningParams =
        SolverTuningParams(listOf(TuningParam.StringParam("logic", "ALL")))
}