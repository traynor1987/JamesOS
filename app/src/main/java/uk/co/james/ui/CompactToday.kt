package uk.co.james.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.settings.EnergyTimeSettings
import uk.co.james.state.*
import uk.co.james.timeline.Moment
import uk.co.james.time.JamesDayWindow
import uk.co.james.work.deriveCurrentWorkState
import uk.co.james.work.WorkMode
import uk.co.james.location.ownershipIntervals
import uk.co.james.location.ownershipSummary
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal data class CompactMetricUi(val id:String,val title:String,val score:Int?,val label:String,val trend:String?=null)
internal data class CompactPromotion(val id:String,val title:String,val label:String,val detail:String,val severity:Int)
internal data class CompactTodayUi(
    val primary:List<CompactMetricUi>,
    val mental:List<CompactMetricUi>,
    val summary:String?,
    val promotions:List<CompactPromotion>,
    val nutrition:List<String>,
    val work:List<String>,
    val activity:List<String>,
    val time:List<String>,
    val places:List<String>,
    val sleep:List<String>,
    val timeline:List<Moment>,
    val routines:List<String>,
    val needsHistoryImport:Boolean
)

private fun reserveLabel(value:Int?)=when(value){null->"UNKNOWN";in 0..19->"DEPLETED";in 20..39->"RUNNING LOW";in 40..59->"MODERATE";in 60..79->"GOOD";else->"HIGH"}
private fun loadLabel(value:Int?)=when(value){null->"UNKNOWN";in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";else->"VERY HIGH"}
private fun metricAt(records:List<StoredRecord>,metric:String,day:JamesDayWindow,clock:Instant)=records.asSequence()
    .filter {it.kind=="HealthMetric"&&it.data().text("metric")==metric}
    .filter {runCatching {Instant.parse(it.timestamp) in day.start..clock}.getOrDefault(false)}
    .maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}

