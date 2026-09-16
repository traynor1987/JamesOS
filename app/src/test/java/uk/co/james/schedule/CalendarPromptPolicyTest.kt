package uk.co.james.schedule

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CalendarPromptPolicyTest {
    private val now=Instant.parse("2026-09-16T18:00:00Z")
    @Test fun onlyNearestImminentBusyUnknownEventIsEligible() {
        val soon=promptEvent("soon",now.plus(Duration.ofMinutes(40)))
        val later=promptEvent("later",now.plus(Duration.ofHours(4)))
        assertEquals("soon",CalendarPromptPolicy.next(listOf(later,soon),emptySet(),emptyMap(),now)?.stableId)
    }
    @Test fun allDayFreeDismissedAndRuleMatchedEventsDoNotNag() {
        val busy=promptEvent("busy",now.plus(Duration.ofMinutes(30)))
        assertNull(CalendarPromptPolicy.next(listOf(busy.copy(allDay=true)),emptySet(),emptyMap(),now))
        assertNull(CalendarPromptPolicy.next(listOf(busy.copy(availability="FREE")),emptySet(),emptyMap(),now))
        assertNull(CalendarPromptPolicy.next(listOf(busy),setOf("busy"),emptyMap(),now))
        assertNull(CalendarPromptPolicy.next(listOf(busy),emptySet(),mapOf("busy" to now.plus(Duration.ofDays(2))),now))
    }
    private fun promptEvent(id:String,start:Instant)=CalendarPromptEvent(id,start,false,"BUSY","UNKNOWN",false)
}
