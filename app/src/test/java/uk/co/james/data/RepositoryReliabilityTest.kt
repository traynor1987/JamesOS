package uk.co.james.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.database.*
import uk.co.james.whoop.WhoopMapper

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class)
class RepositoryReliabilityTest {
    private lateinit var context:Context
    private lateinit var db:JamesDatabase
    private lateinit var repo:JamesRepository
    @Before fun setup(){context=ApplicationProvider.getApplicationContext();db=Room.inMemoryDatabaseBuilder(context,JamesDatabase::class.java).allowMainThreadQueries().build();repo=JamesRepository(context,db)}
    @After fun close(){db.close()}
    private fun metric(id:String,at:Instant)=personal("HealthMetric",fields("metric" to p("HRV"),"value" to p(50)),id,"whoop",at.toString()).changed("externalId" to p(id),"updatedAt" to p(at.toString()))

    @Test fun batchUpsertIsIdempotentAndBoundedQueryExcludesOldPhysiology()=runBlocking {
        val now=Instant.parse("2026-09-12T12:00:00Z")
        val batch=(0 until 10_000).map {i->metric("m-$i",now.minus(Duration.ofMinutes(i.toLong()*10))) }
        assertEquals(10_000,repo.externalBatch(batch))
        assertEquals(10_000,repo.externalBatch(batch))
        assertEquals(7,repo.dao.between(now.minus(Duration.ofHours(1)).toString(),now.toString()).size)
        assertTrue(repo.stateInputs(now).size<10_000)
    }

    @Test fun retainedRawWhoopSleepBackfillsOneStableDetailWithoutNetworkOrDuplicate()=runBlocking {
        val now=Instant.parse("2026-09-12T12:00:00Z")
        val raw=json.parseToJsonElement("""{"id":"synthetic-sleep","cycle_id":7,"created_at":"2026-09-12T07:00:00Z","updated_at":"2026-09-12T08:00:00Z","start":"2026-09-12T00:00:00Z","end":"2026-09-12T07:00:00Z","score_state":"SCORED","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000},"sleep_needed":{"baseline_milli":25200000,"need_from_sleep_debt_milli":0,"need_from_recent_strain_milli":0,"need_from_recent_nap_milli":0}}}""").jsonObject
        val original=WhoopMapper.records("sleep",raw).single {it.text("kind")=="ExternalRecord"}
        repo.dao.put(StoredRecord.from("personalRecords",original))
        assertEquals(1,repo.backfillWhoopSleepDetails(now));assertEquals(0,repo.backfillWhoopSleepDetails(now))
        val detail=repo.dao.get("personalRecords","whoop:sleep-detail:synthetic-sleep")!!
        assertEquals(25200000.0,detail.data().number("totalSleepNeedMilli"),0.0)
        assertEquals(1,repo.dao.all().count {it.recordId==detail.recordId});assertEquals(raw,repo.dao.get("personalRecords",original.text("id"))!!.data().obj("original"))
    }

    @Test fun compatibilityBackfillIncludesRetainedMatureDatabaseSleepOutsideTheActiveUiWindow()=runBlocking {
        val now=Instant.parse("2026-09-12T12:00:00Z")
        val raw=json.parseToJsonElement("""{"id":"retained-legacy-sleep","cycle_id":8,"created_at":"2026-07-30T07:00:00Z","updated_at":"2026-07-30T08:00:00Z","start":"2026-07-30T00:00:00Z","end":"2026-07-30T07:00:00Z","score_state":"SCORED","score":{"stage_summary":{"total_light_sleep_time_milli":14400000,"total_slow_wave_sleep_time_milli":3600000,"total_rem_sleep_time_milli":3600000},"sleep_needed":{"baseline_milli":25200000,"need_from_sleep_debt_milli":0,"need_from_recent_strain_milli":0,"need_from_recent_nap_milli":0}}}""").jsonObject
        repo.dao.put(StoredRecord.from("personalRecords",WhoopMapper.records("sleep",raw).single {it.text("kind")=="ExternalRecord"}))

        assertEquals(1,repo.backfillWhoopSleepDetails(now))
        assertNotNull(repo.dao.get("personalRecords","whoop:sleep-detail:retained-legacy-sleep"))
    }

