package uk.co.james.whoop

import kotlinx.serialization.json.*
import uk.co.james.core.*
import java.time.*

/** Keep original WHOOP payloads; score only SCORED records and never substitute zero for missing values. */
object WhoopMapper {
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
   }
   "workout" -> {
    val start=raw.text("start");val end=raw.text("end")
    if(validTime(start)&&validTime(end))metric("Exercise",Duration.between(Instant.parse(start),Instant.parse(end)).toMillis()/60000.0,"min",start,end)
   }
  }
  return result
 }
}
