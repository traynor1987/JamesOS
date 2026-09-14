package uk.co.james.wear.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch
import uk.co.james.wear.BuildConfig
import uk.co.james.wear.data.*

private val Lime=Color(0xFFAEF76E)
private val Background=Color(0xFF070B0D)
private val Charcoal=Color(0xFF121A1D)
private val Raised=Color(0xFF192427)
private val Line=Color(0xFF263336)
private val Muted=Color(0xFF9EAAAD)
private val Blue=Color(0xFF55C7F3)
private val Pink=Color(0xFFFF6B7A)
private val Amber=Color(0xFFFFCF67)

@Composable fun JamesWearTheme(content:@Composable ()->Unit)=MaterialTheme(
    colors=Colors(primary=Lime,primaryVariant=Lime,secondary=Blue,secondaryVariant=Blue,background=Background,surface=Charcoal,error=Pink,onPrimary=Color.Black,onSecondary=Color.Black,onBackground=Color.White,onSurface=Color.White,onError=Color.Black),
    content=content
)

private enum class Screen{HOME,RIGHT_NOW,HEART,ACTIVITY,STRESS,WELLBEING,SLEEP,SETTINGS}

@Composable fun WearApp(vm:WearViewModel,startInSettings:Boolean=false,onPermissions:()->Unit,onInstall:()->Unit,onDismissUpdate:()->Unit={}) {
    var screen by rememberSaveable {mutableStateOf(if(startInSettings)Screen.SETTINGS else Screen.HOME)}
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val cachedUpdate by vm.cachedUpdate.collectAsStateWithLifecycle()
    BackHandler(enabled=true){if(screen!=Screen.HOME)screen=Screen.HOME}
    Box(Modifier.fillMaxSize().background(Background)) {
        when(screen){
            Screen.HOME->Home(snapshot,vm,screen={screen=it})
            Screen.RIGHT_NOW->RightNowScreen(snapshot,vm,onBack={screen=Screen.HOME})
            Screen.HEART->HeartScreen(vm,onBack={screen=Screen.HOME})
            Screen.ACTIVITY->ActivityScreen(snapshot,vm,onBack={screen=Screen.HOME})
            Screen.STRESS->StressScreen(vm,onBack={screen=Screen.HOME})
            Screen.WELLBEING->WellbeingScreen(snapshot,vm,onBack={screen=Screen.HOME})
            Screen.SLEEP->SleepScreen(snapshot,onBack={screen=Screen.HOME})
            Screen.SETTINGS->SettingsScreen(vm,onPermissions,onBack={screen=Screen.HOME})
        }
        if(update.stage in setOf(TransferStage.PREPARING,TransferStage.RECEIVING,TransferStage.VERIFYING,TransferStage.READY,TransferStage.AWAITING_CONFIRMATION,TransferStage.UPDATED,TransferStage.FAILED))UpdateOverlay(update,cachedUpdate,onInstall,onDismissUpdate)
    }
}

@Composable private fun Page(content:ScalingLazyListScope.()->Unit){
    val state=rememberScalingLazyListState();val scope=rememberCoroutineScope()
    ScalingLazyColumn(
        modifier=Modifier.fillMaxSize().onRotaryScrollEvent {scope.launch {state.scrollBy(it.verticalScrollPixels)};true},
        state=state,
        contentPadding=PaddingValues(horizontal=10.dp,vertical=14.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp),
        content=content
    )
}

