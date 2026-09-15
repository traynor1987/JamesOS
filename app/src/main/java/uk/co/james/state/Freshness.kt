package uk.co.james.state

import java.time.Duration
import java.time.Instant

enum class PhysiologyFreshnessClass { LIVE_FAST, DAILY_SLOW, LONGITUDINAL }
enum class PhysiologyFreshnessState { FRESH, AGING, STALE, FUTURE, MISSING }

data class PhysiologyFreshnessPolicy(
    val freshnessClass: PhysiologyFreshnessClass,
    val fullStrengthFor: Duration,
    val unavailableAfter: Duration,
    val allowedFutureSkew: Duration = Duration.ofMinutes(5)
)

data class PhysiologyFreshness(
    val freshnessClass: PhysiologyFreshnessClass,
    val state: PhysiologyFreshnessState,
    val ageMinutes: Long?,
    val multiplier: Double,
    val included: Boolean,
    val reason: String
)

object PhysiologyFreshnessPolicies {
    private val live = PhysiologyFreshnessPolicy(PhysiologyFreshnessClass.LIVE_FAST, Duration.ofMinutes(30), Duration.ofHours(6))
    private val spot = PhysiologyFreshnessPolicy(PhysiologyFreshnessClass.LIVE_FAST, Duration.ofMinutes(15), Duration.ofHours(2))
    private val daily = PhysiologyFreshnessPolicy(PhysiologyFreshnessClass.DAILY_SLOW, Duration.ofHours(36), Duration.ofHours(72))
    private val longitudinal = PhysiologyFreshnessPolicy(PhysiologyFreshnessClass.LONGITUDINAL, Duration.ofDays(7), Duration.ofDays(28))

    fun forMetric(metric: String, source: String = "", context: String = ""): PhysiologyFreshnessPolicy = when (metric) {
        "James Stress" -> live
        "Heart rate", "Skin conductance", "EDA", "Skin temperature", "Steps", "Exercise" -> spot
        "HRV" -> if (source == "whoop" || context == "WHOOP_OVERNIGHT_HRV") daily else spot
        "Resting heart rate", "Sleep", "Recovery" -> daily
        else -> longitudinal
    }

    fun assess(metric: String, observedAt: Instant?, now: Instant, source: String = "", context: String = ""): PhysiologyFreshness {
        val policy = forMetric(metric, source, context)
        if (observedAt == null) return PhysiologyFreshness(policy.freshnessClass, PhysiologyFreshnessState.MISSING, null, 0.0, false, "No valid observation timestamp")
        val age = Duration.between(observedAt, now)
        if (age.isNegative && age.abs() > policy.allowedFutureSkew) {
            return PhysiologyFreshness(policy.freshnessClass, PhysiologyFreshnessState.FUTURE, age.toMinutes(), 0.0, false, "Observation is implausibly in the future")
        }
        if (age <= policy.fullStrengthFor) return PhysiologyFreshness(policy.freshnessClass, PhysiologyFreshnessState.FRESH, maxOf(0, age.toMinutes()), 1.0, true, "Within the full-strength freshness window")
        if (age >= policy.unavailableAfter) return PhysiologyFreshness(policy.freshnessClass, PhysiologyFreshnessState.STALE, age.toMinutes(), 0.0, false, "Older than the usable freshness window")
        val span = (policy.unavailableAfter.minus(policy.fullStrengthFor)).toMillis().toDouble()
        val elapsed = (age.minus(policy.fullStrengthFor)).toMillis().toDouble()
        val multiplier = (1.0 - elapsed / span).coerceIn(0.0, 1.0)
        return PhysiologyFreshness(policy.freshnessClass, PhysiologyFreshnessState.AGING, age.toMinutes(), multiplier, true, "Influence reduced as the observation ages")
    }
}
