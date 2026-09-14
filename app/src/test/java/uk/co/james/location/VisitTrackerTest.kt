package uk.co.james.location

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VisitTrackerTest {
    @Test fun nearbySamplesStayInOneVisit() {
        assertTrue(sameVisit(199f,20f,30f))
        assertFalse(sameVisit(250f,20f,30f))
    }
    @Test fun accuracyExpandsTheGroupingRadiusWithoutMakingTimeNegative() {
        assertTrue(sameVisit(300f,180f,30f))
        val start=Instant.parse("2026-09-10T10:00:00Z")
        assertEquals(15,visitMinutes(start,Instant.parse("2026-09-10T10:15:59Z")))
        assertEquals(0,visitMinutes(start,Instant.parse("2026-09-10T09:59:00Z")))
    }
    @Test fun only_stops_of_five_minutes_are_completed() {
        assertFalse(completedVisit(4))
        assertTrue(completedVisit(5))
    }
}
