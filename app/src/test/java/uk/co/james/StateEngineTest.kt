package uk.co.james

import org.junit.Test
import org.junit.Assert.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*
import java.time.*
import kotlinx.serialization.json.jsonObject

class StateEngineTest {
    private val clock=Instant.parse("2026-09-09T12:00:00Z")
    private val zone=ZoneId.of("Europe/London")
    private fun sleep(days:Long,minutes:Int=420,id:String="sleep-$days"):StoredRecord {
        val stamp=clock.minus(Duration.ofDays(days)).minus(Duration.ofHours(5)).toString()
        return StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Sleep"),"unit" to p("min"),"value" to p(minutes),"provider" to p("com.whoop.android")),id,"health_connect",stamp))
    }
    private fun report(days:Long,energy:String="Good",mood:String="Good"):StoredRecord {
        val stamp=clock.minus(Duration.ofDays(days)).minus(Duration.ofHours(1)).toString()
        return StoredRecord.from("personalRecords",personal("MoodEntry",fields("energy" to p(energy),"mood" to p(mood)),"report-$days","manual",stamp).changed("updatedAt" to p(stamp)))
    }
    private fun state(rows:List<StoredRecord>)=stateSummary(rows,clock,zone)
    @Test fun importedHistorySkipsInitialWait() {
        val s=state((0L..7).map {sleep(it)})
        assertEquals(0,s.remaining)
        assertEquals("Moderate",s.values.first {it.key=="energy"}.value)
        assertEquals("Neutral",s.values.first {it.key=="mood"}.value)
    }
    @Test fun countdownCountsDaysNotRecordsOrElapsedTime() {
        val rows=(1L..4).map {sleep(it)}+List(8){sleep(1,id="duplicate-$it")}
        assertEquals(3,state(rows).remaining)
        assertEquals(7,stateSummary(rows,clock.plus(Duration.ofDays(40)),zone).remaining)
    }
    @Test fun invalidAndFutureSleepDoesNotCount() {
        assertEquals(7,state(listOf(sleep(-1),sleep(1,0),sleep(2,2000))).remaining)
    }
    @Test fun staleCurrentInputDoesNotProduceEstimate() {
        val s=state((2L..9).map {sleep(it)})
        assertEquals(0,s.remaining)
        assertNull(s.values.first {it.key=="energy"}.value)
    }
    @Test fun reportsOverrideRulesAndPersistInSnapshotWithoutRecursion() {
        val s=state((0L..7).map {sleep(it)}+report(0,"High"))
        assertEquals("High",s.values.first {it.key=="energy"}.value)
        assertTrue(s.values.first {it.key=="energy"}.reported)
        assertTrue(s.snapshot().obj("inputsUsed").array("reports").isNotEmpty())
    }
    @Test fun pairedReportsEnableExploratoryPersonalisation() {
        val s=state((0L..7).map {sleep(it)}+(1L..7).map {report(it,"Good","Neutral")})
        assertEquals("Neutral",s.values.first {it.key=="mood"}.value)
        assertFalse(s.values.first {it.key=="mood"}.reported)
        assertTrue(s.values.first {it.key=="mood"}.reasons.any {it.contains("7 paired days")})
    }
    @Test fun oldReportsDoNotOverrideToday() {
        val s=state((0L..7).map {sleep(it)}+report(1,"High"))
        assertEquals("Moderate",s.values.first {it.key=="energy"}.value)
    }
    @Test fun energySurvivesLocalMidnightWithoutNewSleep() {
        val rows=(0L..7).map {sleep(it)}
        val before=stateSummary(rows,Instant.parse("2026-09-09T22:59:00Z"),zone)
        val after=stateSummary(rows,Instant.parse("2026-09-09T23:10:00Z"),zone)
        assertEquals(before.values.first {it.key=="energy"}.value,after.values.first {it.key=="energy"}.value)
        assertNotNull(after.values.first {it.key=="energy"}.value)
        assertEquals(0,after.remaining)
        assertTrue(after.values.first {it.key=="energy"}.reasons.any {it.contains("Sleep was")})
    }
    @Test fun sleepExpiresAfter36HoursAndFreshCheckInCrossesMidnight() {
        val rows=(0L..7).map {sleep(it)}
        val expired=stateSummary(rows,Instant.parse("2026-09-10T19:01:00Z"),zone)
        assertNull(expired.values.first {it.key=="energy"}.value)
        val stamp="2026-09-09T22:30:00Z"
        val manual=StoredRecord.from("personalRecords",personal("MoodEntry",fields("energy" to p("High")),"late-check-in","manual",stamp).changed("updatedAt" to p(stamp)))
        val after=stateSummary(rows+manual,Instant.parse("2026-09-09T23:10:00Z"),zone)
        assertEquals("High",after.values.first {it.key=="energy"}.value)
        assertTrue(after.values.first {it.key=="energy"}.reported)
    }


