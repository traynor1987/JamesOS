package uk.co.james.schedule

import java.time.Duration
import java.time.Instant

/** Pure, bounded anti-nag policy for the future Calendar surface.  It has no
 * relationship to the existing live Time Ownership controls. */
data class CalendarPromptEvent(val stableId:String,val start:Instant,val allDay:Boolean,val availability:String?,val plannedOwnership:String,val hasAuthoritativeClassification:Boolean)

object CalendarPromptPolicy {
    private val horizon=Duration.ofHours(24)
    fun next(events:List<CalendarPromptEvent>, answered:Set<String>, dismissedUntil:Map<String,Instant>, now:Instant):CalendarPromptEvent? =
        events.asSequence()
            .filter { it.plannedOwnership=="UNKNOWN"&&!it.hasAuthoritativeClassification&&!it.allDay&&it.availability.equals("BUSY",true) }
            .filter { it.stableId !in answered && (dismissedUntil[it.stableId]?.isAfter(now)!=true) }
            .filter { it.start.isAfter(now)&&it.start<=now.plus(horizon) }
            .minByOrNull { it.start }
}
