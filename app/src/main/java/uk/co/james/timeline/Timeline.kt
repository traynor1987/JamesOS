package uk.co.james.timeline
import java.time.Instant
import uk.co.james.database.StoredRecord
import uk.co.james.core.*
import uk.co.james.location.semanticBelongsToVisit
import uk.co.james.time.JamesDayWindow
data class Moment(val id: String,val date: String,val timestamp: String,val title: String,val source: String,val detail: String,val approximate: Boolean,val record: StoredRecord)
private val semanticVisitKinds=setOf("ContextPeriod","LifeFactActivity","OwnershipPeriod","VisitInterruption")
private fun intervalStart(row:StoredRecord):Instant?=row.data().text("start",row.timestamp).takeIf(::validTime)?.let(Instant::parse)
private fun intervalEnd(row:StoredRecord):Instant?=row.data().text("end").takeIf(::validTime)?.let(Instant::parse)

/** Presentation-only join. Raw semantic records remain independently stored and
 * editable, while Timeline can tell the story of one physical Visit once. */
fun visitNarrative(records:List<StoredRecord>,visit:StoredRecord):List<String> {
    if(visit.kind!="PlaceVisit")return emptyList()
    val start=intervalStart(visit)?:return emptyList(); val end=intervalEnd(visit)?:return emptyList()
    val rows=records.filter { row->
        row.kind in semanticVisitKinds && row.recordId!=visit.recordId &&
            semanticBelongsToVisit(row,visit)
    }
    val lines=mutableListOf<String>()
    rows.filter {it.kind=="ContextPeriod"}.maxByOrNull {it.timestamp}?.data()?.text("visitType")?.takeIf {it.isNotBlank()&&it!="UNKNOWN"}?.let {lines+="Context: "+it.lowercase().replace('_',' ').replaceFirstChar(Char::uppercase)}
    rows.filter {it.kind=="LifeFactActivity"}.mapNotNull {it.data().text("title").takeIf(String::isNotBlank)}.distinct().take(2).takeIf {it.isNotEmpty()}?.let {lines+="Activity: "+it.joinToString(" · ")}
    val ownership=rows.filter {it.kind=="OwnershipPeriod"}.map {it.data().text("ownership","UNKNOWN")}.filter {it!="UNKNOWN"}.groupingBy {it}.eachCount()
    ownership.takeIf {it.isNotEmpty()}?.let {lines+="Time: "+it.entries.joinToString(" · "){(key,count)->key.lowercase().replaceFirstChar(Char::uppercase)+if(count>1)" ×$count" else ""}}
    val interruptions=rows.count {it.kind=="VisitInterruption"}
    if(interruptions>0)lines+="Interruptions: $interruptions"
    return lines
}

fun isNarratedInsideVisit(record:StoredRecord,visits:List<StoredRecord>):Boolean {
    if(record.kind !in semanticVisitKinds)return false
    return visits.any { visit -> semanticBelongsToVisit(record,visit) }
}
/** The same bounded James-Day filter used by the Timeline route. Keeping this
 * pure prevents a calendar-date screen from quietly telling a different story
 * than Today around midnight or a main-sleep boundary. */
fun timelineForJamesDay(records:List<StoredRecord>,day:JamesDayWindow):List<Moment> =
    timeline(records).filter { entry ->
        entry.timestamp.takeIf(::validTime)?.let(Instant::parse)?.let { it>=day.start&&it<day.end }==true
    }
