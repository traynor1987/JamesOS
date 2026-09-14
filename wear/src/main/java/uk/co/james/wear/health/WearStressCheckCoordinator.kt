package uk.co.james.wear.health

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uk.co.james.wear.data.WearRepository
import uk.co.james.wear.sync.WearSync

/**
 * One owner for both a visible and a remotely requested Samsung sensor check.
 * The Wear screen observes this coordinator; it never has to be open for a
 * phone request to complete.
 */
class WearStressCheckCoordinator(
    private val context:Context,
    private val repository:WearRepository
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val provider:SamsungSensorProvider=SamsungHealthSensorProvider(context.applicationContext)
    val sensorCheck=provider.sensorCheck
    val status=provider.status
    private val starting=AtomicBoolean(false)
    private val _active=MutableStateFlow(false)
    val active:StateFlow<Boolean> = _active
    @Volatile private var remoteRequest=false
    @Volatile private var remoteRequestId=""
    @Volatile private var remoteRequestedAt=0L
    private var savedSensorCheckAt=0L
    private var lastProgressSamples=-1

    init {
        provider.connect()
        scope.launch {
            sensorCheck.collect { state->
                when(state.phase) {
                    "Collecting" -> if(remoteRequest&&state.heartSamples!=lastProgressSamples) {
                        lastProgressSamples=state.heartSamples
                        report("MEASURING","Measuring on your watch…",state)
                    }
                    "Complete" -> {
                        val check=state.check
                        if(check!=null&&check.measuredAt!=savedSensorCheckAt) {
                            savedSensorCheckAt=check.measuredAt
                            saveAndSend(check,state)
                        }
                    }
                    "Failed","Unavailable","Cancelled" -> finishFailure(state.phase.uppercase(),state.message,state)
                }
            }
        }
    }

    /** Called by the Wear Data Layer listener after the phone requests a check. */
    fun startRemoteCheck(requestId:String,requestedAt:Long):Boolean=startCheck(remote=true,requestId=requestId,requestedAt=requestedAt)

    /** Existing on-watch action; it shares the exact same provider and state. */
    fun startManualCheck():Boolean=startCheck(remote=false)

    private fun startCheck(remote:Boolean,requestId:String="",requestedAt:Long=0L):Boolean {
        if(!starting.compareAndSet(false,true)) {
            if(remote) scope.launch { report("CHECK_ALREADY_IN_PROGRESS","A sensor check is already in progress.",sensorCheck.value,requestId,requestedAt) }
            return false
        }
        remoteRequest=remote
        remoteRequestId=if(remote)requestId else ""
        remoteRequestedAt=if(remote)requestedAt else 0L
        _active.value=true
        scope.launch {
            val initial=sensorCheck.value
            if(remote) report("REQUEST_RECEIVED","Watch request received.",initial)
            if(status.value.state!="Connected") provider.connect()
            val connected=withTimeoutOrNull(12_000L) {
                status.first { it.state!="Connecting" }
            }
            if(connected?.state!="Connected") {
                finishFailure("SENSOR_UNAVAILABLE",connected?.message?:"Samsung Health Sensor Service did not become ready.",sensorCheck.value)
                return@launch
            }
            if(remote) report("INITIALIZING","Preparing the Samsung sensor check…",sensorCheck.value)
            if(!provider.startSensorCheck(repository.sensorSettings.value)) {
                val state=sensorCheck.value
                finishFailure(
                    when(state.phase) {
                        "Unavailable" -> "SENSOR_UNAVAILABLE"
                        "Failed" -> "FAILED"
                        else -> "FAILED"
                    },
                    state.message,
                    state
                )
            }
        }
        return true
    }

    private suspend fun saveAndSend(check:SamsungSensorCheck,state:SamsungSensorCheckState) {
        try {
            if(remoteRequest) report("PROCESSING","Processing valid sensor readings…",state)
            check.averageHeartRate?.let {repository.observe("heart_rate",it,"bpm",check.measuredAt)}
            check.hrvRmssd?.let {repository.observe("samsung_hrv",it,"ms",check.measuredAt)}
            check.skinConductance?.let {repository.observe("samsung_eda",it,"µS",check.measuredAt)}
            check.skinTemperature?.let {repository.observe("samsung_skin_temperature",it,"°C",check.measuredAt)}
            val recentSteps=repository.dao.recent("steps",3).first().filter {check.measuredAt-it.observedAt<=10*60_000L}
            val moving=recentSteps.zipWithNext().any {(a,b)->a.value!=b.value}
            val resting=repository.snapshot.first().restingHeartRate.value
            val stress=StressEngine().fromSensorCheck(check,resting,moving)
            stress.score?.let {repository.observe("james_stress",it.toDouble(),"/100",check.measuredAt)}
            val completingRequestId=remoteRequestId
            val completingRequestedAt=remoteRequestedAt
            val sent=WearSync(context,repository).uploadPending(
                requestId=completingRequestId.takeIf {remoteRequest},
                requestedAt=completingRequestedAt,
                measurementCompletedAt=check.measuredAt
            )
            if(sent) {
                if(remoteRequest) report("SENSOR_DATA_SENT","Fresh readings sent to your phone for Anxiety recalculation.",state)
                remoteRequest=false
                remoteRequestId=""
                remoteRequestedAt=0L
                starting.set(false)
                _active.value=false
                lastProgressSamples=-1
            } else {
                finishFailure("WATCH_DISCONNECTED","The check completed, but the phone disconnected before receiving the fresh readings.",state)
            }
        } catch(error:Exception) {
            finishFailure("FAILED",error.message?:"James could not process the sensor check.",state)
        }
    }

    private suspend fun report(stage:String,message:String,state:SamsungSensorCheckState,requestId:String=remoteRequestId,requestedAt:Long=remoteRequestedAt) {
        if(requestId.isBlank())return
        WearSync(context,repository).stressCheckStatus(
            requestId=requestId,
            requestedAt=requestedAt,
            stage=stage,
            message=message,
            startedAt=state.startedAt,
            measurementCompletedAt=state.check?.measuredAt?:0L,
            expectedDurationMillis=state.expectedDurationMillis,
            sampleCount=state.heartSamples
        )
    }

    private suspend fun finishFailure(stage:String,message:String,state:SamsungSensorCheckState) {
        if(remoteRequest) report(stage,message,state)
        remoteRequest=false
        remoteRequestId=""
        remoteRequestedAt=0L
        starting.set(false)
        _active.value=false
        lastProgressSamples=-1
    }

    fun cancel() {
        provider.cancelSensorCheck()
        remoteRequest=false
        remoteRequestId=""
        remoteRequestedAt=0L
        starting.set(false)
        _active.value=false
        lastProgressSamples=-1
    }

    /** Foreground service owns its own lifetime; the application owns hardware cleanup. */
    fun release(){provider.disconnect();scope.cancel()}
}
