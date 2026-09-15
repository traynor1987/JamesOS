package uk.co.james

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.database.StoredRecord

class StoredRecordPreparationTest {
    @Test fun parsedJsonIsPreparedOnceForAnImmutableRoomRow() {
        val row=StoredRecord.from("personalRecords",fields("kind" to p("HealthMetric"),"data" to fields("metric" to p("Steps"),"value" to p(42))))
        assertSame(row.raw(),row.raw())
        assertSame(row.data(),row.data())
    }

    @Test fun legacyNonObjectPayloadIsQuarantinedWithoutThrowingDuringPreparedParsing() {
        // A historical Room row can contain a payload which is not the object
        // contract required by current semantic preparation. It must remain a
        // retained, quarantined row instead of crashing Today.
        val row=StoredRecord(
            store="personalRecords",recordId="legacy-missing-payload",kind="HealthMetric",
            source="legacy",timestamp="2026-09-15T12:00:00Z",localDate="2026-09-15",
            updatedAt="2026-09-15T12:00:00Z",externalId=null,rawJson="null"
        )

        assertEquals("NON_OBJECT_PAYLOAD",row.rawPayloadIssue())
        assertTrue(row.raw().isEmpty())
        assertTrue(row.data().isEmpty())
    }

    @Test fun missingHistoricalPayloadIsQuarantinedWithoutThrowingDuringPreparedParsing() {
        val row=StoredRecord(
            store="personalRecords",recordId="legacy-null-payload",kind="HealthMetric",
            source="legacy",timestamp="2026-09-15T12:00:00Z",localDate="2026-09-15",
            updatedAt="2026-09-15T12:00:00Z",externalId=null,rawJson=null
        )

        assertEquals("MISSING_PAYLOAD",row.rawPayloadIssue())
        assertTrue(row.raw().isEmpty())
    }

    @Test fun malformedHistoricalPayloadIsQuarantinedWithoutRepeatedParsing() {
        val row=StoredRecord(
            store="personalRecords",recordId="legacy-malformed-payload",kind="HealthMetric",
            source="legacy",timestamp="2026-09-15T12:00:00Z",localDate="2026-09-15",
            updatedAt="2026-09-15T12:00:00Z",externalId=null,rawJson="{not-json"
        )

        assertEquals("MALFORMED_JSON",row.rawPayloadIssue())
        assertSame(row.raw(),row.raw())
    }
}
