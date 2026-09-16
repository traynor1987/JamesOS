package uk.co.james

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.calibration.JamesCalibrationEngine
import uk.co.james.state.JamesAlgorithmRegistry

class AlgorithmRegistryPresentationTest {
    @Test fun lowMoodMetadataNamesTheActualDerivedInputNotLegacyRut() {
        val inputs=JamesAlgorithmRegistry.get("low_mood_load")!!.inputs
        assertTrue("Life Balance" in inputs)
        assertFalse("Rut" in inputs)
        assertTrue("life_balance" in JamesAlgorithmRegistry.dependencies.getValue("low_mood_load"))
        assertFalse("rut" in JamesAlgorithmRegistry.dependencies.getValue("low_mood_load"))
    }
    @Test fun balanceV2RegistryNamesOnlyItsFactualInputsAndCalibrationDirectionIsOwnTime() {
        val entry=JamesAlgorithmRegistry.get("life_balance_v2")!!
        assertTrue(entry.inputs.contains("Time Ownership"))
        assertFalse(entry.inputs.any {it in setOf("RUT","Recovery","Low Mood","Activity")})
        assertEquals(0.0,JamesCalibrationEngine.normalizeObserved("life_balance_v2","HARDLY ANY OF MY TIME FELT LIKE MINE")!!,0.0)
        assertEquals(100.0,JamesCalibrationEngine.normalizeObserved("life_balance_v2","MY TIME LARGELY FELT LIKE MINE")!!,0.0)
    }
}
