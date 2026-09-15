package uk.co.james.wear.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotFreshnessTest {
    private val now=10_000_000L
    @Test fun missingSnapshotIsStale(){assertTrue(isSnapshotStale(0L,now))}
    @Test fun twoHoursIsStillFresh(){assertFalse(isSnapshotStale(now-SNAPSHOT_STALE_AFTER_MS,now))}
    @Test fun olderThanTwoHoursIsStale(){assertTrue(isSnapshotStale(now-SNAPSHOT_STALE_AFTER_MS-1,now))}
}
