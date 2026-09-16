package uk.co.james.location

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord

class TimeOwnershipTest {
    private fun instant(value:String)=Instant.parse(value)
    @Test fun unknown_time_is_not_counted_as_autonomous() {
        val summary=ownershipSummary(listOf(OwnershipInterval(instant("2026-09-10T10:00:00Z"),instant("2026-09-10T11:00:00Z"),TimeOwnership.UNKNOWN)))
        assertEquals(0,summary.autonomousMinutes);assertEquals(60,summary.unknownMinutes)
    }
    @Test fun no_intervals_are_no_evidence_not_confirmed_zero_personal_time() {
        val summary=ownershipSummary(emptyList())
        assertEquals(0,summary.autonomousMinutes)
        assertEquals(0,summary.classifiedMinutes)
        assertEquals(0,summary.unknownMinutes)
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
    @Test fun explicit_segment_wins_over_enclosing_visit_without_double_counting() {
        fun row(kind:String,id:String,owner:String,start:String,end:String)=StoredRecord.from("personalRecords",personal(kind,fields("ownership" to p(owner),"start" to p(start),"end" to p(end)),id,"manual",start))
        val intervals=ownershipIntervals(listOf(row("PlaceVisit","visit","UNKNOWN","2026-09-10T10:00:00Z","2026-09-10T11:00:00Z"),row("OwnershipPeriod","mine","AUTONOMOUS","2026-09-10T10:15:00Z","2026-09-10T10:45:00Z")),instant("2026-09-10T10:00:00Z"),instant("2026-09-10T11:00:00Z"))
        val summary=ownershipSummary(intervals)
        assertEquals(30,summary.autonomousMinutes);assertEquals(30,summary.unknownMinutes)
    }
    @Test fun persisted_interruption_splits_personal_block_without_becoming_a_second_clock() {
        fun row(kind:String,id:String,start:String,end:String,owner:String="UNKNOWN")=StoredRecord.from("personalRecords",personal(kind,fields("ownership" to p(owner),"start" to p(start),"end" to p(end)),id,"manual",start))
        val intervals=ownershipIntervals(listOf(
            row("OwnershipPeriod","personal","2026-09-10T10:00:00Z","2026-09-10T11:00:00Z","AUTONOMOUS"),
            row("VisitInterruption","call","2026-09-10T10:18:00Z","2026-09-10T10:31:00Z")
        ),instant("2026-09-10T10:00:00Z"),instant("2026-09-10T11:00:00Z"))
        val summary=ownershipSummary(intervals)
        assertEquals(47,summary.autonomousMinutes)
        assertEquals(1,summary.interruptions)
        assertEquals(13,summary.interruptionMinutes)
        assertEquals(29,summary.longestAutonomousBlockMinutes)
    }
    @Test fun ending_personal_closes_it_and_starts_explicit_unknown_without_extending_personal() {
        val t0=instant("2026-09-10T17:32:00Z")
        val t1=instant("2026-09-10T19:47:00Z")
        val later=instant("2026-09-10T20:47:00Z")
        val transition=endOwnership("personal",TimeOwnership.AUTONOMOUS)
        assertEquals(listOf("personal"),transition.closeIds)
        assertEquals(TimeOwnership.UNKNOWN,transition.successor)
        val activeRow=row("OwnershipPeriod","personal",fields("ownership" to p("AUTONOMOUS"),"start" to p(t0.toString()),"end" to p("")))
        assertEquals(135,ownershipSummary(ownershipIntervals(listOf(activeRow),t0,t1)).autonomousMinutes)
        val rows=listOf(
            row("OwnershipPeriod","personal",fields("ownership" to p("AUTONOMOUS"),"start" to p(t0.toString()),"end" to p(t1.toString()))),
            row("OwnershipPeriod","unknown",fields("ownership" to p("UNKNOWN"),"start" to p(t1.toString()),"end" to p("")))
        )
        val summary=ownershipSummary(ownershipIntervals(rows,t0,later))
        assertEquals(135,summary.autonomousMinutes)
        assertEquals(60,summary.unknownMinutes)
    }
    @Test fun changing_personal_to_constrained_is_atomic_and_has_no_ownership_overlap() {
        val transition=changeOwnership(listOf("personal"),TimeOwnership.CONSTRAINED)
        assertEquals(listOf("personal"),transition.closeIds)
        assertEquals(TimeOwnership.CONSTRAINED,transition.successor)
    }
    @Test fun confirmed_committed_period_is_the_current_authoritative_ownership() {
        val current=activeOwnershipPeriod(listOf(
            row("OwnershipPeriod","unknown",fields("ownership" to p("UNKNOWN"),"start" to p("2026-09-10T10:00:00Z"),"end" to p("2026-09-10T10:20:00Z"))),
            row("OwnershipPeriod","confirmed",fields("ownership" to p("COMMITTED"),"start" to p("2026-09-10T10:20:00Z"),"end" to p(""),"ownershipSource" to p("JAMES_CONFIRMED")))
        ))
        assertEquals("confirmed",current?.recordId)
        assertEquals("COMMITTED",current?.data()?.text("ownership"))
    }
    @Test fun confirmed_committed_period_wins_when_location_refresh_keeps_an_unknown_visit() {
        val rows=listOf(
            row("PlaceVisit","visit",fields("ownership" to p("UNKNOWN"),"ownershipSource" to p("INFERRED"),"start" to p("2026-09-10T10:00:00Z"),"end" to p("2026-09-10T12:00:00Z"))),
            row("OwnershipPeriod","confirmed",fields("ownership" to p("COMMITTED"),"ownershipSource" to p("JAMES_CONFIRMED"),"start" to p("2026-09-10T10:20:00Z"),"end" to p("")))
        )
        val intervals=ownershipIntervals(rows,instant("2026-09-10T10:00:00Z"),instant("2026-09-10T11:00:00Z"))
        assertEquals(40,ownershipSummary(intervals).committedMinutes)
        assertEquals("confirmed",activeOwnershipPeriod(rows)?.recordId)
    }
    @Test fun interruption_is_not_a_deliberate_end() {
        val end=endOwnership("personal",TimeOwnership.AUTONOMOUS)
        val interrupted=interruptOwnership("personal",TimeOwnership.AUTONOMOUS)
        assertEquals(TimeOwnership.UNKNOWN,end.successor)
        assertEquals(TimeOwnership.COMMITTED,interrupted.successor)
        assertTrue(interrupted.recordsInterruption)
        assertFalse(end.recordsInterruption)
    }

    private fun row(kind:String,id:String,data:kotlinx.serialization.json.JsonObject)=StoredRecord.from(
        "personalRecords",personal(kind,data,id,"manual",data.text("start","2026-09-10T00:00:00Z"))
    )
}
