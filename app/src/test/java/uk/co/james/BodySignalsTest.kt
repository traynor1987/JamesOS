package uk.co.james

import org.junit.Test
import org.junit.Assert.*
import java.time.*
import uk.co.james.core.*
import uk.co.james.state.*
import uk.co.james.database.StoredRecord

class BodySignalsTest {
    private val clock=Instant.parse("2026-09-10T12:00:00Z")
    private val zone=ZoneId.of("Europe/London")
    private fun pulse(day:Long,value:Int=60,provider:String="com.whoop.android")=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Resting heart rate"),"unit" to p("bpm"),"value" to p(value),"provider" to p(provider)),"rhr-$day-$provider","health_connect",clock.minus(Duration.ofDays(day)).minusSeconds(3600).toString()))
    @Test fun fatigueUsesAvailableSleepWithoutManualCalibration() {
        assertEquals("Moderate",bodySignals(emptyList(),clock,zone,90.0).getValue("fatigue").value)
        assertNull(bodySignals(emptyList(),clock,zone,90.0).getValue("bodyLoad").value)
    }
    @Test fun bodyLoadNeedsSameProviderHistoryAndRecentRhr() {
        val rows=(1L..7).map {pulse(it)}+pulse(0,68)
        assertEquals("Elevated",bodySignals(rows,clock,zone,90.0).getValue("bodyLoad").value)
        assertNull(bodySignals((1L..7).map {pulse(it,60,"other")}+pulse(0,68),clock,zone,90.0).getValue("bodyLoad").value)
        assertNull(bodySignals((2L..9).map {pulse(it)},clock,zone,90.0).getValue("bodyLoad").value)
    }
    @Test fun duplicateExerciseIntervalsAreNotSummed() {
        fun workout(id:String)=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Exercise"),"start" to p(clock.minusSeconds(3600).toString()),"end" to p(clock.minusSeconds(1800).toString())),id,"health_connect",clock.minusSeconds(1800).toString()))
        val v=bodySignals(listOf(workout("a"),workout("b")),clock,zone,null).getValue("fatigue")
        assertEquals("Low",v.value)
        assertTrue(v.reasons.any {it.startsWith("30 min")})
    }
    @Test fun partialCheckInPreservesAnotherRecentSignal() {
        fun report(id:String,hours:Long,key:String,value:String)=StoredRecord.from("personalRecords",personal("MoodEntry",fields(key to p(value)),id,"manual",clock.minusSeconds(hours*3600).toString()))
        val s=stateSummary(listOf(report("energy",2,"energy","High"),report("mood",1,"mood","Good")),clock,zone)
        assertEquals("High",s.values.first {it.key=="energy"}.value)
        assertEquals("Good",s.values.first {it.key=="mood"}.value)
    }
    @Test fun noHealthDataDoesNotPretendToKnowFatigueOrBodyLoad() {
        val s=bodySignals(emptyList(),clock,zone,null)
        assertNull(s.getValue("fatigue").value)
        assertNull(s.getValue("bodyLoad").value)
    }
}
