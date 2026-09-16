package uk.co.james.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.database.*
import uk.co.james.imports.*
import uk.co.james.routines.*
import uk.co.james.state.MentalWellbeingSummary
import uk.co.james.state.RightNowSummary
import uk.co.james.state.rightNowRecord
import uk.co.james.state.wellbeingRecord
import uk.co.james.state.BodyBattery
import uk.co.james.state.bodyBatteryRecord
import uk.co.james.calibration.*
import uk.co.james.location.reconstructLegacyVisits
import uk.co.james.whoop.WhoopMapper
import java.io.File
import java.time.*
import java.util.concurrent.ConcurrentHashMap

data class BodyBatteryPersistenceResult(val accepted:Boolean,val reason:String)
data class ArchiveCleanupDiagnostics(val retainedBackups:Int,val orphanFilesFound:Int,val orphanFilesCleaned:Int,val stagedImportsRetained:Int,val failures:Int,val completedAt:String)

internal fun sameBodyBatteryStrainOwner(oldData:JsonObject?,newData:JsonObject):Boolean {
    if(oldData==null)return false
    val oldStrain=oldData.obj("strain")
    val newStrain=newData.obj("strain")
    val sameDay=oldStrain.text("jamesDayId").isNotBlank()&&oldStrain.text("jamesDayId")==newStrain.text("jamesDayId")
    val sameBoundary=oldStrain.text("sleepBoundary").isNotBlank()&&oldStrain.text("sleepBoundary")==newStrain.text("sleepBoundary")
    val oldCycle=oldStrain.text("whoopCycleId")
    val newCycle=newStrain.text("whoopCycleId")
    val sameCycle=when {
        oldCycle.isNotBlank()||newCycle.isNotBlank() -> oldCycle.isNotBlank()&&oldCycle==newCycle
        else -> oldStrain.text("sourceStart").isNotBlank()&&oldStrain.text("sourceStart")==newStrain.text("sourceStart")
    }
    return sameDay&&sameBoundary&&sameCycle
}

