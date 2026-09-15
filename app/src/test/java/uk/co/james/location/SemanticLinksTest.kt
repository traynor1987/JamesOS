package uk.co.james.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.database.StoredRecord

class SemanticLinksTest {
    private fun visit(anchor:String="anchor-1")=StoredRecord.from("personalRecords",personal("PlaceVisit",fields(
        "start" to p("2026-09-15T10:00:00Z"),"end" to p("2026-09-15T11:00:00Z"),"anchorId" to p(anchor)
    ),"visit-1","gps","2026-09-15T10:00:00Z"))

    @Test fun new_semantic_period_joins_only_its_anchor_visit() {
        val period=StoredRecord.from("personalRecords",personal("OwnershipPeriod",fields(
            "start" to p("2026-09-15T10:10:00Z"),"anchorId" to p("anchor-1"),"visitId" to p("")
        ),"period","manual","2026-09-15T10:10:00Z"))
        assertTrue(semanticBelongsToVisit(period,visit()))
        assertFalse(semanticBelongsToVisit(period,visit("anchor-2")))
    }

    @Test fun explicit_visit_link_never_falls_back_to_an_overlapping_visit() {
        val period=StoredRecord.from("personalRecords",personal("LifeFactActivity",fields(
            "start" to p("2026-09-15T10:10:00Z"),"visitId" to p("another-visit"),"anchorId" to p("")
        ),"activity","manual","2026-09-15T10:10:00Z"))
        assertFalse(semanticBelongsToVisit(period,visit()))
    }

    @Test fun legacy_unlinked_period_uses_bounded_time_compatibility_only() {
        val legacy=StoredRecord.from("personalRecords",personal("ContextPeriod",fields(
            "start" to p("2026-09-15T10:10:00Z"),"visitId" to p(""),"anchorId" to p("")
        ),"legacy","legacy","2026-09-15T10:10:00Z"))
        assertTrue(semanticBelongsToVisit(legacy,visit()))
    }
}
