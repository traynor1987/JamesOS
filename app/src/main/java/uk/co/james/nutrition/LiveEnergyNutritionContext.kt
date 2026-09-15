package uk.co.james.nutrition

import java.time.Duration
import java.time.Instant

/**
 * A description of available food/drink context, deliberately not a score.
 * Live Energy keeps its existing calibration; check-ins remain the evidence.
 */
data class LiveEnergyNutritionContext(
    val available:Boolean,
    val timeSinceLastMeal:Duration?,
    val recentEnergyKcal:Double?,
    val recentCarbsGrams:Double?,
    val recentProteinGrams:Double?,
    val recentFatGrams:Double?,
    val recentCaffeineMg:Double?,
    val hydrationTodayMl:Double?
)

fun liveEnergyNutritionContext(events:List<NutritionEvent>,hydration:List<HydrationEvent>,at:Instant=Instant.now()):LiveEnergyNutritionContext {
    if(events.isEmpty()&&hydration.isEmpty())return LiveEnergyNutritionContext(false,null,null,null,null,null,null,null)
    val recent=events.filter { !it.end.isAfter(at)&&Duration.between(it.end,at)<=Duration.ofHours(6) }
    val last=events.maxByOrNull {it.end}
    fun List<Double?>.sumKnown()=mapNotNull {it}.takeIf {it.isNotEmpty()}?.sum()
    return LiveEnergyNutritionContext(true,last?.end?.let {Duration.between(it,at).takeIf {d->!d.isNegative}},
        recent.map {it.energyKcal}.sumKnown(),recent.map {it.carbsGrams}.sumKnown(),
        recent.map {it.proteinGrams}.sumKnown(),recent.map {it.fatGrams}.sumKnown(),
        recent.map {it.caffeineMg}.sumKnown(),hydration.filter {!it.end.isAfter(at)}.map {it.volumeMl}.takeIf {it.isNotEmpty()}?.sum())
}