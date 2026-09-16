package uk.co.james

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.settings.EnergyTimeSettings
import uk.co.james.state.*

class EnergyTimeEngineTest {
    private val clock=Instant.parse("2026-09-13T12:00:00Z")
    private fun row(kind:String,data:kotlinx.serialization.json.JsonObject,at:Instant=clock,id:String="$kind:${at.epochSecond}",source:String="manual")=
        StoredRecord.from("personalRecords",personal(kind,data,id,source,at.toString()).changed("updatedAt" to p(at.toString())))
    private fun base():List<StoredRecord> {
        val wake=clock.minus(Duration.ofHours(4))
        return listOf(
            row("HealthMetric",fields("metric" to p("Sleep"),"value" to p(231),"unit" to p("min"),"start" to p(wake.minus(Duration.ofMinutes(231)).toString()),"end" to p(wake.toString()),"provider" to p("whoop")),wake,"sleep","whoop"),
            row("HealthMetric",fields("metric" to p("Recovery"),"value" to p(46),"unit" to p("%"),"provider" to p("whoop")),wake,"recovery","whoop")
        )
    }

    @Test fun highReportedEnergyCanCoexistWithLowSustainability() {
        val wake=clock.minus(Duration.ofHours(15))
        val shortSleep=row("HealthMetric",fields("metric" to p("Sleep"),"value" to p(231),"unit" to p("min"),"start" to p(wake.minus(Duration.ofMinutes(231)).toString()),"end" to p(wake.toString()),"provider" to p("whoop")),wake,"short-sleep","whoop")
        val recovery=row("HealthMetric",fields("metric" to p("Recovery"),"value" to p(46),"unit" to p("%"),"provider" to p("whoop")),wake,"short-recovery","whoop")
        val check=row("WellbeingCheckIn",fields("mood" to p("OKAY"),"energy" to p("VERY HIGH"),"anxiety" to p("NONE")),clock.minus(Duration.ofMinutes(2)),"check")
        val result=rightNowSummary(listOf(shortSleep,recovery,check),clock=clock,zone=ZoneOffset.UTC)
        assertTrue(result.liveEnergy.score>=60)
        assertTrue(result.sleepiness.score>=60)
        assertTrue(result.sustainability.score<result.liveEnergy.score)
        assertTrue(result.crashRisk.score>0)
        assertNotEquals(result.bodyBattery,result.liveEnergy.score)
    }

    @Test fun personalTimeRelievesSameCountdownPressure() {
        val shift=row("WorkShift",fields("title" to p("Work"),"start" to p(clock.plus(Duration.ofMinutes(90)).toString()),"fixedConstraint" to p(true)),clock.plus(Duration.ofMinutes(90)),"shift")
        val none=rightNowSummary(base()+shift,clock=clock,zone=ZoneOffset.UTC)
        val personal=row("TimeBlock",fields("title" to p("Played video games"),"category" to p("Personal"),"start" to p(clock.minus(Duration.ofHours(3)).toString()),"end" to p(clock.toString())),clock.minus(Duration.ofHours(3)),"personal")
        val achieved=rightNowSummary(base()+shift+personal,clock=clock,zone=ZoneOffset.UTC)
        assertEquals(90,none.nextConstraint!!.usableMinutes)
        assertEquals(180,achieved.personalMinutes)
        assertTrue(achieved.timePressure.score<none.timePressure.score)
    }

    @Test fun onlyKnownBuffersReduceUsableWindow() {
        val shift=row("WorkShift",fields("title" to p("Work"),"start" to p(clock.plus(Duration.ofMinutes(120)).toString()),"fixedConstraint" to p(true),"preparationMinutes" to p(20),"travelMinutes" to p(10)),clock.plus(Duration.ofMinutes(120)),"shift")
        val result=rightNowSummary(base()+shift,clock=clock,zone=ZoneOffset.UTC)
        assertEquals(90,result.nextConstraint!!.usableMinutes)
    }

    @Test fun scheduledCommitmentCreatesUpcomingPressureWithoutActualOwnership() {
        val scheduled=row("ScheduledCommitment",fields(
            "title" to p("Work"),
            "start" to p(clock.plus(Duration.ofMinutes(75)).toString()),
            "end" to p(clock.plus(Duration.ofHours(6)).toString()),
            "plannedOwnership" to p("WORK"),
            "status" to p("UPCOMING"),
            "fixedConstraint" to p(true),
            "preparationMinutes" to p(15)
        ),clock,"rota","shift_tracker")
        val result=rightNowSummary(base()+scheduled,clock=clock,zone=ZoneOffset.UTC)
        assertEquals("Work",result.nextConstraint!!.title)
        assertEquals(60,result.nextConstraint!!.usableMinutes)
        assertFalse((base()+scheduled).any {it.kind=="OwnershipPeriod"})
    }

