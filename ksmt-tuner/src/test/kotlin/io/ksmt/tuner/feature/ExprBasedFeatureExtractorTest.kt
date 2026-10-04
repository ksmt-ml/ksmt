package io.ksmt.tuner.feature

import io.ksmt.KContext
import io.ksmt.expr.KExpr
import io.ksmt.sort.KBoolSort
import io.ksmt.utils.mkConst
import kotlin.test.Test
import kotlin.test.assertEquals

class ExprBasedFeatureExtractorTest {

    private val extractor = ExprBasedFeatureExtractor(FeatureSchema.DEFAULT)

    @Test
    fun extractsMixedTheoryFeatures() = with(KContext()) {
        val fa = boolSort.mkConst("a")
        val fb = boolSort.mkConst("b")
        val fc = boolSort.mkConst("c")
        val x = mkBv32Sort().mkConst("x")
        val y = mkBv32Sort().mkConst("y")
        val i = intSort.mkConst("i")

        val bvEq = mkEq(mkBvAddExpr(x, y), mkBv(value = 7))
        val and1 = mkAnd(fa, fb, flat = false, order = false)
        val notOr = mkNot(mkOr(fa, fc, flat = false, order = false))
        val ite = mkIte(fc, and1, notOr)
        val arithGe = i ge 0.expr
        val distinct = mkDistinct(listOf(fa, fb, fc))
        val formula = mkAnd(listOf(bvEq, ite, arithGe, distinct), flat = false, order = false)

        val features = extractor.extract(listOf(formula))

        // BV and integer theories are present.
        assertEquals(1.0, features.get("usesBv"))
        assertEquals(1.0, features.get("usesArithInt"))
        assertEquals(0.0, features.get("usesFp"))
        assertEquals(0.0, features.get("usesArray"))
        assertEquals(0.0, features.get("usesUf"))
        assertEquals(2.0, features.get("theoryCount"))

        // No quantifiers in this formula.
        assertEquals(0.0, features.get("hasQuantifiers"))
        assertEquals(0.0, features.get("numQuantifiers"))

        // Operator counters.
        assertEquals(2.0, features.get("numAnd"))      // and1 + top-level and
        assertEquals(1.0, features.get("numOr"))
        assertEquals(1.0, features.get("numNot"))
        assertEquals(1.0, features.get("numIte"))
        assertEquals(1.0, features.get("numEq"))
        assertEquals(1.0, features.get("numDistinct"))

        // Bit-vector specifics.
        assertEquals(1.0, features.get("numBvOps"))   // bvAdd
        assertEquals(32.0, features.get("maxBvWidth"))

        // Structure.
        assertEquals(5.0, features.get("maxDepth"))
        assertEquals(17.0, features.get("numNodes"))
    }

    @Test
    fun detectsQuantifiersAndNestingDepth() = with(KContext()) {
        val n = intSort.mkConst("n")
        val k = intSort.mkConst("k")
        val inner = mkUniversalQuantifier(k gt 0.expr, listOf(k.decl))
        val outer = mkExistentialQuantifier(inner, listOf(n.decl))

        val features = extractor.extract(listOf(outer))

        assertEquals(1.0, features.get("hasQuantifiers"))
        assertEquals(2.0, features.get("numQuantifiers"))
        assertEquals(2.0, features.get("quantifierDepth"))
        assertEquals(1.0, features.get("usesArithInt"))
    }

    @Test
    fun aggregatesMultipleFormulas() = with(KContext()) {
        val a = boolSort.mkConst("a")
        val b = boolSort.mkConst("b")
        val f1 = mkAnd(a, b, flat = false, order = false)
        val f2 = mkOr(a, b, flat = false, order = false)

        val features = extractor.extract(listOf(f1, f2))

        assertEquals(1.0, features.get("numAnd"))
        assertEquals(1.0, features.get("numOr"))
    }

    @Test
    fun emptyFormulaListFails() {
        val empty = emptyList<KExpr<KBoolSort>>()
        kotlin.test.assertFailsWith<IllegalArgumentException> { extractor.extract(empty) }
    }
}
