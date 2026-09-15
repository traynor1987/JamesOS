package uk.co.james.location

import java.time.Instant
import uk.co.james.core.text
import uk.co.james.core.validTime
import uk.co.james.database.StoredRecord

/**
 * A physical visit and a semantic period are deliberately different records.  A
 * period is attached to the current location anchor while it is live; when that
 * anchor is completed it becomes the stable relationship to the PlaceVisit.
 * Older rows have no link and are presented through the bounded time fallback.
 */
internal fun semanticBelongsToVisit(period: StoredRecord, visit: StoredRecord): Boolean {
    if (visit.kind != "PlaceVisit") return false
    val p = period.data()
    val v = visit.data()
    if (p.text("visitId").isNotBlank()) return p.text("visitId") == visit.recordId
    if (p.text("anchorId").isNotBlank() && v.text("anchorId").isNotBlank()) {
        return p.text("anchorId") == v.text("anchorId")
    }
    // Compatibility only: newly-created records always have an explicit
    // visit/anchor link.  Do not apply this to a row that carries another link.
    if (p.text("anchorId").isNotBlank() || p.text("visitId").isNotBlank()) return false
    val start = v.text("start").takeIf(::validTime)?.let(Instant::parse) ?: return false
    val end = v.text("end").takeIf(::validTime)?.let(Instant::parse) ?: return false
    val at = p.text("start", period.timestamp).takeIf(::validTime)?.let(Instant::parse) ?: return false
    return at >= start && at < end
}
