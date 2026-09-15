package uk.co.james.whoop

import kotlinx.serialization.json.*
import uk.co.james.core.*
import java.time.*

/** Keep original WHOOP payloads; score only SCORED records and never substitute zero for missing values. */
object WhoopMapper {
 const val SLEEP_DETAIL_MAPPING_VERSION="1"
 private fun JsonObject.finiteNumber(key:String):Double?=(this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
 private fun optional(value:Double?):JsonElement=value?.let(::p)?:JsonNull

 /** A provider-faithful, display-neutral companion to the existing Sleep metric.
  * It is deliberately not a HealthMetric: no State/Body Battery/Sleepiness code
  * can consume it accidentally. The companion raw ExternalRecord remains the
  * immutable provider evidence. */
 fun sleepDetailRecord(raw:JsonObject):JsonObject? {
  if(raw.text("score_state")!="SCORED")return null
  val external=raw.text("id").takeIf {it.isNotBlank()}?:return null
  val start=raw.text("start");val end=raw.text("end")
  val time=end.takeIf(::validTime)?:start.takeIf(::validTime)?:return null
  val updated=raw.text("updated_at").takeIf(::validTime)?:time
  val score=raw.obj("score");val stages=score.obj("stage_summary");val need=score.obj("sleep_needed")
  fun nonNegative(objectValue:JsonObject,key:String)=objectValue.finiteNumber(key)?.takeIf {it>=0.0}
  val light=nonNegative(stages,"total_light_sleep_time_milli")
  val slowWave=nonNegative(stages,"total_slow_wave_sleep_time_milli")
  val rem=nonNegative(stages,"total_rem_sleep_time_milli")
  val actual=listOf(light,slowWave,rem).takeIf {it.all {value->value!=null}}?.sumOf {it!!}
  val baseline=nonNegative(need,"baseline_milli")
  val debt=nonNegative(need,"need_from_sleep_debt_milli")
  val recentStrain=nonNegative(need,"need_from_recent_strain_milli")
  // WHOOP's documented value is already signed: retain it exactly instead of
  // subtracting a nap a second time.
  val napAdjustment=need.finiteNumber("need_from_recent_nap_milli")
  val totalNeed=listOf(baseline,debt,recentStrain,napAdjustment).takeIf {it.all {value->value!=null}}?.sumOf {it!!}
  val difference=if(actual!=null&&totalNeed!=null)actual-totalNeed else null
  val shortfall=difference?.let {(-it).coerceAtLeast(0.0)}
  val surplus=difference?.let {it.coerceAtLeast(0.0)}
  val needStatus=difference?.let {when {it<0.0->"SHORTFALL";it>0.0->"SURPLUS";else->"MET"}}
  val data=fields(
   "sleepRecordId" to p(external),"mappingVersion" to p(SLEEP_DETAIL_MAPPING_VERSION),
   "start" to p(start),"end" to p(end),"nap" to p(raw.flag("nap")),
   "actualSleepDurationMilli" to optional(actual),
   "baselineSleepNeedMilli" to optional(baseline),"sleepDebtContributionMilli" to optional(debt),
   "recentStrainContributionMilli" to optional(recentStrain),"napAdjustmentMilli" to optional(napAdjustment),
   "totalSleepNeedMilli" to optional(totalNeed),"sleepDifferenceMilli" to optional(difference),
   "shortfallMilli" to optional(shortfall),"surplusMilli" to optional(surplus),"needStatus" to (needStatus?.let(::p)?:JsonNull),
   "efficiencyPercentage" to optional(score.finiteNumber("sleep_efficiency_percentage")),
   "consistencyPercentage" to optional(score.finiteNumber("sleep_consistency_percentage")),
   "awakeDurationMilli" to optional(nonNegative(stages,"total_awake_time_milli")),
   "disturbanceCount" to optional(nonNegative(stages,"disturbance_count")),
   "timeInBedMilli" to optional(nonNegative(stages,"total_in_bed_time_milli")),
   "noDataDurationMilli" to optional(nonNegative(stages,"total_no_data_time_milli")),
   "sleepCycleCount" to optional(nonNegative(stages,"sleep_cycle_count")),
   "stages" to fields("lightMilli" to optional(light),"slowWaveMilli" to optional(slowWave),"remMilli" to optional(rem))
  )
  return personal("SleepDetail",data,"whoop:sleep-detail:$external","whoop",time).changed(
   "externalId" to p("sleep-detail:$external"),"updatedAt" to p(updated),
   "metadata" to fields("whoopType" to p("sleep"),"whoopId" to p(external),"whoopCycleId" to p(raw.text("cycle_id")),
    "recordStart" to p(start),"recordEnd" to p(end),"sourceUpdatedAt" to p(updated),"ingestedAt" to p(now()),"mappingVersion" to p(SLEEP_DETAIL_MAPPING_VERSION))
  )
 }
 fun records(type:String,raw:JsonObject):List<JsonObject> {
  val external=raw.text(if(type=="recovery")"cycle_id"else "id")
  if(external.isBlank())return emptyList()
  val time=raw.text("end").takeIf {validTime(it)}?:raw.text("start").takeIf {validTime(it)}?:raw.text("created_at")
  if(!validTime(time))return emptyList()
  val updated=raw.text("updated_at").takeIf {validTime(it)}?:time
  val result=mutableListOf(personal("ExternalRecord",fields("type" to p(type),"original" to raw),"whoop:raw:$type:$external","whoop",time).changed("externalId" to p("$type:$external"),"updatedAt" to p(updated)))
  if(raw.text("score_state")!="SCORED")return result
  val score=raw.obj("score")
  fun metric(name:String,value:Double?,unit:String,start:String="",end:String="") {
   if(value==null||!value.isFinite()||value<0)return
   val cycleId=raw.text("cycle_id").ifBlank {if(type=="cycle") external else ""}
   val recordStart=raw.text("start");val recordEnd=raw.text("end")
   var data=fields("metric" to p(name),"value" to p(value),"unit" to p(unit),"provider" to p("whoop"),"date" to p(dayOf(time)),"title" to p("$name recorded"),"nap" to p(raw.flag("nap")),"whoopCycleId" to p(cycleId),"recordStart" to p(recordStart),"recordEnd" to p(recordEnd))
   if(validTime(start)&&validTime(end))data=data.changed("start" to p(start),"end" to p(end))
   result.add(personal("HealthMetric",data,"whoop:$type:$external:$name","whoop",time).changed("externalId" to p("$type:$external:$name"),"updatedAt" to p(updated),"metadata" to fields("whoopType" to p(type),"whoopId" to p(external),"whoopCycleId" to p(cycleId),"recordStart" to p(recordStart),"recordEnd" to p(recordEnd),"sourceUpdatedAt" to p(updated),"ingestedAt" to p(now()))))
  }
  fun n(key:String)=(score[key] as? JsonPrimitive)?.doubleOrNull
  when(type) {
   "recovery" -> {
    if(!score.flag("user_calibrating"))metric("Recovery",n("recovery_score"),"%")
    metric("HRV",n("hrv_rmssd_milli"),"ms")
    metric("Resting heart rate",n("resting_heart_rate"),"bpm")
    metric("Blood oxygen",n("spo2_percentage"),"%")
    metric("Skin temperature",n("skin_temp_celsius"),"°C")
   }
   "cycle" -> metric("Strain",n("strain"),"/ 21")
   "sleep" -> {
    val stages=score.obj("stage_summary");val values=listOf("total_light_sleep_time_milli","total_slow_wave_sleep_time_milli","total_rem_sleep_time_milli").map {(stages[it] as? JsonPrimitive)?.doubleOrNull}
    if(values.all {it!=null&&it>=0})metric("Sleep",values.filterNotNull().sum()/60000,"min",raw.text("start"),raw.text("end"))
    metric("Sleep quality",n("sleep_performance_percentage"),"%")
    metric("Respiratory rate",n("respiratory_rate"),"rpm")
    sleepDetailRecord(raw)?.let(result::add)
   }
   "workout" -> {
    val start=raw.text("start");val end=raw.text("end")
    if(validTime(start)&&validTime(end))metric("Exercise",Duration.between(Instant.parse(start),Instant.parse(end)).toMillis()/60000.0,"min",start,end)
   }
  }
  return result
 }
}
