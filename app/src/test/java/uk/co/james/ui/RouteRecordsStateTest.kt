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
        val states=routeRecords("Today|2026-09-15|1",flowOf(listOf(row))).toList()
        assertFalse(states.first().loaded)
        assertTrue(states.first().records.isEmpty())
        assertTrue(states.last().loaded)
        assertEquals(listOf(row),states.last().records)
    }

    @Test fun insightsSnapshotIsNeverAcceptedAsTodayDataDuringBottomNavHandoff() {
        val insights=RouteRecordsState("Insights|2026-09-15|1",emptyList(),true)
        val todayKey=routeRequestKey("Today","2026-09-15",1)
        val todayLoading=RouteRecordsState(todayKey,emptyList(),false)
        val todayReady=RouteRecordsState(todayKey,emptyList(),true)
        assertFalse(insights.matches(todayKey))
        assertFalse(todayLoading.loaded&&todayLoading.matches(todayKey))
        assertTrue(todayReady.loaded&&todayReady.matches(todayKey))
    }

    @Test fun matureLegacyPayloadCannotCrashTodayPreparationAfterInsightsHandoff() = runBlocking {
        val valid=StoredRecord.from("personalRecords",fields("id" to p("steps"),"kind" to p("HealthMetric"),"source" to p("wear"),"timestamp" to p("2026-09-15T12:00:00Z")))
        val legacyMissing=StoredRecord("personalRecords","legacy-null","HealthMetric","legacy","2026-09-15T11:00:00Z","2026-09-15","2026-09-15T11:00:00Z",null,null)
        val todayKey=routeRequestKey("Today","2026-09-15",1)

        val states=routeRecords(todayKey,flowOf(listOf(legacyMissing,valid))).toList()

        assertTrue(states.last().matches(todayKey))
        assertEquals(listOf(valid),todayPreparationInputRecords(states.last().records))
    }
}
