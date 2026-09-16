package uk.co.james.calibration

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.work.deriveCurrentWorkState
import uk.co.james.state.JamesAlgorithmRegistry

enum class CalibrationParameterType { DOUBLE, INTEGER, PERCENTAGE, MULTIPLIER }
enum class CalibrationParameterSource { DEFAULT, MANUAL, CALIBRATION_ENGINE, NOVA_PROPOSAL, RESTORED }
enum class CalibrationQuality { NOT_ENOUGH_DATA, EARLY, DEVELOPING, GOOD, STRONG }
enum class ErrorDirection { OVERESTIMATED, UNDERESTIMATED, MATCHED }
enum class CandidateCreator { CALIBRATION_ENGINE, NOVA, MANUAL }
enum class CandidateStatus { DRAFT, TESTED, APPROVED, REJECTED, ACTIVE, ROLLED_BACK, STALE }

data class CalibrationParameterDefinition(val id:String,val displayName:String,val description:String,val defaultValue:Double,val minimumAllowed:Double,val maximumAllowed:Double,val type:CalibrationParameterType) {
    fun bounded(value:Double)=value.takeIf {it.isFinite()}?.coerceIn(minimumAllowed,maximumAllowed)?:defaultValue
    fun accepts(value:Double)=value.isFinite()&&value in minimumAllowed..maximumAllowed&&when(type) {
        CalibrationParameterType.INTEGER,CalibrationParameterType.PERCENTAGE -> value==value.toInt().toDouble()
        else -> true
    }
}
data class CalibrationRegistration(val algorithmId:String,val supportsCalibration:Boolean,val feedbackDimension:String,val minimumEvidence:Int,val automaticCandidates:Boolean,val parameters:List<CalibrationParameterDefinition>)
interface AlgorithmRunner { val algorithmId:String;val algorithmVersion:String;fun run(inputSnapshot:JsonObject,calibrationParameters:Map<String,Double>):Pair<Int,JsonObject> }
class StoredPredictionRunner(override val algorithmId:String,override val algorithmVersion:String):AlgorithmRunner {
    override fun run(inputSnapshot:JsonObject,calibrationParameters:Map<String,Double>):Pair<Int,JsonObject> {
        val base=inputSnapshot["prediction"]?.jsonPrimitive?.doubleOrNull?:error("Snapshot has no replayable prediction.")
        val adjustment=(calibrationParameters["outputBias"]?:0.0).takeIf {it.isFinite()}?.coerceIn(-12.0,12.0)?:0.0
        val output=(base+adjustment).roundToInt().coerceIn(0,100)
        return output to fields("replayMethod" to p("STORED_INPUT_SNAPSHOT"),"baseOutput" to p(base),"calibrationEffect" to p(adjustment),"finalOutput" to p(output))
    }
}
data class CalibrationObservation(val id:String,val algorithmId:String,val algorithmVersion:String,val calibrationVersion:String,val timestamp:Instant,val jamesDayId:String?,val prediction:Double,val observed:Double,val error:Double,val absoluteError:Double,val direction:ErrorDirection,val feedback:String,val contextTags:Set<String>,val snapshot:JsonObject,val calibrationSetId:String="",val evidenceConfidence:String="HIGH",val ignored:Boolean=false)
data class CalibrationSlice(val name:String,val count:Int,val meanError:Double,val meanAbsoluteError:Double)
data class CalibrationMetrics(val count:Int,val meanError:Double?,val meanAbsoluteError:Double?,val medianAbsoluteError:Double?,val recencyWeightedMae:Double?,val quality:CalibrationQuality,val scoreRanges:List<CalibrationSlice>,val contexts:List<CalibrationSlice>,val newestAt:Instant?)
data class CandidateCalibration(val id:String,val algorithmId:String,val algorithmVersion:String,val baseCalibrationVersion:String,val parameters:Map<String,Double>,val reason:String,val evidenceIds:List<String>,val createdAt:Instant,val createdBy:CandidateCreator,val status:CandidateStatus,val calibrationSchemaVersion:String="calibration-schema-v1",val engineVersion:String=JamesCalibrationEngine.VERSION,val baseParameters:Map<String,Double> = emptyMap(),val baseCalibrationSetId:String="",val datasetHash:String="",val dependencyVersions:Map<String,String> = emptyMap())
data class BackTestResult(val trainCount:Int,val validationCount:Int,val currentMae:Double?,val candidateMae:Double?,val currentBias:Double?,val candidateBias:Double?,val improvementPercent:Double?,val regressions:List<String>,val robustValidation:Boolean,val currentTrainMae:Double?=null,val candidateTrainMae:Double?=null,val replayMethod:String="STORED_INPUT_SNAPSHOT")
data class ActiveCalibration(val version:String,val setId:String,val parameters:Map<String,Double>,val source:CalibrationParameterSource)

