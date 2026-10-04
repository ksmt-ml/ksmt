package io.ksmt.tuner.feature

/**
 * Stable, versioned description of a flat numerical feature vector.
 *
 * The order and the number of columns is a hard contract: the same
 * [FeatureSchema] must be used both when producing features
 * ([FormulaFeatureExtractor]) and when consuming them
 * ([io.ksmt.tuner.model.TunerModel], in particular the ONNX model).
 * Changing the column layout without bumping the schema breaks the
 * agreement with any previously trained model.
 *
 * @param name schema identifier, e.g. `"v1"`.
 * @param dimension number of scalar features (must equal [columnNames].size).
 * @param columnNames canonical ordered list of feature column names.
 */
data class FeatureSchema(
    val name: String,
    val dimension: Int,
    val columnNames: List<String>
) {
    init {
        require(columnNames.size == dimension) {
            "Feature schema dimension=$dimension does not match column count=${columnNames.size}"
        }
        require(columnNames.distinct().size == columnNames.size) {
            "Feature schema column names must be unique: $columnNames"
        }
    }

    companion object {
        /**
         * Canonical feature schema. Single source of truth for the feature layout.
         * The order of the columns below must match the trained model's inputs.
         */
        val DEFAULT: FeatureSchema = runtimeSchema(
            "v1",
            "hasQuantifiers",
            "usesBv",
            "usesFp",
            "usesArithInt",
            "usesArithReal",
            "usesArray",
            "usesUf",
            "theoryCount",
            "numConstants",
            "numSymbols",
            "numApp",
            "numAnd",
            "numOr",
            "numNot",
            "numIte",
            "numEq",
            "numDistinct",
            "numBvOps",
            "maxBvWidth",
            "numArrayOps",
            "numFpOps",
            "numFpConsts",
            "numQuantifiers",
            "quantifierDepth",
            "maxDepth",
            "numNodes"
        )

        fun runtimeSchema(name: String, vararg columnNames: String): FeatureSchema =
            FeatureSchema(name = name, dimension = columnNames.size, columnNames = columnNames.toList())
    }
}
