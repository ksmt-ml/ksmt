package io.ksmt.tuner.feature

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FormulaFeaturesTest {

    @Test
    fun zeroInitializedVectorHasSchemaDimension() {
        val features = FormulaFeatures.Builder.forSchema(FeatureSchema.DEFAULT).build()

        assertEquals(FeatureSchema.DEFAULT.dimension, features.size)
        features.validateDimensions()
    }

    @Test
    fun toFloatArrayPreservesOrderAndLength() {
        val builder = FormulaFeatures.Builder.forSchema(FeatureSchema.DEFAULT)
        builder.set("numApp", 3.0)
        builder.set("maxBvWidth", 64.0)
        val features = builder.build()

        val floats = features.toFloatArray()

        assertEquals(features.size, floats.size)
        assertEquals(3.0f, floats[features.indexOfColumn("numApp")])
        assertEquals(64.0f, floats[features.indexOfColumn("maxBvWidth")])
    }

    @Test
    fun getByNameReturnsValue() {
        val features = FormulaFeatures.Builder.forSchema(FeatureSchema.DEFAULT)
            .set("numAnd", 5.0)
            .build()

        assertEquals(5.0, features.get("numAnd"))
    }

    @Test
    fun getByUnknownNameFails() {
        val features = FormulaFeatures.Builder.forSchema(FeatureSchema.DEFAULT).build()

        assertFailsWith<IllegalArgumentException> { features.get("noSuchColumn") }
    }

    @Test
    fun validateDimensionsRejectsMismatchedVector() {
        val schema = FeatureSchema.runtimeSchema("test", "a", "b")
        val features = FormulaFeatures(schema, doubleArrayOf(1.0)) // wrong size

        assertFailsWith<IllegalStateException> { features.validateDimensions() }
        assertTrue(features.size == 1)
    }
}
