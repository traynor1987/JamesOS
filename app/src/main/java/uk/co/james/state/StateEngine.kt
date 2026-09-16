package uk.co.james.state

import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import kotlinx.serialization.json.*
import java.time.*
import uk.co.james.routines.Ledger
import uk.co.james.settings.WellbeingSettings
import uk.co.james.time.jamesDayWindow
import kotlin.math.abs
import kotlin.math.roundToInt

val stateFields=linkedMapOf("mood" to "Mood","energy" to "Energy","fatigue" to "Physical fatigue","mentalLoad" to "Mental load","motivation" to "Motivation","socialBattery" to "Social battery","stress" to "Stress likelihood")
val stateChoices=mapOf("mood" to listOf("Great","Good","Neutral","Low","Difficult"),"energy" to listOf("High","Good","Moderate","Low","Very low"),"fatigue" to listOf("Low","Moderate","High"),"mentalLoad" to listOf("Low","Moderate","High"),"motivation" to listOf("Low","Moderate","Good"),"socialBattery" to listOf("Low","Moderate","Good"),"stress" to listOf("Low","Moderate","High"))
data class StateValue(val label:String,val value:String?,val confidence:Int?,val reasons:List<String>,val key:String="",val reported:Boolean=false,val remaining:Int=0)
data class StateSummary(val days:Int,val values:List<StateValue>,val inputs:JsonObject) {
    val remaining:Int get()=(7-days).coerceAtLeast(0)
    fun snapshot()=fields("ruleVersion" to p("state-v2.0"),"calibrationDays" to p(days),"inputsUsed" to inputs,"values" to JsonArray(values.map {fields("metric" to p(it.key),"value" to (it.value?.let(::p)?:JsonNull),"reported" to p(it.reported),"confidence" to p(if(it.reported)"Self-reported" else "Low · exploratory rule"),"reasons" to JsonArray(it.reasons.map(::p)))}))
}
/** Historical baselines improve estimates; check-ins are optional. */
fun stateSummary(records:List<StoredRecord>,clock:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):StateSummary {
    val day=clock.atZone(zone).toLocalDate()
    fun date(r:StoredRecord)=runCatching {Instant.parse(r.timestamp).takeIf {it<=clock}?.atZone(zone)?.toLocalDate()}.getOrNull()
    // One longest recorded sleep session per wake date; duplicates/naps never inflate sample size.
    val sleep=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"&&!it.data().flag("nap")&&it.data().text("unit")=="min"&&it.data().number("value").let {v->v.isFinite()&&v in 30.0..960.0}}
        .mapNotNull {r->date(r)?.takeIf {it>=day.minusDays(28)&&it<=day}?.let {it to r}}
        .groupBy({it.first},{it.second}).mapValues {(_,rs)->rs.maxWith(compareBy<StoredRecord> {if(it.source=="whoop")1 else 0}.thenBy {it.data().number("value")}.thenBy {it.updatedAt})}
    // Sleep belongs to a waking period, which can continue past midnight.
    val current=sleep.values.filter {Duration.between(Instant.parse(it.timestamp),clock)<=Duration.ofHours(36)}.maxByOrNull {it.timestamp}
    val currentDate=current?.let {date(it)}
    val history=sleep.filterKeys {it!=currentDate}
    val count=sleep.size.coerceAtMost(7)
    val reportRows=records.filter {it.kind in listOf("MoodEntry","DailyReview")&&date(it)?.let {d->d>=day.minusDays(28)}==true}
    val baseline=history.values.map {it.data().number("value")}.sorted().let {if(it.isEmpty())0.0 else it[it.size/2]}
    val body=bodySignals(records,clock,zone,if(count>=7&&current!=null)baseline-current.data().number("value")else null)
    val automatic=automaticSignals(records,clock,current,baseline.takeIf {history.size>=3})
    val values=stateFields.map {(key,label)->
        val reports=reportRows.filter {it.data().text(key) in stateChoices.getValue(key)}.groupBy {date(it)!!}.mapValues {(_,rs)->rs.maxBy {it.timestamp}}
        val report=reports.values.filter {Duration.between(Instant.parse(it.timestamp),clock)<Duration.ofHours(12)}.maxByOrNull {it.timestamp}
        val own=report?.data()?.text(key)
        val pairs=history.mapNotNull {(date,s)->reports[date]?.data()?.text(key)?.let {Triple(date,s,it)}}
        val reasons=mutableListOf<String>()
        var estimate:String?=null
        if(count>=7&&current!=null) {
            val minutes=current.data().number("value")
            reasons.add("Sleep ended ${Instant.parse(current.timestamp).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm"))} (${Duration.between(Instant.parse(current.timestamp),clock).toHours()}h ago).")
            reasons.add("Latest sleep: ${minutes.toInt()/60}h ${minutes.toInt()%60}m · ${providerLabel(current)}")
            if(pairs.size>=7) {
                val nearest=pairs.sortedBy {kotlin.math.abs(it.second.data().number("value")-minutes)}.take(3)
                val winner=nearest.groupingBy {it.third}.eachCount().maxByOrNull {it.value}
                if(winner!=null&&winner.value>=2){estimate=winner.key;reasons.add("${winner.value} of 3 days with the most similar sleep had this reported $label (${pairs.size} paired days).")}
                else reasons.add("Similar days had mixed reports; no clear estimate yet.")
            } else if(key=="energy") {
                estimate=when {minutes<baseline-60->"Low";minutes>baseline+60->"Good";else->"Moderate"}
                reasons.add("Compared with your ${history.size}-day typical sleep: ${baseline.toInt()/60}h ${baseline.toInt()%60}m.")
                reasons.add("Sleep-only starting rule; workload, recovery and how you feel may differ.")
            }
        }
        val auto=automatic.getValue(key)
        if(auto.value!=null&&(estimate==null||key in listOf("energy","fatigue"))) {
            estimate=auto.value
            reasons.clear()
            reasons.addAll(auto.reasons)
        }
        if(key=="fatigue"&&estimate==null){estimate=body.getValue("fatigue").value;reasons.addAll(body.getValue("fatigue").reasons)}
        val left=0
        if(estimate==null)reasons.addAll(auto.reasons)
        StateValue(label,own?:estimate,null,if(own!=null)listOf("Reported by you within the last 12 hours. Overrides automatic estimates.")else reasons,key,own!=null,left)
    }
    return StateSummary(count,values+(automatic.getValue("bodyLoad").takeIf {it.value!=null}?:body.getValue("bodyLoad")),fields("asOf" to p(clock.toString()),"zone" to p(zone.id),"currentSleepId" to p(current?.recordId?:""),"sleepRecords" to JsonArray(sleep.values.map {it.raw()}),"bodyRecords" to JsonArray(records.filter {it.kind=="HealthMetric"&&it.data().text("metric") in listOf("Resting heart rate","Exercise","Recovery","HRV","Strain","Sleep quality")&&date(it)?.let {d->d>=day.minusDays(28)}==true}.map {it.raw()}),"reports" to JsonArray(reportRows.map {fields("id" to p(it.recordId),"timestamp" to p(it.timestamp),"data" to it.data())})))
}
fun stateValues(records:List<StoredRecord>)=stateSummary(records).values
fun correction(estimate:StoredRecord,value:String)=personal("UserCorrection",fields("timestamp" to p(now()),"predictionId" to p(estimate.recordId),"originalPrediction" to estimate.raw(),"confidence" to (estimate.raw()["confidence"]?:JsonNull),"inputsUsed" to estimate.raw().obj("metadata"),"correctedValue" to p(value)))