@Composable private fun Home(snapshot:JamesSnapshot,vm:WearViewModel,screen:(Screen)->Unit){
    val connection by vm.connection.collectAsStateWithLifecycle();val pending by vm.pending.collectAsStateWithLifecycle()
    val receivedAt by vm.snapshotReceivedAt.collectAsStateWithLifecycle();val heartRows by vm.heart.collectAsStateWithLifecycle()
    val stale=isSnapshotStale(receivedAt);val latestHeart=heartRows.firstOrNull();val stress=vm.stress()
    val heartValue=(latestHeart?.value?:snapshot.heartRate.value)?.toInt()?.let{"$it bpm"}?:"—"
    val heartNote=when{latestHeart==null->snapshot.heartRate.source.ifBlank{"Waiting for reading"};System.currentTimeMillis()-latestHeart.observedAt<5*60_000L->"Recent · ${WearRepository.ageText(latestHeart.observedAt)}";else->"Last measured · ${WearRepository.ageText(latestHeart.observedAt)}"}
    val connectionLabel=when{connection==ConnectionState.SYNCING->"SYNCING";stale->"STALE DATA";connection==ConnectionState.CONNECTED->"PHONE CONNECTED";else->"PHONE DISCONNECTED"}
    Page {
        item {Text("JAMES OS",fontSize=12.sp,fontWeight=FontWeight.Bold,color=Lime,letterSpacing=1.sp)}
        item {ReserveRing(snapshot.battery.value,snapshot.battery.headline)}
        if(stale)item {Text("STALE DATA · ${WearRepository.ageText(receivedAt)}",color=Amber,fontSize=11.sp,fontWeight=FontWeight.Bold)}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){Metric("RECOVERY",snapshot.recovery.value?.toInt()?.let{"$it%"}?:"—",Pink);Metric("SLEEP",snapshot.sleepQuality.value?.toInt()?.let{"$it%"}?:"—",Blue);Metric("STRAIN",snapshot.strain.value?.let{"%.1f".format(it)}?:"—",Blue)}}
        item {SectionLabel("MONITORS")}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)){
            MonitorCard("HEART RATE",heartValue,heartNote,Pink,Modifier.weight(1f)){screen(Screen.HEART)}
            MonitorCard("JAMES STRESS",stress.score?.let{"$it / 100"}?:"Calibrating",if(stress.score==null)"More readings needed" else "${stress.label} · experimental",stressColour(stress.score),Modifier.weight(1f)){screen(Screen.STRESS)}
        }}
        item {ActionCard("MENTAL WELLBEING",snapshot.wellbeing.mentalReserve?.let{"Reserve $it · ${snapshot.wellbeing.reserveLabel}"}?:"Learning your baseline",Lime){screen(Screen.WELLBEING)}}
        item {ActionCard("RIGHT NOW",snapshot.rightNow.liveEnergy?.let{"Energy $it · ${snapshot.rightNow.energyLabel} · Time pressure ${snapshot.rightNow.timePressure?.toString()?:"—"}"}?:"Learning your energy and time pressure",Blue){screen(Screen.RIGHT_NOW)}}
        item {ActionCard("ACTIVITY",snapshot.steps.value?.toLong()?.let{"%,d steps".format(it)}?:"Awaiting watch data",Blue){screen(Screen.ACTIVITY)}}
        item {ActionCard("SLEEP",duration(snapshot.sleep.value),Blue){screen(Screen.SLEEP)}}
        item {SectionLabel("QUICK ACTIONS")}
        item {ActionCard("LOG HOME","Save a simple timeline marker",Lime){vm.quickAction("Home")}}
        item {ActionCard("LOG DRIVING","Save a simple timeline marker",Amber){vm.quickAction("Driving")}}
        item {ActionCard("LOG WORK","Save a simple timeline marker",Blue){vm.quickAction("Work")}}
        item {ActionCard("LOG WALK","Save a simple timeline marker",Lime){vm.quickAction("Walk")}}
        item {ActionCard(connectionLabel,"Last contact ${WearRepository.ageText(vm.repo.lastPhoneContact)} · $pending queued",if(connection==ConnectionState.CONNECTED&&!stale)Lime else if(stale)Amber else Pink){screen(Screen.SETTINGS)}}
    }
}

