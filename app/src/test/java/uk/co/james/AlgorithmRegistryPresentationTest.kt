package uk.co.james

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.state.JamesAlgorithmRegistry

class AlgorithmRegistryPresentationTest {
    @Test fun lowMoodMetadataNamesTheActualDerivedInputNotLegacyRut() {
        val inputs=JamesAlgorithmRegistry.get("low_mood_load")!!.inputs
        assertTrue("Life Balance" in inputs)
        assertFalse("Rut" in inputs)
        assertTrue("life_balance" in JamesAlgorithmRegistry.dependencies.getValue("low_mood_load"))
        assertFalse("rut" in JamesAlgorithmRegistry.dependencies.getValue("low_mood_load"))
    }
}