/** Experimental, transparent personal wellbeing model. It describes trends
 * relative to the person's own data; it never diagnoses a condition. */
const val WELLBEING_ALGORITHM_VERSION=JamesAlgorithmRegistry.WELLBEING_VERSION
const val WELLBEING_CALIBRATION_VERSION="1.0.0"
const val ANXIETY_ALGORITHM_VERSION=JamesAlgorithmRegistry.ANXIETY_VERSION
const val ANXIETY_CALIBRATION_VERSION="1.0.0"
const val ANXIETY_POST_EXERCISE_WINDOW_MINUTES=120L
const val ANXIETY_MOVEMENT_WINDOW_MINUTES=30L
data class AnxietyInputStamp(val source:String,val timestamp:String)
data class AnxietyCalculationDiagnostics(val calculatedAt:String,val newestInputAt:String?,val nextEligibleAt:String,val trigger:String,val previousScore:Int?,val currentScore:Int,val unchanged:Boolean,val inputTimestamps:List<AnxietyInputStamp>)
data class WellbeingContribution(
    val source:String,
    val current:Double?,
    val baseline:Double?,
    val direction:String,
    val weight:Double,
    val confidence:Double,
    val contribution:Double,
    val explanation:String,
    /** The derived score this precise contribution participated in. */
    val targetScore:String="",
    /** Relative-to-baseline deviation when a baseline exists; never treats missing data as zero. */
    val normalizedValue:Double?=null,
    /** Provenance prevents incompatible measurements being presented as one baseline. */
    val measurementSource:String="",
    val measurementContext:String="",
    /** Raw linear evidence and its bounded calibrated form, for auditability. */
    val preCapContribution:Double?=null,
    val postCapContribution:Double?=null,
    val observedAt:String?=null,
    val ageMinutes:Long?=null,
    val freshnessClass:String="LONGITUDINAL",
    val freshnessState:String="FRESH",
    val freshnessMultiplier:Double=1.0,
    val included:Boolean=true,
    val exclusionReason:String=""
)
data class WellbeingOutput(val score:Int,val label:String,val trend:String,val confidence:String,val contributors:List<WellbeingContribution>)
data class MentalWellbeingSummary(val date:String,val anxiety:WellbeingOutput,val lowMood:WellbeingOutput,val reserve:WellbeingOutput,val days:Int,val usableHrv:Int,val sleepDays:Int,val moodCheckIns:Int,val activityDays:Int,val calculatedAt:String="",val anxietyInputs:List<AnxietyInputStamp> = emptyList(),val anxietyDiagnostics:AnxietyCalculationDiagnostics? = null,val calibrationVersions:Map<String,String> = emptyMap(),val calibrationSetIds:Map<String,String> = emptyMap())

/** Exercise suppresses fast Anxiety physiology only around that exact reading. */
internal fun contemporaneousAnxietyActivity(health:List<StoredRecord>,readingAt:Instant):Boolean {
    val exercise=health.filter {it.data().text("metric")=="Exercise"}.any {row->runCatching {
        val start=row.data().text("start").takeIf(::validTime)?.let {Instant.parse(it)}?:Instant.parse(row.timestamp)
        val end=row.data().text("end").takeIf(::validTime)?.let {Instant.parse(it)}?:start
        readingAt>=start&&readingAt<=end.plus(Duration.ofMinutes(ANXIETY_POST_EXERCISE_WINDOW_MINUTES))
    }.getOrDefault(false)}
    if(exercise)return true
    val movementStart=readingAt.minus(Duration.ofMinutes(ANXIETY_MOVEMENT_WINDOW_MINUTES))
    val steps=health.filter {it.data().text("metric")=="Steps"}.mapNotNull {row->
        runCatching {Instant.parse(row.timestamp)}.getOrNull()?.takeIf {it in movementStart..readingAt}
            ?.let {it to row.data().number("value",Double.NaN)}
    }.filter {it.second.isFinite()}.sortedBy {it.first}
    return steps.size>=2&&steps.last().second-steps.first().second>500.0
}