@Composable private fun RightNowScreen(snapshot:JamesSnapshot,vm:WearViewModel,onBack:()->Unit) {
    Page {
        item {Header("RIGHT NOW",onBack)}
        item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
            MonitorCard("LIVE ENERGY",snapshot.rightNow.liveEnergy?.toString()?:"—",snapshot.rightNow.energyLabel,Blue,Modifier.weight(1f)){}
            MonitorCard("TIME PRESSURE",snapshot.rightNow.timePressure?.toString()?:"—",snapshot.rightNow.timePressureLabel,Amber,Modifier.weight(1f)){}
        }}
        item {InfoCard("SUSTAINABILITY",snapshot.rightNow.sustainability?.let{"$it · ${snapshot.rightNow.sustainabilityLabel}"}?:"Learning","Crash Risk: ${snapshot.rightNow.crashLabel} · experimental",Lime)}
        item {InfoCard("BODY BATTERY",snapshot.battery.value?.let{"$it%"}?:"—","Kept separate from temporary Live Energy.",Pink)}
        item {SectionLabel("ENERGY CHECK-IN")}
        listOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH").forEach {value->item {ActionCard(value,"How energetic do you feel right now?",Blue){vm.rightNowCheckIn("energy",value)}}}
        item {SectionLabel("SHORT OF TIME?")}
        listOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY").forEach {value->item {ActionCard(value,"Time for yourself",Amber){vm.rightNowCheckIn("timePressure",value)}}}
    }
}

@Composable private fun ReserveRing(value:Int?,headline:String){
    val colour=reserveColour(value)
    Column(horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.size(126.dp),contentAlignment=Alignment.Center){
            Canvas(Modifier.fillMaxSize().padding(7.dp)){drawArc(Line,140f,260f,false,style=Stroke(10.dp.toPx(),cap=StrokeCap.Round));if(value!=null)drawArc(colour,140f,260f*(value/100f),false,style=Stroke(10.dp.toPx(),cap=StrokeCap.Round))}
            Text(value?.let{"$it%"}?:"—",fontSize=40.sp,fontWeight=FontWeight.Black)
        }
        Text(headline.uppercase(),fontSize=15.sp,fontWeight=FontWeight.Bold,color=colour,textAlign=TextAlign.Center)
    }
}

@Composable private fun Metric(label:String,value:String,color:Color){Column(horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=18.sp,fontWeight=FontWeight.Bold,color=color);Text(label,fontSize=9.sp,color=Muted,letterSpacing=.7.sp)}}
@Composable private fun SectionLabel(value:String){Text(value,color=Lime,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp,modifier=Modifier.fillMaxWidth().padding(start=8.dp,top=5.dp))}

@Composable private fun MonitorCard(label:String,value:String,note:String,accent:Color,modifier:Modifier=Modifier,onClick:()->Unit){
    Column(modifier.heightIn(min=94.dp).background(Raised,RoundedCornerShape(22.dp)).clickable(onClick=onClick).padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(7.dp).background(accent,CircleShape));Spacer(Modifier.width(6.dp));Text(label,fontSize=9.sp,fontWeight=FontWeight.Bold,color=Muted,letterSpacing=.6.sp)}
        Text(value,fontSize=19.sp,fontWeight=FontWeight.Black,color=Color.White,maxLines=1)
        Text(note,fontSize=9.sp,color=Muted,maxLines=2)
    }
}

@Composable private fun ActionCard(title:String,subtitle:String,accent:Color,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).background(Charcoal,RoundedCornerShape(24.dp)).clickable(onClick=onClick).padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
        Box(Modifier.width(4.dp).height(32.dp).background(accent,RoundedCornerShape(4.dp)));Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Bold,color=Color.White);Text(subtitle,fontSize=10.sp,color=Muted,maxLines=2)}
        Text("›",fontSize=22.sp,color=Muted)
    }
}

@Composable private fun InfoCard(label:String,value:String,body:String="",accent:Color=Lime){
    Column(Modifier.fillMaxWidth().background(Charcoal,RoundedCornerShape(22.dp)).padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Text(label.uppercase(),fontSize=9.sp,fontWeight=FontWeight.Bold,color=accent,letterSpacing=.8.sp)
        Text(value,fontSize=17.sp,fontWeight=FontWeight.Bold,color=Color.White)
        if(body.isNotBlank())Text(body,fontSize=11.sp,color=Muted)
    }
}

@Composable private fun Header(title:String,onBack:()->Unit){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).background(Charcoal,RoundedCornerShape(24.dp)).clickable(onClick=onBack).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){Text("‹",fontSize=25.sp,fontWeight=FontWeight.Bold,color=Lime);Spacer(Modifier.width(8.dp));Text(title,fontSize=15.sp,fontWeight=FontWeight.Bold,color=Color.White)}
}

