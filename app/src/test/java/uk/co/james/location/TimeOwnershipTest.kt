package uk.co.james.location

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeOwnershipTest {
    private fun instant(value:String)=Instant.parse(value)
    @Test fun unknown_time_is_not_counted_as_autonomous() {
        val summary=ownershipSummary(listOf(OwnershipInterval(instant("2026-09-10T10:00:00Z"),instant("2026-09-10T11:00:00Z"),TimeOwnership.UNKNOWN)))
        assertEquals(0,summary.autonomousMinutes);assertEquals(60,summary.unknownMinutes)
    }
    @Test fun interruption_fragments_personal_time_and_prevents_double_counting() {
        val summary=ownershipSummary(listOf(
            OwnershipInterval(instant("2026-09-10T10:00:00Z"),instant("2026-09-10T10:18:00Z"),TimeOwnership.AUTONOMOUS),
            OwnershipInterval(instant("2026-09-10T10:18:00Z"),instant("2026-09-10T10:31:00Z"),TimeOwnership.CONSTRAINED,true),
            OwnershipInterval(instant("2026-09-10T10:31:00Z"),instant("2026-09-10T10:47:00Z"),TimeOwnership.AUTONOMOUS)))
        assertEquals(34,summary.autonomousMinutes);assertEquals(1,summary.interruptions);assertEquals(18,summary.longestAutonomousBlockMinutes)
    }
    @Test fun adjacent_autonomous_segments_resume_into_one_continuous_block() {
        val summary=ownershipSummary(listOf(
            OwnershipInterval(instant("2026-09-10T10:00:00Z"),instant("2026-09-10T10:20:00Z"),TimeOwnership.AUTONOMOUS),
            OwnershipInterval(instant("2026-09-10T10:20:00Z"),instant("2026-09-10T11:00:00Z"),TimeOwnership.AUTONOMOUS)))
        assertEquals(60,summary.longestAutonomousBlockMinutes)
    }
}
