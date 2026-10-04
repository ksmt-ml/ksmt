package io.ksmt.tuner.feature

import io.ksmt.expr.KExpr
import io.ksmt.sort.KBoolSort

/**
 * Produces a flat, schema-aligned numerical feature vector from a list of formulas.
 */
fun interface FormulaFeatureExtractor {
    /**
     * @param exprs non-empty list of formulas to analyze.
     * @return features aligned with the extractor's [FeatureSchema].
     * @throws IllegalArgumentException if [exprs] is empty or expressions use
     * different contexts.
     */
    fun extract(exprs: List<KExpr<KBoolSort>>): FormulaFeatures
}