/** Pure, bounded presentation preparation. It does not calculate or persist a score. */
internal fun prepareCompactToday(
    records:List<StoredRecord>,clock:Instant,day:JamesDayWindow,nutrition:NutritionTodayUi,
    right:RightNowSummary,wellbeing:MentalWellbeingSummary,health:WhoopOverviewSnapshot,
    context:ContextLoadSummary,life:LifeBalanceSummary,wearSignals:Map<String,StoredRecord>,
    settings:EnergyTimeSettings
):CompactTodayUi {
    val primary=listOf(
        CompactMetricUi("body_battery","BODY BATTERY",health.battery.value,reserveLabel(health.battery.value)),
        CompactMetricUi("live_energy","LIVE ENERGY",right.liveEnergy.score,right.liveEnergy.label),
        CompactMetricUi("sleepiness","SLEEPINESS",right.sleepiness.score,right.sleepiness.label),
        CompactMetricUi("mental_reserve","MENTAL RESERVE",wellbeing.reserve.score,wellbeing.reserve.label,wellbeing.reserve.trend)
    )
    val stress=wearSignals["James Stress"]?.data()?.number("value",Double.NaN)?.takeIf(Double::isFinite)?.toInt()?.coerceIn(0,100)
    val mental=buildList {
        add(CompactMetricUi("james_stress","STRESS",stress,loadLabel(stress)))
        add(CompactMetricUi("anxiety_load","ANXIETY",wellbeing.anxiety.score,wellbeing.anxiety.label,wellbeing.anxiety.trend))
        add(CompactMetricUi("low_mood_load","LOW-MOOD",wellbeing.lowMood.score,wellbeing.lowMood.label,wellbeing.lowMood.trend))
        if(settings.timePressure)add(CompactMetricUi("time_pressure","TIME PRESSURE",right.timePressure.score,right.timePressure.label))
    }
    val summary=compactCurrentSummary(right.liveEnergy.score,right.sleepiness.score,health.battery.value,wellbeing.reserve.score)
    val candidates=compactPromotions(right.crashRisk.score,right.crashRisk.label,right.timePressure.score,right.timePressure.label,right.nextConstraint?.usableMinutes,right.nextConstraint?.title,context.difficultActive,context.difficultMinutes,settings)
    fun amount(value:Double?,unit:String)=value?.let {"${it.toInt()}$unit"}
    val nutritionLines=buildList {
        nutrition.calories?.let {add("${it.toInt()} kcal")}
        listOfNotNull(amount(nutrition.protein,"g protein"),amount(nutrition.carbs,"g carbs"),amount(nutrition.fat,"g fat")).takeIf {it.isNotEmpty()}?.let {add(it.joinToString(" · "))}
        nutrition.waterMl?.let {add(String.format(java.util.Locale.UK,"%.1fL water",it/1000.0))}
        nutrition.caffeine?.let {add("${it.toInt()}mg caffeine")}
    }
    val workState=deriveCurrentWorkState(records,clock)
    val workLines=if(workState.mode in setOf(WorkMode.AT_STORE,WorkMode.WORKING,WorkMode.DELIVERY,WorkMode.BREAK,WorkMode.TASK)) listOfNotNull(workState.session?.let { compactDuration(it.shiftMinutes) }, workState.mode.name.replace("_"," ")) else emptyList()
    val activityLines=listOfNotNull(
        metricAt(records,"Steps",day,clock)?.let {"${healthValue(it)} steps"},
        metricAt(records,"Distance",day,clock)?.let {"${healthValue(it)} distance"},
        metricAt(records,"Exercise",day,clock)?.let {"Exercise ${healthValue(it)}"}
    ).take(3)
    val ownership=ownershipSummary(ownershipIntervals(records,day.start,clock))
    val timeLines=buildList {listOf("Personal" to ownership.autonomousMinutes,"Work" to ownership.workMinutes,"Obligation" to ownership.committedMinutes,"Constrained" to ownership.constrainedMinutes,"Unknown" to ownership.unknownMinutes).forEach {(name,minutes)->if(minutes>0)add("$name ${compactDuration(minutes)}")}}
    val placeLines=records.filter {it.kind=="PlaceVisit"}.mapNotNull {row->
        val start=runCatching {Instant.parse(row.timestamp)}.getOrNull()?:return@mapNotNull null
        val end=runCatching {Instant.parse(row.data().text("end"))}.getOrNull()?:clock
        val minutes=Duration.between(maxOf(start,day.start),minOf(end,clock)).toMinutes().coerceAtLeast(0)
        val name=row.data().text("title").takeIf {it.isNotBlank()&&it!="Unknown place"}?:return@mapNotNull null
        name to minutes
    }.groupBy {it.first}.mapValues {it.value.sumOf {pair->pair.second}}.entries.sortedByDescending {it.value}.take(3).map {"${it.key} ${compactDuration(it.value)}"}
    val sleepLines=buildList {
        health.sleep?.let {row->
            add("${healthValue(row)}")
            val start=row.data().text("start").takeIf(::validTime)?.let {localClock(it)}
            val end=row.data().text("end").takeIf(::validTime)?.let {localClock(it)}
            if(start!=null&&end!=null)add("$start → $end")
        }
        health.recovery?.let {add("WHOOP Recovery ${healthValue(it)}")}
    }
    val timeline=compactTimeline(uk.co.james.timeline.timeline(records),day)
    val due=records.filter {uk.co.james.routines.Habits.due(it.raw(),today())}
    val personal=records.filter {it.store=="personalRecords"}.map {it.raw()}
    val routineLines=if(due.isEmpty()) emptyList() else listOf("${due.count {uk.co.james.routines.Habits.complete(personal,it.recordId,today())}} / ${due.size} complete")
    return CompactTodayUi(primary,mental,summary,candidates,nutritionLines,workLines,activityLines,timeLines,placeLines,sleepLines,timeline,routineLines,records.none {it.store=="loggedEvents"})
}

