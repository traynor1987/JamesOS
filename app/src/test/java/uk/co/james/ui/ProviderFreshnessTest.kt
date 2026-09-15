package uk.co.james.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderFreshnessTest {
    private val now=Instant.parse("2026-09-15T12:00:00Z")
    @Test fun connection_freshness_never_treats_missing_or_old_sync_as_current() {
        assertEquals("NOT SYNCED",providerSyncStatus("",now))
        assertEquals("FRESH",providerSyncStatus("2026-09-15T11:40:00Z",now))
        assertEquals("AGING",providerSyncStatus("2026-09-15T07:00:00Z",now))
        assertEquals("STALE",providerSyncStatus("2026-09-13T12:00:00Z",now))
    }
}
