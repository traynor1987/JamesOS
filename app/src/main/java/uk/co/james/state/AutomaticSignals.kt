package uk.co.james.state

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import uk.co.james.core.*
import uk.co.james.database.StoredRecord

/** Product heuristics, not a clinical model. Subjective outputs are explicitly weak proxies. */
fun automaticSignals(records:List<StoredRecord>, clock:Instant, sleep:StoredRecord?, baseline:Double?):Map<String,StateValue> {
    fun readableTime(record:StoredRecord)=Instant.parse(record.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")).lowercase()
    fun readableDuration(minutes:Double)="${minutes.toInt()/60}h ${minutes.toInt()%60}m"
    fun latest(metric:String,unit:String,range:ClosedFloatingPointRange<Double>):StoredRecord? = records.filter {
        it.kind=="HealthMetric" && it.source=="whoop" && it.data().text("metric")==metric && it.data().text("unit")==unit &&
        it.data().number("value",Double.NaN) in range && runCatching {
            val t=Instant.parse(it.timestamp);t<=clock && t>=clock.minus(Duration.ofHours(36))
        }.getOrDefault(false)
    // WHOOP cycle Strain is updated in-place during the day, while its score
    // timestamp can remain at cycle start. Read the most recently refreshed row.
    }.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    val recovery=latest("Recovery","%",0.0..100.0)
    val strain=latest("Strain","/ 21",0.0..21.0)
    val reasons=mutableListOf<String>()
    val pressures=mutableListOf<Double>()
    recovery?.let {
        val v=it.data().number("value")
        pressures.add(when {v<34->1.0;v<67->0.5;else->0.0})
        reasons.add("Recovery was ${v.toInt()}% (WHOOP, ${readableTime(it)}). James treats this as a ${when {v<34->"low";v<67->"moderate";else->"strong"}} recovery signal.")
        pressures.add(pressures.last())
    }
    sleep?.let {
        val minutes=it.data().number("value")
        val target=baseline?.takeIf {v->v>0}?:420.0
        pressures.add(when {minutes<target-60->1.0;minutes>target+60->0.0;else->0.5})
        val difference=(minutes-target).toInt()
        val comparison=when {
            baseline==null -> "compared with an initial 7-hour reference"
            difference>0 -> "${difference} min above your usual ${readableDuration(target)}"
            difference<0 -> "${-difference} min below your usual ${readableDuration(target)}"
            else -> "the same as your usual ${readableDuration(target)}"
        }
        reasons.add("Sleep was ${readableDuration(minutes)}, $comparison (${providerLabel(it)}, ended ${readableTime(it)}).")
    }
    strain?.let {
        val v=it.data().number("value")
        pressures.add(when {v>=14->1.0;v>=10->0.5;else->0.0})
        reasons.add("WHOOP Strain was ${String.format(Locale.UK,"%.1f",v)}/21 when last updated at ${readableTime(it)}. Strain can rise as more activity is recorded.")
    }
    val pressure=pressures.takeIf {it.isNotEmpty()}?.average()
    val level=pressure?.let {when {it>=0.7->"High";it<=0.3->"Low";else->"Moderate"}}
    return (stateFields + ("bodyLoad" to "Body load")).mapValues {(key,label)->
        val value=if(level==null)null else when(key) {
            "bodyLoad"->when(level){"High"->"Elevated";"Low"->"No clear elevation";else->"Some load"}
            "energy"->when(level){"High"->"Low";"Low"->"Good";else->"Moderate"}
            "mood"->when(level){"High"->"Low";"Low"->"Good";else->"Neutral"}
            "motivation","socialBattery"->when(level){"High"->"Low";"Low"->"Good";else->"Moderate"}
            else->level
        }
        val subjective=key in listOf("mood","mentalLoad","motivation","socialBattery","stress")
        StateValue(label,value,null,if(value==null)listOf("No usable recent sleep, WHOOP Recovery or Strain. Sync your connected sources; no check-in is required.")else reasons+listOf(
            "James combines the available signal bands and gives Recovery extra weight. Missing readings are ignored rather than counted as good or bad.",
            if(subjective)"Very low-confidence proxy from physical recovery and load. These inputs cannot tell how you feel or measure emotional stress; this may be wrong even when sensor data is accurate." else "Low-confidence estimate of available capacity, not a measured energy or fatigue score."
        ),key)
    }
}
