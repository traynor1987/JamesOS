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
}
