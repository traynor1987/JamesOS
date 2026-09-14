package uk.co.james.wear.sync

import com.google.android.gms.wearable.*
import java.io.File
import android.content.Intent
import androidx.core.content.ContextCompat
import uk.co.james.wear.health.WearStressCheckService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.ui.WearMainActivity
import uk.co.james.wear.data.*
import uk.co.james.wear.health.HealthServicesProvider

class WearDataLayerService:WearableListenerService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val repo get()=(application as JamesWearApplication).repository
    override fun onDataChanged(events:DataEventBuffer) {
        events.forEach {event->
            if(event.type==DataEvent.TYPE_CHANGED&&event.dataItem.uri.path==SNAPSHOT_PATH) {
                DataMapItem.fromDataItem(event.dataItem).dataMap.getByteArray("payload")?.decodeToString()?.let {raw->
                    scope.launch {runCatching {repo.saveSnapshot(raw);WearSync(this@WearDataLayerService,repo).sendStatus(event.dataItem.uri.host)}}
                }
            }
        }
    }
    override fun onPeerConnected(peer:Node){repo.setConnected(true);scope.launch {WearSync(this@WearDataLayerService,repo).uploadPending();WearSync(this@WearDataLayerService,repo).sendStatus(peer.id)}}
    override fun onPeerDisconnected(peer:Node){repo.setConnected(false)}
    override fun onMessageReceived(event:MessageEvent) {when(event.path) {
        ACK_PATH->scope.launch {val ids=Json.parseToJsonElement(event.data.decodeToString()).jsonObject["ids"]?.jsonArray?.mapNotNull {it.jsonPrimitive.contentOrNull}.orEmpty();if(ids.isNotEmpty()){repo.dao.acknowledge(ids);repo.uploaded();WearSync(this@WearDataLayerService,repo).uploadPending()}}
        UPDATE_CONTROL_PATH->runCatching {
            val data=Json.parseToJsonElement(event.data.decodeToString()).jsonObject
            val version=data["version"]?.jsonPrimitive?.content.orEmpty()
            val sha=data["sha256"]?.jsonPrimitive?.content.orEmpty()
            val size=data["size"]?.jsonPrimitive?.longOrNull?:0
            require(version.isNotBlank()&&sha.matches(Regex("[0-9a-fA-F]{64}"))&&size>0){"Invalid update control message."}
            // commit() makes the channel receiver safe even if it starts immediately after this message.
            check(getSharedPreferences("wear-update",MODE_PRIVATE).edit().putString("version",version).putString("sha256",sha).putLong("size",size).putString("node",event.sourceNodeId).commit())
            repo.update(UpdateState(TransferStage.PREPARING,version,0,"Ready to receive the update."))
            scope.launch {WearSync(this@WearDataLayerService,repo).updateStatus(event.sourceNodeId,TransferStage.PREPARING,version,0,"Watch ready to receive update")}
            startActivity(android.content.Intent(this,WearMainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        "/james/v1/open/settings"->startActivity(android.content.Intent(this,WearMainActivity::class.java).putExtra(WearMainActivity.EXTRA_SCREEN,WearMainActivity.SCREEN_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
        SENSOR_SETTINGS_PATH->scope.launch {runCatching {val d=Json.parseToJsonElement(event.data.decodeToString()).jsonObject;repo.sensorSettings(SensorSettings(d["passiveHeart"]?.jsonPrimitive?.booleanOrNull?:true,d["steps"]?.jsonPrimitive?.booleanOrNull?:true,d["calories"]?.jsonPrimitive?.booleanOrNull?:true,d["distance"]?.jsonPrimitive?.booleanOrNull?:true,d["automaticStress"]?.jsonPrimitive?.booleanOrNull?:true,d["detailHeart"]?.jsonPrimitive?.booleanOrNull?:true,d["hrv"]?.jsonPrimitive?.booleanOrNull?:true,d["eda"]?.jsonPrimitive?.booleanOrNull?:true,d["skinTemperature"]?.jsonPrimitive?.booleanOrNull?:true));HealthServicesProvider(this@WearDataLayerService).register(repo.sensorSettings.value);repo.passive(true)}}
        STRESS_CHECK_PATH->runCatching {
            val request=Json.parseToJsonElement(event.data.decodeToString()).jsonObject
            val requestId=request["requestId"]?.jsonPrimitive?.content.orEmpty()
            val requestedAt=request["requestedAt"]?.jsonPrimitive?.longOrNull?:0L
            val protocol=request["protocolVersion"]?.jsonPrimitive?.intOrNull?:0
            require(protocol==STRESS_CHECK_PROTOCOL_VERSION&&requestId.isNotBlank()&&requestedAt>0L) {"Invalid stress-check request."}
            ContextCompat.startForegroundService(
                this,
                Intent(this,WearStressCheckService::class.java)
                    .setAction(WearStressCheckService.ACTION_REMOTE_STRESS_CHECK)
                    .putExtra(WearStressCheckService.EXTRA_REQUEST_ID,requestId)
                    .putExtra(WearStressCheckService.EXTRA_REQUESTED_AT,requestedAt)
            )
        }.onFailure {
            scope.launch {
                val request=runCatching {Json.parseToJsonElement(event.data.decodeToString()).jsonObject}.getOrNull()
                WearSync(this@WearDataLayerService,repo).stressCheckStatus(
                    requestId=request?.get("requestId")?.jsonPrimitive?.content.orEmpty(),
                    requestedAt=request?.get("requestedAt")?.jsonPrimitive?.longOrNull?:0L,
                    stage="FAILED",
                    message=it.message?:"The watch could not start the sensor check."
                )
            }
        }
        OPEN_READY_UPDATE_PATH->if(repo.showReadyUpdate())startActivity(android.content.Intent(this,WearMainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        else->{ }
    }}
    override fun onChannelOpened(channel:ChannelClient.Channel) {if(channel.path!=UPDATE_CHANNEL_PATH)return;scope.launch {receiveUpdate(channel)}}
    private suspend fun receiveUpdate(channel:ChannelClient.Channel) {
        val prefs=getSharedPreferences("wear-update",MODE_PRIVATE);val version=prefs.getString("version","").orEmpty();val sha=prefs.getString("sha256","").orEmpty();val size=prefs.getLong("size",0);val part=File(repo.updateDir(),"incoming.part");val target=File(repo.updateDir(),"james-wear.apk");val sync=WearSync(this,repo)
        try {
            repo.update(UpdateState(TransferStage.RECEIVING,version,0,"Receiving update"));sync.updateStatus(channel.nodeId,TransferStage.RECEIVING,version,0)
            val input=Wearable.getChannelClient(this).getInputStream(channel).await();var total=0L
            input.use {source->
                part.outputStream().use {out->
                    val buffer=ByteArray(64*1024)
                    while(true){val n=source.read(buffer);if(n<0)break;total+=n;require(total<=100L*1024*1024){"Wear APK is too large."};out.write(buffer,0,n);val progress=if(size>0)(total*100/size).toInt().coerceIn(0,99)else 0;repo.update(UpdateState(TransferStage.RECEIVING,version,progress,"Receiving update"));if(progress%10==0)sync.updateStatus(channel.nodeId,TransferStage.RECEIVING,version,progress)}
                }
            }
            require(size<=0||total==size){"Transfer ended early: received $total of $size bytes."};repo.update(UpdateState(TransferStage.VERIFYING,version,100,"Verifying checksum"));sync.updateStatus(channel.nodeId,TransferStage.VERIFYING,version,100)
            require(sha.matches(Regex("[0-9a-fA-F]{64}"))&&repo.verify(part,sha)){"Checksum failed after receiving $total bytes. Try sending the update again."};if(target.exists())target.delete();check(part.renameTo(target));repo.markUpdateReady(version,sha);repo.update(UpdateState(TransferStage.READY,version,100,"Verified and saved on this watch. Tap Install."));sync.updateStatus(channel.nodeId,TransferStage.READY,version,100,"Verified on watch; ready to install")
        } catch(e:Exception){part.delete();val message=e.message?:"Transfer failed";repo.update(UpdateState(TransferStage.FAILED,version,0,message));sync.updateStatus(channel.nodeId,TransferStage.FAILED,version,0,message)} finally {runCatching {Wearable.getChannelClient(this).close(channel).await()}}
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
