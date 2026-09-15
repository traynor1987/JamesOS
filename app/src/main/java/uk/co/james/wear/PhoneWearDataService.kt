package uk.co.james.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import uk.co.james.JamesApplication
import uk.co.james.core.*

class PhoneWearDataService:WearableListenerService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val app get()=application as JamesApplication
    override fun onMessageReceived(event:MessageEvent){when(event.path){
        CompanionContract.STATUS_PATH->app.wear.receiveStatus(event.sourceNodeId,event.data.decodeToString())
        CompanionContract.UPDATE_STATUS_PATH->app.wear.receiveUpdate(event.data.decodeToString())
        CompanionContract.STRESS_CHECK_STATUS_PATH->app.wear.receiveStressCheck(event.data.decodeToString())
        CompanionContract.OBSERVATIONS_PATH->scope.launch {receiveObservations(event)}
        CompanionContract.QUICK_ACTION_PATH->scope.launch {receiveQuickAction(event)}
        CompanionContract.WELLBEING_CHECKIN_PATH->scope.launch {receiveWellbeingCheckIn(event)}
        CompanionContract.RIGHT_NOW_CHECKIN_PATH->scope.launch {receiveRightNowCheckIn(event)}
    }}
    private suspend fun receiveObservations(event:MessageEvent) {
        val root=runCatching {json.parseToJsonElement(event.data.decodeToString()).jsonObject}.getOrNull()?:return
        data class Accepted(val id:String,val type:String,val observedAt:Long)
        val accepted=mutableListOf<Accepted>()
        val incoming=mutableListOf<JsonObject>()
        root.array("observations").forEach {item->
            val o=item.jsonObject
            val id=o.text("id")
            val type=o.text("type")
            val stamp=java.time.Instant.ofEpochMilli(o.number("observedAt").toLong()).toString()
            val metric=when(type) {
                "heart_rate"->"Heart rate";"steps"->"Steps";"calories"->"Calories";"distance"->"Distance"
                "samsung_hrv"->"HRV";"samsung_eda"->"Skin conductance";"samsung_skin_temperature"->"Skin temperature"
                "james_stress"->"James Stress";else->return@forEach
            }
            val raw=personal("HealthMetric",fields(
                "metric" to p(metric),"value" to p(o.number("value")),"unit" to p(o.text("unit")),
                "provider" to p("uk.co.james.wear"),
                "measurementContext" to p(when(type) {
                    "samsung_hrv"->"SAMSUNG_SENSOR_CHECK_HRV"
                    "samsung_eda","samsung_skin_temperature"->"SAMSUNG_SENSOR_CHECK"
                    "james_stress"->"SAMSUNG_SENSOR_CHECK_STRESS"
                    else->"WEAR_PASSIVE"
                }),
                "date" to p(dayOf(stamp))
            ),"wear:$id",stamp).changed("source" to p("wear"),"externalId" to p(id),"updatedAt" to p(stamp))
            incoming+=raw
            accepted.add(Accepted(id,type,o.number("observedAt").toLong()))
        }
        runCatching {app.repository.externalBatch(incoming)}.getOrElse {return}
        val ack=buildJsonObject {put("schemaVersion",CompanionContract.SCHEMA_VERSION);putJsonArray("ids"){accepted.forEach {add(it.id)}}}.toString().encodeToByteArray()
        Wearable.getMessageClient(this).sendMessage(event.sourceNodeId,CompanionContract.ACK_PATH,ack).await()

        // A live phone refresh must use the just-persisted James Stress reading
        // before Anxiety is evaluated. Passive observations still follow their
        // ordinary flow; they never fabricate a manual refresh state.
        val requestId=root.text("requestId")
        val stressObservation=accepted.filter {it.type=="james_stress"}.maxByOrNull {it.observedAt}
        if(qualifiesStressCheckResult(
                app.wear.stressCheck.value,requestId,
                root.number("stressCheckProtocolVersion").toInt(),root.number("requestedAt").toLong(),
                root.number("measurementCompletedAt").toLong(),stressObservation?.observedAt
            )) {
            app.wear.sensorDataPersisted(requestId,stressObservation!!.observedAt)
            val rows=app.repository.stateInputs()
            val fresh=uk.co.james.state.mentalWellbeing(rows,app.preferences.wellbeing.first())
            app.repository.persistWellbeing(fresh)
            val stress=rows.firstOrNull {it.externalId==stressObservation.id&&it.kind=="HealthMetric"&&it.data().text("metric")=="James Stress"}
                ?.data()?.number("value")
            app.wear.completeStressCheck(requestId,stress,fresh.anxiety.score)
        }
        app.wear.publish(app.repository.stateInputs())
    }
    private suspend fun receiveQuickAction(event:MessageEvent) {
        val root=runCatching {json.parseToJsonElement(event.data.decodeToString()).jsonObject}.getOrNull()?:return
        val label=root.text("label").takeIf {it in setOf("Home","Driving","Work","Walk")}?:return
        val millis=root.number("observedAt").toLong().takeIf {it>0}?:System.currentTimeMillis()
        val stamp=java.time.Instant.ofEpochMilli(millis).toString()
        val raw=personal("Event",fields("title" to p("Watch: $label"),"category" to p("Watch quick action"),"date" to p(dayOf(stamp)),"note" to p("Logged from James OS Wear."),"end" to p(stamp)),"wear:quick:$millis:$label",stamp).changed("source" to p("wear"),"externalId" to p("wear:quick:$millis:$label"),"updatedAt" to p(stamp))
        runCatching {app.repository.external(raw)}.onSuccess {app.wear.publish(app.repository.stateInputs())}
    }
    private suspend fun receiveWellbeingCheckIn(event:MessageEvent) {
        val root=runCatching {json.parseToJsonElement(event.data.decodeToString()).jsonObject}.getOrNull()?:return
        val mood=root.text("mood").takeIf {it in setOf("VERY LOW","LOW","OKAY","GOOD","GREAT")}?:return
        val millis=root.number("observedAt").toLong().takeIf {it>0}?:System.currentTimeMillis()
        val stamp=java.time.Instant.ofEpochMilli(millis).toString()
        val raw=personal("WellbeingCheckIn",fields("mood" to p(mood),"energy" to p(""),"anxiety" to p(""),"date" to p(dayOf(stamp)),"algorithmVersion" to p(uk.co.james.state.WELLBEING_ALGORITHM_VERSION)),"wear:wellbeing:$millis",stamp).changed("source" to p("wear"),"externalId" to p("wear:wellbeing:$millis"),"updatedAt" to p(stamp))
        runCatching {app.repository.external(raw)}.onSuccess {app.wear.publish(app.repository.stateInputs())}
    }
    private suspend fun receiveRightNowCheckIn(event:MessageEvent) {
        val root=runCatching {json.parseToJsonElement(event.data.decodeToString()).jsonObject}.getOrNull()?:return
        val kind=root.text("kind").takeIf {it in setOf("energy","timePressure")}?:return
        val allowed=if(kind=="energy")setOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH") else setOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY")
        val value=root.text("value").takeIf {it in allowed}?:return
        val millis=root.number("observedAt").toLong().takeIf {it in 1..System.currentTimeMillis().plus(5*60_000L)}?:return
        val stamp=java.time.Instant.ofEpochMilli(millis).toString()
        val summary=uk.co.james.state.rightNowSummary(app.repository.stateInputs(),app.preferences.energyTime.first(),java.time.Instant.ofEpochMilli(millis))
        val raw=if(kind=="energy")personal("WellbeingCheckIn",fields("mood" to p(""),"energy" to p(value),"anxiety" to p(""),"date" to p(dayOf(stamp)),"liveEnergyBefore" to p(summary.liveEnergy.score),"bodyBattery" to (summary.bodyBattery?.let(::p)?:JsonNull),"mentalReserve" to p(summary.mentalReserve),"awakeMinutes" to p(summary.awakeMinutes),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.LIVE_ENERGY_VERSION)),"wear:energy:$millis","wear",stamp)
        else personal("TimePressureCheckIn",fields("title" to p("Time pressure check-in"),"pressure" to p(value),"date" to p(dayOf(stamp)),"predictionBefore" to p(summary.timePressure.score),"nextConstraint" to p(summary.nextConstraint?.title?:""),"usableMinutes" to (summary.nextConstraint?.usableMinutes?.let(::p)?:JsonNull),"personalMinutes" to p(summary.personalMinutes),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.TIME_PRESSURE_VERSION)),"wear:pressure:$millis","wear",stamp)
        runCatching {app.repository.external(raw.changed("externalId" to p(raw.text("id")),"updatedAt" to p(stamp)));app.wear.publish(app.repository.stateInputs())}
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
