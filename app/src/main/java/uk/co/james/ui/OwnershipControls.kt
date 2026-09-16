package uk.co.james.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import uk.co.james.core.text
import uk.co.james.location.TimeOwnership
import java.time.Duration
import java.time.Instant

/** The only current-ownership control surface.  Its local minute clock merely
 * updates the displayed duration; it does not recalculate historical state. */
internal data class OwnershipChoice(val ownership:TimeOwnership,val label:String)
internal val ownershipChoices=listOf(
    OwnershipChoice(TimeOwnership.AUTONOMOUS,"PERSONAL"),
    OwnershipChoice(TimeOwnership.COMMITTED,"OBLIGATION"),
    OwnershipChoice(TimeOwnership.CONSTRAINED,"CONSTRAINED"),
    OwnershipChoice(TimeOwnership.WORK,"WORK"),
    OwnershipChoice(TimeOwnership.UNKNOWN,"UNKNOWN")
)

@Composable internal fun CurrentOwnershipControls(vm:JamesViewModel,showWhenUnknown:Boolean=true) {
    val records by vm.records.collectAsState()
    val active by vm.currentOwnership.collectAsState()
    val activeInterruption=remember(records) { records.filter {it.kind=="VisitInterruption"&&it.data().text("end").isBlank()}.maxByOrNull {it.timestamp} }
    val ownership=active?.let {runCatching {TimeOwnership.valueOf(it.data().text("ownership","UNKNOWN"))}.getOrDefault(TimeOwnership.UNKNOWN)}?:TimeOwnership.UNKNOWN
    var changing by remember(active?.recordId,ownership) {mutableStateOf(false)}
    var clock by remember(active?.recordId) {mutableStateOf(Instant.now())}
    LaunchedEffect(active?.recordId) { while(true) { delay(30_000);clock=Instant.now() } }
    val started=active?.data()?.text("start")?.let {runCatching {Instant.parse(it)}.getOrNull()}
    val duration=started?.let {Duration.between(it,clock).toMinutes().coerceAtLeast(0)}
    val label=ownershipLabel(ownership)
    if(!showWhenUnknown&&(active==null||ownership==TimeOwnership.UNKNOWN)) return
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(active!=null&&ownership!=TimeOwnership.UNKNOWN) {
            Text("CURRENT TIME",fontWeight=FontWeight.Bold)
            Text("$label · ${durationText(duration?:0)}",fontWeight=FontWeight.Black)
            started?.let {Text("Started ${it.toString().substring(11,16)}",style=androidx.compose.material3.MaterialTheme.typography.bodySmall)}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={changing=!changing},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(if(changing)"HIDE CHANGE" else "CHANGE")}
                Button(onClick=vm::endCurrentOwnership,modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text("END $label")}
            }
            if(ownership==TimeOwnership.AUTONOMOUS) OutlinedButton(onClick={vm.interruptCurrentTime()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("INTERRUPTED")}
            if(activeInterruption!=null) OutlinedButton(onClick=vm::resumeCurrentTime,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("RESUME MY TIME")}
        } else {
            Text("TIME OWNERSHIP",fontWeight=FontWeight.Bold)
            Text("UNKNOWN",fontWeight=FontWeight.Black)
            Text("Choose only if you know who controlled this time.",style=androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        if(active==null||ownership==TimeOwnership.UNKNOWN||changing) OwnershipChoices(onSelect={value->vm.setCurrentOwnership(value.name)})
    }
}

@Composable private fun OwnershipChoices(onSelect:(TimeOwnership)->Unit) {
    ownershipChoices.chunked(2).forEach { row->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach {choice->TextButton(onClick={onSelect(choice.ownership)},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(choice.label)}}
            if(row.size==1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
    }
}

private fun ownershipLabel(ownership:TimeOwnership)=when(ownership) {
    TimeOwnership.AUTONOMOUS->"PERSONAL"
    TimeOwnership.COMMITTED->"OBLIGATION"
    TimeOwnership.CONSTRAINED->"CONSTRAINED"
    TimeOwnership.WORK->"WORK"
    TimeOwnership.UNKNOWN->"UNKNOWN"
}
private fun durationText(minutes:Long)=if(minutes<60)"${minutes}m" else "${minutes/60}h ${minutes%60}m"
