package uk.co.james.imports

import kotlinx.serialization.json.*
import uk.co.james.calibration.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.routines.Ledger
import uk.co.james.state.JamesAlgorithmRegistry

data class ImportPlan(val source: String, val rows: List<StoredRecord>, val original: String, val routines: Int, val entries: Int, val dateRange: String, val digest: String)
data class MergeResult(val additions: List<StoredRecord>, val duplicates: Int, val conflicts: List<String>)
object BackupCodec {
    fun parse(text: String): ImportPlan {
        require(text.toByteArray().size<=40*1024*1024) { "Backup exceeds 40 MB." }
        val root=json.parseToJsonElement(text) as? JsonObject ?: error("Choose a JSON backup.")
        val source=root.text("app");require(source in listOf("RUT","James","JamesAndroid")) { "Unsupported backup. Shift and Gig formats need a verified adapter." }
        require(validTime(root.text("exportedAt"))) { "Missing export timestamp." }
        require(root.number("schemaVersion").toInt() in if(source=="RUT") listOf(1,2) else listOf(1)) { "Unsupported backup schema." }
        val data=root["data"] as? JsonObject ?: error("Missing backup data.");val required=if(source=="RUT") rutStores else backupStores
        required.forEach { require(data[it] is JsonArray) { "Incomplete backup: $it" } }
        val rows=required.flatMap { store ->val entries=data.array(store).map { it as? JsonObject ?: error("Invalid $store row.") };require(entries.map { keyFor(store,it) }.let { ids -> ids.all { it.isNotBlank() } && ids.distinct().size==ids.size }) { "Invalid or duplicate $store ID." };entries.forEach { validateRow(store,it) };entries.map { StoredRecord.from(store,it) }}+quarantinedRows(root)
        Ledger.validate(rows.filter { it.store=="loggedEvents"&&it.hasUsableRawPayload() }.map { it.raw() });val dates=rows.map { it.localDate }.filter { validDate(it) }.sorted()
        return ImportPlan(source,rows,text,rows.count { it.store=="eventTemplates" || it.kind=="Routine" },rows.count { it.store=="loggedEvents" || it.kind=="RoutineCompletion" },if(dates.isEmpty()) "No dated entries" else "${dates.first()} — ${dates.last()}",sha(text.toByteArray()))
    }
    fun validateRow(store: String, r: JsonObject) {
        when(store) {
            "loggedEvents" -> Ledger.validateEvent(r)
            "eventTemplates" -> Ledger.validateTemplate(r)
            "archives" -> require(r["payload"]!=null && r.text("type").isNotBlank() && validTime(r.text("timestamp"))) { "Invalid archived backup." }
            "dailyNotes" -> require(validDate(r.text("date")) && validTime(r.text("updatedAt")) && r["text"] is JsonPrimitive) { "Invalid note." }
            "settings" -> if(r.text("key")=="theme") require((r["value"] as? JsonPrimitive)?.content in listOf("light","dark","system"))
            "metadata" -> if(r.text("key").startsWith("day-review:")) {val v=r.obj("value");require(validDate(v.text("date")) && validTime(v.text("updatedAt")) && v.text("status") in listOf("reviewed","quiet","unknown"))}
            "personalRecords" -> {require(r.text("kind").isNotBlank() && r["data"] is JsonObject && listOf("timestamp","createdAt","updatedAt").all { validTime(r.text(it)) }) { "Invalid James record." };val d=r.obj("data");if(r.text("kind")=="Routine") require(d.text("title").isNotBlank() && validDate(d.text("startDate")) && d.text("startDate")>="1900-01-01" && d.array("days").isNotEmpty() && d.array("days").all { (it as? JsonPrimitive)?.intOrNull in 0..6 }) { "Invalid routine." };if(r.text("kind")=="RoutineCompletion") require(validDate(d.text("date")) && d.text("routineId").isNotBlank() && (d["completed"] as? JsonPrimitive)?.booleanOrNull!=null);if(r.text("kind")=="TimeBlock") require(validTime(d.text("end")) && java.time.Instant.parse(d.text("end")).isAfter(java.time.Instant.parse(r.text("timestamp"))));validateSemanticRow(r);validateCalibrationRow(r)}
        }
    }
    /** Semantic rows remain generic JSON for import compatibility, but their
     * essential interval/enum fields are now validated before they can become
     * authoritative day evidence. Legacy rows without these current shapes are
     * still retained as legacy context rather than guessed into ownership. */
    private fun validateSemanticRow(raw:JsonObject) {
        val kind=raw.text("kind"); val d=raw.obj("data")
        fun interval(required:Boolean=true) {
            val start=d.text("start",raw.text("timestamp"))
            if(required) require(validTime(start)) { "Invalid $kind start." }
            val end=d.text("end")
            if(end.isNotBlank()) require(validTime(end) && java.time.Instant.parse(end)>=java.time.Instant.parse(start)) { "Invalid $kind end." }
        }
        when(kind) {
            "PlaceVisit" -> { interval(); require(d.text("title").isNotBlank()) { "Invalid Visit." } }
            "OwnershipPeriod" -> { interval(); require(d.text("ownership") in setOf("AUTONOMOUS","COMMITTED","CONSTRAINED","WORK","UNKNOWN")) { "Invalid ownership." } }
            "ContextPeriod" -> { interval(); require(d.text("visitType","UNKNOWN") in setOf("HOME","WORK","DRIVING","SHOPPING","FAMILY","SOCIAL","APPOINTMENT","ERRAND","MEAL","LEISURE","PERSONAL_PROJECT","RESTING","OTHER","PERSONAL","NEUTRAL","OBLIGATION","UNKNOWN")) { "Invalid context." } }
            "VisitInterruption" -> interval()
        }
    }
    private fun validateCalibrationRow(raw:JsonObject) {
        val kind=raw.text("kind");val d=raw.obj("data");if(kind !in setOf("CalibrationEvent","CalibrationCandidate","CalibrationProfile","CalibrationBacktest"))return
        val algorithmId=d.text("algorithmId");val entry=JamesAlgorithmRegistry.get(algorithmId)?:error("Unknown calibration algorithm.");require(d.text("algorithmVersion") in entry.calibrationCompatibleAlgorithmVersions){"Incompatible calibration algorithm version."}
        when(kind) {
            "CalibrationEvent" -> require(listOf("prediction","observed","error","absoluteError").all {d[it]?.jsonPrimitive?.doubleOrNull?.isFinite()==true}&&d.text("inputSnapshotSchemaVersion").isNotBlank()&&d["snapshot"] is JsonObject){"Invalid Calibration Event."}
            "CalibrationCandidate" -> require(JamesCalibrationEngine.parseCandidate(StoredRecord.from("personalRecords",raw))!=null){"Invalid Calibration Candidate."}
            "CalibrationProfile" -> {val registration=JamesCalibrationCatalog.get(algorithmId)?.takeIf {it.supportsCalibration}?:error("Calibration unsupported.");require(d.text("calibrationSchemaVersion")==entry.calibrationSchemaVersion){"Invalid calibration schema."};val parameters=d.obj("parameters");require("outputBias" in parameters&&parameters.keys.all {key->registration.parameters.any {it.id==key}}){"Unknown or missing calibration parameter."};require(parameters.all {(key,value)->value.jsonPrimitive.doubleOrNull?.let {number->registration.parameters.first {it.id==key}.accepts(number)}==true}){"Calibration parameter is outside safe bounds."};require(d.text("calibrationSetId")==sha(canonical(parameters).toByteArray())){"Calibration identity does not match its parameters."}}
            "CalibrationBacktest" -> require(d.text("candidateId").isNotBlank()&&(d.text("datasetHash").isNotBlank()||d.text("calibrationEngineVersion")=="1.0.1")&&listOf("currentMae","candidateMae").all {d[it] is JsonNull||d[it]?.jsonPrimitive?.doubleOrNull?.isFinite()==true}){"Invalid Calibration Back-test."}
        }
    }
    fun merge(plan: ImportPlan, current: List<StoredRecord>): MergeResult {
        val existing=current.associateBy { it.store to it.recordId };val additions=mutableListOf<StoredRecord>();val conflicts=mutableListOf<String>();var duplicates=0
        val currentActive=current.filter {it.hasUsableRawPayload()&&it.kind=="CalibrationProfile"&&it.data().flag("active")}.map {it.data().text("algorithmId")}.toSet();val newestImportedActive=plan.rows.filter {it.hasUsableRawPayload()&&it.kind=="CalibrationProfile"&&it.data().flag("active")}.groupBy {it.data().text("algorithmId")}.mapValues {(_,rows)->rows.maxByOrNull {it.data().text("activatedAt",it.timestamp)}?.recordId}
        plan.rows.forEach { incomingOriginal ->val algorithm=incomingOriginal.data().text("algorithmId");val neutralize=incomingOriginal.kind=="CalibrationProfile"&&incomingOriginal.data().flag("active")&&(algorithm in currentActive||newestImportedActive[algorithm]!=incomingOriginal.recordId);val incoming=if(neutralize)StoredRecord.from(incomingOriginal.store,incomingOriginal.raw().changed("data" to incomingOriginal.data().changed("active" to p(false),"importedInactiveAt" to p(now())))) else incomingOriginal;val old=existing[incoming.store to incoming.recordId]
            when {old?.rawJson==incoming.rawJson -> duplicates++;old!=null -> conflicts.add("${incoming.store}: ${incoming.recordId} (current retained)");incoming.store=="loggedEvents" && incoming.raw().text("type")=="initial" && current.any { it.store=="loggedEvents" && it.raw().text("type")=="initial" } -> conflicts.add("Starting point retained");else -> additions.add(incoming)}
        }
        Ledger.validate((current+additions).filter { it.store=="loggedEvents"&&it.hasUsableRawPayload() }.map { it.raw() });return MergeResult(additions,duplicates,conflicts)
    }
    fun fingerprint(rows: List<StoredRecord>) = sha(rows.sortedWith(compareBy({it.store},{it.recordId})).joinToString("\n") { it.store+":"+it.recordId+":"+it.rawJson }.toByteArray())
    /** A legacy/corrupt payload is retained verbatim in a private sidecar. It
     * must not be coerced into an empty normal record, which would silently
     * turn missing evidence into a different record during restore. */
    private fun quarantinedRows(root:JsonObject):List<StoredRecord> = root.array("quarantinedRows").map { element ->
        val row=element as? JsonObject ?: error("Invalid quarantined record.")
        val store=row.text("store");require(store in backupStores) { "Invalid quarantined record store." }
        val id=row.text("recordId");require(id.isNotBlank()) { "Invalid quarantined record ID." }
        val payload=row["rawJson"]
        require(payload==null||payload is JsonNull||payload is JsonPrimitive) { "Invalid quarantined payload." }
        StoredRecord(store,id,row.text("kind",store),row.text("source","legacy"),row.text("timestamp","1970-01-01T00:00:00Z"),row.text("localDate"),row.text("updatedAt"),row.text("externalId").ifBlank { null },(payload as? JsonPrimitive)?.contentOrNull,row.text("algorithmId").ifBlank { null },row.text("calibrationVersion").ifBlank { null },row.text("candidateStatus").ifBlank { null },row.text("jamesDayId").ifBlank { null })
    }
    private fun quarantinedRow(row:StoredRecord)=fields(
        "store" to p(row.store),"recordId" to p(row.recordId),"kind" to p(row.kind),"source" to p(row.source),
        "timestamp" to p(row.timestamp),"localDate" to p(row.localDate),"updatedAt" to p(row.updatedAt),
        "externalId" to (row.externalId?.let(::p)?:JsonNull),"rawJson" to (row.rawJson?.let(::p)?:JsonNull),
        "algorithmId" to (row.algorithmId?.let(::p)?:JsonNull),"calibrationVersion" to (row.calibrationVersion?.let(::p)?:JsonNull),
        "candidateStatus" to (row.candidateStatus?.let(::p)?:JsonNull),"jamesDayId" to (row.jamesDayId?.let(::p)?:JsonNull),
        "payloadIssue" to p(row.rawPayloadIssue().orEmpty())
    )
    fun export(rows: List<StoredRecord>): JsonObject = fields("app" to p("JamesAndroid"),"schemaVersion" to p(1),"exportedAt" to p(now()),"data" to JsonObject(backupStores.associateWith { store -> JsonArray(rows.filter { it.store==store&&it.hasUsableRawPayload() }.map { it.raw() }) }),"quarantinedRows" to JsonArray(rows.filterNot {it.hasUsableRawPayload()}.map(::quarantinedRow)))
}
