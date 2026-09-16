package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uk.co.james.core.text
import uk.co.james.database.StoredRecord
import java.time.Instant

/** A deliberate, user-opened schedule surface.  It never interrogates James
 * while Today recomposes: UNKNOWN is valid planned ownership. */
@Composable fun ScheduleScreen(vm:JamesViewModel, records:List<StoredRecord>) {
    val now=remember { Instant.now() }
    val commitments=records.filter {it.kind=="ScheduledCommitment"}
        .filter {it.data().text("status","UPCOMING") in setOf("UPCOMING","IN_PROGRESS")}
        .filter {it.data().text("start").let {stamp->runCatching {Instant.parse(stamp)}.getOrNull()?.isAfter(now.minusSeconds(60))==true}}
        .sortedBy {it.data().text("start") }.take(20)
    val rules=records.filter {it.kind=="CalendarClassificationRule"}.sortedBy {it.data().text("title")}
    var target by remember { mutableStateOf<StoredRecord?>(null) }
    AdaptiveCards(buildList {
        add({ PageTitle("Schedule","PLANNED, NOT ACTUAL") })
        add({ JamesCard("Upcoming","Selected calendars and Shift Tracker rota") {
            TextButton(onClick=vm::refreshCalendar){Text("REFRESH CALENDAR")}
            if(commitments.isEmpty()) Muted("No upcoming planned commitments in the current bounded window.")
            commitments.forEach { commitment ->
                val d=commitment.data(); val ownership=d.text("plannedOwnership","UNKNOWN")
                HorizontalDivider()
                Text(d.text("title","Scheduled commitment"),style=MaterialTheme.typography.titleMedium)
                Muted("${d.text("start")} · $ownership")
                Muted("${when(d.text("classificationProvenance","UNKNOWN")){"MANUAL_EVENT"->"Classified for this event";"USER_RULE"->"Classified by your rule";"SHIFT_TRACKER_AUTHORITATIVE"->"Shift Tracker work rota";else->"Ownership unknown"}}")
                if(ownership=="UNKNOWN" && commitment.source!="shift_tracker") TextButton(onClick={target=commitment}){Text("CLASSIFY THIS EVENT")}
            }
        } })
        add({ JamesCard("Classification rules","TRANSPARENT LOCAL RULES") {
            if(rules.isEmpty()) Muted("Rules are optional. James OS never needs every event classified.")
            rules.forEach { rule -> val d=rule.data(); Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(d.text("title"));Muted("Calendar ${d.text("calendarId")} → ${d.text("ownership")}")};TextButton(onClick={vm.deleteCalendarRule(rule.recordId)}){Text("DELETE")}} }
        } })
    })
    target?.let { event ->
        val d=event.data(); var apply by remember {mutableStateOf(false)}
        AlertDialog(onDismissRequest={target=null},title={Text("Classify planned ownership")},text={Column {Text(d.text("title"));Muted("This affects planned context only. It never creates actual Personal, Work or Life Balance time.");Row {Checkbox(apply,onCheckedChange={apply=it});Text("Apply to similar future events on this calendar")};listOf("AUTONOMOUS" to "PERSONAL","WORK" to "WORK","COMMITTED" to "COMMITTED","CONSTRAINED" to "CONSTRAINED","UNKNOWN" to "LEAVE UNKNOWN").forEach {(value,label)->TextButton(onClick={vm.classifyCalendarCommitment(event.recordId,value,apply);target=null}){Text(label)}}}},confirmButton={TextButton(onClick={vm.dismissCalendarClassification(event.recordId);target=null}){Text("NOT NOW")}},dismissButton={TextButton(onClick={target=null}){Text("CANCEL")}})
    }
}
