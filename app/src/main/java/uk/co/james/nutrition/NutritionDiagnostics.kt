package uk.co.james.nutrition

import kotlinx.serialization.json.JsonObject
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import java.time.Instant

data class NutritionDiagnostics(
    val permission:String,
    val lastSync:String?,
    val nutritionRecords:Int,
    val hydrationRecords:Int,
    val latestMeal:String?,
    val latestHydration:String?,
    val latestCaffeine:String?,
    val detectedSources:Set<String>
)

fun nutritionDiagnostics(records:List<StoredRecord>, granted:Boolean):NutritionDiagnostics {
    val events=records.filter { it.store=="personalRecords"&&it.raw().text("kind")=="NutritionEvent" }
    val hydration=records.filter { it.store=="personalRecords"&&it.raw().text("kind")=="HydrationEvent" }
    fun end(row:StoredRecord)=row.data().text("end")
    val latest=events.maxByOrNull(::end)
    val caffeine=events.filter { it.data().number("caffeineMg")>0 }.maxByOrNull(::end)
    return NutritionDiagnostics(
        permission=if(granted)"Granted" else "Permission required",
        lastSync=records.firstOrNull {it.recordId=="source:health_connect_nutrition"}?.data()?.text("lastSync")?.ifBlank {null},
        nutritionRecords=events.size,hydrationRecords=hydration.size,
        latestMeal=latest?.let(::end),latestHydration=hydration.maxByOrNull(::end)?.let(::end),
        latestCaffeine=caffeine?.let(::end),
        detectedSources=(events+hydration).map {it.data().text("provider")}.filter {it.isNotBlank()}.toSet()
    )
}

fun nutritionDiagnosticRecord(value:NutritionDiagnostics)=personal("NutritionDiagnostics",fields(
    "title" to p("Nutrition diagnostics"),"permission" to p(value.permission),
    "lastSync" to (value.lastSync?.let(::p)?:kotlinx.serialization.json.JsonNull),
    "nutritionRecords" to p(value.nutritionRecords),"hydrationRecords" to p(value.hydrationRecords),
    "latestMeal" to (value.latestMeal?.let(::p)?:kotlinx.serialization.json.JsonNull),
    "latestHydration" to (value.latestHydration?.let(::p)?:kotlinx.serialization.json.JsonNull),
    "latestCaffeine" to (value.latestCaffeine?.let(::p)?:kotlinx.serialization.json.JsonNull),
    "sources" to kotlinx.serialization.json.JsonArray(value.detectedSources.sorted().map(::p)),
    "updatedAt" to p(Instant.now().toString())
),"nutrition-diagnostics","james")