@Composable private fun HeartScreen(vm:WearViewModel,onBack:()->Unit){
    val rows by vm.heart.collectAsStateWithLifecycle();val phone by vm.snapshot.collectAsStateWithLifecycle();val latest=rows.firstOrNull();val displayed=latest?.value?:phone.heartRate.value
    val recent=latest!=null&&System.currentTimeMillis()-latest.observedAt<5*60_000L
    Page {
        item {Header("HEART MONITOR",onBack)}
        item {Text(if(recent)"RECENT WATCH READING" else "LAST MEASURED",color=if(recent)Lime else Amber,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=.8.sp)}
        item {Text(displayed?.toInt()?.let{"$it bpm"}?:"—",fontSize=40.sp,fontWeight=FontWeight.Black,color=Color.White)}
        item {Text(latest?.let{WearRepository.ageText(it.observedAt)}?:phone.heartRate.source.ifBlank{"No reading available"},fontSize=11.sp,color=Muted)}
        item {val values=rows.map{it.value.toInt()};InfoCard("RECENT RANGE",if(values.isEmpty())"No watch readings yet" else "${values.min()}–${values.max()} bpm","Passive readings collected by Wear Health Services.",Pink)}
        item {InfoCard("RESTING HEART RATE",phone.restingHeartRate.value?.toInt()?.let{"$it bpm"}?:"Unavailable",if(phone.restingHeartRate.value==null)"Waiting for the phone baseline." else "Authoritative baseline supplied by James OS on the phone.",Blue)}
        item {InfoCard("MONITOR STATUS",if(vm.repo.passiveRegistered)"Passive monitoring active" else "Permission required","James samples efficiently in the background; this is not a continuous ECG.",Lime)}
    }
}

@Composable private fun ActivityScreen(snapshot:JamesSnapshot,vm:WearViewModel,onBack:()->Unit){
    val rows by vm.steps.collectAsStateWithLifecycle();val latest=rows.firstOrNull();val recent=latest!=null&&System.currentTimeMillis()-latest.observedAt<10*60_000L
    Page {
        item {Header("ACTIVITY",onBack)}
        item {Text(latest?.value?.toLong()?.let{"%,d".format(it)}?:snapshot.steps.value?.toLong()?.let{"%,d".format(it)}?:"—",fontSize=38.sp,fontWeight=FontWeight.Black)}
        item {Text("STEPS TODAY",color=Blue,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=.8.sp)}
        item {InfoCard("MOVEMENT",if(recent)"Recent step update" else "Activity state unknown",latest?.let{"Measured ${WearRepository.ageText(it.observedAt)}"}?:"Waiting for a passive watch observation.",if(recent)Lime else Amber)}
        item {InfoCard("DETECTION","Platform supplied only","James labels walking, running or workouts only when a supported activity source supplies that state.",Blue)}
    }
}