    @Test fun freeAndAllDayCalendarEntriesDoNotPretendToBeConstraints() {
        val free=row("ScheduledCommitment",fields("title" to p("Optional reminder"),"start" to p(clock.plus(Duration.ofMinutes(20)).toString()),"status" to p("UPCOMING"),"fixedConstraint" to p(false)),clock,"free","android_calendar")
        val allDay=row("ScheduledCommitment",fields("title" to p("Birthday"),"start" to p(clock.plus(Duration.ofMinutes(10)).toString()),"status" to p("UPCOMING"),"allDay" to p(true),"fixedConstraint" to p(true)),clock,"all-day","android_calendar")
        assertNull(rightNowSummary(base()+free+allDay,clock=clock,zone=ZoneOffset.UTC).nextConstraint)
    }

    @Test fun plannedPersonalCommitmentStillHasADeadline() {
        val cinema=row("ScheduledCommitment",fields("title" to p("Cinema"),"start" to p(clock.plus(Duration.ofMinutes(45)).toString()),"status" to p("UPCOMING"),"plannedOwnership" to p("AUTONOMOUS"),"fixedConstraint" to p(true)),clock,"cinema","android_calendar")
        assertEquals("Cinema",rightNowSummary(base()+cinema,clock=clock,zone=ZoneOffset.UTC).nextConstraint?.title)
    }

    @Test fun nutritionIsContextAndMissingNutritionIsNeutral() {
        val meal=row("Nutrition",fields("title" to p("Breakfast"),"energyKcal" to p(812),"proteinGrams" to p(32),"provider" to p("com.mynetdiary")),clock.minus(Duration.ofMinutes(30)),"meal","health_connect")
        val withMeal=rightNowSummary(base()+meal,clock=clock,zone=ZoneOffset.UTC)
        val without=rightNowSummary(base(),clock=clock,zone=ZoneOffset.UTC)
        assertEquals(812.0,withMeal.nutrition.todayEnergyKcal!!,.01)
        assertTrue(withMeal.liveEnergy.contributors.any {it.name=="Recent meal"&&it.contribution==0.0})
        assertFalse(without.liveEnergy.contributors.any {it.name=="Recent meal"})
    }

    @Test fun timePressureCheckInIsBoundedAndDoesNotChangeAnxiety() {
        val shift=row("WorkShift",fields("title" to p("Work"),"start" to p(clock.plus(Duration.ofMinutes(60)).toString()),"fixedConstraint" to p(true)),clock.plus(Duration.ofMinutes(60)),"shift")
        val report=row("TimePressureCheckIn",fields("pressure" to p("EXTREMELY")),clock.minus(Duration.ofMinutes(1)),"pressure")
        val beforeAnxiety=mentalWellbeing(base()+shift,clock=clock,zone=ZoneOffset.UTC).anxiety.score
        val result=rightNowSummary(base()+shift+report,EnergyTimeSettings(),clock,ZoneOffset.UTC)
        val afterAnxiety=mentalWellbeing(base()+shift+report,clock=clock,zone=ZoneOffset.UTC).anxiety.score
        assertEquals(beforeAnxiety,afterAnxiety)
        assertTrue(result.timePressure.contributors.first {it.name=="Time-pressure check-in"}.contribution<=20)
    }

    @Test fun noKnownConstraintIsNotPresentedAsMeasuredZero() {
        val result=rightNowSummary(base(),clock=clock,zone=ZoneOffset.UTC)
        assertEquals(5,result.timePressure.score)
        assertEquals("NO_KNOWN_CONSTRAINT",result.timePressure.evidenceState)
        assertEquals("LIMITED",result.timePressure.confidence)
    }

    @Test fun freshDirectNoPressureCanRepresentZero() {
        val report=row("TimePressureCheckIn",fields("pressure" to p("NOT AT ALL")),clock.minus(Duration.ofMinutes(1)),"pressure")
        val result=rightNowSummary(base()+report,clock=clock,zone=ZoneOffset.UTC)
        assertEquals(0,result.timePressure.score)
        assertEquals("DIRECT",result.timePressure.evidenceState)
    }
}
