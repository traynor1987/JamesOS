package uk.co.james.wear.health

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint as SamsungDataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt
import uk.co.james.wear.data.SensorSettings

data class SensorCapabilities(val heartRate:Boolean=false,val steps:Boolean=false,val calories:Boolean=false,val distance:Boolean=false,val ibi:Boolean=false,val ppg:Boolean=false,val eda:Boolean=false,val skinTemperature:Boolean=false)
interface SensorProvider {suspend fun capabilities():SensorCapabilities}
data class SamsungSensorStatus(
    val state:String="Connecting",
    val serviceVersion:String?=null,
    val supportedTrackers:Set<String> = emptySet(),
    val message:String="Connecting to Samsung Health Sensor Service…"
) {
    val heartRate get() = supportedTrackers.any { it == "HEART_RATE" || it == "HEART_RATE_CONTINUOUS" }
    val ibi get() = heartRate
    val ppg get() = supportedTrackers.any { it.startsWith("PPG_") }
    val eda get() = "EDA_CONTINUOUS" in supportedTrackers
    val skinTemperature get() = supportedTrackers.any { it.startsWith("SKIN_TEMPERATURE") }
    val summary get() = if(supportedTrackers.isEmpty()) "None detected" else supportedTrackers.sorted().joinToString(", ")
    /** Compact, truthful groups for a small round diagnostics display. */
    val diagnosticGroups:List<String> get() = buildList {
        if(heartRate) add("Heart rate · standard and continuous")
        if(eda||ppg) add("Stress signals · "+listOfNotNull(if(eda)"EDA" else null,if(ppg)"PPG" else null).joinToString(" + "))
        if(skinTemperature) add("Temperature · skin sensor")
        if(supportedTrackers.any {it=="SPO2"||it=="SPO2_ON_DEMAND"}) add("Oxygen · SpO₂")
        if(supportedTrackers.any {it.startsWith("ACCELEROMETER")}) add("Movement · accelerometer")
        if(supportedTrackers.any {it.startsWith("BIA")||it.startsWith("MF_BIA")}) add("Body composition · on demand")
        if(supportedTrackers.any {it=="ECG"||it=="ECG_ON_DEMAND"}) add("ECG · on demand")
        if("SWEAT_LOSS" in supportedTrackers) add("Hydration · sweat loss")
    }
}
interface SamsungSensorProvider:SensorProvider {
    val status:StateFlow<SamsungSensorStatus>
    val sensorCheck:StateFlow<SamsungSensorCheckState>
    fun connect()
    fun disconnect()
    /** Starts an explicitly requested, short high-detail reading. It never runs all day. */
    fun startSensorCheck(settings:SensorSettings=SensorSettings(),durationMillis:Long=45_000L):Boolean
    fun cancelSensorCheck()
}

data class SamsungSensorCheck(
    val measuredAt:Long,
    val averageHeartRate:Double?,
    val hrvRmssd:Double?,
    val skinConductance:Double?,
    val skinTemperature:Double?,
    val heartSamples:Int
)

data class SamsungSensorCheckState(
    val phase:String="Idle",
    val message:String="Run a short sensor check when you want a higher-detail stress reading.",
    val check:SamsungSensorCheck?=null,
    val startedAt:Long=0L,
    val expectedDurationMillis:Long=0L,
    val heartSamples:Int=0
) { val collecting get()=phase=="Collecting" }

/** Health Services remains the passive source of truth; this private Samsung
 * enhancement only connects and detects capability until a future opt-in
 * high-detail collection session needs a tracker. */
