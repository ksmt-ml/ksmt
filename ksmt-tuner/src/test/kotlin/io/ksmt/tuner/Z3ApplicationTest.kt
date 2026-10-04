package io.ksmt.tuner

import io.ksmt.KContext
import io.ksmt.solver.KSolverStatus
import io.ksmt.solver.z3.KZ3Solver
import io.ksmt.tuner.feature.ExprBasedFeatureExtractor
import io.ksmt.tuner.model.StubTunerModel
import io.ksmt.tuner.params.TuningParam
import io.ksmt.utils.mkConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test: formula -> feature extraction -> stub model -> configure a real
 * Z3 solver (native binaries) -> check returns a satisfiability result.
 * This exercises the full tuned path.
 */
class Z3ApplicationTest {

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

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), StubTunerModel())
        val params = tuner.selectParams(listOf(formula))

        // The stub returns a single Z3-valid integer parameter.
        assertEquals(1, params.params.size)
        assertTrue(params.params.first() is TuningParam.IntParam)

        KZ3Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            assertEquals(KSolverStatus.SAT, solver.check())
        }
    }

    @Test
    fun tuneAndCheckUnsatisfiableFormula() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val formula = mkAnd(a, mkNot(a), flat = false, order = false)

        val tuner = SolverTuner(ExprBasedFeatureExtractor(), StubTunerModel())

        KZ3Solver(this).use { solver ->
            tuner.configure(solver, listOf(formula))
            solver.assert(formula)
            assertEquals(KSolverStatus.UNSAT, solver.check())
        }
    }
}
