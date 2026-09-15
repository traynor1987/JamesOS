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
    @Test fun home_and_no_obligation_remain_unknown_until_james_corrects_them() {
        assertEquals(TimeOwnership.UNKNOWN,inferredOwnership())
    }
    @Test fun jitter_does_not_close_an_open_visit_but_a_sustained_departure_does() {
        val last=Instant.parse("2026-09-10T10:00:00Z")
        assertFalse(shouldCloseAnchor(last,Instant.parse("2026-09-10T10:05:00Z"),false))
        assertTrue(shouldCloseAnchor(last,Instant.parse("2026-09-10T10:11:00Z"),false))
    }
    @Test fun overlapping_places_choose_the_closest_eligible_boundary() {
        val far=PlaceMatch("a","A","Home",120f,100f)
        val near=PlaceMatch("b","B","Work",50f,100f)
        assertEquals("b",choosePlace(listOf(far,near))?.id)
    }
}
