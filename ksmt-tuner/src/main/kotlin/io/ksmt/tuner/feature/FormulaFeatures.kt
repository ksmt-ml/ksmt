package io.ksmt.tuner.feature

import kotlin.math.max

/**
 * Flat numerical representation of a set of formulas.
 *
 * The [schema] carries the canonical column order; [values] holds one scalar
 * per column ([values].size == [schema].dimension). This is the object handed
 * to the [io.ksmt.tuner.model.TunerModel] for prediction.
 */
class FormulaFeatures @JvmOverloads constructor(
    val schema: FeatureSchema,
    values: DoubleArray = DoubleArray(schema.dimension)
) {
    private val values: DoubleArray = values.copyOf()

    val size: Int get() = values.size

    fun get(index: Int): Double = values[index]

    fun get(columnName: String): Double {
        val index = indexOfColumn(columnName)
        return values[index]
    }

    /**
     * Builds the flat float tensor used as the ONNX model input with shape {1, N}.
     */
    fun toFloatArray(): FloatArray {
        validateDimensions()
        return FloatArray(values.size) { values[it].toFloat() }
    }

    fun toDoubleArray(): DoubleArray = values.copyOf()

    /**
     * Verifies that the vector is aligned with the [schema] dimension.
     */
    fun validateDimensions() {
        check(values.size == schema.dimension) {
            "Feature vector size=${values.size} does not match schema '${schema.name}' dimension=${schema.dimension}"
        }
    }

    internal fun indexOfColumn(columnName: String): Int {
        validateDimensions()
        val index = schema.columnNames.indexOf(columnName)
        require(index >= 0) { "Unknown feature column '$columnName', schema=${schema.name}" }
        return index
    }

    /**
     * Mutable accumulator aligned with [schema], used to construct a
     * [FormulaFeatures] instance. Consumers should not need it.
     */
    class Builder private constructor(private val schema: FeatureSchema) {
        private val values = DoubleArray(schema.dimension)

        fun set(columnName: String, value: Double): Builder {
            values[schema.columnNames.indexOf(columnName)] = value
            return this
        }

        fun increment(columnName: String, delta: Double = 1.0): Builder {
            values[schema.columnNames.indexOf(columnName)] += delta
            return this
        }

        fun updateMax(columnName: String, candidate: Double): Builder {
            val index = schema.columnNames.indexOf(columnName)
            values[index] = max(values[index], candidate)
            return this
        }

        fun build(): FormulaFeatures = FormulaFeatures(schema, values)

        companion object {
            fun forSchema(schema: FeatureSchema): Builder = Builder(schema)
        }
    }
}
