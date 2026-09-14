package uk.co.james.state

import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.time.JamesDayWindow
import uk.co.james.time.jamesDayWindow
import uk.co.james.time.timeBreakdownSeconds

data class BodyBattery(
    val value:Int?,
    val morning:Int?,
    val used:Int?,
    val headline:String,
    val summary:String,
    val confidence:String,
    val reasons:List<String>,
    val forecast:IntRange?=null,
    val recharge:Int=0,
    val personalisedDays:Int=0,
    val wakeTime:Instant?=null,
    /** WHOOP has detected the latest main sleep but has not scored it yet. */
    val sleepProcessing:Boolean=false,
    val algorithmVersion:String=JamesAlgorithmRegistry.BODY_BATTERY_VERSION,
    val calibrationVersion:String=JamesAlgorithmRegistry.BODY_BATTERY_CALIBRATION,
    val strainDiagnostics:StrainDiagnostics?=null,
    val trace:BodyBatteryTrace?=null,
    val baseValue:Int?=value,
    val calibrationSetId:String?=null
)

data class BodyBatteryTrace(
    val calculatedAt:String,
    val jamesDayId:String,
    val trigger:String,
    val previousReserve:Int?,
    val currentReserve:Int?,
    val morningReserve:Int,
    val awakeMinutes:Long,
    val awakeTimeCost:Double,
    val strainCost:Double,
    val activityCost:Double,
    val workloadCost:Double,
    val restorativeCredit:Int,
    val jamesDayStart:String="",
    val acceptedMainSleepId:String?=null,
    val whoopCycleId:String?=null,
    val contextTimeSeconds:Map<String,Long> = emptyMap(),
    val contextTimeUnit:String="seconds",
    val previousPersistenceStatus:String="NOT_YET_PERSISTED",
    val previousPersistenceReason:String="",
    val increaseReason:String?=null,
    val sourceTimestamps:List<String> = emptyList(),
    val baseOutput:Int?=currentReserve,
    val calibrationEffect:Int=0,
    val finalOutput:Int?=currentReserve,
    val calibrationSetId:String?=null
)

data class StrainDiagnostics(
    val jamesDayId:String,
    val rawWhoopStrain:Double?,
    val category:String,
    val transformedTotalCost:Double,
    val alreadyAccountedCost:Double,
    val latestIncrementalDebit:Double,
    val recoveryModifier:Double,
    val sourceReconciliation:String,
    val whoopTimestamp:String?,
    val whoopCycleId:String?=null,
    val currentDayPending:Boolean=false,
    val ignoredPreviousDayStrain:Double?=null,
    val sleepBoundary:String?=null,
    val accepted:Boolean=false,
    val highestRawStrain:Double?=null,
    val invalidatedPersistedStrain:Double?=null,
    val invalidationReason:String?=null,
    val previousWhoopCycleId:String?=null,
    val sourceStart:String?=null,
    val sourceEnd:String?=null
)

/** A transparent piecewise-smooth calibration curve, not WHOOP's proprietary formula. */
private val strainAnchors=listOf(
    0.0 to 0.0, 1.0 to 0.25, 2.0 to 0.75, 4.0 to 2.5, 5.0 to 4.5,
    6.0 to 5.5, 6.7 to 7.0, 8.0 to 8.5, 9.0 to 10.0, 10.0 to 11.5,
    11.0 to 13.5, 13.0 to 18.0, 14.0 to 21.0, 16.0 to 28.0,
    18.0 to 36.5, 19.0 to 42.5, 20.0 to 49.5, 21.0 to 57.5
)

/** Monotone smoothstep interpolation makes the total cost accelerate without buckets. */
fun whoopStrainReserveCost(strain:Double):Double {
    val x=strain.coerceIn(0.0,21.0)
    val pair=strainAnchors.zipWithNext().firstOrNull {x>=it.first.first&&x<=it.second.first}
        ?:return if(x<=0.0)0.0 else strainAnchors.last().second
    val left=pair.first
    val right=pair.second
    val t=((x-left.first)/(right.first-left.first)).coerceIn(0.0,1.0)
    val smooth=t*t*(3.0-2.0*t)
    return left.second+(right.second-left.second)*smooth
}
fun whoopStrainCategory(strain:Double)=when(strain.coerceIn(0.0,21.0)){
    in 0.0..9.999999->"LIGHT"; in 10.0..13.999999->"MODERATE"; in 14.0..17.999999->"HIGH"; else->"ALL OUT"
}

