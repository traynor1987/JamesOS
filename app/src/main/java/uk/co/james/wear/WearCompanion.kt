package uk.co.james.wear

import android.content.Context
import android.content.pm.PackageManager
import com.google.android.gms.wearable.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import uk.co.james.BuildConfig
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.updates.UpdateCredentials
import uk.co.james.updates.UpdatePolicy

data class WearStatus(val connected:Boolean=false,val device:String="",val nodeId:String="",val watchVersion:String="",val watchVersionCode:Long=0,val lastSeen:Long=0,val passiveRegistered:Boolean=false,val pending:Int=0,val updateStage:String="IDLE",val updateProgress:Int=0,val updateMessage:String="",val updateVersion:String="")
data class WearRelease(val code:Long,val version:String,val asset:String,val checksum:String,val size:Long)
data class StressCheckStatus(
    val requestId:String="",
    val stage:String="IDLE",
    val message:String="",
    val updatedAt:Long=0L,
    val requestedAt:Long=0L,
    val startedAt:Long=0L,
    val expectedDurationMillis:Long=0L,
    val sampleCount:Int=0,
    val acknowledgedAt:Long=0L,
    val measurementCompletedAt:Long=0L,
    val stressPersistedAt:Long=0L,
    val anxietyRecalculatedAt:Long=0L,
    val previousStress:Double?=null,
    val previousAnxiety:Int?=null,
    val currentStress:Double?=null,
    val currentAnxiety:Int?=null
) {
    val active get()=stage in setOf("REQUESTED","REQUEST_RECEIVED","INITIALIZING","MEASURING","PROCESSING","SENSOR_DATA_SENT","RECALCULATING")
    /** Compatibility for existing UI observers; active includes the full remote lifecycle. */
    val collecting get()=active
    val terminal get()=stage in setOf("COMPLETE","FAILED","SENSOR_UNAVAILABLE","WATCH_DISCONNECTED","TIMED_OUT","CANCELLED")
}

internal fun qualifiesStressCheckResult(
    active:StressCheckStatus,
    resultRequestId:String,
    protocolVersion:Int,
    echoedRequestedAt:Long,
    measurementCompletedAt:Long,
    stressObservedAt:Long?
):Boolean = active.active&&active.requestId.isNotBlank()&&active.requestId==resultRequestId&&
    protocolVersion==CompanionContract.STRESS_CHECK_PROTOCOL_VERSION&&echoedRequestedAt==active.requestedAt&&
    measurementCompletedAt>=active.requestedAt&&stressObservedAt!=null&&stressObservedAt>=active.requestedAt

