package uk.co.james.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.database.*
import uk.co.james.schedule.*
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class)
class EvidenceWindowTest {
    private lateinit var db:JamesDatabase
    private lateinit var repo:JamesRepository
    private val now=Instant.parse("2026-09-30T12:00:00Z")
    @Before fun setup(){val context=ApplicationProvider.getApplicationContext<Application>();db=Room.inMemoryDatabaseBuilder(context,JamesDatabase::class.java).allowMainThreadQueries().build();repo=JamesRepository(context,db)}
    @After fun close(){db.close()}
    private suspend fun put(kind:String,id:String,at:String,data:kotlinx.serialization.json.JsonObject=fields()) {
        repo.dao.put(StoredRecord.from("personalRecords",personal(kind,data,id,"manual",at)))
    }
    private suspend fun route(name:String)=repo.observeScreenRecords(name,LocalDate.parse("2026-09-30"),1,now,ZoneOffset.UTC).first()
    @Test fun scheduleAndTodayIncludeFutureRotaButDoNotLoadFuturePhysiology()=runBlocking {
        put("ScheduledCommitment","tomorrow","2026-10-01T12:00:00Z",fields("start" to p("2026-10-01T12:00:00Z"),"end" to p("2026-10-01T13:00:00Z")))
        put("ScheduledCommitment","week","2026-10-06T12:00:00Z")
        put("HealthMetric","future-health","2026-10-01T12:00:00Z")
        assertTrue(route("Today").any {it.recordId=="tomorrow"})
        assertFalse(route("Today").any {it.recordId=="week"||it.recordId=="future-health"})
        assertTrue(route("Schedule").any {it.recordId=="week"})
        assertFalse(route("Schedule").any {it.recordId=="future-health"})
    }
    @Test fun lifeBalanceIncludesSixtyDayOwnershipAndBoundarySpanningPeriod()=runBlocking {
        put("OwnershipPeriod","old","2026-08-01T10:00:00Z",fields("start" to p("2026-08-01T10:00:00Z"),"end" to p("2026-08-01T11:00:00Z")))
        put("OwnershipPeriod","crosses","2026-06-01T10:00:00Z",fields("start" to p("2026-06-01T10:00:00Z"),"end" to p("2026-07-03T10:00:00Z")))
        put("OwnershipPeriod","outside","2026-06-01T09:00:00Z",fields("end" to p("2026-06-01T10:00:00Z")))
        assertEquals(setOf("old","crosses"),route("Life Balance").map {it.recordId}.toSet())
        assertFalse(route("Today").any {it.recordId=="old"})
    }
    @Test fun oldCalendarConfigurationIsReadableAndShownInSettingsAndSchedule()=runBlocking {
        put("CalendarSelection","selection","2026-01-01T00:00:00Z",fields("enabled" to p(true),"calendarId" to p("1")))
        put("CalendarClassificationRule","rule","2026-01-01T00:00:00Z",fields("enabled" to p(true)))
        assertEquals(2,repo.dao.calendarConfiguration().size)
        assertTrue(route("Settings").any {it.recordId=="selection"})
        assertTrue(route("Schedule").any {it.recordId=="rule"})
    }
    @Test fun disablingLastCalendarRetiresFutureAndOngoingPlansButPreservesActualHistory()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val source=CalendarScheduleSource(context,repo)
        val planned=CalendarInstance("event","1","Work",now.plusSeconds(3600),now.plusSeconds(7200),false,"BUSY",1).asCommitment().asRecord()
        val ongoing=CalendarInstance("ongoing","1","Work",now.minusSeconds(3600),now.plusSeconds(3600),false,"BUSY",1).asCommitment().asRecord()
        repo.dao.putAll(listOf(planned,ongoing).map {StoredRecord.from("personalRecords",it)})
        put("OwnershipPeriod","actual",now.minusSeconds(3600).toString(),fields("ownership" to p("WORK")))
        source.refresh(emptySet(),now)
        assertTrue(repo.dao.all().filter {it.kind=="ScheduledCommitment"}.all {it.data().text("status")=="CANCELLED"&&!it.data().flag("fixedConstraint")})
        assertEquals("WORK",repo.dao.get("personalRecords","actual")!!.data().text("ownership"))
    }
    @Test fun calibrationEvidenceOutsideFortyDaysIsAlgorithmSpecificAndRetained()=runBlocking {
        put("CalibrationEvent","weekly-old","2026-04-01T00:00:00Z",fields("algorithmId" to p("life_balance_v2")))
        put("CalibrationEvent","other","2026-04-01T00:00:00Z",fields("algorithmId" to p("sleepiness")))
        assertEquals(listOf("weekly-old"),repo.calibrationEvidence("life_balance_v2").map {it.recordId})
        assertEquals(2,repo.observeCalibrationLab().first().count {it.kind=="CalibrationEvent"})
        assertEquals(2,route("Calibrate James OS").count {it.kind=="CalibrationEvent"})
    }
    @Test fun retainedOldEvidenceUsesSameDatasetForAnalysisAndCandidateSave()=runBlocking {
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get("body_battery")!!
        repeat(30) {index->
            val prediction=if(index%2==0)20.0 else 60.0
            val raw=uk.co.james.calibration.JamesCalibrationEngine.event("body_battery",prediction,if(index%2==0)"VERY LOW" else "OKAY",entry.algorithmVersion,entry.calibrationVersion,"day-${index/3}",fields("prediction" to p(prediction),"sleepMinutes" to p(if(index%2==0)260 else 420)),timestamp=now.minus(Duration.ofDays(60)).minusSeconds((30-index).toLong()*60),recordId="old-calibration:$index")
            repo.saveCalibrationEvent(raw)
        }
        val inputs=repo.calibrationAnalysisInputs("body_battery")
        val events=inputs.mapNotNull(uk.co.james.calibration.JamesCalibrationEngine::parse)
        assertEquals(30,events.size)
        val active=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(inputs,"body_battery",entry.calibrationVersion,entry.algorithmVersion)
        val candidate=uk.co.james.calibration.JamesCalibrationEngine.candidate(events,"body_battery",active.version,active.parameters,baseCalibrationSetId=active.setId,dependencyVersions=uk.co.james.calibration.JamesCalibrationEngine.dependencyVersions(inputs,"body_battery"))!!
        val result=uk.co.james.calibration.JamesCalibrationEngine.backTest(events,candidate)
        repo.saveAnalysedCandidate(candidate,result,uk.co.james.calibration.CandidateStatus.DRAFT)
        assertEquals("DRAFT",repo.dao.get("personalRecords",candidate.id)!!.data().text("status"))
    }

}
