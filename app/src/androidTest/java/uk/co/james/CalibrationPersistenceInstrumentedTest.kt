package uk.co.james

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import uk.co.james.calibration.*
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.database.JamesDatabase
import uk.co.james.state.JamesAlgorithmRegistry

@RunWith(AndroidJUnit4::class)
class CalibrationPersistenceInstrumentedTest {
    @Test fun activationRestartRollbackAndIdempotentFeedbackUseRealRoomPath() { runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="calibration-${System.nanoTime()}.db";val now=Instant.now()
        var db=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
        val repo=JamesRepository(context,db);val entry=JamesAlgorithmRegistry.get("body_battery")!!
        repeat(30) {index->
            val prediction=if(index%2==0)20.0 else 60.0;val feedback=if(index%2==0)"VERY LOW" else "OKAY"
            val raw=JamesCalibrationEngine.event("body_battery",prediction,feedback,entry.algorithmVersion,entry.calibrationVersion,"day-${index/3}",fields("prediction" to p(prediction),"sleepMinutes" to p(if(index%2==0)260 else 420)),timestamp=now.minusSeconds((30-index).toLong()*60),recordId="calibration-event:$index")
            assertTrue(repo.saveCalibrationEvent(raw));assertFalse(repo.saveCalibrationEvent(raw))
        }
        val rows=repo.dao.all();val events=rows.mapNotNull(JamesCalibrationEngine::parse);val active=JamesCalibrationEngine.activeCalibration(rows,"body_battery",entry.calibrationVersion,entry.algorithmVersion)
        val dependencies=JamesCalibrationEngine.dependencyVersions(rows,"body_battery")
        val candidate=JamesCalibrationEngine.candidate(events,"body_battery",active.version,active.parameters,baseCalibrationSetId=active.setId,dependencyVersions=dependencies)!!
        val backtest=JamesCalibrationEngine.backTest(events,candidate);assertTrue(backtest.robustValidation)
        repo.saveAnalysedCandidate(candidate,backtest,CandidateStatus.TESTED)
        val activated=repo.activateCalibrationCandidate(candidate.id);assertEquals("1.1.0",activated)
        db.close();db=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
        val reopened=JamesRepository(context,db);var persisted=JamesCalibrationEngine.activeCalibration(reopened.dao.all(),"body_battery",entry.calibrationVersion,entry.algorithmVersion)
        assertEquals("1.1.0",persisted.version);assertEquals(candidate.parameters["outputBias"],persisted.parameters["outputBias"])
        assertEquals("1.0.0",reopened.rollbackCalibration("body_battery"))
        db.close();db=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
        persisted=JamesCalibrationEngine.activeCalibration(db.records().all(),"body_battery",entry.calibrationVersion,entry.algorithmVersion)
        assertEquals("1.0.0",persisted.version)
        assertEquals(2,db.records().calibrationRows("body_battery",listOf("CalibrationProfile"),100).size)
        db.close();context.deleteDatabase(name)
    }}

    @Test fun changedEvidenceMakesTestedCandidateStaleAndBlocksActivation() { runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="stale-${System.nanoTime()}.db";val db=Room.databaseBuilder(context,JamesDatabase::class.java,name).build()
        try {
            val repo=JamesRepository(context,db);val entry=JamesAlgorithmRegistry.get("live_energy")!!;val now=Instant.now()
            repeat(25) {index->val prediction=if(index%2==0)25.0 else 65.0;val feedback=if(index%2==0)"VERY LOW" else "OKAY";repo.saveCalibrationEvent(JamesCalibrationEngine.event("live_energy",prediction,feedback,entry.algorithmVersion,entry.calibrationVersion,"day",fields("prediction" to p(prediction)),timestamp=now.minusSeconds((25-index).toLong()*60),recordId="live:$index"))}
            val rows=repo.dao.all();val events=rows.mapNotNull(JamesCalibrationEngine::parse);val active=JamesCalibrationEngine.activeCalibration(rows,"live_energy",entry.calibrationVersion,entry.algorithmVersion);val dependencies=JamesCalibrationEngine.dependencyVersions(rows,"live_energy")
            val candidate=JamesCalibrationEngine.candidate(events,"live_energy",active.version,active.parameters,baseCalibrationSetId=active.setId,dependencyVersions=dependencies)!!;val result=JamesCalibrationEngine.backTest(events,candidate)
            repo.saveAnalysedCandidate(candidate,result,CandidateStatus.TESTED)
            repo.saveCalibrationEvent(JamesCalibrationEngine.event("live_energy",40.0,"LOW",entry.algorithmVersion,entry.calibrationVersion,"day",fields("prediction" to p(40)),recordId="live:new"))
            assertEquals("STALE",repo.dao.get("personalRecords",candidate.id)!!.data().text("status"))
            assertThrows(IllegalArgumentException::class.java) {runBlocking {repo.activateCalibrationCandidate(candidate.id)}}
        } finally {db.close();context.deleteDatabase(name)}
    }}
}
