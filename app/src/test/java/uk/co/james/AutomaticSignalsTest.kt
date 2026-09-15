package uk.co.james

import java.time.*
import org.junit.Test
import org.junit.Assert.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

class AutomaticSignalsTest {
    private val clock=Instant.parse("2026-09-10T12:00:00Z")
    private fun metric(name:String,value:Double,unit:String="%",hours:Long=1,source:String="whoop") =
        StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(name),"unit" to p(unit),"value" to p(value)),"$name-$hours-$source",source,clock.minusSeconds(hours*3600).toString()))
    private fun auto(rows:List<StoredRecord>)=automaticSignals(rows,clock,null,null)
    @Test fun recoveryWorksWithoutManualCalibration() {
        val s=stateSummary(listOf(metric("Recovery",20.0)),clock,ZoneOffset.UTC)
        assertTrue(s.values.filter {it.key in stateFields}.all {it.value!=null&&!it.reported&&it.remaining==0})
        assertEquals("Low",s.values.first {it.key=="energy"}.value)
        assertTrue(s.values.first {it.key=="mood"}.reasons.any {it.contains("Very low-confidence proxy")})
        assertTrue(s.inputs.array("bodyRecords").isNotEmpty())
    }
    @Test fun emptyStaleFutureInvalidAndWrongSourceStayUnavailable() {
        listOf(emptyList(),listOf(metric("Recovery",20.0,hours=37)),listOf(metric("Recovery",20.0,hours=-1)),listOf(metric("Recovery",101.0)),listOf(metric("Recovery",20.0,source="manual"))).forEach {
            assertTrue(auto(it).values.all {v->v.value==null})
        }
    }
    @Test fun duplicatesDoNotMultiplyLoadAndRecoveryIsNotSummed() {
        val rows=listOf(metric("Recovery",80.0),metric("Strain",18.0,"/ 21"))
        assertEquals("Moderate",auto(rows).getValue("energy").value)
        assertEquals(auto(rows),auto(rows+rows+rows))
    }
    @Test fun recentReportsStillOverrideAutomaticallyComputedValues() {
        val report=StoredRecord.from("personalRecords",personal("MoodEntry",fields("energy" to p("High")),"report","manual",clock.toString()))
        val energy=stateSummary(listOf(metric("Recovery",20.0),report),clock,ZoneOffset.UTC).values.first {it.key=="energy"}
        assertTrue(energy.reported)
        assertEquals("High",energy.value)
    }
    @Test fun missingRecoveryIsNotZeroAndSingleSleepCanStart() {
        val sleep=metric("Sleep",420.0,"min",source="health_connect")
        val s=stateSummary(listOf(sleep),clock,ZoneOffset.UTC)
        assertEquals("Moderate",s.values.first {it.key=="energy"}.value)
        assertTrue(s.values.filter {it.key in stateFields}.all {it.value!=null})
    }
    @Test fun explanationUsesReadableLanguageNotRawTimestamps() {
        val reasons=auto(listOf(metric("Recovery",30.0))).getValue("energy").reasons
        assertTrue(reasons.any {it.contains("Recovery was 30%")})
        assertFalse(reasons.any {it.contains("2026-")||it.contains("T11:")})
    }
}
