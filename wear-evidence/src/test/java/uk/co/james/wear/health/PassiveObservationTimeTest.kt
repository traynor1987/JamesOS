package uk.co.james.wear.health

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class PassiveObservationTimeTest {
    @Test fun batchedReadingKeepsItsOriginalTimeRatherThanCallbackTime() {
        assertEquals(1_700_000_040_000L,passiveObservationTime(Duration.ofSeconds(40),1_700_000_120_000L,120_000L))
    }
    @Test fun rebootRecomputesBootAnchorAndRejectsImpossibleFuturePoint() {
        assertEquals(1_700_000_101_000L,passiveObservationTime(Duration.ofSeconds(1),1_700_000_110_000L,10_000L))
        assertNull(passiveObservationTime(Duration.ofSeconds(20),1_700_000_110_000L,10_000L))
        assertNull(passiveObservationTime(Duration.ofSeconds(-1),1_700_000_110_000L,10_000L))
    }
}
