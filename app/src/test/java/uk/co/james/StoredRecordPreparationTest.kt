package uk.co.james

import org.junit.Assert.assertSame
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
}
