package uk.co.james

import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.imports.BackupCodec
import uk.co.james.routines.*
import uk.co.james.time.timeBreakdown
import uk.co.james.calibration.*
import uk.co.james.state.JamesAlgorithmRegistry
import java.time.Instant

class MigrationTest {
    private val stamp="2025-09-09T12:00:00Z"
    private fun event(id:String,type:String,points:Int)=fields("id" to p(id),"title" to p(id),"category" to p("Personal"),"type" to p(type),"points" to p(points),"timestamp" to p(stamp),"createdAt" to p(stamp),"updatedAt" to p(stamp),"localDate" to p("2025-09-09"))
    private fun backup(events:List<JsonObject>)=fields("app" to p("RUT"),"schemaVersion" to p(2),"exportedAt" to p(stamp),"data" to JsonObject(rutStores.associateWith {if(it=="loggedEvents")JsonArray(events)else JsonArray(emptyList())}))
    @Test fun originalFieldsAndScoreSurviveRoundTrip() {
        val initial=event("start","initial",-700).changed("unknownFutureField" to fields("nested" to p("preserve")))
        val plan=BackupCodec.parse(backup(listOf(initial,event("win","positive",20))).toString())
        assertEquals(-680,Ledger.score(plan.rows.map {it.raw()}).toInt())
        val restored=BackupCodec.parse(BackupCodec.export(plan.rows).toString())
        assertEquals(plan.rows,restored.rows)
        assertEquals("preserve",restored.rows.find {it.recordId=="start"}!!.raw().obj("unknownFutureField").text("nested"))
    }
    @Test fun repeatImportIsIdempotent() {val plan=BackupCodec.parse(backup(listOf(event("start","initial",0))).toString());val merged=BackupCodec.merge(plan,plan.rows);assertEquals(1,merged.duplicates);assertTrue(merged.additions.isEmpty())}
    @Test fun conflictingRecordNeverOverwritesCurrent() {val plan=BackupCodec.parse(backup(listOf(event("start","initial",0))).toString());val current=StoredRecord.from("loggedEvents",event("start","initial",500));val merged=BackupCodec.merge(plan,listOf(current));assertEquals(1,merged.conflicts.size);assertTrue(merged.additions.isEmpty())}
    @Test fun secondStartingPointIsNotAdded() {val plan=BackupCodec.parse(backup(listOf(event("old","initial",0))).toString());assertTrue(BackupCodec.merge(plan,listOf(StoredRecord.from("loggedEvents",event("new","initial",10)))).additions.isEmpty())}
    @Test(expected=IllegalArgumentException::class) fun incompleteBackupRejected() {BackupCodec.parse(backup(emptyList()).changed("data" to fields()).toString())}
    @Test(expected=IllegalArgumentException::class) fun orphanRecoveryRejected() {Ledger.validate(listOf(event("start","initial",0),event("recovery","recovery",30).changed("linkedEventId" to p("missing"))))}
    @Test(expected=IllegalArgumentException::class) fun duplicateRecoveryRejected() {Ledger.validate(listOf(event("start","initial",0),event("pull","negative",-10),event("r1","recovery",30).changed("linkedEventId" to p("pull")),event("r2","recovery",30).changed("linkedEventId" to p("pull"))))}
    @Test fun canonicalKeysDoNotCreateFalseDuplicates() {assertEquals(canonical(fields("a" to p(1),"b" to p(2))),canonical(fields("b" to p(2),"a" to p(1))))}
    @Test fun scheduledStreakUsesHistoricalCompletions() {
        val routine=personal("Routine",fields("title" to p("Read"),"days" to JsonArray((0..6).map {p(it)}),"startDate" to p("2025-09-08")),"read")
        val done=personal("RoutineCompletion",fields("routineId" to p("read"),"date" to p("2025-09-08"),"completed" to p(true)))
        assertEquals(Triple(1,1,0),Habits.stats(routine,listOf(done),"2025-09-09"))
    }
    @Test fun overlappingManualTimeIsNotDoubleCounted() {
        fun block(id:String,start:String,end:String,category:String,updated:String)=StoredRecord.from("personalRecords",personal("TimeBlock",fields("category" to p(category),"end" to p(end)),id,timestamp=start).changed("updatedAt" to p(updated)))
        val time=timeBreakdown(listOf(block("a","2025-09-09T10:00:00Z","2025-09-09T12:00:00Z","Work",stamp),block("b","2025-09-09T11:00:00Z","2025-09-09T12:00:00Z","Driving","2025-09-10T12:00:00Z")),"2025-09-09")
        assertEquals(60L,time["Work"]);assertEquals(60L,time["Driving"])
    }
    @Test fun importedArchiveRowsRemainPortable() {
        val archive=StoredRecord.from("archives",fields("id" to p("original"),"payload" to backup(emptyList()),"timestamp" to p(stamp),"type" to p("original-import")))
        assertEquals(archive,BackupCodec.parse(BackupCodec.export(listOf(archive)).toString()).rows.single())
    }
    @Test fun calibrationRecordsRemainPortableAndIndexed() {
        val raw=JamesCalibrationEngine.event("body_battery",65.0,"MUCH TOO HIGH",JamesAlgorithmRegistry.BODY_BATTERY_VERSION,"1.0.0","sleep:123",fields("prediction" to p(65)),timestamp=Instant.parse(stamp),recordId="event")
        val stored=StoredRecord.from("personalRecords",raw)
        assertEquals("body_battery",stored.algorithmId)
        assertEquals("1.0.0",stored.calibrationVersion)
        assertEquals("sleep:123",stored.jamesDayId)
        val restored=BackupCodec.parse(BackupCodec.export(listOf(stored)).toString()).rows.single()
        assertEquals(stored.rawJson,restored.rawJson)
    }
    @Test fun ownership_context_activity_and_interruption_records_restore_without_special_backup_path() {
        val rows=listOf(
            StoredRecord.from("personalRecords",personal("OwnershipPeriod",fields("start" to p(stamp),"end" to p("2025-09-09T13:00:00Z"),"ownership" to p("AUTONOMOUS"),"ownershipSource" to p("JAMES_CONFIRMED"),"provenance" to p("James confirmed")),"ownership",timestamp=stamp)),
            StoredRecord.from("personalRecords",personal("ContextPeriod",fields("start" to p(stamp),"end" to p("2025-09-09T13:00:00Z"),"visitType" to p("RESTING")),"context",timestamp=stamp)),
            StoredRecord.from("personalRecords",personal("LifeFactActivity",fields("title" to p("Gaming"),"activityType" to p("GAMING")),"activity",timestamp=stamp)),
            StoredRecord.from("personalRecords",personal("VisitInterruption",fields("start" to p("2025-09-09T12:20:00Z"),"end" to p("2025-09-09T12:30:00Z"),"reason" to p("PHONE_CALL"),"source" to p("JAMES_CORRECTION")),"interruption",timestamp=stamp))
        )
        val restored=BackupCodec.parse(BackupCodec.export(rows).toString()).rows
        assertEquals(rows.map {it.rawJson}.toSet(),restored.map {it.rawJson}.toSet())
    }
    @Test fun importedOlderActiveCalibrationCannotReplaceCurrentActive() {
        val entry=JamesAlgorithmRegistry.get("live_energy")!!
        fun profile(version:String,bias:Double)=StoredRecord.from("personalRecords",JamesCalibrationEngine.activeProfileRecord(CandidateCalibration("c-$version","live_energy",entry.algorithmVersion,"1.0.0",mapOf("outputBias" to bias),"test",emptyList(),Instant.parse(stamp),CandidateCreator.MANUAL,CandidateStatus.TESTED),version,"test"))
        val current=profile("1.2.0",-2.0);val incoming=profile("1.1.0",-1.0)
        val plan=BackupCodec.parse(BackupCodec.export(listOf(incoming)).toString())
        val merged=BackupCodec.merge(plan,listOf(current))
        assertFalse(merged.additions.single().data().flag("active"));assertTrue(current.data().flag("active"))
    }
    @Test(expected=IllegalArgumentException::class) fun importedOutOfBoundsCalibrationIsRejected() {
        val entry=JamesAlgorithmRegistry.get("live_energy")!!
        val profile=JamesCalibrationEngine.activeProfileRecord(CandidateCalibration("bad","live_energy",entry.algorithmVersion,"1.0.0",mapOf("outputBias" to -2.0),"test",emptyList(),Instant.parse(stamp),CandidateCreator.MANUAL,CandidateStatus.TESTED),"1.1.0","test")
        val bad=profile.changed("data" to profile.obj("data").changed("parameters" to fields("outputBias" to p(999))))
        BackupCodec.parse(BackupCodec.export(listOf(StoredRecord.from("personalRecords",bad))).toString())
    }
}
