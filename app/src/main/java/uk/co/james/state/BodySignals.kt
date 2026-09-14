package uk.co.james.state

import java.time.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord

/** Transparent product heuristics. Body load is not emotional stress or a vendor stress score. */
fun bodySignals(records:List<StoredRecord>,clock:Instant,zone:ZoneId,sleepDeficit:Double?):Map<String,StateValue> {
    fun instant(r:StoredRecord)=runCatching {Instant.parse(r.timestamp)}.getOrNull()
    val pulses=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Resting heart rate"&&it.data().text("unit")=="bpm"&&it.data().number("value") in 25.0..220.0}.filter {instant(it)?.let {t->t<=clock&&t>=clock.minus(Duration.ofDays(28))}==true}
    val latest=pulses.filter {instant(it)!!>=clock.minus(Duration.ofHours(36))}.maxByOrNull {instant(it)!!}
    val previous=pulses.filter {it.data().text("provider")==latest?.data()?.text("provider")&&instant(it)!!.atZone(zone).toLocalDate()!=latest?.let {r->instant(r)!!.atZone(zone).toLocalDate()}}.groupBy {instant(it)!!.atZone(zone).toLocalDate()}.values.map {rs->rs.maxBy {instant(it)!!}.data().number("value")}.sorted()
    val delta=if(latest!=null&&previous.size>=7)latest.data().number("value")-previous[previous.size/2]else null
    // Merge overlapping exercise intervals across providers instead of summing duplicate workouts.
    val intervals=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Exercise"}.mapNotNull {r->runCatching {
        val start=Instant.parse(r.data().text("start"));val end=Instant.parse(r.data().text("end"))
        if(end<=clock&&end>start&&end>=clock.minus(Duration.ofHours(24)))maxOf(start,clock.minus(Duration.ofHours(24))) to end else null
    }.getOrNull()}.sortedBy {it.first}
    var end=Instant.MIN;var seconds=0L
    intervals.forEach {(a,b)->val start=maxOf(a,end);if(b>start)seconds+=Duration.between(start,b).seconds;end=maxOf(end,b)}
    val exercise=if(intervals.isNotEmpty())seconds/60 else null
    val factors=mutableListOf<String>()
    var load=0
    if(sleepDeficit!=null){if(sleepDeficit>60)load++;factors.add("Recorded sleep ${if(sleepDeficit>0)"below"else "at or above"} your typical duration${if(sleepDeficit>0)" by ${sleepDeficit.toInt()} min"else ""}.")}
    if(delta!=null){if(delta>=5)load++;factors.add("Resting heart rate ${if(delta>=0)"+"else ""}${delta.toInt()} bpm versus ${previous.size} previous days from the same provider · ${providerLabel(latest!!)}.")}
    if(exercise!=null){if(exercise>=60)load++;factors.add("$exercise min of recorded exercise in the last 24 hours; overlapping sessions counted once.")}
    val enough=sleepDeficit!=null||delta!=null||exercise!=null
    val fatigue=if(enough)when {load>=2->"High";load==1->"Moderate";else->"Low"}else null
    val fatigueReasons=factors+"Early fatigue estimate from recorded inputs only. Missing workouts, work, illness or recovery data can change how you feel."
    val body=if(delta!=null&&(sleepDeficit!=null||exercise!=null))when {load>=2->"Elevated";load==1->"Some load";else->"No clear elevation"}else null
    return mapOf(
        "fatigue" to StateValue("Physical fatigue",fatigue,null,if(enough)fatigueReasons else listOf("Allow Sleep, Resting heart rate or Exercise in Health Connect. Sleep and resting heart rate need historical baselines."),"fatigue"),
        "bodyLoad" to StateValue("Body load",body,null,if(body!=null)factors+"Not a WHOOP/Samsung stress score and not a measure of emotional stress. Low-confidence rule, not a medical assessment."else listOf("Needs a recent resting-heart-rate reading, 7 previous days from the same provider, and usable sleep or exercise data. This estimates body load, not emotional stress."),"bodyLoad")
    )
}