    @Test fun mentalWellbeingKeepsPerTargetContributionAttribution() {
        val summary=mentalWellbeing(emptyList(),clock=clock,zone=zone)
        val all=summary.anxiety.contributors+summary.lowMood.contributors+summary.reserve.contributors
        assertTrue(summary.lowMood.contributors.isNotEmpty())
        assertTrue(summary.lowMood.contributors.all {it.targetScore=="LOW_MOOD_LOAD"})
        assertTrue(summary.reserve.contributors.all {it.targetScore=="MENTAL_RESERVE"})
        assertTrue(all.all {it.targetScore.isNotBlank()})
        val stored=wellbeingRecord(summary).obj("value").obj("lowMood").array("contributors")
        assertTrue(stored.all {it.jsonObject.text("targetScore")=="LOW_MOOD_LOAD"})
    }

    @Test fun wellbeingUsesNewestSameDayStressInput() {
        val base=Instant.parse("2026-09-11T12:00:00Z")
        fun metric(name:String,value:Double,at:Instant)=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(name),"value" to p(value)),"${name}-${at.epochSecond}","wear",at.toString()).changed("updatedAt" to p(at.toString())))
        val history=(1L..8L).flatMap {day->
            val at=base.minus(Duration.ofDays(day))
            listOf(metric("James Stress",20.0,at),metric("HRV",60.0,at),metric("Resting heart rate",60.0,at),metric("Sleep",420.0,at),metric("Recovery",70.0,at))
        }
        val early=metric("James Stress",20.0,base.minus(Duration.ofMinutes(25)))
        val fresh=metric("James Stress",70.0,base.minus(Duration.ofMinutes(2)))
        val summary=mentalWellbeing(history+early+fresh,clock=base,zone=ZoneOffset.UTC)
        val stress=summary.anxiety.contributors.first {it.source=="James Stress"}
        assertEquals(70.0,stress.current!!,.001)
    }

    private fun wellbeingMetric(name:String,value:Double,at:Instant,source:String="whoop"):StoredRecord =
        StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(name),"value" to p(value)),"${name}-${at.epochSecond}-${value}-${source}",source,at.toString()).changed("updatedAt" to p(at.toString())))

    private fun wellbeingScenario(
        hrv:Double=100.0,recovery:Double=60.0,sleepValue:Double=420.0,
        rhr:Double=70.0,stressValue:Double=20.0,exercise:Boolean=false,exerciseHoursAgo:Long?=null,
        checkIn:Triple<String,String,String>?=null,hasPersonal:Boolean=false,diversity:Boolean=false
    ):MentalWellbeingSummary {
        val base=Instant.parse("2026-09-11T12:00:00Z")
        val history=(1L..10L).flatMap {days->
            val at=base.minus(Duration.ofDays(days))
            listOf(wellbeingMetric("HRV",100.0,at),wellbeingMetric("Recovery",60.0,at),wellbeingMetric("Sleep",420.0,at),wellbeingMetric("Resting heart rate",70.0,at),wellbeingMetric("James Stress",20.0,at))
        }
        val current=listOf(wellbeingMetric("HRV",hrv,base),wellbeingMetric("Recovery",recovery,base),wellbeingMetric("Sleep",sleepValue,base),wellbeingMetric("Resting heart rate",rhr,base),wellbeingMetric("James Stress",stressValue,base))
        val activity=if(exercise||exerciseHoursAgo!=null) listOf(wellbeingMetric("Exercise",1.0,base.minus(Duration.ofHours(exerciseHoursAgo?:0L)))) else emptyList()
        val report=checkIn?.let {(mood,energy,anxiety)->
            listOf(StoredRecord.from("personalRecords",personal("WellbeingCheckIn",fields("mood" to p(mood),"energy" to p(energy),"anxiety" to p(anxiety)),"check-in","manual",base.toString()).changed("updatedAt" to p(base.toString()))))
        }?:emptyList()
        val personalRecord=if(hasPersonal)listOf(StoredRecord.from("personalRecords",personal("TimeBlock",fields("category" to p("Personal"),"title" to p("Walk")),"personal","manual",base.toString()))) else emptyList()
        val places=if(diversity)listOf("Home","Shops").mapIndexed {index,title->StoredRecord.from("personalRecords",personal("PlaceVisit",fields("title" to p(title),"category" to p(title)),"place-§{index}","manual",base.toString()))} else emptyList()
        return mentalWellbeing(history+current+activity+report+personalRecord+places,clock=base,zone=ZoneOffset.UTC)
    }
    private fun WellbeingOutput.contribution(source:String)=contributors.first {it.source==source}.contribution

    @Test fun wellbeingPhysiologyDirectionUsesLoadSemantics() {
        val at=wellbeingScenario()
        val above=wellbeingScenario(hrv=120.0,recovery=72.0,sleepValue=502.0,rhr=65.0,stressValue=10.0)
        val below=wellbeingScenario(hrv=30.0,recovery=30.0,sleepValue=300.0,rhr=90.0,stressValue=50.0)

        // Above baseline: higher HRV/recovery, lower RHR/stress help Anxiety. Longer
        // sleep is range based and therefore neutral rather than punished.
        assertTrue(above.anxiety.contribution("HRV")<0.0)
        assertTrue(above.anxiety.contribution("Recovery")<0.0)
        assertTrue(above.anxiety.contribution("Resting heart rate")<0.0)
        assertTrue(above.anxiety.contribution("James Stress")<0.0)
        assertEquals(0.0,above.anxiety.contribution("Sleep"),.001)
        // At baseline stays neutral; below baseline has the opposite load direction.
        listOf("HRV","Recovery","Resting heart rate","James Stress","Sleep").forEach {assertEquals(0.0,at.anxiety.contribution(it),.001)}
        assertTrue(below.anxiety.contribution("HRV")>0.0)
        assertTrue(below.anxiety.contribution("Recovery")>0.0)
        assertTrue(below.anxiety.contribution("Resting heart rate")>0.0)
        assertTrue(below.anxiety.contribution("James Stress")>0.0)
        assertTrue(below.anxiety.contribution("Sleep")>0.0)
        assertTrue(above.anxiety.score<at.anxiety.score)
        assertTrue(below.anxiety.score>at.anxiety.score)
        assertTrue(above.reserve.score>at.reserve.score)
        assertTrue(below.reserve.score<at.reserve.score)
    }

    @Test fun lowMoodPhysiologyIsDirectionalAndSlow() {
        val at=wellbeingScenario()
        val above=wellbeingScenario(hrv=120.0,recovery=72.0,sleepValue=502.0)
        val below=wellbeingScenario(hrv=30.0,recovery=30.0,sleepValue=300.0)
        // Fresh helpful readings never add Low-Mood Load. A single fresh reading is
        // deliberately muted by the rolling window, while persistent-bad direction adds load.
        assertTrue(above.lowMood.contribution("HRV")<=0.0)
        assertTrue(above.lowMood.contribution("Recovery")<=0.0)
        assertEquals(0.0,above.lowMood.contribution("Sleep"),.001)
        assertEquals(0.0,at.lowMood.contribution("HRV"),.001)
        assertEquals(0.0,at.lowMood.contribution("Recovery"),.001)
        assertEquals(0.0,at.lowMood.contribution("Sleep"),.001)
        assertTrue(below.lowMood.contribution("HRV")>0.0)
        assertTrue(below.lowMood.contribution("Recovery")>0.0)
        assertTrue(below.lowMood.contribution("Sleep")>0.0)
        assertTrue(below.lowMood.score>at.lowMood.score)
    }

    @Test fun exerciseSuppressesExertionLikeAnxietyAndSelfReportIsBounded() {
        val stationary=wellbeingScenario(hrv=30.0,recovery=30.0,rhr=90.0,stressValue=65.0)
        val exercising=wellbeingScenario(hrv=30.0,recovery=30.0,rhr=90.0,stressValue=65.0,exercise=true)
        val report=wellbeingScenario(checkIn=Triple("OKAY","OKAY","NONE"))
        val contextual=wellbeingScenario(exercise=true,hasPersonal=true,diversity=true)
        assertTrue(stationary.anxiety.contribution("James Stress")>0.0)
        assertTrue(exercising.anxiety.contributors.none {it.source=="James Stress"})
        assertTrue(exercising.anxiety.score<stationary.anxiety.score)
        assertTrue(exercising.lowMood.contribution("Exercise")<0.0)
        assertTrue(exercising.reserve.contribution("Exercise")>0.0)
        assertTrue(contextual.lowMood.contribution("Time that was mine")<0.0)
        assertTrue(contextual.lowMood.contribution("Activity diversity")<0.0)
        assertTrue(contextual.reserve.contribution("Time that was mine")>0.0)
        assertEquals(0.0,report.lowMood.contribution("Self-reported mood"),.001)
        assertEquals(-6.0,report.anxiety.contribution("Self-reported anxiety"),.001)
        assertEquals(0.0,report.reserve.contribution("Self-reported energy"),.001)
        assertTrue(report.anxiety.score>0) // A single “none” check-in never forces zero.
    }

    @Test fun anxietyExerciseSuppressionIsContemporaneousNotFourteenDayHistorical() {
        val oldExercise=wellbeingScenario(hrv=30.0,recovery=30.0,rhr=90.0,stressValue=65.0,exerciseHoursAgo=7*24)
        val justInsideRecovery=wellbeingScenario(hrv=30.0,recovery=30.0,rhr=90.0,stressValue=65.0,exerciseHoursAgo=2)
        val outsideRecovery=wellbeingScenario(hrv=30.0,recovery=30.0,rhr=90.0,stressValue=65.0,exerciseHoursAgo=3)
        assertTrue(oldExercise.anxiety.contributors.any {it.source=="James Stress"})
        assertTrue(outsideRecovery.anxiety.contributors.any {it.source=="James Stress"})
        assertTrue(justInsideRecovery.anxiety.contributors.none {it.source=="James Stress"})
        assertTrue(oldExercise.lowMood.contribution("Exercise")<0.0)
    }

    @Test fun anxietyUsesDiminishingReturnsAndCannotBeDecidedByHrvAlone() {
        val baseline=wellbeingScenario()
        val moderate=wellbeingScenario(hrv=150.0)
        val huge=wellbeingScenario(hrv=1000.0)
        val moderateHrv=kotlin.math.abs(moderate.anxiety.contribution("HRV"))
        val hugeHrv=kotlin.math.abs(huge.anxiety.contribution("HRV"))
        assertTrue(hugeHrv>moderateHrv)
        assertTrue(hugeHrv<8.0) // HRV-specific cap, approached rather than hit linearly.
        assertTrue(hugeHrv/moderateHrv<1.5) // +900% is not nine times +50%.
        assertTrue(huge.anxiety.score>0) // HRV alone cannot force a zero.
        val converging=wellbeingScenario(hrv=1000.0,recovery=100.0,rhr=55.0,stressValue=0.0,checkIn=Triple("OKAY","OKAY","NONE"))
        assertEquals(0,converging.anxiety.score) // Zero remains available to converging independent evidence.
        assertTrue(wellbeingScenario(hrv=1000.0,stressValue=100.0).anxiety.score>baseline.anxiety.score)
    }

    @Test fun samsungSpotHrvDoesNotBorrowWhoopOvernightBaseline() {
        val base=Instant.parse("2026-09-11T12:00:00Z")
        val whoopHistory=(1L..10L).flatMap {days->
            val at=base.minus(Duration.ofDays(days))
            listOf(wellbeingMetric("HRV",29.6,at),wellbeingMetric("Recovery",60.0,at),wellbeingMetric("Sleep",420.0,at),wellbeingMetric("Resting heart rate",70.0,at),wellbeingMetric("James Stress",20.0,at))
        }
        val current=listOf(
            wellbeingMetric("HRV",106.8,base,"wear"),
            wellbeingMetric("Recovery",60.0,base),wellbeingMetric("Sleep",420.0,base),
            wellbeingMetric("Resting heart rate",70.0,base),wellbeingMetric("James Stress",20.0,base)
        )
        val summary=mentalWellbeing(whoopHistory+current,clock=base,zone=ZoneOffset.UTC)
        val hrv=summary.anxiety.contributors.first {it.source=="HRV"}
        assertNull(hrv.baseline)
        assertEquals(0.0,hrv.contribution,.001)
        assertEquals("SAMSUNG_SENSOR_CHECK_HRV",hrv.measurementContext)
        assertTrue(hrv.explanation.contains("not compared"))
    }

    @Test fun staleStressLosesAuthorityAndFutureStressCannotBecomeLatest(){
        val base=Instant.parse("2026-09-11T12:00:00Z")
        val history=(1L..10L).flatMap {days->val at=base.minus(Duration.ofDays(days));listOf(
            wellbeingMetric("James Stress",20.0,at,"wear"),wellbeingMetric("Recovery",60.0,at),
            wellbeingMetric("Sleep",420.0,at),wellbeingMetric("HRV",60.0,at),wellbeingMetric("Resting heart rate",70.0,at))}
        val stale=wellbeingMetric("James Stress",90.0,base.minus(Duration.ofHours(8)),"wear")
        val future=wellbeingMetric("James Stress",100.0,base.plus(Duration.ofHours(2)),"wear")
        val summary=mentalWellbeing(history+stale+future,clock=base,zone=ZoneOffset.UTC)
        val stress=summary.anxiety.contributors.first {it.source=="James Stress"}
        assertEquals(90.0,stress.current!!,.001)
        assertFalse(stress.included);assertEquals(0.0,stress.contribution,.001)
        assertEquals("STALE",stress.freshnessState);assertEquals(0.0,stress.freshnessMultiplier,.001)
    }

}
