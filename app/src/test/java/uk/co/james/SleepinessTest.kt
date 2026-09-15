package uk.co.james

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

class SleepinessTest {
    private val clock=Instant.parse("2026-09-13T12:00:00Z")
    private fun sleep(start:Instant,end:Instant,nap:Boolean=false,id:String="sleep:${end.epochSecond}",source:String="whoop")=StoredRecord.from("personalRecords",personal("HealthMetric",fields(
        "metric" to p("Sleep"),"value" to p(Duration.between(start,end).toMinutes()),"unit" to p("min"),"start" to p(start.toString()),"end" to p(end.toString()),"nap" to p(nap),"provider" to p(source)
    ),id,source,end.toString()))
    private fun main(minutes:Long=480,wake:Instant=clock.minus(Duration.ofHours(2)))=sleep(wake.minus(Duration.ofMinutes(minutes)),wake,id="main")
    private fun history(days:Int,minutes:Long)= (1..days).map {day->val end=clock.minus(Duration.ofDays(day.toLong())).minus(Duration.ofHours(2));sleep(end.minus(Duration.ofMinutes(minutes)),end,id="history:$day")}
    private fun nutrition(at:Instant,vararg values:Pair<String,kotlinx.serialization.json.JsonElement>)=StoredRecord.from("personalRecords",personal("Nutrition",fields("sourceDisplay" to p("MyNetDiary via Health Connect"),*values),"nutrition:${at.epochSecond}","health_connect",at.toString()))

    @Test fun adequateSleepAndShortWakeProducesLowSleepiness() {
        val result=sleepiness(history(7,480)+main(),clock,ZoneOffset.UTC)
        assertTrue(result.score<40);assertEquals("GOOD",result.confidence);assertEquals(120,result.timeAwakeMinutes)
    }

    @Test fun severeShortSleepAndExtendedWakeAreMateriallyElevated() {
        val wake=clock.minus(Duration.ofHours(16))
        val result=sleepiness(history(5,450)+main(230,wake),clock,ZoneOffset.UTC)
        assertTrue(result.score>=60);assertTrue(result.shortSleepContribution>0);assertTrue(result.wakeContribution>30)
    }

    @Test fun bodyBatteryIsNotAnInputAndContradictionsRemainPossible() {
        val rows=history(5,450)+main(230,clock.minus(Duration.ofHours(15)))
        val before=sleepiness(rows,clock,ZoneOffset.UTC)
        val batteryLike=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Body Battery"),"value" to p(95)),source="test",timestamp=clock.toString()))
        val after=sleepiness(rows+batteryLike,clock,ZoneOffset.UTC)
        assertEquals(before.score,after.score);assertTrue(after.score>=60)
    }

    @Test fun caffeineChangesExpressedNotUnderlyingPressure() {
        val rows=history(5,420)+main(240,clock.minus(Duration.ofHours(15)))
        val before=sleepiness(rows,clock,ZoneOffset.UTC)
        val after=sleepiness(rows+nutrition(clock.minus(Duration.ofHours(1)),"caffeineMg" to p(100)),clock,ZoneOffset.UTC)
        assertEquals(before.underlyingPressure,after.underlyingPressure)
        assertTrue(after.score<before.score);assertTrue(after.caffeineModifier<0)
        assertEquals("MyNetDiary via Health Connect",after.caffeineSource)
    }

    @Test fun foodAndWaterDoNotCreateSleepinessBonuses() {
        val rows=history(5,420)+main(300,clock.minus(Duration.ofHours(10)))
        val before=sleepiness(rows,clock,ZoneOffset.UTC)
        val meal=nutrition(clock.minusSeconds(1800),"energyKcal" to p(800))
        val water=StoredRecord.from("personalRecords",personal("Hydration",fields("volumeMl" to p(500)),source="health_connect",timestamp=clock.minusSeconds(900).toString()))
        assertEquals(before.score,sleepiness(rows+meal+water,clock,ZoneOffset.UTC).score)
    }

    @Test fun napIsTemporaryAndNeverResetsJamesDayOrDebt() {
        val wake=clock.minus(Duration.ofHours(12));val rows=history(5,360)+main(300,wake)
        val before=sleepiness(rows,clock,ZoneOffset.UTC)
        val nap=sleep(clock.minus(Duration.ofMinutes(65)),clock.minus(Duration.ofMinutes(30)),true,"nap")
        val after=sleepiness(rows+nap,clock,ZoneOffset.UTC)
        assertEquals(before.jamesDayId,after.jamesDayId);assertEquals(before.recentShortfallContribution,after.recentShortfallContribution,0.001)
        assertTrue(after.score<before.score);assertTrue(after.napModifier<0)
    }

