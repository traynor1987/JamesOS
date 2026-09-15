package uk.co.james.state

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import uk.co.james.calibration.JamesCalibrationEngine
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.time.jamesDayWindow

/** Deterministic Sleepiness v1 result. Higher means a stronger current propensity to sleep. */
data class SleepinessResult(
    val score:Int,
    val label:String,
    val confidence:String,
    val underlyingPressure:Int,
    val expressedSleepiness:Int,
    val calculatedAt:String,
    val jamesDayId:String,
    val wakeAt:String,
    val timeAwakeMinutes:Long,
    val mainSleepMinutes:Double?,
    val mainSleepSource:String?,
    val baselineSleepMinutes:Double?,
    val baselineSampleCount:Int,
    val wakeContribution:Double,
    val shortSleepContribution:Double,
    val recentShortfallContribution:Double,
    val restorativeSleepContribution:Double,
    val circadianContribution:Double,
    val napModifier:Double,
    val caffeineModifier:Double,
    val latestNapAt:String?,
    val latestCaffeineAt:String?,
    val caffeineSource:String?,
    val sourceFreshness:String,
    val contributors:List<ContextEvidence>,
    val algorithmVersion:String,
    val calibrationVersion:String,
    val calibrationSetId:String
)

fun sleepinessLabel(score:Int)=when(score){in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";else->"VERY HIGH"}

private fun StoredRecord.sleepInstant():Instant?=runCatching {Instant.parse(timestamp)}.getOrNull()
private fun StoredRecord.sleepStart():Instant?=runCatching {Instant.parse(data().text("start",timestamp))}.getOrNull()
private fun StoredRecord.sleepEnd():Instant?=runCatching {Instant.parse(data().text("end",timestamp))}.getOrNull()
private fun StoredRecord.isSleep()=kind=="HealthMetric"&&data().text("metric")=="Sleep"
private fun StoredRecord.isNap()=data().flag("nap")||data().text("sleepType").equals("NAP",true)
private fun sourceRank(row:StoredRecord)=when {row.source.contains("whoop",true)->3;row.source.contains("health",true)||row.data().text("provider").contains("health",true)->2;else->1}

private fun deduplicatedSleeps(records:List<StoredRecord>,clock:Instant):List<StoredRecord> {
    val rows=records.filter {it.isSleep()&&it.sleepEnd()?.let {end->end<=clock.plus(Duration.ofMinutes(5))}==true}
    val groups=mutableListOf<MutableList<StoredRecord>>()
    rows.sortedBy {it.sleepEnd()}.forEach {row->
        val end=row.sleepEnd()!!
        val group=groups.firstOrNull {existing->existing.any {other->kotlin.math.abs(Duration.between(other.sleepEnd(),end).toMinutes())<=90}}
        if(group==null)groups+=mutableListOf(row) else group+=row
    }
    return groups.map {it.maxWith(compareBy<StoredRecord>({sourceRank(it)},{it.timestamp}))}.sortedBy {it.sleepEnd()}
}

private fun whoopSleepPending(records:List<StoredRecord>,clock:Instant):Boolean {
    val pending=records.filter {it.kind=="ExternalRecord"&&it.source=="whoop"&&it.data().text("type")=="sleep"}.mapNotNull {row->
        val raw=row.data().obj("original");val end=raw.text("end").takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}
        end?.takeIf {it<=clock&&!raw.text("score_state").equals("SCORED",true)&&!raw.flag("nap")}
    }.maxOrNull()?:return false
    val scored=records.filter {it.isSleep()&&it.source=="whoop"&&!it.isNap()}.mapNotNull {it.sleepEnd()}.maxOrNull()
    return scored==null||pending>scored
}

/**
 * Conservative, transparent two-process-style estimate. It deliberately does not read Body Battery,
 * Live Energy, Mental Reserve, food, hydration, Stress or activity as proof that James is/not sleepy.
 */
