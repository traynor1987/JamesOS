package uk.co.james

import java.time.*
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

class BodyBatteryTest {
    private val clock=Instant.parse("2026-09-10T21:00:00Z")
    private fun metric(name:String,value:Double,unit:String,time:Instant=clock,source:String="whoop",provider:String="whoop")=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(name),"value" to p(value),"unit" to p(unit),"provider" to p(provider),"date" to p("2026-09-10")),"$name:$source",source,time.toString()))
    @Test fun batteryDepletesWithTimeAndActivity() {
        val sleep=metric("Sleep",480.0,"min",Instant.parse("2026-09-10T09:00:00Z"))
        val morningTime=Instant.parse("2026-09-10T09:00:00Z")
        val morning=bodyBattery(listOf(sleep,metric("Recovery",99.0,"%",morningTime),metric("Strain",0.0,"/ 21",morningTime)),morningTime,ZoneOffset.UTC)
        val evening=bodyBattery(listOf(sleep,metric("Recovery",99.0,"%"),metric("Strain",18.0,"/ 21"),metric("Steps",10_000.0,"steps",source="health_connect")),clock,ZoneOffset.UTC)
        assertEquals(99,morning.value)
        assertTrue(evening.value!! in 40..60)
        assertTrue(evening.used!!>morning.used!!)
    }
    @Test fun whoopDominatesConflictingSamsungScore() {
        val samsung=metric("Energy score",79.0,"%",source="health_connect",provider="com.sec.android.app.shealth")
        val battery=bodyBattery(listOf(metric("Recovery",30.0,"%"),samsung),clock,ZoneOffset.UTC)
        assertEquals(49,battery.morning)
    }
    @Test fun missingReadinessStaysUnavailable() {
        assertNull(bodyBattery(emptyList(),clock,ZoneOffset.UTC).value)
    }
    @Test fun napRechargesWithoutReplacingOvernightSleep() {
        val at=Instant.parse("2026-09-10T14:30:00Z")
        val overnight=metric("Sleep",420.0,"min",Instant.parse("2026-09-10T07:00:00Z"))
        val nap=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Sleep"),"value" to p(30.0),"unit" to p("min"),"provider" to p("whoop"),"date" to p("2026-09-10"),"nap" to p(true)),"nap","whoop",Instant.parse("2026-09-10T14:00:00Z").toString()))
        val inputs=listOf(overnight,nap,metric("Recovery",70.0,"%",Instant.parse("2026-09-10T07:00:00Z")),metric("Strain",0.0,"/ 21",at))
        val battery=bodyBattery(inputs,at,ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-09-10T07:00:00Z"),battery.wakeTime)
        assertEquals(3,battery.recharge)
        assertTrue(battery.reasons.any {it.contains("never replace")})
        assertTrue(battery.value!! < battery.morning!!)
    }
    @Test fun lowRecoveryDoesNotBecomeAnEmptyBatteryByMidAfternoon() {
        val afternoon=Instant.parse("2026-09-10T15:43:00Z")
        val inputs=listOf(
            metric("Sleep",414.0,"min",Instant.parse("2026-09-10T11:09:00Z")),
            metric("Sleep quality",72.0,"%",Instant.parse("2026-09-10T11:09:00Z")),
            metric("Recovery",30.0,"%",Instant.parse("2026-09-10T07:39:00Z")),
            metric("Strain",4.7,"/ 21",afternoon),
            metric("Steps",2780.0,"steps",afternoon,source="health_connect")
        )
        val battery=bodyBattery(inputs,afternoon,ZoneOffset.UTC)
        assertEquals(45,battery.morning)
        assertTrue(battery.value!! in 30..40)
    }
    @Test fun refreshedWhoopCycleStrainWinsOverTheOriginalCycleTimestamp() {
        val cycleTime=Instant.parse("2026-09-10T08:11:00Z")
        fun cycle(value:Double,updated:Instant)=StoredRecord.from(
            "personalRecords",
            personal("HealthMetric",fields("metric" to p("Strain"),"value" to p(value),"unit" to p("/ 21"),"provider" to p("whoop"),"date" to p("2026-09-10")),"strain:$value","whoop",cycleTime.toString())
                .changed("updatedAt" to p(updated.toString()))
        )
        val battery=bodyBattery(listOf(
            metric("Sleep",420.0,"min",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Recovery",30.0,"%",Instant.parse("2026-09-10T07:39:00Z")),
            cycle(4.7,Instant.parse("2026-09-10T14:00:00Z")),
            cycle(7.3,Instant.parse("2026-09-10T19:00:00Z"))
        ),clock,ZoneOffset.UTC)
        assertTrue(battery.reasons.any {it.contains("WHOOP Day Strain 7.3/21")})
    }
    @Test fun unscoredMainWhoopSleepShowsChargingInsteadOfOldReserve() {
        val at=Instant.parse("2026-09-11T09:30:00Z")
        val pending=StoredRecord.from(
            "personalRecords",
            personal("ExternalRecord",fields(
                "type" to p("sleep"),
                "original" to fields(
                    "id" to p("sleep-pending"),
                    "score_state" to p("PENDING_SCORE"),
                    "nap" to p(false),
                    "start" to p("2026-09-11T01:00:00Z"),
                    "end" to p("2026-09-11T09:00:00Z")
                )
            ),"whoop:raw:sleep:sleep-pending","whoop",at.toString())
        )
        val battery=bodyBattery(listOf(
            metric("Sleep",420.0,"min",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Recovery",65.0,"%",Instant.parse("2026-09-10T07:39:00Z")),
            pending
        ),at,ZoneOffset.UTC)
        assertTrue(battery.sleepProcessing)
        assertNull(battery.value)
        assertEquals("Charging your new day",battery.headline)
    }

    @Test fun nonlinearWhoopStrainCurveMatchesJamesAnchorsAndAccelerates() {
        val values=listOf(0.0,1.0,2.0,4.0,5.0,6.0,6.7,8.0,9.0,10.0,11.0,13.0,14.0,16.0,18.0,19.0,20.0,21.0)
            .map {it to whoopStrainReserveCost(it)}
        assertTrue(values.zipWithNext().all {it.first.second<=it.second.second})
        assertEquals(7.0,whoopStrainReserveCost(6.7),0.25)
        assertEquals(18.0,whoopStrainReserveCost(13.0),1.0)
        assertEquals(36.5,whoopStrainReserveCost(18.0),2.0)
        assertEquals(49.5,whoopStrainReserveCost(20.0),2.0)
        assertTrue(whoopStrainReserveCost(18.0)>whoopStrainReserveCost(9.0)*3)
        assertTrue(whoopStrainReserveCost(20.0)>whoopStrainReserveCost(10.0)*4)
        assertTrue((whoopStrainReserveCost(6.0)-whoopStrainReserveCost(5.0)) <
            (whoopStrainReserveCost(11.0)-whoopStrainReserveCost(10.0)))
        assertTrue((whoopStrainReserveCost(16.0)-whoopStrainReserveCost(15.0)) <
            (whoopStrainReserveCost(20.0)-whoopStrainReserveCost(19.0)))
    }

    @Test fun strainAccountingIsIncrementalAndDoesNotDoubleDebitOnEqualRefresh() {
        val wake=Instant.parse("2026-09-10T07:00:00Z")
        fun inputs(strain:Double, at:Instant, metadata:StoredRecord?=null)=listOfNotNull(
            metric("Sleep",420.0,"min",wake),
            metric("Recovery",50.0,"%",wake),
            metric("Strain",strain,"/ 21",at),
            metadata
        )
        val at4=Instant.parse("2026-09-10T09:00:00Z")
        val first=bodyBattery(inputs(4.0,at4),at4,ZoneOffset.UTC)
        assertEquals(2.5,first.strainDiagnostics!!.latestIncrementalDebit,0.25)
        val saved4=StoredRecord.from("metadata",bodyBatteryRecord(first,at4))
        val at67=Instant.parse("2026-09-10T13:00:00Z")
        val second=bodyBattery(inputs(6.7,at67,saved4),at67,ZoneOffset.UTC)
        assertEquals(7.0,second.strainDiagnostics!!.transformedTotalCost,0.25)
        assertEquals(4.5,second.strainDiagnostics!!.latestIncrementalDebit,0.35)
        val saved67=StoredRecord.from("metadata",bodyBatteryRecord(second,at67))
        val duplicate=bodyBattery(inputs(6.7,at67,saved67),at67,ZoneOffset.UTC)
        assertEquals(0.0,duplicate.strainDiagnostics!!.latestIncrementalDebit,0.001)
    }

    @Test fun lowerStaleWhoopRefreshDoesNotRefundReserve() {
        val at=Instant.parse("2026-09-10T17:00:00Z")
        val current=bodyBattery(listOf(
            metric("Sleep",420.0,"min",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Recovery",50.0,"%",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Strain",13.0,"/ 21",at)
        ),at,ZoneOffset.UTC)
        val saved=StoredRecord.from("metadata",bodyBatteryRecord(current,at))
        val stale=bodyBattery(listOf(
            metric("Sleep",420.0,"min",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Recovery",50.0,"%",Instant.parse("2026-09-10T07:00:00Z")),
            metric("Strain",6.7,"/ 21",at.plusSeconds(120)),
            saved
        ),at.plusSeconds(120),ZoneOffset.UTC)
        assertEquals(13.0,stale.strainDiagnostics!!.rawWhoopStrain!!,0.001)
        assertEquals(0.0,stale.strainDiagnostics!!.latestIncrementalDebit,0.001)
    }

    @Test fun laterSourceRefreshCannotRechargeEstablishedDay() {
        val wake=Instant.parse("2026-09-10T09:00:00Z")
        val firstAt=Instant.parse("2026-09-10T22:45:00Z")
        val initial=listOf(metric("Sleep",480.0,"min",wake),metric("Recovery",69.0,"%",wake),metric("Strain",6.7,"/ 21",firstAt))
        val first=bodyBattery(initial,firstAt,ZoneOffset.UTC)
        val saved=StoredRecord.from("metadata",bodyBatteryRecord(first,firstAt))
        val later=bodyBattery(initial+metric("Recovery",95.0,"%",firstAt.plusSeconds(60))+saved,Instant.parse("2026-09-11T01:20:00Z"),ZoneOffset.UTC)
        assertEquals(first.morning,later.morning)
        assertTrue("A source refresh without restoration cannot increase Reserve",later.value!!<=first.value!!)
        assertTrue(later.trace!!.awakeMinutes>first.trace!!.awakeMinutes)
    }

    @Test fun awakeTimeDrainContinuesAcrossOneAndThreeHours() {
        val wake=Instant.parse("2026-09-10T09:00:00Z")
        val at0=Instant.parse("2026-09-10T18:00:00Z")
        val inputs=listOf(metric("Sleep",480.0,"min",wake),metric("Recovery",72.0,"%",wake),metric("Strain",6.7,"/ 21",at0))
        val first=bodyBattery(inputs,at0,ZoneOffset.UTC)
        val at1=at0.plus(Duration.ofHours(1))
        val second=bodyBattery(inputs+StoredRecord.from("metadata",bodyBatteryRecord(first,at0)),at1,ZoneOffset.UTC)
        val at3=at0.plus(Duration.ofHours(3))
        val third=bodyBattery(inputs+StoredRecord.from("metadata",bodyBatteryRecord(second,at1)),at3,ZoneOffset.UTC)
        assertTrue(second.value!!<=first.value!!)
        assertTrue(third.value!!<=second.value!!)
        assertTrue(third.trace!!.awakeTimeCost>first.trace!!.awakeTimeCost)
    }

    @Test fun newestBodyBatteryStateWinsOverOlderSameDaySnapshot() {
        val wake=Instant.parse("2026-09-10T09:00:00Z")
        val at10=Instant.parse("2026-09-10T10:00:00Z")
        val inputs=listOf(metric("Sleep",480.0,"min",wake),metric("Recovery",70.0,"%",wake),metric("Strain",6.7,"/ 21",at10))
        val early=bodyBattery(inputs,at10,ZoneOffset.UTC)
        val at13=Instant.parse("2026-09-10T13:00:00Z")
        val newer=bodyBattery(inputs+StoredRecord.from("metadata",bodyBatteryRecord(early,at10)),at13,ZoneOffset.UTC)
        val resolved=bodyBattery(inputs+StoredRecord.from("metadata",bodyBatteryRecord(early,at10))+StoredRecord.from("metadata",bodyBatteryRecord(newer,at13)),at13.plusSeconds(60),ZoneOffset.UTC)
        assertEquals(newer.value,resolved.trace!!.previousReserve)
        assertTrue(resolved.value!!<=newer.value!!)
    }

    @Test fun completedNapMayIncreaseReserveButMentalWellbeingEvaluationCannot() {
        val wake=Instant.parse("2026-09-10T09:00:00Z")
        val at=Instant.parse("2026-09-10T15:00:00Z")
        val inputs=listOf(metric("Sleep",480.0,"min",wake),metric("Recovery",70.0,"%",wake),metric("Strain",0.0,"/ 21",at))
        val before=bodyBattery(inputs,at,ZoneOffset.UTC)
        val saved=StoredRecord.from("metadata",bodyBatteryRecord(before,at))
        val untouched=bodyBattery(inputs+saved,at,ZoneOffset.UTC)
        assertEquals(before.value,untouched.value)
        val nap=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("Sleep"),"value" to p(30.0),"unit" to p("min"),"provider" to p("whoop"),"date" to p("2026-09-10"),"nap" to p(true)),"nap-credit","whoop",at.toString()))
        val restored=bodyBattery(inputs+saved+nap,at,ZoneOffset.UTC)
        assertTrue(restored.value!!>before.value!!)
        assertNotNull(restored.trace!!.increaseReason)
        mentalWellbeing(inputs+saved)
        assertEquals(untouched.value,bodyBattery(inputs+saved,at,ZoneOffset.UTC).value)
    }

    @Test fun previousDayStrainIsIgnoredAfterCompletedMainSleepUntilCurrentCycleArrives() {
        fun owned(name:String,value:Double,unit:String,time:Instant,cycle:String,id:String)=StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p(name),"value" to p(value),"unit" to p(unit),"provider" to p("whoop"),"date" to p(time.atZone(ZoneOffset.UTC).toLocalDate().toString()),"whoopCycleId" to p(cycle),"recordStart" to p(time.toString())),"$id:$name","whoop",time.toString()).changed("updatedAt" to p("2026-09-12T08:00:00Z"),"metadata" to fields("whoopCycleId" to p(cycle),"whoopId" to p(cycle))))
        val oldWake=Instant.parse("2026-09-11T09:00:00Z")
        val newWake=Instant.parse("2026-09-12T07:43:00Z")
        val at=Instant.parse("2026-09-12T08:11:00Z")
        val newSleep=owned("Sleep",231.0,"min",newWake,"cycle-b","sleep-b")
        val morningInputs=listOf(newSleep,owned("Sleep quality",50.0,"%",newWake,"cycle-b","sleep-q-b"),owned("Recovery",46.0,"%",newWake,"cycle-b","recovery-b"),owned("Strain",6.7,"/ 21",oldWake,"cycle-b","strain-a"))
        val pending=bodyBattery(morningInputs,at,ZoneOffset.UTC)
        assertEquals(47,pending.morning)
        assertTrue(pending.strainDiagnostics!!.currentDayPending)
        assertNull(pending.strainDiagnostics!!.rawWhoopStrain)
        assertEquals(0.0,pending.strainDiagnostics!!.transformedTotalCost,0.001)
        val current=bodyBattery(morningInputs+owned("Strain",0.4,"/ 21",newWake,"cycle-c","strain-b"),at,ZoneOffset.UTC)
        assertFalse(current.strainDiagnostics!!.currentDayPending)
        assertEquals(0.4,current.strainDiagnostics!!.rawWhoopStrain!!,0.001)
        assertTrue(current.strainDiagnostics!!.transformedTotalCost<1.0)
    }

    @Test fun poisonedPreviousCycleMaximumIsInvalidatedAndCurrentThreePointThreeWins() {
        fun owned(name:String,value:Double,unit:String,time:Instant,cycle:String,id:String)=StoredRecord.from(
            "personalRecords",
            personal("HealthMetric",fields(
                "metric" to p(name),"value" to p(value),"unit" to p(unit),"provider" to p("whoop"),
                "date" to p(time.atZone(ZoneOffset.UTC).toLocalDate().toString()),
                "whoopCycleId" to p(cycle),"recordStart" to p(time.toString())
            ),"$id:$name","whoop",time.toString()).changed(
                "updatedAt" to p("2026-09-12T09:17:00Z"),
                "metadata" to fields("whoopCycleId" to p(cycle),"whoopId" to p(cycle),"recordStart" to p(time.toString()))
            )
        )
        val wake=Instant.parse("2026-09-12T07:43:00Z")
        val at=Instant.parse("2026-09-12T10:17:00Z")
        val poisoned=StoredRecord.from("metadata",fields(
            "key" to p("body-battery:2026-09-12"),
            "value" to fields(
                "score" to p(38),"morning" to p(47),"recharge" to p(0),
                "strain" to fields(
                    "jamesDayId" to p("2026-09-12"),
                    "highestRawStrain" to p(6.7),
                    "transformedCost" to p(7.1),
                    "whoopCycleId" to p("cycle-a")
                ),
                "trace" to fields("calculatedAt" to p("2026-09-12T08:00:00Z"))
            )
        ))
        val inputs=listOf(
            owned("Sleep",231.0,"min",wake,"cycle-b","sleep-b"),
            owned("Sleep quality",50.0,"%",wake,"cycle-b","sleep-q-b"),
            owned("Recovery",46.0,"%",wake,"cycle-b","recovery-b"),
            owned("Strain",3.3,"/ 21",wake,"cycle-b","strain-b"),
            poisoned
        )
        val fixed=bodyBattery(inputs,at,ZoneOffset.UTC)
        assertEquals(3.3,fixed.strainDiagnostics!!.rawWhoopStrain!!,0.001)
        assertEquals(3.3,fixed.strainDiagnostics!!.highestRawStrain!!,0.001)
        assertEquals("cycle-b",fixed.strainDiagnostics!!.whoopCycleId)
        assertTrue(fixed.strainDiagnostics!!.accepted)
        assertEquals(6.7,fixed.strainDiagnostics!!.invalidatedPersistedStrain!!,0.001)
        assertEquals(0.0,fixed.strainDiagnostics!!.alreadyAccountedCost,0.001)
        assertNotEquals(38,fixed.value)
        val repaired=StoredRecord.from("metadata",bodyBatteryRecord(fixed,at))
        val second=bodyBattery(inputs.filterNot {it===poisoned}+repaired,at.plusSeconds(60),ZoneOffset.UTC)
        assertNull(second.strainDiagnostics!!.invalidatedPersistedStrain)
        assertEquals(3.3,second.strainDiagnostics!!.rawWhoopStrain!!,0.001)
    }

    @Test fun monotonicProtectionIsScopedToOneCycleAndNewCycleMayStartLower() {
        fun dayInputs(wake:Instant,strain:Double,cycle:String,at:Instant)=listOf(
            metric("Sleep",420.0,"min",wake),
            metric("Recovery",60.0,"%",wake),
            StoredRecord.from("personalRecords",personal("HealthMetric",fields(
                "metric" to p("Strain"),"value" to p(strain),"unit" to p("/ 21"),"provider" to p("whoop"),
                "date" to p(wake.atZone(ZoneOffset.UTC).toLocalDate().toString()),
                "whoopCycleId" to p(cycle),"recordStart" to p(wake.toString())
            ),"strain:$cycle","whoop",wake.toString()).changed("updatedAt" to p(at.toString())))
        )
        val wakeA=Instant.parse("2026-09-11T08:00:00Z")
        val atA=Instant.parse("2026-09-11T22:00:00Z")
        val high=bodyBattery(dayInputs(wakeA,18.0,"cycle-a",atA),atA,ZoneOffset.UTC)
        val savedHigh=StoredRecord.from("metadata",bodyBatteryRecord(high,atA))
        val staleSameCycle=bodyBattery(dayInputs(wakeA,4.5,"cycle-a",atA.plusSeconds(60))+savedHigh,atA.plusSeconds(60),ZoneOffset.UTC)
        assertEquals(18.0,staleSameCycle.strainDiagnostics!!.rawWhoopStrain!!,0.001)

        val wakeB=Instant.parse("2026-09-12T07:43:00Z")
        val atB=Instant.parse("2026-09-12T08:15:00Z")
        val lowNewCycle=bodyBattery(dayInputs(wakeB,0.5,"cycle-b",atB)+savedHigh,atB,ZoneOffset.UTC)
        assertEquals(0.5,lowNewCycle.strainDiagnostics!!.rawWhoopStrain!!,0.001)
    }

    @Test fun openWhoopCycleBeginningInsideAcceptedSleepIsCurrentNotPending() {
        fun whoop(name:String,value:Double,time:Instant,cycle:String,start:String,end:String="")=StoredRecord.from(
            "personalRecords",
            personal("HealthMetric",fields(
                "metric" to p(name),"value" to p(value),"unit" to p(if(name=="Strain")"/ 21" else if(name=="Sleep")"min" else "%"),
                "provider" to p("whoop"),"date" to p("2026-09-12"),"whoopCycleId" to p(cycle),
                "recordStart" to p(start),"recordEnd" to p(end),
                "start" to p(if(name=="Sleep")start else ""),"end" to p(if(name=="Sleep")time.toString() else "")
            ),"$cycle:$name","whoop",time.toString()).changed(
                "updatedAt" to p("2026-09-12T09:50:00Z"),
                "metadata" to fields("whoopCycleId" to p(cycle),"whoopId" to p(cycle),"recordStart" to p(start),"recordEnd" to p(end))
            )
        )
        val sleepStart="2026-09-12T03:48:00Z"
        val wake=Instant.parse("2026-09-12T07:43:00Z")
        val at=Instant.parse("2026-09-12T09:53:00Z")
        val previous=whoop("Strain",6.7,Instant.parse("2026-09-11T09:00:00Z"),"cycle-a","2026-09-11T09:00:00Z",wake.toString())
        val current=whoop("Strain",3.3,Instant.parse(sleepStart),"cycle-b",sleepStart)
        val battery=bodyBattery(listOf(
            whoop("Sleep",231.0,wake,"cycle-a",sleepStart,wake.toString()),
            whoop("Sleep quality",50.0,wake,"cycle-a",sleepStart,wake.toString()),
            whoop("Recovery",46.0,wake,"cycle-a",sleepStart,wake.toString()),
            previous,current
        ),at,ZoneOffset.UTC)
        assertFalse(battery.strainDiagnostics!!.currentDayPending)
        assertTrue(battery.strainDiagnostics!!.accepted)
        assertEquals(3.3,battery.strainDiagnostics!!.rawWhoopStrain!!,0.001)
        assertEquals("cycle-b",battery.strainDiagnostics!!.whoopCycleId)
        assertEquals(6.7,battery.strainDiagnostics!!.ignoredPreviousDayStrain!!,0.001)
    }

}