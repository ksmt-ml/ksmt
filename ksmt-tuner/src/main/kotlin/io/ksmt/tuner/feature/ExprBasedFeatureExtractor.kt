package io.ksmt.tuner.feature

import io.ksmt.KContext
import io.ksmt.expr.KAndExpr
import io.ksmt.expr.KApp
import io.ksmt.expr.KArrayConst
import io.ksmt.expr.KArrayLambdaBase
import io.ksmt.expr.KArraySelectBase
import io.ksmt.expr.KArrayStoreBase
import io.ksmt.expr.KConst
import io.ksmt.expr.KDistinctExpr
import io.ksmt.expr.KEqExpr
import io.ksmt.expr.KExistentialQuantifier
import io.ksmt.expr.KExpr
import io.ksmt.expr.KFpValue
import io.ksmt.expr.KFunctionApp
import io.ksmt.expr.KFunctionAsArray
import io.ksmt.expr.KIntNumExpr
import io.ksmt.expr.KInterpretedValue
import io.ksmt.expr.KIteExpr
import io.ksmt.expr.KNotExpr
import io.ksmt.expr.KOrExpr
import io.ksmt.expr.KRealNumExpr
import io.ksmt.expr.KUniversalQuantifier
import io.ksmt.expr.transformer.KNonRecursiveTransformer
import io.ksmt.sort.KArray2Sort
import io.ksmt.sort.KArray3Sort
import io.ksmt.sort.KArrayNSort
import io.ksmt.sort.KArraySort
import io.ksmt.sort.KArraySortBase
import io.ksmt.sort.KBoolSort
import io.ksmt.sort.KBvSort
import io.ksmt.sort.KFpRoundingModeSort
import io.ksmt.sort.KFpSort
import io.ksmt.sort.KIntSort
import io.ksmt.sort.KRealSort
import io.ksmt.sort.KSort
import io.ksmt.sort.KSortVisitor
import io.ksmt.sort.KUninterpretedSort

/**
 * Default [FormulaFeatureExtractor] implementation.
 *
 * Walks the formulas with a [KNonRecursiveTransformer] and aggregates the
 * counters defined by [schema] (see [FeatureSchema.DEFAULT]).
 */
class ExprBasedFeatureExtractor(private val schema: FeatureSchema = FeatureSchema.DEFAULT) : FormulaFeatureExtractor {

    override fun extract(exprs: List<KExpr<KBoolSort>>): FormulaFeatures {
        require(exprs.isNotEmpty()) { "Feature extraction requires at least one formula" }
        val ctx = exprs.first().ctx
        require(exprs.all { it.ctx === ctx }) { "All formulas must belong to the same context" }

        val collector = FeatureCollector(ctx, schema)
        exprs.forEach { collector.apply(it) }
        return collector.features()
    }

