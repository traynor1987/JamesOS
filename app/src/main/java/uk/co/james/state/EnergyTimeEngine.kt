package uk.co.james.state

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.settings.EnergyTimeSettings
import uk.co.james.time.JamesDayWindow
import uk.co.james.time.jamesDayWindow

data class ContextEvidence(
    val name:String,
    val contribution:Double,
    val explanation:String,
    val observedAt:String?=null,
    val source:String="",
    val freshnessMultiplier:Double=1.0,
    val included:Boolean=true
)

data class RightNowMetric(
    val id:String,
    val score:Int,
    val label:String,
    val confidence:String,
    val calculatedAt:String,
    val contributors:List<ContextEvidence>,
    val baseScore:Int=score,
    val algorithmVersion:String="1.0.0",
    val calibrationVersion:String="1.0.0",
    val calibrationSetId:String=""
)

data class NutritionContext(
    val latestMealAt:String?=null,
    val minutesSinceMeal:Long?=null,
    val recentEnergyKcal:Double?=null,
    val todayEnergyKcal:Double?=null,
    val proteinGrams:Double?=null,
    val carbohydrateGrams:Double?=null,
    val fatGrams:Double?=null,
    val hydrationMl:Double?=null,
    val latestHydrationAt:String?=null,
    val caffeineMg:Double?=null,
    val latestCaffeineAt:String?=null,
    val source:String?=null
)

data class TimeConstraint(
    val title:String,
    val startsAt:String,
    val source:String,
    val minutesUntil:Long,
    val preparationMinutes:Long,
    val travelMinutes:Long,
    val usableMinutes:Long
)

data class RightNowSummary(
    val liveEnergy:RightNowMetric,
    val sleepiness:RightNowMetric,
    val sleepinessDetails:SleepinessResult,
    val sustainability:RightNowMetric,
    val crashRisk:RightNowMetric,
    val timePressure:RightNowMetric,
    val bodyBattery:Int?,
    val mentalReserve:Int,
    val awakeMinutes:Long,
    val personalMinutes:Long,
    val nutrition:NutritionContext,
    val nextConstraint:TimeConstraint?,
    val jamesDay:JamesDayWindow
)