fun timeline(records: List<StoredRecord>): List<Moment> = records.mapNotNull { r ->
    if(r.kind=="PlaceVisit"&&r.data().text("supersededByVisitId").isNotBlank()) return@mapNotNull null
    if(r.source=="shift_tracker"&&r.kind=="WorkEvent"&&r.data().flag("deleted")) return@mapNotNull null
    val raw=r.raw();val d=r.data()
    when {
        r.store=="loggedEvents" -> Moment(r.recordId,r.localDate,r.timestamp,raw.text("title"),"Routines · RUT","${raw.number("points").toLong()} points · ${raw.text("note")}",raw.text("timeAccuracy")=="approximate",r)
        r.kind=="RoutineCompletion" && d.flag("completed") -> Moment(r.recordId,d.text("date"),r.timestamp,(records.find {it.recordId==d.text("routineId") && it.kind=="Routine"}?.data()?.text("title")?:"Routine")+" completed",r.source,"",d.flag("approximate"),r)
        r.kind in listOf("Event","TimelineEvent","LocationEvent","PlaceVisit","TimeBlock","MoodEntry","DailyReview","HealthMetric","WorkShift","GigSession","Nutrition","NutritionEvent","Hydration","HydrationEvent","EnergySnapshot","TimePressureCheckIn","WellbeingCheckIn","ContextPeriod","ContextDifficultInterval","DifficultInteraction","LifeFactActivity","OwnershipPeriod","VisitInterruption","WorkEvent") -> Moment(r.recordId,r.localDate,r.timestamp,d.text("title",when(r.kind){"MoodEntry","WellbeingCheckIn"->"Personal check-in";"TimePressureCheckIn"->"Time pressure check-in";"EnergySnapshot"->"Right now updated";"Nutrition","NutritionEvent"->"Meal";"Hydration","HydrationEvent"->"Hydration";"ContextPeriod"->"Context: "+d.text("visitType","UNKNOWN").lowercase().replaceFirstChar {it.uppercase()};"OwnershipPeriod"->"Time ownership: "+d.text("ownership","UNKNOWN").lowercase().replaceFirstChar {it.uppercase()};"VisitInterruption"->"Interrupted · "+d.text("reason","Unknown").lowercase().replace('_',' ').replaceFirstChar {it.uppercase()};"ContextDifficultInterval"->if(d.text("end").isBlank())"Difficult active" else "Difficult period";"DifficultInteraction"->d.text("title","Difficult interaction");"LifeFactActivity"->d.text("title","Activity");"WorkEvent"->d.text("title","Work event");"DailyReview"->"Daily review";"TimeBlock"->d.text("category");"HealthMetric"->"${d.text("metric","Health")} recorded";else->r.kind}),r.source,when(r.kind){"HealthMetric"->healthValue(r);"Nutrition","NutritionEvent"->listOfNotNull(d.number("energyKcal",Double.NaN).takeIf(Double::isFinite)?.toInt()?.let{"$it kcal"},d.number("proteinGrams",Double.NaN).takeIf(Double::isFinite)?.toInt()?.let{"Protein ${it}g"},d.number("carbohydrateGrams",Double.NaN).takeIf(Double::isFinite)?.toInt()?.let{"Carbs ${it}g"}).joinToString(" · ");"Hydration","HydrationEvent"->d.number("volumeMl",Double.NaN).takeIf(Double::isFinite)?.let{"${it.toInt()} ml"}.orEmpty();"PlaceVisit"->"${d.number("durationMin").toLong()/60}h ${d.number("durationMin").toLong()%60}m · ${d.text("category","Unclassified")} · ${d.text("activity","Unknown")}";"OwnershipPeriod"->d.text("ownershipSource","UNKNOWN").lowercase().replace('_',' ');"VisitInterruption"->d.text("provenance",d.text("source","UNKNOWN")).lowercase().replace('_',' ');"WorkEvent"->listOfNotNull(d.text("deliveryType").takeIf {it.isNotBlank()},d.text("externalShiftId").takeIf {it.isNotBlank()}).joinToString(" · ");else->d.text("note",d.text("important"))},d.flag("approximate"),r)
        else -> null
    }
}.sortedByDescending {it.timestamp}.let(::compactPassiveHealthUpdates)

private val passiveTimelineMetrics=setOf("Steps","Calories","Heart rate","Distance","Blood oxygen","Respiratory rate")

/** Keeps raw samples in storage while making Timeline a record of the day, not every passive delivery. */
private fun compactPassiveHealthUpdates(moments:List<Moment>):List<Moment> {
    val passive=moments.filter {it.record.kind=="HealthMetric"&&it.record.data().text("metric") in passiveTimelineMetrics}
    if(passive.isEmpty()) return moments
    val grouped=passive.groupBy {moment->
        val bucket=runCatching {Instant.parse(moment.timestamp).epochSecond/(15*60)}.getOrDefault(moment.timestamp.hashCode().toLong())
        "${moment.source}:$bucket"
    }
    val passiveIds=passive.map {it.id}.toSet()
    val updates=grouped.values.mapNotNull {group->
        val newest=group.maxByOrNull {it.timestamp}?:return@mapNotNull null
        val latestReadings=group.groupBy {it.record.data().text("metric")}.values
            .mapNotNull {rows->rows.maxByOrNull {it.timestamp}}
            .sortedBy {passiveTimelineMetrics.indexOf(it.record.data().text("metric")).takeIf {index->index>=0}?:Int.MAX_VALUE}
        Moment(
            id="health-update:${newest.source}:${newest.timestamp}",
            date=newest.date,
            timestamp=newest.timestamp,
            title="${sourceLabel(newest.source)} health update",
            source=newest.source,
            detail=latestReadings.joinToString("  ·  ") {reading->"${reading.record.data().text("metric")}: ${healthValue(reading.record)}"},
            approximate=false,
            record=newest.record
        )
    }
    return (moments.filter {it.id !in passiveIds}+updates).sortedByDescending {it.timestamp}
}
