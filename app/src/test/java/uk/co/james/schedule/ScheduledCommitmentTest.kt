package uk.co.james.schedule

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ScheduledCommitmentTest {
    @Test fun calendarInstanceDefaultsToUnknownAndMinimisesPrivateFields() {
        val source=CalendarInstance("42","personal","Cinema",Instant.parse("2026-09-16T19:30:00Z"),Instant.parse("2026-09-16T22:00:00Z"),false,"BUSY",1)
        val mapped=source.asCommitment()
        assertEquals("UNKNOWN",mapped.plannedOwnership)
        assertEquals("UPCOMING",mapped.status)
        assertFalse(mapped.dataKeys.contains("description"))
        assertFalse(mapped.dataKeys.contains("attendees"))
    }
}