class WearCompanion(private val context:Context) {
    val status=MutableStateFlow(load())
    val transfer=MutableStateFlow("")
    private val prefs=context.getSharedPreferences("wear-companion",Context.MODE_PRIVATE)
    val stressCheck=MutableStateFlow(loadStressCheck())
    private val checkScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.Default)
    /** Set only after the watch has durably stored the transfer control message. */
    private val controlReady=MutableStateFlow("")
    init {
        val restored=stressCheck.value
        if(restored.active&&restored.requestId.isBlank()) {
            setStressCheck(restored.copy(stage="FAILED",message="The previous uncorrelated sensor request cannot be resumed. Tap refresh to retry.",updatedAt=System.currentTimeMillis()))
        } else if(restored.active) scheduleTimeout(restored.requestId,restored.requestedAt)
    }
    suspend fun refreshConnection():WearStatus {val nodes=runCatching {Wearable.getCapabilityClient(context).getCapability(CompanionContract.CAPABILITY,CapabilityClient.FILTER_REACHABLE).await().nodes}.getOrDefault(emptySet());val node=nodes.firstOrNull();val old=status.value;val next=old.copy(connected=node!=null,nodeId=node?.id?:old.nodeId,device=node?.displayName?:old.device);save(next);return next}
    suspend fun requestStressCheck(previousStress:Double?=null,previousAnxiety:Int?=null):Boolean {
        if(stressCheck.value.active)return false
        val status=refreshConnection()
        val node=Wearable.getNodeClient(context).connectedNodes.await().firstOrNull {it.id==status.nodeId}?:return false
        val requestedAt=System.currentTimeMillis()
        val requestId=java.util.UUID.randomUUID().toString()
        setStressCheck(StressCheckStatus(
            requestId=requestId,
            stage="REQUESTED",
            message="Requesting your watch…",
            updatedAt=requestedAt,
            requestedAt=requestedAt,
            previousStress=previousStress,
            previousAnxiety=previousAnxiety
        ))
        return runCatching {
            val payload=buildJsonObject {
                put("protocolVersion",CompanionContract.STRESS_CHECK_PROTOCOL_VERSION)
                put("requestId",requestId)
                put("requestedAt",requestedAt)
            }.toString().encodeToByteArray()
            Wearable.getMessageClient(context).sendMessage(node.id,CompanionContract.STRESS_CHECK_PATH,payload).await()
            scheduleTimeout(requestId,requestedAt)
            true
        }.getOrElse {
            setStressCheck(stressCheck.value.copy(stage="FAILED",message="The watch did not receive the request. Check its connection and try again.",updatedAt=System.currentTimeMillis()))
            false
        }
    }
    private fun scheduleTimeout(requestId:String,requestedAt:Long) {
        checkScope.launch {
            delay((75_000L-(System.currentTimeMillis()-requestedAt)).coerceAtLeast(0L))
            val current=stressCheck.value
            if(current.active&&current.requestId==requestId) {
                setStressCheck(current.copy(stage="TIMED_OUT",message="The watch did not return a valid correlated Stress result in time. Your previous reading is still available.",updatedAt=System.currentTimeMillis()))
            }
        }
    }
    suspend fun sendSensorSettings(v:uk.co.james.settings.WearSensorSettings):Boolean {val status=refreshConnection();val node=Wearable.getNodeClient(context).connectedNodes.await().firstOrNull {it.id==status.nodeId}?:return false;val payload=buildJsonObject {put("passiveHeart",v.passiveHeart);put("steps",v.steps);put("calories",v.calories);put("distance",v.distance);put("automaticStress",v.automaticStress);put("detailHeart",v.detailHeart);put("hrv",v.hrv);put("eda",v.eda);put("skinTemperature",v.skinTemperature)}.toString().encodeToByteArray();Wearable.getMessageClient(context).sendMessage(node.id,CompanionContract.SENSOR_SETTINGS_PATH,payload).await();return true}
    suspend fun publish(records:List<StoredRecord>) {if(!refreshConnection().connected)return;val request=PutDataMapRequest.create(CompanionContract.SNAPSHOT_PATH).apply {dataMap.putByteArray("payload",CompanionContract.snapshot(records).toString().encodeToByteArray());dataMap.putLong("nonce",System.currentTimeMillis())}.asPutDataRequest().setUrgent();Wearable.getDataClient(context).putDataItem(request).await();refreshConnection()}
    fun receiveStatus(nodeId:String,raw:String){runCatching {val data=json.parseToJsonElement(raw).jsonObject;save(status.value.copy(connected=true,nodeId=nodeId,device=data.text("device",status.value.device),watchVersion=data.text("watchVersion"),watchVersionCode=data.number("watchVersionCode").toLong(),lastSeen=System.currentTimeMillis(),passiveRegistered=data.flag("passiveRegistered"),pending=data.number("pending").toInt()))}}
    fun receiveStressCheck(raw:String):Boolean=runCatching {
        val d=json.parseToJsonElement(raw).jsonObject
        val previous=stressCheck.value
        val requestId=d.text("requestId")
        if(!previous.active||requestId.isBlank()||requestId!=previous.requestId)return@runCatching false
        val sentAt=d.number("sentAt").toLong()
        val stage=d.text("stage","IDLE")
        setStressCheck(previous.copy(
            stage=stage,
            message=d.text("message"),
            updatedAt=sentAt,
            startedAt=d.number("startedAt").toLong(),
            expectedDurationMillis=d.number("expectedDurationMillis").toLong(),
            sampleCount=d.number("sampleCount").toInt(),
            acknowledgedAt=if(stage=="REQUEST_RECEIVED"&&previous.acknowledgedAt==0L)sentAt else previous.acknowledgedAt,
            measurementCompletedAt=d.number("measurementCompletedAt",previous.measurementCompletedAt.toDouble()).toLong()
        ))
        true
    }.getOrDefault(false)

    /** Called only after PhoneWearDataService has durably saved valid watch readings. */
    fun sensorDataPersisted(requestId:String,stressObservedAt:Long):Boolean {
        val current=stressCheck.value
        if(!current.active||current.requestId!=requestId||stressObservedAt<current.requestedAt)return false
        val now=System.currentTimeMillis()
        setStressCheck(current.copy(stage="RECALCULATING",message="Recalculating Anxiety from fresh readings…",updatedAt=now,stressPersistedAt=now,measurementCompletedAt=maxOf(current.measurementCompletedAt,stressObservedAt)))
        return true
    }

    /** Phone is authoritative for Anxiety. This ends a remote refresh only after
     * the fresh stress record and the derived Anxiety result both exist locally. */
    fun completeStressCheck(requestId:String,currentStress:Double?,currentAnxiety:Int?):Boolean {
        val current=stressCheck.value
        if(current.requestId!=requestId||current.stage!="RECALCULATING")return false
        val now=System.currentTimeMillis()
        setStressCheck(current.copy(
            stage="COMPLETE",
            message="Stress and Anxiety updated.",
            updatedAt=now,
            anxietyRecalculatedAt=now,
            currentStress=currentStress,
            currentAnxiety=currentAnxiety
        ))
        return true
    }

    private fun setStressCheck(value:StressCheckStatus) {
        stressCheck.value=value
        prefs.edit().putString("stress-check",Json.encodeToString(JsonObject.serializer(),buildJsonObject {
            put("requestId",value.requestId);put("stage",value.stage);put("message",value.message);put("updatedAt",value.updatedAt);put("requestedAt",value.requestedAt)
            put("startedAt",value.startedAt);put("expectedDurationMillis",value.expectedDurationMillis);put("sampleCount",value.sampleCount)
            put("acknowledgedAt",value.acknowledgedAt);put("measurementCompletedAt",value.measurementCompletedAt)
            put("stressPersistedAt",value.stressPersistedAt);put("anxietyRecalculatedAt",value.anxietyRecalculatedAt)
            value.previousStress?.let {put("previousStress",it)};value.previousAnxiety?.let {put("previousAnxiety",it)}
            value.currentStress?.let {put("currentStress",it)};value.currentAnxiety?.let {put("currentAnxiety",it)}
        })).apply()
    }

    private fun loadStressCheck():StressCheckStatus=runCatching {
        val d=json.parseToJsonElement(prefs.getString("stress-check","{}")!!).jsonObject
        StressCheckStatus(
            requestId=d.text("requestId"),stage=d.text("stage","IDLE"),message=d.text("message"),updatedAt=d.number("updatedAt").toLong(),
            requestedAt=d.number("requestedAt").toLong(),startedAt=d.number("startedAt").toLong(),
            expectedDurationMillis=d.number("expectedDurationMillis").toLong(),sampleCount=d.number("sampleCount").toInt(),
            acknowledgedAt=d.number("acknowledgedAt").toLong(),measurementCompletedAt=d.number("measurementCompletedAt").toLong(),
            stressPersistedAt=d.number("stressPersistedAt").toLong(),anxietyRecalculatedAt=d.number("anxietyRecalculatedAt").toLong(),
            previousStress=d["previousStress"]?.jsonPrimitive?.doubleOrNull,previousAnxiety=d["previousAnxiety"]?.jsonPrimitive?.intOrNull,
            currentStress=d["currentStress"]?.jsonPrimitive?.doubleOrNull,currentAnxiety=d["currentAnxiety"]?.jsonPrimitive?.intOrNull
        )
    }.getOrDefault(StressCheckStatus())
    fun receiveUpdate(raw:String){runCatching {val d=json.parseToJsonElement(raw).jsonObject;val next=status.value.copy(connected=true,lastSeen=System.currentTimeMillis(),updateStage=d.text("stage","IDLE"),updateProgress=d.number("progress").toInt(),updateMessage=d.text("message"),updateVersion=d.text("version"));if(next.updateStage=="PREPARING"&&next.updateVersion.isNotBlank())controlReady.value=next.updateVersion;save(next);transfer.value=when(next.updateStage){"RECEIVING"->"Sending ${next.updateProgress}%";"VERIFYING"->"Verifying on watch";"READY"->"Ready to install on watch";"AWAITING_CONFIRMATION"->"Install confirmation waiting on watch";"FAILED"->next.updateMessage.ifBlank{"Transfer failed"};else->next.updateStage.lowercase().replaceFirstChar {it.uppercase()}}}}
    fun clearUpdateReady(){controlReady.value=""}
    suspend fun awaitUpdateReady(version:String):Boolean=withTimeoutOrNull(12_000){controlReady.first {it==version};true}?:false
    private fun save(value:WearStatus){status.value=value;prefs.edit().putString("state",Json.encodeToString(JsonObject.serializer(),buildJsonObject {put("connected",value.connected);put("device",value.device);put("nodeId",value.nodeId);put("watchVersion",value.watchVersion);put("watchVersionCode",value.watchVersionCode);put("lastSeen",value.lastSeen);put("passive",value.passiveRegistered);put("pending",value.pending);put("updateStage",value.updateStage);put("updateProgress",value.updateProgress);put("updateMessage",value.updateMessage);put("updateVersion",value.updateVersion)})).apply()}
    private fun load():WearStatus=runCatching {val d=json.parseToJsonElement(prefs.getString("state","{}")!!).jsonObject;WearStatus(false,d.text("device"),d.text("nodeId"),d.text("watchVersion"),d.number("watchVersionCode").toLong(),d.number("lastSeen").toLong(),d.flag("passive"),d.number("pending").toInt(),d.text("updateStage","IDLE"),d.number("updateProgress").toInt(),d.text("updateMessage"),d.text("updateVersion"))}.getOrDefault(WearStatus())
    companion object {fun age(value:Long):String {val min=((System.currentTimeMillis()-value)/60000).coerceAtLeast(0);return when{min<1->"Just now";min<60->"${min}m ago";min<1440->"${min/60}h ago";else->"${min/1440}d ago"}}}
}

