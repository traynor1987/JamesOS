package uk.co.james.time
import uk.co.james.database.StoredRecord
import uk.co.james.core.*
import java.time.*

val timeCategories=listOf("Sleep","Work","Driving","Gig work","Coding","Exercise","Errands","Home","Relaxation","Routine / chores")

/**
 * The single ownership window for intraday James OS state. A James Day starts
 * when the accepted completed main sleep ends; it is deliberately unrelated to
 * local midnight. The epoch-based id remains unique if two main sleeps finish
 * on the same local date, while [displayDate] remains suitable for UI labels.
 */
data class JamesDayWindow(
    val id:String,
    val start:Instant,
    val end:Instant,
    val acceptedMainSleepId:String?,
    val acceptedMainSleepStart:Instant?,
    val whoopCycleId:String?,
    val displayDate:String,
    val acceptedMainSleepSource:String?=null
) {
    init { require(end>=start) { "James Day cannot end before it starts." } }
    val fromWhoop get()=acceptedMainSleepSource=="whoop"
}

fun jamesDayWindow(
    records:List<StoredRecord>,
    clock:Instant=Instant.now(),
    zone:ZoneId=ZoneId.systemDefault()
):JamesDayWindow {
    fun usable(record:StoredRecord)=runCatching {
        val end=Instant.parse(record.timestamp)
        end<=clock&&Duration.between(end,clock)<=Duration.ofHours(36)
    }.getOrDefault(false)
    val candidates=records.filter {
        it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"&&!it.data().flag("nap")&&
            it.data().number("value",Double.NaN) in 30.0..960.0&&usable(it)
    }
    val whoop=candidates.filter {it.source=="whoop"}
    val sleep=if(whoop.isNotEmpty())whoop.maxByOrNull {it.timestamp}
        else candidates.maxWithOrNull(compareBy<StoredRecord> {it.data().number("value")}.thenBy {it.timestamp})
    val todayAtEight=clock.atZone(zone).toLocalDate().atTime(8,0).atZone(zone).toInstant()
    val fallback=if(todayAtEight<=clock)todayAtEight else todayAtEight.minus(Duration.ofDays(1))
    val start=sleep?.timestamp?.let {runCatching {Instant.parse(it)}.getOrNull()}?.takeIf {it<=clock}?:fallback
    val rawStart=sleep?.data()?.text("start").takeIf {validTime(it.orEmpty())}
        ?:sleep?.data()?.text("recordStart").takeIf {validTime(it.orEmpty())}
        ?:sleep?.raw()?.obj("metadata")?.text("recordStart").takeIf {validTime(it.orEmpty())}
    fun cycleId(record:StoredRecord?):String? = record?.data()?.text("whoopCycleId")?.takeIf {it.isNotBlank()}
        ?:record?.raw()?.obj("metadata")?.text("whoopCycleId")?.takeIf {it.isNotBlank()}
        ?:record?.raw()?.obj("metadata")?.text("whoopId")?.takeIf {it.isNotBlank()}
    return JamesDayWindow(
        id="james-day:${start.toEpochMilli()}",
        start=start,
        end=clock,
        acceptedMainSleepId=sleep?.externalId?.takeIf {it.isNotBlank()}?:sleep?.recordId,
        acceptedMainSleepStart=rawStart?.let {Instant.parse(it)},
        whoopCycleId=cycleId(sleep),
        displayDate=start.atZone(zone).toLocalDate().toString(),
        acceptedMainSleepSource=sleep?.source
    )
}

/** Canonical time ownership result. Values are always seconds. */
fun timeBreakdownSeconds(records:List<StoredRecord>,window:JamesDayWindow):Map<String,Long> {
    val start=window.start
    val end=window.end
    if(end<=start)return emptyMap()
    data class Block(val record:StoredRecord,val start:Instant,val end:Instant,val category:String)
    val blocks=records.mapNotNull {r->
        val d=r.data()
        val health=r.kind=="HealthMetric" && d.text("metric") in listOf("Sleep","Exercise")
        if(r.kind!="TimeBlock" && !health)return@mapNotNull null
        runCatching {Block(r,maxOf(start,Instant.parse(if(health)d.text("start")else r.timestamp)),minOf(end,Instant.parse(d.text("end"))),if(health)d.text("metric")else d.text("category","Unclassified"))}.getOrNull()
    }.filter {it.end>it.start}
    val edges=(listOf(start,end)+blocks.flatMap {listOf(it.start,it.end)}).distinct().sorted()
    val totals=linkedMapOf<String,Long>()
    edges.zipWithNext().forEach {(a,b)->
        val winner=blocks.filter {it.start<=a&&it.end>=b}.sortedWith(compareByDescending<Block>{it.record.source=="manual"}.thenByDescending {it.record.updatedAt}).firstOrNull()
        val category=winner?.category?:"Unclassified"
        totals[category]=(totals[category]?:0)+Duration.between(a,b).seconds
    }
    return totals
}

/** Calendar history/UI compatibility. This legacy API returns whole minutes. */
fun timeBreakdown(records:List<StoredRecord>,date:String,zone:ZoneId=ZoneId.systemDefault(),clock:Instant=Instant.now()):Map<String,Long> {
    val day=LocalDate.parse(date)
    val start=day.atStartOfDay(zone).toInstant()
    val end=minOf(day.plusDays(1).atStartOfDay(zone).toInstant(),clock)
    val window=JamesDayWindow("calendar:$date",start,end,null,null,null,date)
    return timeBreakdownSeconds(records,window).mapValues {it.value/60L}
}
fun duration(minutes:Long)="${minutes/60}h ${minutes%60}m"
