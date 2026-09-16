package uk.co.james

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

class ContextLoadTest {
    private val now=Instant.parse("2026-09-13T12:00:00Z")
    private fun record(kind:String,data:kotlinx.serialization.json.JsonObject,id:String,at:Instant)=
        StoredRecord.from("personalRecords",personal(kind,data,id,"manual",at.toString()).changed("updatedAt" to p(at.toString())))
    private fun context(id:String,type:String,start:Instant,end:Instant?=null,place:String="Same place")=
        record("ContextPeriod",fields("visitType" to p(type),"start" to p(start.toString()),"end" to p(end?.toString()?:""),"placeName" to p(place)) ,id,start)
    private fun ownership(id:String,type:String,start:Instant,end:Instant)=
        record("OwnershipPeriod",fields("ownership" to p(type),"ownershipSource" to p("JAMES_CONFIRMED"),"start" to p(start.toString()),"end" to p(end.toString())),id,start)
    @Test fun unknownContextIsNotAPenalty() {
        val current=currentContext(emptyList(),now)
        assertEquals(0,current.score)
        assertNull(current.active)
    }
    @Test fun emptyHistoryDoesNotClaimLowPersonalTime() {
        val balance=lifeBalance(emptyList(),now)
        assertEquals("LEARNING",balance.trend)
        assertFalse(balance.hurting.contains("Low personal time"))
        assertEquals("LEARNING",balance.autonomy)
    }
    @Test fun calmObligationIsLimited() {
        val row=context("visit","OBLIGATION",now.minus(Duration.ofHours(3)))
        val current=currentContext(listOf(row),now)
        assertTrue(current.score in 12..20)
        assertFalse(current.difficultActive)
    }
    @Test fun difficultIntervalIsStrongerAndBounded() {
        val period=context("visit","OBLIGATION",now.minus(Duration.ofHours(3)))
        val difficult=record("ContextDifficultInterval",fields("contextId" to p("visit"),"start" to p(now.minus(Duration.ofMinutes(30)).toString()),"end" to p("")),"difficult",now.minus(Duration.ofMinutes(30)))
        val current=currentContext(listOf(period,difficult),now)
        assertTrue(current.difficultActive)
        assertTrue(current.score>40)
        assertTrue(current.score<=100)
    }
    @Test fun samePlaceDoesNotInheritPreviousInterpretation() {
        val old=context("old","OBLIGATION",now.minus(Duration.ofDays(1)),now.minus(Duration.ofHours(20)))
        val oldDifficult=record("ContextDifficultInterval",fields("contextId" to p("old"),"start" to p(now.minus(Duration.ofHours(23)).toString()),"end" to p(now.minus(Duration.ofHours(22)).toString())),"old-d",now.minus(Duration.ofHours(23)))
        val current=context("new","PERSONAL",now.minus(Duration.ofMinutes(20)),null,"Same place")
        val summary=currentContext(listOf(old,oldDifficult,current),now)
        assertEquals(VisitType.PERSONAL,summary.active?.visitType)
        assertFalse(summary.difficultActive)
        assertTrue(summary.score<20)
    }
    @Test fun rollingBalanceIsBoundedAndRecentPersonalTimeHelps() {
        val difficultDays=(1L..28L).flatMap {day->
            val start=now.minus(Duration.ofDays(day)).minus(Duration.ofHours(3))
            listOf(context("o"+day,"OBLIGATION",start,start.plus(Duration.ofHours(3))),
                record("ContextDifficultInterval",fields("contextId" to p("o"+day),"start" to p(start.plus(Duration.ofHours(1)).toString()),"end" to p(start.plus(Duration.ofHours(2)).toString())),"d"+day,start.plus(Duration.ofHours(1))))
        }
        val personal=ownership("personal","AUTONOMOUS",now.minus(Duration.ofHours(2)),now)
        val balance=lifeBalance(difficultDays+personal,now)
        assertNotNull(balance.days28.score)
        assertTrue(balance.days28.score!! in 0..100)
        assertTrue(balance.days7.personalMinutes>=120)
    }
    @Test fun unchangedLifeBalanceIsNotReportedAsImproving() {
        val stable=ownership("stable-autonomy","AUTONOMOUS",now.minus(Duration.ofDays(14)),now)
        val balance=lifeBalance(listOf(stable),now)
        assertEquals(balance.days7.score,balance.days14.score)
        assertEquals("STEADY",balance.trend)
    }
    @Test fun context_and_activity_do_not_create_ownership() {
        val context=context("home","PERSONAL",now.minus(Duration.ofHours(3)),now)
        val activity=record("LifeFactActivity",fields("title" to p("Gaming")),"gaming",now)
        assertNull(lifeBalance(listOf(context,activity),now).current.score)
    }
    @Test fun historicalRutIsNotUsedOrRewritten() {
        val legacy=StoredRecord.from("loggedEvents",fields("id" to p("legacy"),"title" to p("Old pull"),"category" to p("INDEPENDENCE"),"points" to p(-100),"type" to p("negative"),"timestamp" to p(now.toString()),"createdAt" to p(now.toString()),"updatedAt" to p(now.toString()),"localDate" to p(now.toString().substring(0,10)) ))
        assertNull(lifeBalance(listOf(legacy),now).current.score)
    }
    @Test fun balanceV2RequiresCoverageButDoesNotTreatUnknownAsNegative() {
        val start=now.minus(Duration.ofHours(8))
        val mostlyUnknown=listOf(
            ownership("personal","AUTONOMOUS",start,start.plus(Duration.ofHours(2))),
            ownership("unknown","UNKNOWN",start.plus(Duration.ofHours(2)),now)
        )
        val summary=lifeBalanceV2(mostlyUnknown,now)
        assertNull(summary.days7.score)
        assertEquals("LEARNING",summary.days7.evidenceState)
        assertTrue(summary.days7.coveragePercent in 20..30)
    }
    @Test fun balanceV2UsesOwnershipNotPlaceActivityHealthOrLegacyRut() {
        val facts=(0L..6L).flatMap { day->
            val start=now.minus(Duration.ofDays(day)).minus(Duration.ofHours(9))
            listOf(
                ownership("personal-$day","AUTONOMOUS",start,start.plus(Duration.ofHours(5))),
                ownership("constrained-$day","CONSTRAINED",start.plus(Duration.ofHours(5)),start.plus(Duration.ofHours(9)))
            )
        }
        val unrelated=listOf(
            record("LifeFactActivity",fields("title" to p("Gaming")),"game",now),
            record("HealthMetric",fields("metric" to p("Recovery"),"value" to p(12)),"recovery",now),
            StoredRecord.from("loggedEvents",fields("id" to p("rut"),"title" to p("Old pull"),"points" to p(-870),"type" to p("negative"),"timestamp" to p(now.toString()),"createdAt" to p(now.toString()),"updatedAt" to p(now.toString()),"localDate" to p(now.toString().substring(0,10))))
        )
        val baseline=lifeBalanceV2(facts,now).days7
        val withUnrelated=lifeBalanceV2(facts+unrelated,now).days7
        assertNotNull(baseline.score)
        assertEquals(baseline.score,withUnrelated.score)
        assertEquals(baseline.ownershipDistribution,withUnrelated.ownershipDistribution)
    }
    @Test fun balanceV2ShowsConstrainedTimeAndFragmentationWithoutDoubleCounting() {
        val facts=(0L..6L).flatMap { day->
            val start=now.minus(Duration.ofDays(day)).minus(Duration.ofHours(9))
            listOf(
                ownership("personal-$day","AUTONOMOUS",start,start.plus(Duration.ofHours(4))),
                ownership("constrained-$day","CONSTRAINED",start.plus(Duration.ofHours(4)),start.plus(Duration.ofHours(9))),
                record("VisitInterruption",fields("start" to p(start.plus(Duration.ofHours(1)).toString()),"end" to p(start.plus(Duration.ofMinutes(90)).toString())),"interrupt-$day",start.plus(Duration.ofHours(1)))
            )
        }
        val balance=lifeBalanceV2(facts,now).days7
        assertNotNull(balance.score)
        assertEquals(1470,balance.autonomousMinutes)
        assertEquals(2100,balance.constrainedMinutes)
        assertEquals(7,balance.interruptions)
        assertEquals(150,balance.longestAutonomousBlockMinutes)
        assertTrue(balance.score!! < 50)
    }
}