internal fun compactCurrentSummary(liveEnergy:Int,sleepiness:Int,bodyBattery:Int?,mentalReserve:Int):String?=when {
        liveEnergy>=60&&sleepiness>=60&&((bodyBattery?:100)<50||mentalReserve<50)->"You're fairly energised, but Sleepiness is high and your underlying reserves are limited."
        liveEnergy>=60&&sleepiness>=60->"Current energy is high, while Sleepiness is also elevated. These can validly disagree."
        mentalReserve<30&&(bodyBattery?:100)>=50->"Physical Reserve is holding up, but usable mental capacity is limited."
        else->null
    }
internal fun compactPromotions(crashScore:Int,crashLabel:String,pressureScore:Int,pressureLabel:String,usableMinutes:Long?,constraintTitle:String?,difficult:Boolean,difficultMinutes:Long,settings:EnergyTimeSettings):List<CompactPromotion> = buildList {
        if(settings.crashRisk&&crashScore>=50)add(CompactPromotion("crash","CRASH RISK",crashLabel,"Current energy may be less sustainable.",100+crashScore))
        if(settings.timePressure&&pressureScore>=80)add(CompactPromotion("pressure","TIME PRESSURE",pressureLabel,if(usableMinutes!=null&&constraintTitle!=null)"${usableMinutes}m usable before $constraintTitle." else "Usable personal time is very limited.",80+pressureScore))
        if(difficult)add(CompactPromotion("difficult","DIFFICULT CONTEXT","ACTIVE","${difficultMinutes}m recorded in the current context.",70))
    }.sortedWith(compareByDescending<CompactPromotion>{it.severity}.thenBy {it.id}).take(2)
internal fun compactTimeline(moments:List<Moment>,day:JamesDayWindow):List<Moment> = moments.asSequence()
        .filter {runCatching {Instant.parse(it.timestamp)>=day.start}.getOrDefault(false)}
        .filterNot {it.record.kind=="EnergySnapshot"}
        .filterNot {it.record.kind=="HealthMetric"&&it.record.data().text("metric") !in setOf("Sleep","Exercise")}
        .take(12).toList()

private fun compactDuration(minutes:Long)=if(minutes<60)"${minutes}m" else "${minutes/60}h ${minutes%60}m"

