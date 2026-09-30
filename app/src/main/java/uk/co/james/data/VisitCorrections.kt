package uk.co.james.data

import androidx.room.withTransaction
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.location.isSupersededVisit
import java.time.Duration
import java.time.Instant

private suspend fun JamesRepository.editableVisit(id:String,expected:String?):StoredRecord {
    val row=dao.get("personalRecords",id)?.takeIf {it.kind=="PlaceVisit"}?:error("Visit no longer exists.")
    require(row.rawJson==expected) { "This visit changed. Close and reopen it before correcting." }
    require(!row.isSupersededVisit()) { "This visit was superseded. Open the current visit." }
    return row
}
private fun StoredRecord.closedInterval():Pair<Instant,Instant> {
    val start=data().text("start").takeIf(::validTime)?.let(Instant::parse)?:error("Visit start is unavailable.")
    val end=data().text("end").takeIf(::validTime)?.let(Instant::parse)?:error("Only a completed visit can be corrected.")
    require(end.isAfter(start)) { "Visit end must follow its start." }
    return start to end
}
private suspend fun JamesRepository.snapshot(row:StoredRecord,action:String,stamp:String):String {
    val key="visit-evidence:${row.recordId}:${id()}"
    save("personalRecords",personal("VisitEvidenceSnapshot",fields("visitId" to p(row.recordId),"rawVisit" to row.raw(),"capturedFor" to p(action),"capturedAt" to p(stamp)),recordId=key,source="correction",timestamp=stamp))
    return key
}
private fun fingerprint(row:StoredRecord)=sha(canonical(kotlinx.serialization.json.JsonObject(row.raw().filterKeys {it!="updatedAt"})).toByteArray())

/** All evidence snapshots, factual updates and the decision commit together. */
suspend fun JamesRepository.splitVisit(visitId:String,expected:String?):String=db.withTransaction {
    val visit=editableVisit(visitId,expected);val (start,end)=visit.closedInterval()
    require(Duration.between(start,end)>=Duration.ofMinutes(10)) { "A visit needs at least ten minutes to split." }
    val mid=start.plusSeconds(Duration.between(start,end).seconds/2);val stamp=now()
    val evidenceId=snapshot(visit,"SPLIT",stamp)
    val first=StoredRecord.from(visit.store,visit.raw().changed("data" to visit.data().changed("end" to p(mid.toString()),"durationMin" to p(Duration.between(start,mid).toMinutes()),"correctionSource" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp)))
    val second=StoredRecord.from("personalRecords",personal("PlaceVisit",visit.data().changed("start" to p(mid.toString()),"end" to p(end.toString()),"durationMin" to p(Duration.between(mid,end).toMinutes()),"splitFromVisitId" to p(visit.recordId),"correctionSource" to p("JAMES_CORRECTION")),source="manual",timestamp=mid.toString()))
    save(first.store,first.raw(),visit.rawJson);save(second.store,second.raw())
    save("personalRecords",personal("VisitCorrection",fields("visitId" to p(visitId),"action" to p("SPLIT"),"targetVisitId" to p(second.recordId),"rawEvidenceId" to p(evidenceId),"visitAfterHash" to p(fingerprint(first)),"targetAfterHash" to p(fingerprint(second)),"source" to p("JAMES_CORRECTION"),"reversible" to p(true)),source="manual",timestamp=stamp))
    second.recordId
}

