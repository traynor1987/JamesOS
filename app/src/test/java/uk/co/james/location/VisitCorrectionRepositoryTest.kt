package uk.co.james.location

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.core.*
import uk.co.james.data.*
import uk.co.james.database.*

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class)
class VisitCorrectionRepositoryTest {
    private lateinit var db:JamesDatabase
    private lateinit var repo:JamesRepository
    @Before fun setup(){val context=ApplicationProvider.getApplicationContext<Application>();db=Room.inMemoryDatabaseBuilder(context,JamesDatabase::class.java).allowMainThreadQueries().build();repo=JamesRepository(context,db)}
    @After fun close(){db.close()}
    private suspend fun visit(id:String,start:String="2026-07-01T10:00:00Z",end:String="2026-07-01T11:00:00Z"):StoredRecord {
        val row=StoredRecord.from("personalRecords",personal("PlaceVisit",fields("placeId" to p("home"),"title" to p("Home"),"start" to p(start),"end" to p(end),"durationMin" to p(60)),id,"gps",start))
        repo.dao.put(row);return row
    }
    @Test fun oldVisitSplitsOnceAndRevertsWithoutLosingOriginalEvidence()=runBlocking {
        val original=visit("original")
        val secondId=repo.splitVisit(original.recordId,original.rawJson)
        assertEquals("2026-07-01T10:30:00Z",repo.dao.get("personalRecords","original")!!.data().text("end"))
        assertEquals("2026-07-01T10:30:00Z",repo.dao.get("personalRecords",secondId)!!.data().text("start"))
        assertEquals(1,repo.dao.all().count {it.kind=="VisitEvidenceSnapshot"})
        repo.revertLatestVisitCorrection("original")
        assertEquals(original.data(),repo.dao.get("personalRecords","original")!!.data())
        assertTrue(repo.dao.get("personalRecords",secondId)!!.isSupersededVisit())
        assertEquals(1,repo.dao.all().count {it.kind=="VisitCorrection"&&it.data().text("revertedAt").isNotBlank()})
        assertTrue(runCatching {repo.revertLatestVisitCorrection("original")}.isFailure)
    }
    @Test fun mergePreservesBothSourcesAndRevertRestoresBoth()=runBlocking {
        val previous=visit("previous");val current=visit("current","2026-07-01T11:10:00Z","2026-07-01T12:00:00Z")
        repo.mergeVisitWithPrevious("current",current.rawJson)
        assertEquals("2026-07-01T12:00:00Z",repo.dao.get("personalRecords","previous")!!.data().text("end"))
        assertTrue(repo.dao.get("personalRecords","current")!!.isSupersededVisit())
        repo.revertLatestVisitCorrection("previous")
        assertEquals(previous.data(),repo.dao.get("personalRecords","previous")!!.data())
        assertEquals(current.data(),repo.dao.get("personalRecords","current")!!.data())
    }
    @Test fun staleOrShortVisitLeavesNoPartialSnapshots()=runBlocking {
        val original=visit("short","2026-07-01T10:00:00Z","2026-07-01T10:04:00Z")
        assertTrue(runCatching {repo.splitVisit("short",original.rawJson)}.isFailure)
        assertEquals(1,repo.dao.all().size)
        val long=visit("long")
        repo.dao.put(StoredRecord.from(long.store,long.raw().changed("data" to long.data().changed("note" to p("newer edit")))))
        assertTrue(runCatching {repo.splitVisit("long",long.rawJson)}.isFailure)
        assertEquals(2,repo.dao.all().size)
    }
    @Test fun reversalRefusesToOverwriteInterveningEdit()=runBlocking {
        val original=visit("original");repo.splitVisit("original",original.rawJson)
        val changed=repo.dao.get("personalRecords","original")!!
        repo.dao.put(StoredRecord.from(changed.store,changed.raw().changed("data" to changed.data().changed("note" to p("keep this")))))
        assertTrue(runCatching {repo.revertLatestVisitCorrection("original")}.isFailure)
        assertEquals("keep this",repo.dao.get("personalRecords","original")!!.data().text("note"))
        assertTrue(repo.dao.all().single {it.kind=="VisitCorrection"}.data().text("revertedAt").isBlank())
    }
    @Test fun failedLastWriteRollsBackBothVisitsAndSnapshots()=runBlocking {
        val original=visit("original")
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_correction BEFORE INSERT ON records WHEN NEW.kind='VisitCorrection' BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
        assertTrue(runCatching {repo.splitVisit("original",original.rawJson)}.isFailure)
        assertEquals(listOf(original),repo.dao.all())
    }
    @Test fun twoSplitsCanBeUndoneInSequence()=runBlocking {
        val original=visit("chain")
        repo.splitVisit("chain",original.rawJson)
        val first=repo.dao.get("personalRecords","chain")!!
        repo.splitVisit("chain",first.rawJson)
        repo.revertLatestVisitCorrection("chain")
        assertEquals(first.data(),repo.dao.get("personalRecords","chain")!!.data())
        repo.revertLatestVisitCorrection("chain")
        assertEquals(original.data(),repo.dao.get("personalRecords","chain")!!.data())
    }
}
