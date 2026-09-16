package uk.co.james.schedule

import java.time.Instant
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal

/** Provider-neutral planned evidence. It intentionally has no actual ownership
 * field: plans can be reconciled with reality but never assert it. */
data class ScheduledCommitment(
    val stableId:String,
    val source:String,
    val sourceId:String,
    val calendarId:String?,
    val title:String,
    val start:Instant,
    val end:Instant,
    val allDay:Boolean,
    val plannedOwnership:String="UNKNOWN",
    val status:String="UPCOMING",
    val fixedConstraint:Boolean=false,
    val preparationMinutes:Long=0,
    val revision:Long=0,
    val dataKeys:Set<String>
) {
    fun asRecord()=personal("ScheduledCommitment",fields(
        "externalCommitmentId" to p(sourceId),"calendarId" to (calendarId?.let(::p)?:kotlinx.serialization.json.JsonNull),
        "title" to p(title),"start" to p(start.toString()),"end" to p(end.toString()),"allDay" to p(allDay),
        "plannedOwnership" to p(plannedOwnership),"status" to p(status),"fixedConstraint" to p(fixedConstraint),
        "preparationMinutes" to p(preparationMinutes),"revision" to p(revision),"reconciliationState" to p("UNKNOWN"),"mappingVersion" to p("1")
    ),stableId,source,start.toString())
}

/** Minimal Calendar Provider projection. Descriptions, attendees and notes are
 * deliberately absent before mapping, so they cannot leak into storage/logs. */
data class CalendarInstance(
    val eventId:String,
    val calendarId:String,
    val title:String,
    val start:Instant,
    val end:Instant,
    val allDay:Boolean,
    val availability:String?,
    val lastModified:Long
) {
    fun asCommitment()=ScheduledCommitment(
        stableId="calendar:$calendarId:$eventId:${start.toEpochMilli()}",source="android_calendar",sourceId=eventId,calendarId=calendarId,
        title=title.take(160),start=start,end=end,allDay=allDay,
        // Calendar labels are not semantic ownership.  Busy is a fixed planned
        // constraint; FREE/TENTATIVE remain non-fixed until James confirms.
        plannedOwnership="UNKNOWN",status="UPCOMING",fixedConstraint=!allDay&&availability.equals("BUSY",true),revision=lastModified,
        dataKeys=setOf("eventId","calendarId","title","start","end","allDay","availability","lastModified")
    )
}
