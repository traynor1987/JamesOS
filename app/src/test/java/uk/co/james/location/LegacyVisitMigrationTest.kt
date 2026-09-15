package uk.co.james.location

import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*

class LegacyVisitMigrationTest {
    @Test fun bounded_legacy_context_becomes_a_provenanced_visit() {
        val legacy=personal("ContextPeriod",fields("start" to p("2026-09-14T10:00:00Z"),"end" to p("2026-09-14T11:30:00Z"),"placeName" to p("Ormskirk"),"visitType" to p("PERSONAL")),"old-context","manual","2026-09-14T10:00:00Z")
        val visit=reconstructedVisit(uk.co.james.database.StoredRecord.from("personalRecords",legacy))!!
        assertEquals("Ormskirk",visit.obj("data").text("title"));assertEquals("LEGACY_RECONSTRUCTION",visit.obj("data").text("ownershipSource"));assertEquals("legacy-visit:personalRecords:old-context",visit.text("id"))
    }
    @Test fun evidence_without_a_reliable_departure_is_not_fabricated() {
        val legacy=personal("LocationEvent",fields("title" to p("Arrived at Home")),"arrival","gps","2026-09-14T10:00:00Z")
        assertNull(reconstructedVisit(uk.co.james.database.StoredRecord.from("personalRecords",legacy)))
    }
}
