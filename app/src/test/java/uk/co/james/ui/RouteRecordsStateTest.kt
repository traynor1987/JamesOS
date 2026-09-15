package uk.co.james.ui

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.database.StoredRecord

class RouteRecordsStateTest {
    @Test fun destinationTransitionStartsLoadingBeforePublishingItsOwnSnapshot() = runBlocking {
        val row=StoredRecord.from("personalRecords",fields("kind" to p("HealthMetric")))
        val states=routeRecords(flowOf(listOf(row))).toList()
        assertFalse(states.first().loaded)
        assertTrue(states.first().records.isEmpty())
        assertTrue(states.last().loaded)
        assertEquals(listOf(row),states.last().records)
    }
}
