package uk.co.james

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.james.ui.adaptiveCardContentType
import uk.co.james.ui.prepareWhoopOverview

class TodayPerformanceTest {
    @Test fun dashboardCardsShareReusableContentType() {
        assertEquals(adaptiveCardContentType(1),adaptiveCardContentType(2))
    }

    @Test fun healthOverviewCanBePreparedOutsideCompositionWithUnknownData() {
        val snapshot=prepareWhoopOverview(emptyList(),Instant.parse("2026-09-13T12:00:00Z"))
        assertNull(snapshot.sleep)
        assertNull(snapshot.recovery)
        assertNull(snapshot.hrv)
    }
}