@Composable private fun StressScreen(vm:WearViewModel,onBack:()->Unit){
    val estimate=vm.stress();val rows by vm.heart.collectAsStateWithLifecycle();val latest=rows.firstOrNull();val samsung by vm.samsungStatus.collectAsStateWithLifecycle();val check by vm.samsungSensorCheck.collectAsStateWithLifecycle()
    Page {
        item {Header("STRESS MONITOR",onBack)}
        check.check?.let {reading->item {InfoCard("LAST CHECK",buildString {append(reading.averageHeartRate?.toInt()?.let{"$it bpm"}?:"No heart rate");reading.hrvRmssd?.let {append(" · HRV ${"%.0f".format(it)} ms")}},listOfNotNull(reading.skinConductance?.let {"EDA ${"%.2f".format(it)} µS"},reading.skinTemperature?.let {"Skin ${"%.1f".format(it)}°C"}).joinToString(" · ").ifBlank {"No extra Samsung signals were available."},Blue)}}
            ?: item {InfoCard("LAST CHECK","No detailed check yet","Tap Check now for a 45-second reading.",Muted)}
        item {ActionCard(if(check.collecting)"CHECK IN PROGRESS" else "CHECK NOW",when {check.collecting->"Collecting your 45-second reading…";samsung.state!="Connected"->samsung.message;else->"Uses only the readings enabled in Settings"},if(check.collecting)Amber else Lime){if(!check.collecting&&samsung.state=="Connected")vm.startSamsungSensorCheck()}}
        if(check.collecting)item {ActionCard("CANCEL CHECK","Discard this in-progress reading",Muted){vm.cancelSamsungSensorCheck()}}
        item {Text(estimate.score?.let{"$it"}?:"—",fontSize=42.sp,fontWeight=FontWeight.Black,color=stressColour(estimate.score))}
        item {Text(estimate.label.uppercase(),fontWeight=FontWeight.Bold,color=stressColour(estimate.score))}
        item {Text(if(latest==null)"Waiting for watch readings" else "Updated from readings ${WearRepository.ageText(latest.observedAt)}",fontSize=10.sp,color=Muted)}
        item {InfoCard("STATUS","${estimate.confidence} estimate",if(estimate.score==null)"James needs several recent heart readings plus a resting-heart-rate baseline." else "This is James's experimental wellbeing signal, not Samsung Stress and not a medical diagnosis.",stressColour(estimate.score))}
        if(estimate.reasons.isNotEmpty())item {InfoCard("WHY JAMES THINKS THIS","Signal explanation",estimate.reasons.joinToString("\n\n"){"• $it"},Blue)}
        item {InfoCard("SAMSUNG SENSOR SERVICE",samsung.state,if(samsung.state=="Connected") "Enhanced signals detected: ${samsung.diagnosticGroups.joinToString(" · ")}. Passive Health Services monitoring stays on for all-day battery life." else samsung.message,if(samsung.state=="Connected")Lime else Muted)}
    }
}

@Composable private fun WellbeingScreen(snapshot:JamesSnapshot,vm:WearViewModel,onBack:()->Unit){
    var saved by rememberSaveable {mutableStateOf("")}
    Page {
        item {Header("MENTAL WELLBEING",onBack)}
        item {InfoCard("MENTAL RESERVE",snapshot.wellbeing.mentalReserve?.let{"$it · ${snapshot.wellbeing.reserveLabel}"}?:"Learning", "Phone-computed personal estimate.", Lime)}
        item {InfoCard("ANXIETY",snapshot.wellbeing.anxietyLoad?.let{"$it · ${snapshot.wellbeing.anxietyLabel}"}?:"Learning", anxietyWatchFreshness(snapshot.wellbeing), stressColour(snapshot.wellbeing.anxietyLoad))}
        item {InfoCard("TREND",snapshot.wellbeing.trend.ifBlank {"Learning"}, "Confidence: ${snapshot.wellbeing.confidence.ifBlank {"Learning"}}", Blue)}
        item {SectionLabel("MOOD CHECK-IN")}
        listOf("VERY LOW","LOW","OKAY","GOOD","GREAT").forEach {mood->
            item {ActionCard(mood,"Save optional check-in to your phone",if(mood in listOf("GOOD","GREAT"))Lime else if(mood in listOf("VERY LOW","LOW"))Amber else Blue){vm.wellbeingCheckIn(mood);saved=mood}}
        }
        if(saved.isNotBlank()) item {InfoCard("SAVED",saved,"Your phone remains the private, authoritative record.",Lime)}
        item {Text("Experimental wellbeing estimates. Not a diagnosis.",fontSize=10.sp,color=Muted,textAlign=TextAlign.Center)}
    }
}
@Composable private fun SleepScreen(snapshot:JamesSnapshot,onBack:()->Unit){
    Page {
        item {Header("SLEEP",onBack)}
        item {Text(duration(snapshot.sleep.value),fontSize=38.sp,fontWeight=FontWeight.Black,color=Blue)}
        item {Text(snapshot.sleepQuality.value?.toInt()?.let{"$it% SLEEP"}?:"SLEEP SCORE UNAVAILABLE",fontWeight=FontWeight.Bold)}
        item {InfoCard("RECOVERY",snapshot.recovery.value?.toInt()?.let{"$it%"}?:"Unavailable","Latest recovery supplied by the James OS phone.",Pink)}
        item {InfoCard("SOURCE","WHOOP / Health Connect","Phone sleep remains authoritative. James does not invent watch sleep stages.",Blue)}
        item {Text("Last phone sync ${snapshot.generatedAt.take(16).replace('T',' ')}",fontSize=10.sp,color=Muted)}
    }
}

