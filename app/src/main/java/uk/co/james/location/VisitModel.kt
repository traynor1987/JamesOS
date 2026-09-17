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
internal enum class PlaceMatchConfidence { CONFIRMED, LIKELY, AMBIGUOUS, UNKNOWN }
internal data class PlaceMatchResolution(val place:PlaceMatch?,val confidence:PlaceMatchConfidence)

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

/** A passive point can identify a place only when one saved place is materially
 * better supported than the others.  The UI must preserve ambiguity rather
 * than picking whichever Place happened to be first in Room. */
internal fun resolvePlaceMatch(matches:List<PlaceMatch>,accuracyMetres:Float):PlaceMatchResolution {
    val candidates=matches.filter {it.distanceMetres<=it.radiusMetres+accuracyMetres}.sortedBy {it.distanceMetres}
    val first=candidates.firstOrNull()?:return PlaceMatchResolution(null,PlaceMatchConfidence.UNKNOWN)
    val second=candidates.getOrNull(1)
    if(second!=null&&second.distanceMetres-first.distanceMetres<=accuracyMetres)
        return PlaceMatchResolution(null,PlaceMatchConfidence.AMBIGUOUS)
    return PlaceMatchResolution(first,if(first.distanceMetres<=first.radiusMetres) PlaceMatchConfidence.CONFIRMED else PlaceMatchConfidence.LIKELY)
}

/** Geofence callbacks carry authoritative place identity, but Android may
 * deliver the old-place exit after the new-place enter.  Never let that late
 * exit close the new current visit. */
internal fun shouldStartGeofenceAnchor(activePlaceId:String,eventPlaceId:String,entering:Boolean):Boolean =
    entering&&activePlaceId!=eventPlaceId

internal fun shouldCloseGeofenceAnchor(activePlaceId:String,eventPlaceId:String,entering:Boolean):Boolean =
    !entering&&activePlaceId==eventPlaceId

data class PlaceSaveDuplicate(val id:String,val title:String,val category:String,val distanceMetres:Float,val sameName:Boolean)
private const val SAVE_PLACE_MAX_MATCH_DISTANCE_METRES=50f
private const val SAVE_PLACE_MAX_ACCURACY_METRES=50f

/** Saving a place is deliberately stricter than passive visit matching. A
 * nearby existing place becomes a candidate for an explicit choice; neither
 * coordinates nor a spelling resemblance silently merge two homes next door. */
internal fun placeSaveDuplicateCandidate(enteredName:String,fixAccuracyMetres:Float,matches:List<PlaceMatch>):PlaceSaveDuplicate? {
    if(fixAccuracyMetres>SAVE_PLACE_MAX_ACCURACY_METRES)return null
    return matches.asSequence()
        .filter {it.distanceMetres<=minOf(it.radiusMetres,SAVE_PLACE_MAX_MATCH_DISTANCE_METRES)}
        .minByOrNull {it.distanceMetres}
        ?.let {PlaceSaveDuplicate(it.id,it.title,it.category,it.distanceMetres,placeNamesSupportSameIdentity(enteredName,it.title))}
}

internal fun placeNamesSupportSameIdentity(left:String,right:String):Boolean = normalisedPlaceName(left)==normalisedPlaceName(right)
private fun normalisedPlaceName(value:String):String = value.lowercase()
    .replace(Regex("[^a-z0-9 ]")," ").trim().split(Regex("\\s+")).filter {it.isNotBlank()}
    .joinToString("") {token->if(token.length>3&&token.endsWith("s"))token.dropLast(1) else token}

/** Explicit place metadata wins over an unclassified duplicate during an
 * explicit user-approved merge. */
internal fun mergedPlaceCategory(primary:String,duplicate:String):String =
    if(primary.isBlank()||primary=="Unclassified") duplicate.takeIf {it.isNotBlank()&&it!="Unclassified"}?:"Unclassified" else primary

/** A correction changes the current interpretation, never erases source evidence. */
internal fun StoredRecord.isSupersededVisit():Boolean = kind=="PlaceVisit" && data().text("supersededByVisitId").isNotBlank()
internal fun authoritativeVisits(rows:List<StoredRecord>):List<StoredRecord> = rows.filter {it.kind=="PlaceVisit"&&!it.isSupersededVisit()}
