package io.ksmt.tuner

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.KSolverUnsupportedParameterException
import io.ksmt.solver.cvc5.KCvc5Solver
import io.ksmt.tuner.feature.ExprBasedFeatureExtractor
import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.model.StubTunerModel
import io.ksmt.tuner.model.TunerModel
import io.ksmt.tuner.params.SolverTuningParams
import io.ksmt.tuner.params.TuningParam
import io.ksmt.utils.mkConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end test mirroring [Z3ApplicationTest] but against the cvc5 solver
 * (native binaries). Exercises: formula -> feature extraction -> model predict ->
 * configure a real cvc5 solver -> check returns a satisfiability result.
 *
 * cvc5's [io.ksmt.solver.cvc5.KCvc5SolverConfiguration] is string-only, so the
 * stub's `IntParam` would be rejected. A tiny test-only model returning a
 * `StringParam` stands in instead.
 */
class Cvc5ApplicationTest {

    @Test
    fun tuneAndCheckSatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")
        val bv = mkBv32Sort().mkConst("v")
        val formula = mkAnd(
            listOf(
                mkAnd(a, b, flat = false, order = false),
                bv eq mkBv(value = 10)
            ),
            flat = false,
            order = false
        )

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), Cvc5StringTunerModel)
        val params = tuner.selectParams(listOf(formula))

        // The test-only model returns a single cvc5-valid string parameter.
        assertEquals(1, params.params.size)
        assertTrue(params.params.first() is TuningParam.StringParam)
        val stringParam = params.params.first() as TuningParam.StringParam
        assertEquals("logic", stringParam.name)
        assertEquals("ALL", stringParam.value)

        KCvc5Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            assertEquals(KSolverStatus.SAT, solver.check())
        }
    }

    @Test
    fun tuneAndCheckUnsatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val formula = mkAnd(a, mkNot(a), flat = false, order = false)

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), Cvc5StringTunerModel)

        KCvc5Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            assertEquals(KSolverStatus.UNSAT, solver.check())
        }
    }

    @Test
    fun stubIntParamIsRejectedByCvc5() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val formula = mkAnd(a, mkNot(a), flat = false, order = false)

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), StubTunerModel())

        assertFailsWith<KSolverUnsupportedParameterException> {
            KCvc5Solver(this).use { solver ->
                tuner.configure(solver, listOf(formula))
            }
        }
        Unit
    }
}

/** Test-only model that returns a cvc5-safe string parameter (`logic = ALL`). */
private object Cvc5StringTunerModel : TunerModel {
    override val name: String = "cvc5-string-stub"

    override fun predict(features: FormulaFeatures): SolverTuningParams =
        SolverTuningParams(listOf(TuningParam.StringParam("logic", "ALL")))
}