class SamsungHealthSensorProvider(private val context:Context):SamsungSensorProvider,ConnectionListener {
    private val _status=MutableStateFlow(SamsungSensorStatus())
    override val status:StateFlow<SamsungSensorStatus> = _status
    private val _sensorCheck=MutableStateFlow(SamsungSensorCheckState())
    override val sensorCheck:StateFlow<SamsungSensorCheckState> = _sensorCheck
    private val service=HealthTrackingService(this,context.applicationContext)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private var sensorJob:Job?=null
    private val activeTrackers=mutableListOf<HealthTracker>()
    private val heartRates=mutableListOf<Double>()
    private val ibi=mutableListOf<Double>()
    private val eda=mutableListOf<Double>()
    private val temperatures=mutableListOf<Double>()
    private var lastProgressAt=0L
    override fun connect() {
        _status.value=SamsungSensorStatus(state="Connecting",message="Connecting to Samsung Health Sensor Service…")
        runCatching { service.connectService() }.onFailure {
            _status.value=SamsungSensorStatus(state="Unavailable",message=it.message?:"Samsung Health Sensor Service is unavailable.")
        }
    }
    override fun onConnectionSuccess() {
        runCatching {
            val trackers=service.getTrackingCapability().getSupportHealthTrackerTypes().map { it.name }.toSet()
            SamsungSensorStatus("Connected","API 1.4.1",trackers,if(trackers.isEmpty()) "Service connected, but this watch reported no supported tracker types." else "Capabilities detected from this watch.")
        }.onSuccess {_status.value=it}.onFailure {
            _status.value=SamsungSensorStatus(state="Unavailable",message=it.message?:"Could not read Samsung sensor capabilities.")
        }
    }
    override fun onConnectionEnded() {_status.value=_status.value.copy(state="Disconnected",message="Samsung Health Sensor Service disconnected.")}
    override fun onConnectionFailed(exception:HealthTrackerException) {_status.value=SamsungSensorStatus(state="Unavailable",message=exception.message?:"Samsung Health Sensor Service rejected the connection.")}
    override suspend fun capabilities():SensorCapabilities {val d=status.value;return SensorCapabilities(heartRate=d.heartRate,ibi=d.ibi,ppg=d.ppg,eda=d.eda,skinTemperature=d.skinTemperature)}
    override fun startSensorCheck(settings:SensorSettings,durationMillis:Long):Boolean {
        val current=status.value
        if(current.state!="Connected") {
            _sensorCheck.value=SamsungSensorCheckState("Unavailable",current.message)
            return false
        }
        if(sensorJob?.isActive==true) return false
        val available=current.supportedTrackers
        val wantsHeart=settings.detailHeart||settings.hrv
        val wantsEda=settings.eda
        val wantsTemperature=settings.skinTemperature
        if(!wantsHeart&&!wantsEda&&!wantsTemperature) {
            _sensorCheck.value=SamsungSensorCheckState("Unavailable","All Check now readings are turned off in Settings.")
            return false
        }
        if((wantsHeart&&available.none {it in setOf("HEART_RATE","HEART_RATE_CONTINUOUS")})&&(!wantsEda||"EDA_CONTINUOUS" !in available)&&(!wantsTemperature||"SKIN_TEMPERATURE_CONTINUOUS" !in available)) {
            _sensorCheck.value=SamsungSensorCheckState("Unavailable","This watch did not report a signal suitable for a James sensor check.")
            return false
        }
        heartRates.clear();ibi.clear();eda.clear();temperatures.clear();activeTrackers.clear()
        val startedAt=System.currentTimeMillis()
        lastProgressAt=startedAt
        _sensorCheck.value=SamsungSensorCheckState("Collecting","Collecting a 45-second private sensor reading…",startedAt=startedAt,expectedDurationMillis=durationMillis)
        runCatching {
            if(wantsHeart&&"HEART_RATE_CONTINUOUS" in available) listen(HealthTrackerType.HEART_RATE_CONTINUOUS,::readHeart)
            else if(wantsHeart&&"HEART_RATE" in available) listen(HealthTrackerType.HEART_RATE,::readHeart)
            if(wantsEda&&"EDA_CONTINUOUS" in available) listen(HealthTrackerType.EDA_CONTINUOUS,::readEda)
            if(wantsTemperature&&"SKIN_TEMPERATURE_CONTINUOUS" in available) listen(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,::readTemperature)
        }.onFailure {
            stopListening()
            _sensorCheck.value=SamsungSensorCheckState("Failed",it.message?:"James could not start the Samsung sensor check.")
            return false
        }
        sensorJob=scope.launch {
            delay(durationMillis)
            stopListening()
            val check=SamsungSensorCheck(
                measuredAt=System.currentTimeMillis(),
                averageHeartRate=if(settings.detailHeart)heartRates.averageOrNull() else null,
                hrvRmssd=if(settings.hrv)rmssd(ibi) else null,
                skinConductance=if(settings.eda)eda.averageOrNull() else null,
                skinTemperature=if(settings.skinTemperature)temperatures.averageOrNull() else null,
                heartSamples=heartRates.size
            )
            _sensorCheck.value=SamsungSensorCheckState(
                if(check.averageHeartRate==null&&check.hrvRmssd==null&&check.skinConductance==null&&check.skinTemperature==null) "Unavailable" else "Complete",
                if(check.averageHeartRate==null&&check.hrvRmssd==null&&check.skinConductance==null&&check.skinTemperature==null) "No usable reading arrived. Check the watch fit and try again." else "Derived readings saved privately to James OS.",
                check,
                startedAt=startedAt,
                expectedDurationMillis=durationMillis,
                heartSamples=heartRates.size
            )
        }
        return true
    }
    override fun cancelSensorCheck() {if(sensorJob?.isActive==true){sensorJob?.cancel();stopListening();_sensorCheck.value=SamsungSensorCheckState("Cancelled","Sensor check cancelled. Nothing was saved.")}}
    private fun listen(type:HealthTrackerType,onData:(List<SamsungDataPoint>)->Unit) {
        val tracker=service.getHealthTracker(type)
        tracker.setEventListener(object:HealthTracker.TrackerEventListener {
            override fun onDataReceived(dataPoints:MutableList<SamsungDataPoint>) {onData(dataPoints)}
            override fun onFlushCompleted()=Unit
            override fun onError(error:HealthTracker.TrackerError) {
                if(sensorJob?.isActive==true) {
                    sensorJob?.cancel()
                    stopListening()
                    _sensorCheck.value=SamsungSensorCheckState("Failed","Samsung sensor error: ${error.name}")
                }
            }
        })
        activeTrackers.add(tracker)
    }
    private fun readHeart(points:List<SamsungDataPoint>) {points.forEach {point->
        number(point,ValueKey.HeartRateSet.HEART_RATE)?.takeIf {it in 25.0..240.0}?.let {heartRates.add(it)}
        runCatching {point.getValue(ValueKey.HeartRateSet.IBI_LIST)}.getOrNull().asNumbers().filter {it in 250.0..2_500.0}.let {ibi.addAll(it)}
    };publishProgress()}

