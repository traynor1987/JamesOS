package uk.co.james

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.state.*

class FreshnessPolicyTest {
    private val now=Instant.parse("2026-09-12T12:00:00Z")

    @Test fun fastInputTransitionsFromFreshToReducedToUnavailable(){
        val fresh=PhysiologyFreshnessPolicies.assess("James Stress",now.minus(Duration.ofMinutes(10)),now)
        val aging=PhysiologyFreshnessPolicies.assess("James Stress",now.minus(Duration.ofHours(3)),now)
        val stale=PhysiologyFreshnessPolicies.assess("James Stress",now.minus(Duration.ofHours(8)),now)
        assertEquals(1.0,fresh.multiplier,0.0)
        assertTrue(aging.multiplier in 0.0..0.99);assertTrue(aging.included)
        assertEquals(0.0,stale.multiplier,0.0);assertFalse(stale.included)
    }

    @Test fun dailyInputsHaveDailyRatherThanLiveCadence(){
        val recovery=PhysiologyFreshnessPolicies.assess("Recovery",now.minus(Duration.ofHours(30)),now,"whoop")
        val stress=PhysiologyFreshnessPolicies.assess("James Stress",now.minus(Duration.ofHours(30)),now,"wear")
        assertTrue(recovery.included);assertEquals(1.0,recovery.multiplier,0.0)
        assertFalse(stress.included)
    }

    @Test fun futureAndMissingInputsNeverContribute(){
        val future=PhysiologyFreshnessPolicies.assess("HRV",now.plus(Duration.ofMinutes(6)),now,"wear")
        val missing=PhysiologyFreshnessPolicies.assess("HRV",null,now,"wear")
        assertEquals(PhysiologyFreshnessState.FUTURE,future.state);assertFalse(future.included)
        assertEquals(PhysiologyFreshnessState.MISSING,missing.state);assertFalse(missing.included)
    }
}