private fun wellbeingLabel(value:Int)=when(value){in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";else->"VERY HIGH"}
private fun reserveLabel(value:Int)=when(value){in 0..19->"DEPLETED";in 20..39->"LOW";in 40..59->"OKAY";in 60..79->"GOOD";else->"STRONG"}
private fun wellbeingConfidence(days:Int,signals:Int)=when {days>=28&&signals>=5->"GOOD CONFIDENCE";days>=14&&signals>=3->"MODERATE CONFIDENCE";days>=7->"LOW CONFIDENCE";else->"LEARNING"}

fun mentalWellbeing(records:List<StoredRecord>,settings:WellbeingSettings=WellbeingSettings(),clock:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault(),sleepinessContext:SleepinessResult?=null):MentalWellbeingSummary {
    val today=clock.atZone(zone).toLocalDate()
    fun date(row:StoredRecord)=runCatching {Instant.parse(row.timestamp).atZone(zone).toLocalDate()}.getOrNull()
    val health=records.filter {it.kind=="HealthMetric"}.filter { row-> when(row.source) { "whoop"->settings.whoop; "health_connect"->settings.healthConnect; "wear"->settings.wear && (row.data().text("metric") !in setOf("HRV","Skin conductance","Skin temperature") || settings.samsung); else->true } }
    fun metric(name:String)=health.filter {it.data().text("metric")==name}.takeIf {settings.physiology || name !in setOf("HRV","Resting heart rate","James Stress")}?.mapNotNull {row->date(row)?.let {it to row.data().number("value")}}?.filter {it.second.isFinite()}
    fun recent(name:String,days:Long)=metric(name).orEmpty().filter {it.first>=today.minusDays(days)}.sortedBy {it.first}
    // Records can share a calendar day; pick the actual newest timestamp, not an arbitrary same-day row.
    fun last(name:String):Double?=health.asSequence().filter {it.data().text("metric")==name}
        .filter {settings.physiology || name !in setOf("HRV","Resting heart rate","James Stress")}
        .mapNotNull {row->val at=runCatching {Instant.parse(row.updatedAt.ifBlank {row.timestamp})}.getOrNull();val value=row.data().number("value");if(at!=null&&at<=clock&&date(row)?.let {it>=today.minusDays(7)}==true&&value.isFinite())at to value else null}
        .maxByOrNull {it.first}?.second
    fun measurementContext(row:StoredRecord?,name:String):String=when {
        row==null->"UNKNOWN"
        name=="HRV"&&row.source=="whoop"->"WHOOP_OVERNIGHT_HRV"
        name=="HRV"&&row.source=="wear"->row.data().text("measurementContext","SAMSUNG_SENSOR_CHECK_HRV")
        name=="HRV"->"${row.source.uppercase()}_HRV"
        row.source=="whoop"->"WHOOP_DAILY"
        row.source=="wear"->"WEAR_LIVE"
        else->row.source.uppercase()
    }
    fun observedAt(row:StoredRecord?)=row?.timestamp?.takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}
    fun latestRecord(name:String)=health.asSequence().filter {it.data().text("metric")==name}
        .filter {settings.physiology || name !in setOf("HRV","Resting heart rate","James Stress")}
        .mapNotNull {row->observedAt(row)?.takeIf {it<=clock.plus(Duration.ofMinutes(5))}?.let {it to row}}
        .maxByOrNull {it.first}?.second
    fun baseline(name:String,context:String?=null):Pair<Double?,Int> {
        val rows=health.filter {row->row.data().text("metric")==name&&(context==null||measurementContext(row,name)==context)&&(settings.physiology||name !in setOf("HRV","Resting heart rate","James Stress"))}
        val values=rows.mapNotNull {row->observedAt(row)?.takeIf {it<=clock.plus(Duration.ofMinutes(5))}?.atZone(zone)?.toLocalDate()?.takeIf {it in today.minusDays(28)..today.minusDays(1)}?.let {row.data().number("value")}}.filter {it.isFinite()}
        return if(values.size>=3)values.sorted()[values.size/2] to values.size else null to values.size
    }
    // Low-Mood deliberately sees a short rolling window, rather than treating one
    // fresh physiological reading as a seven-day change.
    fun slowCurrent(name:String,context:String?=null)=health.filter {row->row.data().text("metric")==name&&(context==null||measurementContext(row,name)==context)}
        .mapNotNull {row->observedAt(row)?.takeIf {it<=clock.plus(Duration.ofMinutes(5))}?.atZone(zone)?.toLocalDate()?.takeIf {it>=today.minusDays(6)}?.let {row.data().number("value")}}.filter {it.isFinite()}.takeIf {it.isNotEmpty()}?.average()
    /**
     * Load scores use positive = more load. The old helper applied a
     * "higher is helping" sign directly to the score, reversing beneficial
     * HRV/recovery/sleep changes. This keeps semantic direction and score sign
     * together: helpful always produces a negative load contribution.
     */
    /** Bounded evidence keeps a single remarkable signal from deciding Anxiety alone. */
    fun saturatingContribution(raw:Double,cap:Double):Double=if(raw==0.0)0.0 else kotlin.math.sign(raw)*cap*kotlin.math.tanh(abs(raw)/cap)
    fun loadContribution(source:String,current:Double?,base:Double?,weight:Double,worseWhenHigher:Boolean,cap:Double,detail:String,record:StoredRecord?=null):WellbeingContribution? {
        if(current==null||base==null||base==0.0)return null
        val fresh=PhysiologyFreshnessPolicies.assess(source,observedAt(record),clock,record?.source.orEmpty(),measurementContext(record,source))
        val normalized=((current-base)/abs(base)).coerceIn(-1.0,1.0)
        val oriented=if(worseWhenHigher) normalized else -normalized
        val preCap=if(abs(normalized)<.04)0.0 else oriented*weight
        val capped=saturatingContribution(preCap,cap)
        val contribution=if(fresh.included)capped*fresh.multiplier else 0.0
        val direction=when {contribution<0->"HELPING";contribution>0->"WORKING AGAINST";else->"STEADY"}
        val explanation=if(fresh.included) detail else "$detail Excluded because ${fresh.reason.lowercase()}."
        return WellbeingContribution(source,current,base,direction,weight,(.25+minOf(.7,metric(source).orEmpty().size/20.0))*fresh.multiplier,contribution,explanation,
            measurementSource=record?.source.orEmpty(),measurementContext=measurementContext(record,source),preCapContribution=preCap,postCapContribution=capped,
            observedAt=observedAt(record)?.toString(),ageMinutes=fresh.ageMinutes,freshnessClass=fresh.freshnessClass.name,freshnessState=fresh.state.name,
            freshnessMultiplier=fresh.multiplier,included=fresh.included,exclusionReason=if(fresh.included)"" else fresh.reason)
    }
    /** Low-Mood keeps its v1.1 linear magnitude; only its seven-day input window is new. */
    fun slowLoadContribution(source:String,current:Double?,base:Double?,weight:Double,worseWhenHigher:Boolean,detail:String,record:StoredRecord?=null):WellbeingContribution? {
        if(current==null||base==null||base==0.0)return null
        val normalized=((current-base)/kotlin.math.abs(base)).coerceIn(-1.0,1.0)
        val preCap=if(kotlin.math.abs(normalized)<.04)0.0 else (if(worseWhenHigher) normalized else -normalized)*weight
        val direction=when {preCap<0->"HELPING";preCap>0->"WORKING AGAINST";else->"STEADY"}
        return WellbeingContribution(source,current,base,direction,weight,(.25+minOf(.7,metric(source).orEmpty().size/20.0)),preCap,detail,
            measurementSource=record?.source.orEmpty(),measurementContext=measurementContext(record,source),preCapContribution=preCap,postCapContribution=preCap)
    }
    fun slowSleepShortfall(source:String,current:Double?,base:Double?,weight:Double,detail:String,record:StoredRecord?=null):WellbeingContribution? {
        if(current==null||base==null||base==0.0)return null
        val shortfall=((base-current)/kotlin.math.abs(base)).coerceIn(0.0,1.0)
        val contribution=if(shortfall<.04)0.0 else shortfall*weight
        return WellbeingContribution(source,current,base,if(contribution>0)"WORKING AGAINST" else "STEADY",weight,(.25+minOf(.7,metric(source).orEmpty().size/20.0)),contribution,detail,
            measurementSource=record?.source.orEmpty(),measurementContext=measurementContext(record,source),preCapContribution=contribution,postCapContribution=contribution)
    }
    /** Sleep is range/context based: shortfall can add load; more sleep alone cannot. */
    fun sleepShortfall(source:String,current:Double?,base:Double?,weight:Double,cap:Double,detail:String,record:StoredRecord?=null):WellbeingContribution? {
        if(current==null||base==null||base==0.0)return null
        val fresh=PhysiologyFreshnessPolicies.assess(source,observedAt(record),clock,record?.source.orEmpty(),measurementContext(record,source))
        val shortfall=((base-current)/abs(base)).coerceIn(0.0,1.0)
        val preCap=if(shortfall<.04)0.0 else shortfall*weight
        val capped=saturatingContribution(preCap,cap)
        val contribution=if(fresh.included)capped*fresh.multiplier else 0.0
        return WellbeingContribution(source,current,base,if(contribution>0)"WORKING AGAINST" else "STEADY",weight,(.25+minOf(.7,metric(source).orEmpty().size/20.0)),contribution,detail,
            measurementSource=record?.source.orEmpty(),measurementContext=measurementContext(record,source),preCapContribution=preCap,postCapContribution=capped,
            observedAt=observedAt(record)?.toString(),ageMinutes=fresh.ageMinutes,freshnessClass=fresh.freshnessClass.name,freshnessState=fresh.state.name,
            freshnessMultiplier=fresh.multiplier,included=fresh.included,exclusionReason=if(fresh.included)"" else fresh.reason)
    }
    val days=health.mapNotNull(::date).distinct().count {it>=today.minusDays(27)}
    val hrvRecord=latestRecord("HRV"); val hrvContext=measurementContext(hrvRecord,"HRV")
    val hrvBase=baseline("HRV",hrvContext); val hrv=hrvRecord?.data()?.number("value")?.takeIf {it.isFinite()}
    val rhrRecord=latestRecord("Resting heart rate"); val rhrBase=baseline("Resting heart rate"); val rhr=rhrRecord?.data()?.number("value")?.takeIf {it.isFinite()}
    val stressRecord=latestRecord("James Stress"); val stressBase=baseline("James Stress"); val stress=stressRecord?.data()?.number("value")?.takeIf {it.isFinite()}?.let {uk.co.james.calibration.JamesCalibrationEngine.applyActiveValue(it,records,"james_stress")}
    val sleepRecord=latestRecord("Sleep"); val sleepBase=baseline("Sleep"); val sleep=sleepRecord?.data()?.number("value")?.takeIf {it.isFinite()}
    val recoveryRecord=latestRecord("Recovery"); val recoveryBase=baseline("Recovery"); val recovery=recoveryRecord?.data()?.number("value")?.takeIf {it.isFinite()}
    val reserve=bodyBattery(records,clock).value?.toDouble()
    val exerciseDays=health.takeIf {settings.exercise}.orEmpty().filter {it.data().text("metric")=="Exercise"}.mapNotNull(::date).filter {it>=today.minusDays(13)}.distinct().size
    val places=records.takeIf {settings.diversity}.orEmpty().filter {it.kind=="PlaceVisit"}.mapNotNull {row->date(row)?.takeIf {it>=today.minusDays(13)}?.let {row.data().text("title",row.data().text("category"))}}.filter {it.isNotBlank()&&it!="Unknown place"}.distinct().size
    val personalMinutes=records.takeIf {settings.personalTime}.orEmpty().filter {it.kind=="TimeBlock"||it.kind=="Event"}.mapNotNull {row->date(row)?.takeIf {it>=today.minusDays(6)}?.let {row.data().text("category")+" "+row.data().text("title")}}.count {it.contains("personal",true)||it.contains("leisure",true)||it.contains("walk",true)||it.contains("shop",true)||it.contains("project",true)}*45
    // Legacy Rut ledger remains visible exactly as recorded. New wellbeing uses bounded
    // rolling factual context rather than recomputing historic +/- points.
    val balance=lifeBalance(records,clock)
    val contextLoad=currentContext(records,clock)
    val rutDirection=when { balance.days7.score==null||balance.days14.score==null->0.0; else->(balance.days7.score-balance.days14.score)/2.0 }
    val reports=records.filter {it.kind in setOf("MoodEntry","WellbeingCheckIn")}.filter {row->observedAt(row)?.let {it<=clock.plus(Duration.ofMinutes(5))&&it.atZone(zone).toLocalDate()>=today.minusDays(27)}==true}
    val moodChecks=reports.size
    val recentMood=reports.maxByOrNull {observedAt(it)?:Instant.MIN}?.data()?.text("mood")
    val physiologyAt=listOfNotNull(stressRecord,hrvRecord,rhrRecord).mapNotNull {row->
        runCatching {Instant.parse(row.updatedAt.ifBlank {row.timestamp})}.getOrNull()?.takeIf {it<=clock}
    }.maxOrNull()?:clock
    val moving=contemporaneousAnxietyActivity(health,physiologyAt)
    // Only potential Anxiety inputs appear here; absent data lowers confidence and never counts against James.
    val anxietyInputs=(health.filter {it.data().text("metric") in setOf("James Stress","Resting heart rate","HRV","Sleep","Recovery","Steps","Exercise")}+reports).mapNotNull {row->
        val at=runCatching {Instant.parse(row.updatedAt.ifBlank {row.timestamp})}.getOrNull()
        at?.takeIf {it<=clock&&date(row)?.let {day->day>=today.minusDays(7)}==true}?.let {AnxietyInputStamp(row.data().text("metric",row.kind),it.toString())}
    }.sortedBy {it.timestamp}
    fun storedAnxietyDiagnostics():AnxietyCalculationDiagnostics? {
        val value=records.firstOrNull {it.store=="metadata"&&it.recordId=="mental-wellbeing-anxiety:${today}:${ANXIETY_ALGORITHM_VERSION}"}?.raw()?.obj("value")?:return null
        val calculated=value.text("calculatedAt");if(calculated.isBlank())return null
        val inputs=(value["inputs"] as? JsonArray).orEmpty().mapNotNull {entry->(entry as? JsonObject)?.let {item->item.text("timestamp").takeIf {it.isNotBlank()}?.let {stamp->AnxietyInputStamp(item.text("source"),stamp)}}}
        return AnxietyCalculationDiagnostics(calculated,value.text("newestInputAt").ifBlank {null},value.text("nextEligibleAt"),value.text("trigger","Initial calculation"),value["previousScore"]?.jsonPrimitive?.intOrNull,value.number("currentScore").toInt(),value.flag("unchanged"),inputs)
    }
    val anxiety=mutableListOf<WellbeingContribution>()
    // Fast physiological load: higher stress/RHR hurts; higher HRV/recovery helps.
    // Movement suppresses exertion-like physiology, including James Stress.
    if(!moving) {
        loadContribution("James Stress",stress,stressBase.first,28.0,true,12.0,"Sustained Stress relative to your recent baseline.",stressRecord)?.let {anxiety+=it}
        loadContribution("Resting heart rate",rhr,rhrBase.first,18.0,true,7.0,"Resting heart rate relative to your baseline while no exercise context was found.",rhrRecord)?.let {anxiety+=it}
        loadContribution("HRV",hrv,hrvBase.first,18.0,false,8.0,"HRV relative to its own ${hrvContext} baseline; gains use diminishing returns and cannot decide Anxiety alone.",hrvRecord)?.let {anxiety+=it}
        if(hrv!=null&&hrvBase.first==null) anxiety+=WellbeingContribution("HRV",hrv,null,"STEADY",18.0,.2,0.0,"${hrvContext} has ${hrvBase.second} compatible baseline samples. It is not compared with a different HRV measurement context.",measurementSource=hrvRecord?.source.orEmpty(),measurementContext=hrvContext,preCapContribution=0.0,postCapContribution=0.0)
    } else anxiety+=WellbeingContribution("Activity context",null,null,"NEUTRAL",1.0,.8,0.0,"Exercise/movement overlapped this physiological reading or ended within the documented 2-hour recovery window, so exertion-like physiology is not treated as psychological anxiety.")
    sleepShortfall("Sleep",sleep,sleepBase.first,12.0,5.0,"Short sleep relative to your typical duration can add physiological load; longer sleep alone does not.",sleepRecord)?.let {anxiety+=it}
    loadContribution("Recovery",recovery,recoveryBase.first,12.0,false,5.0,"Recovery relative to your own baseline; benefits use diminishing returns.",recoveryRecord)?.let {anxiety+=it}
    val currentCheckIn=reports.maxByOrNull {observedAt(it)?:Instant.MIN}?.takeIf {row->observedAt(row)?.let {Duration.between(it,clock)<=Duration.ofHours(24)}==true}?.data()
    val reportedAnxiety=currentCheckIn?.text("anxiety")?.uppercase()
    when(reportedAnxiety) {
        "NONE"->anxiety+=WellbeingContribution("Self-reported anxiety",0.0,null,"HELPING",1.0,.9,-6.0,"You reported no anxiety right now. This is meaningful context, but does not force the estimate to zero.")
        "LOW"->anxiety+=WellbeingContribution("Self-reported anxiety",1.0,null,"HELPING",1.0,.9,-3.0,"Your optional current check-in is a strong calibration signal.")
        "MODERATE"->anxiety+=WellbeingContribution("Self-reported anxiety",2.0,null,"STEADY",1.0,.9,0.0,"Your optional current check-in is included as calibration context.")
        "HIGH"->anxiety+=WellbeingContribution("Self-reported anxiety",3.0,null,"WORKING AGAINST",1.0,.9,3.0,"Your optional current check-in is a strong calibration signal.")
        "VERY HIGH"->anxiety+=WellbeingContribution("Self-reported anxiety",4.0,null,"WORKING AGAINST",1.0,.9,6.0,"Your optional current check-in is a strong calibration signal.")
    }
    val anxietyScore=(anxiety.sumOf {it.contribution}+20).roundToInt().coerceIn(0,100)
    val low=mutableListOf<WellbeingContribution>()
    if(settings.rut&&balance.current.score!=null) {
        val load=(50-balance.current.score).toDouble().div(3.0).coerceIn(-10.0,10.0)
        low+=WellbeingContribution("Life Balance",balance.current.score.toDouble(),50.0,if(load<0)"HELPING" else if(load>0)"WORKING AGAINST" else "STEADY",1.0,.55,load,
            "Rolling 7-day personal time, obligation, difficult context and chosen-activity pattern. Historical Rut points remain historical records.")
    }
    else if(settings.rut) low+=WellbeingContribution("Life Balance",null,null,"NEUTRAL",1.0,.2,0.0,"No factual Life Balance context is recorded yet. Unknown is not treated as a cost.")
    // Slow trend: use a rolling window. One long sleep or fresh HRV reading cannot
    // create a large Low-Mood movement.
    slowSleepShortfall("Sleep",slowCurrent("Sleep"),sleepBase.first,12.0,"Persistent short sleep relative to your own baseline can support a low-mood pattern; longer sleep alone does not.",sleepRecord)?.let {low+=it}
    slowLoadContribution("Recovery",slowCurrent("Recovery"),recoveryBase.first,10.0,false,"Recent recovery trend compared with your own baseline.",recoveryRecord)?.let {low+=it}
    slowLoadContribution("HRV",slowCurrent("HRV",hrvContext),hrvBase.first,10.0,false,"Recent HRV trend compared with its own baseline.",hrvRecord)?.let {low+=it}
    if(exerciseDays>0) low+=WellbeingContribution("Exercise",exerciseDays.toDouble(),null,"HELPING",1.0,.6,-minOf(12.0,exerciseDays*3.0),"Logged activity is present; this is supporting context, not a requirement.")
    if(personalMinutes>0) low+=WellbeingContribution("Time that was mine",personalMinutes.toDouble(),null,"HELPING",1.0,.45,-minOf(10.0,personalMinutes/30.0),"Intentional non-work/personal activity was found in your timeline.")
    if(places>1) low+=WellbeingContribution("Activity diversity",places.toDouble(),null,"NEUTRAL",1.0,.35,-2.0,"Several meaningful place/activity contexts were present. Location never decides this score alone.")
    when(recentMood?.uppercase()){
        "VERY LOW","LOW","DIFFICULT"->low+=WellbeingContribution("Self-reported mood",null,null,"WORKING AGAINST",1.0,.9,12.0,"Your recent optional check-in is treated as a strong calibration signal.")
        "GOOD","GREAT"->low+=WellbeingContribution("Self-reported mood",null,null,"HELPING",1.0,.9,-10.0,"Your recent optional check-in is treated as a strong calibration signal.")
        "OKAY","NEUTRAL"->low+=WellbeingContribution("Self-reported mood",null,null,"STEADY",1.0,.9,0.0,"You reported feeling okay. This is meaningful calibration context, not a forced score.")
    }
    val lowScore=(30+low.sumOf {it.contribution}).roundToInt().coerceIn(0,100)
    val reserveContrib=mutableListOf<WellbeingContribution>()
    reserve?.let {reserveContrib+=WellbeingContribution("Body Battery",it,null,"AVAILABLE",1.0,.65,(it-50)*.45,"Physical reserve contributes but does not determine Mental Reserve.")}
    if(settings.anxiety) reserveContrib+=WellbeingContribution("Anxiety Load",anxietyScore.toDouble(),null,"WORKING AGAINST",1.0,.7,-(anxietyScore-20)*.35,"Sustained physiological/context load can reduce available capacity.")
    if(contextLoad.active!=null&&contextLoad.score>0) {
        val cost=when {
            contextLoad.difficultActive -> -(contextLoad.score/18.0).coerceAtMost(4.0)
            contextLoad.active.visitType==VisitType.OBLIGATION -> -(contextLoad.score/28.0).coerceAtMost(1.0)
            else -> 0.0
        }
        if(cost!=0.0) reserveContrib+=WellbeingContribution("Context Load",contextLoad.score.toDouble(),null,"WORKING AGAINST",1.0,.45,cost,
            "${contextLoad.active.visitType.name.lowercase().replaceFirstChar {it.uppercase()}} context is experimental, bounded evidence. It does not assume distress from a place or obligation alone.",measurementContext=contextLoad.label.name)
    }
    when(currentCheckIn?.text("energy")?.uppercase()) {
        "VERY LOW","LOW"->reserveContrib+=WellbeingContribution("Self-reported energy",null,null,"WORKING AGAINST",1.0,.9,-5.0,"Your optional energy check-in provides direct personal context.")
        "GOOD","HIGH"->reserveContrib+=WellbeingContribution("Self-reported energy",null,null,"HELPING",1.0,.9,4.0,"Your optional energy check-in provides direct personal context.")
        "OKAY","MODERATE"->reserveContrib+=WellbeingContribution("Self-reported energy",null,null,"STEADY",1.0,.9,0.0,"Your optional energy check-in provides direct personal context.")
    }
    val nutritionResponse=nutritionResponseEvidence(records,clock)
    if(nutritionResponse.confidence!=NutritionResponseConfidence.NO_EVIDENCE&&nutritionResponse.latestAt?.let { runCatching { Duration.between(Instant.parse(it),clock)<=Duration.ofHours(8) }.getOrDefault(false) }==true) {
        val amount=when(nutritionResponse.confidence) {
            NutritionResponseConfidence.POSSIBLE->1.0
            NutritionResponseConfidence.REPEATED_ASSOCIATION, NutritionResponseConfidence.PERSONALISED_PATTERN->2.0
            NutritionResponseConfidence.NO_EVIDENCE->0.0
        }
        reserveContrib+=WellbeingContribution("${nutritionResponse.kind?.replaceFirstChar { it.uppercase() }?:"Nutrition"} response",null,null,"HELPING",amount,.35,amount,
            "Energy/self-report improved after logged ${nutritionResponse.kind?:"nutrition"}. Personal experimental association — not proof of causation.",measurementSource="health_connect",measurementContext=nutritionResponse.confidence.name,observedAt=nutritionResponse.latestAt,freshnessClass="CONTEXT",freshnessState="OBSERVED_RESPONSE")
    }
    reserveContrib+=low.filter {it.direction=="HELPING"||it.direction=="WORKING AGAINST"}.map {it.copy(contribution=-it.contribution*.65,explanation="Contextual wellbeing contributor: "+it.explanation)}
    val sleepinessNow=sleepinessContext?:sleepiness(records,clock,zone)
    if(sleepinessNow.score>=60) {
        // Sleepiness is contextual and bounded. Body Battery already carries some sleep evidence,
        // so reduce this small contribution when Reserve is already low rather than charging twice.
        val raw=-((sleepinessNow.score-60)/15.0).coerceIn(0.0,2.0)
        val reconciled=if((reserve?:50.0)<40.0)raw*.5 else raw
        reserveContrib+=WellbeingContribution("Sleepiness",sleepinessNow.score.toDouble(),null,"WORKING AGAINST",1.0,.35,reconciled,"High Sleepiness can make sustained mental effort harder, but it is a bounded reconciled context rather than a determinant.",measurementSource="James Sleepiness",observedAt=sleepinessNow.calculatedAt,freshnessClass="CURRENT",freshnessState=sleepinessNow.sourceFreshness)
    }
    val reserveScore=(50+reserveContrib.sumOf {it.contribution}).roundToInt().coerceIn(0,100)
    fun trend(value:Double)=when {value>3->"IMPROVING ↘";value< -3->"WORSENING ↗";else->"STABLE →"}
    val conf=wellbeingConfidence(days,listOfNotNull(hrv,rhr,sleep,stress,last("Recovery")).size)
    fun forTarget(target:String,items:List<WellbeingContribution>)=items.map {item->
        val normalized=if(item.current!=null&&item.baseline!=null&&item.baseline!=0.0)
            ((item.current-item.baseline)/abs(item.baseline)).coerceIn(-1.0,1.0) else null
        item.copy(targetScore=target,normalizedValue=normalized)
    }
    // Default calibration has zero effect, preserving the pre-Calibrator behaviour exactly.
    val anxietyCal=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,"anxiety_load",ANXIETY_CALIBRATION_VERSION,JamesAlgorithmRegistry.ANXIETY_VERSION)
    val lowCal=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,"low_mood_load","1.0.0",JamesAlgorithmRegistry.LOW_MOOD_VERSION)
    val reserveCal=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,"mental_reserve","1.0.0",JamesAlgorithmRegistry.WELLBEING_VERSION)
    val anxietyFinal=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(anxietyScore,records,"anxiety_load")
    val lowFinal=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(lowScore,records,"low_mood_load")
    val reserveFinal=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(reserveScore,records,"mental_reserve")
    return MentalWellbeingSummary(
        today.toString(),
        WellbeingOutput(anxietyFinal,wellbeingLabel(anxietyFinal),if(anxietyFinal<40)"LOW LOAD" else "ELEVATED",conf,forTarget("ANXIETY_LOAD",anxiety)),
        WellbeingOutput(lowFinal,wellbeingLabel(lowFinal),trend(rutDirection),conf,forTarget("LOW_MOOD_LOAD",low)),
        WellbeingOutput(reserveFinal,reserveLabel(reserveFinal),trend(rutDirection),conf,forTarget("MENTAL_RESERVE",reserveContrib)),
        days,recent("HRV",90).size,recent("Sleep",90).map {it.first}.distinct().size,moodChecks,exerciseDays,
        calculatedAt=clock.toString(),anxietyInputs=anxietyInputs,anxietyDiagnostics=storedAnxietyDiagnostics(),calibrationVersions=mapOf("anxiety_load" to anxietyCal.version,"low_mood_load" to lowCal.version,"mental_reserve" to reserveCal.version),calibrationSetIds=mapOf("anxiety_load" to anxietyCal.setId,"low_mood_load" to lowCal.setId,"mental_reserve" to reserveCal.setId)
    )
}
fun anxietyDiagnosticsRecord(summary:MentalWellbeingSummary,previousScore:Int?):JsonObject {
    val newest=summary.anxietyInputs.maxByOrNull {it.timestamp}
    val trigger=when(newest?.source) {"WellbeingCheckIn","MoodEntry"->"Manual wellbeing check-in";"James Stress"->"Fresh James Stress reading";"Resting heart rate"->"Fresh resting heart-rate reading";"HRV"->"Fresh HRV reading";"Sleep"->"Sleep update";"Recovery"->"Recovery update";"Steps","Exercise"->"Activity context update";else->"Initial calculation"}
    val calculated=runCatching {Instant.parse(summary.calculatedAt)}.getOrElse {Instant.now()}
    return fields("key" to p("mental-wellbeing-anxiety:${summary.date}:${ANXIETY_ALGORITHM_VERSION}"),"value" to fields(
        "algorithmVersion" to p(ANXIETY_ALGORITHM_VERSION),"calibrationVersion" to p(summary.calibrationVersions["anxiety_load"]?:ANXIETY_CALIBRATION_VERSION),"calibrationSetId" to p(summary.calibrationSetIds["anxiety_load"]?:"default:anxiety_load"),"date" to p(summary.date),
        "calculatedAt" to p(calculated.toString()),"newestInputAt" to (newest?.timestamp?.let(::p)?:JsonNull),"nextEligibleAt" to p(calculated.plus(Duration.ofMinutes(15)).toString()),
        "trigger" to p(trigger),"previousScore" to (previousScore?.let(::p)?:JsonNull),"currentScore" to p(summary.anxiety.score),"unchanged" to p(previousScore==summary.anxiety.score),
        "inputFingerprint" to p(summary.anxietyInputs.joinToString("|") {"${it.source}@${it.timestamp}"}),"inputs" to JsonArray(summary.anxietyInputs.map {fields("source" to p(it.source),"timestamp" to p(it.timestamp))})
    ))
}
fun wellbeingRecord(summary:MentalWellbeingSummary)=fields("key" to p("mental-wellbeing:${summary.date}:$WELLBEING_ALGORITHM_VERSION"),"value" to fields("algorithmVersion" to p(WELLBEING_ALGORITHM_VERSION),"date" to p(summary.date),"anxietyAlgorithmVersion" to p(ANXIETY_ALGORITHM_VERSION),"anxietyCalibrationVersion" to p(summary.calibrationVersions["anxiety_load"]?:ANXIETY_CALIBRATION_VERSION),"anxietyCalibrationSetId" to p(summary.calibrationSetIds["anxiety_load"]?:"default:anxiety_load"),"mentalReserveCalibrationVersion" to p(summary.calibrationVersions["mental_reserve"]?:"1.0.0"),"mentalReserveCalibrationSetId" to p(summary.calibrationSetIds["mental_reserve"]?:"default:mental_reserve"),"lowMoodCalibrationVersion" to p(summary.calibrationVersions["low_mood_load"]?:"1.0.0"),"lowMoodCalibrationSetId" to p(summary.calibrationSetIds["low_mood_load"]?:"default:low_mood_load"),"anxiety" to wellbeingJson(summary.anxiety),"lowMood" to wellbeingJson(summary.lowMood),"reserve" to wellbeingJson(summary.reserve),"days" to p(summary.days),"usableHrv" to p(summary.usableHrv),"sleepDays" to p(summary.sleepDays),"moodCheckIns" to p(summary.moodCheckIns),"activityDays" to p(summary.activityDays)))
private fun wellbeingJson(output:WellbeingOutput)=fields("score" to p(output.score),"label" to p(output.label),"trend" to p(output.trend),"confidence" to p(output.confidence),"contributors" to JsonArray(output.contributors.map {c->fields("source" to p(c.source),"current" to (c.current?.let(::p)?:JsonNull),"baseline" to (c.baseline?.let(::p)?:JsonNull),"direction" to p(c.direction),"weight" to p(c.weight),"confidence" to p(c.confidence),"contribution" to p(c.contribution),"explanation" to p(c.explanation),"targetScore" to p(c.targetScore),"normalizedValue" to (c.normalizedValue?.let(::p)?:JsonNull),"measurementSource" to p(c.measurementSource),"measurementContext" to p(c.measurementContext),"preCapContribution" to (c.preCapContribution?.let(::p)?:JsonNull),"postCapContribution" to (c.postCapContribution?.let(::p)?:JsonNull),"observedAt" to (c.observedAt?.let(::p)?:JsonNull),"ageMinutes" to (c.ageMinutes?.let(::p)?:JsonNull),"freshnessClass" to p(c.freshnessClass),"freshnessState" to p(c.freshnessState),"freshnessMultiplier" to p(c.freshnessMultiplier),"included" to p(c.included),"exclusionReason" to p(c.exclusionReason))}))