private fun energyLabel(score:Int)=when(score){in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";else->"VERY HIGH"}
private fun sustainabilityLabel(score:Int)=when(score){in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"GOOD";else->"STRONG"}
private fun crashLabel(score:Int)=when(score){in 0..24->"LOW";in 25..49->"MODERATE";in 50..74->"ELEVATED";else->"HIGH"}
private fun pressureLabel(score:Int)=when(score){in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";else->"VERY HIGH"}
private fun confidence(signals:Int,reported:Boolean)=when {reported&&signals>=4->"GOOD";reported||signals>=4->"MODERATE";signals>=2->"LOW";else->"LIMITED"}

private fun StoredRecord.observed():Instant?=runCatching {Instant.parse(timestamp)}.getOrNull()
private fun overlaps(row:StoredRecord,window:JamesDayWindow):Long {
    val data=row.data()
    val start=runCatching {Instant.parse(data.text("start",row.timestamp))}.getOrNull()?:return 0
    val end=runCatching {Instant.parse(data.text("end",row.timestamp))}.getOrNull()?:start
    return Duration.between(maxOf(start,window.start),minOf(end,window.end)).toMinutes().coerceAtLeast(0)
}

private val personalTerms=setOf("personal","leisure","relax","game","gaming","cinema","gym","walk","day out","project")
private fun personalTimeMinutes(records:List<StoredRecord>,window:JamesDayWindow):Long {
    val timed=records.filter {it.kind in setOf("TimeBlock","PlaceVisit","Event")}.sumOf {row->
        val label=(row.data().text("category")+" "+row.data().text("title")+" "+row.data().text("activity")).lowercase()
        if(personalTerms.none(label::contains))0L else overlaps(row,window).takeIf {it>0}
            ?:row.data().number("durationMin",0.0).toLong().coerceIn(0,240)
    }
    val rut=records.filter {it.store=="loggedEvents"&&it.raw().text("type") in setOf("positive","recovery")}
        .filter {row->row.observed()?.let {it in window.start..window.end}==true}
        .count {row->val label=(row.raw().text("category")+" "+row.raw().text("title")).lowercase();personalTerms.any(label::contains)}
    return timed+(rut*45L)
}

private fun nutritionContext(records:List<StoredRecord>,window:JamesDayWindow,clock:Instant):NutritionContext {
    val rows=records.filter {it.kind in setOf("Nutrition","NutritionEvent")&&it.observed()?.let {at->at<=clock.plus(Duration.ofMinutes(5))}==true}
    val hydration=records.filter {it.kind in setOf("Hydration","HydrationEvent")&&it.observed()?.let {at->at<=clock.plus(Duration.ofMinutes(5))}==true}
    val current=rows.filter {it.observed()?.let {at->at>=window.start}==true}
    val latest=rows.maxByOrNull {it.timestamp}
    val recent=rows.filter {it.observed()?.let {at->Duration.between(at,clock) in Duration.ZERO..Duration.ofHours(4)}==true}
    fun total(key:String)=current.map {it.data().number(key,Double.NaN)}.filter(Double::isFinite).takeIf {it.isNotEmpty()}?.sum()
    fun hydrationTotal()=hydration.filter {it.observed()?.let {at->at>=window.start}==true}.map {it.data().number("volumeMl",Double.NaN)}.filter(Double::isFinite).takeIf {it.isNotEmpty()}?.sum()
    val latestHydration=hydration.maxByOrNull {it.timestamp}
    val caffeineRows=rows.filter {it.data().number("caffeineMg",Double.NaN).isFinite()}
    val latestCaffeine=caffeineRows.maxByOrNull {it.timestamp}
    return NutritionContext(
        latestMealAt=latest?.timestamp,
        minutesSinceMeal=latest?.observed()?.let {Duration.between(it,clock).toMinutes().coerceAtLeast(0)},
        recentEnergyKcal=recent.map {it.data().number("energyKcal",Double.NaN)}.filter(Double::isFinite).takeIf {it.isNotEmpty()}?.sum(),
        todayEnergyKcal=total("energyKcal"),proteinGrams=total("proteinGrams"),carbohydrateGrams=total("carbohydrateGrams")?:total("carbsGrams"),fatGrams=total("fatGrams"),
        hydrationMl=hydrationTotal(),latestHydrationAt=latestHydration?.timestamp,caffeineMg=total("caffeineMg"),latestCaffeineAt=latestCaffeine?.timestamp,
        source=latest?.data()?.text("provider").takeIf {!it.isNullOrBlank()}?:latest?.source
    )
}

private fun nextConstraint(records:List<StoredRecord>,clock:Instant):TimeConstraint? {
    val candidates=records.filter {it.kind in setOf("WorkShift","TimeBlock","Event","PlannedEvent","Appointment")}.mapNotNull {row->
        val data=row.data();val label=(data.text("category")+" "+data.text("title")+" "+row.kind).lowercase()
        val explicit=data.flag("fixedConstraint")||listOf("work","shift","appointment","commitment","obligation").any(label::contains)
        if(!explicit)return@mapNotNull null
        val starts=data.text("start",data.text("startsAt",row.timestamp)).takeIf(::validTime)?.let(Instant::parse)?:return@mapNotNull null
        if(starts<=clock||starts>clock.plus(Duration.ofHours(36)))return@mapNotNull null
        val until=Duration.between(clock,starts).toMinutes()
        val prep=data.number("preparationMinutes",0.0).toLong().coerceIn(0,240)
        val travel=data.number("travelMinutes",0.0).toLong().coerceIn(0,240)
        TimeConstraint(data.text("title",row.kind),starts.toString(),row.source,until,prep,travel,(until-prep-travel).coerceAtLeast(0))
    }
    return candidates.minByOrNull {it.startsAt}
}

private fun latestHealth(records:List<StoredRecord>,name:String,clock:Instant)=records.filter {row->
    row.kind=="HealthMetric"&&row.data().text("metric")==name&&row.observed()?.let {it<=clock.plus(Duration.ofMinutes(5))}==true
}.maxByOrNull {it.timestamp}

fun rightNowSummary(records:List<StoredRecord>,settings:EnergyTimeSettings=EnergyTimeSettings(),clock:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):RightNowSummary {
    val window=jamesDayWindow(records,clock,zone)
    val battery=bodyBattery(records,clock,zone).value
    val sleepinessDetails=sleepiness(records,clock,zone)
    val wellbeing=mentalWellbeing(records,clock=clock,zone=zone,sleepinessContext=sleepinessDetails)
    val sleepinessMetric=sleepinessDetails.asMetric()
    val awake=Duration.between(window.start,clock).toMinutes().coerceAtLeast(0)
    val personal=if(settings.usePersonalTime)personalTimeMinutes(records,window)else 0
    val nutrition=if(settings.useMealContext)nutritionContext(records,window,clock)else NutritionContext()
    val constraint=nextConstraint(records,clock)
    val check=records.filter {it.kind=="WellbeingCheckIn"}.filter {it.observed()?.let {at->at<=clock&&Duration.between(at,clock)<=Duration.ofHours(8)}==true}.maxByOrNull {it.timestamp}
    val reported=check?.data()?.text("energy")?.uppercase()?.takeIf {it in setOf("VERY LOW","LOW","OKAY","MODERATE","HIGH","VERY HIGH")}
    val evidence=mutableListOf<ContextEvidence>()
    fun add(name:String,value:Double,why:String,at:String?=null,source:String="") {evidence+=ContextEvidence(name,value,why,at,source)}
    val reportTarget=mapOf("VERY LOW" to 10,"LOW" to 30,"OKAY" to 50,"MODERATE" to 50,"HIGH" to 72,"VERY HIGH" to 90)[reported]
    if(settings.energyCheckIns&&reportTarget!=null)check?.let { energyCheck ->
        add("Energy check-in",((reportTarget-50)*.65).coerceIn(-26.0,26.0),"Your own current Energy report is the strongest calibration evidence.",energyCheck.timestamp,energyCheck.source)
    }
    if(settings.usePhysiology&&battery!=null)add("Body Battery",((battery-50)*.18).coerceIn(-9.0,9.0),"Underlying Reserve supports or limits how sustainable the estimate is.")
    add("Mental Reserve",((wellbeing.reserve.score-50)*.12).coerceIn(-6.0,6.0),"Current mental capacity provides bounded context.")
    val recovery=latestHealth(records,"Recovery",clock)
    val recoveryFresh=recovery?.let {PhysiologyFreshnessPolicies.assess("Recovery",it.observed(),clock,it.source,"DAILY")}
    recovery?.data()?.number("value")?.takeIf(Double::isFinite)?.let {value->if(recoveryFresh?.included==true)evidence+=ContextEvidence("Recovery",((value-50)*.08).coerceIn(-4.0,4.0)*recoveryFresh.multiplier,"Daily Recovery is freshness-weighted supporting context, not subjective energy.",recovery.timestamp,recovery.source,recoveryFresh.multiplier,true)}
    val sleep=latestHealth(records,"Sleep",clock)
    val sleepFresh=sleep?.let {PhysiologyFreshnessPolicies.assess("Sleep",it.observed(),clock,it.source,"DAILY")}
    sleep?.data()?.number("value")?.takeIf(Double::isFinite)?.let {minutes->if(minutes<420&&sleepFresh?.included==true)evidence+=ContextEvidence("Short sleep",(-((420-minutes)/45.0)).coerceIn(-8.0,0.0)*sleepFresh.multiplier,"Short sleep is freshness-weighted and can work against alertness without forcing how you feel.",sleep.timestamp,sleep.source,sleepFresh.multiplier,true)}
    if(awake>16*60)add("Extended wakefulness",(-((awake-16*60)/60.0)*2.0).coerceIn(-8.0,0.0),"Long wake duration can work against alertness even when you feel temporarily energised.")
    val stress=latestHealth(records,"James Stress",clock)
    stress?.let {row->
        val fresh=PhysiologyFreshnessPolicies.assess("James Stress",row.observed(),clock,row.source,row.data().text("measurementContext"))
        val rawValue=row.data().number("value",Double.NaN)
        val value=if(rawValue.isFinite())uk.co.james.calibration.JamesCalibrationEngine.applyActiveValue(rawValue,records,"james_stress") else rawValue
        if(value.isFinite())evidence+=ContextEvidence("James Stress",if(fresh.included)((50-value)*.08).coerceIn(-4.0,4.0)*fresh.multiplier else 0.0,"Stress is freshness-weighted; stale or missing readings are not negative evidence.",row.timestamp,row.source,fresh.multiplier,fresh.included)
    }
    val movement=latestHealth(records,"Steps",clock)?.takeIf {row->row.observed()?.let {Duration.between(it,clock)<=Duration.ofMinutes(30)}==true}
    if(settings.usePhysiology&&movement!=null)add("Recent movement",2.0,"Recent movement can support a short-lived alertness estimate.",movement.timestamp,movement.source)
    if(sleepinessMetric.score>=60)add("Sleepiness",-((sleepinessMetric.score-60)/5.0).coerceIn(0.0,5.0),"Sleepiness can reduce current alertness, but it does not force Live Energy to be its inverse.",sleepinessMetric.calculatedAt,"James Sleepiness")
    if(settings.useMealContext&&nutrition.minutesSinceMeal?.let {it in 0..180}==true)add("Recent meal",0.0,"A logged meal is neutral context until James’s own check-ins show a repeated association. James OS does not infer glucose or metabolic response.",nutrition.latestMealAt,nutrition.source.orEmpty())
    if(settings.useMealContext&&nutrition.latestHydrationAt!=null)add("Recent hydration",0.0,"Logged hydration is neutral context until a later James-specific response is observed; missing water is unknown, not dehydration.",nutrition.latestHydrationAt,nutrition.source.orEmpty())
    val caffeine=nutrition.latestCaffeineAt?.let {at->records.firstOrNull {it.timestamp==at&&it.kind in setOf("Nutrition","NutritionEvent")}}
    if(settings.useCaffeineContext&&caffeine!=null)add("Recent caffeine",0.0,"Caffeine is contextual only until James’s own Energy check-ins show a repeated association. It never restores Body Battery.",caffeine.timestamp,caffeine.source)
    val liveScore=(50+evidence.filter {it.included}.sumOf {it.contribution}).roundToInt().coerceIn(0,100)
    val live=RightNowMetric("live_energy",liveScore,energyLabel(liveScore),confidence(evidence.count {it.included},reported!=null),clock.toString(),evidence)

    val recoverySupport=recovery?.data()?.number("value")?.takeIf(Double::isFinite)?.takeIf {recoveryFresh?.included==true}
    // Sleep duration and wakefulness are reconciled inside Sleepiness. Do not charge them again here.
    val supportParts=listOfNotNull(battery?.let {it*.35},wellbeing.reserve.score*.25,recoverySupport?.let {it*.15},(100-sleepinessMetric.score)*.25)
    val supportWeight=(if(battery!=null) .35 else 0.0)+.25+(if(recoverySupport!=null) .15 else 0.0)+.25
    var sustainability=(supportParts.sum()/supportWeight.coerceAtLeast(.3))
    val gap=(liveScore-sustainability).coerceAtLeast(0.0)
    sustainability-=gap*.25
    val sustainableScore=sustainability.roundToInt().coerceIn(0,100)
    val sustainableEvidence=listOf(ContextEvidence("Live Energy",-gap*.25,"Energy above underlying support lowers sustainability."),ContextEvidence("Body Battery",0.0,"Underlying physical Reserve: ${battery?:"unavailable"}."),ContextEvidence("Mental Reserve",0.0,"Underlying mental Reserve: ${wellbeing.reserve.score}."),ContextEvidence("Sleepiness",0.0,"Sleepiness ${sleepinessMetric.score}; sleep and wake pressure are reconciled here once."))
    val sustain=RightNowMetric("energy_sustainability",sustainableScore,sustainabilityLabel(sustainableScore),confidence(supportParts.size,false),clock.toString(),sustainableEvidence)
    val crashScore=((100-sustainableScore)*.65+gap*.35).roundToInt().coerceIn(0,100)
    val crash=RightNowMetric("crash_risk",crashScore,crashLabel(crashScore),sustain.confidence,clock.toString(),listOf(ContextEvidence("Sustainability",100.0-sustainableScore,"Lower support raises the possibility of a later energy drop."),ContextEvidence("Energy–Reserve gap",gap,"Current energy above underlying support adds bounded risk; it does not predict certainty.")))

    val pressureEvidence=mutableListOf<ContextEvidence>()
    var pressure=if(constraint==null)5.0 else when(constraint.usableMinutes){in 0..30->82.0;in 31..60->68.0;in 61..120->52.0;in 121..240->32.0;else->15.0}
    if(constraint!=null)pressureEvidence+=ContextEvidence("Next constraint",pressure,"${constraint.title} in ${constraint.minutesUntil} minutes; usable window ${constraint.usableMinutes} minutes after known buffers.",constraint.startsAt,constraint.source)
    val personalRelief=when {personal>=180->25.0;personal>=120->18.0;personal>=60->10.0;personal>=30->5.0;else->0.0}
    pressure-=personalRelief
    pressureEvidence+=ContextEvidence("Time That Was Mine",-personalRelief,"$personal minutes of meaningful personal time recorded in this James Day.")
    val timeReport=records.filter {it.kind=="TimePressureCheckIn"}.filter {it.observed()?.let {at->at<=clock&&Duration.between(at,clock)<=Duration.ofHours(8)}==true}.maxByOrNull {it.timestamp}
    val self=mapOf("NOT AT ALL" to -15.0,"A LITTLE" to -6.0,"SOMEWHAT" to 0.0,"A LOT" to 12.0,"EXTREMELY" to 20.0)[timeReport?.data()?.text("pressure")?.uppercase()]
    if(settings.timePressureCheckIns&&self!=null)timeReport?.let { pressureCheck ->
        pressure+=self
        pressureEvidence+=ContextEvidence("Time-pressure check-in",self,"Your report is bounded calibration evidence, not an override.",pressureCheck.timestamp,pressureCheck.source)
    }
    val pressureScore=pressure.roundToInt().coerceIn(0,100)
    val timePressure=RightNowMetric("time_pressure",pressureScore,pressureLabel(pressureScore),if(constraint==null&&self==null)"LIMITED" else confidence(pressureEvidence.size,self!=null),clock.toString(),pressureEvidence)
    fun calibrated(metric:RightNowMetric,version:String,label:(Int)->String):RightNowMetric {
        val active=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,metric.id,"1.0.0",version)
        val final=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(metric.score,records,metric.id)
        val trace=if(final==metric.score)metric.contributors else metric.contributors+ContextEvidence("Personal calibration",(final-metric.score).toDouble(),"Bounded adjustment from James Calibration "+active.version+".",source=active.setId)
        return metric.copy(score=final,label=label(final),contributors=trace,baseScore=metric.score,algorithmVersion=version,calibrationVersion=active.version,calibrationSetId=active.setId)
    }
    val finalLive=calibrated(live,JamesAlgorithmRegistry.LIVE_ENERGY_VERSION,::energyLabel)
    val finalSustain=calibrated(sustain,JamesAlgorithmRegistry.SUSTAINABILITY_VERSION,::sustainabilityLabel)
    val finalCrash=calibrated(crash,JamesAlgorithmRegistry.CRASH_RISK_VERSION,::crashLabel)
    val finalPressure=calibrated(timePressure,JamesAlgorithmRegistry.TIME_PRESSURE_VERSION,::pressureLabel)
    return RightNowSummary(finalLive,sleepinessMetric,sleepinessDetails,finalSustain,finalCrash,finalPressure,battery,wellbeing.reserve.score,awake,personal,nutrition,constraint,window)
}

fun rightNowRecord(summary:RightNowSummary,previous:RightNowSummary?=null)=fields(
    "key" to p("right-now:${summary.jamesDay.id}"),"value" to fields(
        "calculatedAt" to p(summary.liveEnergy.calculatedAt),"jamesDayId" to p(summary.jamesDay.id),
        "liveEnergy" to metricJson(summary.liveEnergy),"sleepiness" to metricJson(summary.sleepiness),"sleepinessUnderlyingPressure" to p(summary.sleepinessDetails.underlyingPressure),"sustainability" to metricJson(summary.sustainability),"crashRisk" to metricJson(summary.crashRisk),"timePressure" to metricJson(summary.timePressure),
        "bodyBattery" to (summary.bodyBattery?.let(::p)?:kotlinx.serialization.json.JsonNull),"mentalReserve" to p(summary.mentalReserve),"awakeMinutes" to p(summary.awakeMinutes),"personalMinutes" to p(summary.personalMinutes),
        "previousLiveEnergy" to (previous?.liveEnergy?.score?.let(::p)?:kotlinx.serialization.json.JsonNull),"previousTimePressure" to (previous?.timePressure?.score?.let(::p)?:kotlinx.serialization.json.JsonNull)
    )
)

private fun metricJson(metric:RightNowMetric)=fields("score" to p(metric.score),"baseScore" to p(metric.baseScore),"label" to p(metric.label),"confidence" to p(metric.confidence),"algorithmVersion" to p(metric.algorithmVersion),"calibrationVersion" to p(metric.calibrationVersion),"calibrationSetId" to p(metric.calibrationSetId),"contributors" to JsonArray(metric.contributors.map {fields("name" to p(it.name),"contribution" to p(it.contribution),"explanation" to p(it.explanation),"observedAt" to (it.observedAt?.let(::p)?:kotlinx.serialization.json.JsonNull),"source" to p(it.source),"freshnessMultiplier" to p(it.freshnessMultiplier),"included" to p(it.included))}))
