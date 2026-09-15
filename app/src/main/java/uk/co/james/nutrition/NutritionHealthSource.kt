package uk.co.james.nutrition

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.serialization.json.JsonObject
import uk.co.james.data.JamesRepository
import uk.co.james.time.jamesDayWindow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Bounded, idempotent Health Connect ingestion.
 *
 * Nutrition values use the typed Health Connect unit APIs. Do not use reflection
 * here: it can silently turn a provider's valid Energy/Mass values into null,
 * leaving a persisted meal that Today incorrectly renders as "Not logged".
 */
data class NutritionSyncResult(val nutritionReturned:Int,val hydrationReturned:Int,val recordsPersisted:Int,val permission:String)

class NutritionHealthSource(private val context: Context, private val repo: JamesRepository) {
    private fun client()=HealthConnectClient.getOrCreate(context)
    fun permissions()=setOf(HealthPermission.getReadPermission(NutritionRecord::class),HealthPermission.getReadPermission(HydrationRecord::class))
    private fun mealType(record:NutritionRecord):String?=runCatching {
        val value=record.javaClass.methods.firstOrNull { it.name=="getMealType" }?.invoke(record)?.toString() ?: return null
        value.takeUnless { it=="0" || it.equals("unknown",true) }
    }.getOrNull()
    suspend fun sync(now:Instant=Instant.now()):NutritionSyncResult {
        val granted=client().permissionController.getGrantedPermissions()
        val nutritionGranted=HealthPermission.getReadPermission(NutritionRecord::class) in granted
        val hydrationGranted=HealthPermission.getReadPermission(HydrationRecord::class) in granted
        if(!nutritionGranted&&!hydrationGranted) return NutritionSyncResult(0,0,0,"Permission required")
        val marker=repo.dao.get("personalRecords","source:health_connect")?.data()?.text("lastSync")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val since=(marker?.minus(Duration.ofDays(2)) ?: now.minus(Duration.ofDays(28)))
        val window=jamesDayWindow(repo.stateInputs(now),now,ZoneId.systemDefault())
        val values=mutableListOf<JsonObject>()
        var nutritionReturned=0
        var hydrationReturned=0
        if(nutritionGranted) {
            var page:String?=null
            do {
                val result=client().readRecords(ReadRecordsRequest(NutritionRecord::class,TimeRangeFilter.between(since,now),pageToken=page))
                nutritionReturned+=result.records.size
                result.records.forEach { r ->
                    val provider=r.metadata.dataOrigin.packageName
                    values+=nutritionRecord(r.metadata.id,provider,r.startTime,r.endTime,r.metadata.lastModifiedTime,
                        jamesDayWindow(repo.stateInputs(r.endTime),r.endTime,ZoneId.systemDefault()).id,mealType(r),mapOf(
                        "energyKcal" to r.energy?.inKilocalories,
                        "carbsGrams" to r.totalCarbohydrate?.inGrams,
                        "proteinGrams" to r.protein?.inGrams,
                        "fatGrams" to r.totalFat?.inGrams,
                        "saturatedFatGrams" to r.saturatedFat?.inGrams,
                        "sugarGrams" to r.sugar?.inGrams,
                        "fibreGrams" to r.dietaryFiber?.inGrams,
                        "sodiumMg" to r.sodium?.inMilligrams,
                        "caffeineMg" to r.caffeine?.inMilligrams))
                };page=result.pageToken
            } while(page!=null)
        }
        if(hydrationGranted) {
            var page:String?=null
            do {
                val result=client().readRecords(ReadRecordsRequest(HydrationRecord::class,TimeRangeFilter.between(since,now),pageToken=page))
                hydrationReturned+=result.records.size
                result.records.forEach { r -> values+=hydrationRecord(r.metadata.id,r.metadata.dataOrigin.packageName,r.startTime,r.endTime,r.metadata.lastModifiedTime,jamesDayWindow(repo.stateInputs(r.endTime),r.endTime,ZoneId.systemDefault()).id,r.volume.inLiters*1000.0) };page=result.pageToken
            } while(page!=null)
        }
        val candidateCount=values.size
        values+=uk.co.james.core.personal("NutritionSync",uk.co.james.core.fields("title" to uk.co.james.core.p("Nutrition sync"),"lastSync" to uk.co.james.core.p(now.toString()),"nutritionRecordsReturned" to uk.co.james.core.p(nutritionReturned),"hydrationRecordsReturned" to uk.co.james.core.p(hydrationReturned),"recordsAccepted" to uk.co.james.core.p(candidateCount),"jamesDayId" to uk.co.james.core.p(window.id),"nutritionPermission" to uk.co.james.core.p(if(nutritionGranted)"Granted" else "Not granted"),"hydrationPermission" to uk.co.james.core.p(if(hydrationGranted)"Granted" else "Not granted")),"source:health_connect_nutrition","health_connect",now.toString())
        val persisted=(repo.externalBatch(values)-1).coerceAtLeast(0)
        return NutritionSyncResult(nutritionReturned,hydrationReturned,persisted,if(nutritionGranted)"Granted" else "Not granted")
    }
}