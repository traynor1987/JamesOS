package uk.co.james.wear

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.routines.Habits
import uk.co.james.state.bodyBattery
import uk.co.james.state.mentalWellbeing
import uk.co.james.state.rightNowSummary
import uk.co.james.settings.EnergyTimeSettings

/** Privacy-limited phone-to-watch snapshot. Additive changes remain compatible with v1. */
object CompanionContract {
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
    const val STRESS_CHECK_PATH="/james/v1/stress/check"
    const val SENSOR_SETTINGS_PATH="/james/v1/sensor-settings"
    const val STRESS_CHECK_STATUS_PATH="/james/v1/stress/status"
    /** A deliberately small, non-sensitive timeline marker created on the watch. */
    const val QUICK_ACTION_PATH="/james/v1/quick-action"
    const val WELLBEING_CHECKIN_PATH="/james/v1/wellbeing/check-in"
    const val RIGHT_NOW_CHECKIN_PATH="/james/v1/right-now/check-in"
    const val CAPABILITY="james_wear_v1"

    fun snapshot(records:List<StoredRecord>,clock:Instant=Instant.now()):JsonObject {
        val day=dayOf(clock.toString())
        val battery=bodyBattery(records,clock)
        val wellbeing=mentalWellbeing(records,clock=clock)
        val rightNow=rightNowSummary(records,EnergyTimeSettings(),clock)
        val routines=records.filter {Habits.due(it.raw(),day)}
        val owned=records.filter {it.store=="personalRecords"}.map {it.raw()}
        val completed=routines.count {Habits.complete(owned,it.recordId,day)}
        fun metric(name:String)=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")==name&&!it.data().flag("nap")&&runCatching {Instant.parse(it.timestamp)<=clock}.getOrDefault(false)}.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
        fun reading(name:String)=metric(name)?.let {fields("value" to p(it.data().number("value")),"unit" to p(it.data().text("unit")),"source" to p(it.source),"timestamp" to p(it.timestamp))}
        return fields(
            "schemaVersion" to p(SCHEMA_VERSION),"generatedAt" to p(clock.toString()),
            "bodyBattery" to fields("value" to (battery.value?.let(::p)?:kotlinx.serialization.json.JsonNull),"headline" to p(battery.headline),"summary" to p(battery.summary),"startingReserve" to (battery.morning?.let(::p)?:kotlinx.serialization.json.JsonNull),"used" to (battery.used?.let(::p)?:kotlinx.serialization.json.JsonNull),"confidence" to p(battery.confidence),"forecastLow" to (battery.forecast?.first?.let(::p)?:kotlinx.serialization.json.JsonNull),"forecastHigh" to (battery.forecast?.last?.let(::p)?:kotlinx.serialization.json.JsonNull),"timestamp" to p(clock.toString())),
            "routines" to fields("due" to p(routines.size),"completed" to p(completed)),
            "health" to fields("recovery" to (reading("Recovery")?:kotlinx.serialization.json.JsonNull),"strain" to (reading("Strain")?:kotlinx.serialization.json.JsonNull),"sleep" to (reading("Sleep")?:kotlinx.serialization.json.JsonNull),"sleepQuality" to (reading("Sleep quality")?:kotlinx.serialization.json.JsonNull),"hrv" to (reading("HRV")?:kotlinx.serialization.json.JsonNull),"restingHeartRate" to (reading("Resting heart rate")?:kotlinx.serialization.json.JsonNull),"heartRate" to (reading("Heart rate")?:kotlinx.serialization.json.JsonNull),"steps" to (reading("Steps")?:kotlinx.serialization.json.JsonNull)),
            "wellbeing" to fields("mentalReserve" to p(wellbeing.reserve.score),"reserveLabel" to p(wellbeing.reserve.label),"anxietyLoad" to p(wellbeing.anxiety.score),"anxietyLabel" to p(wellbeing.anxiety.label),"lowMoodLoad" to p(wellbeing.lowMood.score),"trend" to p(wellbeing.lowMood.trend),"confidence" to p(wellbeing.reserve.confidence),
                "anxietyCalculatedAt" to (wellbeing.anxietyDiagnostics?.calculatedAt?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "anxietyNewestInputAt" to (wellbeing.anxietyDiagnostics?.newestInputAt?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "anxietyTrigger" to (wellbeing.anxietyDiagnostics?.trigger?.let(::p)?:kotlinx.serialization.json.JsonNull),
                "anxietyUnchanged" to p(wellbeing.anxietyDiagnostics?.unchanged?:false)
            ),
            "rightNow" to fields("liveEnergy" to p(rightNow.liveEnergy.score),"energyLabel" to p(rightNow.liveEnergy.label),"sustainability" to p(rightNow.sustainability.score),"sustainabilityLabel" to p(rightNow.sustainability.label),"crashRisk" to p(rightNow.crashRisk.score),"crashLabel" to p(rightNow.crashRisk.label),"timePressure" to p(rightNow.timePressure.score),"timePressureLabel" to p(rightNow.timePressure.label),"confidence" to p(rightNow.liveEnergy.confidence),"calculatedAt" to p(clock.toString())),
            "timeline" to fields("todayCount" to p(records.count {it.localDate==day && it.kind in listOf("HealthMetric","RoutineCompletion","PlaceVisit","Event","TimeBlock")})),
            "settings" to fields("units" to p("metric"))
        )
    }
}
