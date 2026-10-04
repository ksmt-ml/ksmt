package io.ksmt.tuner.model

import io.ksmt.tuner.feature.FeatureSchema
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnnxTunerModelTest {

    private val schema = FeatureSchema.DEFAULT

    @Test
    fun missingModelFileFailsWithClearError() {
        val missing = Paths.get("definitely-not-a-real-model.onnx")

        val exception = assertFailsWith<IllegalStateException> { OnnxTunerModel(missing, schema) }

        assertTrue(exception.message!!.contains("Failed to load ONNX model"))
        assertTrue(exception.message!!.contains(missing.toString()))
    }
}