class WearReleaseUpdater(private val context:Context,private val companion:WearCompanion) {
    private val credentials=UpdateCredentials(context)
    private fun connection(url:String,asset:Boolean=false):HttpURLConnection {
        val repo=UpdatePolicy.repository(BuildConfig.UPDATE_REPOSITORY)
        val token=credentials.read()
        var current=url
        repeat(6) {
            UpdatePolicy.https(current)
            val c=(URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects=false;connectTimeout=15000;readTimeout=30000
                setRequestProperty("User-Agent","James-Android")
                setRequestProperty("Accept",if(asset)"application/octet-stream" else "application/vnd.github+json")
                if(UpdatePolicy.canAuthorize(current,repo)&&token.isNotBlank()) {setRequestProperty("Authorization","Bearer $token");setRequestProperty("X-GitHub-Api-Version","2022-11-28")}
            }
            if(c.responseCode !in listOf(301,302,303,307,308))return c
            val next=c.getHeaderField("Location");c.disconnect();require(!next.isNullOrBlank()){ "Invalid update redirect." };current=URL(URL(current),next).toString()
        }
        error("Update server redirected too many times.")
    }
    private fun text(url:String,asset:Boolean=false):String {val c=connection(url,asset);try {require(c.responseCode==200){"Release service returned ${c.responseCode}."};return c.inputStream.bufferedReader().use {it.readText()}}finally{c.disconnect()}}
    suspend fun check():WearRelease?=withContext(Dispatchers.IO) {
        companion.transfer.value="Checking…";val repo=BuildConfig.UPDATE_REPOSITORY
        val release=json.parseToJsonElement(text("https://api.github.com/repos/$repo/releases/latest")).jsonObject
        fun asset(name:String):JsonObject = release.array("assets").map {it.jsonObject}.firstOrNull {it.text("name")==name} ?: error("Release is missing $name")
        fun url(row:JsonObject)=UpdatePolicy.asset(repo,row.number("id").toLong())
        val meta=json.parseToJsonElement(text(url(asset("james-wear-version.json")),true)).jsonObject
        val code=meta.number("versionCode").toLong()
        if(code<=companion.status.value.watchVersionCode){companion.transfer.value="Watch is up to date";return@withContext null}
        val apk=asset("james-wear.apk")
        val checksum=text(url(asset("james-wear.apk.sha256")),true).trim().split(Regex("\\s+")).first()
        require(checksum.matches(Regex("[0-9a-fA-F]{64}"))){"Invalid Wear checksum."}
        WearRelease(code,meta.text("versionName"),url(apk),checksum.lowercase(),apk.number("size").toLong())
    }
    suspend fun download(release:WearRelease,onProgress:(Int)->Unit):File=withContext(Dispatchers.IO) {
        val dir=File(context.cacheDir,"wear-updates").apply{mkdirs()};val part=File(dir,"wear.part");val target=File(dir,"james-wear.apk");val c=connection(release.asset,true)
        try {
            require(c.responseCode==200);val digest=MessageDigest.getInstance("SHA-256");var read=0L
            c.inputStream.use {input->part.outputStream().use {out->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;read+=n;require(read<=100L*1024*1024){"Wear APK is too large."};out.write(b,0,n);digest.update(b,0,n);if(release.size>0)onProgress((read*100/release.size).toInt())}}}
            require(digest.digest().joinToString(""){"%02x".format(it)}==release.checksum){"CHECKSUM FAILED"}
            val archive=context.packageManager.getPackageArchiveInfo(part.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)?:error("Invalid Wear APK")
            require(archive.packageName==context.packageName&&archive.longVersionCode==release.code){"Wear APK identity/version mismatch."}
            fun signer(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
            val phone=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty().map{signer(it.toByteArray())}.toSet()
            val incoming=archive.signingInfo?.apkContentsSigners.orEmpty().map{signer(it.toByteArray())}.toSet()
            require(phone.isNotEmpty()&&phone==incoming){"Wear APK signing certificate changed."}
            if(target.exists())target.delete();check(part.renameTo(target));target
        } catch(e:Exception){part.delete();throw e} finally {c.disconnect()}
    }
    suspend fun send(release:WearRelease,file:File,onProgress:(Int)->Unit)=withContext(Dispatchers.IO){val status=companion.refreshConnection();val node=Wearable.getNodeClient(context).connectedNodes.await().firstOrNull {it.id==status.nodeId}?:error("WATCH NOT CONNECTED");val control=buildJsonObject {put("schemaVersion",CompanionContract.SCHEMA_VERSION);put("version",release.version);put("versionCode",release.code);put("sha256",release.checksum);put("size",file.length())}.toString().encodeToByteArray();companion.clearUpdateReady();Wearable.getMessageClient(context).sendMessage(node.id,CompanionContract.UPDATE_CONTROL_PATH,control).await();companion.transfer.value="Waiting for watch to prepare";val acknowledged=companion.awaitUpdateReady(release.version);if(!acknowledged){companion.transfer.value="Older watch detected; allowing extra preparation time";delay(8_000)}else companion.transfer.value="Watch ready; sending update";val channel=Wearable.getChannelClient(context).openChannel(node.id,CompanionContract.UPDATE_CHANNEL_PATH).await();try {val output=Wearable.getChannelClient(context).getOutputStream(channel).await();file.inputStream().use {input->output.use {out->val b=ByteArray(65536);var sent=0L;while(true){val n=input.read(b);if(n<0)break;out.write(b,0,n);sent+=n;onProgress((sent*100/file.length()).toInt())};out.flush()}}}finally{Wearable.getChannelClient(context).close(channel).await()};companion.transfer.value="Waiting for watch verification"}
}
