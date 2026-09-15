package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*
import java.time.Instant

@Composable fun StateCard(vm:JamesViewModel,records:List<StoredRecord>,clock:Instant) {
    val state=remember(records,clock){stateSummary(records,clock)}
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    val signals=state.values.associateBy {it.key}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=stateInk,contentColor=Color.White),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text("YOUR DAILY OUTLOOK",color=stateMint,style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp)
                        Text("James State",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    }
                    Surface(shape=RoundedCornerShape(50),color=statePanel,contentColor=stateMint) {
                        Text("AUTO\nDaily signals",modifier=Modifier.padding(horizontal=12.dp,vertical=9.dp),style=MaterialTheme.typography.labelSmall)
                    }
                }
                if(state.remaining>0) {
                    LinearProgressIndicator(progress={state.days/7f},modifier=Modifier.fillMaxWidth(),color=stateMint,trackColor=statePanel)
                    Text("Building your sleep baseline · ${state.days}/7 days. Available signals already estimate automatically.",color=stateQuiet,style=MaterialTheme.typography.bodySmall)
                }
                signals["energy"]?.let {v->PerformanceSignal(v,Modifier.fillMaxWidth(),featured=true){selected=v.key}}
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    listOf("fatigue","bodyLoad").forEach {key->signals[key]?.let {v->PerformanceSignal(v,Modifier.weight(1f)){selected=v.key}}}
                }
                val sleep=records.firstOrNull {it.recordId==state.inputs.text("currentSleepId")}
                val pulse=records.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Resting heart rate"&&runCatching {val t=Instant.parse(it.timestamp);t<=clock&&t>=clock.minusSeconds(36*3600)}.getOrDefault(false)}.maxByOrNull {it.timestamp}
                val readouts=listOfNotNull(sleep,pulse)
                if(readouts.isNotEmpty()) {
                    HorizontalDivider(color=Color.White.copy(alpha=.12f))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                        readouts.forEach {record->Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(if(record.data().text("metric")=="Sleep")"LATEST SLEEP"else "RESTING HR",color=stateQuiet,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp)
                            Text(healthValue(record),color=Color.White,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                            Text("${providerLabel(record)} · ${localClock(record.timestamp)}",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
                        }}
                    }
                }
                Text("Tap a signal for its sources and reasons. Body load is not an emotional stress score.",color=stateQuiet,style=MaterialTheme.typography.bodySmall)
            }
        }
        Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal=20.dp,vertical=16.dp)) {
                Text("HOW YOU FEEL · AUTOMATIC",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp)
                Text("James estimates these automatically. Add context only when you want to.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp,bottom=4.dp))
                val personal=state.values.filter {it.key !in listOf("energy","fatigue","bodyLoad")}
                personal.forEachIndexed {i,v->
                    PersonalSignalRow(v){selected=v.key}
                    if(i<personal.lastIndex)HorizontalDivider(color=MaterialTheme.colorScheme.outline.copy(alpha=.6f))
                }
                TextButton(onClick={vm.openStateCheckIn()},contentPadding=PaddingValues(vertical=4.dp)){Text("Adjust James State (optional)")}
                TextButton(onClick={vm.navigate("Connections")},contentPadding=PaddingValues(vertical=4.dp)){Text("Data sources & permissions  →")}
            }
        }
    }
    signals[selected]?.let {v->AlertDialog(onDismissRequest={selected=null},title={Text(v.label)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(v.value?:"Awaiting connected data",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,color=signalAccent(v))
        Text(signalOrigin(v),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text(signalStory(v),style=MaterialTheme.typography.bodyLarge)
        HorizontalDivider(modifier=Modifier.padding(top=6.dp))
        Text("WHY JAMES THINKS THIS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,letterSpacing=1.2.sp)
        v.reasons.forEach {Text("•  $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(!v.reported)Text("Notes: this is an exploratory estimate, not a measured probability or medical assessment. You can correct it at any time.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }},confirmButton={TextButton(onClick={selected=null;vm.openStateCheckIn()}){Text("Adjust (optional)")}},dismissButton={TextButton(onClick={selected=null}){Text("Close")}})}
}
