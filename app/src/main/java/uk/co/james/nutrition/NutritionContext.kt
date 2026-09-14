package uk.co.james.nutrition

import java.time.Duration
import java.time.Instant

/**
 * Health Connect is the transport. These records retain the source identity and
 * intentionally keep absent values null rather than manufacturing zeroes.
 */
data class NutritionEvent(
    val sourceRecordId: String,
    val source: String,
    val sourcePackage: String?,
    val start: Instant,
    val end: Instant,
    val lastModified: Instant?,
    val ingestedAt: Instant,
    val jamesDayId: String,
    val mealType: String?,
    val energyKcal: Double?,
    val carbsGrams: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val saturatedFatGrams: Double?,
    val sugarGrams: Double?,
    val fibreGrams: Double?,
    val sodiumMg: Double?,
    val caffeineMg: Double?
)

data class HydrationEvent(
    val sourceRecordId: String,
    val source: String,
    val sourcePackage: String?,
    val start: Instant,
    val end: Instant,
    val lastModified: Instant?,
    val ingestedAt: Instant,
    val jamesDayId: String,
    val volumeMl: Double
)

data class NutritionSummary(
    val energyKcal: Double?,
    val carbsGrams: Double?,
    val proteinGrams: Double?,
    val fatGrams: Double?,
    val waterMl: Double?,
    val caffeineMg: Double?,
    val latestMeal: NutritionEvent?,
    val latestHydration: HydrationEvent?
) {
    val timeSinceLastMeal: Duration? get() = latestMeal?.end?.let { Duration.between(it, Instant.now()).takeIf { d -> !d.isNegative } }
    val timeSinceCaffeine: Duration? get() = latestMeal?.takeIf { (it.caffeineMg ?: 0.0) > 0.0 }?.end?.let { Duration.between(it, Instant.now()).takeIf { d -> !d.isNegative } }
}

fun healthConnectNutritionSource(packageName: String?): String =
    if (packageName?.contains("mynetdiary", ignoreCase = true) == true) "MyNetDiary via Health Connect"
    else packageName?.ifBlank { null } ?: "Health Connect"

fun nutritionSummary(events: List<NutritionEvent>, hydration: List<HydrationEvent>): NutritionSummary {
    fun List<Double?>.total() = mapNotNull { it }.takeIf { it.isNotEmpty() }?.sum()
    val latest = events.maxByOrNull { it.end }
    return NutritionSummary(
        energyKcal = events.map { it.energyKcal }.total(),
        carbsGrams = events.map { it.carbsGrams }.total(),
        proteinGrams = events.map { it.proteinGrams }.total(),
        fatGrams = events.map { it.fatGrams }.total(),
        waterMl = hydration.map { it.volumeMl }.takeIf { it.isNotEmpty() }?.sum(),
        caffeineMg = events.map { it.caffeineMg }.total(),
        latestMeal = latest,
        latestHydration = hydration.maxByOrNull { it.end }
    )
}