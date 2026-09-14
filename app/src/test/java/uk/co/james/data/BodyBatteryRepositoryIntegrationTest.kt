package uk.co.james.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.database.*
import uk.co.james.state.*

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class)
class BodyBatteryRepositoryIntegrationTest {
    private lateinit var db:JamesDatabase
    private lateinit var repo:JamesRepository

    @Before fun setUp() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        db=Room.inMemoryDatabaseBuilder(context,JamesDatabase::class.java).allowMainThreadQueries().build()
        repo=JamesRepository(context,db)
    }
    @After fun tearDown(){db.close()}

    private fun whoop(name:String,value:Double,time:Instant,cycle:String,id:String,start:Instant=time)=StoredRecord.from(
        "personalRecords",personal("HealthMetric",fields(
            "metric" to p(name),"value" to p(value),"unit" to p(if(name=="Strain")"/ 21" else if(name=="Sleep")"min" else "%"),
            "provider" to p("whoop"),"whoopCycleId" to p(cycle),"recordStart" to p(start.toString()),
            "start" to p(if(name=="Sleep")start.toString() else ""),"end" to p(if(name=="Sleep")time.toString() else "")
        ),id,"whoop",time.toString()).changed("updatedAt" to p(time.toString()),"metadata" to fields("whoopCycleId" to p(cycle),"whoopId" to p(cycle),"recordStart" to p(start.toString()))))

    @Test fun roomAcceptsThreePointThreeAfterPreviousDaySixPointSevenAndMigrationIsIdempotent()=runBlocking {
        val dayAWake=Instant.parse("2026-09-11T09:00:00Z")
        val dayAAt=Instant.parse("2026-09-12T03:11:00Z")
        val dayA=listOf(whoop("Sleep",480.0,dayAWake,"cycle-a","sleep-a",dayAWake.minus(Duration.ofHours(8))),whoop("Recovery",69.0,dayAWake,"cycle-a","recovery-a"),whoop("Strain",6.7,dayAWake,"cycle-a","strain-a"))
        repo.dao.putAll(dayA)
        repo.persistBodyBattery(bodyBattery(dayA,dayAAt,ZoneOffset.UTC))

        val wake=Instant.parse("2026-09-12T07:43:00Z")
        val at=Instant.parse("2026-09-12T10:17:00Z")
        val poisoned=StoredRecord.from("metadata",fields("key" to p("body-battery:2026-09-12"),"value" to fields(
            "score" to p(38),"morning" to p(47),"recharge" to p(0),
            "strain" to fields("jamesDayId" to p("2026-09-12"),"highestRawStrain" to p(6.7),"transformedCost" to p(7.1),"whoopCycleId" to p("cycle-a")),
            "trace" to fields("calculatedAt" to p("2026-09-12T08:00:00Z"))
        )))
        val dayB=listOf(
            whoop("Sleep",231.0,wake,"cycle-b","sleep-b",Instant.parse("2026-09-12T03:48:00Z")),
            whoop("Sleep quality",50.0,wake,"cycle-b","sleep-q-b",Instant.parse("2026-09-12T03:48:00Z")),
            whoop("Recovery",46.0,wake,"cycle-b","recovery-b",Instant.parse("2026-09-12T03:48:00Z")),
            whoop("Strain",3.3,Instant.parse("2026-09-12T03:48:00Z"),"cycle-b","strain-b",Instant.parse("2026-09-12T03:48:00Z"))
        )
        repo.dao.put(poisoned);repo.dao.putAll(dayB)
        val corrected=bodyBattery(repo.dao.all(),at,ZoneOffset.UTC)
        assertEquals(3.3,corrected.strainDiagnostics!!.highestRawStrain!!,.001)
        val first=repo.persistBodyBattery(corrected)
        assertTrue(first.accepted)
        val key=bodyBatteryRecord(corrected,at).text("key")
        val persisted=repo.dao.get("metadata",key)!!.raw().obj("value")
        assertEquals(3.3,persisted.obj("strain").number("highestRawStrain"),.001)
        assertEquals("cycle-b",persisted.obj("strain").text("whoopCycleId"))
        assertEquals("ACCEPTED",persisted.obj("persistence").text("status"))
        assertNotNull(repo.dao.get("metadata","body-battery:2026-09-12"))
        repo.persistBodyBattery(corrected)
        assertEquals(1,repo.dao.all().count {it.store=="metadata"&&it.recordId==key})
    }
}
