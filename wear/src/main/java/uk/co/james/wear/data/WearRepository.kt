package uk.co.james.wear.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.Room
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.*

class WearRepository(private val context:Context) {
    private val db=Room.databaseBuilder(context,WearDatabase::class.java,"james-wear.db").build()
    val dao=db.dao()
    val snapshot=dao.snapshot().map {row->row?.let {runCatching {parseSnapshot(it.json)}.getOrNull()}?:JamesSnapshot()}
    val snapshotReceivedAt=dao.snapshot().map {it?.receivedAt?:0L}
    val pendingCount=dao.pendingCount()
    private val prefs=context.getSharedPreferences("wear-state",Context.MODE_PRIVATE)
    private val _connection=MutableStateFlow(ConnectionState.DISCONNECTED);val connection:StateFlow<ConnectionState> = _connection
    private val _update=MutableStateFlow(loadUpdate());val update:StateFlow<UpdateState> = _update
    private val _sensorSettings=MutableStateFlow(loadSensorSettings());val sensorSettings:StateFlow<SensorSettings> = _sensorSettings
    val lastPhoneContact get()=prefs.getLong("last_phone_contact",0)
    val lastUpload get()=prefs.getLong("last_upload",0)
    /** Automatic estimates are throttled; Health Services decides when it can deliver readings. */
    val lastAutoStressAt get()=prefs.getLong("last_auto_stress_at",0)
    val passiveRegistered get()=prefs.getBoolean("passive_registered",false)
    val lastTransferResult get()=prefs.getString("last_transfer","").orEmpty()
    fun setConnected(value:Boolean){_connection.value=if(value)ConnectionState.CONNECTED else ConnectionState.DISCONNECTED;if(value)prefs.edit().putLong("last_phone_contact",System.currentTimeMillis()).apply()}
    fun syncing(){_connection.value=ConnectionState.SYNCING}
    fun stale(){_connection.value=ConnectionState.STALE_DATA}
    suspend fun saveSnapshot(raw:String){val parsed=parseSnapshot(raw);dao.putSnapshot(SnapshotEntity(json=raw,generatedAt=parsed.generatedAt,receivedAt=System.currentTimeMillis()));setConnected(true)}
    suspend fun observe(type:String,value:Double,unit:String,time:Long=System.currentTimeMillis()) {
        val id="$type:$time:${UUID.randomUUID()}";dao.putObservation(ObservationEntity(id,type,value,unit,time));dao.purge(System.currentTimeMillis()-7*24*60*60*1000L)
    }
    fun passive(value:Boolean){prefs.edit().putBoolean("passive_registered",value).apply()}
    fun sensorSettings(v:SensorSettings){_sensorSettings.value=v;prefs.edit().putBoolean("sensor_passive_heart",v.passiveHeart).putBoolean("sensor_steps",v.steps).putBoolean("sensor_calories",v.calories).putBoolean("sensor_distance",v.distance).putBoolean("sensor_auto_stress",v.automaticStress).putBoolean("sensor_detail_heart",v.detailHeart).putBoolean("sensor_hrv",v.hrv).putBoolean("sensor_eda",v.eda).putBoolean("sensor_skin_temp",v.skinTemperature).apply()}
    private fun loadSensorSettings()=SensorSettings(prefs.getBoolean("sensor_passive_heart",true),prefs.getBoolean("sensor_steps",true),prefs.getBoolean("sensor_calories",true),prefs.getBoolean("sensor_distance",true),prefs.getBoolean("sensor_auto_stress",true),prefs.getBoolean("sensor_detail_heart",true),prefs.getBoolean("sensor_hrv",true),prefs.getBoolean("sensor_eda",true),prefs.getBoolean("sensor_skin_temp",true))
    fun autoStressRecorded(at:Long){prefs.edit().putLong("last_auto_stress_at",at).apply()}
    fun uploaded(){prefs.edit().putLong("last_upload",System.currentTimeMillis()).apply();setConnected(true)}
    fun update(value:UpdateState){_update.value=value;val edit=prefs.edit().putString("update_stage",value.stage.name).putString("update_version",value.version).putInt("update_progress",value.progress).putString("update_message",value.message);if(value.message.isNotBlank())edit.putString("last_transfer",value.message);edit.apply()}
    private fun loadUpdate()=UpdateState(runCatching {TransferStage.valueOf(prefs.getString("update_stage",TransferStage.IDLE.name)!!)}.getOrDefault(TransferStage.IDLE),prefs.getString("update_version","").orEmpty(),prefs.getInt("update_progress",0),prefs.getString("update_message","").orEmpty())
    fun updateDir()=File(context.filesDir,"updates").apply {mkdirs()}
    fun verify(file:File,expected:String):Boolean {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->
            val buffer=ByteArray(64*1024)
            while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}
        }
        return digest.digest().joinToString(""){"%02x".format(it)}.equals(expected,true)
    }
    /** A ready package remains available until Android confirms the replacement. */
    fun markUpdateReady(version:String,sha256:String) {prefs.edit().putString("ready_update_version",version).putString("ready_update_sha256",sha256.lowercase()).apply()}
    fun readyUpdateVersion():String=prefs.getString("ready_update_version","").orEmpty()
    fun hasVerifiedReadyUpdate():Boolean {
        val expected=prefs.getString("ready_update_sha256","").orEmpty()
        val file=File(updateDir(),"james-wear.apk")
        return expected.matches(Regex("[0-9a-fA-F]{64}"))&&file.isFile&&runCatching {verify(file,expected)}.getOrDefault(false)
    }
    fun clearReadyUpdate(){prefs.edit().remove("ready_update_version").remove("ready_update_sha256").apply()}
    fun showReadyUpdate():Boolean {
        if(!hasVerifiedReadyUpdate())return false
        update(UpdateState(TransferStage.READY,readyUpdateVersion(),100,"Verified and saved on this watch. Tap Install."))
        return true
    }
    fun installIntent():Intent {
        require(hasVerifiedReadyUpdate()) {"The verified watch update is no longer available."}
        val file=File(updateDir(),"james-wear.apk")
        val uri:Uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).also { intent->
            intent.clipData=ClipData.newRawUri("James OS Wear update",uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    companion object {fun ageText(value:Long):String=if(value==0L)"Never" else {val mins=((System.currentTimeMillis()-value)/60000).coerceAtLeast(0);when{mins<1->"Just now";mins<60->"${mins}m ago";mins<1440->"${mins/60}h ago";else->"${mins/1440}d ago"}}}
}
