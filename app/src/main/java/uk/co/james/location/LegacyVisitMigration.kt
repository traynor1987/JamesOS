package uk.co.james.location

import java.time.Instant
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.database.StoredRecord

/** Converts only legacy records that already describe a bounded place/context
 * period.  Raw events without a reliable end remain evidence, not invented visits. */
internal fun reconstructedVisit(record:StoredRecord):kotlinx.serialization.json.JsonObject? {
    val d=record.data()
    val start=d.text("start",record.timestamp).takeIf(::validTime)?.let(Instant::parse)?:return null
    val end=d.text("end").takeIf(::validTime)?.let(Instant::parse)?:return null
    if(!isCompletedVisit(start,end)) return null
    val name=d.text("placeName",d.text("title")).ifBlank { "Unknown place" }
    val latitude=d.number("latitude",Double.NaN); val longitude=d.number("longitude",Double.NaN)
    // Context records often have an honest name and duration but no coordinate.
    // Keep them visible in Recent Places, but do not create a fictitious map pin.
    val id="legacy-visit:${record.store}:${record.recordId}"
    return personal("PlaceVisit",fields(
        "title" to p(name), "category" to p(d.text("category",d.text("visitType","Unclassified"))),
        "activity" to p(d.text("activity","Unknown")), "start" to p(start.toString()), "end" to p(end.toString()),
        "durationMin" to p(visitMinutes(start,end)), "latitude" to p(latitude.takeIf(Double::isFinite)?:0.0),
        "longitude" to p(longitude.takeIf(Double::isFinite)?:0.0), "placeId" to p(d.text("placeId")),
        "ownership" to p(d.text("ownership",TimeOwnership.UNKNOWN.name)), "ownershipSource" to p("LEGACY_RECONSTRUCTION"),
        "context" to p(d.text("visitType","UNKNOWN")), "contextSource" to p("LEGACY_RECONSTRUCTION"),
        "provenance" to p("Reconstructed from ${record.kind} ${record.recordId}; original evidence retained."),
        "approximate" to p(true), "note" to p(d.text("note"))
    ),id,"legacy_reconstruction",start.toString()).changed("externalId" to p(id),"confidence" to p(45))
}

private const val LEGACY_VISIT_RECONSTRUCTION_VERSION="legacy-visit-reconstruction-v2"

/** Kept pure so the migration policy is regression-tested without a device DB. */
internal fun legacyReconstructionUsesFullScan(watermark:String,afterImport:Boolean):Boolean =
    afterImport || watermark.isBlank()

/**
 * Versioned and idempotent. Startup processes only evidence newer than its
 * watermark; import deliberately requests one bounded reconciliation pass.
 * Deterministic Visit IDs still protect against duplicates after retry/crash.
 */
suspend fun JamesRepository.reconstructLegacyVisits(afterImport:Boolean=false):Int {
    val state=dao.get("metadata",LEGACY_VISIT_RECONSTRUCTION_VERSION)
    val watermark=state?.raw()?.obj("value")?.text("lastUpdatedAt").orEmpty()
    val evidence=if(legacyReconstructionUsesFullScan(watermark,afterImport)) dao.legacyVisitEvidence()
    else dao.legacyVisitEvidenceUpdatedAfter(watermark)
    var created=0
    evidence.forEach { evidence ->
        val visit=reconstructedVisit(evidence)?:return@forEach
        if(dao.get("personalRecords",visit.text("id"))==null) created++
        save("personalRecords",visit)
    }
    val newest=evidence.maxOfOrNull {it.updatedAt.ifBlank {it.timestamp}} ?: watermark
    if(newest.isNotBlank()) save("metadata",fields("key" to p(LEGACY_VISIT_RECONSTRUCTION_VERSION),"value" to fields("version" to p(2),"lastUpdatedAt" to p(newest),"lastRunAt" to p(now()),"mode" to p(if(afterImport)"IMPORT_RECONCILIATION" else "INCREMENTAL"))))
    return created
}
