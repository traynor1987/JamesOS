package uk.co.james.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.database.StoredRecord

class TodayPreparationStateTest {
    @Test fun firstPreparationUsesInitialLoadingButRefreshKeepsTheReadySnapshot() {
        val old=Any()
        val initial=beginTodayPreparation<Any>(TodayPreparation.InitialLoading)
        val ready=TodayPreparation.Ready<Any>(old)
        val refreshing=beginTodayPreparation(ready)

        assertEquals(TodayPreparation.InitialLoading,initial)
        assertSame(old,(refreshing as TodayPreparation.Refreshing).value)
    }

    @Test fun refreshFailureRetainsTheLastCoherentSnapshot() {
        val old=Any()
        val failed=TodayPreparation.Failed<Any>(old,"NullPointerException","SleepinessKt.sleepiness:126")

        assertSame(old,failed.previous)
        assertSame(old,failed.visibleSnapshot())
    }

    @Test fun rapidRefreshRestartsKeepTheOriginalReadySnapshotUntilAtomicReplacement() {
        val old=Any()
        val first=beginTodayPreparation(TodayPreparation.Ready<Any>(old))
        val second=beginTodayPreparation(first)
        val new=Any()

        assertSame(old,(first as TodayPreparation.Refreshing).value)
        assertSame(old,(second as TodayPreparation.Refreshing).value)
        assertSame(new,TodayPreparation.Ready<Any>(new).visibleSnapshot())
    }

    @Test fun derivedPersistenceRowsDoNotBecomeNewTodayPreparationInputs() {
        val source=StoredRecord.from("personalRecords",fields("id" to p("steps"),"kind" to p("HealthMetric"),"source" to p("wear"),"timestamp" to p("2026-09-15T12:00:00Z")))
        val derived=StoredRecord.from("metadata",fields("key" to p("right-now:james-day:1"),"value" to fields()))
        val legacyMissing=StoredRecord("personalRecords","legacy-null","HealthMetric","legacy","2026-09-15T12:00:00Z","2026-09-15","2026-09-15T12:00:00Z",null,null)

        assertEquals(listOf(source),todayPreparationInputRecords(listOf(source,derived,legacyMissing)))
    }

    @Test fun refreshTriggerNamesSourceUpdatesButTreatsUnchangedInputsAsClockRefresh() {
        val steps=StoredRecord.from("personalRecords",fields("id" to p("steps"),"kind" to p("HealthMetric"),"source" to p("wear"),"timestamp" to p("2026-09-15T12:00:00Z")))
        val same=todayPreparationInputSignature(listOf(steps))
        assertEquals("clock/freshness",todayRefreshTrigger(same,listOf(steps)))

        val stress=StoredRecord.from("personalRecords",fields("id" to p("stress"),"kind" to p("HealthMetric"),"source" to p("wear"),"timestamp" to p("2026-09-15T12:05:00Z")))
        assertEquals("HealthMetric:wear",todayRefreshTrigger(same,listOf(steps,stress)))
    }
}
