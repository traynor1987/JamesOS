package uk.co.james.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.*
import uk.co.james.core.*
import uk.co.james.data.JamesRepository
import uk.co.james.time.jamesDayWindow
import java.time.*
import kotlin.reflect.KClass
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HealthStatus(val available: Boolean,val granted: Set<String>,val explanation: String)
class HealthSource(private val context: Context,private val repo: JamesRepository) {
    private val syncMutex=Mutex()
    val types: Map<String,KClass<out Record>> = HealthCapabilities.byLabel.mapValues {it.value.type}
    fun permissions(selected: Set<String>): Set<String> = selected.mapNotNull { HealthCapabilities.byLabel[it]?.readPermission }.toSet()
    private fun client() = HealthConnectClient.getOrCreate(context)
    suspend fun status(): HealthStatus {
        val status=HealthConnectClient.getSdkStatus(context)
        if(status!=HealthConnectClient.SDK_AVAILABLE)return HealthStatus(false,emptySet(),if(status==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)"Install or update Health Connect in Android settings." else "Health Connect is unavailable on this device.")
        return HealthStatus(true,client().permissionController.getGrantedPermissions(),"Ready for your chosen permissions.")
    }
    suspend fun disconnect() {if(status().available)client().permissionController.revokeAllPermissions()}
    suspend fun sync()=syncMutex.withLock {
        val granted=status();require(granted.available && granted.granted.isNotEmpty()) { "Grant at least one health permission first." }
        val until=Instant.now()
        val previousSync=repo.dao.get("personalRecords","source:health_connect")?.data()?.text("lastSync").orEmpty()
        val since=if(runCatching {Instant.parse(previousSync)}.isSuccess)until.minus(Duration.ofDays(2))else until.minus(Duration.ofDays(28))
        val incoming=mutableListOf<JsonObject>()
        suspend fun <T: Record> read(type: KClass<T>, map: (T)->JsonObject?) {
            if(HealthPermission.getReadPermission(type) !in granted.granted)return
            var token: String?=null
            do { val result=client().readRecords(ReadRecordsRequest(type,TimeRangeFilter.between(since,until),pageToken=token));result.records.forEach { value ->map(value)?.let(incoming::add) };token=result.pageToken } while(token!=null)
        }
        fun metric(record: Record,name: String,value: Double,unit: String,time: Instant,start: Instant?=null,end: Instant?=null): JsonObject {
            val provider=record.metadata.dataOrigin.packageName
            val data=fields("metric" to p(name),"value" to p(value),"unit" to p(unit),"provider" to p(provider),"title" to p("$name recorded"),"date" to p(dayOf(time.toString())))
                .let { if(start!=null && end!=null)it.changed("start" to p(start.toString()),"end" to p(end.toString())) else it }
            return personal("HealthMetric",data,"hc:$name:${record.metadata.id}","health_connect",time.toString()).changed("externalId" to p(record.metadata.id),"updatedAt" to p(record.metadata.lastModifiedTime.toString()),"metadata" to fields("dataOrigin" to p(provider)))
        }
        read(SleepSessionRecord::class) { metric(it,"Sleep",Duration.between(it.startTime,it.endTime).toMinutes().toDouble(),"min",it.endTime,it.startTime,it.endTime) }
        read(ExerciseSessionRecord::class) { metric(it,"Exercise",Duration.between(it.startTime,it.endTime).toMinutes().toDouble(),"min",it.endTime,it.startTime,it.endTime) }
        read(WeightRecord::class) {metric(it,"Weight",it.weight.inKilograms,"kg",it.time)}
        read(RestingHeartRateRecord::class) {metric(it,"Resting heart rate",it.beatsPerMinute.toDouble(),"bpm",it.time)}
        read(HeartRateVariabilityRmssdRecord::class) {metric(it,"HRV",it.heartRateVariabilityMillis,"ms",it.time)}
        read(OxygenSaturationRecord::class) {metric(it,"Blood oxygen",it.percentage.value,"%",it.time)}
        read(RespiratoryRateRecord::class) {metric(it,"Respiratory rate",it.rate,"rpm",it.time)}
        read(HeartRateRecord::class) { if(it.samples.isEmpty()) null else metric(it,"Heart rate",it.samples.map { s->s.beatsPerMinute.toDouble() }.average().takeIf { n->n.isFinite() }?:0.0,"bpm",it.endTime,it.startTime,it.endTime) }
        read(DistanceRecord::class) {metric(it,"Distance",it.distance.inMeters,"m",it.endTime,it.startTime,it.endTime)}
        read(TotalCaloriesBurnedRecord::class) {metric(it,"Calories",it.energy.inKilocalories,"kcal",it.endTime,it.startTime,it.endTime)}
        // Nutrition and hydration use NutritionHealthSource exclusively. Keeping them out of this generic path prevents duplicate Health Connect meals with different stable IDs.
        if(HealthPermission.getReadPermission(StepsRecord::class) in granted.granted) {
            // Health Connect aggregation applies the user's source priority; raw overlapping step rows are never summed.
            // Current-day ownership starts at the accepted main-sleep end, not midnight.
            val window=jamesDayWindow(repo.stateInputs(until),until,ZoneId.systemDefault())
            val start=window.start
            val result=client().aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(start,until)))
            result[StepsRecord.COUNT_TOTAL]?.let { steps -> incoming+=personal("HealthMetric",fields("metric" to p("Steps"),"value" to p(steps),"unit" to p("steps"),"date" to p(window.displayDate),"start" to p(start.toString()),"end" to p(until.toString()),"jamesDayId" to p(window.id),"title" to p("Steps updated"),"provider" to p(result.dataOrigins.joinToString { it.packageName })),"hc:steps:${window.id}","health_connect",until.toString()).changed("externalId" to p("james-day-steps:${window.id}")) }
        }
        incoming+=personal("ExternalSource",fields("title" to p("Health Connect"),"lastSync" to p(now()),"status" to p("synced")),"source:health_connect","health_connect")
        repo.externalBatch(incoming)
    }
}
