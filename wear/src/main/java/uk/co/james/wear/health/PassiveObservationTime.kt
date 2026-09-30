package uk.co.james.wear.health

import java.time.Duration

/** Health Services points use boot-relative time, including batched deliveries.
 * Anchor both clocks once per callback (the documented Health Services conversion). */
fun passiveObservationTime(pointFromBoot:Duration,wallTimeMillis:Long,elapsedRealtimeMillis:Long):Long? {
    val pointMillis=runCatching {pointFromBoot.toMillis()}.getOrNull()?:return null
    if(pointMillis<0||elapsedRealtimeMillis<0||pointMillis>elapsedRealtimeMillis)return null
    return runCatching {Math.addExact(Math.subtractExact(wallTimeMillis,elapsedRealtimeMillis),pointMillis)}.getOrNull()?.takeIf {it>0}
}
