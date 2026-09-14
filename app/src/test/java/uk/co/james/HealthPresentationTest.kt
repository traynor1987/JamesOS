package uk.co.james

import org.junit.Test
import org.junit.Assert.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.time.timeBreakdown
import java.time.*

class HealthPresentationTest {
    private fun health(metric:String,value:Number,unit:String,start:String="2026-09-08T23:00:00Z",end:String="2026-09-09T07:00:00Z",id:String="health")=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(metric),"value" to p(value),"unit" to p(unit),"provider" to p("com.whoop.android"),"start" to p(start),"end" to p(end)),id,"health_connect",end))
    @Test fun nativeHealthValuesAreReadable() {
        assertEquals("7h 11m",healthValue(health("Sleep",431,"min")))
        assertEquals("268 steps",healthValue(health("Steps",268,"steps")))
        assertEquals("73 bpm",healthValue(health("Resting heart rate",73,"bpm")))
        assertEquals("WHOOP · Health Connect",providerLabel(health("Sleep",431,"min")))
    }
    @Test fun timestampsUsePhoneTimezoneIncludingSummerTime() {
        assertEquals("22:35",localClock("2026-09-09T21:35:41.797727Z",ZoneId.of("Europe/London")))
    }
    @Test fun recordedSleepCountsFromLocalMidnight() {
        val time=timeBreakdown(listOf(health("Sleep",480,"min")),"2026-09-09",ZoneId.of("Europe/London"),Instant.parse("2026-09-09T21:00:00Z"))
        assertEquals(480L,time["Sleep"])
        assertEquals(840L,time["Unclassified"])
        assertEquals(1320L,time.values.sum())
    }
    @Test fun duplicateSleepSourcesDoNotDoubleCount() {
        val time=timeBreakdown(listOf(health("Sleep",480,"min",id="a"),health("Sleep",480,"min",id="b")),"2026-09-09",ZoneId.of("Europe/London"),Instant.parse("2026-09-09T21:00:00Z"))
        assertEquals(480L,time["Sleep"])
        assertEquals(1320L,time.values.sum())
    }
    @Test fun manualCorrectionTakesPriorityOverHealth() {
        val manual=StoredRecord.from("personalRecords",personal("TimeBlock",fields("category" to p("Work"),"end" to p("2026-09-09T07:00:00Z")),timestamp="2026-09-09T06:00:00Z"))
        val time=timeBreakdown(listOf(health("Sleep",480,"min"),manual),"2026-09-09",ZoneId.of("Europe/London"),Instant.parse("2026-09-09T21:00:00Z"))
        assertEquals(420L,time["Sleep"]);assertEquals(60L,time["Work"])
    }
    @Test fun springClockChangeUsesActualElapsedTime() {
        val time=timeBreakdown(emptyList(),"2026-03-29",ZoneId.of("Europe/London"),Instant.parse("2026-03-30T12:00:00Z"))
        assertEquals(1380L,time["Unclassified"])
    }
}
