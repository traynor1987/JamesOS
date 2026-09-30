package uk.co.james.wear

import kotlinx.serialization.json.*
import uk.co.james.core.*
import java.time.Instant

/** The measurement clock is immutable across transport retries/delays. */
internal fun mapWearObservation(o:JsonObject,receivedAt:Instant=Instant.now()):JsonObject? {
    val id=o.text("id").takeIf {it.isNotBlank()&&it.length<=256}?:return null
    val type=o.text("type")
    val millis=(o["observedAt"] as? JsonPrimitive)?.longOrNull?.takeIf {it>0&&it<=receivedAt.toEpochMilli()+5*60_000L}?:return null
    val value=(o["value"] as? JsonPrimitive)?.doubleOrNull?.takeIf {it.isFinite()}?:return null
    val metric=when(type){
        "heart_rate"->"Heart rate";"steps"->"Steps";"calories"->"Calories";"distance"->"Distance"
        "samsung_hrv"->"HRV";"samsung_eda"->"Skin conductance";"samsung_skin_temperature"->"Skin temperature";"james_stress"->"James Stress";else->return null
    }
    if(value<0||type=="heart_rate"&&value<=0||type=="james_stress"&&value>100)return null
    val stamp=Instant.ofEpochMilli(millis).toString()
    return personal("HealthMetric",fields(
        "metric" to p(metric),"value" to p(value),"unit" to p(o.text("unit")),"provider" to p("uk.co.james.wear"),
        "measurementContext" to p(when(type){"samsung_hrv"->"SAMSUNG_SENSOR_CHECK_HRV";"samsung_eda","samsung_skin_temperature"->"SAMSUNG_SENSOR_CHECK";"james_stress"->"WEAR_STRESS_ESTIMATE";else->"WEAR_PASSIVE"}),
        "date" to p(dayOf(stamp))
    ),recordId="wear:$id",source="wear",timestamp=stamp).changed("externalId" to p(id),"createdAt" to p(stamp),"updatedAt" to p(stamp))
}
