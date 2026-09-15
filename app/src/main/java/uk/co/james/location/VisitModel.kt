package uk.co.james.location

import java.time.Duration
import java.time.Instant
import uk.co.james.core.text
import uk.co.james.database.StoredRecord

/** Pure, deterministic visit rules.  Android callbacks only feed the current anchor;
 * history is never replayed on a new fix. */
internal const val VISIT_DWELL_MINUTES = 5L
internal const val VISIT_EXIT_GRACE_MINUTES = 10L

internal enum class TimeOwnership { AUTONOMOUS, COMMITTED, CONSTRAINED, WORK, UNKNOWN }
internal data class LocationEvidence(val latitude:Double,val longitude:Double,val accuracyMetres:Float,val at:Instant)
internal data class PlaceMatch(val id:String,val title:String,val category:String,val distanceMetres:Float,val radiusMetres:Float)

internal fun stableWith(anchor:LocationEvidence,next:LocationEvidence,distanceMetres:Float):Boolean =
    distanceMetres <= maxOf(200f, anchor.accuracyMetres * 2, next.accuracyMetres * 2)

internal fun shouldCloseAnchor(lastSeen:Instant,next:Instant,stable:Boolean):Boolean =
    !stable && Duration.between(lastSeen,next).toMinutes() >= VISIT_EXIT_GRACE_MINUTES

internal fun isCompletedVisit(start:Instant,end:Instant):Boolean =
    Duration.between(start,end).toMinutes() >= VISIT_DWELL_MINUTES

/** Category is evidence only.  It deliberately has no ownership mapping: Home and
 * "not working" remain UNKNOWN until James explicitly corrects them. */
internal fun inferredOwnership():TimeOwnership = TimeOwnership.UNKNOWN

internal fun choosePlace(matches:List<PlaceMatch>):PlaceMatch? =
    matches.filter { it.distanceMetres <= it.radiusMetres + 100f }.minByOrNull { it.distanceMetres }

/** A correction changes the current interpretation, never erases source evidence. */
internal fun StoredRecord.isSupersededVisit():Boolean = kind=="PlaceVisit" && data().text("supersededByVisitId").isNotBlank()
internal fun authoritativeVisits(rows:List<StoredRecord>):List<StoredRecord> = rows.filter {it.kind=="PlaceVisit"&&!it.isSupersededVisit()}