@Composable internal fun CompactTodayScreen(vm:JamesViewModel,ready:TodayPrepared,settings:EnergyTimeSettings) {
    val state=ready.compact
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item("title") {Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Column(Modifier.widthIn(max=840.dp)){PageTitle("Good ${if(LocalTime.now().hour<12)"morning" else if(LocalTime.now().hour<18)"afternoon" else "evening"}, James.",LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM")))}}}
        if(state.needsHistoryImport)item("history-import") {JamesCard("Your history belongs here"){Muted("Import your RUT or James backup to continue your existing journey.");Button(onClick={vm.navigate("Import Centre")},modifier=Modifier.fillMaxWidth()){Text("IMPORT EXISTING DATA")}}}
        item("right-now") {CompactSection("RIGHT NOW") {
            state.primary.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){row.forEach {metric->CompactMetricCard(metric,Modifier.weight(1f)){openMetric(vm,metric.id)}}}}
            state.summary?.let {Muted(it)}
            if(settings.sustainability)CompactStatusRow("ENERGY SUSTAINABILITY",ready.rightNow.sustainability.label,"Current energy may not reflect how long it will last."){vm.navigate("Algorithm:energy_sustainability")}
            if(settings.crashRisk&&ready.rightNow.crashRisk.score<50)Muted("Crash Risk ${ready.rightNow.crashRisk.label.lowercase()} · open Sustainability for details.")
            state.promotions.filter {it.id=="crash"}.forEach {CompactPriority(it){vm.navigate("Algorithm:crash_risk")}}
        }}
        item("mental-context") {CompactSection("MENTAL & CONTEXT") {
            state.mental.chunked(2).forEach {row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){row.forEach {metric->CompactMetricCard(metric,Modifier.weight(1f),small=true){openMetric(vm,metric.id)}};if(row.size==1)Spacer(Modifier.weight(1f))}}
            state.promotions.filter {it.id=="pressure"}.forEach {CompactPriority(it){vm.navigate("Algorithm:time_pressure")}}
            CompactContext(vm,ready.context)
            CompactLifeBalance(vm,ready.lifeBalance)
        }}
        item("today-so-far") {CompactSection("TODAY SO FAR") {
            CompactSummaryRow("NUTRITION",state.nutrition.ifEmpty {listOf("Not logged")}){vm.navigate("Connections")}
            if(state.work.isNotEmpty())CompactSummaryRow("WORK",state.work){vm.navigate("Connections")}
            CompactSummaryRow("ACTIVITY",state.activity.ifEmpty {listOf("No activity summary yet")}){vm.open("TimeBlock")}
            CompactSummaryRow("TIME OWNERSHIP",state.time.ifEmpty {listOf("No time ownership confirmed yet")}){vm.navigate("Life Balance")}
            if(state.places.isNotEmpty())CompactSummaryRow("PLACES",state.places){vm.navigate("Location")}
            CompactSummaryRow("LAST SLEEP",state.sleep.ifEmpty {listOf("Sleep data unavailable")}){vm.navigate("Connections")}
            if(state.routines.isNotEmpty())CompactSummaryRow("ROUTINES",state.routines){vm.navigate("Routines")}
            CompactQuickActions(vm,ready.context)
        }}
        item("timeline-title") {CompactHeading("TIMELINE")}
        items(state.timeline,key={it.id},contentType={"compact-timeline"}) {moment->CompactTimelineRow(moment)}
        if(state.timeline.isEmpty())item("timeline-empty") {JamesCard("No moments yet"){Muted("Meaningful events will appear here as your James Day unfolds.")}}
        item("timeline-more") {TextButton(onClick={vm.navigate("Timeline",true)},modifier=Modifier.fillMaxWidth()){Text("OPEN FULL TIMELINE")}}
    }
}

private fun openMetric(vm:JamesViewModel,id:String)=when(id){"mental_reserve","anxiety_load","low_mood_load"->vm.navigate("Insights",true);else->vm.navigate("Algorithm:$id")}

@Composable private fun CompactSection(title:String,content:@Composable ColumnScope.()->Unit)=Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){Column(Modifier.widthIn(max=840.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){CompactHeading(title);content()}}
@Composable private fun CompactHeading(title:String)=Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)

@Composable internal fun CompactMetricCard(metric:CompactMetricUi,modifier:Modifier=Modifier,small:Boolean=false,onClick:()->Unit={}) {
    val accent=when(metric.id){"body_battery","mental_reserve"->if((metric.score?:0)<40)jamesAmber else stateMint;"live_energy"->if((metric.score?:0)>=60)stateMint else jamesBlue;else->if((metric.score?:0)>=60)jamesAmber else stateMint}
    Surface(modifier.clickable(onClick=onClick).semantics {contentDescription="${metric.title}, ${metric.score?:"unknown"}, ${metric.label}"},shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Column(Modifier.height(if(small)96.dp else 116.dp).padding(12.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(metric.title,color=accent,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1)
            Text(metric.score?.toString()?:"—",style=if(small)MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
            Text(metric.label,color=accent,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            metric.trend?.takeIf {it.isNotBlank()&&it!="LEARNING"}?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=stateQuiet,maxLines=1)}
        }
    }
}

@Composable private fun CompactStatusRow(title:String,label:String,detail:String,onClick:()->Unit) {Surface(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f)){Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text(label,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)}}}
@Composable private fun CompactPriority(item:CompactPromotion,onClick:()->Unit) {Card(Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,jamesAmber),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){Row(Modifier.padding(14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(item.title,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,color=jamesAmber);Text(item.detail,style=MaterialTheme.typography.bodySmall)};Text(item.label,fontWeight=FontWeight.Black)}}}