/** A main WHOOP sleep is real as soon as it is detected, but its score arrives later. */
private fun whoopSleepIsProcessing(records:List<StoredRecord>,clock:Instant):Boolean {
    val latestPending=records.asSequence()
        .filter {it.kind=="ExternalRecord"&&it.source=="whoop"&&it.data().text("type")=="sleep"}
        .mapNotNull {record->
            val raw=record.data().obj("original")
            val end=raw.text("end").takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}?:return@mapNotNull null
            if(raw.text("score_state").equals("SCORED",true)||raw.flag("nap")||end>clock)null else end
        }.maxOrNull()?:return false
    val latestCompleted=records.asSequence()
        .filter {it.kind=="HealthMetric"&&it.source=="whoop"&&it.data().text("metric")=="Sleep"&&!it.data().flag("nap")}
        .mapNotNull {record->record.data().text("end",record.timestamp).takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}}
        .maxOrNull()
    return latestCompleted==null||latestPending>latestCompleted
}

/** A transparent James estimate, not a WHOOP/Samsung metric or medical measurement. */
fun bodyBattery(
    records:List<StoredRecord>,
    clock:Instant=Instant.now(),
    zone:ZoneId=ZoneId.systemDefault(),
    trigger:String="state_recalculation"
):BodyBattery {
    val day=clock.atZone(zone).toLocalDate()
    if(whoopSleepIsProcessing(records,clock)) return BodyBattery(
        value=null,morning=null,used=null,
        headline="Charging your new day",
        summary="WHOOP has detected your sleep and is processing it. James will reset your new day when the final sleep score arrives.",
        confidence="Waiting for WHOOP",
        reasons=listOf("The latest main WHOOP sleep is detected but not scored yet. James keeps the previous day boundary and deliberately withholds today's reserve, sleep, recovery and strain values until WHOOP completes processing."),
        sleepProcessing=true
    )
    fun valid(record:StoredRecord,maxAge:Duration=Duration.ofHours(36))=runCatching {val time=Instant.parse(record.timestamp);time<=clock&&Duration.between(time,clock)<=maxAge}.getOrDefault(false)
    // WHOOP's current cycle keeps its original timestamp while its Strain is
    // refreshed. Prefer update time so the battery follows the latest cycle score.
    fun latest(metric:String,source:String?=null)=records.filter {it.kind=="HealthMetric"&&it.data().text("metric").equals(metric,true)&&(source==null||it.source==source)&&it.data().number("value",Double.NaN).isFinite()&&valid(it)}.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    fun time(record:StoredRecord)=Instant.parse(record.timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("h:mm a",Locale.UK)).lowercase()
    val latestRecovery=latest("Recovery","whoop")
    // A nap is a recharge inside an existing waking day. It must never become the
    // day's anchor sleep just because WHOOP finished it more recently.
    val mainSleeps=records.filter {
        it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"&&
            !it.data().flag("nap")&&it.data().number("value",Double.NaN) in 30.0..960.0&&valid(it)
    }
    val directMainSleeps=mainSleeps.filter {it.source=="whoop"}
    val sleep=if(directMainSleeps.isNotEmpty())directMainSleeps.maxByOrNull {it.timestamp}
        else mainSleeps.maxWithOrNull(compareBy<StoredRecord> {it.data().number("value")}.thenBy {it.timestamp})
    val baseDayWindow=jamesDayWindow(records,clock,zone)
    val wake=baseDayWindow.start
    fun whoopCycleId(record:StoredRecord?):String? {
        val dataCycle=record?.data()?.text("whoopCycleId").orEmpty()
        if(dataCycle.isNotBlank())return dataCycle
        val metadata=record?.raw()?.obj("metadata")
        val metadataCycle=metadata?.text("whoopCycleId").orEmpty()
        if(metadataCycle.isNotBlank())return metadataCycle
        return metadata?.text("whoopId")?.takeIf {it.isNotBlank()}
    }
    val sleepCycleId=whoopCycleId(sleep)
    val recoveryCandidates=records.filter {it.kind=="HealthMetric"&&it.source=="whoop"&&it.data().text("metric")=="Recovery"&&it.data().number("value",Double.NaN).isFinite()&&valid(it)}
    val recovery=when {
        sleepCycleId!=null -> recoveryCandidates.filter {whoopCycleId(it)==sleepCycleId}.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
        else -> recoveryCandidates.filter {runCatching {Instant.parse(it.timestamp)>=wake.minus(Duration.ofHours(6))}.getOrDefault(false)}.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    } ?: latestRecovery.takeIf {sleep==null}
    val activeCycleId=whoopCycleId(recovery)?:sleepCycleId
    val dayWindow=baseDayWindow.copy(whoopCycleId=activeCycleId)
    val jamesDayId=dayWindow.id
    val legacyJamesDayId=dayWindow.displayDate
    // This is the one authoritative in-progress state for a James Day. Pick by
    // full update timestamp so an older same-day snapshot cannot replace it.
    fun newestState(candidates:Sequence<StoredRecord>)=candidates.maxByOrNull {candidate->
            candidate.raw().obj("value").obj("trace").text(
                "calculatedAt",
                candidate.raw().obj("value").text("updatedAt",candidate.updatedAt.ifBlank {candidate.timestamp})
            )
        }
    val authoritativeKey="body-battery:$jamesDayId"
    val legacyKey="body-battery:$legacyJamesDayId"
    val priorBodyRecord=newestState(records.asSequence().filter {it.store=="metadata"&&it.raw().text("key")==authoritativeKey})
        ?:newestState(records.asSequence().filter {it.store=="metadata"&&it.raw().text("key")==legacyKey})
    val priorBodyUsesLegacyKey=priorBodyRecord?.raw()?.text("key")==legacyKey
    val priorBodyState=priorBodyRecord?.raw()?.obj("value")
    val sleepQualityCandidates=records.filter {
        it.kind=="HealthMetric"&&it.source=="whoop"&&
            it.data().text("metric")=="Sleep quality"&&!it.data().flag("nap")&&
            it.data().number("value",Double.NaN).isFinite()&&valid(it)
    }
    val sleepQuality=when {
        sleepCycleId!=null->sleepQualityCandidates.filter {whoopCycleId(it)==sleepCycleId}.maxByOrNull {it.timestamp}
        else->sleepQualityCandidates.filter {runCatching {!Instant.parse(it.timestamp).isBefore(wake.minus(Duration.ofHours(6)))}.getOrDefault(false)}.maxByOrNull {it.timestamp}
    }
    val allWhoopStrain=records.filter {it.kind=="HealthMetric"&&it.source=="whoop"&&it.data().text("metric")=="Strain"&&it.data().number("value",Double.NaN).isFinite()&&valid(it)}
    fun strainRecordStart(record:StoredRecord):String? =
        record.data().text("recordStart").takeIf(::validTime)
            ?:record.raw().obj("metadata").text("recordStart").takeIf(::validTime)
            ?:record.timestamp.takeIf(::validTime)
    fun strainRecordEnd(record:StoredRecord):String? =
        record.data().text("recordEnd").takeIf(::validTime)
            ?:record.raw().obj("metadata").text("recordEnd").takeIf(::validTime)
    val acceptedSleepStart=sleep?.data()?.text("start").takeIf {validTime(it.orEmpty())}
        ?:sleep?.data()?.text("recordStart").takeIf {validTime(it.orEmpty())}
        ?:sleep?.raw()?.obj("metadata")?.text("recordStart").takeIf {validTime(it.orEmpty())}
    fun cycleBelongsToCurrentJamesDay(record:StoredRecord):Boolean = runCatching {
        val start=Instant.parse(strainRecordStart(record)?:return@runCatching false)
        val end=strainRecordEnd(record)
        val startsAfterWake=!start.isBefore(wake)
        // WHOOP can open the new cycle during the accepted main-sleep interval,
        // before the scored sleep's end timestamp. An open cycle beginning inside
        // that exact sleep transition is current; a closed/older cycle is not.
        val openCycleDuringAcceptedSleep=end==null&&acceptedSleepStart?.let {
            !start.isBefore(Instant.parse(it))&&start<=wake
        }==true
        startsAfterWake||openCycleDuringAcceptedSleep
    }.getOrDefault(false)
    val strain=allWhoopStrain.filter(::cycleBelongsToCurrentJamesDay)
        .maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    val previousDayStrain=allWhoopStrain.filterNot(::cycleBelongsToCurrentJamesDay).maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    val hrv=latest("HRV","whoop")
    val resting=latest("Resting heart rate")
    val samsung=records.filter {it.kind=="HealthMetric"&&it.source=="health_connect"&&it.data().text("provider").contains("shealth",true)&&it.data().text("metric").lowercase() in listOf("energy score","daily energy")&&it.data().number("value",Double.NaN) in 0.0..100.0&&valid(it)}.maxByOrNull {it.timestamp}
    val recoveryValue=recovery?.data()?.number("value")?.coerceIn(0.0,100.0)
    val samsungValue=samsung?.data()?.number("value")?.coerceIn(0.0,100.0)
    val sleepScore=sleepQuality?.data()?.number("value")?.coerceIn(0.0,100.0)
    val sleepMinutes=sleep?.data()?.number("value")?.takeIf {it in 30.0..960.0}
    val sleepCapacity=sleepScore?:sleepMinutes?.let {(it/480.0*100.0).coerceIn(30.0,100.0)}
    // Recovery is readiness, not a literal fuel gauge. It remains the strongest
    // input, while restorative sleep establishes how much usable reserve the day
    // can start with. Samsung is deliberately a small supporting input.
    var morning=when {
        recoveryValue!=null&&sleepCapacity!=null&&samsungValue!=null -> recoveryValue*.60+sleepCapacity*.30+samsungValue*.10
        recoveryValue!=null&&sleepCapacity!=null -> recoveryValue*.62+sleepCapacity*.38
        recoveryValue!=null&&samsungValue!=null -> (20.0+recoveryValue*.80)*.85+samsungValue*.15
        recoveryValue!=null -> 20.0+recoveryValue*.80
        samsungValue!=null&&sleepCapacity!=null -> samsungValue*.20+sleepCapacity*.80
        samsungValue!=null -> samsungValue
        sleepCapacity!=null -> sleepCapacity
        else -> null
    }
    if(morning==null)return BodyBattery(null,null,null,"Waiting for readiness data","Sync WHOOP or Sleep in Health Connect to start today’s battery.","Unavailable",listOf("No recent Recovery, Samsung energy score, Sleep Performance or sleep duration was available. Missing data is not treated as zero."))
    fun baseline(metric:String,current:StoredRecord?)=records.filter {it.kind=="HealthMetric"&&it.data().text("metric").equals(metric,true)&&it.data().number("value",Double.NaN).isFinite()&&it.timestamp<clock.toString()&&runCatching {Instant.parse(it.timestamp)>=clock.minus(Duration.ofDays(28))}.getOrDefault(false)&&(current==null||it.data().text("provider")==current.data().text("provider"))}.groupBy {it.localDate}.values.map {rows->rows.maxBy {it.timestamp}.data().number("value")}.sorted().let {values->values.takeIf {it.size>=7}?.let {it[it.size/2] to it.size}}
    val hrvBaseline=baseline("HRV",hrv)
    val rhrBaseline=baseline("Resting heart rate",resting)
    val baselineReasons=mutableListOf<String>()
    if(recoveryValue==null&&hrv!=null&&hrvBaseline!=null&&hrvBaseline.first>0) {
        val change=(hrv.data().number("value")-hrvBaseline.first)/hrvBaseline.first
        morning+=when {change<=-.20->-5.0;change>=.10->3.0;else->0.0}
        baselineReasons.add("HRV is ${kotlin.math.abs(change*100).toInt()}% ${if(change<0)"below"else "above"} your ${hrvBaseline.second}-day baseline.")
    }
    if(recoveryValue==null&&resting!=null&&rhrBaseline!=null) {
        val change=resting.data().number("value")-rhrBaseline.first
        morning+=when {change>=5->-5.0;change<=-3->2.0;else->0.0}
        baselineReasons.add("Resting heart rate is ${kotlin.math.abs(change).toInt()} bpm ${if(change>0)"above"else "below"} your ${rhrBaseline.second}-day baseline.")
    }
    // Recovery and sleep can arrive/refine later, but they must not retroactively
    // raise an already-established day base. That was the source of 39 → 42
    // non-restorative jumps during ordinary source refreshes.
    val persistedMorning=priorBodyState?.number("morning",Double.NaN)?.takeIf {it.isFinite()&&it in 1.0..100.0}
    if(persistedMorning!=null) {
        morning=persistedMorning
        baselineReasons.add("Today’s morning Reserve is locked to the first valid James Day calculation; later source refreshes cannot recharge the day.")
    }
    morning=morning.coerceIn(1.0,100.0)
    val awakeHours=Duration.between(wake,clock).toMinutes().coerceAtLeast(0)/60.0
    val breakdownSeconds=timeBreakdownSeconds(records,dayWindow)
    fun hours(category:String)=(breakdownSeconds[category]?:0L)/3600.0
    val restHours=hours("Relaxation")+hours("Free time")
    val activeCalibration=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,"body_battery",JamesAlgorithmRegistry.BODY_BATTERY_CALIBRATION,JamesAlgorithmRegistry.BODY_BATTERY_VERSION)
    val awakeDrainPerHour=activeCalibration.parameters["awakeDrainPerHour"]?:1.65
    val naturalDrain=(awakeHours*awakeDrainPerHour-restHours*.8).coerceIn(0.0,30.0)
    val workloadDrain=(
        hours("Work")*0.4+
        hours("Driving")*0.7+
        hours("Gig work")*0.8+
        hours("Coding")*0.25
    ).coerceIn(0.0,12.0)
    val reportedStrain=strain?.data()?.number("value")?.coerceIn(0.0,21.0)
    val previousStrain=priorBodyState?.obj("strain")
    fun sourceStart(record:StoredRecord?):String? = record?.let(::strainRecordStart)
    fun sourceEnd(record:StoredRecord?):String? = record?.let(::strainRecordEnd)
    val currentCycleId=whoopCycleId(strain)
    val currentSourceStart=sourceStart(strain)
    val currentSourceEnd=sourceEnd(strain)
    val persistedRaw=previousStrain?.number("highestRawStrain",Double.NaN)?.takeIf {it.isFinite()}
    val persistedCycleId=previousStrain?.text("whoopCycleId").orEmpty()
    val persistedSourceStart=previousStrain?.text("sourceStart").takeIf {validTime(it.orEmpty())}
    val persistedSourceEnd=previousStrain?.text("sourceEnd").takeIf {validTime(it.orEmpty())}
    val persistedBoundary=previousStrain?.text("sleepBoundary").takeIf {validTime(it.orEmpty())}
    val persistedStartedThisDay=persistedSourceStart?.let {startText->
        runCatching {
            val start=Instant.parse(startText)
            !start.isBefore(wake)||(persistedSourceEnd==null&&acceptedSleepStart?.let {
                !start.isBefore(Instant.parse(it))&&start<=wake
            }==true)
        }.getOrDefault(false)
    }==true
    val persistedCycleMatches=currentCycleId==null||(persistedCycleId.isNotBlank()&&persistedCycleId==currentCycleId)
    // Legacy state may have today's date but still contain yesterday's maximum.
    // It is trusted only when both the exact sleep boundary and the source cycle
    // provenance prove that it belongs to this same James Day.
    val persistedDayMatches=previousStrain?.text("jamesDayId")==jamesDayId||
        (priorBodyUsesLegacyKey&&previousStrain?.text("jamesDayId")==legacyJamesDayId&&persistedBoundary==wake.toString())
    val priorStrainOwned=previousStrain!=null&&
        persistedDayMatches&&
        persistedBoundary==wake.toString()&&
        persistedStartedThisDay&&
        persistedCycleMatches
    val priorRaw=persistedRaw.takeIf {priorStrainOwned}
    val invalidatedPersistedStrain=persistedRaw.takeIf {!priorStrainOwned}
    // WHOOP strain remains monotonic only inside one owned James Day/cycle.
    val strainValue=listOfNotNull(reportedStrain,priorRaw).maxOrNull()
    val retainedPrior=priorRaw!=null&&(reportedStrain==null||priorRaw>reportedStrain)
    val acceptedCycleId=if(retainedPrior)persistedCycleId.takeIf {it.isNotBlank()} else currentCycleId
    val acceptedSourceStart=if(retainedPrior)persistedSourceStart else currentSourceStart
    val acceptedSourceEnd=if(retainedPrior)persistedSourceEnd else currentSourceEnd
    val acceptedTimestamp=if(retainedPrior)previousStrain?.text("whoopTimestamp")?.takeIf(::validTime)
        else strain?.updatedAt?.ifBlank {strain.timestamp}
    val currentDayStrainPending=strainValue==null&&sleep!=null
    val recoveryAware=records.firstOrNull {it.store=="settings"&&it.raw().text("key")=="body-battery-recovery-aware-strain"}?.raw()?.flag("value")?:true
    val recoveryModifier=if(!recoveryAware||recoveryValue==null)0.0 else (((50.0-recoveryValue)/50.0)*.12).coerceIn(-.10,.12)
    val baseStrainCost=strainValue?.let(::whoopStrainReserveCost)?:0.0
    val transformedStrainCost=(baseStrainCost*(1.0+recoveryModifier)).coerceAtLeast(0.0)
    val alreadyAccounted=if(priorStrainOwned)previousStrain?.number("transformedCost",0.0)?.coerceAtLeast(0.0)?:0.0 else 0.0
    val latestIncremental=(transformedStrainCost-alreadyAccounted).coerceAtLeast(0.0)
    val invalidationReason=invalidatedPersistedStrain?.let {
        "Persisted Strain state did not match the current completed-sleep boundary / WHOOP cycle."
    }
    val strainDiagnostics=StrainDiagnostics(
        jamesDayId,strainValue,strainValue?.let(::whoopStrainCategory)?:"UNAVAILABLE",
        transformedStrainCost,alreadyAccounted,latestIncremental,recoveryModifier,
        if(strainValue!=null)"WHOOP primary; steps, workouts and heart rate are supporting context only." else "WHOOP unavailable; local activity sources fill the gap.",
        acceptedTimestamp,acceptedCycleId,currentDayStrainPending,
        previousDayStrain?.data()?.number("value")?.takeIf {it.isFinite()},
        wake.toString(),strainValue!=null,strainValue,invalidatedPersistedStrain,invalidationReason,
        whoopCycleId(previousDayStrain),acceptedSourceStart,acceptedSourceEnd
    )
    fun ownedTimestamp(record:StoredRecord)=runCatching {Instant.parse(record.timestamp)}.getOrNull()?.let {it>=dayWindow.start&&it<=dayWindow.end}==true
    val steps=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Steps"&&ownedTimestamp(it)&&it.data().number("value",Double.NaN)>=0}.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}?.data()?.number("value")
    val stepDrain=((steps?:0.0)/10_000.0*8.0).coerceIn(0.0,8.0)
    val intervals=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Exercise"}.mapNotNull {r->runCatching {val a=Instant.parse(r.data().text("start"));val b=Instant.parse(r.data().text("end"));if(b>a&&b<=dayWindow.end&&b>=dayWindow.start)maxOf(a,dayWindow.start) to b else null}.getOrNull()}.sortedBy {it.first}
    var end=Instant.MIN;var exerciseSeconds=0L
    intervals.forEach {(a,b)->val start=maxOf(a,end);if(b>start)exerciseSeconds+=Duration.between(start,b).seconds;end=maxOf(end,b)}
    val exerciseMinutes=exerciseSeconds/60.0
    val heart=latest("Heart rate")?.takeIf {valid(it,Duration.ofHours(2))&&ownedTimestamp(it)}
    val heartDrain=if(heart!=null&&resting!=null)((heart.data().number("value")-resting.data().number("value")-20.0)/8.0).coerceIn(0.0,8.0)else 0.0
    // Current, reliable WHOOP Strain is the primary accumulated exertion signal.
    // Other activity sources contribute only a small context amount to avoid double charging one workout.
    val activityDrain=if(strainValue!=null)transformedStrainCost+minOf(1.5,stepDrain*.15) else ((steps?:0.0)/1400.0+exerciseMinutes/30.0*3.5+heartDrain).coerceAtMost(32.0)
    val napCandidates=records.filter {
        it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"&&it.data().flag("nap")&&
            it.data().number("value",Double.NaN) in 10.0..240.0&&valid(it)&&
            runCatching {Instant.parse(it.timestamp)>=wake}.getOrDefault(false)
    }
    val directNaps=napCandidates.filter {it.source=="whoop"}
    val napMinutes=(directNaps.ifEmpty {napCandidates})
        .distinctBy {it.externalId?.takeIf(String::isNotBlank)?:it.recordId}
        .sumOf {it.data().number("value")}
    // Roughly one point per ten minutes, deliberately capped: a nap helps but
    // cannot create a new morning or erase the day's accumulated load.
    val recharge=(napMinutes/10.0).toInt().coerceIn(0,12)
    val grossDrain=naturalDrain+activityDrain+workloadDrain
    val start=morning.toInt().coerceIn(1,100)
    val calculatedValue=(morning-grossDrain+recharge).toInt().coerceIn(1,100)
    val previousReserve=priorBodyState?.number("baseScore",priorBodyState.number("score",Double.NaN))?.takeIf {it.isFinite()&&it in 1.0..100.0}?.toInt()
    val previousRecharge=priorBodyState?.number("recharge",0.0)?.toInt()?:0
    val newRestorativeCredit=recharge>previousRecharge
    // Reserve cannot rise merely because a refresh happened or an upstream source
    // reordered. Only a newly completed, explicit restorative credit may raise it.
    val strainOwnershipCorrection=invalidatedPersistedStrain!=null
    val blockedNonRestorativeIncrease=previousReserve!=null&&calculatedValue>previousReserve&&!newRestorativeCredit&&!strainOwnershipCorrection
    val value=if(blockedNonRestorativeIncrease) previousReserve else calculatedValue
    val increaseReason=when {
        value> (previousReserve?:value) && strainOwnershipCorrection -> "Corrected current-day state by removing previous-cycle WHOOP Strain ${String.format(Locale.UK,"%.1f",invalidatedPersistedStrain)}."
        value> (previousReserve?:value) && newRestorativeCredit -> "Completed nap credit: +"+(recharge-previousRecharge)
        blockedNonRestorativeIncrease -> "Blocked non-restorative increase from a later source/state refresh."
        else -> null
    }
    val reasons=mutableListOf<String>()
    if(strainOwnershipCorrection) reasons.add("Targeted migration ignored persisted WHOOP Strain ${String.format(Locale.UK,"%.1f",invalidatedPersistedStrain)} because it was not owned by this completed-sleep boundary/current cycle. Historical records are unchanged.")
    if(blockedNonRestorativeIncrease) reasons.add("A later source/state refresh would have increased Reserve without a completed restorative credit, so James retained the authoritative prior Reserve.")
    reasons.add(when {
        recoveryValue!=null&&sleepCapacity!=null&&samsungValue!=null -> "Morning capacity was $start% from WHOOP Recovery (${recoveryValue.toInt()}%), sleep (${sleepCapacity.toInt()}%) and a small Samsung energy contribution (${samsungValue.toInt()}%)."
        recoveryValue!=null&&sleepCapacity!=null -> "Morning capacity was $start% from WHOOP Recovery (${recoveryValue.toInt()}%) and restorative sleep (${sleepCapacity.toInt()}%). Recovery carries most weight."
        recoveryValue!=null&&samsungValue!=null -> "Morning capacity was $start% from WHOOP Recovery (${recoveryValue.toInt()}%) with a small Samsung energy contribution (${samsungValue.toInt()}%)."
        recoveryValue!=null -> "Morning capacity was $start% from WHOOP Recovery (${recoveryValue.toInt()}% at ${time(recovery!!)}). Recovery is mapped to usable reserve rather than copied as the battery value."
        samsungValue!=null -> "WHOOP Recovery was unavailable, so morning capacity used Samsung energy and available sleep: $start%."
        sleepScore!=null -> "Recovery scores were unavailable, so morning capacity started from WHOOP Sleep Performance: ${sleepScore.toInt()}%."
        else -> "Recovery scores were unavailable, so ${sleepMinutes?.toInt()} minutes of sleep provided a low-confidence starting charge."
    })
    reasons.addAll(baselineReasons)
    reasons.add("Being awake since your main overnight sleep for ${String.format(Locale.UK,"%.1f",awakeHours)} hours used about ${naturalDrain.toInt()} points. James applies a gradual time-based drain even on quiet days.")
    if(strainValue!=null) {
        reasons.add("WHOOP Day Strain ${String.format(Locale.UK,"%.1f",strainValue)}/21 · ${strainDiagnostics.category} has a nonlinear total Reserve impact of -${String.format(Locale.UK,"%.1f",transformedStrainCost)}. James Body Battery Algorithm v${JamesAlgorithmRegistry.BODY_BATTERY_VERSION} keeps 6.7 Strain at about -7, then increases expenditure progressively faster.")
        if(latestIncremental>0.05) reasons.add("Earlier today, ${String.format(Locale.UK,"%.1f",alreadyAccounted)} Strain Reserve points were already accounted for. This refresh adds ${String.format(Locale.UK,"%.1f",latestIncremental)} points; repeated equal refreshes add 0.")
        if(kotlin.math.abs(recoveryModifier)>=.03) reasons.add("Recovery readiness applied a bounded ${if(recoveryModifier>=0) "+" else ""}${(recoveryModifier*100).toInt()}% Strain-load adjustment.")
        reasons.add("Source reconciliation: WHOOP is primary for accumulated exertion; steps, workouts and elevated heart rate are not fully charged again.")
    } else if(currentDayStrainPending) reasons.add("WHOOP Strain is awaiting the current cycle after your completed main sleep. Previous-day Strain${previousDayStrain?.data()?.number("value")?.let {" ${String.format(Locale.UK,"%.1f",it)}/21"}?:""} is ignored for this James Day, so the temporary Strain contribution is 0.")
    else reasons.add("${steps?.toLong()?:0} steps, ${exerciseMinutes.toInt()} exercise minutes and available heart-rate load used about ${activityDrain.toInt()} points.")
    if(workloadDrain>=1)reasons.add("Recorded work, driving, gig work and focused coding added about ${workloadDrain.toInt()} points of load.")
    if(recharge>0)reasons.add("${napMinutes.toInt()} minutes of completed naps restored about $recharge points. Naps recharge the existing day; they never replace your overnight sleep.")
    reasons.add("This is James’s evolving estimate, not WHOOP Recovery, Samsung Energy Score or a medical measurement. Missing inputs are ignored.")
    val calibratedValue=uk.co.james.calibration.JamesCalibrationEngine.applyActiveScore(value,records,"body_battery")
    val trace=BodyBatteryTrace(
        calculatedAt=clock.toString(),jamesDayId=jamesDayId,trigger=trigger,
        previousReserve=previousReserve,currentReserve=value,morningReserve=start,
        awakeMinutes=Duration.between(wake,clock).toMinutes().coerceAtLeast(0),
        awakeTimeCost=naturalDrain,strainCost=transformedStrainCost,
        activityCost=(activityDrain-transformedStrainCost).coerceAtLeast(0.0),
        workloadCost=workloadDrain,restorativeCredit=recharge,
        jamesDayStart=dayWindow.start.toString(),acceptedMainSleepId=dayWindow.acceptedMainSleepId,
        whoopCycleId=acceptedCycleId,contextTimeSeconds=breakdownSeconds,
        previousPersistenceStatus=priorBodyState?.obj("persistence")?.text("status","LEGACY_OR_UNRECORDED")?:"NOT_YET_PERSISTED",
        previousPersistenceReason=priorBodyState?.obj("persistence")?.text("reason").orEmpty(),
        increaseReason=increaseReason,
        baseOutput=value,calibrationEffect=calibratedValue-value,finalOutput=calibratedValue,calibrationSetId=activeCalibration.setId,
        sourceTimestamps=listOfNotNull(
            recovery?.updatedAt?.ifBlank {recovery.timestamp},
            sleep?.updatedAt?.ifBlank {sleep.timestamp},
            strain?.updatedAt?.ifBlank {strain.timestamp}
        )
    )
    val confidence=when {recoveryValue!=null&&strainValue!=null&&sleep!=null->"Good";recoveryValue!=null||samsungValue!=null->"Moderate";else->"Low"}
    val headline=when {calibratedValue>=75->"Well charged";calibratedValue>=45->"Steady";calibratedValue>=20->"Running low";else->"Nearly empty"}
    val summary=when {calibratedValue>=75->"You appear to have plenty left for the day.";calibratedValue>=45->"You have a workable reserve, with normal pacing likely to matter.";calibratedValue>=20->"Your reserve looks limited, so recovery and a gentler pace may help.";else->"Your estimated reserve is very low. Treat this as a prompt to slow down, not a diagnosis."}
    val remainingHours=Duration.between(clock,day.plusDays(1).atStartOfDay(zone).toInstant()).toMinutes().coerceAtLeast(0)/60.0
    val projected=(calibratedValue-remainingHours*1.35).toInt().coerceIn(1,100)
    val forecast=(projected-7).coerceAtLeast(1)..(projected+7).coerceAtMost(100)
    return BodyBattery(calibratedValue,start,(start-calibratedValue).coerceAtLeast(0),headline,summary,confidence,reasons,forecast,recharge,wakeTime=wake,algorithmVersion=JamesAlgorithmRegistry.BODY_BATTERY_VERSION,calibrationVersion=activeCalibration.version,strainDiagnostics=strainDiagnostics,trace=trace,baseValue=value,calibrationSetId=activeCalibration.setId)
}