@Composable private fun SettingsScreen(vm:WearViewModel,onPermissions:()->Unit,onBack:()->Unit){
    val sensors by vm.repo.sensorSettings.collectAsStateWithLifecycle()
    val caps by vm.capabilities.collectAsStateWithLifecycle();val pending by vm.pending.collectAsStateWithLifecycle();val connection by vm.connection.collectAsStateWithLifecycle();val msg by vm.sensorMessage.collectAsStateWithLifecycle();val samsung by vm.samsungStatus.collectAsStateWithLifecycle()
    Page {
        item {Header("SETTINGS",onBack)}
        item {ActionCard("SENSOR PERMISSIONS",if(vm.repo.passiveRegistered)"Passive monitoring active" else "Tap to enable heart and activity",if(vm.repo.passiveRegistered)Lime else Amber,onPermissions)}
        item {SectionLabel("READINGS & BATTERY")}
        item {ActionCard("PASSIVE HEART · ${if(sensors.passiveHeart)"ON" else "OFF"}","Low battery",if(sensors.passiveHeart)Lime else Muted){vm.sensorSettings(sensors.copy(passiveHeart=!sensors.passiveHeart))}}
        item {ActionCard("STEPS · ${if(sensors.steps)"ON" else "OFF"}","Low battery",if(sensors.steps)Lime else Muted){vm.sensorSettings(sensors.copy(steps=!sensors.steps))}}
        item {ActionCard("CALORIES · ${if(sensors.calories)"ON" else "OFF"}","Low battery",if(sensors.calories)Lime else Muted){vm.sensorSettings(sensors.copy(calories=!sensors.calories))}}
        item {ActionCard("DISTANCE · ${if(sensors.distance)"ON" else "OFF"}","Low battery",if(sensors.distance)Lime else Muted){vm.sensorSettings(sensors.copy(distance=!sensors.distance))}}
        item {ActionCard("AUTO STRESS · ${if(sensors.automaticStress)"ON" else "OFF"}","Low battery · passive only",if(sensors.automaticStress)Lime else Muted){vm.sensorSettings(sensors.copy(automaticStress=!sensors.automaticStress))}}
        item {SectionLabel("CHECK-NOW READINGS")}
        item {ActionCard("CHECK HEART · ${if(sensors.detailHeart)"ON" else "OFF"}","45 sec only",if(sensors.detailHeart)Lime else Muted){vm.sensorSettings(sensors.copy(detailHeart=!sensors.detailHeart))}}
        item {ActionCard("HRV · ${if(sensors.hrv)"ON" else "OFF"}","45 sec only",if(sensors.hrv)Lime else Muted){vm.sensorSettings(sensors.copy(hrv=!sensors.hrv))}}
        item {ActionCard("EDA · ${if(sensors.eda)"ON" else "OFF"}","45 sec only",if(sensors.eda)Lime else Muted){vm.sensorSettings(sensors.copy(eda=!sensors.eda))}}
        item {ActionCard("SKIN TEMPERATURE · ${if(sensors.skinTemperature)"ON" else "OFF"}","45 sec only",if(sensors.skinTemperature)Lime else Muted){vm.sensorSettings(sensors.copy(skinTemperature=!sensors.skinTemperature))}}
        item {InfoCard("CHECK-NOW PRIVACY","No background detail collection","HRV, EDA and skin temperature only run during an explicit 45-second check.",Blue)}
        item {InfoCard("NOT COLLECTED","ECG and raw PPG","Never collected in the background.",Muted)}
                item {ActionCard("SYNC NOW","$pending queued · ${connection.name.lowercase().replace('_',' ')}",Blue){vm.sync()}}
        item {InfoCard("CONNECTION",connection.name.lowercase().replace('_',' ').replaceFirstChar{it.uppercase()},"Last phone contact ${WearRepository.ageText(vm.repo.lastPhoneContact)}",if(connection==ConnectionState.CONNECTED)Lime else Amber)}
        item {InfoCard("DIAGNOSTICS","James OS Wear ${BuildConfig.VERSION_NAME}",buildString {append("Schema $SCHEMA_VERSION\nHealth Services · HR: ${caps?.heartRate==true} · Steps: ${caps?.steps==true}\nSamsung Sensor Service · ${samsung.state}");samsung.serviceVersion?.let {append(" · SDK $it")};if(samsung.state=="Connected")append("\n"+(samsung.diagnosticGroups.ifEmpty {listOf("No supported signals reported")}).joinToString("\n"));append("\nHealth Services remains the passive fallback.");if(msg.isNotBlank())append("\n$msg");if(vm.repo.lastTransferResult.isNotBlank())append("\nLast update: ${vm.repo.lastTransferResult}")},Lime)}
    }
}

