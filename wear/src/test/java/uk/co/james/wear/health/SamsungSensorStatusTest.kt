package uk.co.james.wear.health

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungSensorStatusTest {
    @Test fun derives_only_reported_capabilities() {
        val status=SamsungSensorStatus(supportedTrackers=setOf("HEART_RATE", "PPG_GREEN", "EDA_CONTINUOUS", "SKIN_TEMPERATURE_ON_DEMAND"))
        assertTrue(status.heartRate);assertTrue(status.ibi);assertTrue(status.ppg);assertTrue(status.eda);assertTrue(status.skinTemperature)
    }
    @Test fun does_not_invent_capabilities() {
        val status=SamsungSensorStatus(supportedTrackers=setOf("ACCELEROMETER"))
        assertFalse(status.heartRate);assertFalse(status.ibi);assertFalse(status.ppg);assertFalse(status.eda);assertFalse(status.skinTemperature)
    }
    @Test fun diagnostics_are_grouped_without_dumping_raw_tracker_names() {
        val status=SamsungSensorStatus(supportedTrackers=setOf("HEART_RATE_CONTINUOUS","PPG_GREEN","EDA_CONTINUOUS","SPO2","ACCELEROMETER","ECG_ON_DEMAND"))
        assertEquals(listOf("Heart rate · standard and continuous","Stress signals · EDA + PPG","Oxygen · SpO₂","Movement · accelerometer","ECG · on demand"),status.diagnosticGroups)
    }
}
