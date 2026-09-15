package uk.co.james

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.database.StoredRecord
import uk.co.james.state.hasRightNowEvidence
import uk.co.james.state.hasWellbeingEvidence

class EvidenceReadinessTest {
    @Test fun calibrationAndMetadataDoNotTurnPriorsIntoCurrentEvidence() {
        val calibration=StoredRecord.from("metadata",fields("kind" to p("CalibrationProfile"),"data" to fields("algorithmId" to p("live_energy"))))
        assertFalse(hasWellbeingEvidence(listOf(calibration)))
        assertFalse(hasRightNowEvidence(listOf(calibration)))
    }

    @Test fun aMeasuredHealthMetricPermitsEvidenceBackedPresentationAndPersistence() {
        val measured=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Sleep"),"value" to p(420)),"sleep","whoop","2026-09-15T07:00:00Z"))
        assertTrue(hasWellbeingEvidence(listOf(measured)))
        assertTrue(hasRightNowEvidence(listOf(measured)))
    }

    @Test fun subjectiveCheckInIsEvidenceButAnOwnershipPeriodIsNotWellbeingEvidence() {
        val ownership=StoredRecord.from("personalRecords",personal("OwnershipPeriod",fields("ownership" to p("AUTONOMOUS")),"owned","manual","2026-09-15T12:00:00Z"))
        val checkIn=StoredRecord.from("personalRecords",personal("WellbeingCheckIn",fields("mood" to p("OKAY")),"check","manual","2026-09-15T12:00:00Z"))
        assertFalse(hasWellbeingEvidence(listOf(ownership)))
        assertTrue(hasRightNowEvidence(listOf(ownership)))
        assertTrue(hasWellbeingEvidence(listOf(checkIn)))
    }
}