    @Test fun midnightDoesNotResetAndIrregularWakeIsAuthoritative() {
        val wake=Instant.parse("2026-09-12T11:30:00Z")
        val rows=listOf(sleep(Instant.parse("2026-09-12T04:00:00Z"),wake,id="irregular"))+history(5,450)
        val before=sleepiness(rows,Instant.parse("2026-09-12T23:55:00Z"),ZoneOffset.UTC)
        val after=sleepiness(rows,Instant.parse("2026-09-13T00:05:00Z"),ZoneOffset.UTC)
        assertEquals(wake.toString(),before.wakeAt);assertEquals(before.jamesDayId,after.jamesDayId);assertTrue(after.timeAwakeMinutes>before.timeAwakeMinutes)
    }

    @Test fun elapsedTimeAndProcessReconstructionAreDeterministic() {
        val rows=history(5,420)+main(360,clock.minus(Duration.ofHours(2)))
        val first=sleepiness(rows,clock,ZoneOffset.UTC)
        val again=sleepiness(rows,clock,ZoneOffset.UTC)
        val later=sleepiness(rows,clock.plus(Duration.ofHours(12)),ZoneOffset.UTC)
        assertEquals(first,again);assertTrue(later.underlyingPressure>first.underlyingPressure)
    }

    @Test fun pendingWhoopSleepLowersConfidenceWithoutInventingAReset() {
        val rows=history(5,420)+main(420,clock.minus(Duration.ofHours(20)))
        val raw=fields("score_state" to p("PENDING_SCORE"),"nap" to p(false),"start" to p(clock.minus(Duration.ofHours(5)).toString()),"end" to p(clock.minus(Duration.ofHours(1)).toString()))
        val pending=StoredRecord.from("personalRecords",personal("ExternalRecord",fields("type" to p("sleep"),"original" to raw),"pending","whoop",clock.minus(Duration.ofHours(1)).toString()))
        val result=sleepiness(rows+pending,clock,ZoneOffset.UTC)
        assertEquals("LOW",result.confidence);assertEquals("NEW MAIN SLEEP PROCESSING",result.sourceFreshness)
        assertEquals(clock.minus(Duration.ofHours(20)).toString(),result.wakeAt)
    }

    @Test fun authoritativeSourceWinsDuplicateSleepCorrection() {
        val wake=clock.minus(Duration.ofHours(4))
        val health=sleep(wake.minus(Duration.ofMinutes(240)),wake,id="hc",source="health_connect")
        val whoop=sleep(wake.minus(Duration.ofMinutes(275)),wake,id="whoop-corrected",source="whoop")
        val result=sleepiness(history(5,420)+health+whoop,clock,ZoneOffset.UTC)
        assertEquals(275.0,result.mainSleepMinutes!!,0.001);assertEquals("whoop",result.mainSleepSource)
    }

    @Test fun repeatedShortfallIsBoundedAndAdequateHistoryReducesIt() {
        val poor=sleepiness(history(12,180)+main(300),clock,ZoneOffset.UTC)
        val recovered=sleepiness(history(5,510)+main(420),clock,ZoneOffset.UTC)
        assertTrue(poor.recentShortfallContribution<=15.0);assertTrue(poor.recentShortfallContribution>recovered.recentShortfallContribution)
        assertTrue(poor.score in 0..100&&recovered.score in 0..100)
    }

    @Test fun circadianAndWakeCurveUseProvidedClock() {
        val wake=Instant.parse("2026-09-13T00:00:00Z");val rows=history(5,420)+main(420,wake)
        val two=sleepiness(rows,wake.plus(Duration.ofHours(2)),ZoneOffset.UTC)
        val eight=sleepiness(rows,wake.plus(Duration.ofHours(8)),ZoneOffset.UTC)
        val eighteen=sleepiness(rows,wake.plus(Duration.ofHours(18)),ZoneOffset.UTC)
        assertTrue(two.wakeContribution<eight.wakeContribution);assertTrue(eight.wakeContribution<eighteen.wakeContribution)
        assertEquals(two,sleepiness(rows,wake.plus(Duration.ofHours(2)),ZoneOffset.UTC))
    }
}
