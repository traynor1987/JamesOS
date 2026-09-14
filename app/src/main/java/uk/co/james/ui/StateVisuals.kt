package uk.co.james.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.james.state.StateValue

internal val stateInk=jamesInk
internal val statePanel=jamesPanel
internal val stateQuiet=Color(0xFFAAB7BA)
internal val stateMint=jamesLime
internal fun signalAccent(v:StateValue):Color {
    if(v.value==null)return stateQuiet
    val adverse=if(v.key=="energy")v.value in listOf("Low","Very low")else v.value in listOf("High","Elevated")
    return when {adverse->jamesRed;v.value in listOf("Moderate","Some load")->jamesAmber;else->stateMint}
}
internal fun signalOrigin(v:StateValue)=when {v.reported->"Your report";v.value!=null->if(v.key in listOf("mood","mentalLoad","motivation","socialBattery","stress"))"Tentative · very low confidence"else "Automatic · low confidence";v.remaining>0->"${v.remaining} more data days";else->"Waiting for data"}
internal fun signalStory(v:StateValue):String {
    if(v.value==null)return "James does not have enough recent connected data to describe this yet."
    return when(v.key) {
        "energy"->when(v.value) {
            "High","Good"->"You look reasonably well charged today. Your recent recovery, sleep and strain suggest you may have useful energy available."
            "Moderate"->"Your energy looks fairly steady, although you may not feel fully recharged. James sees a mixed picture across recovery, sleep and strain."
            else->"You may be running on limited energy today. Recovery, sleep or recent load is pulling the estimate down, so an easier pace may feel more realistic."
        }
        "fatigue"->when(v.value) {
            "High"->"Your body may be carrying noticeable fatigue today. Recent recovery, sleep and load suggest you might benefit from more breathing room."
            "Moderate"->"Some physical tiredness is likely, but the signals are not uniformly poor. How you actually feel still matters most."
            else->"James sees relatively little evidence of physical fatigue in the available readings."
        }
        "bodyLoad"->when(v.value) {
            "Elevated"->"Your recent body signals look more strained than usual. This describes physical load, not emotional stress."
            "Some load"->"There are some signs of physical load, but nothing in the available readings points strongly in one direction."
            else->"Your available body signals do not show a clear elevation from the current baseline."
        }
        "mood"->"Your physical signals loosely suggest a ${v.value.lowercase()} outlook. Sensors cannot know your mood, so treat this as a gentle prompt rather than a conclusion."
        "mentalLoad"->"James tentatively estimates ${v.value.lowercase()} mental load from your recovery and physical load. It cannot directly measure what is on your mind."
        "motivation"->"Your available energy and recovery suggest ${v.value.lowercase()} capacity for motivation. What matters to you today may change this completely."
        "socialBattery"->"Your physical readiness loosely points to a ${v.value.lowercase()} social battery. This is only a proxy; James cannot sense whether you want company."
        "stress"->"Your recovery and load suggest ${v.value.lowercase()} stress likelihood. This is not WHOOP’s live Stress Monitor and is not a diagnosis."
        else->"James has combined the recent connected signals into this estimate."
    }
}

@Composable internal fun PerformanceSignal(v:StateValue,modifier:Modifier=Modifier,featured:Boolean=false,onClick:()->Unit) {
    Card(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=statePanel,contentColor=Color.White)) {
        Column(Modifier.padding(if(featured)20.dp else 16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Text(v.label.uppercase(),color=stateQuiet,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
            Text(v.value?:"Awaiting data",color=signalAccent(v),style=if(featured)MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(signalOrigin(v),color=stateQuiet,style=MaterialTheme.typography.labelSmall)
            if(featured){HorizontalDivider(color=Color.White.copy(alpha=.10f));Text(if(v.reported)"How you feel takes priority"else "Explore your contributing signals  →",color=Color.White,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@Composable internal fun PersonalSignalRow(v:StateValue,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(v.label,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
                Text(signalOrigin(v),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(v.value?:"Awaiting data",modifier=Modifier.weight(.8f),style=MaterialTheme.typography.titleMedium,color=if(v.value==null)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text("›",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.titleLarge)
        }
    }
}
