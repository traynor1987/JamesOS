package uk.co.james

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.database.StoredRecord
import uk.co.james.timeline.timeline

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
}
