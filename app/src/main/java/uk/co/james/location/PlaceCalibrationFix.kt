package uk.co.james.location

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/**
 * A location used to define a saved Place.  This is intentionally distinct from
 * the low-power location timeline: a calibration is an explicit user action and
 * must be both fresh and spatially trustworthy.
 */
data class CalibrationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Float,
    val observedAt: Instant,
    val source: CalibrationFixSource
)

enum class CalibrationFixSource(val label: String) {
    PASSIVE_ANCHOR("Low-power timeline"),
    FUSED_CURRENT("Fresh precise location")
}

enum class CalibrationFixFailure {
    TIMEOUT,
    PERMISSION_MISSING,
    PROVIDER_UNAVAILABLE
}

sealed interface PlaceCalibrationFixResult {
    data class Accepted(val fix: CalibrationFix, val requestedPreciseFix: Boolean) : PlaceCalibrationFixResult
    data class Rejected(val message: String) : PlaceCalibrationFixResult
}

const val PLACE_CALIBRATION_MAX_ACCURACY_METRES = 30f
private val PLACE_CALIBRATION_MAX_AGE: Duration = Duration.ofMinutes(1)

fun CalibrationFix.ageAt(now: Instant): Duration = Duration.between(observedAt, now).coerceAtLeast(Duration.ZERO)
fun CalibrationFix.isPlaceCalibrationQuality(now: Instant): Boolean =
    accuracyMetres <= PLACE_CALIBRATION_MAX_ACCURACY_METRES && ageAt(now) <= PLACE_CALIBRATION_MAX_AGE

fun requiresPrecisePlaceCalibrationFix(passive: CalibrationFix?, now: Instant): Boolean =
    passive?.isPlaceCalibrationQuality(now) != true

/** Pure selection policy so UI and provider failures cannot silently weaken the
 * place-quality gate.  A fresh high-accuracy attempt is considered only when
 * the passive anchor is not already suitable. */
fun selectPlaceCalibrationFix(
    passive: CalibrationFix?,
    precise: CalibrationFix?,
    preciseFailure: CalibrationFixFailure?,
    now: Instant
): PlaceCalibrationFixResult {
    if (passive?.isPlaceCalibrationQuality(now) == true) {
        return PlaceCalibrationFixResult.Accepted(passive, requestedPreciseFix = false)
    }
    if (precise?.isPlaceCalibrationQuality(now) == true) {
        return PlaceCalibrationFixResult.Accepted(precise, requestedPreciseFix = true)
    }
    if (precise != null) return PlaceCalibrationFixResult.Rejected(qualityFailure(precise, now))
    return PlaceCalibrationFixResult.Rejected(
        when (preciseFailure) {
            CalibrationFixFailure.PERMISSION_MISSING -> "Precise location permission is required to save a place."
            CalibrationFixFailure.PROVIDER_UNAVAILABLE -> "Precise location provider is unavailable. Turn on Location and try again."
            CalibrationFixFailure.TIMEOUT, null -> timeoutFailure(passive, now)
        }
    )
}

fun CalibrationFix.diagnostic(now: Instant): String =
    "${source.label} · ±${accuracyMetres.roundToLong()}m · ${ageText(ageAt(now))} old"

private fun qualityFailure(fix: CalibrationFix, now: Instant): String = when {
    fix.accuracyMetres > PLACE_CALIBRATION_MAX_ACCURACY_METRES ->
        "Current accuracy is ±${fix.accuracyMetres.roundToLong()}m. Need approximately ±${PLACE_CALIBRATION_MAX_ACCURACY_METRES.toLong()}m or better to save this place."
    else -> "Fresh precise location is ${ageText(fix.ageAt(now))} old. Need a fix less than ${PLACE_CALIBRATION_MAX_AGE.toMinutes()} minute old to save this place."
}

private fun timeoutFailure(passive: CalibrationFix?, now: Instant): String = passive?.let {
    "Last location fix is ${ageText(it.ageAt(now))} old. Couldn't obtain a fresh precise location fix."
} ?: "Couldn't obtain a fresh precise location fix."

fun ageText(age: Duration): String = when {
    age < Duration.ofMinutes(1) -> "${age.seconds.coerceAtLeast(0)} seconds"
    age.toMinutes() == 1L -> "1 minute"
    else -> "${age.toMinutes()} minutes"
}