private val outputBias=CalibrationParameterDefinition("outputBias","Output bias","Bounded final correction after the deterministic algorithm. Raw inputs remain immutable.",0.0,-12.0,12.0,CalibrationParameterType.DOUBLE)
object JamesCalibrationCatalog {
    private val registrations=listOf(
        CalibrationRegistration("body_battery",true,"Physical capacity",20,true,listOf(outputBias,CalibrationParameterDefinition("awakeDrainPerHour","Awake drain per hour","Existing deterministic awake-time coefficient, extracted without changing its default behaviour.",1.65,.8,2.4,CalibrationParameterType.DOUBLE))),
        CalibrationRegistration("james_stress",true,"Subjective stress",18,true,listOf(outputBias)),
        CalibrationRegistration("mental_reserve",true,"Mental capacity",20,true,listOf(outputBias)),
        CalibrationRegistration("anxiety_load",true,"Subjective anxiety",18,true,listOf(outputBias)),
        CalibrationRegistration("low_mood_load",true,"Weekly mood",10,false,listOf(outputBias)),
        CalibrationRegistration("sleepiness",true,"Current subjective sleepiness / propensity to fall asleep",20,true,listOf(
            outputBias,
            CalibrationParameterDefinition("lateWakeThresholdHours","Late-wake threshold","Hours awake before the nonlinear late-wake component begins.",14.0,10.0,20.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("lateWakeAcceleration","Late-wake acceleration","Bounded strength of increasing pressure after the late-wake threshold.",6.0,2.0,10.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("shortSleepSensitivity","Short-sleep sensitivity","Bounded pressure per hour below James's recent sleep reference.",6.5,2.0,12.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("recentShortfallWeight","Recent shortfall weight","Bounded multi-day sleep-shortfall contribution.",2.0,0.0,5.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("napReliefPer30Minutes","Nap relief","Temporary expressed-Sleepiness relief per 30 nap minutes.",8.0,0.0,14.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("napReliefDecayHours","Nap relief decay","Hours over which the temporary nap modifier decays.",4.0,1.0,8.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("circadianAmplitude","Circadian amplitude","Conservative bounded local-time component.",4.0,0.0,10.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("circadianPeakLocalHour","Circadian peak","Local hour of the initial estimated highest circadian sleepiness.",4.0,0.0,23.0,CalibrationParameterType.INTEGER),
            CalibrationParameterDefinition("caffeineReliefPer100mg","Caffeine context magnitude","Temporary expressed-Sleepiness modifier per recorded 100 mg; never changes sleep debt.",5.0,0.0,10.0,CalibrationParameterType.DOUBLE),
            CalibrationParameterDefinition("caffeineDecayHours","Caffeine context decay","Conservative modelling decay for the temporary modifier; not a pharmacokinetic claim.",5.0,2.0,10.0,CalibrationParameterType.DOUBLE)
        )),
        CalibrationRegistration("live_energy",true,"Current energy",16,true,listOf(outputBias)),
        CalibrationRegistration("energy_sustainability",true,"Energy sustainability",20,false,listOf(outputBias)),
        CalibrationRegistration("crash_risk",true,"Perceived crash risk",20,false,listOf(outputBias)),
        CalibrationRegistration("time_pressure",true,"Time for myself",16,true,listOf(outputBias)),
        CalibrationRegistration("context_load",true,"Mental demand",16,true,listOf(outputBias)),
        CalibrationRegistration("context_recovery",false,"Context recovery",20,false,emptyList()),
        CalibrationRegistration("life_balance",false,"Historical Life Balance v1",10,false,listOf(outputBias)),
        CalibrationRegistration("life_balance_v2",true,"Weekly autonomy / alignment",10,false,listOf(outputBias)),
        CalibrationRegistration("autonomy_trend",false,"Autonomy",12,false,emptyList()),
        CalibrationRegistration("rut",false,"Legacy Rut",Int.MAX_VALUE,false,emptyList())
    )
    fun all()=registrations
    fun get(id:String)=registrations.firstOrNull {it.algorithmId==id}
}

object JamesCalibrationEngine {
    const val VERSION="1.0.3"
    private val scoreBands=listOf(0..19,20..49,50..79,80..100)
    private fun ordinal(label:String)=when(label.trim().uppercase()) {
        "EMPTY","GONE","NONE","NOT AT ALL","VERY LOW","KNACKERED","BRAIN GONE","NO ENERGY","DEFINITELY NOT"->0.0
        "LOW","TIRED","MENTALLY TIRED","A LITTLE","MOSTLY NOT"->25.0
        "OKAY","MODERATE","SOMEWHAT","MIXED","ABOUT RIGHT","ABOUT USUAL"->50.0
        "GOOD","HIGH","A LOT","MOSTLY YES","ENERGETIC"->75.0
        "STRONG","VERY HIGH","EXTREME","EXTREMELY","VERY GOOD","GREAT","BUZZING","MORE ENERGY THAN THIS","MORE CAPACITY THAN THIS","DEFINITELY YES"->100.0
        else->null
    }
    fun normalizeObserved(algorithmId:String,label:String,prediction:Double?=null):Double? {
        val upper=label.trim().uppercase()
        val comparison=when(upper) {
            "MUCH LOWER THAN I FEEL","TOO LOW","MUCH TOO LOW","PREDICTION TOO LOW"->prediction?.plus(25)
            "SLIGHTLY LOWER","A LITTLE TOO LOW"->prediction?.plus(12)
            "SLIGHTLY HIGHER","A LITTLE TOO HIGH"->prediction?.minus(12)
            "MUCH HIGHER THAN I FEEL","TOO HIGH","MUCH TOO HIGH","PREDICTION TOO HIGH"->prediction?.minus(25)
            "ABOUT RIGHT"->prediction
            else->null
        }
        val sleepinessOrdinal=if(algorithmId=="sleepiness")when(upper) {"NONE"->0.0;"SLIGHTLY SLEEPY"->25.0;"SLEEPY"->50.0;"VERY SLEEPY"->75.0;"STRUGGLING TO STAY AWAKE"->95.0;else->null}else null
        val lowMoodDirect=if(algorithmId=="low_mood_load")when(upper) {"NOT LOW OR FLAT"->0.0;"A LITTLE LOW OR FLAT"->25.0;"NOTICEABLY LOW OR FLAT"->50.0;"VERY LOW OR FLAT"->75.0;"EXTREMELY LOW OR FLAT"->100.0;else->null}else null
        val balanceDirect=if(algorithmId=="life_balance_v2")when(upper) {"HARDLY ANY OF MY TIME FELT LIKE MINE"->0.0;"A LITTLE OF MY TIME FELT LIKE MINE"->25.0;"MIXED"->50.0;"MOST OF MY TIME FELT LIKE MINE"->75.0;"MY TIME LARGELY FELT LIKE MINE"->100.0;else->null}else null
        val raw=comparison?:sleepinessOrdinal?:lowMoodDirect?:balanceDirect?:ordinal(upper)?:return null
        val reversed=algorithmId=="low_mood_load"&&upper in setOf("VERY LOW","LOW","OKAY","GOOD","VERY GOOD","GREAT")
        return (sleepinessOrdinal?:lowMoodDirect?:balanceDirect?:if(reversed)100.0-raw else raw).coerceIn(0.0,100.0)
    }
    fun event(algorithmId:String,prediction:Double,feedback:String,algorithmVersion:String,calibrationVersion:String,jamesDayId:String?,snapshot:JsonObject,note:String="",evidenceSource:String="DIRECT",sourceEventId:String?=null,timestamp:Instant=Instant.now(),calibrationSetId:String="",evidenceConfidence:String=if(evidenceSource=="DIRECT")"HIGH" else "MODERATE",recordId:String?=null):JsonObject {
        require(JamesCalibrationCatalog.get(algorithmId)?.supportsCalibration==true){"This algorithm does not support calibration."}
        val observed=normalizeObserved(algorithmId,feedback,prediction)?:error("Unsupported calibration response.")
        val error=prediction-observed
        val direction=when {error>2->ErrorDirection.OVERESTIMATED;error < -2->ErrorDirection.UNDERESTIMATED;else->ErrorDirection.MATCHED}
        require(timestamp<=Instant.now().plus(Duration.ofMinutes(5))){"Calibration feedback cannot be in the future."}
        return personal("CalibrationEvent",fields("algorithmId" to p(algorithmId),"algorithmVersion" to p(algorithmVersion),"calibrationVersion" to p(calibrationVersion),"calibrationSetId" to p(calibrationSetId),"prediction" to p(prediction),"observed" to p(observed),"error" to p(error),"absoluteError" to p(abs(error)),"direction" to p(direction.name),"feedback" to p(feedback),"note" to p(note.take(240)),"evidenceSource" to p(evidenceSource),"evidenceConfidence" to p(evidenceConfidence),"sourceEventId" to (sourceEventId?.let(::p)?:JsonNull),"jamesDayId" to (jamesDayId?.let(::p)?:JsonNull),"inputSnapshotSchemaVersion" to p("calibration-input-v2"),"calibrationEngineVersion" to p(VERSION),"snapshot" to snapshot,"ignored" to p(false),"immutableRawSources" to p(true)),recordId=recordId?:id(),source="manual_calibration",timestamp=timestamp.toString())
    }
    fun parse(row:StoredRecord,clock:Instant=Instant.now(),includeIgnored:Boolean=false):CalibrationObservation? {
        if(row.kind!="CalibrationEvent"||(!includeIgnored&&row.data().flag("ignored")))return null
        val d=row.data();val algorithm=d.text("algorithmId");if(algorithm.isBlank())return null
        val prediction=d["prediction"]?.jsonPrimitive?.doubleOrNull?:return null
        val observed=d["observed"]?.jsonPrimitive?.doubleOrNull?:return null
        val stamp=runCatching {Instant.parse(row.timestamp)}.getOrNull()?:return null
        if(stamp>clock.plus(Duration.ofMinutes(5)))return null
        val error=prediction-observed;val snapshot=d.obj("snapshot")
        val tags=buildSet {
            snapshot.text("contextType").takeIf {it.isNotBlank()&&it!="UNKNOWN"}?.let(::add)
            snapshot.text("activity").takeIf {it.isNotBlank()}?.let(::add)
            snapshot["sleepMinutes"]?.jsonPrimitive?.doubleOrNull?.let {if(it<300)add("SHORT_SLEEP")}
            snapshot["timeAwakeMinutes"]?.jsonPrimitive?.longOrNull?.let {if(it>720)add("EXTENDED_WAKE")}
            snapshot["sleepinessNapModifier"]?.jsonPrimitive?.doubleOrNull?.let {if(it<0)add("RECENT_NAP")}
            snapshot["recovery"]?.jsonPrimitive?.doubleOrNull?.let {if(it<34)add("LOW_RECOVERY")else if(it>=67)add("HIGH_RECOVERY")}
            if(snapshot.text("recentMealAt").isNotBlank())add("RECENT_MEAL")
            if(snapshot.text("latestHydrationAt").isNotBlank())add("HYDRATION")
            if(snapshot["caffeineTodayMg"]?.jsonPrimitive?.doubleOrNull!=null)add("CAFFEINE")
        }
        return CalibrationObservation(row.recordId,algorithm,d.text("algorithmVersion"),d.text("calibrationVersion"),stamp,d.text("jamesDayId").ifBlank {null},prediction,observed,error,abs(error),when {error>2->ErrorDirection.OVERESTIMATED;error < -2->ErrorDirection.UNDERESTIMATED;else->ErrorDirection.MATCHED},d.text("feedback"),tags,snapshot,d.text("calibrationSetId"),d.text("evidenceConfidence","MODERATE"),d.flag("ignored"))
    }
    private fun slice(name:String,items:List<CalibrationObservation>)=CalibrationSlice(name,items.size,items.map {it.error}.average(),items.map {it.absoluteError}.average())
    fun evaluate(events:List<CalibrationObservation>,clock:Instant=Instant.now()):CalibrationMetrics {
        if(events.isEmpty())return CalibrationMetrics(0,null,null,null,null,CalibrationQuality.NOT_ENOUGH_DATA,emptyList(),emptyList(),null)
        val errors=events.map {it.absoluteError}.sorted()
        val median=if(errors.size%2==1)errors[errors.size/2]else(errors[errors.size/2-1]+errors[errors.size/2])/2
        val ranges=scoreBands.mapNotNull {band->events.filter {it.prediction.roundToInt() in band}.takeIf {it.isNotEmpty()}?.let {slice(band.first.toString()+"-"+band.last,it)}}
        val contexts=events.flatMap {e->e.contextTags.map {it to e}}.groupBy({it.first},{it.second}).map {(name,items)->slice(name,items)}.sortedByDescending {it.count}
        val recency=Duration.between(events.maxOf {it.timestamp},clock).toDays()
        val diversity=events.flatMap {it.contextTags}.distinct().size;val coverage=ranges.count {it.count>=2}
        val quality=when {events.size<6->CalibrationQuality.NOT_ENOUGH_DATA;events.size<15->CalibrationQuality.EARLY;events.size<35||coverage<3||diversity<2->CalibrationQuality.DEVELOPING;events.size<70||recency>45||diversity<4->CalibrationQuality.GOOD;else->CalibrationQuality.STRONG}
        val weighted=events.map {event->val age=Duration.between(event.timestamp,clock).toDays().coerceAtLeast(0);val w=(1.0/(1.0+age/180.0)).coerceIn(.35,1.0);event.absoluteError to w}
        val weightedMae=weighted.sumOf {it.first*it.second}/weighted.sumOf {it.second}
        return CalibrationMetrics(events.size,events.map {it.error}.average(),errors.average(),median,weightedMae,quality,ranges,contexts,events.maxOf {it.timestamp})
    }
    fun datasetIdentity(events:List<CalibrationObservation>):String {
        val payload=events.filter {!it.ignored}.sortedWith(compareBy({it.timestamp},{it.id})).joinToString("\n") { event->
            listOf(event.id,event.algorithmId,event.algorithmVersion,event.calibrationVersion,event.calibrationSetId,event.timestamp.toString(),event.prediction.toString(),event.observed.toString(),event.evidenceConfidence,event.contextTags.sorted().joinToString(","),canonical(event.snapshot)).joinToString("|")
        }
        return sha(payload.toByteArray())
    }
    fun dependencyVersions(rows:List<StoredRecord>,algorithmId:String):Map<String,String> = JamesAlgorithmRegistry.dependencies[algorithmId].orEmpty().sorted().associateWith {dependency->
        val entry=JamesAlgorithmRegistry.get(dependency)
        if(entry==null)"UNAVAILABLE" else activeCalibration(rows,dependency,entry.calibrationVersion,entry.algorithmVersion).let {it.version+"@"+it.setId}
    }
    fun candidate(events:List<CalibrationObservation>,algorithmId:String,baseVersion:String,baseParameters:Map<String,Double> = emptyMap(),clock:Instant=Instant.now(),baseCalibrationSetId:String="",dependencyVersions:Map<String,String> = emptyMap()):CandidateCalibration? {
        val registration=JamesCalibrationCatalog.get(algorithmId)?:return null
        val usable=events.filter {!it.ignored&&it.algorithmId==algorithmId}
        val coveredBands=scoreBands.count {band->usable.count {it.prediction.roundToInt() in band}>=3}
        if(!registration.automaticCandidates||usable.size<registration.minimumEvidence||coveredBands<2)return null
        val ordered=usable.sortedBy {it.timestamp};val train=ordered.filterIndexed {i,_->i%5!=4};val bias=train.map {it.error}.average()
        val currentBias=baseParameters["outputBias"]?:0.0
        val proposed=registration.parameters.first {it.id=="outputBias"}.bounded(currentBias-bias.coerceIn(-4.0,4.0))
        val evidence=usable.map {it.id }.sorted();val datasetHash=datasetIdentity(usable)
        val identity=sha((algorithmId+"|"+baseVersion+"|"+baseCalibrationSetId+"|"+datasetHash+"|"+VERSION+"|"+dependencyVersions.toSortedMap()+"|"+proposed).toByteArray()).take(24)
        return CandidateCalibration("candidate:$identity",algorithmId,JamesAlgorithmRegistry.get(algorithmId)?.algorithmVersion?:"",baseVersion,mapOf("outputBias" to proposed),"Bounded correction for repeated "+(if(bias>0)"overestimation"else"underestimation")+" across "+train.size+" training observations; unsupported parameters are unchanged.",evidence,clock,CandidateCreator.CALIBRATION_ENGINE,CandidateStatus.DRAFT,baseParameters=baseParameters,baseCalibrationSetId=baseCalibrationSetId,datasetHash=datasetHash,dependencyVersions=dependencyVersions)
    }
    fun backTest(events:List<CalibrationObservation>,candidate:CandidateCalibration):BackTestResult {
        val ordered=events.sortedBy {it.timestamp};val validation=ordered.filterIndexed {i,_->i%5==4};val train=ordered.filterNot {it in validation};val bias=(candidate.parameters["outputBias"]?:0.0)-(candidate.baseParameters["outputBias"]?:0.0)
        fun mae(rows:List<CalibrationObservation>,offset:Double)=rows.takeIf {it.isNotEmpty()}?.map {abs((it.prediction+offset).coerceIn(0.0,100.0)-it.observed)}?.average()
        fun signed(rows:List<CalibrationObservation>,offset:Double)=rows.takeIf {it.isNotEmpty()}?.map {(it.prediction+offset).coerceIn(0.0,100.0)-it.observed}?.average()
        val current=mae(validation,0.0);val proposed=mae(validation,bias)
        val rangeRegressions=scoreBands.mapNotNull {band->val rows=validation.filter {it.prediction.roundToInt() in band};if(rows.size>=3&&(mae(rows,bias)?:0.0)>(mae(rows,0.0)?:0.0)+2.0)band.first.toString()+"-"+band.last+" worsens by more than 2 points"else null}
        val contextRegressions=validation.flatMap {it.contextTags}.distinct().mapNotNull {tag->val rows=validation.filter {tag in it.contextTags};if(rows.size>=3&&(mae(rows,bias)?:0.0)>(mae(rows,0.0)?:0.0)+2.0)"$tag context worsens by more than 2 points" else null}
        return BackTestResult(train.size,validation.size,current,proposed,signed(validation,0.0),signed(validation,bias),if(current!=null&&proposed!=null&&current>0)(current-proposed)/current*100 else null,rangeRegressions+contextRegressions,events.size>=25&&validation.size>=5,mae(train,0.0),mae(train,bias))
    }
    fun backtestRecord(candidate:CandidateCalibration,result:BackTestResult)=personal("CalibrationBacktest",fields(
        "candidateId" to p(candidate.id),"algorithmId" to p(candidate.algorithmId),"algorithmVersion" to p(candidate.algorithmVersion),
        "baseCalibrationVersion" to p(candidate.baseCalibrationVersion),"baseCalibrationSetId" to p(candidate.baseCalibrationSetId),
        "calibrationEngineVersion" to p(VERSION),"datasetHash" to p(candidate.datasetHash),
        "dependencyVersions" to JsonObject(candidate.dependencyVersions.toSortedMap().mapValues {p(it.value)}),
        "evidenceIds" to JsonArray(candidate.evidenceIds.map(::p)),"trainCount" to p(result.trainCount),"validationCount" to p(result.validationCount),
        "currentTrainMae" to (result.currentTrainMae?.let(::p)?:JsonNull),"candidateTrainMae" to (result.candidateTrainMae?.let(::p)?:JsonNull),
        "currentMae" to (result.currentMae?.let(::p)?:JsonNull),"candidateMae" to (result.candidateMae?.let(::p)?:JsonNull),
        "currentBias" to (result.currentBias?.let(::p)?:JsonNull),"candidateBias" to (result.candidateBias?.let(::p)?:JsonNull),
        "improvementPercent" to (result.improvementPercent?.let(::p)?:JsonNull),"regressions" to JsonArray(result.regressions.map(::p)),
        "robustValidation" to p(result.robustValidation),"replayMethod" to p(result.replayMethod)
    ),recordId="backtest:"+candidate.id,source="calibration_engine")
    fun candidateRecord(candidate:CandidateCalibration,status:CandidateStatus=candidate.status)=personal("CalibrationCandidate",fields("candidateId" to p(candidate.id),"algorithmId" to p(candidate.algorithmId),"algorithmVersion" to p(candidate.algorithmVersion),"calibrationSchemaVersion" to p(candidate.calibrationSchemaVersion),"calibrationEngineVersion" to p(candidate.engineVersion),"baseCalibrationVersion" to p(candidate.baseCalibrationVersion),"baseCalibrationSetId" to p(candidate.baseCalibrationSetId),"datasetHash" to p(candidate.datasetHash),"dependencyVersions" to JsonObject(candidate.dependencyVersions.toSortedMap().mapValues {p(it.value)}),"baseParameters" to JsonObject(candidate.baseParameters.mapValues {p(it.value)}),"parameters" to JsonObject(candidate.parameters.mapValues {p(it.value)}),"reason" to p(candidate.reason),"evidenceIds" to JsonArray(candidate.evidenceIds.map(::p)),"createdBy" to p(candidate.createdBy.name),"status" to p(status.name)),recordId=candidate.id,source="calibration_engine",timestamp=candidate.createdAt.toString())
    fun parseCandidate(row:StoredRecord):CandidateCalibration? {
        if(row.kind!="CalibrationCandidate")return null
        val d=row.data();val stamp=runCatching {Instant.parse(row.timestamp)}.getOrNull()?:return null
        val parameters=mutableMapOf<String,Double>()
        for((key,element) in d.obj("parameters")){val value=element.jsonPrimitive.doubleOrNull?:return null;parameters[key]=value}
        if("outputBias" !in parameters)return null
        val registration=JamesCalibrationCatalog.get(d.text("algorithmId"))?:return null
        val schema=d.text("calibrationSchemaVersion","calibration-schema-v1")
        val engine=d.text("calibrationEngineVersion",VERSION)
        val entry=JamesAlgorithmRegistry.get(d.text("algorithmId"))?:return null
        if(schema!=entry.calibrationSchemaVersion||d.text("algorithmVersion") !in entry.calibrationCompatibleAlgorithmVersions)return null
        if(parameters.any {(key,value)->registration.parameters.none {it.id==key}||!registration.parameters.first {it.id==key}.accepts(value)})return null
        val baseParameters=d.obj("baseParameters").mapValues {it.value.jsonPrimitive.doubleOrNull?:return null}
        val dependencies=d.obj("dependencyVersions").mapValues {it.value.jsonPrimitive.contentOrNull?:return null}
        return CandidateCalibration(row.recordId,d.text("algorithmId"),d.text("algorithmVersion"),d.text("baseCalibrationVersion"),parameters,d.text("reason"),d.array("evidenceIds").mapNotNull {it.jsonPrimitive.contentOrNull},stamp,runCatching {CandidateCreator.valueOf(d.text("createdBy"))}.getOrElse {CandidateCreator.MANUAL},runCatching {CandidateStatus.valueOf(d.text("status"))}.getOrElse {CandidateStatus.DRAFT},schema,engine,baseParameters,d.text("baseCalibrationSetId"),d.text("datasetHash"),dependencies)
    }
    fun candidateStalenessReason(candidate:CandidateCalibration,events:List<CalibrationObservation>,active:ActiveCalibration,dependencies:Map<String,String>):String? = when {
        candidate.engineVersion!=VERSION -> "Calibration Engine version changed."
        candidate.baseCalibrationVersion!=active.version || candidate.baseCalibrationSetId!=active.setId -> "The active base calibration changed."
        candidate.datasetHash.isBlank() || candidate.datasetHash!=datasetIdentity(events.filter {it.algorithmId==candidate.algorithmId}) -> "Calibration evidence changed."
        candidate.evidenceIds.sorted()!=events.filter {!it.ignored&&it.algorithmId==candidate.algorithmId}.map {it.id}.sorted() -> "Calibration evidence set changed."
        candidate.dependencyVersions!=dependencies -> "A dependency calibration changed."
        else -> null
    }
    fun activeProfileRecord(candidate:CandidateCalibration,newVersion:String,reason:String):JsonObject {
        val registration=JamesCalibrationCatalog.get(candidate.algorithmId)?:error("Unknown calibration schema.")
        val complete=registration.parameters.associate {it.id to (candidate.parameters[it.id]?:candidate.baseParameters[it.id]?:it.defaultValue)}
        require(complete.all {(key,value)->registration.parameters.first {it.id==key}.accepts(value)}){"Calibration parameters failed bounds/type validation."}
        val parameters=JsonObject(complete.toSortedMap().mapValues {p(it.value)})
        val setId=sha(canonical(parameters).toByteArray())
        return personal("CalibrationProfile",fields("algorithmId" to p(candidate.algorithmId),"algorithmVersion" to p(candidate.algorithmVersion),"calibrationSchemaVersion" to p(candidate.calibrationSchemaVersion),"calibrationEngineVersion" to p(candidate.engineVersion),"calibrationVersion" to p(newVersion),"calibrationSetId" to p(setId),"parameters" to parameters,"baseCalibrationVersion" to p(candidate.baseCalibrationVersion),"candidateId" to p(candidate.id),"activatedAt" to p(now()),"changeReason" to p(reason),"createdBy" to p(candidate.createdBy.name),"source" to p(CalibrationParameterSource.CALIBRATION_ENGINE.name),"active" to p(true)),recordId="calibration:"+candidate.algorithmId+":"+newVersion,source="calibration_engine")
    }
    fun activeParameters(rows:List<StoredRecord>,algorithmId:String):Map<String,Double> = rows.filter {it.kind=="CalibrationProfile"&&it.data().text("algorithmId")==algorithmId&&it.data().flag("active")}.maxByOrNull {it.data().text("activatedAt",it.timestamp)}?.data()?.obj("parameters")?.mapValues {it.value.jsonPrimitive.doubleOrNull?:0.0}.orEmpty()
    fun activeCalibration(rows:List<StoredRecord>,algorithmId:String,defaultVersion:String,algorithmVersion:String):ActiveCalibration {
        val compatible=JamesAlgorithmRegistry.get(algorithmId)?.calibrationCompatibleAlgorithmVersions?:setOf(algorithmVersion)
        val row=rows.filter {it.kind=="CalibrationProfile"&&it.data().text("algorithmId")==algorithmId&&it.data().flag("active")&&it.data().text("algorithmVersion") in compatible}.maxByOrNull {it.data().text("activatedAt",it.timestamp)}
        if(row==null)return ActiveCalibration(defaultVersion,"default:"+algorithmId+":"+algorithmVersion,emptyMap(),CalibrationParameterSource.DEFAULT)
        val rawParameters=row.data().obj("parameters")
        val parsed=rawParameters.mapValues {it.value.jsonPrimitive.doubleOrNull?:Double.NaN}
        val registration=JamesCalibrationCatalog.get(algorithmId);val entry=JamesAlgorithmRegistry.get(algorithmId)
        val expected=registration?.parameters?.map {it.id}?.toSet().orEmpty()
        val setId=sha(canonical(rawParameters).toByteArray())
        // v1 profiles released before the audit stored only changed parameters.
        // Accept that explicit legacy shape, verify its hash, and fill unchanged
        // defaults in memory. New profiles are always complete and immutable.
        val valid=registration!=null&&entry!=null&&row.data().text("calibrationSchemaVersion")==entry.calibrationSchemaVersion&&"outputBias" in parsed&&parsed.keys.all {it in expected}&&parsed.all {(key,value)->registration.parameters.first {it.id==key}.accepts(value)}&&row.data().text("calibrationSetId")==setId
        val complete=registration?.parameters?.associate {it.id to (parsed[it.id]?:it.defaultValue)}.orEmpty()
        return if(valid)ActiveCalibration(row.data().text("calibrationVersion",defaultVersion),row.data().text("calibrationSetId",row.recordId),complete,runCatching {CalibrationParameterSource.valueOf(row.data().text("source"))}.getOrElse {CalibrationParameterSource.RESTORED})else ActiveCalibration(defaultVersion,"default:"+algorithmId+":"+algorithmVersion,registration?.parameters?.associate {it.id to it.defaultValue}.orEmpty(),CalibrationParameterSource.DEFAULT)
    }
    fun applyActiveScore(base:Int,rows:List<StoredRecord>,algorithmId:String):Int {
        val algorithm=JamesAlgorithmRegistry.get(algorithmId)?:return base.coerceIn(0,100)
        val active=activeCalibration(rows,algorithmId,algorithm.calibrationVersion,algorithm.algorithmVersion)
        return (base+(active.parameters["outputBias"]?:0.0)).roundToInt().coerceIn(0,100)
    }
    fun applyActiveValue(base:Double,rows:List<StoredRecord>,algorithmId:String):Double {
        if(!base.isFinite())return base
        val algorithm=JamesAlgorithmRegistry.get(algorithmId)?:return base.coerceIn(0.0,100.0)
        val active=activeCalibration(rows,algorithmId,algorithm.calibrationVersion,algorithm.algorithmVersion)
        return (base+(active.parameters["outputBias"]?:0.0)).coerceIn(0.0,100.0)
    }
}

fun calibrationSnapshot(records:List<StoredRecord>,prediction:Int,confidence:String,jamesDayId:String?,clock:Instant=Instant.now()):JsonObject {
    fun atOrBefore(row:StoredRecord)=runCatching {Instant.parse(row.timestamp)<=clock.plus(Duration.ofMinutes(5))}.getOrDefault(false)
    fun latestMetric(name:String)=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")==name&&atOrBefore(it)}.maxByOrNull {it.timestamp}
    fun metric(name:String)=latestMetric(name)?.data()?.get("value")?:JsonNull
    val day=uk.co.james.time.jamesDayWindow(records,clock)
    fun inDay(row:StoredRecord)=runCatching {Instant.parse(row.timestamp) in day.start..clock.plus(Duration.ofMinutes(5))}.getOrDefault(false)
    val nutritionRows=records.filter {it.kind in setOf("Nutrition","NutritionEvent")&&inDay(it)}
    val hydrationRows=records.filter {it.kind in setOf("Hydration","HydrationEvent")&&inDay(it)}
    val nutrition=nutritionRows.maxByOrNull {it.timestamp};val hydration=hydrationRows.maxByOrNull {it.timestamp}
    val caffeineRows=nutritionRows.filter {it.data()["caffeineMg"]?.jsonPrimitive?.doubleOrNull?.isFinite()==true }
    val work=deriveCurrentWorkState(records,clock)
    val current=uk.co.james.state.currentContext(records,clock);val activity=records.filter {it.kind in setOf("Activity","Exercise","PlaceVisit","TimeBlock")&&atOrBefore(it)}.maxByOrNull {it.timestamp}
    val battery=uk.co.james.state.bodyBattery(records,clock)
    val wellbeing=uk.co.james.state.mentalWellbeing(records,clock=clock)
    val rightNow=uk.co.james.state.rightNowSummary(records,clock=clock)
    val life=uk.co.james.state.lifeBalance(records,clock)
    val lifeV2=uk.co.james.state.lifeBalanceV2(records,clock).current
    val sleepMinutes=latestMetric("Sleep")?.data()?.number("value",Double.NaN)?.takeIf(Double::isFinite)
    val jamesStressRaw=latestMetric("James Stress")?.data()?.number("value",Double.NaN)?.takeIf(Double::isFinite)
    val jamesStress=jamesStressRaw?.let {JamesCalibrationEngine.applyActiveValue(it,records,"james_stress")}
    val provenance=JsonObject(listOf("Heart rate","HRV","Resting heart rate","EDA","Recovery","Sleep","Strain").mapNotNull {name->latestMetric(name)?.let {row->name to fields("source" to p(row.source),"provider" to p(row.data().text("provider")),"measurementContext" to p(row.data().text("measurementContext")),"observedAt" to p(row.timestamp))}}.toMap())
    return fields(
        "prediction" to p(prediction),"confidence" to p(confidence),"jamesDayId" to p(jamesDayId?:day.id),"capturedAt" to p(clock.toString()),"timeOfDay" to p(clock.atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString()),
        "heartRate" to metric("Heart rate"),"hrv" to metric("HRV"),"restingHeartRate" to metric("Resting heart rate"),"eda" to metric("EDA"),"jamesStress" to (jamesStress?.let(::p)?:JsonNull),"jamesStressRawSource" to (jamesStressRaw?.let(::p)?:JsonNull),"recovery" to metric("Recovery"),"strain" to metric("Strain"),"sleepMinutes" to (sleepMinutes?.let(::p)?:JsonNull),"sleepDebtMinutes" to (sleepMinutes?.let {(420.0-it).coerceAtLeast(0.0)}?.let(::p)?:JsonNull),
        "timeAwakeMinutes" to p(rightNow.awakeMinutes),"wakeAt" to p(rightNow.sleepinessDetails.wakeAt),"bodyBattery" to (battery.value?.let(::p)?:JsonNull),"mentalReserve" to p(wellbeing.reserve.score),"anxietyLoad" to p(wellbeing.anxiety.score),"lowMoodLoad" to p(wellbeing.lowMood.score),"liveEnergy" to p(rightNow.liveEnergy.score),"sleepiness" to p(rightNow.sleepiness.score),"underlyingSleepPressure" to p(rightNow.sleepinessDetails.underlyingPressure),"sleepinessMainSleepMinutes" to (rightNow.sleepinessDetails.mainSleepMinutes?.let(::p)?:JsonNull),"sleepinessBaselineMinutes" to (rightNow.sleepinessDetails.baselineSleepMinutes?.let(::p)?:JsonNull),"sleepinessRecentShortfallContribution" to p(rightNow.sleepinessDetails.recentShortfallContribution),"sleepinessRestorativeContribution" to p(rightNow.sleepinessDetails.restorativeSleepContribution),"sleepinessNapModifier" to p(rightNow.sleepinessDetails.napModifier),"sleepinessCircadianContribution" to p(rightNow.sleepinessDetails.circadianContribution),"sleepinessCaffeineModifier" to p(rightNow.sleepinessDetails.caffeineModifier),"sleepinessSourceFreshness" to p(rightNow.sleepinessDetails.sourceFreshness),"sleepinessMainSleepSource" to (rightNow.sleepinessDetails.mainSleepSource?.let(::p)?:JsonNull),"timePressure" to p(rightNow.timePressure.score),"personalTimeMinutes" to p(rightNow.personalMinutes),"lifeBalance" to (life.current.score?.let(::p)?:JsonNull),"lifeBalanceV2" to (lifeV2.score?.let(::p)?:JsonNull),"lifeBalanceV2Coverage" to p(lifeV2.coveragePercent),"lifeBalanceV2WindowDays" to p(lifeV2.days),"lifeBalanceV2AutonomousMinutes" to p(lifeV2.autonomousMinutes),"lifeBalanceV2ConstrainedMinutes" to p(lifeV2.constrainedMinutes),"lifeBalanceV2CommittedMinutes" to p(lifeV2.committedMinutes),"lifeBalanceV2WorkMinutes" to p(lifeV2.workMinutes),"lifeBalanceV2Interruptions" to p(lifeV2.interruptions),
        "calculationTrace" to (battery.trace?.let {fields("algorithmVersion" to p(battery.algorithmVersion),"calibrationVersion" to p(battery.calibrationVersion),"calibrationSetId" to (battery.calibrationSetId?.let(::p)?:JsonNull),"calculatedAt" to p(it.calculatedAt),"baseOutput" to (it.baseOutput?.let(::p)?:JsonNull),"calibrationEffect" to p(it.calibrationEffect),"finalOutput" to (it.finalOutput?.let(::p)?:JsonNull))}?:fields()),"sourceProvenance" to provenance,
        "contextType" to p(current.active?.visitType?.name?:"UNKNOWN"),"contextLoad" to p(current.score),"contextPeriodId" to (current.active?.id?.let(::p)?:JsonNull),"difficultActive" to p(current.difficultActive),"activity" to p(activity?.let {it.data().text("activity",it.data().text("title",it.kind))}?:""),
        "recentMealAt" to (nutrition?.timestamp?.let(::p)?:JsonNull),"recentMealEnergyKcal" to (nutrition?.data()?.get("energyKcal")?:JsonNull),"recentMealCarbsGrams" to (nutrition?.data()?.get("carbohydrateGrams")?:nutrition?.data()?.get("carbsGrams")?:JsonNull),"recentMealProteinGrams" to (nutrition?.data()?.get("proteinGrams")?:JsonNull),"recentMealFatGrams" to (nutrition?.data()?.get("fatGrams")?:JsonNull),"nutritionSource" to (nutrition?.data()?.text("sourceDisplay").takeIf {!it.isNullOrBlank()}?.let(::p)?:JsonNull),
        "hydrationTodayMl" to hydrationRows.mapNotNull {it.data()["volumeMl"]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)}.takeIf {it.isNotEmpty()}?.sum()?.let(::p).orJsonNull(),"latestHydrationAt" to (hydration?.timestamp?.let(::p)?:JsonNull),"hydrationSource" to (hydration?.data()?.text("sourceDisplay").takeIf {!it.isNullOrBlank()}?.let(::p)?:JsonNull),
        "workActive" to p(work.mode !in setOf(uk.co.james.work.WorkMode.OFF_WORK,uk.co.james.work.WorkMode.UNKNOWN)),"workState" to p(work.mode.name),"workShiftId" to (work.externalShiftId?.let(::p)?:JsonNull),"workSince" to (work.since?.let {p(it.toString())}?:JsonNull),"workDeliveryActive" to p(work.mode==uk.co.james.work.WorkMode.DELIVERY),"workBreakActive" to p(work.mode==uk.co.james.work.WorkMode.BREAK),"workDurationMinutes" to (work.session?.workingMinutes?.let(::p)?:JsonNull),"caffeineTodayMg" to caffeineRows.mapNotNull {it.data()["caffeineMg"]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)}.takeIf {it.isNotEmpty()}?.sum()?.let(::p).orJsonNull(),"latestCaffeineAt" to (caffeineRows.maxByOrNull {it.timestamp}?.timestamp?.let(::p)?:JsonNull),"replayMethod" to p("STORED_INPUT_SNAPSHOT")
    )
}

private fun JsonElement?.orJsonNull():JsonElement=this?:JsonNull
