package uk.co.james.state

import uk.co.james.database.StoredRecord
import uk.co.james.core.number

/**
 * Separates an algorithm's neutral mathematical starting point from evidence
 * that can honestly be shown or persisted as James's state. Metadata and
 * calibration rows alone must never make a prior look like an observation.
 */
internal fun hasWellbeingEvidence(records:List<StoredRecord>):Boolean = records.any { row ->
    (row.kind=="HealthMetric" && row.data().number("value",Double.NaN).isFinite()) ||
        row.kind in setOf("MoodEntry","WellbeingCheckIn")
}

internal fun hasRightNowEvidence(records:List<StoredRecord>):Boolean =
    hasWellbeingEvidence(records) || records.any {it.kind in setOf("TimePressureCheckIn","OwnershipPeriod")}