    /** Progress is emitted only from real incoming samples and throttled so the
     * Data Layer is not flooded during a 45-second collection. */
    private fun publishProgress() {
        val current=_sensorCheck.value
        val now=System.currentTimeMillis()
        if(current.collecting&&now-lastProgressAt>=5_000L) {
            lastProgressAt=now
            _sensorCheck.value=current.copy(heartSamples=heartRates.size)
        }
    }
    private fun readEda(points:List<SamsungDataPoint>) {points.forEach {number(it,ValueKey.EdaSet.SKIN_CONDUCTANCE)?.takeIf {value->value>=0.0}?.let(eda::add)}}
    private fun readTemperature(points:List<SamsungDataPoint>) {points.forEach {number(it,ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)?.takeIf {value->value in 15.0..50.0}?.let(temperatures::add)}}
    private fun number(point:SamsungDataPoint,key:ValueKey<*>):Double?=runCatching {(point.getValue(key) as? Number)?.toDouble()}.getOrNull()
    private fun Any?.asNumbers():List<Double> = when(this) {is List<*>->mapNotNull {(it as? Number)?.toDouble()};is IntArray->map {it.toDouble()};is FloatArray->map {it.toDouble()};is DoubleArray->toList();else->emptyList()}
    private fun List<Double>.averageOrNull():Double? = takeIf {it.isNotEmpty()}?.average()
    private fun rmssd(values:List<Double>):Double? {if(values.size<2)return null;return sqrt(values.zipWithNext {a,b->(b-a)*(b-a)}.average())}
    private fun stopListening(){activeTrackers.forEach {runCatching {it.unsetEventListener()}};activeTrackers.clear()}
    override fun disconnect() {sensorJob?.cancel();stopListening();scope.cancel();runCatching {service.disconnectService()}}
}
class HealthServicesProvider(private val context:Context):SensorProvider {
    private val client=HealthServices.getClient(context).passiveMonitoringClient
    override suspend fun capabilities():SensorCapabilities {
        val supported=client.getCapabilitiesAsync().await().supportedDataTypesPassiveMonitoring
        return SensorCapabilities(DataType.HEART_RATE_BPM in supported,DataType.STEPS_DAILY in supported,DataType.CALORIES_DAILY in supported,DataType.DISTANCE_DAILY in supported)
    }
    suspend fun register(settings:SensorSettings=SensorSettings()):SensorCapabilities {
        val caps=capabilities();val types=buildSet {
            val body=ContextCompat.checkSelfPermission(context,Manifest.permission.BODY_SENSORS)==PackageManager.PERMISSION_GRANTED
            val activity=ContextCompat.checkSelfPermission(context,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED
            if(settings.passiveHeart&&caps.heartRate&&body)add(DataType.HEART_RATE_BPM)
            if(settings.steps&&caps.steps&&activity)add(DataType.STEPS_DAILY)
            if(settings.calories&&caps.calories&&activity)add(DataType.CALORIES_DAILY)
            if(settings.distance&&caps.distance&&activity)add(DataType.DISTANCE_DAILY)
        }
        require(types.isNotEmpty()){ "Grant heart or activity permission to enable passive monitoring." }
        client.setPassiveListenerServiceAsync(JamesPassiveDataService::class.java,PassiveListenerConfig(types,true,emptySet(),emptySet())).await()
        return caps
    }
    suspend fun unregister(){client.clearPassiveListenerServiceAsync().await()}
}

data class StressEstimate(val score:Int?=null,val label:String="Awaiting signals",val confidence:String="Unavailable",val reasons:List<String> = emptyList())
class StressEngine {
    fun estimate(recentHr:List<Double>,restingHr:Double?,moving:Boolean):StressEstimate {
        if(recentHr.size<3||restingHr==null)return StressEstimate(reasons=listOf("James needs several recent heart-rate readings and a resting-heart-rate baseline."))
        val average=recentHr.average();val elevation=(average-restingHr).coerceAtLeast(0.0)
        val score=((elevation/35.0)*100.0-(if(moving)25 else 0)).toInt().coerceIn(0,100)
        return StressEstimate(score,when{score>=70->"Elevated";score>=40->"Moderate";else->"Low"},"Experimental",listOf("Recent heart rate averaged ${average.toInt()} bpm against a ${restingHr.toInt()} bpm resting baseline.",if(moving)"Movement context reduced the estimate so ordinary activity is not labelled as stress." else "No recent movement was available to explain the elevation.","This is an experimental wellbeing estimate, not a diagnosis."))
    }
    /** A short, user-requested Samsung check can add HRV context. EDA and skin
     * temperature are retained as readings but need a personal baseline before
     * they can responsibly move the score. */
    fun fromSensorCheck(check:SamsungSensorCheck,restingHr:Double?,moving:Boolean):StressEstimate {
        val heart=check.averageHeartRate
        if(heart==null&&check.hrvRmssd==null)return StressEstimate(reasons=listOf("The sensor check did not receive enough usable readings."))
        val heartLoad=if(heart!=null&&restingHr!=null) ((heart-restingHr).coerceAtLeast(0.0)/35.0*65.0) else 0.0
        val hrvLoad=when(check.hrvRmssd) {null->0.0;in 0.0..<20.0->25.0;in 20.0..<35.0->12.0;else->0.0}
        val score=(heartLoad+hrvLoad-(if(moving)18 else 0)).toInt().coerceIn(0,100)
        val evidence=listOfNotNull(
            heart?.let {"Sensor-check heart rate averaged ${it.toInt()} bpm."},
            restingHr?.let {"Resting baseline is ${it.toInt()} bpm."},
            check.hrvRmssd?.let {"HRV was ${"%.0f".format(it)} ms (RMSSD)."},
            check.skinConductance?.let {"Skin conductance was recorded for your future baseline."},
            check.skinTemperature?.let {"Skin temperature was recorded for your future baseline."},
            if(moving) "Movement context reduced the estimate." else null,
            "Experimental wellbeing estimate from a short sensor check, not a diagnosis."
        )
        return StressEstimate(score,when{score>=70->"Elevated";score>=40->"Moderate";else->"Low"},"Experimental · sensor check",evidence)
    }
}
