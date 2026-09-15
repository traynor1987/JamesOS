package uk.co.james.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.james.state.AnxietyInputStamp

class WellbeingDiagnosticsPresentationTest {
    @Test fun matureInputHistoryIsBoundedToTheNewestTwelveRows() {
        val inputs=(1..1000).map {AnxietyInputStamp("Stress","2026-09-15T12:${it.toString().padStart(4,'0')}:00Z")}
        val visible=boundedDiagnosticInputs(inputs)
        assertEquals(12,visible.size)
        assertEquals(inputs.last().timestamp,visible.last().timestamp)
    }
}