/** Persisted metadata supports diagnostics and future non-destructive recalculation. */
fun bodyBatteryRecord(battery:BodyBattery,clock:Instant=Instant.now()):JsonObject {
    val d=battery.strainDiagnostics
    val t=battery.trace
    return fields("key" to p("body-battery:${d?.jamesDayId?:clock.atZone(ZoneId.systemDefault()).toLocalDate()}"),
        "value" to fields(
            "score" to (battery.value?.let(::p)?:kotlinx.serialization.json.JsonNull),
            "baseScore" to (battery.baseValue?.let(::p)?:kotlinx.serialization.json.JsonNull),
            "calibrationSetId" to (battery.calibrationSetId?.let(::p)?:kotlinx.serialization.json.JsonNull),
            "morning" to (battery.morning?.let(::p)?:kotlinx.serialization.json.JsonNull),
            "used" to (battery.used?.let(::p)?:kotlinx.serialization.json.JsonNull),
            "recharge" to p(battery.recharge),
            "algorithmVersion" to p(battery.algorithmVersion),"calibrationVersion" to p(battery.calibrationVersion),
            "updatedAt" to p(clock.toString()),
            "strain" to fields(
                "jamesDayId" to p(d?.jamesDayId?:""),"highestRawStrain" to (d?.rawWhoopStrain?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "category" to p(d?.category?:"UNAVAILABLE"),"transformedCost" to p(d?.transformedTotalCost?:0.0),
                "alreadyAccounted" to p(d?.alreadyAccountedCost?:0.0),"latestIncrementalDebit" to p(d?.latestIncrementalDebit?:0.0),
                "recoveryModifier" to p(d?.recoveryModifier?:0.0),"reconciliation" to p(d?.sourceReconciliation?:""),
                "whoopTimestamp" to p(d?.whoopTimestamp?:""),"whoopCycleId" to p(d?.whoopCycleId?:""),
                "sleepBoundary" to p(d?.sleepBoundary?:""),"sourceStart" to p(d?.sourceStart?:""),"sourceEnd" to p(d?.sourceEnd?:""),
                "accepted" to p(d?.accepted?:false),"currentDayPending" to p(d?.currentDayPending?:false),
                "ignoredPreviousDayStrain" to (d?.ignoredPreviousDayStrain?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "invalidatedPersistedStrain" to (d?.invalidatedPersistedStrain?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "invalidationReason" to p(d?.invalidationReason?:""),"previousWhoopCycleId" to p(d?.previousWhoopCycleId?:"")
            ),
            "trace" to fields(
                "calculatedAt" to p(t?.calculatedAt?:clock.toString()),
                "jamesDayId" to p(t?.jamesDayId?:d?.jamesDayId?:""),
                "trigger" to p(t?.trigger?:"state_recalculation"),
                "previousReserve" to (t?.previousReserve?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "currentReserve" to (t?.currentReserve?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "morningReserve" to p(t?.morningReserve?:battery.morning?:0),
                "awakeMinutes" to p(t?.awakeMinutes?:0L),
                "awakeTimeCost" to p(t?.awakeTimeCost?:0.0),
                "strainCost" to p(t?.strainCost?:0.0),
                "activityCost" to p(t?.activityCost?:0.0),
                "workloadCost" to p(t?.workloadCost?:0.0),
                "restorativeCredit" to p(t?.restorativeCredit?:0),
                "baseOutput" to (t?.baseOutput?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "calibrationEffect" to p(t?.calibrationEffect?:0),
                "finalOutput" to (t?.finalOutput?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "calibrationSetId" to (t?.calibrationSetId?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "jamesDayStart" to p(t?.jamesDayStart?:d?.sleepBoundary?:""),
                "acceptedMainSleepId" to p(t?.acceptedMainSleepId?:""),
                "whoopCycleId" to p(t?.whoopCycleId?:d?.whoopCycleId?:""),
                "contextTimeUnit" to p(t?.contextTimeUnit?:"seconds"),
                "contextTimeSeconds" to JsonObject((t?.contextTimeSeconds?:emptyMap()).mapValues {p(it.value)}),
                "previousPersistenceStatus" to p(t?.previousPersistenceStatus?:"NOT_YET_PERSISTED"),
                "previousPersistenceReason" to p(t?.previousPersistenceReason?:""),
                "increaseReason" to p(t?.increaseReason?:""),
                "sourceTimestamps" to kotlinx.serialization.json.JsonArray((t?.sourceTimestamps?:emptyList()).map(::p))
            )
        ))
}
