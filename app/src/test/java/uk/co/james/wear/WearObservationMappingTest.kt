package uk.co.james.wear

import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import java.time.Instant

class WearObservationMappingTest {
    private val received=Instant.parse("2026-09-30T12:00:00Z")
    private fun observation(at:Long)=fields("id" to p("delayed"),"type" to p("heart_rate"),"value" to p(65),"unit" to p("bpm"),"observedAt" to p(at))
    @Test fun delayedDeliveryPreservesMeasurementTimestampAndStableIdentity() {
        val at=Instant.parse("2026-09-28T23:00:00Z")
        val first=mapWearObservation(observation(at.toEpochMilli()),received)!!
        val retry=mapWearObservation(observation(at.toEpochMilli()),received.plusSeconds(600))!!
        assertEquals("2026-09-28T23:00:00Z",first.text("timestamp"))
        assertEquals("wear",first.text("source"))
        assertEquals("delayed",first.text("externalId"))
        assertEquals(first,retry)
        assertEquals(at.toString(),StoredRecord.from("personalRecords",first).timestamp)
    }
    @Test fun invalidValuesAndFutureOrMissingTimestampsAreRejected() {
        assertNull(mapWearObservation(observation(0),received))
        assertNull(mapWearObservation(observation(received.plusSeconds(3600).toEpochMilli()),received))
        assertNull(mapWearObservation(observation(received.toEpochMilli()).changed("value" to p(-3)),received))
        assertNull(mapWearObservation(observation(received.toEpochMilli()).changed("type" to p("unknown")),received))
        assertNull(mapWearObservation(observation(received.toEpochMilli()).changed("id" to p("")),received))
        assertNull(mapWearObservation(observation(received.toEpochMilli()).changed("value" to p("NaN")),received))
    }
}
