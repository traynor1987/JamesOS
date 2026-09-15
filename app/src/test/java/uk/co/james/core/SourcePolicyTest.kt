package uk.co.james.core

import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {
    @Test fun declared_provider_wins_over_fallback_for_equivalent_metric() {
        assertTrue(SourcePolicy.rank("Sleep","whoop") < SourcePolicy.rank("Sleep","health_connect"))
        assertTrue(SourcePolicy.rank("HRV","whoop") < SourcePolicy.rank("HRV","health_connect"))
    }
    @Test fun unlisted_source_is_fallback_not_an_authority() {
        assertTrue(SourcePolicy.rank("Resting heart rate","whoop") < SourcePolicy.rank("Resting heart rate","manual_import"))
    }
}