@Composable private fun UpdateOverlay(state:UpdateState,cachedUpdate:Boolean,onInstall:()->Unit,onDismiss:()->Unit){
    Box(Modifier.fillMaxSize().background(Color(0xF4070B0D)).padding(22.dp),contentAlignment=Alignment.Center){
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("JAMES OS UPDATE",color=Lime,fontWeight=FontWeight.Bold);Text("v${state.version}",fontSize=24.sp,fontWeight=FontWeight.Black)
            Text(when(state.stage){TransferStage.PREPARING->"Preparing…";TransferStage.RECEIVING->"Receiving ${state.progress}%";TransferStage.VERIFYING->"Verifying";TransferStage.READY->"Update ready";TransferStage.AWAITING_CONFIRMATION->"Awaiting Android confirmation";TransferStage.UPDATED->"James OS updated";TransferStage.FAILED->"Update failed";else->state.stage.name},textAlign=TextAlign.Center)
            if(state.stage==TransferStage.RECEIVING)CircularProgressIndicator(progress=state.progress/100f)
            if(state.message.isNotBlank())Text(state.message,fontSize=11.sp,color=Muted,textAlign=TextAlign.Center)
            // READY is only entered after checksum verification. Keep the action
            // visible while the ViewModel refreshes the persisted-cache flag.
            if((cachedUpdate||state.stage==TransferStage.READY)&&state.stage in setOf(TransferStage.READY,TransferStage.AWAITING_CONFIRMATION,TransferStage.FAILED))UpdateInstallAction(if(state.stage==TransferStage.AWAITING_CONFIRMATION)"TRY INSTALL AGAIN" else "INSTALL UPDATE",onInstall)
            if(state.stage in setOf(TransferStage.AWAITING_CONFIRMATION,TransferStage.UPDATED,TransferStage.FAILED))Text("CLOSE",color=Lime,fontWeight=FontWeight.Bold,modifier=Modifier.clickable(onClick=onDismiss).padding(12.dp))
        }
    }
}

@Composable private fun UpdateInstallAction(label:String,onClick:()->Unit){
    Box(
        Modifier.fillMaxWidth().height(50.dp).background(Lime,RoundedCornerShape(16.dp)).clickable(onClick=onClick),
        contentAlignment=Alignment.Center
    ) { Text(label,color=Color.Black,fontSize=14.sp,fontWeight=FontWeight.Black,letterSpacing=.7.sp) }
}

private fun anxietyWatchFreshness(wellbeing:uk.co.james.wear.data.WellbeingSnapshot):String {
    val calculated=wellbeing.anxietyCalculatedAt.takeIf {it.isNotBlank()}?:return "Awaiting first phone calculation"
    val seconds=runCatching {java.time.Duration.between(java.time.Instant.parse(calculated),java.time.Instant.now()).seconds.coerceAtLeast(0)}.getOrDefault(0)
    val age=when {seconds<60->"Now";seconds<3600->"${seconds/60}m ago";else->"${seconds/3600}h ago"}
    return "${if(seconds>45*60)"Stale · " else ""}Updated ${age}${if(wellbeing.anxietyUnchanged)" · unchanged" else ""}"
}
private fun reserveColour(value:Int?)=when(value?:0){in 0..19->Pink;in 20..44->Amber;else->Lime}
private fun stressColour(score:Int?)=when(score){null->Muted;in 70..100->Pink;in 40..69->Amber;else->Lime}
private fun duration(minutes:Double?)=minutes?.toInt()?.let{"${it/60}h ${it%60}m"}?:"Awaiting phone"
