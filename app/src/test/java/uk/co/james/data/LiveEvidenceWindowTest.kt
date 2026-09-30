package uk.co.james.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.database.*
import uk.co.james.settings.*
import uk.co.james.ui.currentCalibrationTarget
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class)
class LiveEvidenceWindowTest {
    private lateinit var db:JamesDatabase
    private lateinit var repo:JamesRepository
    private val at=Instant.parse("2026-09-30T12:00:00Z")
    @Before fun setup(){val context=ApplicationProvider.getApplicationContext<Application>();db=Room.inMemoryDatabaseBuilder(context,JamesDatabase::class.java).allowMainThreadQueries().build();repo=JamesRepository(context,db)}
    @After fun close(){db.close()}
    private suspend fun stress(id:String,value:Int,time:Instant) {
        repo.dao.put(StoredRecord.from("personalRecords",personal("HealthMetric",fields("metric" to p("James Stress"),"value" to p(value)),id,"wear",time.toString())))
    }
    @Test fun currentRouteRollsWithClockWithoutNavigation()=runBlocking {
        val clock=MutableStateFlow(at);val rows=Channel<List<StoredRecord>>(Channel.UNLIMITED)
        val collector=launch {repo.observeLiveScreenRecords("Today",at.atZone(ZoneOffset.UTC).toLocalDate(),1,clock,ZoneOffset.UTC).collect {rows.send(it)}}
        try {
            withTimeout(10_000){rows.receive()}
            stress("new-health",52,at.plusSeconds(600))
            val planAt=at.plus(Duration.ofHours(40))
            repo.dao.put(StoredRecord.from("personalRecords",personal("ScheduledCommitment",fields("start" to p(planAt.toString()),"end" to p(planAt.plusSeconds(3600).toString())),"new-plan","manual",planAt.toString())))
            clock.value=at.plus(Duration.ofHours(6))
            val updated=withTimeout(10_000){var r=rows.receive();while(r.none {it.recordId=="new-plan"}||r.none {it.recordId=="new-health"})r=rows.receive();r}
            assertTrue(updated.any {it.recordId=="new-plan"});assertTrue(updated.any {it.recordId=="new-health"})
        } finally {collector.cancelAndJoin();rows.close()}
    }
    @Test fun productionReadinessAndFrozenFeedbackFollowIncomingEvidence()=runBlocking {
        stress("stress",37,at)
        val clock=MutableStateFlow(at);val snapshots=Channel<CalibrationProductionInputs>(Channel.UNLIMITED)
        val collector=launch {repo.observeCalibrationProductionInputs(clock,ZoneOffset.UTC).collect {snapshots.send(it)}}
        try {
            assertFalse(withTimeout(10_000){snapshots.receive()}.loaded)
            val initial=withTimeout(10_000){snapshots.receive()}
            assertTrue(initial.loaded)
            val frozen=currentCalibrationTarget("james_stress",initial.records,WellbeingSettings(),EnergyTimeSettings(),initial.at)!!
            assertEquals(37,frozen.score)
            stress("stress",52,at)
            val incoming=withTimeout(10_000){var r=snapshots.receive();while(r.records.first {it.recordId=="stress"}.data().number("value")!=52.0)r=snapshots.receive();r}
            assertEquals(52,currentCalibrationTarget("james_stress",incoming.records,WellbeingSettings(),EnergyTimeSettings(),incoming.at)!!.score)
            assertEquals(37.0,frozen.evidenceSnapshot.number("prediction"),0.0)
            assertEquals(37.0,frozen.evidenceSnapshot.number("jamesStressRawSource"),0.0)
        } finally {collector.cancelAndJoin();snapshots.close()}
    }
}