@Composable private fun CompactContext(vm:JamesViewModel,summary:ContextLoadSummary) {val active=summary.active?:return;Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Column{Text("CURRENT CONTEXT",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Text(active.placeName?:active.visitType.contextLabel(),fontWeight=FontWeight.Bold)};Text("${summary.score} ${summary.label.name.replace('_',' ')}",fontWeight=FontWeight.Bold)};if(summary.difficultActive)Text("DIFFICULT ACTIVE · ${summary.difficultMinutes}m",color=jamesAmber,fontWeight=FontWeight.Bold);OutlinedButton(onClick={if(summary.difficultActive)vm.endDifficult() else vm.markDifficult()},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(summary.difficultActive)"END DIFFICULT" else "MARK DIFFICULT")}}}}
@Composable private fun CompactLifeBalance(vm:JamesViewModel,summary:LifeBalanceSummary) {val current=summary.current;Surface(Modifier.fillMaxWidth().clickable {vm.navigate("Life Balance")},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("LIFE BALANCE",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold);Text(summary.trend,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)};Text(if(current.score==null)"Not enough confirmed time ownership yet" else "Personal ${compactDuration(current.personalMinutes)} · Constrained ${compactDuration(current.constrainedMinutes)}");summary.helping.firstOrNull()?.let {Muted("Helping: $it")};summary.hurting.firstOrNull()?.let {Muted("Hurting: $it")}}}}

@Composable private fun CompactSummaryRow(title:String,lines:List<String>,onClick:()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onClick).semantics {contentDescription="$title, ${lines.joinToString()}"},shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.4f)) {
        Row(Modifier.padding(horizontal=14.dp,vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.Top) {
            Text(title,Modifier.width(84.dp),style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Black,color=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                lines.take(4).forEachIndexed {index,line->Text(line,style=if(index==0)MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,fontWeight=if(index==0)FontWeight.Bold else FontWeight.Normal,color=if(index==0)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        }
    }
}

@Composable private fun CompactQuickActions(vm:JamesViewModel,context:ContextLoadSummary) {var expanded by rememberSaveable {mutableStateOf(false)};Column{OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(expanded)"HIDE QUICK ACTIONS" else "QUICK ACTIONS")};if(expanded){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={if(context.active==null)vm.startContext("RESTING") else vm.endContext()},modifier=Modifier.weight(1f)){Text(if(context.active==null)"SET CONTEXT" else "END CONTEXT")};OutlinedButton(onClick={vm.open("TimeBlock")},modifier=Modifier.weight(1f)){Text("LOG ACTIVITY")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("AUTONOMOUS" to "MY TIME","COMMITTED" to "OBLIGATION","CONSTRAINED" to "CONSTRAINED","WORK" to "WORK").chunked(2).forEach {pair->pair.forEach {(value,label)->TextButton(onClick={vm.setCurrentOwnership(value)},modifier=Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.labelSmall)}}}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.interruptCurrentTime()},modifier=Modifier.weight(1f)){Text("INTERRUPTED")};OutlinedButton(onClick={vm.resumeCurrentTime()},modifier=Modifier.weight(1f)){Text("RESUME MY TIME")}};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={vm.open("MoodEntry")},modifier=Modifier.weight(1f)){Text("CHECK-IN")};TextButton(onClick={vm.navigate("Calibrate James OS")},modifier=Modifier.weight(1f)){Text("CALIBRATE")};TextButton(onClick={vm.open("DailyReview")},modifier=Modifier.weight(1f)){Text("JOURNAL")}}}}}

@Composable private fun CompactTimelineRow(moment:Moment) {Row(Modifier.fillMaxWidth().semantics {contentDescription="${localClock(moment.timestamp)}, ${moment.title}"},horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top){Text(localClock(moment.timestamp),Modifier.width(48.dp).padding(top=12.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Surface(Modifier.weight(1f),shape=RoundedCornerShape(15.dp),color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)){Column(Modifier.padding(horizontal=14.dp,vertical=11.dp)){Text(moment.title,fontWeight=FontWeight.Bold);if(moment.detail.isNotBlank())Text(moment.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)}}}
}
