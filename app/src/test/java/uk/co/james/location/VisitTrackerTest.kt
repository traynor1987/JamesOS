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
    @Test fun one_initial_and_one_five_minute_fix_are_enough_to_activate_a_visit() {
        val start=Instant.parse("2026-09-10T10:00:00Z")
        assertTrue(completedVisit(visitMinutes(start,Instant.parse("2026-09-10T10:05:00Z"))))
    }
    @Test fun overlapping_places_choose_the_closest_eligible_boundary() {
        val far=PlaceMatch("a","A","Home",120f,100f)
        val near=PlaceMatch("b","B","Work",50f,100f)
        assertEquals("b",choosePlace(listOf(far,near))?.id)
    }

    @Test fun one_strong_spatial_home_match_is_confirmed_even_when_ownership_is_unknown() {
        val result=resolvePlaceMatch(
            matches=listOf(PlaceMatch("home","Home","Home",4f,150f)),
            accuracyMetres=4f
        )

        assertEquals("home",result.place?.id)
        assertEquals(PlaceMatchConfidence.CONFIRMED,result.confidence)
        assertEquals(TimeOwnership.UNKNOWN,inferredOwnership())
    }

    @Test fun materially_overlapping_saved_places_stay_ambiguous() {
        val result=resolvePlaceMatch(
            matches=listOf(
                PlaceMatch("home","Home","Home",14f,80f),
                PlaceMatch("family","Family house","Family",16f,80f)
            ),
            accuracyMetres=8f
        )

        assertEquals(PlaceMatchConfidence.AMBIGUOUS,result.confidence)
        assertEquals(null,result.place)
    }

    @Test fun geofence_arrival_replaces_a_different_active_place_but_ignores_a_late_exit_for_the_old_place() {
        assertTrue(shouldStartGeofenceAnchor(activePlaceId="sister",eventPlaceId="home",entering=true))
        assertFalse(shouldCloseGeofenceAnchor(activePlaceId="home",eventPlaceId="sister",entering=false))
        assertTrue(shouldCloseGeofenceAnchor(activePlaceId="home",eventPlaceId="home",entering=false))
    }

    @Test fun six_day_old_passive_fix_is_not_current_place_evidence() {
        val now=Instant.parse("2026-09-17T21:02:00Z")
        assertFalse(isFreshPassiveFix(now.minusSeconds(9150L*60),now))
        assertTrue(isFreshPassiveFix(now.minusSeconds(5*60),now))
    }
}
