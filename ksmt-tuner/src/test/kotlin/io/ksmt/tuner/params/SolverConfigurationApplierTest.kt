package io.ksmt.tuner.params

import io.ksmt.solver.KSolverConfiguration
import io.ksmt.solver.KTheory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolverConfigurationApplierTest {

    private class RecordingConfiguration : KSolverConfiguration {
        val calls = mutableListOf<String>()

        override fun setBoolParameter(param: String, value: Boolean) {
            calls += "bool:$param=$value"
        }

        override fun setIntParameter(param: String, value: Int) {
            calls += "int:$param=$value"
        }

        override fun setStringParameter(param: String, value: String) {
            calls += "string:$param=$value"
        }

        override fun setDoubleParameter(param: String, value: Double) {
            calls += "double:$param=$value"
        }

        override fun optimizeForTheories(theories: Set<KTheory>?, quantifiersAllowed: Boolean) {
            // no-op recording configuration
        }
    }

    @Test
    fun dispatchesEachParameterType() {
        val config = RecordingConfiguration()
        val params = SolverTuningParams(
            listOf(
                TuningParam.IntParam("a", 7),
                TuningParam.BoolParam("b", true),
                TuningParam.StringParam("c", "value"),
                TuningParam.DoubleParam("d", 2.5)
            )
        )

        params.applyTo(config)

        assertEquals(4, config.calls.size)
        assertEquals("int:a=7", config.calls[0])
        assertEquals("bool:b=true", config.calls[1])
        assertEquals("string:c=value", config.calls[2])
        assertEquals("double:d=2.5", config.calls[3])
    }

    @Test
    fun emptyParamsDoNothing() {
        val config = RecordingConfiguration()
        SolverTuningParams().applyTo(config)

        assertTrue(config.calls.isEmpty())
    }
}