class JamesRepository(val context: Context, val db: JamesDatabase) {
    val dao=db.records()
    /** Global reactive state is deliberately bounded. Full history is accessed only
     * by explicit backup/import queries and route-specific windows. */
    val records=dao.observeStateInputs(Instant.now().minus(Duration.ofDays(40)).toString())
    val imports=dao.imports()
    private val archiveDir=File(context.filesDir,"archives").apply { mkdirs() }
    private val activeStaged=ConcurrentHashMap.newKeySet<String>()
    suspend fun stateInputs(at:Instant=Instant.now()):List<StoredRecord> = dao.stateInputs(at.minus(Duration.ofDays(40)).toString())
    /** One bounded compatibility pass after the Part 2 mapper is introduced.
     * New/revised provider rows are mapped in WhoopMapper.records during every
     * ordinary sync; this only reconstructs details from already-retained raw
     * evidence and never makes an API request. */
    suspend fun backfillWhoopSleepDetails(at:Instant=Instant.now(),force:Boolean=false):Int = db.withTransaction {
        val key="whoop-sleep-detail-backfill:${WhoopMapper.SLEEP_DETAIL_MAPPING_VERSION}"
        if(!force&&dao.get("metadata",key)!=null)return@withTransaction 0
        var afterTimestamp=""
        var afterRecordId=""
        var mapped=0
        while(true) {
            val page=dao.sourceKindPage("whoop","ExternalRecord",afterTimestamp,afterRecordId,100)
            if(page.isEmpty())break
            val candidates=page.asSequence().filter {it.data().text("type")=="sleep"}
                .mapNotNull {WhoopMapper.sleepDetailRecord(it.data().obj("original"))}
                .map {StoredRecord.from("personalRecords",it)}.groupBy {it.recordId}.values.map {versions->versions.maxBy {it.updatedAt}}
            val existing=candidates.map {it.recordId}.distinct().chunked(100).flatMap {dao.getByIds("personalRecords",it)}.associateBy {it.recordId}
            val accepted=candidates.filter {incoming->existing[incoming.recordId]?.let {old->old.source==incoming.source&&incoming.updatedAt>=old.updatedAt}?:true}
            if(accepted.isNotEmpty())dao.putAll(accepted)
            mapped+=accepted.size
            val last=page.last();afterTimestamp=last.timestamp;afterRecordId=last.recordId
        }
        dao.put(StoredRecord.from("metadata",fields("key" to p(key),"value" to fields("mappingVersion" to p(WhoopMapper.SLEEP_DETAIL_MAPPING_VERSION),"completedAt" to p(now()),"records" to p(mapped)))))
        mapped
    }
    suspend fun readFile(uri: Uri): String = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input -> val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var count=input.read(buffer);while(count!=-1){require(output.size()+count<=40*1024*1024){"Backup exceeds 40 MB."};output.write(buffer,0,count);count=input.read(buffer)};output.toString("UTF-8") } ?: error("Cannot open the chosen file.")
    }
    suspend fun stage(uri: Uri): String { val text=readFile(uri);BackupCodec.parse(text);return archive(text,"staged").path.also {activeStaged+=it} }
    fun releaseStaged(path:String){activeStaged.remove(path)}
    suspend fun discardStaged(path:String)=withContext(Dispatchers.IO){
        activeStaged.remove(path)
        val file=File(path).canonicalFile
        if(file.parentFile==archiveDir.canonicalFile&&file.name.startsWith("staged-")&&file.extension=="json")file.delete()
    }
    suspend fun loadPlan(path: String): Pair<ImportPlan,String> = withContext(Dispatchers.IO) { val plan=BackupCodec.parse(File(path).readText());plan to BackupCodec.fingerprint(dao.all()) }
    private fun archive(text: String,purpose: String): ArchiveRecord {val stamp=now();val name=id();val prefix=if(purpose=="staged")"staged-" else "archive-";val f=File(archiveDir,"$prefix$name.json");val temp=File(archiveDir,"$prefix$name.tmp");temp.outputStream().use { it.write(text.toByteArray());it.flush() };check(temp.renameTo(f)) { "Unable to preserve backup." };return ArchiveRecord(name,f.absolutePath,sha(text.toByteArray()),stamp,purpose)}
    suspend fun import(plan: ImportPlan,expected: String): MergeResult = withContext(Dispatchers.IO) {
        val original=archive(plan.original,"original-import")
        val result=db.withTransaction {
            val all=dao.all();check(BackupCodec.fingerprint(all)==expected) { "Data changed since preview. Preview again." }
            val merge=BackupCodec.merge(plan,all)
            val before=archive(BackupCodec.export(all).toString(),"before-import")
            dao.archive(before);dao.archive(original);dao.putAll(merge.additions)
            dao.importHistory(ImportHistory(id(),plan.source,now(),plan.rows.size,merge.additions.size,merge.duplicates,merge.conflicts.size,"",original.id))
            merge
        }
        // Imported legacy records may be older than the local watermark.
        reconstructLegacyVisits(afterImport=true)
        // Import can restore a prior backup containing raw WHOOP sleep but no
        // derived Part 2 companion. Rebuild only from local evidence; never
        // reach back to WHOOP and never duplicate stable detail identities.
        backfillWhoopSleepDetails(force=true)
        result
    }
    suspend fun exportTo(uri: Uri) = withContext(Dispatchers.IO) {
        // Include originals as nested portable JSON; the entire export can be restored or originals recovered separately.
        val snapshot=db.withTransaction { BackupCodec.export(dao.all()) }
        val archives=dao.archives().filter { it.purpose=="original-import" }.map { a -> fields("id" to p(a.id),"purpose" to p(a.purpose),"sha256" to p(a.digest),"payload" to json.parseToJsonElement(File(a.path).readText())) }
        val payload=snapshot.changed("preservedOriginals" to JsonArray(archives))
        require(payload.toString().toByteArray().size<=40*1024*1024){"Full backup exceeds the current 40 MB restore limit. Export original archives separately; no backup was written."}
        context.contentResolver.openOutputStream(uri,"wt")?.use { it.write(payload.toString().toByteArray()) } ?: error("Cannot write backup.")
    }
    suspend fun exportArchive(uri: Uri,archiveId: String) = withContext(Dispatchers.IO) {val a=dao.archives().firstOrNull { it.id==archiveId } ?: error("Backup no longer available.");context.contentResolver.openOutputStream(uri,"wt")?.use { out -> File(a.path).inputStream().use { it.copyTo(out) } } ?: error("Cannot write file.")}
    suspend fun autoBackup() = withContext(Dispatchers.IO) {
        val retired=db.withTransaction {
            val text=BackupCodec.export(dao.all()).toString();val a=archive(text,"daily");dao.archive(a)
            dao.archives().filter { it.purpose=="daily" }.drop(7).also {old->old.forEach {dao.deleteArchive(it.id)}}
        }
        // Delete only after the metadata transaction commits and only if no row references the path.
        val referenced=dao.archives().map {File(it.path).canonicalPath}.toSet()
        retired.forEach {old->File(old.path).takeIf {it.canonicalPath !in referenced}?.delete()}
        cleanupPrivateArchives()
    }
    suspend fun cleanupPrivateArchives(nowInstant:Instant=Instant.now(),stagedMaxAge:Duration=Duration.ofHours(24)):ArchiveCleanupDiagnostics=withContext(Dispatchers.IO){
        val referenced=dao.archives().map {File(it.path).canonicalPath}.toSet()
        var found=0;var cleaned=0;var failures=0;var stagedRetained=0
        archiveDir.listFiles().orEmpty().filter {it.isFile&&it.parentFile.canonicalFile==archiveDir.canonicalFile}.forEach {file->
            val canonical=file.canonicalPath
            val staged=file.name.startsWith("staged-")
            val age=Duration.ofMillis((nowInstant.toEpochMilli()-file.lastModified()).coerceAtLeast(0))
            val eligible=file.extension in setOf("json","tmp")&&canonical !in referenced&&canonical !in activeStaged&&(!staged||age>=stagedMaxAge)&&age>=Duration.ofHours(24)
            if(staged&&!eligible)stagedRetained++
            if(eligible){found++;if(file.delete())cleaned++ else failures++}
        }
        val result=ArchiveCleanupDiagnostics(dao.archives().count {it.purpose=="daily"},found,cleaned,stagedRetained,failures,nowInstant.toString())
        val raw=fields("key" to p("archive-cleanup"),"value" to fields("backupFilesRetained" to p(result.retainedBackups),"orphanFilesFound" to p(found),"orphanFilesCleaned" to p(cleaned),"stagedImportsRetained" to p(stagedRetained),"cleanupFailures" to p(failures),"lastCleanup" to p(result.completedAt)))
        db.withTransaction {dao.put(StoredRecord.from("metadata",raw))}
        result
    }
    suspend fun save(store: String,raw: JsonObject,expected: String?=null) = db.withTransaction {
        val key=keyFor(store,raw);require(key.isNotBlank());val old=dao.get(store,key)
        require(old?.rawJson==expected) { "This record changed. Reopen it before editing." }
        BackupCodec.validateRow(store,raw)
        if(store=="loggedEvents") Ledger.validate(dao.all().filter { it.store==store && it.recordId!=key }.map { it.raw() }+raw)
        if(store=="personalRecords" && raw.text("kind")=="MoodEntry" && old!=null) {
            dao.put(StoredRecord.from("personalRecords",personal("UserCorrection",fields("originalPrediction" to old.raw(),"confidence" to (old.raw()["confidence"]?:JsonNull),"inputsUsed" to old.raw().obj("metadata"),"correctedValue" to raw.obj("data"),"timestamp" to p(now())))))
        }
        if(store=="personalRecords" && raw.text("kind")=="MoodEntry" && raw.obj("metadata").obj("stateAtCheckIn").isNotEmpty()) {
            val snapshot=raw.obj("metadata").obj("stateAtCheckIn")
            dao.put(StoredRecord.from("personalRecords",personal("UserCorrection",fields("timestamp" to p(now()),"originalPrediction" to snapshot,"confidence" to p("Low · exploratory rule"),"inputsUsed" to snapshot.obj("inputsUsed"),"correctedValue" to raw.obj("data"),"reportId" to p(key)))))
        }
        dao.put(StoredRecord.from(store,raw))
    }
    /** Owns one subjective-clock boundary.  Closing the prior segment and
     * opening its successor must commit together: otherwise route observers
     * could briefly calculate overlapping Personal/Constrained time. */
    suspend fun transitionOwnership(openPeriods:Collection<StoredRecord>,successor:JsonObject,relatedRecords:Collection<Pair<String,JsonObject>> = emptyList()) = db.withTransaction {
        val stamp=successor.obj("data").text("start")
        require(validTime(stamp)) { "Ownership transition needs a valid start time." }
        openPeriods.distinctBy {it.store to it.recordId}.forEach { expected->
            val current=dao.get(expected.store,expected.recordId)?:return@forEach
            if(current.kind=="OwnershipPeriod"&&current.data().text("end").isBlank()) {
                val closed=current.raw().changed(
                    "data" to current.data().changed("end" to p(stamp)),
                    "updatedAt" to p(stamp)
                )
                dao.put(StoredRecord.from(current.store,closed))
            }
        }
        relatedRecords.forEach {(store,raw)->
            BackupCodec.validateRow(store,raw)
            dao.put(StoredRecord.from(store,raw))
        }
        BackupCodec.validateRow("personalRecords",successor)
        dao.put(StoredRecord.from("personalRecords",successor))
    }
    suspend fun calibrationDataset(algorithmId:String,from:Instant,to:Instant,limit:Int=1000):List<StoredRecord> =
        dao.calibrationEvents(algorithmId,from.toString(),to.toString(),limit)

    suspend fun recordCalibrationAnalysis(algorithmId:String,status:String,detail:String="")=db.withTransaction {
        require(status in setOf("ANALYSING","COMPLETE","FAILED"))
        val stamp=now();val raw=personal("CalibrationAnalysis",fields("algorithmId" to p(algorithmId),"status" to p(status),"detail" to p(detail.take(160)),"calibrationEngineVersion" to p(JamesCalibrationEngine.VERSION),"updatedAt" to p(stamp)),recordId="calibration-analysis:$algorithmId",source="calibration_engine",timestamp=stamp)
        dao.put(StoredRecord.from("personalRecords",raw))
    }

    suspend fun recoverInterruptedCalibrationAnalysis()=db.withTransaction {
        uk.co.james.state.JamesAlgorithmRegistry.entries.forEach {entry->
            val row=dao.get("personalRecords","calibration-analysis:"+entry.id)?:return@forEach
            if(row.data().text("status")=="ANALYSING") {
                val stamp=now();dao.put(StoredRecord.from(row.store,row.raw().changed("data" to row.data().changed("status" to p("FAILED"),"detail" to p("Analysis was interrupted; retry safely."),"updatedAt" to p(stamp)),"updatedAt" to p(stamp))))
            }
        }
    }

    /** Enforces one valid compatible active set per supported production algorithm. */
    suspend fun ensureCalibrationProfiles()=db.withTransaction {
        uk.co.james.state.JamesAlgorithmRegistry.entries.forEach {entry->
            val registration=JamesCalibrationCatalog.get(entry.id)?.takeIf {it.supportsCalibration}?:return@forEach
            val profiles=dao.calibrationRows(entry.id,listOf("CalibrationProfile"),1000)
            val validActive=profiles.filter {profile->
                profile.data().flag("active")&&profile.data().text("algorithmVersion") in entry.calibrationCompatibleAlgorithmVersions&&
                    JamesCalibrationEngine.activeCalibration(listOf(profile),entry.id,entry.calibrationVersion,entry.algorithmVersion).setId==profile.data().text("calibrationSetId")
            }.sortedByDescending {it.data().text("activatedAt",it.timestamp)}
            val keep=validActive.firstOrNull()
            profiles.filter {it.data().flag("active")&&it.recordId!=keep?.recordId}.forEach {profile->
                val stamp=now();dao.put(StoredRecord.from(profile.store,profile.raw().changed("data" to profile.data().changed("active" to p(false),"deactivatedAt" to p(stamp),"deactivationReason" to p("Invalid or duplicate active calibration repaired.")),"updatedAt" to p(stamp))))
            }
            if(keep==null) {
                val defaults=registration.parameters.associate {it.id to it.defaultValue};val stamp=Instant.now()
                val candidate=CandidateCalibration("initial-default:"+entry.id,entry.id,entry.algorithmVersion,entry.calibrationVersion,defaults,"Initial explicit parameter set preserving released behaviour.",emptyList(),stamp,CandidateCreator.MANUAL,CandidateStatus.ACTIVE,baseParameters=defaults)
                val raw=JamesCalibrationEngine.activeProfileRecord(candidate,entry.calibrationVersion,"Initial explicit parameter set preserving released behaviour.")
                dao.put(StoredRecord.from("personalRecords",raw))
            }
        }
    }

    /** Stable event IDs make double-taps, recreation and write retries idempotent. */
    suspend fun saveCalibrationEvent(event:JsonObject):Boolean=db.withTransaction {
        require(event.text("kind")=="CalibrationEvent")
        val stored=StoredRecord.from("personalRecords",event)
        require(JamesCalibrationEngine.parse(stored,includeIgnored=true)!=null){"Calibration feedback failed validation."}
        val old=dao.get(stored.store,stored.recordId)
        if(old!=null)return@withTransaction false
        dao.put(stored)
        invalidateCandidates(stored.algorithmId.orEmpty(),"New calibration evidence was recorded; re-analysis is required.")
        true
    }

    private suspend fun invalidateCandidates(algorithmId:String,reason:String) {
        dao.calibrationCandidates(algorithmId,listOf("DRAFT","TESTED","APPROVED"),1000).forEach {candidate->
            val changed=candidate.data().changed("status" to p(CandidateStatus.STALE.name),"invalidatedAt" to p(now()),"invalidationReason" to p(reason))
            dao.put(StoredRecord.from(candidate.store,candidate.raw().changed("data" to changed,"updatedAt" to p(now()))))
        }
    }

    /** Commits analysis only if the exact evidence/base/dependency snapshot is still current. */
    suspend fun saveAnalysedCandidate(candidate:CandidateCalibration,result:BackTestResult,status:CandidateStatus)=db.withTransaction {
        require(status in setOf(CandidateStatus.DRAFT,CandidateStatus.TESTED))
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get(candidate.algorithmId)?:error("Algorithm unavailable.")
        val events=dao.calibrationEvents(candidate.algorithmId,"1970-01-01T00:00:00Z","9999-12-31T23:59:59Z",5000).mapNotNull(JamesCalibrationEngine::parse)
        val profiles=dao.calibrationRows(candidate.algorithmId,listOf("CalibrationProfile"),1000)
        val active=JamesCalibrationEngine.activeCalibration(profiles,candidate.algorithmId,entry.calibrationVersion,entry.algorithmVersion)
        val dependencies=currentDependencyVersions(candidate.algorithmId)
        require(JamesCalibrationEngine.candidateStalenessReason(candidate,events,active,dependencies)==null){"Analysis inputs changed; run analysis again."}
        dao.put(StoredRecord.from("personalRecords",JamesCalibrationEngine.candidateRecord(candidate,status)))
        dao.put(StoredRecord.from("personalRecords",JamesCalibrationEngine.backtestRecord(candidate,result)))
    }

    private suspend fun currentDependencyVersions(algorithmId:String):Map<String,String> = uk.co.james.state.JamesAlgorithmRegistry.dependencies[algorithmId].orEmpty().sorted().associateWith {dependency->
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get(dependency)
        if(entry==null)"UNAVAILABLE" else {
            val profiles=dao.calibrationRows(dependency,listOf("CalibrationProfile"),1000)
            JamesCalibrationEngine.activeCalibration(profiles,dependency,entry.calibrationVersion,entry.algorithmVersion).let {it.version+"@"+it.setId}
        }
    }

    private fun nextCalibrationVersion(profiles:List<StoredRecord>):String {
        val next=(profiles.mapNotNull {it.data().text("calibrationVersion").split('.').getOrNull(1)?.toIntOrNull()}.maxOrNull()?:0)+1
        return "1.$next.0"
    }

    /** Validation, deactivation, new profile, candidate state and audit history commit atomically. */
    suspend fun activateCalibrationCandidate(candidateId:String):String=db.withTransaction {
        val row=dao.get("personalRecords",candidateId)?:error("Candidate unavailable.")
        val candidate=JamesCalibrationEngine.parseCandidate(row)?:error("Candidate failed schema or bounds validation.")
        require(candidate.status==CandidateStatus.TESTED){"Only a tested candidate can be activated."}
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get(candidate.algorithmId)?:error("Algorithm unavailable.")
        require(candidate.algorithmVersion==entry.algorithmVersion){"Candidate is incompatible with the current algorithm."}
        val events=dao.calibrationEvents(candidate.algorithmId,"1970-01-01T00:00:00Z","9999-12-31T23:59:59Z",5000).mapNotNull(JamesCalibrationEngine::parse)
        val profiles=dao.calibrationRows(candidate.algorithmId,listOf("CalibrationProfile"),1000)
        val active=JamesCalibrationEngine.activeCalibration(profiles,candidate.algorithmId,entry.calibrationVersion,entry.algorithmVersion)
        require(JamesCalibrationEngine.candidateStalenessReason(candidate,events,active,currentDependencyVersions(candidate.algorithmId))==null){"Candidate is stale; run analysis again."}
        val backtest=dao.get("personalRecords","backtest:"+candidate.id)?.data()?:error("A complete back-test is required.")
        require(backtest.text("datasetHash")==candidate.datasetHash&&backtest.text("baseCalibrationSetId")==candidate.baseCalibrationSetId){"Back-test identity does not match the candidate."}
        require(backtest.flag("robustValidation")&&backtest.array("regressions").isEmpty()&&backtest.number("improvementPercent",Double.NaN).isFinite()&&backtest.number("improvementPercent")>0){"Candidate no longer passes validation or regression guards."}
        val newVersion=nextCalibrationVersion(profiles)
        val stamp=now()
        if(profiles.none {it.data().text("calibrationVersion")==entry.calibrationVersion}) {
            val registration=JamesCalibrationCatalog.get(candidate.algorithmId)?:error("Calibration unsupported.")
            val defaultCandidate=CandidateCalibration("initial-default:"+candidate.algorithmId,candidate.algorithmId,entry.algorithmVersion,entry.calibrationVersion,registration.parameters.associate {it.id to it.defaultValue},"Initial explicit parameter set preserving released behaviour.",emptyList(),Instant.parse(stamp),CandidateCreator.MANUAL,CandidateStatus.ROLLED_BACK,baseParameters=registration.parameters.associate {it.id to it.defaultValue},baseCalibrationSetId=active.setId)
            val defaultRaw=JamesCalibrationEngine.activeProfileRecord(defaultCandidate,entry.calibrationVersion,"Initial explicit parameter set preserving released behaviour.")
            dao.put(StoredRecord.from("personalRecords",defaultRaw.changed("data" to defaultRaw.obj("data").changed("active" to p(false),"deactivatedAt" to p(stamp)))))
        }
        profiles.filter {it.data().flag("active")}.forEach {profile->dao.put(StoredRecord.from(profile.store,profile.raw().changed("data" to profile.data().changed("active" to p(false),"deactivatedAt" to p(stamp)),"updatedAt" to p(stamp))))}
        dao.put(StoredRecord.from("personalRecords",JamesCalibrationEngine.activeProfileRecord(candidate,newVersion,candidate.reason)))
        dao.put(StoredRecord.from(row.store,row.raw().changed("data" to row.data().changed("status" to p(CandidateStatus.ACTIVE.name),"activatedAt" to p(stamp)),"updatedAt" to p(stamp))))
        dao.put(StoredRecord.from("personalRecords",personal("CalibrationActivation",fields("algorithmId" to p(candidate.algorithmId),"fromVersion" to p(active.version),"toVersion" to p(newVersion),"candidateId" to p(candidate.id),"action" to p("ACTIVATE"),"reason" to p(candidate.reason)),source="manual_approval")))
        newVersion
    }

    suspend fun rollbackCalibration(algorithmId:String):String=db.withTransaction {
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get(algorithmId)?:error("Algorithm unavailable.")
        val profiles=dao.calibrationRows(algorithmId,listOf("CalibrationProfile"),1000).sortedByDescending {it.data().text("activatedAt",it.timestamp)}
        val current=profiles.firstOrNull {it.data().flag("active")}?:error("No active personal calibration.")
        val previous=profiles.firstOrNull {profile->
            profile.recordId!=current.recordId&&profile.data().text("algorithmVersion")==entry.algorithmVersion&&
                JamesCalibrationEngine.activeCalibration(listOf(StoredRecord.from(profile.store,profile.raw().changed("data" to profile.data().changed("active" to p(true))))),algorithmId,entry.calibrationVersion,entry.algorithmVersion).setId==profile.data().text("calibrationSetId")
        }?:error("No previous compatible valid calibration is available.")
        val stamp=now()
        profiles.filter {it.data().flag("active")}.forEach {profile->dao.put(StoredRecord.from(profile.store,profile.raw().changed("data" to profile.data().changed("active" to p(false),"rolledBackAt" to p(stamp)),"updatedAt" to p(stamp))))}
        dao.put(StoredRecord.from(previous.store,previous.raw().changed("data" to previous.data().changed("active" to p(true),"activatedAt" to p(stamp)),"updatedAt" to p(stamp))))
        dao.put(StoredRecord.from("personalRecords",personal("CalibrationActivation",fields("algorithmId" to p(algorithmId),"fromVersion" to p(current.data().text("calibrationVersion")),"toVersion" to p(previous.data().text("calibrationVersion")),"action" to p("ROLLBACK")),source="manual_approval")))
        previous.data().text("calibrationVersion")
    }

    suspend fun restoreDefaultCalibration(algorithmId:String):String=db.withTransaction {
        val entry=uk.co.james.state.JamesAlgorithmRegistry.get(algorithmId)?:error("Algorithm unavailable.")
        val registration=JamesCalibrationCatalog.get(algorithmId)?.takeIf {it.supportsCalibration}?:error("Calibration unsupported.")
        val profiles=dao.calibrationRows(algorithmId,listOf("CalibrationProfile"),1000)
        val current=JamesCalibrationEngine.activeCalibration(profiles,algorithmId,entry.calibrationVersion,entry.algorithmVersion)
        val candidate=CandidateCalibration("restore-default:"+id(),algorithmId,entry.algorithmVersion,current.version,registration.parameters.associate {it.id to it.defaultValue},"Restore the compatible default parameter set.",emptyList(),Instant.now(),CandidateCreator.MANUAL,CandidateStatus.TESTED,baseParameters=current.parameters,baseCalibrationSetId=current.setId)
        val newVersion=nextCalibrationVersion(profiles);val stamp=now()
        profiles.filter {it.data().flag("active")}.forEach {profile->dao.put(StoredRecord.from(profile.store,profile.raw().changed("data" to profile.data().changed("active" to p(false),"deactivatedAt" to p(stamp)),"updatedAt" to p(stamp))))}
        dao.put(StoredRecord.from("personalRecords",JamesCalibrationEngine.activeProfileRecord(candidate,newVersion,"Restored default calibration by James.")))
        dao.put(StoredRecord.from("personalRecords",personal("CalibrationActivation",fields("algorithmId" to p(algorithmId),"fromVersion" to p(current.version),"toVersion" to p(newVersion),"action" to p("RESTORE_DEFAULT")),source="manual_approval")))
        newVersion
    }

    suspend fun deleteCalibrationEvent(recordId:String)=db.withTransaction {
        val row=dao.get("personalRecords",recordId)?:return@withTransaction
        require(row.kind=="CalibrationEvent")
        dao.delete("personalRecords",recordId)
    }

    /** Feedback mutations invalidate analysed candidates atomically. */
    suspend fun mutateCalibrationEvidence(event:JsonObject?,deleteId:String?=null,algorithmId:String)=db.withTransaction {
        if(deleteId!=null) {
            val row=dao.get("personalRecords",deleteId)?:return@withTransaction
            require(row.kind=="CalibrationEvent");dao.delete("personalRecords",deleteId)
        }
        if(event!=null) {
            require(event.text("kind")=="CalibrationEvent")
            dao.put(StoredRecord.from("personalRecords",event))
        }
        invalidateCandidates(algorithmId,"Calibration evidence was edited, ignored or deleted; run analysis again.")
    }

    /** Imports only semantically exact structured check-ins; vague notes and chats are never inferred. */
    suspend fun bootstrapCalibrationEvidence()=db.withTransaction {
        val all=dao.all()
        val linked=all.filter {it.kind=="CalibrationEvent"}.map {it.data().text("sourceEventId")}.filter {it.isNotBlank()}.toSet()
        all.filter {it.kind=="WellbeingCheckIn"&&it.recordId !in linked}.forEach {check->
            val d=check.data();val energy=d.text("energy");val prediction=d["liveEnergyBefore"]?.jsonPrimitive?.doubleOrNull
            if(energy in setOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH")&&prediction!=null) {
                val snapshot=fields(
                    "sourceRecordId" to p(check.recordId),"bodyBattery" to (d["bodyBattery"]?:JsonNull),
                    "mentalReserve" to (d["mentalReserve"]?:JsonNull),"sleepMinutes" to (d["sleepMinutes"]?:JsonNull),
                    "recovery" to (d["recovery"]?:JsonNull),"awakeMinutes" to (d["awakeMinutes"]?:JsonNull),
                    "recentMealAt" to (d["lastMealAt"]?:JsonNull),"latestHydrationAt" to (d["lastHydrationAt"]?:JsonNull),
                    "caffeineTodayMg" to (d["caffeineTodayMg"]?:JsonNull),"nutritionSource" to (d["nutritionSource"]?:JsonNull),
                    "capturedAt" to p(check.timestamp),"replayMethod" to p("STORED_CHECK_IN_SNAPSHOT"),
                    "evidenceConfidence" to p("STRUCTURED")
                )
                val event=uk.co.james.calibration.JamesCalibrationEngine.event("live_energy",prediction,energy,uk.co.james.state.JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,"1.0.0",d.text("jamesDayId").ifBlank {null},snapshot,evidenceSource="HISTORICAL_CHECK_IN",sourceEventId=check.recordId,timestamp=runCatching {Instant.parse(check.timestamp)}.getOrElse {Instant.now()})
                dao.put(StoredRecord.from("personalRecords",event))
            }
        }
    }

    suspend fun persistWellbeing(summary:MentalWellbeingSummary)=db.withTransaction {
        val raw=wellbeingRecord(summary);val key=raw.text("key");val stored=StoredRecord.from("metadata",raw)
        if(dao.get("metadata",key)?.rawJson!=stored.rawJson) dao.put(stored)
        // An input fingerprint prevents timestamp-only writes and the Room observer loop they would cause.
        val diagnosticKey="mental-wellbeing-anxiety:${summary.date}:${uk.co.james.state.ANXIETY_ALGORITHM_VERSION}"
        val old=dao.get("metadata",diagnosticKey);val oldValue=old?.raw()?.obj("value")
        val diagnostic=uk.co.james.state.anxietyDiagnosticsRecord(summary,oldValue?.number("currentScore")?.toInt())
        val newValue=diagnostic.obj("value")
        if(oldValue==null||oldValue.text("inputFingerprint")!=newValue.text("inputFingerprint"))dao.put(StoredRecord.from("metadata",diagnostic))
    }
    suspend fun persistRightNow(summary:RightNowSummary)=db.withTransaction {
        val raw=rightNowRecord(summary);val key=raw.text("key");val old=dao.get("metadata",key);val oldValue=old?.raw()?.obj("value")
        val oldEnergy=oldValue?.obj("liveEnergy")?.number("score",Double.NaN)?.takeIf {it.isFinite()}?.toInt()
        val oldPressure=oldValue?.obj("timePressure")?.number("score",Double.NaN)?.takeIf {it.isFinite()}?.toInt()
        val oldSleepiness=oldValue?.obj("sleepiness")?.number("score",Double.NaN)?.takeIf {it.isFinite()}?.toInt()
        val oldCalculated=oldValue?.text("calculatedAt")?.takeIf(::validTime)?.let(java.time.Instant::parse)
        val stateChanged=oldValue==null||oldEnergy!=summary.liveEnergy.score||oldPressure!=summary.timePressure.score||oldSleepiness!=summary.sleepiness.score||oldValue.obj("sustainability").number("score",Double.NaN).toInt()!=summary.sustainability.score||oldValue.obj("crashRisk").number("score",Double.NaN).toInt()!=summary.crashRisk.score||oldValue.number("bodyBattery",Double.NaN).toInt()!=summary.bodyBattery||oldValue.number("mentalReserve",Double.NaN).toInt()!=summary.mentalReserve||oldValue.number("awakeMinutes",0.0).toLong()/15!=summary.awakeMinutes/15||oldValue.number("personalMinutes",0.0).toLong()!=summary.personalMinutes
        val meaningful=old==null||oldEnergy==null||kotlin.math.abs(summary.liveEnergy.score-oldEnergy)>=10||oldPressure==null||kotlin.math.abs(summary.timePressure.score-oldPressure)>=10||oldSleepiness==null||kotlin.math.abs(summary.sleepiness.score-oldSleepiness)>=10||oldCalculated?.let {java.time.Duration.between(it,java.time.Instant.parse(summary.liveEnergy.calculatedAt))>=java.time.Duration.ofHours(2)}==true
        val current=StoredRecord.from("metadata",raw)
        if(stateChanged)dao.put(current)
        if(meaningful) {
            val stamp=summary.liveEnergy.calculatedAt
            val snapshot=personal("EnergySnapshot",fields(
                "title" to p("Right now updated"),"date" to p(summary.jamesDay.displayDate),
                "liveEnergy" to p(summary.liveEnergy.score),"sleepiness" to p(summary.sleepiness.score),"sleepinessUnderlyingPressure" to p(summary.sleepinessDetails.underlyingPressure),"sleepinessAlgorithmVersion" to p(summary.sleepiness.algorithmVersion),"sleepinessCalibrationVersion" to p(summary.sleepiness.calibrationVersion),"sleepinessCalibrationSetId" to p(summary.sleepiness.calibrationSetId),"sustainability" to p(summary.sustainability.score),"crashRisk" to p(summary.crashRisk.score),"timePressure" to p(summary.timePressure.score),
                "previousLiveEnergy" to (oldEnergy?.let(::p)?:JsonNull),"previousTimePressure" to (oldPressure?.let(::p)?:JsonNull),
                "algorithmVersion" to p("1.0.0"),"calibrationVersion" to p("1.0.0"),
                "note" to p("Live Energy ${summary.liveEnergy.score}; Sleepiness ${summary.sleepiness.score}; Sustainability ${summary.sustainability.score}; Crash Risk ${summary.crashRisk.label}; Time Pressure ${summary.timePressure.score}.")
            ),"right-now-snapshot:${summary.jamesDay.id}:${java.time.Instant.parse(stamp).epochSecond/7200}","james",stamp)
            dao.put(StoredRecord.from("personalRecords",snapshot))
        }
    }
    suspend fun persistBodyBattery(battery:BodyBattery):BodyBatteryPersistenceResult=db.withTransaction {
        if(battery.value==null||battery.strainDiagnostics==null)return@withTransaction BodyBatteryPersistenceResult(false,"INCOMPLETE_CALCULATION")
        val raw=bodyBatteryRecord(battery);val key=raw.text("key");val stored=StoredRecord.from("metadata",raw)
        val old=dao.get("metadata",key)
        val oldData=old?.raw()?.obj("value")
        val newData=stored.raw().obj("value")
        val oldTrace=oldData?.obj("trace")
        val newTrace=newData.obj("trace")
        fun instant(value:String)=runCatching {java.time.Instant.parse(value)}.getOrNull()
        val oldCalculated=instant(oldTrace?.text("calculatedAt")?:oldData?.text("updatedAt").orEmpty())
        val newCalculated=instant(newTrace.text("calculatedAt"))
        // A delayed observer or process recreation must never overwrite a newer
        // authoritative day state with an earlier calculation.
        if(oldCalculated!=null&&newCalculated!=null&&newCalculated<oldCalculated)return@withTransaction BodyBatteryPersistenceResult(false,"OLDER_CALCULATION")
        val oldStrain=oldData?.obj("strain")?.number("transformedCost",0.0)?:0.0
        val newStrain=newData.obj("strain").number("transformedCost",0.0)
        val oldScore=oldData?.number("score",Double.NaN)?.takeIf {it.isFinite()}?.toInt()
        val newScore=newData.number("score",Double.NaN).takeIf {it.isFinite()}?.toInt()?:0
        val oldRecharge=oldData?.number("recharge",0.0)?.toInt()?:0
        val newRecharge=newData.number("recharge",0.0).toInt()
        // Preserve an explicit nap/restorative increase; reject every other
        // attempt to replace the authoritative state with a higher Reserve.
        val sameStrainOwner=sameBodyBatteryStrainOwner(oldData,newData)
        val ownershipRepair=newData.obj("strain")["invalidatedPersistedStrain"]?.jsonPrimitive?.doubleOrNull!=null
        val nonRestorativeIncrease=oldScore!=null&&newScore>oldScore&&newRecharge<=oldRecharge&&!ownershipRepair
        val materiallyChanged=oldData==null ||
            oldData["score"]!=newData["score"] || oldData["morning"]!=newData["morning"] ||
            oldData["used"]!=newData["used"] || oldData.obj("strain")["highestRawStrain"]!=newData.obj("strain")["highestRawStrain"] ||
            oldData.obj("strain")["transformedCost"]!=newData.obj("strain")["transformedCost"] ||
            oldData.obj("strain")["latestIncrementalDebit"]!=newData.obj("strain")["latestIncrementalDebit"] ||
            oldData.obj("trace")["awakeMinutes"]!=newData.obj("trace")["awakeMinutes"]
        val monotonicWithinOwner=!sameStrainOwner||newStrain+0.0001>=oldStrain
        val accepted=materiallyChanged&&!nonRestorativeIncrease&&monotonicWithinOwner
        val reason=when {
            !materiallyChanged->"UNCHANGED"
            nonRestorativeIncrease->"NON_RESTORATIVE_INCREASE_REJECTED"
            !monotonicWithinOwner->"LOWER_STRAIN_REJECTED_WITHIN_SAME_OWNER"
            ownershipRepair->"CROSS_BOUNDARY_STRAIN_RECONCILED"
            !sameStrainOwner&&oldData!=null->"NEW_JAMES_DAY_OR_WHOOP_CYCLE"
            else->"ACCEPTED"
        }
        if(accepted) {
            val annotated=raw.changed("value" to newData.changed("persistence" to fields(
                "status" to p("ACCEPTED"),"reason" to p(reason),"decidedAt" to p(newTrace.text("calculatedAt")),
                "sameStrainOwner" to p(sameStrainOwner)
            )))
            dao.put(StoredRecord.from("metadata",annotated))
        }
        BodyBatteryPersistenceResult(accepted,reason)
    }
    suspend fun resetWellbeingBaseline()=db.withTransaction {
        dao.all().filter {it.store=="metadata"&&it.recordId.startsWith("mental-wellbeing:")}.forEach {dao.delete(it.store,it.recordId)}
    }
    suspend fun setting(key: String,value: JsonElement) = db.withTransaction {dao.put(StoredRecord.from("settings",fields("key" to p(key),"value" to value)))}
    suspend fun seedDefaults() = db.withTransaction {
        val defaults=json.parseToJsonElement(context.assets.open("rut-defaults.json").bufferedReader().readText()) as JsonArray
        defaults.forEach { value -> val raw=value.jsonObject;if(dao.get("eventTemplates",raw.text("id"))==null) dao.put(StoredRecord.from("eventTemplates",raw)) }
    }
    suspend fun log(template: JsonObject,date: String=today(),note: String="",linked: JsonObject?=null) = db.withTransaction {
        val stamp=if(date==today()) now() else LocalDate.parse(date).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toString()
        val action="last-action:"+(linked?.text("id")?:template.text("id"));val previous=dao.get("metadata",action)?.raw()?.get("value") as? JsonPrimitive
        if(previous?.longOrNull?.let { System.currentTimeMillis()-it<1200 }==true) return@withTransaction
        val raw=fields("id" to p(id()),"timestamp" to p(stamp),"localDate" to p(date),"createdAt" to p(now()),"updatedAt" to p(now()),"templateId" to p(template.text("id")),"title" to p(if(linked==null)template.text("title") else linked.text("recoveryTitle").ifBlank {template.text("recoveryTitle").ifBlank {"Recovered: "+linked.text("title")}}),"category" to p(if(linked==null)template.text("category") else "Recovery"),"type" to p(if(linked==null)template.text("type") else "recovery"),"points" to p(if(linked==null)template.number("points") else linked.number("recoveryPoints",template.number("recoveryPoints",kotlin.math.abs(linked.number("points"))+20))),"note" to p(note),"timeAccuracy" to p(if(date==today())"exact" else "approximate"))
            .let { if(linked!=null)it.changed("linkedEventId" to p(linked.text("id"))) else it.changed("recoveryTitle" to p(template.text("recoveryTitle")),"recoveryPoints" to p(template.number("recoveryPoints"))) }
        Ledger.validate(dao.all().filter { it.store=="loggedEvents" }.map { it.raw() }+raw)
        dao.put(StoredRecord.from("loggedEvents",raw));dao.put(StoredRecord.from("metadata",fields("key" to p(action),"value" to p(System.currentTimeMillis()))))
    }
    suspend fun start(points: Long) = db.withTransaction {
        require(dao.all().none { it.store=="loggedEvents" }) { "Import or edit your existing starting point." }
        val raw=fields("id" to p(id()),"type" to p("initial"),"points" to p(points),"title" to p("Starting point"),"category" to p("Other"),"timestamp" to p(now()),"localDate" to p(today()),"createdAt" to p(now()),"updatedAt" to p(now()))
        dao.put(StoredRecord.from("loggedEvents",raw))
    }
    suspend fun deleteEvent(event: StoredRecord) = db.withTransaction {
        require(dao.get(event.store,event.recordId)?.rawJson==event.rawJson) { "This event changed." }
        require(event.raw().text("type")!="initial") { "Edit the starting point instead." }
        val all=dao.all().filter { it.store=="loggedEvents" };all.filter { it.recordId==event.recordId || it.raw().text("linkedEventId")==event.recordId }.forEach {dao.delete(it.store,it.recordId)}
    }
    suspend fun markDay(date: String,status: String) = db.withTransaction {
        require(validDate(date) && date<=today() && status in listOf("reviewed","quiet","unknown"))
        if(status=="quiet") require(dao.all().none { it.store=="loggedEvents" && it.localDate==date && it.raw().text("type")!="initial" }) { "This day has events; mark it reviewed instead." }
        dao.put(StoredRecord.from("metadata",fields("key" to p("day-review:$date"),"value" to fields("date" to p(date),"status" to p(status),"updatedAt" to p(now())))))
    }
    suspend fun toggleHabit(routine: StoredRecord,date: String) = db.withTransaction {
        require(validDate(date) && date<=today() && Habits.due(routine.raw(),date)) { "Not a scheduled day." }
        val key="completion:${routine.recordId}:$date";val old=dao.get("personalRecords",key);val d=old?.data()
        val raw=personal("RoutineCompletion",fields("routineId" to p(routine.recordId),"date" to p(date),"completed" to p(!(d?.flag("completed")?:false)),"approximate" to p(date!=today())),key,timestamp=if(date==today())now() else LocalDate.parse(date).atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toString())
        dao.put(StoredRecord.from("personalRecords",if(old==null)raw else raw.changed("createdAt" to p(old.raw().text("createdAt")))))
    }
    suspend fun external(raw: JsonObject) = db.withTransaction {
        val incoming=StoredRecord.from("personalRecords",raw);val old=dao.get(incoming.store,incoming.recordId)
        require(incoming.source !in listOf("manual","rut","james"))
        if(old==null || old.source==incoming.source && incoming.updatedAt>=old.updatedAt)dao.put(incoming)
    }
    suspend fun externalBatch(raws:Collection<JsonObject>)=db.withTransaction {
        val candidates=raws.map {raw->StoredRecord.from("personalRecords",raw).also {require(it.source !in listOf("manual","rut","james"))}}
            .groupBy {it.recordId}.values.map {versions->versions.maxBy {it.updatedAt}}
        // SQLite has a bounded parameter count. Chunking turns a large import from
        // one SELECT per record into roughly one indexed SELECT per 500 records.
        val existing=candidates.map {it.recordId}.distinct().chunked(500).flatMap {dao.getByIds("personalRecords",it)}.associateBy {it.recordId}
        val accepted=candidates.filter {incoming->existing[incoming.recordId]?.let {old->old.source==incoming.source&&incoming.updatedAt>=old.updatedAt}?:true}
        if(accepted.isNotEmpty())dao.putAll(accepted)
        accepted.size
    }
}
