package uk.co.james

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.database.StoredRecord
import uk.co.james.timeline.timeline
import uk.co.james.timeline.isNarratedInsideVisit
import uk.co.james.timeline.visitNarrative

class TimelineOwnershipTest {
    private val stamp="2026-09-15T10:00:00Z"
    private fun row(kind:String,id:String,data:kotlinx.serialization.json.JsonObject)=
        StoredRecord.from("personalRecords",personal(kind,data,id,"manual",stamp))

    @Test fun ownership_and_interruptions_are_meaningful_timeline_moments() {
        val moments=timeline(listOf(
            row("OwnershipPeriod","own",fields("ownership" to p("AUTONOMOUS"),"ownershipSource" to p("JAMES_CONFIRMED"))),
            row("VisitInterruption","call",fields("reason" to p("PHONE_CALL"),"source" to p("JAMES_CORRECTION")))
        ))
        assertEquals("Time ownership: Autonomous",moments.first {it.id=="own"}.title)
        assertEquals("Interrupted · Phone call",moments.first {it.id=="call"}.title)
    }
    @Test fun visit_presents_linked_semantic_story_without_deleting_the_records() {
        val visit=row("PlaceVisit","visit",fields("start" to p("2026-09-15T09:00:00Z"),"end" to p("2026-09-15T12:00:00Z"),"durationMin" to p(180)))
        val context=row("ContextPeriod","context",fields("start" to p("2026-09-15T09:05:00Z"),"end" to p("2026-09-15T11:00:00Z"),"visitType" to p("PERSONAL_PROJECT")))
        val activity=row("LifeFactActivity","activity",fields("start" to p("2026-09-15T09:10:00Z"),"title" to p("James OS")))
        val ownership=row("OwnershipPeriod","ownership",fields("start" to p("2026-09-15T09:10:00Z"),"ownership" to p("AUTONOMOUS")))
        val interruption=row("VisitInterruption","interruption",fields("start" to p("2026-09-15T10:00:00Z"),"reason" to p("PHONE_CALL")))
        val rows=listOf(visit,context,activity,ownership,interruption)
        assertEquals(listOf("Context: Personal project","Activity: James OS","Time: Autonomous","Interruptions: 1"),visitNarrative(rows,visit))
        assertEquals(true,isNarratedInsideVisit(context,listOf(visit)))
        assertEquals(true,isNarratedInsideVisit(activity,listOf(visit)))
    }
}