suspend fun JamesRepository.mergeVisitWithPrevious(visitId:String,expected:String?):String=db.withTransaction {
    val visit=editableVisit(visitId,expected);val (start,end)=visit.closedInterval();val d=visit.data()
    require(d.text("placeId").isNotBlank()) { "Choose a visit linked to a saved place." }
    val previous=dao.visitsBetween(start.minus(Duration.ofHours(12)).toString(),start.toString())
        .filter {it.recordId!=visitId&&!it.isSupersededVisit()&&it.data().text("placeId")==d.text("placeId")}
        .filter {row->runCatching {row.closedInterval().second<=start}.getOrDefault(false)}
        .maxByOrNull {it.timestamp}?:error("No previous completed visit at this place was found within twelve hours.")
    require(listOf("ownership","ownershipSource","context","contextSource").all {previous.data().text(it)==d.text(it)}) { "These visits have different ownership or context. Keep their separate evidence." }
    val stamp=now();val visitEvidence=snapshot(visit,"MERGE",stamp);val previousEvidence=snapshot(previous,"MERGE",stamp)
    val merged=StoredRecord.from(previous.store,previous.raw().changed("data" to previous.data().changed("end" to p(end.toString()),"durationMin" to p(Duration.between(previous.closedInterval().first,end).toMinutes()),"mergedVisitIds" to p(listOf(previous.data().text("mergedVisitIds"),visitId).filter {it.isNotBlank()}.joinToString(",")),"correctionSource" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp)))
    val superseded=StoredRecord.from(visit.store,visit.raw().changed("data" to d.changed("supersededByVisitId" to p(previous.recordId),"supersededAt" to p(stamp),"correctionSource" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp)))
    save(merged.store,merged.raw(),previous.rawJson);save(superseded.store,superseded.raw(),visit.rawJson)
    save("personalRecords",personal("VisitCorrection",fields("visitId" to p(visitId),"action" to p("MERGED_INTO"),"targetVisitId" to p(previous.recordId),"previousRawEvidenceId" to p(visitEvidence),"targetRawEvidenceId" to p(previousEvidence),"visitAfterHash" to p(fingerprint(superseded)),"targetAfterHash" to p(fingerprint(merged)),"source" to p("JAMES_CORRECTION"),"reversible" to p(true)),source="manual",timestamp=stamp))
    previous.recordId
}

/** Restore only the exact corrected revision; never overwrite a later edit. */
suspend fun JamesRepository.revertLatestVisitCorrection(visitId:String)=db.withTransaction {
    val correction=dao.kindRecords("VisitCorrection").firstOrNull {row->row.data().text("revertedAt").isBlank()&&(row.data().text("visitId")==visitId||row.data().text("targetVisitId")==visitId)}?:error("No reversible visit correction found.")
    val d=correction.data();val stamp=now()
    val visit=dao.get("personalRecords",d.text("visitId"))?:error("Corrected visit is unavailable.")
    val target=dao.get("personalRecords",d.text("targetVisitId"))?:error("Related visit is unavailable.")
    require(d.text("visitAfterHash").isNotBlank()&&d.text("targetAfterHash").isNotBlank()) { "This older correction has no revision checks. Review its retained evidence before restoring manually." }
    require(fingerprint(visit)==d.text("visitAfterHash")&&fingerprint(target)==d.text("targetAfterHash")) { "A related visit changed after this correction. Review it before reverting." }
    suspend fun restore(snapshotId:String) {
        val original=dao.get("personalRecords",snapshotId)?.data()?.obj("rawVisit")?:error("Original visit evidence is unavailable.")
        require(original.text("kind")=="PlaceVisit"&&original.text("id") in setOf(visit.recordId,target.recordId)) { "Original visit evidence is invalid." }
        val current=dao.get("personalRecords",original.text("id"))?:error("Original visit is unavailable.")
        save(current.store,original.changed("updatedAt" to p(stamp)),current.rawJson)
    }
    when(d.text("action")) {
        "MERGED_INTO"->{restore(d.text("previousRawEvidenceId"));restore(d.text("targetRawEvidenceId"))}
        "SPLIT"->{restore(d.text("rawEvidenceId"));save(target.store,target.raw().changed("data" to target.data().changed("supersededByVisitId" to p(visit.recordId),"supersededAt" to p(stamp),"correctionSource" to p("REVERSAL")),"updatedAt" to p(stamp)),target.rawJson)}
        else->error("That correction cannot be reversed automatically.")
    }
    save(correction.store,correction.raw().changed("data" to d.changed("revertedAt" to p(stamp),"revertedBy" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp)),correction.rawJson)
}
