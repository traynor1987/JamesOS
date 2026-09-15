package uk.co.james.wear.sync

import android.content.Context
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import uk.co.james.wear.data.*

internal fun rowsForUpload(
    ordinary:List<ObservationEntity>,
    correlated:List<ObservationEntity>,
    correlationRequired:Boolean
):List<ObservationEntity> {
    if(correlationRequired&&correlated.none {it.type=="james_stress"})return emptyList()
    return (correlated+ordinary).distinctBy {it.id}.take(50)
}

class WearSync(private val context:Context,private val repo:WearRepository) {
    suspend fun uploadPending(requestId:String?=null,requestedAt:Long=0L,measurementCompletedAt:Long=0L):Boolean=uploadMutex.withLock {
        val nodes=runCatching {Wearable.getNodeClient(context).connectedNodes.await()}.getOrElse {repo.setConnected(false);return false}
        val node=nodes.firstOrNull()?:run {repo.setConnected(false);return false}
        repo.syncing()
        val ordinary=repo.dao.pending(50)
        val correlated=if(requestId.isNullOrBlank()) emptyList() else repo.dao.pendingSince(requestedAt)
            .filter {it.type in setOf("heart_rate","samsung_hrv","samsung_eda","samsung_skin_temperature","james_stress")}
        // A correlated upload is successful only when the actual result is in
        // this envelope. Prioritise the new sensor-check rows over any passive
        // backlog; otherwise the phone will correctly keep waiting.
        val rows=rowsForUpload(ordinary,correlated,!requestId.isNullOrBlank())
        if(!requestId.isNullOrBlank()&&rows.isEmpty())return false
        if(rows.isEmpty()){return runCatching {sendStatus(node.id);repo.uploaded();true}.getOrElse {repo.setConnected(false);false}}
        val payload=buildJsonObject {
            put("schemaVersion",SCHEMA_VERSION);put("sentAt",System.currentTimeMillis())
            if(!requestId.isNullOrBlank()) {
                put("stressCheckProtocolVersion",STRESS_CHECK_PROTOCOL_VERSION)
                put("requestId",requestId);put("requestedAt",requestedAt);put("measurementCompletedAt",measurementCompletedAt)
            }
            putJsonArray("observations") {rows.forEach {r->add(buildJsonObject {put("id",r.id);put("type",r.type);put("value",r.value);put("unit",r.unit);put("observedAt",r.observedAt)})}}
        }.toString().encodeToByteArray()
        return runCatching {
            Wearable.getMessageClient(context).sendMessage(node.id,OBSERVATIONS_PATH,payload).await()
            repo.dao.attempted(rows.map {it.id});repo.uploaded();true
        }.getOrElse {repo.setConnected(false);false}
    }
    suspend fun quickAction(label:String):Boolean {
        require(label in setOf("Home","Driving","Work","Walk")){"Unknown quick action."}
        val node=runCatching {Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()}.getOrNull()?:return false
        val payload=buildJsonObject {put("schemaVersion",SCHEMA_VERSION);put("label",label);put("observedAt",System.currentTimeMillis())}.toString().encodeToByteArray()
        return runCatching {Wearable.getMessageClient(context).sendMessage(node.id,QUICK_ACTION_PATH,payload).await();true}.getOrDefault(false)
    }
    suspend fun wellbeingCheckIn(mood:String):Boolean {
        require(mood in setOf("VERY LOW","LOW","OKAY","GOOD","GREAT")){"Unknown mood."}
        val node=runCatching {Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()}.getOrNull()?:return false
        val payload=buildJsonObject {put("schemaVersion",SCHEMA_VERSION);put("mood",mood);put("observedAt",System.currentTimeMillis())}.toString().encodeToByteArray()
        return runCatching {Wearable.getMessageClient(context).sendMessage(node.id,WELLBEING_CHECKIN_PATH,payload).await();true}.getOrDefault(false)
    }
    suspend fun rightNowCheckIn(kind:String,value:String):Boolean {
        require(kind in setOf("energy","timePressure"))
        val allowed=if(kind=="energy")setOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH") else setOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY")
        require(value in allowed){"Unknown check-in value."}
        val node=runCatching {Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()}.getOrNull()?:return false
        val payload=buildJsonObject {put("schemaVersion",SCHEMA_VERSION);put("kind",kind);put("value",value);put("observedAt",System.currentTimeMillis())}.toString().encodeToByteArray()
        return runCatching {Wearable.getMessageClient(context).sendMessage(node.id,RIGHT_NOW_CHECKIN_PATH,payload).await();true}.getOrDefault(false)
    }
    suspend fun sendStatus(nodeId:String?=null) {
        val target=nodeId?:Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()?.id?:return
        val payload=buildJsonObject {put("schemaVersion",SCHEMA_VERSION);put("watchVersion",uk.co.james.wear.BuildConfig.VERSION_NAME);put("watchVersionCode",uk.co.james.wear.BuildConfig.VERSION_CODE);put("device",android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL);put("passiveRegistered",repo.passiveRegistered);put("pending",repo.dao.pending().size);put("sentAt",System.currentTimeMillis())}.toString().encodeToByteArray()
        Wearable.getMessageClient(context).sendMessage(target,STATUS_PATH,payload).await();repo.setConnected(true)
    }
    suspend fun stressCheckStatus(
        requestId:String,
        requestedAt:Long,
        stage:String,
        message:String,
        startedAt:Long=0L,
        measurementCompletedAt:Long=0L,
        expectedDurationMillis:Long=0L,
        sampleCount:Int=0
    ) {
        val node=runCatching {Wearable.getNodeClient(context).connectedNodes.await().firstOrNull()}.getOrNull()?:return
        val payload=buildJsonObject {
            put("schemaVersion",SCHEMA_VERSION)
            put("stressCheckProtocolVersion",STRESS_CHECK_PROTOCOL_VERSION)
            put("requestId",requestId)
            put("requestedAt",requestedAt)
            put("stage",stage)
            put("message",message)
            put("startedAt",startedAt)
            put("measurementCompletedAt",measurementCompletedAt)
            put("expectedDurationMillis",expectedDurationMillis)
            put("sampleCount",sampleCount)
            put("sentAt",System.currentTimeMillis())
        }.toString().encodeToByteArray()
        runCatching {Wearable.getMessageClient(context).sendMessage(node.id,STRESS_CHECK_STATUS_PATH,payload).await()}
    }
    suspend fun updateStatus(nodeId:String,stage:TransferStage,version:String,progress:Int,message:String="") {
        val payload=buildJsonObject {put("schemaVersion",SCHEMA_VERSION);put("stage",stage.name);put("version",version);put("progress",progress);put("message",message);put("sentAt",System.currentTimeMillis())}.toString().encodeToByteArray()
        runCatching {Wearable.getMessageClient(context).sendMessage(nodeId,UPDATE_STATUS_PATH,payload).await()}
    }
    private companion object {val uploadMutex=Mutex()}
}
