package uk.co.james

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.wear.CompanionContract
import uk.co.james.wear.StressCheckStatus
import uk.co.james.wear.qualifiesStressCheckResult

class WearCompanionContractTest {
    @Test fun snapshotIsVersionedAndExcludesPrivateRawData() {
        val clock=Instant.parse("2026-09-10T12:00:00Z")
        val privateRow=StoredRecord.from("personalRecords",personal("PlaceVisit",fields("latitude" to p(51.0),"longitude" to p(-1.0),"note" to p("private"),"token" to p("secret")),"place"))
        val text=CompanionContract.snapshot(listOf(privateRow),clock).toString()
        assertTrue(text.contains("\"schemaVersion\":${CompanionContract.SCHEMA_VERSION}"))
        assertTrue(text.contains("\"rightNow\""))
        assertFalse(text.contains("latitude"));assertFalse(text.contains("longitude"))
        assertFalse(text.contains("private"));assertFalse(text.contains("secret"))
    }

    @Test fun manualStressResultRequiresMatchingCorrelatedFreshJamesStress() {
        val active=StressCheckStatus(requestId="request-b",stage="MEASURING",requestedAt=2_000L)
        assertFalse(qualifiesStressCheckResult(active,"",2,2_000L,2_100L,null)) // passive HR/steps batch
        assertFalse(qualifiesStressCheckResult(active,"request-a",2,2_000L,2_100L,2_100L))
        assertFalse(qualifiesStressCheckResult(active,"request-b",1,2_000L,2_100L,2_100L))
        assertFalse(qualifiesStressCheckResult(active,"request-b",2,2_000L,2_100L,1_999L))
        assertTrue(qualifiesStressCheckResult(active,"request-b",2,2_000L,2_100L,2_100L))
        val newer=active.copy(requestId="request-c",requestedAt=3_000L)
        assertFalse(qualifiesStressCheckResult(newer,"request-b",2,2_000L,2_100L,2_100L))
    }
}
