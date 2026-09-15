package uk.co.james.whoop

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.state.bodyBattery
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.*

data class WhoopSyncResult(
    val apiRequestSucceeded:Boolean,
    val recordsReceived:Int,
    val currentCycleReceived:Boolean,
    val currentCycleId:String?,
    val currentCycleStrain:Double?,
    val currentCycleAccepted:Boolean,
    val currentStrainAccepted:Boolean,
    val bodyBatteryConsumedCurrentStrain:Boolean,
    val bodyBatteryValue:Int?,
    val summary:String
)

class WhoopSource(context: Context, private val repo: JamesRepository) {
    private val credentials = WhoopCredentials(context)
    private val mutex = Mutex()
    companion object {
        const val ORIGIN = "https://james-os-privacy.traynor1987.chatgpt.site"
    }
    fun configured(): Boolean = credentials.configured()

    suspend fun connectUrl(): String = withContext(Dispatchers.IO) {
        var key = credentials.read()
        if (key.isBlank()) {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            credentials.save(key)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }
        "$ORIGIN/whoop/connect?device=$hash"
    }

    private fun request(path: String, method: String = "GET"): JsonObject {
        val key = credentials.read()
        check(key.isNotBlank()) { "Connect WHOOP first." }
        val connection = URL(ORIGIN + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (method == "POST") {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= 2 * 1024 * 1024) { "WHOOP response too large." }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: byteArrayOf()
            val body = runCatching { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
            if (code !in 200..299) {
                throw WhoopException(code, body?.text("error")?.takeIf { it.isNotBlank() } ?: "WHOOP unavailable ($code). Try again later.")
            }
            return body ?: error("WHOOP returned an unreadable response.")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun sync():WhoopSyncResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val end = Instant.now()
                val previousSync=repo.dao.get("personalRecords","source:whoop")?.data()?.text("lastSync").orEmpty()
                val start=if(runCatching {Instant.parse(previousSync)}.isSuccess)end.minus(Duration.ofDays(2))else end.minus(Duration.ofDays(28))
                var count = 0
                val incoming=mutableListOf<JsonObject>()
                val domainWatermarks=mutableMapOf<String,String>()
                var newestCycleStart:Instant?=null
                var newestCycleId:String?=null
                var newestCycleStrain:Double?=null
                for (type in listOf("sleep", "recovery", "cycle", "workout")) {
                    var token = ""
                    val seen = mutableSetOf<String>()
                    var pages = 0
                    do {
                        pages += 1
                        check(pages <= 100) { "WHOOP history exceeded the page limit." }
                        val builder = Uri.Builder().appendQueryParameter("type", type)
                            .appendQueryParameter("start", start.toString()).appendQueryParameter("end", end.toString())
                        if (token.isNotBlank()) builder.appendQueryParameter("nextToken", token)
                        val query = builder.build().encodedQuery
                        val data = request("/api/whoop/data?$query")
                        for (raw in data.array("records")) {
                            if (raw is JsonObject) {
                                val watermark=raw.text("updated_at").takeIf(::validTime)?:raw.text("end").takeIf(::validTime)?:raw.text("start").takeIf(::validTime)
                                if(watermark!=null && (domainWatermarks[type].isNullOrBlank()||watermark>domainWatermarks[type]!!))domainWatermarks[type]=watermark
                                if(type=="cycle") {
                                    val cycleStart=raw.text("start").takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}
                                    if(cycleStart!=null&&(newestCycleStart==null||cycleStart>newestCycleStart)) {
                                        newestCycleStart=cycleStart
                                        newestCycleId=raw.text("id").takeIf {it.isNotBlank()}
                                        newestCycleStrain=raw.takeIf {it.text("score_state")=="SCORED"}?.obj("score")
                                            ?.number("strain",Double.NaN)?.takeIf {it.isFinite()&&it in 0.0..21.0}
                                    }
                                }
                                incoming+=WhoopMapper.records(type, raw)
                                count += 1
                            }
                        }
                        token = (data["next_token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        check(token.isBlank() || seen.add(token)) { "WHOOP repeated a history page." }
                    } while (token.isNotBlank())
                }
                repo.externalBatch(incoming)
                // Reconstruct a single bounded set of Part 2 sleep-detail rows
                // from retained raw evidence. This has no network effect; normal
                // incoming rows already carry their detail mapper result.
                repo.backfillWhoopSleepDetails(end)
                // Reconcile immediately from the just-persisted rows. This makes
                // manual, foreground and WorkManager syncs use the same pipeline.
                val battery=bodyBattery(repo.stateInputs(end),end,trigger="whoop_sync")
                repo.persistBodyBattery(battery)
                val diagnostic=battery.strainDiagnostics
                val cycleAccepted=newestCycleId!=null&&diagnostic?.accepted==true&&diagnostic.whoopCycleId==newestCycleId
                val strainAccepted=cycleAccepted&&newestCycleStrain!=null&&
                    kotlin.math.abs((diagnostic.rawWhoopStrain?:Double.NaN)-newestCycleStrain)<0.0001
                val consumed=strainAccepted&&diagnostic?.currentDayPending==false
                val completed=now()
                val summary=when {
                    strainAccepted -> "WHOOP synced · current Strain ${String.format(java.util.Locale.UK,"%.1f",newestCycleStrain)} accepted by Body Battery."
                    newestCycleId!=null -> "WHOOP API synced · current cycle received; Strain is pending James Day ownership."
                    else -> "WHOOP API synced · current cycle not received."
                }
                val previous=repo.dao.get("personalRecords","source:whoop")?.data()?:fields()
                repo.external(personal("ExternalSource",previous.changed(
                    "title" to p("WHOOP API"),"status" to p("synced"),
                    "lastAttempt" to p(completed),"lastSuccess" to p(completed),"lastSync" to p(completed),
                    "records" to p(count),"watermarks" to JsonObject(domainWatermarks.mapValues {(_,stamp)->p(stamp)}),"error" to p(""),
                    "apiRequestSucceeded" to p(true),
                    "currentCycleReceived" to p(newestCycleId!=null),
                    "currentCycleId" to p(newestCycleId?:""),
                    "currentCycleStart" to p(newestCycleStart?.toString()?:""),
                    "currentCycleStrain" to (newestCycleStrain?.let(::p)?:JsonNull),
                    "currentCycleAccepted" to p(cycleAccepted),
                    "currentStrainAccepted" to p(strainAccepted),
                    "bodyBatteryConsumedCurrentStrain" to p(consumed),
                    "bodyBatteryValue" to (battery.value?.let(::p)?:JsonNull),
                    "syncSummary" to p(summary)
                ),"source:whoop","whoop"))
                WhoopSyncResult(true,count,newestCycleId!=null,newestCycleId,newestCycleStrain,cycleAccepted,strainAccepted,consumed,battery.value,summary)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val previous = repo.dao.get("personalRecords", "source:whoop")?.data() ?: fields()
                repo.external(personal("ExternalSource", previous.changed("title" to p("WHOOP API"), "status" to p("error"), "lastAttempt" to p(now()), "apiRequestSucceeded" to p(false), "error" to p(e.message ?: "Sync failed")), "source:whoop", "whoop"))
                throw e
            }
        }
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                request("/api/whoop/disconnect", "POST")
            } catch (e: WhoopException) {
                if (e.code != 401) throw e
            }
            credentials.clear()
            val previous = repo.dao.get("personalRecords", "source:whoop")?.data() ?: fields()
            repo.external(personal("ExternalSource", previous.changed("title" to p("WHOOP API"), "status" to p("disconnected"), "error" to p("")), "source:whoop", "whoop"))
        }
    }
}
class WhoopException(val code: Int, message: String) : Exception(message)
