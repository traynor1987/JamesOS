package uk.co.james.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.bodyBattery
import uk.co.james.wear.StressCheckStatus
import uk.co.james.state.MentalWellbeingSummary
import uk.co.james.state.stateSummary
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class RingReading(
    val label:String,
    val display:String,
    val progress:Float,
    val color:Color,
    val record:StoredRecord?
)

data class WhoopOverviewSnapshot(
    val battery:uk.co.james.state.BodyBattery,
    val sleep:StoredRecord?,
    val sleepQuality:StoredRecord?,
    val recovery:StoredRecord?,
    val strain:StoredRecord?,
    val hrv:StoredRecord?,
    val resting:StoredRecord?,
    val respiratory:StoredRecord?,
    val oxygen:StoredRecord?,
    val skinTemperature:StoredRecord?,
    val sleepDetail:StoredRecord?,
    val bodyUpdated:String,
    val wellbeingUpdated:String
)

private fun currentMetric(records:List<StoredRecord>, metric:String, clock:Instant, whoopOnly:Boolean=false, excludeNaps:Boolean=false):StoredRecord? =
    records.filter { record ->
        record.kind=="HealthMetric" && record.data().text("metric")==metric &&
            (!whoopOnly || record.source=="whoop") && (!excludeNaps || !record.data().flag("nap")) &&
            runCatching {
                val timestamp=Instant.parse(record.timestamp)
                timestamp<=clock && Duration.between(timestamp,clock)<=Duration.ofHours(36)
            }.getOrDefault(false)
    }.minWithOrNull(compareBy<StoredRecord> {
        SourcePolicy.rank(metric,it.source)
    // WHOOP updates the active daily cycle in place. Its cycle timestamp can stay
    // at the start of the day while the strain value rises, so freshness must use
    // the source update time before falling back to the event timestamp.
    }.thenByDescending {it.updatedAt.ifBlank {it.timestamp}}.thenByDescending {it.timestamp})

internal fun prepareWhoopOverview(records:List<StoredRecord>,clock:Instant):WhoopOverviewSnapshot {
    val battery=bodyBattery(records,clock)
    val sleep=currentMetric(records,"Sleep",clock,true,excludeNaps=true)
        ?:currentMetric(records,"Sleep",clock,excludeNaps=true)
    val sleepQuality=currentMetric(records,"Sleep quality",clock,true,excludeNaps=true)
    val recovery=currentMetric(records,"Recovery",clock,true)
    val strain=currentMetric(records,"Strain",clock,true)
    val hrv=currentMetric(records,"HRV",clock)
    val resting=currentMetric(records,"Resting heart rate",clock)
    val respiratory=currentMetric(records,"Respiratory rate",clock)
    val oxygen=currentMetric(records,"Blood oxygen",clock)
    val skinTemperature=currentMetric(records,"Skin temperature",clock,true)
    val sleepDetailId=sleep?.raw()?.obj("metadata")?.text("whoopId").orEmpty()
    val sleepDetail=records.asSequence().filter {it.kind=="SleepDetail"&&it.source=="whoop"&&!it.data().flag("nap")}
        .filter {sleepDetailId.isBlank()||it.data().text("sleepRecordId")==sleepDetailId}
        .filter {runCatching {Instant.parse(it.timestamp)<=clock}.getOrDefault(false)}
        .maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}
    val wellbeingUpdated=relativeJamesUpdate(clock,records.asSequence().filter {row->
        row.kind in setOf("HealthMetric","MoodEntry","WellbeingCheckIn") &&
            (row.data().text("metric") in setOf("James Stress","HRV","Resting heart rate","Sleep","Recovery","Heart rate") ||
             row.kind in setOf("MoodEntry","WellbeingCheckIn"))
    }.toList())
    return WhoopOverviewSnapshot(battery,sleep,sleepQuality,recovery,strain,hrv,resting,respiratory,oxygen,skinTemperature,sleepDetail,
        bodySourceFreshness(clock,recovery,sleepQuality?:sleep,battery.strainDiagnostics),wellbeingUpdated)
}

