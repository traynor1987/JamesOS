package uk.co.james.ui

import java.time.Instant
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.time.JamesDayWindow

/** Bounded James-Day presentation data. Compose never scans nutrition history. */
internal data class NutritionTodayUi(
    val calories:Double?=null,val protein:Double?=null,val carbs:Double?=null,val fat:Double?=null,
    val fibre:Double?=null,val sugar:Double?=null,val caffeine:Double?=null,val waterMl:Double?=null,
    val lastMealAt:Instant?=null,val source:String?=null,val meals:List<StoredRecord> = emptyList()
)

internal fun nutritionToday(records:List<StoredRecord>,day:JamesDayWindow,clock:Instant):NutritionTodayUi {
    fun instant(row:StoredRecord)=runCatching {Instant.parse(row.timestamp)}.getOrNull()
    /** Health Connect rows carry their resolved James-Day owner at ingestion.
     * That owner is authoritative for current NutritionEvent/HydrationEvent
     * presentation; legacy rows retain their timestamp fallback. */
    fun belongsToCurrentJamesDay(row:StoredRecord):Boolean {
        val ownedDay=row.data().text("jamesDayId").takeIf {it.isNotBlank()}
        return ownedDay?.let {it==day.id} ?: instant(row)?.let {at->at in day.start..clock}==true
    }
    val meals=records.filter {it.kind in setOf("Nutrition","NutritionEvent")}
        .filter(::belongsToCurrentJamesDay).sortedBy {it.timestamp}
    val water=records.filter {it.kind in setOf("Hydration","HydrationEvent")}
        .filter(::belongsToCurrentJamesDay)
    fun total(rows:List<StoredRecord>,key:String):Double?=rows.map {it.data().number(key,Double.NaN)}
        .filter(Double::isFinite).takeIf {it.isNotEmpty()}?.sum()
    val source=meals.lastOrNull()?.data()?.text("provider")?.takeIf {it.isNotBlank()}
        ?:water.lastOrNull()?.data()?.text("provider")?.takeIf {it.isNotBlank()}
    return NutritionTodayUi(
        total(meals,"energyKcal"),total(meals,"proteinGrams"),
        total(meals,"carbohydrateGrams")?:total(meals,"carbsGrams"),total(meals,"fatGrams"),
        total(meals,"fibreGrams"),total(meals,"sugarGrams"),total(meals,"caffeineMg"),
        total(water,"volumeMl"),meals.lastOrNull()?.let(::instant),source,meals
    )
}

internal fun nutritionProviderLabel(provider:String?):String=when {
    provider.isNullOrBlank()->"Health Connect"
    provider.contains("mynetdiary",true)->"MyNetDiary via Health Connect"
    else->"$provider via Health Connect"
}