package uk.co.james.wear.data

import kotlinx.serialization.json.*

const val SCHEMA_VERSION=2
const val STRESS_CHECK_PROTOCOL_VERSION=2
const val SNAPSHOT_PATH="/james/v1/snapshot"
const val STATUS_PATH="/james/v1/status"
const val OBSERVATIONS_PATH="/james/v1/observations"
const val ACK_PATH="/james/v1/observations/ack"
const val UPDATE_CONTROL_PATH="/james/v1/update/control"
const val UPDATE_CHANNEL_PATH="/james/v1/update/apk"
const val UPDATE_STATUS_PATH="/james/v1/update/status"
const val OPEN_READY_UPDATE_PATH="/james/v1/update/open-ready"
/** Explicit phone-to-watch request for the short, high-detail sensor check. */
const val STRESS_CHECK_PATH="/james/v1/stress/check"
const val SENSOR_SETTINGS_PATH="/james/v1/sensor-settings"
const val STRESS_CHECK_STATUS_PATH="/james/v1/stress/status"
const val QUICK_ACTION_PATH="/james/v1/quick-action"
const val WELLBEING_CHECKIN_PATH="/james/v1/wellbeing/check-in"
const val RIGHT_NOW_CHECKIN_PATH="/james/v1/right-now/check-in"
data class SensorSettings(val passiveHeart:Boolean=true,val steps:Boolean=true,val calories:Boolean=true,val distance:Boolean=true,val automaticStress:Boolean=true,val detailHeart:Boolean=true,val hrv:Boolean=true,val eda:Boolean=true,val skinTemperature:Boolean=true)
const val CAPABILITY="james_wear_v1"

data class Reading(val value:Double?=null,val unit:String="",val source:String="",val timestamp:String="")
data class WellbeingSnapshot(val mentalReserve:Int?=null,val reserveLabel:String="Learning",val anxietyLoad:Int?=null,val anxietyLabel:String="Learning",val lowMoodLoad:Int?=null,val trend:String="Learning",val confidence:String="Learning",val anxietyCalculatedAt:String="",val anxietyNewestInputAt:String="",val anxietyTrigger:String="",val anxietyUnchanged:Boolean=false)
data class RightNowSnapshot(val liveEnergy:Int?=null,val energyLabel:String="Learning",val sustainability:Int?=null,val sustainabilityLabel:String="Learning",val crashRisk:Int?=null,val crashLabel:String="Learning",val timePressure:Int?=null,val timePressureLabel:String="Learning",val confidence:String="Learning",val calculatedAt:String="")
data class BatterySnapshot(val value:Int?=null,val headline:String="Waiting for phone",val summary:String="",val starting:Int?=null,val used:Int?=null,val confidence:String="Unavailable",val forecastLow:Int?=null,val forecastHigh:Int?=null,val timestamp:String="")
data class JamesSnapshot(
    val schemaVersion:Int=SCHEMA_VERSION,val generatedAt:String="",val battery:BatterySnapshot=BatterySnapshot(),val wellbeing:WellbeingSnapshot=WellbeingSnapshot(),val rightNow:RightNowSnapshot=RightNowSnapshot(),
    val recovery:Reading=Reading(),val sleep:Reading=Reading(),val sleepQuality:Reading=Reading(),val strain:Reading=Reading(),
    val hrv:Reading=Reading(),val restingHeartRate:Reading=Reading(),val heartRate:Reading=Reading(),val steps:Reading=Reading(),
    val routinesDue:Int=0,val routinesCompleted:Int=0,val timelineCount:Int=0
)

private fun JsonObject.text(key:String)=this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
private fun JsonObject.number(key:String)=this[key]?.jsonPrimitive?.doubleOrNull
private fun JsonObject.obj(key:String)=this[key] as? JsonObject?:JsonObject(emptyMap())
fun parseSnapshot(raw:String):JamesSnapshot {
    val root=Json.parseToJsonElement(raw).jsonObject
    val body=root.obj("bodyBattery");val health=root.obj("health");val wellbeing=root.obj("wellbeing");val rightNow=root.obj("rightNow")
    fun reading(name:String):Reading {val r=health.obj(name);return Reading(r.number("value"),r.text("unit"),r.text("source"),r.text("timestamp"))}
    return JamesSnapshot(
        schemaVersion=root.number("schemaVersion")?.toInt()?:1,generatedAt=root.text("generatedAt"),
        battery=BatterySnapshot(body.number("value")?.toInt(),body.text("headline"),body.text("summary"),body.number("startingReserve")?.toInt(),body.number("used")?.toInt(),body.text("confidence"),body.number("forecastLow")?.toInt(),body.number("forecastHigh")?.toInt(),body.text("timestamp")),wellbeing=WellbeingSnapshot(wellbeing.number("mentalReserve")?.toInt(),wellbeing.text("reserveLabel"),wellbeing.number("anxietyLoad")?.toInt(),wellbeing.text("anxietyLabel"),wellbeing.number("lowMoodLoad")?.toInt(),wellbeing.text("trend"),wellbeing.text("confidence"),wellbeing.text("anxietyCalculatedAt"),wellbeing.text("anxietyNewestInputAt"),wellbeing.text("anxietyTrigger"),wellbeing["anxietyUnchanged"]?.jsonPrimitive?.booleanOrNull?:false),rightNow=RightNowSnapshot(rightNow.number("liveEnergy")?.toInt(),rightNow.text("energyLabel"),rightNow.number("sustainability")?.toInt(),rightNow.text("sustainabilityLabel"),rightNow.number("crashRisk")?.toInt(),rightNow.text("crashLabel"),rightNow.number("timePressure")?.toInt(),rightNow.text("timePressureLabel"),rightNow.text("confidence"),rightNow.text("calculatedAt")),
        recovery=reading("recovery"),sleep=reading("sleep"),sleepQuality=reading("sleepQuality"),strain=reading("strain"),hrv=reading("hrv"),restingHeartRate=reading("restingHeartRate"),heartRate=reading("heartRate"),steps=reading("steps"),
        routinesDue=root.obj("routines").number("due")?.toInt()?:0,routinesCompleted=root.obj("routines").number("completed")?.toInt()?:0,timelineCount=root.obj("timeline").number("todayCount")?.toInt()?:0
    )
}

enum class ConnectionState{CONNECTED,DISCONNECTED,SYNCING,STALE_DATA}
enum class TransferStage{IDLE,PREPARING,RECEIVING,VERIFYING,READY,AWAITING_CONFIRMATION,UPDATED,FAILED}
data class UpdateState(val stage:TransferStage=TransferStage.IDLE,val version:String="",val progress:Int=0,val message:String="")

const val SNAPSHOT_STALE_AFTER_MS=2*60*60_000L
fun isSnapshotStale(receivedAt:Long,now:Long=System.currentTimeMillis())=receivedAt<=0L||now-receivedAt>SNAPSHOT_STALE_AFTER_MS