    @Test fun orphanAndStagingCleanupIsAgeBoundAndReferenceSafe()=runBlocking {
        val dir=File(context.filesDir,"archives").apply {mkdirs()}
        val orphan=File(dir,"archive-orphan.json").apply {writeText("private");setLastModified(Instant.now().minus(Duration.ofDays(2)).toEpochMilli())}
        val active=File(dir,"staged-active.json").apply {writeText("private")}
        val referenced=File(dir,"archive-kept.json").apply {writeText("private");setLastModified(Instant.now().minus(Duration.ofDays(2)).toEpochMilli())}
        repo.dao.archive(ArchiveRecord("kept",referenced.absolutePath,"digest",Instant.now().minus(Duration.ofDays(2)).toString(),"daily"))
        val result=repo.cleanupPrivateArchives()
        assertFalse(orphan.exists());assertTrue(active.exists());assertTrue(referenced.exists())
        assertEquals(1,result.orphanFilesCleaned);assertEquals(1,result.retainedBackups)
        active.delete();referenced.delete()
        Unit
    }

    @Test fun approved_place_merge_repoints_facts_but_preserves_duplicate_provenance()=runBlocking {
        val at="2026-09-12T12:00:00Z"
        val canonical=personal("Place",fields("title" to p("Family House"),"category" to p("Family"),"latitude" to p(53.0),"longitude" to p(-2.7),"radius" to p(150)),"place:family","manual",at)
        val duplicate=personal("Place",fields("title" to p("Family Houses"),"category" to p("Unclassified"),"latitude" to p(53.0001),"longitude" to p(-2.7001),"radius" to p(150)),"place:duplicate","manual",at)
        val visit=personal("PlaceVisit",fields("title" to p("Family Houses"),"category" to p("Unclassified"),"placeId" to p("place:duplicate"),"start" to p(at),"end" to p("2026-09-12T13:00:00Z")),"visit:synthetic","gps",at)
        val anchor=personal("LocationAnchor",fields("title" to p("Family Houses"),"category" to p("Unclassified"),"placeId" to p("place:duplicate"),"start" to p(at)),"location:current-anchor","gps",at)
        repo.dao.putAll(listOf(StoredRecord.from("personalRecords",canonical),StoredRecord.from("personalRecords",duplicate),StoredRecord.from("personalRecords",visit),StoredRecord.from("personalRecords",anchor)))

        val result=repo.mergePlaces("place:family","place:duplicate")

        assertEquals(2,result.referencesRepointed)
        assertEquals("Family",repo.dao.get("personalRecords","place:family")!!.data().text("category"))
        assertEquals("MERGED",repo.dao.get("personalRecords","place:duplicate")!!.data().text("status"))
        assertEquals("place:family",repo.dao.get("personalRecords","visit:synthetic")!!.data().text("placeId"))
        assertEquals("place:family",repo.dao.get("personalRecords","location:current-anchor")!!.data().text("placeId"))
        assertEquals(listOf("place:family"),repo.dao.places().map {it.recordId})
    }

    @Test fun ownership_transition_closes_database_active_period_even_when_screen_snapshot_is_empty()=runBlocking {
        val started="2026-09-12T10:00:00Z";val boundary="2026-09-12T10:20:00Z"
        val unknown=personal("OwnershipPeriod",fields("ownership" to p("UNKNOWN"),"ownershipSource" to p("INFERRED"),"start" to p(started),"end" to p("")),"ownership:unknown","gps",started)
        val committed=personal("OwnershipPeriod",fields("ownership" to p("COMMITTED"),"ownershipSource" to p("JAMES_CONFIRMED"),"start" to p(boundary),"end" to p("")),"ownership:committed","manual",boundary)
        repo.dao.put(StoredRecord.from("personalRecords",unknown))

        repo.transitionOwnership(emptyList(),committed)

        assertEquals(boundary,repo.dao.get("personalRecords","ownership:unknown")!!.data().text("end"))
        assertEquals("COMMITTED",repo.currentOwnership()!!.data().text("ownership"))
    }
}
