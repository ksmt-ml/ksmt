package io.ksmt.tuner.model

import io.ksmt.tuner.feature.FeatureSchema
import io.ksmt.tuner.feature.FormulaFeatures
import io.ksmt.tuner.params.TuningParam
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StubTunerModelTest {

    @Test
    fun predictReturnsFixedZ3ValidParameterSet() {
        val model = StubTunerModel()
        val features = FormulaFeatures.Builder.forSchema(FeatureSchema.DEFAULT).build()

        val params = model.predict(features)

        assertEquals("stub", model.name)
        assertEquals(1, params.params.size)
        val param = params.params.first()
        assertTrue(param is TuningParam.IntParam)
        assertEquals("random_seed", param.name)
        assertEquals(42, param.value)
    }
}