    private class FeatureCollector(
        override val ctx: KContext,
        private val schema: FeatureSchema
    ) : KNonRecursiveTransformer(ctx) {

        private val builder = FormulaFeatures.Builder.forSchema(schema)
        private val sortRequirementCollector = Sort2TunerFeature()

        private val depths = HashMap<KExpr<*>, Int>()
        private val quantifierDepths = HashMap<KExpr<*>, Int>()

        private var hasQuantifiers = false

        override fun <T : KSort> transformExpr(expr: KExpr<T>): KExpr<T> {
            expr.sort.accept(sortRequirementCollector)
            builder.increment("numNodes")

            collectLeafCounters(expr)
            collectAppCounters(expr)
            countArrayOps(expr)
            trackDepths(expr)

            return super.transformExpr(expr)
        }

        private fun collectLeafCounters(expr: KExpr<*>) {
            when (expr) {
                is KConst<*> -> builder.increment("numSymbols")
                is KIntNumExpr, is KRealNumExpr -> builder.increment("numConstants")
                is KFpValue<*> -> builder.increment("numFpConsts")
                is KExistentialQuantifier, is KUniversalQuantifier -> {
                    hasQuantifiers = true
                    builder.increment("numQuantifiers")
                }
                else -> Unit
            }
        }

        private fun collectAppCounters(expr: KExpr<*>) {
            if (expr !is KApp<*, *> || expr is KInterpretedValue<*> || expr is KConst<*>) {
                return
            }

            builder.increment("numApp")
            countBoolOps(expr)

            if (expr.sort is KBvSort) {
                builder.increment("numBvOps")
            }
            if (expr.sort is KFpSort) {
                builder.increment("numFpOps")
            }
            if (expr is KFunctionApp<*> && expr.args.isNotEmpty()) {
                sortRequirementCollector.markUf()
            }
        }

        private fun countBoolOps(expr: KApp<*, *>) {
            when (expr) {
                is KAndExpr -> builder.increment("numAnd")
                is KOrExpr -> builder.increment("numOr")
                is KNotExpr -> builder.increment("numNot")
                is KIteExpr<*> -> builder.increment("numIte")
                is KEqExpr<*> -> builder.increment("numEq")
                is KDistinctExpr<*> -> builder.increment("numDistinct")
                else -> Unit
            }
        }

        private fun countArrayOps(expr: KExpr<*>) {
            when (expr) {
                is KArrayStoreBase<*, *>,
                is KArraySelectBase<*, *>,
                is KArrayLambdaBase<*, *>,
                is KArrayConst<*, *>,
                is KFunctionAsArray<*, *> -> builder.increment("numArrayOps")
                else -> Unit
            }
        }

        private fun trackDepths(expr: KExpr<*>) {
            val children = childrenOf(expr)
            val maxChildDepth = if (children.isEmpty()) 0 else (children.maxOf { depths[it] ?: 0 })
            val depth = maxChildDepth + 1
            depths[expr] = depth
            builder.updateMax("maxDepth", depth.toDouble())

            val maxChildQuantifierDepth =
                if (children.isEmpty()) 0 else (children.maxOf { quantifierDepths[it] ?: 0 })
            val quantifierDepth =
                if (isQuantifier(expr)) maxChildQuantifierDepth + 1 else maxChildQuantifierDepth
            quantifierDepths[expr] = quantifierDepth
            builder.updateMax("quantifierDepth", quantifierDepth.toDouble())

            if (expr.sort is KBvSort) {
                builder.updateMax("maxBvWidth", (expr.sort as KBvSort).sizeBits.toDouble())
            }
        }

        private fun isQuantifier(expr: KExpr<*>): Boolean =
            expr is KExistentialQuantifier || expr is KUniversalQuantifier

        private fun childrenOf(expr: KExpr<*>): List<KExpr<*>> = when (expr) {
            is KApp<*, *> -> expr.args.unchecked()
            is KExistentialQuantifier -> listOf(expr.body)
            is KUniversalQuantifier -> listOf(expr.body)
            is KArrayLambdaBase<*, *> -> listOf(expr.body)
            else -> emptyList()
        }

        internal fun features(): FormulaFeatures {
            builder.set("hasQuantifiers", if (hasQuantifiers) 1.0 else 0.0)
            builder.set("theoryCount", sortRequirementCollector.theoryCount().toDouble())
            return builder.build()
        }

        private inner class Sort2TunerFeature : KSortVisitor<Unit> {
            private var usesBv = false
            private var usesFp = false
            private var usesArithInt = false
            private var usesArithReal = false
            private var usesArray = false
            private var usesUf = false

            fun theoryCount(): Int =
                listOf(usesBv, usesFp, usesArithInt, usesArithReal, usesArray, usesUf).count { it }

            fun markUf() {
                usesUf = true
                builder.set("usesUf", 1.0)
            }

            override fun visit(sort: KBoolSort) {
                // Nothing to do: boolean sort is not a theory.
            }

            override fun visit(sort: KIntSort) {
                setFlag("usesArithInt") { usesArithInt = true }
            }

            override fun visit(sort: KRealSort) {
                setFlag("usesArithReal") { usesArithReal = true }
            }

            override fun <S : KBvSort> visit(sort: S) {
                setFlag("usesBv") { usesBv = true }
            }

            override fun <S : KFpSort> visit(sort: S) {
                setFlag("usesFp") { usesFp = true }
            }

            override fun <D : KSort, R : KSort> visit(sort: KArraySort<D, R>) {
                setFlag("usesArray") { usesArray = true }
                visitArray(sort)
            }

            override fun <D0 : KSort, D1 : KSort, R : KSort> visit(sort: KArray2Sort<D0, D1, R>) {
                setFlag("usesArray") { usesArray = true }
                visitArray(sort)
            }

            override fun <D0 : KSort, D1 : KSort, D2 : KSort, R : KSort> visit(sort: KArray3Sort<D0, D1, D2, R>) {
                setFlag("usesArray") { usesArray = true }
                visitArray(sort)
            }

            override fun <R : KSort> visit(sort: KArrayNSort<R>) {
                setFlag("usesArray") { usesArray = true }
                visitArray(sort)
            }

            override fun visit(sort: KFpRoundingModeSort) {
                setFlag("usesFp") { usesFp = true }
            }

            override fun visit(sort: KUninterpretedSort) = markUf()

            private fun <S : KArraySortBase<R>, R : KSort> visitArray(sort: S) {
                sort.range.accept(this)
                sort.domainSorts.forEach { it.accept(this) }
            }

            private inline fun setFlag(column: String, mark: () -> Unit) {
                mark()
                builder.set(column, 1.0)
            }
        }
    }
}

/**
 * Internal helper to fix the generic variance of [KApp] argument lists.
 */
@Suppress("UNCHECKED_CAST")
private fun <R> List<*>.unchecked(): List<R> = this as List<R>
