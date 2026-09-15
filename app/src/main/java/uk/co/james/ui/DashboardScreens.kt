package uk.co.james.ui
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.routines.*
import uk.co.james.state.*
import uk.co.james.timeline.*
import uk.co.james.time.*
import uk.co.james.work.workSessions
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable fun TodayScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    LaunchedEffect(Unit) {while(isActive){vm.healthSyncQuietly();vm.whoopSyncQuietly();delay(5*60*1000L)}}
    val clock=foregroundMinute()
    val wellbeing by vm.wellbeing.collectAsStateWithLifecycle()
    val wellbeingSettings by vm.wellbeingSettings.collectAsStateWithLifecycle()
    val energyTimeSettings by vm.energyTimeSettings.collectAsStateWithLifecycle()
    val compactToday by vm.compactToday.collectAsStateWithLifecycle()
    val stressCheck by vm.wearStressCheck.collectAsStateWithLifecycle()
    // Prepare every calculation that feeds the first dashboard cards off the UI
    // thread. This includes the previously synchronous Right Now calculation.
    val prepared by produceState<TodayPrepared?>(null,records,clock,energyTimeSettings,wellbeing) {
        value=withContext(Dispatchers.Default) {
            val personal=records.filter {it.store=="personalRecords"}.map {it.raw()}
            val routines=records.filter {Habits.due(it.raw(),today())}
            val day=jamesDayWindow(records,clock)
            val wearSignals=records.asSequence().filter {it.kind=="HealthMetric"&&it.source=="wear"&&it.data().text("metric") in setOf("Heart rate","James Stress","HRV","Skin conductance","Skin temperature")}.groupBy {it.data().text("metric")}.mapValues {(_,rows)->rows.maxByOrNull {it.timestamp}!!}.toMutableMap()
            wearSignals["James Stress"]?.let {row->
                val rawValue=row.data().number("value",Double.NaN)
                if(rawValue.isFinite()) {
                    val active=uk.co.james.calibration.JamesCalibrationEngine.activeCalibration(records,"james_stress",uk.co.james.state.JamesAlgorithmRegistry.get("james_stress")!!.calibrationVersion,uk.co.james.state.JamesAlgorithmRegistry.STRESS_VERSION)
                    val calibrated=uk.co.james.calibration.JamesCalibrationEngine.applyActiveValue(rawValue,records,"james_stress")
                    wearSignals["James Stress"]=StoredRecord.from(row.store,row.raw().changed("data" to row.data().changed("value" to p(calibrated),"rawSourceValue" to p(rawValue),"calibrationVersion" to p(active.version),"calibrationSetId" to p(active.setId))))
                }
            }
            val moments=timeline(records).filter {moment->runCatching {Instant.parse(moment.timestamp)>=day.start}.getOrDefault(false)}
            val nutrition=nutritionToday(records,day,clock)
            val right=rightNowSummary(records,energyTimeSettings,clock)
            val context=currentContext(records,clock)
            val balance=lifeBalance(records,clock)
            val health=prepareWhoopOverview(records,clock)
            val compact=prepareCompactToday(records,clock,day,nutrition,right,wellbeing,health,context,balance,wearSignals,energyTimeSettings)
            TodayPrepared(personal,routines,routines.count {Habits.complete(personal,it.recordId,today())},day,
                moments,nutrition,right,context,balance,health,wearSignals,wellbeing,compact)
        }
    }
    if(prepared==null) {
        AdaptiveCards(listOf({PageTitle("Good morning, James.",LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")))},{JamesCard("Health monitor","Preparing current health context") {LinearProgressIndicator(modifier=Modifier.fillMaxWidth())}}),listOf("title","health-monitor"))
        return
    }
    val ready=prepared!!
    if(compactToday) {
        CompactTodayScreen(vm,ready,energyTimeSettings)
        return
    }
    val personal=ready.personal;val routines=ready.routines;val done=ready.done
    val dayBoundary=ready.day;val moments=ready.moments
    val cards=mutableListOf<@Composable ()->Unit>()
    val cardKeys=mutableListOf<String>()
    fun card(key:String,content:@Composable ()->Unit){cardKeys+=key;cards+=content}
    card("title") {PageTitle("Good ${if(LocalTime.now().hour<12)"morning"else if(LocalTime.now().hour<18)"afternoon"else "evening"}, James.",LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")))}
    if(records.none {it.store=="loggedEvents"})card("history-import") {JamesCard("Your history belongs here") {Muted("Import your RUT or James backup to continue your existing journey.");Button(onClick={vm.navigate("Import Centre")}){Text("Import existing RUT data")}}}
    card("health-monitor") {WhoopOverview(
        snapshot=ready.healthOverview,openConnections={vm.navigate("Connections")},
        wellbeing=wellbeing.takeIf {wellbeingSettings.enabled},
        heartRate=ready.wearSignals["Heart rate"],jamesStress=ready.wearSignals["James Stress"],
        openMental={vm.navigate("Insights",true)},checkStressNow=vm::requestWearStressCheck,
        stressCheck=stressCheck
    )}
    card("right-now") {RightNowCard(vm,ready.rightNow,energyTimeSettings)}
    card("current-context") {CurrentContextCard(vm,ready.context)}
    card("life-balance") {LifeBalanceCard(vm,ready.lifeBalance)}
    card("my-day-title") {Column(Modifier.fillMaxWidth().padding(top=4.dp)) {Text("My day",style=MaterialTheme.typography.headlineMedium);Muted(if(dayBoundary.acceptedMainSleepId!=null)"Since your completed main sleep." else "Since the current James Day fallback while sleep is unavailable.")}}
    card("day-at-a-glance") {DayAtAGlance(records,dayBoundary,clock)}
    if(ready.nutrition.hasData())card("nutrition") {NutritionTodayCard(ready.nutrition)}
    card("routines") {JamesCard("Routines",if(routines.isEmpty())"Your daily rhythm"else "$done / ${routines.size} complete") {
        if(routines.isEmpty())Muted("No routines scheduled today. Add a habit or choose its scheduled days.")
        else listOf("Morning","Afternoon","Evening").forEach {period->
            val rs=routines.filter {it.data().text("period")==period}
            if(rs.isNotEmpty()) {val n=rs.count {Habits.complete(personal,it.recordId,today())};Text("$period · $n / ${rs.size}");LinearProgressIndicator(progress={n.toFloat()/rs.size},modifier=Modifier.fillMaxWidth())}
        }
        TextButton(onClick={vm.navigate("Routines")}){Text("Open routines")}
    }}
    card("moments") {JamesCard("Today’s moments","Since your last main sleep") {moments.filter {it.record.kind !in listOf("HealthMetric","PlaceVisit","TimeBlock")}.take(4).forEach {Text(it.title);Muted("${localClock(it.timestamp)} · ${sourceLabel(it.source)}")};if(moments.none {it.record.kind !in listOf("HealthMetric","PlaceVisit","TimeBlock")})Muted("No additional moments recorded yet.");TextButton(onClick={vm.navigate("Timeline",true)}){Text("Open timeline")}}}
    card("journal") {JamesCard("Your journal","Optional") {Muted("Your notes add context that sensors cannot see.");Button(onClick={vm.open("DailyReview",records.firstOrNull {it.kind=="DailyReview"&&it.localDate==today()})}){Text("Write or review today")};TextButton(onClick={vm.navigate("Weekly review")}){Text("Your week")}}}
    records.filter {it.kind=="StateEstimate"&&it.localDate==today()}.takeLast(1).forEach {estimate->card("estimate:"+estimate.recordId) {EstimateCard(vm,estimate)}}
    AdaptiveCards(cards,cardKeys)
}

internal data class TodayPrepared(val personal:List<JsonObject>,val routines:List<StoredRecord>,val done:Int,val day:JamesDayWindow,val moments:List<uk.co.james.timeline.Moment>,val nutrition:NutritionTodayUi,val rightNow:RightNowSummary,val context:ContextLoadSummary,val lifeBalance:LifeBalanceSummary,val healthOverview:WhoopOverviewSnapshot,val wearSignals:Map<String,StoredRecord>,val wellbeing:MentalWellbeingSummary,val compact:CompactTodayUi)
private fun NutritionTodayUi.hasData()=listOf(calories,protein,carbs,fat,waterMl,caffeine).any {it!=null}

@Composable private fun NutritionTodayCard(summary:NutritionTodayUi) {
    var details by rememberSaveable { mutableStateOf(false) }
    JamesCard("Nutrition","Context from Health Connect") {
        val values=listOfNotNull(summary.calories?.let {"CALORIES  ${it.toInt()} kcal"},summary.protein?.let {"PROTEIN  ${it.toInt()} g"},summary.carbs?.let {"CARBS  ${it.toInt()} g"},summary.fat?.let {"FAT  ${it.toInt()} g"},summary.waterMl?.let {"HYDRATION  ${"%.1f".format(it/1000)} L"},summary.caffeine?.let {"CAFFEINE  ${it.toInt()} mg"})
        values.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){row.forEach {Surface(Modifier.weight(1f),shape=RoundedCornerShape(14.dp),color=statePanel){Text(it,Modifier.padding(10.dp),fontWeight=FontWeight.Bold)} };if(row.size==1)Spacer(Modifier.weight(1f))}}
        summary.lastMealAt?.let {Muted("Last meal: ${Duration.between(it,Instant.now()).toMinutes().coerceAtLeast(0)}m ago")}
        Muted("Source: ${nutritionProviderLabel(summary.source)}")
        TextButton(onClick={details=!details}) {Text(if(details)"HIDE DETAILS" else "NUTRITION DETAILS")}
        if(details) {summary.fibre?.let {Text("Fibre ${it.toInt()} g")};summary.sugar?.let {Text("Sugar ${it.toInt()} g")};summary.meals.forEach {meal->Text("${localClock(meal.timestamp)} · ${meal.data().text("title","Meal")}"+(meal.data().number("energyKcal",Double.NaN).takeIf(Double::isFinite)?.let {" · ${it.toInt()} kcal"}?:""))};Muted("MyNetDiary remains your food diary. James OS keeps this as personal context and does not infer metabolic response.")}
    }
}

@Composable private fun RightNowCard(vm:JamesViewModel,summary:RightNowSummary,settings:uk.co.james.settings.EnergyTimeSettings) {
    var details by rememberSaveable {mutableStateOf(false)}
    var energy by rememberSaveable {mutableStateOf("OKAY")}
    var pressure by rememberSaveable {mutableStateOf("NOT AT ALL")}
    JamesCard("Right now","Fast personal context · experimental") {
        val metrics=listOfNotNull(
            if(settings.liveEnergy)Triple("LIVE ENERGY",summary.liveEnergy,when(summary.liveEnergy.score){in 0..39->jamesAmber;in 40..79->jamesBlue;else->stateMint})else null,
            Triple("SLEEPINESS",summary.sleepiness,when(summary.sleepiness.score){in 0..39->stateMint;in 40..59->jamesBlue;else->jamesAmber}),
            if(settings.sustainability)Triple("SUSTAINABILITY",summary.sustainability,when(summary.sustainability.score){in 0..39->jamesAmber;in 40..59->jamesBlue;else->stateMint})else null,
            if(settings.crashRisk)Triple("CRASH RISK",summary.crashRisk,when(summary.crashRisk.score){in 0..49->stateMint;in 50..74->jamesAmber;else->jamesRed})else null,
            if(settings.timePressure)Triple("TIME PRESSURE",summary.timePressure,when(summary.timePressure.score){in 0..39->stateMint;in 40..59->jamesBlue;else->jamesAmber})else null
        )
        metrics.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {row.forEach {(title,metric,colour)->RightNowTile(title,metric,colour,Modifier.weight(1f),if(metric.id=="sleepiness"){{vm.navigate("Algorithm:sleepiness")}}else null)};if(row.size==1)Spacer(Modifier.weight(1f))}}
        summary.nextConstraint?.let {Muted("Next: ${it.title} · ${it.usableMinutes}m usable after ${it.preparationMinutes+it.travelMinutes}m known preparation/travel")}
            ?:Muted("No known upcoming constraint · Time Pressure confidence is limited")
        TextButton(onClick={details=!details},modifier=Modifier.fillMaxWidth()){Text(if(details)"HIDE DETAILS" else "WHY? / DETAILS")}
        if(details) {
            RightNowWhy("LIVE ENERGY",summary.liveEnergy)
            RightNowWhy("SLEEPINESS",summary.sleepiness)
            RightNowWhy("SUSTAINABILITY",summary.sustainability)
            RightNowWhy("CRASH RISK",summary.crashRisk)
            RightNowWhy("TIME PRESSURE",summary.timePressure)
            Divider();Text("QUICK CHECK-INS",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
            if(settings.energyCheckIns){Choice("Energy right now",energy,listOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH")){energy=it};Button(onClick={vm.energyCheckIn(energy)},modifier=Modifier.fillMaxWidth()){Text("SAVE ENERGY")}}
            if(settings.timePressureCheckIns){Choice("Short of time for yourself?",pressure,listOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY")){pressure=it};OutlinedButton(onClick={vm.timePressureCheckIn(pressure)},modifier=Modifier.fillMaxWidth()){Text("SAVE TIME PRESSURE")}}
            summary.nutrition.latestMealAt?.let {Muted("Latest meal context: ${summary.nutrition.minutesSinceMeal}m ago · ${summary.nutrition.source?:"Health Connect"}. No glucose or metabolic response is inferred.")}
        }
        Muted("Live Energy, Sleepiness, Body Battery and Mental Reserve may validly disagree. These are experimental personal estimates, not diagnoses.")
    }
}

@Composable private fun RightNowTile(title:String,metric:RightNowMetric,colour:Color,modifier:Modifier=Modifier,onClick:(()->Unit)?=null) {
    val interactive=if(onClick==null)modifier else modifier.clickable(onClick=onClick)
    Surface(interactive,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {Column(Modifier.heightIn(min=112.dp).padding(13.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {Text(title,color=colour,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Text("${metric.score}",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black);Text(metric.label,fontWeight=FontWeight.Bold,color=colour);Text("${metric.confidence} confidence",style=MaterialTheme.typography.labelSmall,color=stateQuiet)}}
}

@Composable private fun RightNowWhy(title:String,metric:RightNowMetric) {
    Text("WHY $title IS ${metric.label}",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
    if(metric.contributors.isEmpty())Muted("Limited evidence. Missing data does not count against you.") else metric.contributors.filter {it.included}.sortedByDescending {abs(it.contribution)}.forEach {item->
        Text("${if(item.contribution>0)"+" else if(item.contribution<0)"−" else "•"} ${item.name}",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodyMedium)
        Muted(item.explanation+if(item.freshnessMultiplier<.999)" · Freshness ${(item.freshnessMultiplier*100).toInt()}%" else "")
    }
}

@Composable private fun TodayMentalWellbeingCard(summary:MentalWellbeingSummary,onOpen:()->Unit) {
    JamesCard("Mental wellbeing","Personal context · not a diagnosis") {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            Column {
                Text("MENTAL RESERVE",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text("${summary.reserve.score}",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                Text(summary.reserve.label,fontWeight=FontWeight.Bold,color=wellbeingReserveColour(summary.reserve.score))
            }
            Text(summary.reserve.trend,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            TodayWellbeingStat("ANXIETY NOW",summary.anxiety.score,summary.anxiety.label,wellbeingLoadColour(summary.anxiety.score),Modifier.weight(1f))
            TodayWellbeingStat("LOW-MOOD TREND",summary.lowMood.score,summary.lowMood.label,wellbeingLoadColour(summary.lowMood.score),Modifier.weight(1f))
        }
        TextButton(onClick=onOpen,modifier=Modifier.fillMaxWidth()) {Text("OPEN MENTAL WELLBEING  →")}
    }
}
@Composable private fun TodayWellbeingStat(label:String,value:Int,status:String,colour:Color,modifier:Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,color=colour,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
            Text("$value / 100",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Black)
            Text(status,fontWeight=FontWeight.Bold,color=colour)
        }
    }
}

private data class DayActivity(val title:String,val minutes:Long)

private fun overlapMinutes(start:Instant,end:Instant,boundary:Instant,clock:Instant):Long =
    Duration.between(maxOf(start,boundary),minOf(end,clock)).toMinutes().coerceAtLeast(0)

private fun dayActivities(records:List<StoredRecord>,boundary:Instant,clock:Instant):List<DayActivity> {
    val entries=records.mapNotNull {record->
        val data=record.data()
        val title=when(record.kind) {
            "PlaceVisit"->data.text("title").takeIf {it.isNotBlank()&&it!="Unknown place"}?:data.text("category")
            "TimeBlock"->data.text("activity").takeIf {it.isNotBlank()&&it!="Unknown"}?:data.text("category")
            "HealthMetric"->if(data.text("metric")=="Exercise") "Exercise" else ""
            else->""
        }.trim()
        if(title.isBlank()||title=="Unclassified")return@mapNotNull null
        val start=runCatching {Instant.parse(if(record.kind=="HealthMetric")data.text("start",record.timestamp) else record.timestamp)}.getOrNull()?:return@mapNotNull null
        val end=runCatching {Instant.parse(data.text("end",record.timestamp))}.getOrNull()?:return@mapNotNull null
        overlapMinutes(start,end,boundary,clock).takeIf {it>0}?.let {DayActivity(title,it)}
    }
    return entries.groupBy {it.title}.map {(title,items)->DayActivity(title,items.sumOf {it.minutes})}.sortedByDescending {it.minutes}.take(6)
}

private fun dayMetric(records:List<StoredRecord>,metric:String,boundary:Instant,clock:Instant):StoredRecord? =
    records.filter {record->record.kind=="HealthMetric"&&record.data().text("metric")==metric&&runCatching {Instant.parse(record.timestamp) in boundary..clock}.getOrDefault(false)}
        .minWithOrNull(compareBy<StoredRecord> {SourcePolicy.priorities[metric]?.indexOf(it.source)?.takeIf {index->index>=0}?:99}.thenByDescending {it.updatedAt.ifBlank {it.timestamp}})

@Composable private fun DayAtAGlance(records:List<StoredRecord>,boundary:JamesDayWindow,clock:Instant) {
    val activities=dayActivities(records,boundary.start,clock)
    val metrics=listOf("Steps","Calories","Distance","Heart rate").map {it to dayMetric(records,it,boundary.start,clock)}
    JamesCard("Your day",if(boundary.fromWhoop)"WHOOP sleep day" else "Calendar day · waiting for WHOOP") {
        Text("WHERE YOUR DAY WENT",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
        if(activities.isEmpty())Muted("Places and activity appear here once they happen after your main sleep. James records completed visits, driving, work, exercise and errands—not an endless route trace.")
        else activities.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {row.forEach {activity->DayActivityTile(activity,Modifier.weight(1f))};if(row.size==1)Spacer(Modifier.weight(1f))}}
        Spacer(Modifier.height(4.dp));Text("LIVE HEALTH",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
        metrics.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {row.forEach {(label,record)->DayMetricTile(label,record,Modifier.weight(1f))}}}
        val nutrition=records.filter {it.kind=="Nutrition"&&runCatching {Instant.parse(it.timestamp) in boundary.start..clock}.getOrDefault(false)}
        if(nutrition.isNotEmpty()) {
            val kcal=nutrition.sumOf {it.data().number("energyKcal",0.0)}
            val protein=nutrition.sumOf {it.data().number("proteinGrams",0.0)}
            val carbs=nutrition.sumOf {it.data().number("carbohydrateGrams",0.0)}
            val fat=nutrition.sumOf {it.data().number("fatGrams",0.0)}
            Spacer(Modifier.height(4.dp));Text("NUTRITION CONTEXT",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
            Text("${kcal.toInt()} kcal · Protein ${protein.toInt()}g · Carbs ${carbs.toInt()}g · Fat ${fat.toInt()}g",fontWeight=FontWeight.Bold)
            Muted("${nutrition.map {providerLabel(it)}.distinct().joinToString()} · Food logging remains in your nutrition app.")
        }
    }
}

@Composable private fun DayActivityTile(activity:DayActivity,modifier:Modifier=Modifier) {Surface(modifier,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(activity.title.uppercase(),color=stateMint,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1);Text(duration(activity.minutes),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}}}
@Composable private fun DayMetricTile(label:String,record:StoredRecord?,modifier:Modifier=Modifier) {Surface(modifier,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(label.uppercase(),color=stateQuiet,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1);Text(record?.let(::healthValue)?:"—",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1);Text(record?.let(::providerLabel)?:"Awaiting data",color=stateQuiet,style=MaterialTheme.typography.labelSmall,maxLines=1)}}}
@Composable private fun WearSensorMonitor(signals:Map<String,StoredRecord>,checkStatus:uk.co.james.wear.StressCheckStatus,onCheckNow:()->Unit) { JamesCard("Heart & stress","James OS Wear · derived readings") {
    val heart=signals["Heart rate"];val stress=signals["James Stress"];val hrv=signals["HRV"];val eda=signals["Skin conductance"];val temperature=signals["Skin temperature"]
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        WearSignal("HEART RATE",heart?.let(::healthValue)?:"—",heart?.let {"Last saved ${localClock(it.timestamp)}"}?:"Awaiting watch",jamesRed,Modifier.weight(1f))
        val stressScore=stress?.data()?.number("value")
        WearSignal("JAMES STRESS",stressScore?.let(::stressLevel)?:"Awaiting",stressScore?.let {"${"%.1f".format(it)} / 100 · experimental"}?:"Run a Wear sensor check",when(stressScore?.toInt()?:-1){in 80..100->jamesRed;in 60..79->jamesAmber;else->jamesLime},Modifier.weight(1f))
    }
    if(hrv!=null||eda!=null||temperature!=null) {
        Spacer(Modifier.height(8.dp));Text("SENSOR CHECK",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
        Text(listOfNotNull(hrv?.let {"HRV ${healthValue(it)}"},eda?.let {"EDA ${healthValue(it)}"},temperature?.let {"Skin ${healthValue(it)}"}).joinToString("  ·  "),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
        Muted("Saved from a short, opt-in Samsung sensor check. These values need your own baseline before James can interpret changes confidently.")
    }
    Spacer(Modifier.height(6.dp));Button(enabled=!checkStatus.collecting,onClick=onCheckNow,modifier=Modifier.fillMaxWidth()){Text(if(checkStatus.collecting)"CHECK IN PROGRESS" else "CHECK STRESS NOW")}
    if(checkStatus.stage!="IDLE")Muted(checkStatus.message.ifBlank {checkStatus.stage.lowercase().replaceFirstChar {it.uppercase()}})
    Muted("Last saved values stay visible until the watch sends a newer one. Passive updates depend on Wear OS delivery; Check now is the deliberate 45-second refresh. James Stress is experimental—not Samsung Stress or a medical diagnosis.")
}}
@Composable private fun WearSignal(label:String,value:String,note:String,accent:Color,modifier:Modifier=Modifier) { Surface(modifier,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) { Text(label,color=accent,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,maxLines=1);Muted(note) } } }
private fun stressLevel(score:Double)=when {score<20->"Very low";score<40->"Low";score<60->"Medium";score<80->"High";else->"Very high"}
@Composable fun EstimateCard(vm:JamesViewModel,estimate:StoredRecord) {var correcting by rememberSaveable {mutableStateOf(false)};var value by rememberSaveable {mutableStateOf("")};var dismissed by rememberSaveable {mutableStateOf(false)};if(!dismissed)JamesCard("Estimated state","Not a diagnosis") {Text(estimate.data().text("summary"));Muted("Confidence: ${estimate.raw().text("confidence","Unavailable")}");estimate.data().array("factors").forEach {Text(it.toString().trim('"'))};TextButton(onClick={vm.action {vm.repo.save("personalRecords",correction(estimate,"Confirmed"))};dismissed=true}){Text("That’s right")};TextButton(onClick={dismissed=true}){Text("Close")};TextButton(onClick={correcting=true}){Text("Completely wrong")};if(correcting){OutlinedTextField(value,onValueChange={value=it},label={Text("Your correction")});Button(enabled=value.isNotBlank(),onClick={vm.action {vm.repo.save("personalRecords",correction(estimate,value))};correcting=false}){Text("Save correction")}}}
}
@Composable fun TimelineScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val clock=foregroundMinute()
    val date by vm.date.collectAsStateWithLifecycle()
    var source by rememberSaveable {mutableStateOf("all")}
    val selected=runCatching {LocalDate.parse(date)}.getOrElse {LocalDate.now()}
    // Select the James Day containing local noon. The bounded three-day route
    // window supplies sleep/boundary context without reviving historical scans.
    val day=remember(records,selected) { jamesDayWindow(records,selected.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault()) }
    val rawEntries=uk.co.james.timeline.timelineForJamesDay(records,day).filter {entry->
        source=="all"||entry.source.contains(source)||(source=="whoop"&&entry.record.data().text("provider").contains("whoop"))
    }
    // Health Connect can carry the same provider event as a direct API. Keep one
    // compact timeline moment while the database retains both authoritative rows.
    val deduplicated=rawEntries.groupBy {e->if(e.record.kind=="HealthMetric")runCatching {"health:${e.record.data().text("metric")}:${Instant.parse(e.timestamp).epochSecond/300}"}.getOrDefault(e.id)else e.id}
        .map {(_,group)->group.minWith(compareBy {e->if(e.record.data().text("metric")=="Sleep"&&e.source=="whoop")0 else SourcePolicy.priorities[e.record.data().text("metric")]?.indexOf(e.source)?.takeIf {it>=0}?:99})}
        .sortedByDescending {it.timestamp}
    val visitRows=deduplicated.map {it.record}.filter {it.kind=="PlaceVisit"}
    val entries=deduplicated.filterNot {isNarratedInsideVisit(it.record,visitRows)}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {
            PageTitle("Timeline","JAMES DAY · ${displayDate(day.displayDate)}")
            Spacer(Modifier.height(6.dp))
            Text("Your places, moments and meaningful health changes. Frequent passive readings are grouped.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            DateControl(date,vm::date)
            Choice("Source",sourceLabel(source),listOf("All sources","Routines","You","Health Connect","WHOOP","Location","Android","Shift Tracker","Gig Tracker")){label->source=mapOf("All sources" to "all","Routines" to "Routines","You" to "manual","Health Connect" to "health_connect","WHOOP" to "whoop","Location" to "gps","Android" to "android","Shift Tracker" to "shift_tracker","Gig Tracker" to "gig_tracker")[label]?:"all"}
            Row {
                TextButton(onClick={vm.open("Event")}){Text("Add moment")}
                TextButton(onClick={vm.open("TimeBlock")}){Text("Add time")}
            }
        }
        items(entries,key={it.id}) {e->
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
                Text(localClock(e.timestamp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.width(58.dp).padding(top=16.dp))
                Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.width(18.dp)) {
                    Spacer(Modifier.height(18.dp));Surface(Modifier.size(9.dp),shape=RoundedCornerShape(50),color=when(e.source){"whoop"->stateMint;"health_connect"->jamesBlue;"wear"->jamesLime;"gps"->jamesAmber;else->MaterialTheme.colorScheme.primary}){};Box(Modifier.width(1.dp).height(58.dp)){}
                }
                Card(Modifier.weight(1f),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text(if(e.record.kind=="HealthMetric"&&e.record.data().text("metric")=="Sleep"&&e.record.data().flag("nap"))"Nap completed"else e.title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                        Text("${if(e.record.kind=="HealthMetric")providerLabel(e.record)else sourceLabel(e.source)}${if(e.approximate)" · Approximate"else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        if(e.detail.isNotBlank())Text(e.detail,style=MaterialTheme.typography.bodyMedium)
                        if(e.record.kind=="PlaceVisit")visitNarrative(records,e.record).forEach {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                        if(e.record.store=="loggedEvents") TextButton(onClick={vm.open("RutEvent",e.record)},contentPadding=PaddingValues(0.dp)){Text("Edit event")}
                        else if(e.record.kind in listOf("Event","PlaceVisit","TimeBlock","MoodEntry","DailyReview")) TextButton(onClick={vm.open(e.record.kind,e.record)},contentPadding=PaddingValues(0.dp)){Text("Edit")}
                    }
                }
            }
        }
        if(entries.isEmpty())item {JamesCard("No moments recorded"){Muted("Choose another date, import your history or add a moment.")}}
        item {JamesCard("James Day breakdown"){timeBreakdown(records,day.displayDate,clock=clock).forEach {(k,v)->Text("$k · ${duration(v)}")}}}
    }
}
@Composable fun MeScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val events=records.filter {it.store=="loggedEvents"}.map {it.raw()}
    val battery=bodyBattery(records)
    val routines=records.filter {Habits.due(it.raw(),today())}
    val personal=records.filter {it.store=="personalRecords"}.map {it.raw()}
    val complete=routines.count {Habits.complete(personal,it.recordId,today())}
    val recordedDays=records.map {it.localDate}.filter {it.isNotBlank()}.distinct().size
    AdaptiveCards(listOf(
        {PageTitle("Me","THE PERSON BEHIND THE NUMBERS")},
        {Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=stateInk,contentColor=Color.White),modifier=Modifier.fillMaxWidth()) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("Your operating picture",color=stateMint,style=MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                MeStat("RESERVE",battery.value?.let {"$it%"}?:"—",Modifier.weight(1f));MeStat("ROUTINES","$complete/${routines.size}",Modifier.weight(1f));MeStat("HISTORY","$recordedDays days",Modifier.weight(1f))
            }
            Text(battery.summary,color=stateQuiet,style=MaterialTheme.typography.bodyMedium)
        }}},
        {JamesCard("Your systems","Everything about you, in one place") {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={vm.navigate("Routines")},modifier=Modifier.weight(1f)){Text("Routines")};Button(onClick={vm.navigate("Connections")},modifier=Modifier.weight(1f)){Text("Health")}}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.navigate("Location")},modifier=Modifier.weight(1f)){Text("Places")};OutlinedButton(onClick={vm.open("DailyReview",records.firstOrNull {it.kind=="DailyReview"&&it.localDate==today()})},modifier=Modifier.weight(1f)){Text("Journal")}}
        }},
        {JamesCard("Your RUT journey",if(events.isEmpty())"History waiting to import"else Ledger.stage(Ledger.score(events))){Text(if(events.isEmpty())"Your existing history can be imported safely."else "Current journey score  ${Ledger.score(events)}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);TextButton(onClick={vm.navigate("Life events")}){Text("Explore life events  →")}}},
        {JamesCard("Recent reflections","Context sensors cannot capture") {val reports=records.filter {it.kind in listOf("MoodEntry","DailyReview")}.sortedByDescending {it.timestamp}.take(5);if(reports.isEmpty())Muted("Nothing written yet. Journaling is optional.");reports.forEach {r->TextButton(onClick={vm.open(r.kind,r)},modifier=Modifier.fillMaxWidth()) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(displayDate(r.localDate));Text(r.data().text("mood",r.kind),color=MaterialTheme.colorScheme.primary)}}}}}
    ))
}
@Composable private fun MeStat(label:String,value:String,modifier:Modifier=Modifier){Surface(modifier,shape=RoundedCornerShape(16.dp),color=statePanel,contentColor=Color.White){Column(Modifier.padding(horizontal=10.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(label,color=stateQuiet,style=MaterialTheme.typography.labelSmall);Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,maxLines=1)}}}
@Composable fun InsightsScreen(vm:JamesViewModel,records:List<StoredRecord>) {
    val date by vm.date.collectAsStateWithLifecycle()
    val day=LocalDate.parse(date)
    val start=day.minusDays((day.dayOfWeek.value-1).toLong())
    val end=start.plusDays(6)
    val entries=records.filter {it.localDate in start.toString()..end.toString()}
    val wellbeing by vm.wellbeing.collectAsStateWithLifecycle()
    val wellbeingSettings by vm.wellbeingSettings.collectAsStateWithLifecycle()
    val events=entries.filter {it.store=="loggedEvents"&&it.raw().text("type")!="initial"}.map {it.raw()}
    AdaptiveCards(listOf(
        {PageTitle("Your week");DateControl(date,vm::date);Text("${displayDate(start.toString())} — ${displayDate(end.toString())}")},
        {if(wellbeingSettings.enabled) MentalWellbeingCard(vm,wellbeing,wellbeingSettings.checkIns) else JamesCard("Mental wellbeing","Turned off"){Muted("Enable Mental wellbeing insights in Settings whenever you want.")}},
        {JamesCard("Life events","${events.size} observations"){Text("${events.count {it.text("type")=="positive"}} wins · ${events.count {it.text("type")=="negative"}} Rut Pulls · ${events.count {it.text("type")=="recovery"}} recoveries");Text("Net change: ${Ledger.score(events)}");Muted("Blank days are unknown. Counts describe recorded events.");TextButton(onClick={vm.navigate("RUT Insights")}){Text("RUT patterns")}}},
        {JamesCard("Routines"){Text("${entries.count {it.kind=="RoutineCompletion"&&it.data().flag("completed")}} completions");TextButton(onClick={vm.navigate("Routines")}){Text("Streaks and missed days")}}},
        {JamesCard("Mood & energy"){entries.filter {it.kind=="MoodEntry"}.forEach {Text("${it.localDate} · ${it.data().text("mood")} · ${it.data().text("energy")}")};Muted("Personal reports, not inferred diagnoses.")}},
        {JamesCard("Highlights & problems"){entries.filter {it.kind=="DailyReview"}.forEach {r->Text(r.localDate);listOf("good","bad","important").forEach {key->r.data().text(key).takeIf {it.isNotBlank()}?.let {Text(it)}}};TextButton(onClick={vm.open("WeekReflection",records.firstOrNull {it.store=="metadata"&&it.recordId=="week-reflection:$start"})}){Text("Weekly reflection")}}},
        {JamesCard("Weekly health review","Recorded health, not a diagnosis") {
            val healthRows=entries.filter {it.kind=="HealthMetric"}
            fun latest(metric:String)=healthRows.filter {it.data().text("metric")==metric}.lastOrNull()
            if(healthRows.isEmpty()) Muted("No health readings were recorded this week.") else {
                latest("Sleep")?.let {Text("Latest sleep: ${it.data().number("value")?.toInt() ?: "—"} ${it.data().text("unit")}")}
                latest("Recovery")?.let {Text("Latest recovery: ${it.data().number("value")?.toInt() ?: "—"}%")}
                latest("Strain")?.let {Text("Latest strain: ${it.data().number("value")?.let {"%.1f".format(it)} ?: "—"}")}
                Muted("Readings remain labelled with their original source in Settings → Data status.")
            }
        }}
    ))
}
@Composable private fun MentalWellbeingCard(vm:JamesViewModel,summary:MentalWellbeingSummary,checkInsEnabled:Boolean) {
    var mood by rememberSaveable {mutableStateOf("OKAY")}
    var energy by rememberSaveable {mutableStateOf("OKAY")}
    var anxiety by rememberSaveable {mutableStateOf("NONE")}
    JamesCard("Mental wellbeing","Experimental personal trends · not a diagnosis") {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
            WellbeingRing(summary.reserve.score,wellbeingReserveColour(summary.reserve.score))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text("YOUR CAPACITY",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text(summary.reserve.label,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black)
                Text("Mental Reserve",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Muted("Trend: ${summary.reserve.trend}")
            }
        }
        Text("LIVE WELLBEING VIEW",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            WellbeingMetric("ANXIETY LOAD",summary.anxiety.score,summary.anxiety.label,wellbeingLoadColour(summary.anxiety.score),Modifier.weight(1f))
            WellbeingMetric("LOW-MOOD LOAD",summary.lowMood.score,summary.lowMood.label,wellbeingLoadColour(summary.lowMood.score),Modifier.weight(1f),summary.lowMood.trend)
        }
        Muted("Confidence: ${summary.reserve.confidence} · ${summary.days} days learning · ${summary.usableHrv} HRV · ${summary.sleepDays} sleep days.")
        summary.anxietyDiagnostics?.let {diagnostic->
            Divider()
            Text("ANXIETY UPDATE DIAGNOSTICS",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
            Muted("Last recalculated: ${diagnostic.calculatedAt}")
            Muted("Trigger: ${diagnostic.trigger}")
            Muted("Newest input: ${diagnostic.newestInputAt?:"Unavailable"} · Next eligible: ${diagnostic.nextEligibleAt}")
            Muted("Previous: ${diagnostic.previousScore?.toString()?:"—"} · Current: ${diagnostic.currentScore}${if(diagnostic.unchanged)" · unchanged" else ""}")
            Muted("Inputs used: ${diagnostic.inputTimestamps.joinToString {it.source+" "+it.timestamp.take(16)}}")
        }
        Text("WHY JAMES THINKS THIS",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
        WellbeingContributionBreakdown("MENTAL RESERVE",summary.reserve.contributors)
        WellbeingContributionBreakdown("ANXIETY LOAD",summary.anxiety.contributors)
        WellbeingContributionBreakdown("LOW-MOOD LOAD",summary.lowMood.contributors)
        if(checkInsEnabled) {
            Divider()
            Text("HOW ARE YOU FEELING?",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold)
            Choice("Mood",mood,listOf("VERY LOW","LOW","OKAY","GOOD","GREAT")){mood=it}
            Choice("Energy",energy,listOf("VERY LOW","LOW","OKAY","GOOD","HIGH")){energy=it}
            Choice("Anxiety right now",anxiety,listOf("NONE","LOW","MODERATE","HIGH","VERY HIGH")){anxiety=it}
            Button(onClick={vm.wellbeingCheckIn(mood,energy,anxiety)},modifier=Modifier.fillMaxWidth()){Text("SAVE OPTIONAL CHECK-IN")}
        } else Muted("Mood check-ins are turned off in Settings.")
        Muted("Personal wellbeing estimates, never a diagnosis or emergency decision.")
    }
}
@Composable private fun WellbeingContributionBreakdown(target:String,contributors:List<WellbeingContribution>) {
    if(contributors.isEmpty()) {
        Muted("${target.lowercase().replaceFirstChar {it.uppercase()}} has limited evidence right now. Missing inputs reduce confidence; they do not count against you.")
        return
    }
    val isLoad=target!="MENTAL_RESERVE"
    fun state(item:WellbeingContribution)=when {
        kotlin.math.abs(item.contribution)<.05 -> "NEUTRAL / LIMITED EVIDENCE"
        (!isLoad&&item.contribution>0)||(isLoad&&item.contribution<0) -> "HELPING"
        else -> "HURTING"
    }
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.38f)) {
        Column(Modifier.padding(13.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(target,style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
            listOf("HELPING","HURTING","NEUTRAL / LIMITED EVIDENCE").forEach {section->
                val rows=contributors.filter {state(it)==section}
                if(rows.isNotEmpty()) {
                    Text(section,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Black,color=when(section){"HELPING"->stateMint;"HURTING"->jamesAmber;else->MaterialTheme.colorScheme.onSurfaceVariant})
                    rows.sortedByDescending {kotlin.math.abs(it.contribution)}.forEach {item->WellbeingContributionRow(target,item,section)}
                }
            }
        }
    }
}
@Composable private fun WellbeingContributionRow(target:String,item:WellbeingContribution,section:String) {
    val positive=item.contribution>0
    val sign=if(positive)"+" else if(item.contribution<0)"−" else "±"
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
        Text("${if(section=="HELPING")"+" else if(section=="HURTING")"−" else "•"} ${item.source}",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodyMedium)
        wellbeingContributionComparison(item)?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        Text("${target.replace('_',' ')} ${sign}${String.format(java.util.Locale.UK,"%.1f",kotlin.math.abs(item.contribution))}",style=MaterialTheme.typography.labelLarge,fontWeight=FontWeight.Bold,color=when(section){"HELPING"->stateMint;"HURTING"->jamesAmber;else->MaterialTheme.colorScheme.onSurfaceVariant})
        if(item.measurementSource.isNotBlank())Text("Source: ${item.measurementSource} · ${item.measurementContext}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(item.preCapContribution!=null&&item.postCapContribution!=null)Text("Raw evidence: ${String.format(java.util.Locale.UK,"%.1f",item.preCapContribution)} · after cap: ${String.format(java.util.Locale.UK,"%.1f",item.postCapContribution)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        contributorFreshnessLine(item)?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        if(item.freshnessMultiplier<.999)Text("Freshness strength: ${(item.freshnessMultiplier*100).toInt()}%${if(!item.included)" · excluded" else ""}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Confidence: ${wellbeingContributionConfidence(item.confidence)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Muted(item.explanation)
    }
}
private fun relativeAge(stamp:String):String=runCatching {val minutes=java.time.Duration.between(java.time.Instant.parse(stamp),java.time.Instant.now()).toMinutes().coerceAtLeast(0);when {minutes<1->"now";minutes<60->"${minutes}m ago";minutes<1440->"${minutes/60}h ago";else->"${minutes/1440}d ago"}}.getOrDefault("at an unknown time")
internal fun contributorFreshnessLine(item:WellbeingContribution):String?=item.observedAt?.let {"Observed ${relativeAge(it)} · ${item.freshnessClass.replace('_',' ').lowercase()} · ${item.freshnessState.lowercase()}"}
private fun wellbeingContributionConfidence(value:Double)=when {
    value>=.8->"Good (${(value*100).toInt()}%)"
    value>=.55->"Moderate (${(value*100).toInt()}%)"
    else->"Limited (${(value*100).toInt()}%)"
}
private fun wellbeingContributionComparison(item:WellbeingContribution):String? {
    fun value(value:Double)=when {
        item.source.contains("Sleep",true)->"${(value/60).toInt()}h ${value.toInt()%60}m"
        item.source.contains("Personal time",true)->"${(value/60).toInt()}h ${value.toInt()%60}m"
        item.source.contains("HRV",true)->"${String.format(java.util.Locale.UK,"%.1f",value)} ms"
        item.source.contains("Recovery",true)||item.source.contains("Body Battery",true)->"${value.toInt()}%"
        item.source.contains("Stress",true)||item.source.contains("Anxiety",true)->"${String.format(java.util.Locale.UK,"%.1f",value)} / 100"
        item.source.contains("Exercise",true)->"${value.toInt()} session${if(value.toInt()==1)"" else "s"}"
        item.source.contains("diversity",true)->"${value.toInt()} contexts"
        item.source.contains("Rut",true)->value.toInt().toString()
        else->String.format(java.util.Locale.UK,"%.1f",value)
    }
    val current=item.current?:return null
    val baseline=item.baseline
    return if(baseline!=null)"${value(current)} vs ${value(baseline)} recent baseline" else "${value(current)} recorded"
}

@Composable private fun WellbeingRing(value:Int,colour:Color) {
    val track=MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(106.dp),contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width=9.dp.toPx()
            drawArc(track,140f,260f,false,style=Stroke(width,cap=StrokeCap.Round))
            drawArc(colour,140f,260f*(value/100f),false,style=Stroke(width,cap=StrokeCap.Round))
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("$value",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
            Text("RESERVE",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
        }
    }
}
@Composable private fun WellbeingMetric(label:String,value:Int,status:String,colour:Color,modifier:Modifier=Modifier,trend:String="") {
    val detail=when(label) {
        "ANXIETY LOAD" -> when {
            value<20->"Little physiological pressure"
            value<40->"Below your usual stress range"
            value<60->"A few signals are elevated"
            else->"Several signals need recovery"
        }
        else -> if(trend.isBlank())"Personal trend is still learning" else trend.replace("↗","").replace("↘","").trim().lowercase().replaceFirstChar {it.uppercase()}
    }
    Surface(modifier,shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                Text(label,color=colour,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                WellbeingMiniGauge(value,colour)
            }
            Row(verticalAlignment=Alignment.Bottom) {
                Text("$value",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                Text(" /100",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=5.dp))
            }
            Text(status,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Black,color=colour)
            LinearProgressIndicator(progress={value/100f},modifier=Modifier.fillMaxWidth().height(5.dp),color=colour,trackColor=MaterialTheme.colorScheme.surface)
            Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun WellbeingMiniGauge(value:Int,colour:Color) {
    val track=MaterialTheme.colorScheme.surface
    Canvas(Modifier.size(30.dp)) {
        val width=5.dp.toPx()
        drawArc(track,140f,260f,false,style=Stroke(width,cap=StrokeCap.Round))
        drawArc(colour,140f,260f*(value/100f),false,style=Stroke(width,cap=StrokeCap.Round))
    }
}
private fun wellbeingLoadColour(value:Int)=when(value){in 0..19->Color(0xFF55C7F3);in 20..39->Color(0xFFAEF76E);in 40..59->Color(0xFFFFCF67);else->Color(0xFFFF6B7A)}
private fun wellbeingReserveColour(value:Int)=when(value){in 0..19->Color(0xFFFF6B7A);in 20..39->Color(0xFFFFCF67);in 40..59->Color(0xFF55C7F3);else->Color(0xFFAEF76E)}

@Composable fun NovaScreen(vm:JamesViewModel,records:List<StoredRecord>,export:(String?)->Unit) {var question by rememberSaveable {mutableStateOf("")};AdaptiveCards(listOf({PageTitle("Nova","A LITTLE PERSPECTIVE")},{JamesCard("Your life. With context.","AI provider not connected"){Muted("No personal data is being sent to an AI service.");listOf("How was my week?","Why am I tired today?","Am I keeping up with routines?").forEach {q->TextButton(onClick={question=q}){Text(q)}};OutlinedTextField(question,onValueChange={question=it},label={Text("Ask Nova")},modifier=Modifier.fillMaxWidth());Button(onClick={vm.message.value="Nova’s data access is prepared; an AI provider is not connected."},enabled=question.isNotBlank()){Text("Ask Nova")};TextButton(onClick={export(null)}){Text("Export my data")}}}))}

@Composable private fun CurrentContextCard(vm:JamesViewModel,summary:ContextLoadSummary) {
    var chooser by rememberSaveable {mutableStateOf(false)}
    JamesCard("Current context","EXPERIMENTAL · PRIVATE") {
        val active=summary.active
        if(active==null) {
            Muted("No context is active. Unknown is neutral: James OS does not infer it from a place.")
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf("HOME","WORK","RESTING").forEach {type->OutlinedButton(onClick={vm.startContext(type)},modifier=Modifier.weight(1f)){Text(type,style=MaterialTheme.typography.labelSmall)}}
            }
        } else {
            Text(active.placeName?:"CURRENT PERIOD",fontWeight=FontWeight.Bold)
            Text(active.visitType.contextLabel(),fontWeight=FontWeight.Black,color=jamesLime)
            Text("Context Load: ${summary.score} ${summary.label.name.replace('_',' ')}")
            Muted(if(summary.difficultActive)"DIFFICULT ACTIVE" else "Difficult: not active")
            Muted(summary.explanation)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={chooser=!chooser},modifier=Modifier.weight(1f)){Text("CHANGE")}
                OutlinedButton(onClick={if(summary.difficultActive)vm.endDifficult()else vm.markDifficult()},modifier=Modifier.weight(1f)){Text(if(summary.difficultActive)"END DIFFICULT" else "MARK DIFFICULT")}
            }
            if(chooser)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {listOf("HOME","WORK","RESTING").forEach {type->TextButton(onClick={vm.endContext();vm.startContext(type)}){Text(type)}}}
            TextButton(onClick={vm.endContext()}){Text("END CONTEXT")}
        }
    }
}
@Composable private fun LifeBalanceCard(vm:JamesViewModel,summary:LifeBalanceSummary) {
    val current=summary.current
    JamesCard("Life Balance","${summary.trend} · ${summary.autonomy}") {
        if(current.score==null) Muted("Not enough confirmed time ownership yet. Historical Rut entries remain unchanged.")
        else {
            Text("Personal: ${current.personalMinutes/60}h ${current.personalMinutes%60}m · Work: ${current.workMinutes/60}h ${current.workMinutes%60}m")
            Text("Obligation: ${current.obligationMinutes/60}h ${current.obligationMinutes%60}m · Constrained: ${current.constrainedMinutes/60}h ${current.constrainedMinutes%60}m")
            if(summary.helping.isNotEmpty())Muted("Helping: ${summary.helping.joinToString(" · ")}")
            if(summary.hurting.isNotEmpty())Muted("Hurting: ${summary.hurting.joinToString(" · ")}")
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Gym","Gaming","Walk").forEach {activity->OutlinedButton(onClick={vm.logLifeActivity(activity)},modifier=Modifier.weight(1f)){Text(activity,style=MaterialTheme.typography.labelSmall)}}
        }
        TextButton(onClick={vm.navigate("Life Balance")}){Text("DETAILS →")}
    }
}