@Composable fun WhoopOverview(
    snapshot:WhoopOverviewSnapshot,
    openConnections:()->Unit,
    wellbeing:MentalWellbeingSummary?=null,
    heartRate:StoredRecord?=null,
    jamesStress:StoredRecord?=null,
    openMental:()->Unit={},
    checkStressNow:()->Unit={},
    stressCheck:StressCheckStatus=StressCheckStatus()
) {
    val battery=snapshot.battery
    val processing=battery.sleepProcessing
    var explain by rememberSaveable {mutableStateOf(false)}
    var sleepDetailOpen by rememberSaveable {mutableStateOf(false)}
    val sleep=snapshot.sleep
    val sleepQuality=snapshot.sleepQuality
    val recovery=snapshot.recovery
    val strain=snapshot.strain
    val hrv=snapshot.hrv
    val resting=snapshot.resting
    val respiratory=snapshot.respiratory
    val oxygen=snapshot.oxygen
    val skinTemperature=snapshot.skinTemperature
    val ownedStrainScore=battery.strainDiagnostics?.rawWhoopStrain
    val currentStrainPending=battery.strainDiagnostics?.currentDayPending==true
    val bodyUpdated=snapshot.bodyUpdated
    val wellbeingUpdated=snapshot.wellbeingUpdated
    val sleepMinutes=sleep?.data()?.number("value",Double.NaN)
    val sleepScore=sleepQuality?.data()?.number("value",Double.NaN)
    val recoveryScore=recovery?.data()?.number("value",Double.NaN)
    val strainScore=strain?.data()?.number("value",Double.NaN)
    val rings=listOf(
        RingReading(
            "SLEEP",
            when {
                sleepScore?.isFinite()==true -> "${sleepScore.toInt()}%"
                sleepMinutes?.isFinite()==true -> "${sleepMinutes.toInt()/60}h ${sleepMinutes.toInt()%60}m"
                else -> "—"
            },
            when {
                sleepScore?.isFinite()==true -> (sleepScore/100.0).toFloat()
                sleepMinutes?.isFinite()==true -> (sleepMinutes/480.0).coerceIn(0.0,1.0).toFloat()
                else -> 0f
            },
            jamesBlue,
            sleepQuality?:sleep
        ),
        RingReading("RECOVERY",recoveryScore?.takeIf {it.isFinite()}?.let {"${it.toInt()}%"}?:"—",(recoveryScore?:0.0).div(100.0).coerceIn(0.0,1.0).toFloat(),when {
            recoveryScore==null -> stateQuiet
            recoveryScore<34 -> jamesRed
            recoveryScore<67 -> jamesAmber
            else -> stateMint
        },recovery),
        RingReading(
            "STRAIN",
            if(currentStrainPending)"PENDING" else ownedStrainScore?.takeIf {it.isFinite()}?.let {String.format("%.1f",it)}?:"—",
            if(currentStrainPending)0f else (ownedStrainScore?:0.0).div(21.0).coerceIn(0.0,1.0).toFloat(),
            if(currentStrainPending)stateQuiet else jamesBlue,
            strain.takeIf {battery.strainDiagnostics?.accepted==true}
        )
    ).map {reading->if(processing)reading.copy(display="—",progress=0f,color=stateQuiet,record=null)else reading}
    Card(shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=stateInk,contentColor=Color.White),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal=16.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JAMES BODY BATTERY",color=stateMint,style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp)
                    Text("Your reserve",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("Updated: Now · sources: ${bodyUpdated}",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick=openConnections){Text("SOURCES  →",color=stateMint)}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(18.dp)) {
                Box(Modifier.size(112.dp),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=statePanel,trackColor=statePanel,strokeWidth=10.dp)
                    if(processing) {
                        CircularProgressIndicator(progress={.72f},modifier=Modifier.fillMaxSize(),color=stateMint,trackColor=Color.Transparent,strokeWidth=10.dp)
                        Text("⚡",color=stateMint,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                    } else {
                        CircularProgressIndicator(progress={(battery.value?:0)/100f},modifier=Modifier.fillMaxSize(),color=when {battery.value==null->stateQuiet;battery.value>=70->stateMint;battery.value>=35->jamesAmber;else->jamesRed},trackColor=Color.Transparent,strokeWidth=10.dp)
                        Text(battery.value?.let {"$it%"}?:"—",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(battery.headline,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text(battery.summary,color=stateQuiet,style=MaterialTheme.typography.bodySmall)
                    if(battery.morning!=null)Text("Morning ${battery.morning}%  ·  Spent ${battery.used}%${if(battery.recharge>0)"  ·  Nap +${battery.recharge}%"else ""}",color=stateMint,style=MaterialTheme.typography.labelMedium)
                    Text("Confidence: ${battery.confidence}",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
                }
            }
            if(processing) {
                SleepProcessingMessage()
            } else {
                LinearProgressIndicator(progress={(battery.value?:0)/100f},modifier=Modifier.fillMaxWidth().height(8.dp),color=when {battery.value==null->stateQuiet;battery.value>=70->stateMint;battery.value>=35->jamesAmber;else->jamesRed},trackColor=statePanel)
                battery.forecast?.let {range->Text("Tonight’s estimate  ${range.first}–${range.last}%",color=stateQuiet,style=MaterialTheme.typography.labelMedium)}
                TextButton(onClick={explain=true},contentPadding=PaddingValues(0.dp)){Text("Why this score  →",color=stateMint)}
            }
            wellbeing?.let {summary->
                HorizontalDivider(color=Color.White.copy(alpha=.12f))
                BodyBatteryMentalWellbeing(summary,wellbeingUpdated,heartRate,jamesStress,stressCheck,openMental,checkStressNow)
            }
            HorizontalDivider(color=Color.White.copy(alpha=.12f))
            Text("LIVE INPUTS",color=stateQuiet,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                rings.forEach {reading->RingMetric(reading,Modifier.weight(1f))}
            }
            if(processing) {
                SleepProcessingCard()
            } else {
                SleepWindowCard(sleep,snapshot.sleepDetail,onOpenDetail={sleepDetailOpen=true})
                HorizontalDivider(color=Color.White.copy(alpha=.12f))
                Text("OVERNIGHT HEALTH MONITOR",color=stateQuiet,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    CompactHealthMetric("HRV",hrv,Modifier.weight(1f))
                    CompactHealthMetric("RESTING HR",resting,Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    CompactHealthMetric("RESPIRATORY",respiratory,Modifier.weight(1f))
                    CompactHealthMetric("BLOOD OXYGEN",oxygen,Modifier.weight(1f))
                }
                CompactHealthMetric("SKIN TEMPERATURE",skinTemperature,Modifier.fillMaxWidth())
            }
            Text(
                if(processing) "WHOOP has the sleep. James OS is waiting for the final score before it starts the new day."
                else if(rings.any {it.record!=null}) "WHOOP supplies overnight baselines. Health Connect fills supported Samsung Health records, while James OS Wear provides live sensor checks. Missing sources are ignored."
                else "Connect WHOOP or Health Connect to begin. No values are invented.",
                color=stateQuiet,style=MaterialTheme.typography.bodySmall
            )
        }
    }
    if(explain&&!processing)AlertDialog(onDismissRequest={explain=false},title={Text("James Body Battery")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(battery.value?.let {"$it% · ${battery.headline}"}?:battery.headline,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
        Text(battery.summary,style=MaterialTheme.typography.bodyLarge)
        HorizontalDivider()
        Text("WHY JAMES THINKS THIS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,letterSpacing=1.2.sp)
        battery.reasons.forEach {Text("•  $it",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        battery.trace?.let {trace->
            HorizontalDivider()
            Text("CALCULATION DIAGNOSTICS",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary,letterSpacing=1.2.sp)
            Text("Previous ${trace.previousReserve?:"—"} → Current ${trace.currentReserve?:"—"} · ${trace.trigger}",style=MaterialTheme.typography.bodySmall)
            Text("Awake ${trace.awakeMinutes/60}h ${trace.awakeMinutes%60}m · total cost -${String.format(java.util.Locale.UK,"%.1f",trace.awakeTimeCost)}",style=MaterialTheme.typography.bodySmall)
            Text("WHOOP Strain total -${String.format(java.util.Locale.UK,"%.1f",trace.strainCost)} · context -${String.format(java.util.Locale.UK,"%.1f",trace.activityCost+trace.workloadCost)} · restorative +${trace.restorativeCredit}",style=MaterialTheme.typography.bodySmall)
            val context=trace.contextTimeSeconds.filterValues {it>0L}.entries.joinToString(" · ") {(name,seconds)->"$name ${seconds/60}m"}
            if(context.isNotBlank())Text("Context time (${trace.contextTimeUnit}): $context",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
            Text("Previous persistence: ${trace.previousPersistenceStatus}${trace.previousPersistenceReason.takeIf {it.isNotBlank()}?.let {" · $it"}?:""}",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
            battery.strainDiagnostics?.let {strain->
                val current=strain.rawWhoopStrain?.let {String.format(java.util.Locale.UK,"%.1f",it)}?:"PENDING"
                Text("CURRENT JAMES DAY  ${strain.jamesDayId}",style=MaterialTheme.typography.labelSmall,color=stateMint)
                Text("Sleep boundary: ${strain.sleepBoundary?:"—"}",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
                Text("CURRENT WHOOP CYCLE  ${strain.whoopCycleId?:"awaiting"}",style=MaterialTheme.typography.labelSmall,color=stateMint)
                Text("Current Strain: $current · Accepted: ${if(strain.accepted)"YES" else "NO"}",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
                Text("highestRawStrain: ${strain.highestRawStrain?.let {String.format(java.util.Locale.UK,"%.1f",it)}?:"—"} · scoped to this day/cycle",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
                strain.ignoredPreviousDayStrain?.let {previous->
                    Text("Previous Strain: ${String.format(java.util.Locale.UK,"%.1f",previous)} · cycle ${strain.previousWhoopCycleId?:"previous/unowned"} · IGNORED for current day",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
                }
                strain.invalidatedPersistedStrain?.let {stale->
                    Text("Migrated stale persisted Strain ${String.format(java.util.Locale.UK,"%.1f",stale)} · current-day contribution IGNORED",style=MaterialTheme.typography.bodySmall,color=jamesAmber)
                    Text("Reason: ${strain.invalidationReason}",style=MaterialTheme.typography.bodySmall,color=stateQuiet)
                }
            }
            trace.increaseReason?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            Text("Calculated ${trace.calculatedAt}",style=MaterialTheme.typography.labelSmall,color=stateQuiet)
        }
    }},confirmButton={TextButton(onClick={explain=false}){Text("Close")}})
    if(sleepDetailOpen&&snapshot.sleepDetail!=null)SleepDetailDialog(snapshot.sleepDetail,onDismiss={sleepDetailOpen=false})
}

@Composable private fun BodyBatteryMentalWellbeing(
    summary:MentalWellbeingSummary,
    updated:String,
    heartRate:StoredRecord?,
    jamesStress:StoredRecord?,
    stressCheck:StressCheckStatus,
    onOpen:()->Unit,
    onCheckStress:()->Unit
) {
    val reserveColour=when(summary.reserve.score){in 0..19->jamesRed;in 20..39->jamesAmber;in 40..59->jamesBlue;else->stateMint}
    val anxietyColour=when(summary.anxiety.score){in 0..19->jamesBlue;in 20..39->stateMint;in 40..59->jamesAmber;else->jamesRed}
    val moodColour=when(summary.lowMood.score){in 0..19->jamesBlue;in 20..39->stateMint;in 40..59->jamesAmber;else->jamesRed}
    val liveUpdating=stressCheck.active
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JAMES MENTAL WELLBEING",color=stateMint,style=MaterialTheme.typography.labelSmall,letterSpacing=1.2.sp)
                Text("Your mental reserve",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text("Last updated: ${updated}",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
            }
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(
                    onClick=onCheckStress,
                    enabled=!liveUpdating,
                    modifier=Modifier.semantics {contentDescription="Refresh live wellbeing readings"}
                ) {
                    if(liveUpdating) CircularProgressIndicator(modifier=Modifier.size(21.dp),color=stateMint,strokeWidth=2.dp)
                    else Text("↻",color=stateMint,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                }
                TextButton(onClick=onOpen){Text("DETAILS  →",color=stateMint)}
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(82.dp),contentAlignment=Alignment.Center) {
                CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=statePanel,trackColor=statePanel,strokeWidth=8.dp)
                CircularProgressIndicator(progress={summary.reserve.score/100f},modifier=Modifier.fillMaxSize(),color=reserveColour,trackColor=Color.Transparent,strokeWidth=8.dp)
                Column(horizontalAlignment=Alignment.CenterHorizontally) {
                    Text("${summary.reserve.score}",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("RESERVE",style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(summary.reserve.label,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=reserveColour)
                Text("Higher is better · 0 depleted → 100 strong",color=stateQuiet,style=MaterialTheme.typography.bodySmall)
                Text(summary.reserve.trend,color=stateQuiet,style=MaterialTheme.typography.labelMedium)
            }
        }
        val stressValue=jamesStress?.data()?.number("value")?.takeIf {it.isFinite()}
        val heartValue=heartRate?.data()?.number("value")?.takeIf {it.isFinite()}
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WellbeingMetricCard(
                label="ANXIETY",value=summary.anxiety.score,status=summary.anxiety.label,
                detail="Lower is better · 0 calm → 100 high",colour=anxietyColour,
                freshness=anxietyFreshness(summary.anxietyDiagnostics),modifier=Modifier.weight(1f),
                updating=liveUpdating,updatingLabel=anxietyUpdateLabel(stressCheck.stage)
            )
            WellbeingMetricCard(
                label="JAMES STRESS",value=stressValue?.toInt(),status=stressLabel(stressValue),
                detail=stressValue?.let {"${String.format(java.util.Locale.UK,"%.0f",it)} /100"}?:"Awaiting watch",
                colour=stressColour(stressValue),freshness=readingFreshness(jamesStress),modifier=Modifier.weight(1f),
                updating=liveUpdating,updatingLabel=stressUpdateLabel(stressCheck.stage)
            )
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            WellbeingMetricCard(
                label="LOW-MOOD",value=summary.lowMood.score,status=summary.lowMood.label,
                detail="Lower is better · 0 few patterns → 100 elevated",colour=moodColour,
                freshness="Longitudinal estimate",modifier=Modifier.weight(1f)
            )
            WellbeingMetricCard(
                label="HEART RATE",value=heartValue?.toInt(),status=heartValue?.let {"${it.toInt()} bpm"}?:"AWAITING",
                detail=if(heartValue==null)"Awaiting watch" else "Wear live reading",colour=jamesRed,
                freshness=readingFreshness(heartRate),modifier=Modifier.weight(1f)
            )
        }
        Text("Personal experimental estimate · not a diagnosis",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun WellbeingMetricCard(
    label:String,
    value:Int?,
    status:String,
    detail:String,
    colour:Color,
    freshness:String,
    modifier:Modifier=Modifier,
    updating:Boolean=false,
    updatingLabel:String=""
) {
    Surface(modifier.height(142.dp),shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxSize().padding(11.dp).alpha(if(updating).35f else 1f),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                    CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=stateInk,trackColor=stateInk,strokeWidth=5.dp)
                    CircularProgressIndicator(progress={(value?:0).coerceIn(0,100)/100f},modifier=Modifier.fillMaxSize(),color=colour,trackColor=Color.Transparent,strokeWidth=5.dp)
                    Text(value?.toString()?:"—",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(label,color=colour,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,maxLines=1)
                    Text(status,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodySmall,maxLines=1)
                    Text(detail,color=stateQuiet,style=MaterialTheme.typography.labelSmall,maxLines=2)
                    Text(freshness,color=stateQuiet,style=MaterialTheme.typography.labelSmall,maxLines=2)
                }
            }
            if(updating) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment=Alignment.CenterHorizontally,
                    verticalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    CircularProgressIndicator(modifier=Modifier.size(28.dp),color=stateMint,strokeWidth=3.dp)
                    Text(updatingLabel,color=stateMint,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                }
            }
        }
    }
}

private fun stressColour(stress:Double?):Color=when(stress?.toInt()?:-1) {in 0..19->jamesBlue;in 20..39->stateMint;in 40..59->jamesAmber;else->jamesRed}
private fun stressLabel(stress:Double?):String=when(stress?.toInt()?:-1) {in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";in 80..100->"VERY HIGH";else->"AWAITING"}
private fun stressUpdateLabel(stage:String):String=when(stage) {
    "REQUESTED","REQUEST_RECEIVED"->"REQUESTING WATCH"
    "INITIALIZING"->"INITIALIZING"
    "MEASURING"->"MEASURING…"
    "PROCESSING"->"PROCESSING"
    "SENSOR_DATA_SENT"->"SYNCING"
    "RECALCULATING"->"RECALCULATING"
    else->"UPDATING"
}
private fun anxietyUpdateLabel(stage:String):String=when(stage) {
    "REQUESTED","REQUEST_RECEIVED","INITIALIZING","MEASURING"->"WAITING FOR SENSORS"
    "PROCESSING","SENSOR_DATA_SENT","RECALCULATING"->"RECALCULATING…"
    else->"UPDATING"
}
private fun readingFreshness(record:StoredRecord?):String {
    val timestamp=record?.updatedAt?.ifBlank {record.timestamp}.orEmpty()
    if(timestamp.isBlank())return "Awaiting watch"
    val seconds=runCatching {Duration.between(Instant.parse(timestamp),Instant.now()).seconds.coerceAtLeast(0)}.getOrDefault(0)
    return when {seconds<60->"Updated now";seconds<3600->"Updated ${seconds/60}m ago";seconds<86400->"Updated ${seconds/3600}h ago";else->"Updated ${seconds/86400}d ago"}
}

private fun anxietyFreshness(diagnostic:uk.co.james.state.AnxietyCalculationDiagnostics?):String {
    diagnostic?:return "Awaiting first calculation"
    val seconds=runCatching {Duration.between(Instant.parse(diagnostic.calculatedAt),Instant.now()).seconds.coerceAtLeast(0)}.getOrDefault(0)
    val age=when {seconds<60->"Now";seconds<3600->"${seconds/60}m ago";seconds<86400->"${seconds/3600}h ago";else->"${seconds/86400}d ago"}
    return when {seconds>45*60->"STALE · recalculated ${age}";diagnostic.unchanged->"Updated ${age} · unchanged";else->"Updated ${age}"}
}

@Composable private fun JamesStressBatteryTile(stress:Double?,modifier:Modifier=Modifier,onCheckNow:()->Unit) {
    val colour=when(stress?.toInt()?:-1) {in 0..19->jamesBlue;in 20..39->stateMint;in 40..59->jamesAmber;else->jamesRed}
    val label=when(stress?.toInt()?:-1) {in 0..19->"VERY LOW";in 20..39->"LOW";in 40..59->"MODERATE";in 60..79->"HIGH";in 80..100->"VERY HIGH";else->"AWAITING"}
    Surface(modifier,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=stateInk,trackColor=stateInk,strokeWidth=5.dp)
                CircularProgressIndicator(progress={(stress?:0.0).coerceIn(0.0,100.0).toFloat()/100f},modifier=Modifier.fillMaxSize(),color=colour,trackColor=Color.Transparent,strokeWidth=5.dp)
                Text(stress?.let {String.format(java.util.Locale.UK,"%.0f",it)}?:"—",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("JAMES STRESS",color=colour,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text(label,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodySmall)
                Text(stress?.let {"${String.format(java.util.Locale.UK,"%.0f",it)} /100"}?:"Awaiting watch",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
                TextButton(onClick=onCheckNow,contentPadding=PaddingValues(0.dp)){Text("CHECK NOW",color=stateMint,style=MaterialTheme.typography.labelSmall)}
            }
        }
    }
}
@Composable private fun HeartRateBatteryTile(heart:Double?,modifier:Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(48.dp),contentAlignment=Alignment.Center) {
                CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=stateInk,trackColor=stateInk,strokeWidth=5.dp)
                CircularProgressIndicator(progress={(heart?:0.0).coerceIn(0.0,160.0).toFloat()/160f},modifier=Modifier.fillMaxSize(),color=jamesRed,trackColor=Color.Transparent,strokeWidth=5.dp)
                Text(heart?.toInt()?.toString()?:"—",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("HEART RATE",color=jamesRed,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold)
                Text(heart?.let {"${it.toInt()} bpm"}?:"AWAITING",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.bodySmall)
                Text(if(heart==null)"Awaiting watch" else "Wear live reading",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun relativeJamesUpdate(clock:Instant,rows:List<StoredRecord>):String {
    val latest=rows.mapNotNull {row->runCatching {Instant.parse(row.updatedAt.ifBlank {row.timestamp})}.getOrNull()}.maxOrNull()?:return "Awaiting data"
    val seconds=Duration.between(latest,clock).seconds.coerceAtLeast(0)
    return when {
        seconds<60 -> "Now"
        seconds<60*60 -> "${seconds/60}m ago"
        seconds<24*60*60 -> "${seconds/(60*60)}h ago"
        else -> "${seconds/(24*60*60)}d ago"
    }
}

@Composable private fun SleepProcessingMessage() {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("⚡",color=stateMint,style=MaterialTheme.typography.titleLarge)
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text("SLEEP DETECTED",color=stateMint,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                Text("Charging your new day",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Muted("WHOOP is processing the final overnight scores.")
            }
        }
    }
}

@Composable private fun SleepProcessingCard() {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Row(Modifier.padding(horizontal=15.dp,vertical=13.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("☾",color=jamesBlue,style=MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("SLEEP PROCESSING",color=jamesBlue,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp)
                Text("WHOOP is finishing your sleep",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
            }
            Text("—",color=stateQuiet,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
        }
    }
}

private fun sleepDuration(millis:Double?):String?=millis?.takeIf {it.isFinite()&&it>=0.0}?.let {value->
    val minutes=(value/60000.0).toInt();"${minutes/60}h ${minutes%60}m"
}

@Composable private fun SleepWindowCard(sleep:StoredRecord?,detail:StoredRecord?,onOpenDetail:()->Unit) {
    val minutes=sleep?.data()?.number("value")?.toInt()
    val start=sleep?.data()?.text("start").orEmpty();val end=sleep?.data()?.text("end").orEmpty()
    val detailData=detail?.data();val need=sleepDuration(detailData?.number("totalSleepNeedMilli",Double.NaN)?.takeIf(Double::isFinite))
    val shortfall=sleepDuration(detailData?.number("shortfallMilli",Double.NaN)?.takeIf {it.isFinite()&&it>0.0})
    val surplus=sleepDuration(detailData?.number("surplusMilli",Double.NaN)?.takeIf {it.isFinite()&&it>0.0})
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=statePanel,contentColor=Color.White) {
        Column(Modifier.padding(horizontal=15.dp,vertical=13.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text("LAST SLEEP",color=jamesBlue,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp);Text(minutes?.let {"${it/60}h ${it%60}m"}?:"Awaiting sleep",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
                Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(if(start.isNotBlank()&&end.isNotBlank())"${sleepClock(start)} – ${sleepClock(end)}" else "Overnight sleep",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);Text("Recorded sleep",color=stateQuiet,style=MaterialTheme.typography.labelSmall)}
            }
            if(need!=null)Text(listOfNotNull("Need $need",shortfall?.let {"Shortfall $it"},surplus?.let {"Surplus $it"},if(detailData?.text("needStatus")=="MET")"Need met" else null).joinToString(" · "),color=stateMint,style=MaterialTheme.typography.labelMedium)
            if(detail!=null)TextButton(onClick=onOpenDetail,contentPadding=PaddingValues(0.dp)){Text("SLEEP DETAILS  →",color=stateMint,style=MaterialTheme.typography.labelSmall)}
        }
    }
}

@Composable private fun SleepDetailDialog(detail:StoredRecord,onDismiss:()->Unit) {
    val d=detail.data();fun duration(key:String)=sleepDuration(d.number(key,Double.NaN).takeIf(Double::isFinite))
    @Composable fun line(label:String,value:String?){if(value!=null)Text("$label  $value",style=MaterialTheme.typography.bodyMedium)}
    AlertDialog(onDismissRequest=onDismiss,title={Text("Sleep detail")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("SLEEP NEED",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)
        duration("totalSleepNeedMilli")?.let {Text(it,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)}?:Text("Unavailable",color=stateQuiet)
        line("Baseline",duration("baselineSleepNeedMilli"));line("Sleep debt",duration("sleepDebtContributionMilli")?.let {"+$it"});line("Recent strain",duration("recentStrainContributionMilli")?.let {"+$it"})
        d.number("napAdjustmentMilli",Double.NaN).takeIf(Double::isFinite)?.let {line("Nap adjustment",(if(it<0)"−" else "+")+sleepDuration(kotlin.math.abs(it)))}
        when(d.text("needStatus")){"SHORTFALL"->line("Shortfall",duration("shortfallMilli"));"SURPLUS"->line("Surplus",duration("surplusMilli"));"MET"->Text("Sleep met the estimated need.",color=stateMint)}
        HorizontalDivider();Text("SLEEP QUALITY",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall)
        line("Efficiency",d.number("efficiencyPercentage",Double.NaN).takeIf(Double::isFinite)?.let {"${it.toInt()}%"});line("Consistency",d.number("consistencyPercentage",Double.NaN).takeIf(Double::isFinite)?.let {"${it.toInt()}%"});line("Awake",duration("awakeDurationMilli"));line("Disturbances",d.number("disturbanceCount",Double.NaN).takeIf(Double::isFinite)?.toInt()?.toString())
        val stages=d.obj("stages");val stageLines=listOfNotNull(durationFrom(stages,"slowWaveMilli")?.let {"Deep $it"},durationFrom(stages,"remMilli")?.let {"REM $it"},durationFrom(stages,"lightMilli")?.let {"Light $it"});if(stageLines.isNotEmpty()){HorizontalDivider();Text("SLEEP STAGES",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall);Text(stageLines.joinToString(" · "),style=MaterialTheme.typography.bodySmall)}
        HorizontalDivider();Text("SOURCE",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelSmall);Text("WHOOP Sleep",style=MaterialTheme.typography.bodyMedium);Text("Recorded: ${d.text("start")} – ${d.text("end")}",color=stateQuiet,style=MaterialTheme.typography.bodySmall);Text("Last updated: ${detail.updatedAt.ifBlank {detail.timestamp}} · James OS mapping v${d.text("mappingVersion")}",color=stateQuiet,style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
private fun durationFrom(data:kotlinx.serialization.json.JsonObject,key:String)=sleepDuration(data.number(key,Double.NaN).takeIf(Double::isFinite))
private fun sleepClock(value:String)=runCatching {Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault("—")

@Composable private fun RingMetric(reading:RingReading, modifier:Modifier=Modifier) {
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(78.dp),contentAlignment=Alignment.Center) {
            CircularProgressIndicator(progress={1f},modifier=Modifier.fillMaxSize(),color=statePanel,trackColor=statePanel,strokeWidth=7.dp)
            CircularProgressIndicator(progress={reading.progress},modifier=Modifier.fillMaxSize(),color=reading.color,trackColor=Color.Transparent,strokeWidth=7.dp)
            Text(reading.display,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,maxLines=1)
        }
        Text(reading.label,style=MaterialTheme.typography.labelSmall,fontWeight=FontWeight.Bold,letterSpacing=.8.sp)
        Text(if(reading.record!=null)"Recorded" else "Waiting",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
    }
}

@Composable private fun CompactHealthMetric(label:String,record:StoredRecord?,modifier:Modifier=Modifier) {
    Surface(modifier,shape=RoundedCornerShape(16.dp),color=statePanel,contentColor=Color.White) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(label,color=stateQuiet,style=MaterialTheme.typography.labelSmall,letterSpacing=1.sp)
            Text(record?.let(::healthValue)?:"—",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
            Text(record?.let {providerLabel(it)}?:"Awaiting data",color=stateQuiet,style=MaterialTheme.typography.labelSmall)
        }
    }
}

private fun bodySourceFreshness(clock:Instant,recovery:StoredRecord?,sleep:StoredRecord?,strain:uk.co.james.state.StrainDiagnostics?):String {
    fun age(record:StoredRecord?)=record?.let {relativeJamesUpdate(clock,listOf(it))}?:"awaiting"
    return "Recovery: ${age(recovery)} · Sleep: ${age(sleep)} · Strain: ${if(strain?.currentDayPending==true)"pending" else strain?.whoopTimestamp?.let {stamp->runCatching {val minutes=Duration.between(Instant.parse(stamp),clock).toMinutes().coerceAtLeast(0);if(minutes<1)"now"else if(minutes<60)"${minutes}m" else "${minutes/60}h"}.getOrDefault("updated")} ?: "awaiting"}"
}
