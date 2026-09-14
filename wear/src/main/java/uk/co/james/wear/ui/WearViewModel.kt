package uk.co.james.wear.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.data.*
import uk.co.james.wear.health.*
import uk.co.james.wear.sync.WearSync

class WearViewModel(application:Application):AndroidViewModel(application) {
    private val app=application as JamesWearApplication;val repo=app.repository
    val snapshot=repo.snapshot.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),JamesSnapshot())
    val snapshotReceivedAt=repo.snapshotReceivedAt.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0L)
    val connection=repo.connection;val update=repo.update;val pending=repo.pendingCount.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val heart=repo.dao.recent("heart_rate",30).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val steps=repo.dao.recent("steps",3).stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val capabilities=MutableStateFlow<SensorCapabilities?>(null);val sensorMessage=MutableStateFlow("")
    val cachedUpdate=MutableStateFlow(repo.hasVerifiedReadyUpdate())
    private val stressChecks=app.stressChecks
    val samsungStatus=stressChecks.status
    val samsungSensorCheck=stressChecks.sensorCheck
    init {
        viewModelScope.launch {capabilities.value=runCatching {HealthServicesProvider(app).capabilities()}.getOrNull();WearSync(app,repo).uploadPending()}
        // Samsung sensor collection is application-scoped. The UI only observes
        // it, so a phone-initiated request keeps running if this Activity closes.
        viewModelScope.launch {update.collect {state->
            if(state.stage in setOf(TransferStage.READY,TransferStage.AWAITING_CONFIRMATION,TransferStage.FAILED)) cachedUpdate.value=repo.hasVerifiedReadyUpdate()
        }}
    }
    fun registerPassive(){viewModelScope.launch {runCatching {capabilities.value=HealthServicesProvider(app).register(repo.sensorSettings.value);repo.passive(true);sensorMessage.value="Passive monitoring active";WearSync(app,repo).uploadPending()}.onFailure {repo.passive(false);sensorMessage.value=it.message?:"Permission or capability unavailable"}}}
    fun sensorSettings(v:SensorSettings){repo.sensorSettings(v);viewModelScope.launch {runCatching {capabilities.value=HealthServicesProvider(app).register(v);repo.passive(true)}.onFailure {repo.passive(false);sensorMessage.value=it.message?:"No passive readings selected or permission unavailable"};WearSync(app,repo).uploadPending()}}
    fun sync(){viewModelScope.launch {sensorMessage.value=if(WearSync(app,repo).uploadPending())"Synced" else "Phone unavailable"}}
    fun quickAction(label:String){viewModelScope.launch {sensorMessage.value=if(WearSync(app,repo).quickAction(label))"$label saved to your phone timeline" else "Phone unavailable; quick action was not saved"}}
    fun wellbeingCheckIn(mood:String){viewModelScope.launch {sensorMessage.value=if(WearSync(app,repo).wellbeingCheckIn(mood))"Mood check-in saved to your phone" else "Phone unavailable; check-in was not saved"}}
    fun rightNowCheckIn(kind:String,value:String){viewModelScope.launch {sensorMessage.value=if(WearSync(app,repo).rightNowCheckIn(kind,value))"Check-in saved to your phone" else "Phone unavailable; check-in was not saved"}}
    fun startSamsungSensorCheck(){if(!stressChecks.startManualCheck())sensorMessage.value=samsungSensorCheck.value.message else sensorMessage.value="Stress check collecting"}
    fun cancelSamsungSensorCheck(){stressChecks.cancel();sensorMessage.value=samsungSensorCheck.value.message}
    fun stress(now:Long=System.currentTimeMillis()):StressEstimate {val h=heart.value.filter {now-it.observedAt<=30*60_000L}.map {it.value};val recentSteps=steps.value.filter {now-it.observedAt<=10*60_000L};val moving=recentSteps.zipWithNext().any {(a,b)->a.value!=b.value};return StressEngine().estimate(h,snapshot.value.restingHeartRate.value,moving)}
    fun installIntent():Intent {cachedUpdate.value=repo.hasVerifiedReadyUpdate();return repo.installIntent()}
    fun installerLaunched(){val current=repo.update.value;val version=current.version.ifBlank {repo.readyUpdateVersion()};repo.update(current.copy(stage=TransferStage.AWAITING_CONFIRMATION,version=version,progress=100,message="Android installer opened. Confirm the update there, or tap Try install again."));viewModelScope.launch {val node=getApplication<JamesWearApplication>().getSharedPreferences("wear-update",0).getString("node","").orEmpty();if(node.isNotBlank())WearSync(app,repo).updateStatus(node,TransferStage.AWAITING_CONFIRMATION,version,100,"Waiting for Android confirmation")}}
    fun installerLaunchFailed(error:Throwable){val version=repo.readyUpdateVersion().ifBlank {repo.update.value.version};repo.update(UpdateState(TransferStage.READY,version,100,"Could not open Android installer. Tap Try install again."));cachedUpdate.value=repo.hasVerifiedReadyUpdate()}
    fun dismissUpdate(){repo.update(UpdateState());cachedUpdate.value=repo.hasVerifiedReadyUpdate()}
    override fun onCleared(){super.onCleared()}
}