fun sleepiness(records:List<StoredRecord>,clock:Instant=Instant.now(),zone:ZoneId=ZoneId.systemDefault()):SleepinessResult {
    val entry=JamesAlgorithmRegistry.get("sleepiness")!!
    val active=JamesCalibrationEngine.activeCalibration(records,"sleepiness",entry.calibrationVersion,entry.algorithmVersion)
    fun parameter(id:String,default:Double)=active.parameters[id]?:default
    val day=jamesDayWindow(records,clock,zone)
    val sleeps=deduplicatedSleeps(records,clock)
    val main=sleeps.filter {!it.isNap()}.minByOrNull {row->kotlin.math.abs(Duration.between(row.sleepEnd(),day.start).toMinutes())}
        ?.takeIf {kotlin.math.abs(Duration.between(it.sleepEnd(),day.start).toMinutes())<=90}
    val wake=main?.sleepEnd()?:day.start
    val awakeMinutes=Duration.between(wake,clock).toMinutes().coerceAtLeast(0)
    val historical=sleeps.filter {!it.isNap()&&it.sleepEnd()?.isBefore(wake)==true}.takeLast(14)
    val durations=historical.mapNotNull {it.data().number("value",Double.NaN).takeIf(Double::isFinite)}.sorted()
    val baseline=durations.takeIf {it.size>=3}?.let {values->if(values.size%2==1)values[values.size/2]else(values[values.size/2-1]+values[values.size/2])/2.0}
    val need=(baseline?:420.0).coerceIn(330.0,570.0)
    val mainMinutes=main?.data()?.number("value",Double.NaN)?.takeIf(Double::isFinite)
    val sleepQuality=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep quality"&&it.source==main?.source}.filter {row->row.sleepInstant()?.let {at->kotlin.math.abs(Duration.between(at,wake).toMinutes())<=90}==true}.maxByOrNull {it.timestamp}?.data()?.number("value",Double.NaN)?.takeIf(Double::isFinite)

    val hoursAwake=awakeMinutes/60.0
    val lateThreshold=parameter("lateWakeThresholdHours",14.0)
    val early=(hoursAwake/lateThreshold).coerceIn(0.0,1.0).pow(1.7)*30.0
    val late=(hoursAwake-lateThreshold).coerceAtLeast(0.0)
    val wakeContribution=(early+late.pow(1.35)*parameter("lateWakeAcceleration",6.0)/4.0).coerceIn(0.0,60.0)
    val shortfall=(mainMinutes?.let {need-it}?:0.0).coerceAtLeast(0.0)
    val shortSleepContribution=(shortfall/60.0*parameter("shortSleepSensitivity",6.5)).coerceIn(0.0,30.0)
    val recentShortfall=historical.takeLast(5).asReversed().mapIndexed {index,row->
        val minutes=row.data().number("value",need);((need-minutes).coerceAtLeast(0.0)/60.0)*0.7.pow(index)
    }.sum()*parameter("recentShortfallWeight",2.0)
    val recentContribution=recentShortfall.coerceIn(0.0,15.0)
    val restorativeContribution=sleepQuality?.let {quality->when {quality<70->((70-quality)/5.0).coerceAtMost(6.0);quality>85->-((quality-85)/5.0).coerceAtMost(3.0);else->0.0}}?:0.0
    val localHour=clock.atZone(zone).hour+clock.atZone(zone).minute/60.0
    val peak=parameter("circadianPeakLocalHour",4.0)
    val circadian=parameter("circadianAmplitude",4.0)*cos(2.0*PI*(localHour-peak)/24.0)

    val naps=sleeps.filter {it.isNap()&&it.sleepEnd()?.let {end->end in day.start..clock}==true}
    val latestNap=naps.maxByOrNull {it.sleepEnd()!!}
    val napRelief=naps.sumOf {nap->
        val duration=nap.data().number("value",0.0).coerceIn(0.0,180.0)
        val age=Duration.between(nap.sleepEnd(),clock).toMinutes().coerceAtLeast(0)/60.0
        (duration/30.0)*parameter("napReliefPer30Minutes",8.0)*exp(-age/parameter("napReliefDecayHours",4.0))
    }.coerceIn(0.0,14.0)

    val caffeineRows=records.filter {it.kind in setOf("Nutrition","NutritionEvent")}.mapNotNull {row->
        val at=row.sleepInstant()?:return@mapNotNull null
        val dose=row.data().number("caffeineMg",Double.NaN).takeIf(Double::isFinite)?:return@mapNotNull null
        val age=Duration.between(at,clock)
        if(age.isNegative||age>Duration.ofHours(16)||dose<=0)return@mapNotNull null
        Triple(row,dose,age.toMinutes()/60.0)
    }.distinctBy {it.first.recordId.ifBlank {it.first.data().text("externalId",it.first.timestamp)} }
    val caffeineRelief=caffeineRows.sumOf {(_,dose,age)->(dose/100.0)*parameter("caffeineReliefPer100mg",5.0)*exp(-age/parameter("caffeineDecayHours",5.0))}.coerceIn(0.0,12.0)
    val latestCaffeine=caffeineRows.maxByOrNull {it.first.timestamp}?.first

    val underlying=(10.0+wakeContribution+shortSleepContribution+recentContribution+restorativeContribution+circadian).coerceIn(0.0,100.0).roundToInt()
    val baseExpressed=(underlying-napRelief-caffeineRelief).coerceIn(0.0,100.0).roundToInt()
    val final=JamesCalibrationEngine.applyActiveScore(baseExpressed,records,"sleepiness")
    val processing=whoopSleepPending(records,clock)
    val freshness=main?.sleepEnd()?.let {Duration.between(it,clock).toHours()}.let {hours->when {processing->"NEW MAIN SLEEP PROCESSING";main==null->"MAIN SLEEP PENDING OR UNAVAILABLE";hours!=null&&hours<=36->"CURRENT JAMES DAY";else->"STALE"}}
    val confidence=when {processing||main==null->"LOW";baseline!=null&&sourceRank(main)>=3->"GOOD";else->"MODERATE"}
    val evidence=buildList {
        if(mainMinutes!=null&&shortSleepContribution>0)add(ContextEvidence("Short main sleep",shortSleepContribution,"${mainMinutes.roundToInt()} min compared with ${need.roundToInt()} min ${if(baseline==null)"conservative learning reference" else "recent personal baseline"}.",main.timestamp,main.source))
        add(ContextEvidence("Time awake",wakeContribution,"Awake ${awakeMinutes/60}h ${awakeMinutes%60}m; wake pressure uses a bounded nonlinear curve.",wake.toString(),main?.source.orEmpty()))
        if(recentContribution>0)add(ContextEvidence("Recent sleep shortfall",recentContribution,"Recent short sleep adds bounded, decaying multi-day pressure."))
        if(restorativeContribution!=0.0)add(ContextEvidence("Restorative sleep",restorativeContribution,"Recorded sleep performance provides a small bounded contribution.",main?.timestamp,main?.source.orEmpty()))
        if(kotlin.math.abs(circadian)>=0.5)add(ContextEvidence("Circadian context",circadian,"A conservative local-time component; James's phase is still learning."))
        if(napRelief>0)add(ContextEvidence("Recent nap",-napRelief,"A nap temporarily reduces expressed Sleepiness without resetting James Day or accumulated shortfall.",latestNap?.timestamp,latestNap?.source.orEmpty()))
        if(caffeineRelief>0)add(ContextEvidence("Recent caffeine",-caffeineRelief,"Temporary alertness context only; caffeine does not remove underlying sleep pressure.",latestCaffeine?.timestamp,latestCaffeine?.source.orEmpty()))
        if(final!=baseExpressed)add(ContextEvidence("Personal calibration",(final-baseExpressed).toDouble(),"Calculated using James Calibration ${active.version}.",source=active.setId))
    }
    return SleepinessResult(
        score=final,label=sleepinessLabel(final),confidence=confidence,underlyingPressure=underlying,expressedSleepiness=final,
        calculatedAt=clock.toString(),jamesDayId=day.id,wakeAt=wake.toString(),timeAwakeMinutes=awakeMinutes,
        mainSleepMinutes=mainMinutes,mainSleepSource=main?.source,baselineSleepMinutes=baseline,baselineSampleCount=durations.size,
        wakeContribution=wakeContribution,shortSleepContribution=shortSleepContribution,recentShortfallContribution=recentContribution,
        restorativeSleepContribution=restorativeContribution,circadianContribution=circadian,napModifier=-napRelief,caffeineModifier=-caffeineRelief,
        latestNapAt=latestNap?.timestamp,latestCaffeineAt=latestCaffeine?.timestamp,
        caffeineSource=latestCaffeine?.data()?.text("sourceDisplay").takeIf {!it.isNullOrBlank()}?:latestCaffeine?.data()?.text("provider").takeIf {!it.isNullOrBlank()}?:latestCaffeine?.source,
        sourceFreshness=freshness,contributors=evidence,algorithmVersion=entry.algorithmVersion,calibrationVersion=active.version,calibrationSetId=active.setId
    )
}

fun SleepinessResult.asMetric()=RightNowMetric("sleepiness",score,label,confidence,calculatedAt,contributors,baseScore=(score-(contributors.lastOrNull {it.name=="Personal calibration"}?.contribution?:0.0)).roundToInt().coerceIn(0,100),algorithmVersion=algorithmVersion,calibrationVersion=calibrationVersion,calibrationSetId=calibrationSetId)
