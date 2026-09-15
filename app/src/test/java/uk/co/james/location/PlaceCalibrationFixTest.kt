package uk.co.james.location

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCalibrationFixTest {
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private fun fix(
        accuracy: Float,
        age: Duration,
        source: CalibrationFixSource = CalibrationFixSource.PASSIVE_ANCHOR,
        latitude: Double = 53.0,
        longitude: Double = -2.7
    ) = CalibrationFix(latitude, longitude, accuracy, now.minus(age), source)

    @Test fun stale_passive_fix_is_replaced_by_a_fresh_precise_fix() {
        val result = selectPlaceCalibrationFix(
            passive = fix(84f, Duration.ofMinutes(12)),
            precise = fix(8f, Duration.ofSeconds(2), CalibrationFixSource.FUSED_CURRENT),
            preciseFailure = null,
            now = now
        )

        val accepted = result as PlaceCalibrationFixResult.Accepted
        assertEquals(CalibrationFixSource.FUSED_CURRENT, accepted.fix.source)
        assertTrue(accepted.requestedPreciseFix)
    }

    @Test fun inaccurate_passive_fix_saves_the_coordinates_from_the_precise_fix() {
        val precise = fix(12f, Duration.ofSeconds(1), CalibrationFixSource.FUSED_CURRENT, 53.123, -2.456)
        val result = selectPlaceCalibrationFix(fix(96f, Duration.ofSeconds(3)), precise, null, now)

        val accepted = result as PlaceCalibrationFixResult.Accepted
        assertEquals(53.123, accepted.fix.latitude, 0.000001)
        assertEquals(-2.456, accepted.fix.longitude, 0.000001)
    }

    @Test fun precise_fix_timeout_does_not_save_and_explains_that_no_fresh_fix_arrived() {
        val result = selectPlaceCalibrationFix(fix(84f, Duration.ofMinutes(12)), null, CalibrationFixFailure.TIMEOUT, now)

        assertEquals(
            "Last location fix is 12 minutes old. Couldn't obtain a fresh precise location fix.",
            (result as PlaceCalibrationFixResult.Rejected).message
        )
    }

    @Test fun permission_or_provider_failure_is_actionable() {
        val result = selectPlaceCalibrationFix(null, null, CalibrationFixFailure.PROVIDER_UNAVAILABLE, now)

        assertEquals(
            "Precise location provider is unavailable. Turn on Location and try again.",
            (result as PlaceCalibrationFixResult.Rejected).message
        )
    }

    @Test fun missing_precise_permission_is_not_misreported_as_bad_outdoor_accuracy() {
        val result = selectPlaceCalibrationFix(null, null, CalibrationFixFailure.PERMISSION_MISSING, now)

        assertEquals(
            "Precise location permission is required to save a place.",
            (result as PlaceCalibrationFixResult.Rejected).message
        )
    }

    @Test fun already_fresh_accurate_fix_saves_without_another_sensor_request() {
        val result = selectPlaceCalibrationFix(fix(18f, Duration.ofSeconds(12)), null, null, now)

        val accepted = result as PlaceCalibrationFixResult.Accepted
        assertEquals(CalibrationFixSource.PASSIVE_ANCHOR, accepted.fix.source)
        assertTrue(!accepted.requestedPreciseFix)
    }
}
