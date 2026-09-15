package uk.co.james.location

import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.imports.BackupCodec

class VisitCorrectionTest {
    private fun visit(id:String, supersededBy:String="")=StoredRecord.from("personalRecords",personal("PlaceVisit",fields(
        "title" to p("Home"),"start" to p("2026-09-15T10:00:00Z"),"end" to p("2026-09-15T11:00:00Z"),
        "durationMin" to p(60),"supersededByVisitId" to p(supersededBy)
    ),id,"gps","2026-09-15T10:00:00Z"))

    @Test fun superseded_source_visit_is_preserved_but_not_presented_as_current_visit() {
        val source=visit("source","target"); val target=visit("target")
        assertEquals(listOf("target"),authoritativeVisits(listOf(source,target)).map {it.recordId})
        assertTrue(source.isSupersededVisit())
    }

    @Test fun evidence_snapshot_and_correction_survive_generic_backup_round_trip() {
        val raw=visit("source").raw()
        val snapshot=StoredRecord.from("personalRecords",personal("VisitEvidenceSnapshot",fields("visitId" to p("source"),"rawVisit" to raw,"capturedFor" to p("MERGE")),"evidence","correction","2026-09-15T12:00:00Z"))
        val correction=StoredRecord.from("personalRecords",personal("VisitCorrection",fields("visitId" to p("source"),"action" to p("MERGED_INTO"),"targetVisitId" to p("target"),"reversible" to p(true)),"correction","manual","2026-09-15T12:00:00Z"))
        val restored=BackupCodec.parse(BackupCodec.export(listOf(visit("source","target"),visit("target"),snapshot,correction)).toString()).rows
        assertEquals(4,restored.size)
        assertEquals(raw.toString(),restored.first {it.recordId=="evidence"}.data().obj("rawVisit").toString())
        assertTrue(restored.first {it.recordId=="correction"}.data().flag("reversible"))
    }
}
