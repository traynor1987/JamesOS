package uk.co.james.ui

import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.BuildConfig
import uk.co.james.state.*
import uk.co.james.time.jamesDayWindow
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Connection and freshness are deliberately separate: an authorised provider
 * with an old successful sync must not look like current evidence. */
internal fun providerSyncStatus(stamp:String,now:Instant=Instant.now()):String {
    val at=stamp.takeIf(::validTime)?.let {runCatching {Instant.parse(it)}.getOrNull()}?:return "NOT SYNCED"
    val age=java.time.Duration.between(at,now)
    return when {age.isNegative->"CHECKING TIME";age.toMinutes()<=30->"FRESH";age.toHours()<=24->"AGING";else->"STALE"}
}

@Composable fun SettingsScreen(vm:JamesViewModel,export:(String?)->Unit,install:()->Unit) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val compactToday by vm.compactToday.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val whoopConfigured by vm.whoopConfigured.collectAsStateWithLifecycle()
    val wear by vm.wearStatus.collectAsStateWithLifecycle()
    AdaptiveCards(listOf(
        {PageTitle("Settings","EVERYTHING HAS A HOME")},
        {SettingsDestinationCard("Display & Today",if(compactToday)"Compact Today" else "Classic Today","Layout, theme and presentation preferences") {vm.navigate("Settings:Display")}},
        {SettingsDestinationCard("Connections & Data",listOf(if(health.granted.isEmpty())"Health Connect not connected" else "Health Connect connected",if(whoopConfigured)"WHOOP connected" else "WHOOP not connected").joinToString(" · "),"Providers, permissions, nutrition and Shift Tracker status") {vm.navigate("Connections")}},
        {SettingsDestinationCard("Watch & Sensors",if(wear.connected)"${wear.device.ifBlank {"Wear OS"}} · connected" else "Watch not connected","Wear companion, sensor choices, sync and watch updates") {vm.navigate("Settings:Watch")}},
        {SettingsDestinationCard("Places & Context","Location, places and movement","Location permissions, known places and context detection") {vm.navigate("Location")}},
        {SettingsDestinationCard("James OS & Calibration","Algorithms and personal tuning","Energy, wellbeing and calibration controls") {vm.navigate("Settings:JamesOS")}},
        {SettingsDestinationCard("Data, Backup & Import","Import Centre","Export, validated restore and backup maintenance") {vm.navigate("Import Centre")}},
        {SettingsDestinationCard("App & Updates","James OS ${BuildConfig.VERSION_NAME}","Signed app updates, release source and version information") {vm.navigate("Settings:App")}},
        {SettingsDestinationCard("Advanced & Diagnostics","Technical status","Data-source and maintenance information") {vm.navigate("Settings:Diagnostics")}}
    ),keys=listOf("settings-title","display","connections","watch","places","james-os","backup","updates","diagnostics"))
}

@Composable private fun SettingsDestinationCard(title:String,summary:String,description:String,onClick:()->Unit) {
    JamesCard(title,summary,onClick=onClick) {
        Muted(description)
        Text("Open",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
    }
}

@Composable fun DisplaySettingsScreen(vm:JamesViewModel) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val compactToday by vm.compactToday.collectAsStateWithLifecycle()
    AdaptiveCards(listOf(
        {PageTitle("Display & Today","PRESENTATION PREFERENCES")},
        {JamesCard("Appearance","TODAY LAYOUT") {
            Choice("Theme",theme,listOf("system","light","dark"),vm::theme)
            SensorSwitch("Compact Today",compactToday,vm::compactToday)
            Muted(if(compactToday)"Prioritised live state and compact daily summaries. Compact Today is active." else "Classic Today is active. It shows the original full dashboard layout.")
        }}
    ),keys=listOf("display-title","appearance"))
}

@Composable fun WatchSettingsScreen(vm:JamesViewModel) = AdaptiveCards(listOf(
    {PageTitle("Watch & Sensors","COMPANION AND SENSOR PREFERENCES")},
    {WearCompanionCard(vm)},
    {WearSensorSettingsCard(vm)}
),keys=listOf("watch-title","watch-companion","watch-sensors"))

@Composable fun JamesOsSettingsScreen(vm:JamesViewModel) = AdaptiveCards(listOf(
    {PageTitle("James OS & Calibration","PERSONAL, VERSIONED CONTROLS")},
    {AlgorithmsSettingsCard(vm)},
    {EnergyTimeSettingsCard(vm)},
    {MentalWellbeingSettingsCard(vm)}
),keys=listOf("james-title","algorithms","energy-time","wellbeing"))

@Composable fun AppSettingsScreen(vm:JamesViewModel,install:()->Unit) = AdaptiveCards(listOf(
    {PageTitle("App & Updates","SIGNED JAMES OS RELEASES")},
    {UpdateSettingsCard(vm,install)}
),keys=listOf("app-title","updates"))

@Composable fun DiagnosticsSettingsScreen(vm:JamesViewModel) {
    val crash by vm.localCrashReport.collectAsStateWithLifecycle()
    AdaptiveCards(listOfNotNull(
        {PageTitle("Advanced & Diagnostics","TECHNICAL STATUS")},
        {DataStatusCard(vm)},
        crash?.let { report -> { JamesCard("Last app crash","LOCAL BETA DIAGNOSTIC") {
            Text("${report.exceptionType.substringAfterLast('.')} · ${report.occurredAt}")
            Text("Stage: ${report.stage.ifBlank {"unknown"}}")
            Text("Route: ${report.previousRoute.ifBlank {"unknown"}} → ${report.route.ifBlank {"unknown"}}")
            Muted("Phase: ${report.uiPhase.ifBlank {"unknown"}} · app ${report.appVersion} · database ${report.databaseSchemaVersion}")
            if(report.message.isNotBlank())Muted(report.message)
            if(report.jamesFrames.isNotEmpty()) { Text("James OS stack",style=MaterialTheme.typography.labelLarge);report.jamesFrames.forEach {Muted(it)} }
            TextButton(onClick=vm::clearLocalCrashReport){Text("CLEAR LOCAL REPORT")}
            Muted("Contains no health values, locations, notes, credentials or provider payloads. It stays on this phone unless you choose to share it.")
        } } }
    ),keys=buildList {add("diagnostics-title");add("data-status");if(crash!=null)add("last-crash")})
}
@Composable private fun EnergyTimeSettingsCard(vm:JamesViewModel) {
    val current by vm.energyTimeSettings.collectAsStateWithLifecycle()
    fun save(change:(uk.co.james.settings.EnergyTimeSettings)->uk.co.james.settings.EnergyTimeSettings){vm.energyTimeSettings(change(current))}
    JamesCard("Energy & Time","Separate personal context estimates") {
        SensorSwitch("Live Energy",current.liveEnergy){v->save {it.copy(liveEnergy=v)}}
        SensorSwitch("Energy Sustainability",current.sustainability){v->save {it.copy(sustainability=v)}}
        SensorSwitch("Crash Risk",current.crashRisk){v->save {it.copy(crashRisk=v)}}
        SensorSwitch("Time Pressure",current.timePressure){v->save {it.copy(timePressure=v)}}
        SensorSwitch("Energy check-ins",current.energyCheckIns){v->save {it.copy(energyCheckIns=v)}}
        SensorSwitch("Time Pressure check-ins",current.timePressureCheckIns){v->save {it.copy(timePressureCheckIns=v)}}
        SensorSwitch("Use meal context",current.useMealContext){v->save {it.copy(useMealContext=v)}}
        SensorSwitch("Use caffeine context",current.useCaffeineContext){v->save {it.copy(useCaffeineContext=v)}}
        SensorSwitch("Use Life Balance context",current.usePersonalTime){v->save {it.copy(usePersonalTime=v)}}
        SensorSwitch("Use physiological context",current.usePhysiology){v->save {it.copy(usePhysiology=v)}}
        SensorSwitch("Health Connect nutrition",current.nutritionEnabled){v->save {it.copy(nutritionEnabled=v)}}
        Muted("Enable nutrition, then choose its Health Connect permission under Connections. James OS uses logged meals as context—it does not infer glucose, metabolism or fasting.")
    }
}
@Composable private fun MentalWellbeingSettingsCard(vm:JamesViewModel) {
    val current by vm.wellbeingSettings.collectAsStateWithLifecycle()
    var confirm by rememberSaveable {mutableStateOf(false)}
    fun save(change:(uk.co.james.settings.WellbeingSettings)->uk.co.james.settings.WellbeingSettings){vm.wellbeingSettings(change(current))}
    JamesCard("Mental wellbeing","Private experimental insights · not a diagnosis") {
        SensorSwitch("Mental wellbeing insights",current.enabled){value->save {settings->settings.copy(enabled=value)}}
        SensorSwitch("Mood check-ins",current.checkIns){value->save {settings->settings.copy(checkIns=value)}}
        SensorSwitch("Anxiety Load",current.anxiety){value->save {settings->settings.copy(anxiety=value)}}
        SensorSwitch("Low-Mood Trend",current.lowMood){value->save {settings->settings.copy(lowMood=value)}}
        SensorSwitch("Mental Reserve",current.reserve){value->save {settings->settings.copy(reserve=value)}}
        SensorSwitch("Use Life Balance context",current.rut){value->save {settings->settings.copy(rut=value)}}
        SensorSwitch("Use exercise",current.exercise){value->save {settings->settings.copy(exercise=value)}}
        SensorSwitch("Use location/activity diversity",current.diversity){value->save {settings->settings.copy(diversity=value)}}
        SensorSwitch("Use personal time",current.personalTime){value->save {settings->settings.copy(personalTime=value)}}
        SensorSwitch("Use physiological signals",current.physiology){value->save {settings->settings.copy(physiology=value)}}
        SensorSwitch("Use WHOOP",current.whoop){value->save {settings->settings.copy(whoop=value)}}
        SensorSwitch("Use Health Connect",current.healthConnect){value->save {settings->settings.copy(healthConnect=value)}}
        SensorSwitch("Use Wear sensors",current.wear){value->save {settings->settings.copy(wear=value)}}
        SensorSwitch("Use Samsung sensor readings",current.samsung){value->save {settings->settings.copy(samsung=value)}}
        TextButton(onClick={confirm=true}){Text("Reset personal baseline")}
        Muted("Resetting removes only saved wellbeing summaries. It does not delete health, WHOOP, location, timeline, Legacy RUT or check-in history.")
    }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Reset personal baseline?")},text={Text("James will learn again from your existing private history. No unrelated data is deleted.")},confirmButton={TextButton(onClick={vm.resetWellbeingBaseline();confirm=false}){Text("Reset")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
}
@Composable private fun DataStatusCard(vm:JamesViewModel) {
    val records by vm.records.collectAsStateWithLifecycle()
    val wear by vm.wearStatus.collectAsStateWithLifecycle()
    val health=records.firstOrNull {it.recordId=="source:health_connect"}?.data()?.text("lastSync").orEmpty()
    val whoop=records.firstOrNull {it.recordId=="source:whoop"}?.data()?.text("lastSync").orEmpty()
    val latest=records.filter {it.kind=="HealthMetric"}.groupBy {it.data().text("metric")}.mapValues {(_,items)->items.maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}}.toList().sortedBy {it.first}.take(8)
    JamesCard("Data status","What James has, and where it came from") {
        Text("Health Connect: ${providerSyncStatus(health)} · ${health.ifBlank {"Not synced yet"}}")
        Text("WHOOP: ${providerSyncStatus(whoop)} · ${whoop.ifBlank {"Not synced yet"}}")
        Text("Watch: ${if(wear.connected)"${wear.device.ifBlank {"connected"}} · ${uk.co.james.wear.WearCompanion.age(wear.lastSeen)}" else "not connected"}")
        if(latest.isEmpty()) Muted("No health readings saved yet.") else {
            Text("Latest reading sources",style=MaterialTheme.typography.labelLarge)
            latest.forEach {(metric,row)->Text("$metric · ${row?.source?.replace('_',' ') ?: "Unknown"}")}
        }
        Muted("If sources overlap, James keeps them separately and shows the source above rather than silently combining them.")
    }
}
@Composable private fun WearSensorSettingsCard(vm:JamesViewModel) {val sensors by vm.wearSensors.collectAsStateWithLifecycle();fun update(change:(uk.co.james.settings.WearSensorSettings)->uk.co.james.settings.WearSensorSettings){vm.wearSensors(change(sensors))};JamesCard("Watch readings & battery","Switch off anything you do not want") {Muted("Low-power sensors run only when enabled. Automatic stress uses the readings already supplied by Wear OS; it does not start a detailed sensor session.");SensorSwitch("Passive heart rate · low battery",sensors.passiveHeart){value->update {current->current.copy(passiveHeart=value)}};SensorSwitch("Steps · low battery",sensors.steps){value->update {current->current.copy(steps=value)}};SensorSwitch("Calories · low battery",sensors.calories){value->update {current->current.copy(calories=value)}};SensorSwitch("Distance · low battery",sensors.distance){value->update {current->current.copy(distance=value)}};SensorSwitch("Automatic James Stress · low battery",sensors.automaticStress){value->update {current->current.copy(automaticStress=value)}};Muted("These only run during an explicit 45-second Check now on the watch.");SensorSwitch("Check heart rate",sensors.detailHeart){value->update {current->current.copy(detailHeart=value)}};SensorSwitch("HRV",sensors.hrv){value->update {current->current.copy(hrv=value)}};SensorSwitch("EDA",sensors.eda){value->update {current->current.copy(eda=value)}};SensorSwitch("Skin temperature",sensors.skinTemperature){value->update {current->current.copy(skinTemperature=value)}};Muted("ECG and raw PPG are not collected.")}}
@Composable private fun SensorSwitch(label:String,checked:Boolean,onChange:(Boolean)->Unit) {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f));Switch(checked,onCheckedChange=onChange)}}
@Composable private fun WearCompanionCard(vm:JamesViewModel) {
    val status by vm.wearStatus.collectAsStateWithLifecycle();val transfer by vm.wearTransfer.collectAsStateWithLifecycle()
    JamesCard("Wear OS companion",if(status.connected)"Connected" else "Not connected") {
        if(status.device.isNotBlank())Text("Connected watch: ${status.device}") else Muted("Install James OS Wear on a paired Wear OS watch, then open it once.")
        Text("Watch version: ${status.watchVersion.ifBlank {"Unknown"}}")
        Text("Connection: ${if(status.connected)"Connected" else "Disconnected"}")
        Text("Last sync: ${if(status.lastSeen==0L)"Never" else uk.co.james.wear.WearCompanion.age(status.lastSeen)}")
        Text("Sensors: ${if(status.passiveRegistered)"Active" else "Permission required"}")
        if(transfer.isNotBlank())Text(transfer,color=if(transfer.contains("failed",true)||transfer.contains("checksum",true))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Button(enabled=status.connected,onClick={vm.wearOpenSettings()}){Text("Open watch settings")}
        TextButton(enabled=status.connected,onClick={vm.wearSync()}){Text("Sync now")}
        TextButton(onClick={vm.wearCheckUpdate()}){Text("Check watch update")}
        if(transfer.contains("available",true)||transfer.startsWith("Downloading")||transfer.startsWith("Verified"))Button(enabled=status.connected,onClick={vm.wearDownloadAndSend()}){Text("Download & send to watch")}
        if(status.updateStage in setOf("READY","AWAITING_CONFIRMATION"))Button(enabled=status.connected,onClick={vm.wearOpenReadyUpdate()}){Text("Install saved update ${status.updateVersion.ifBlank {"on watch"}}")}
        Muted("The phone remains the database and update controller. Secrets, private notes and raw location history are never sent to the watch.")
    }
}
@Composable fun ConnectionsScreen(vm:JamesViewModel,requestHealth:(Set<String>)->Unit,requestCalendar:()->Unit) {
    val health by vm.health.collectAsStateWithLifecycle();val records by vm.records.collectAsStateWithLifecycle();var selected by rememberSaveable {mutableStateOf(listOf("Steps"))}
    val cards=mutableListOf<@Composable ()->Unit>()
    cards.add {PageTitle("Connections","YOU CHOOSE WHAT JAMES CAN READ")}
    val energySettings by vm.energyTimeSettings.collectAsStateWithLifecycle()
    cards.add {JamesCard("Calendar",if(vm.app.calendar.permitted())"Read-only access allowed" else "Not connected") { Muted("James OS reads only selected device calendars for upcoming planned commitments. It never creates, edits or deletes Calendar events."); if(!vm.app.calendar.permitted())Button(onClick=requestCalendar){Text("ALLOW CALENDAR ACCESS")} else { val selectedCalendars=records.filter {it.kind=="CalendarSelection"&&it.data().flag("enabled")}.map {it.data().text("calendarId")}.toSet(); val calendars=runCatching {uk.co.james.schedule.CalendarProvider(vm.app.contentResolver).calendars()}.getOrDefault(emptyList()); if(calendars.isEmpty())Muted("No device calendars are available or selected.") else calendars.forEach {calendar->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(calendar.name,modifier=Modifier.weight(1f));Switch(calendar.id in selectedCalendars,onCheckedChange={vm.calendarSelection(calendar.id,calendar.name,it)})}}; TextButton(onClick={vm.navigate("Schedule")}){Text("OPEN UPCOMING SCHEDULE")}; Muted("Only enabled calendars contribute. All-day and FREE events are not fixed constraints. Unknown ownership is allowed; classification is optional and user-initiated.") } }}
    cards.add {JamesCard("Health Connect",if(health.granted.isEmpty())"Not connected"else "${health.granted.size} permissions granted"){Muted(health.explanation);vm.app.health.types.keys.forEach {name->Row {Checkbox(name in selected,onCheckedChange={selected=if(it)selected+name else selected-name});Text(name)}};Button(enabled=health.available&&selected.isNotEmpty(),onClick={requestHealth(vm.app.health.permissions(selected.toSet()))}){Text("Choose health permissions")};TextButton(enabled=health.granted.isNotEmpty(),onClick={vm.healthSync()}){Text("Sync now")};TextButton(enabled=health.granted.isNotEmpty(),onClick={vm.healthDisconnect()}){Text("Disconnect")};val synced=records.find {it.recordId=="source:health_connect"}?.data()?.text("lastSync").orEmpty();Muted("Last successful sync: ${providerSyncStatus(synced)} · ${synced.ifBlank {"Never"}}");Muted("Nutrition permissions control ingestion. The Live Energy nutrition setting only controls contextual use; provenance is retained.");Muted("Automatic refresh: about every 15 minutes in the background and every 5 minutes while Today is open. Android may delay background work.");Muted("Only authorised records are read. Originating providers are retained. Imported history stays available offline.")}}
    val lastSync=records.find {it.recordId=="source:health_connect"}?.data()?.text("lastSync")
    fun sourceMetrics(packageName:String)=records.filter {it.kind=="HealthMetric"&&it.source=="health_connect"&&it.data().text("provider").split(',').any {p->p.trim()==packageName}}
    listOf("Samsung Health" to "com.sec.android.app.shealth","WHOOP" to "com.whoop.android").forEach {(name,packageName)->
        val received=sourceMetrics(packageName)
        cards.add {JamesCard("$name via Health Connect",if(received.isNotEmpty())"${if(health.granted.isEmpty())"Saved data"else "Data received"}"else "No records received yet") {
            Muted(if(received.isEmpty())"Enable sharing to Health Connect in $name, then allow James to read the health types you want."else "Available: "+received.map {it.data().text("metric")}.distinct().joinToString())
            Muted("Health Connect last sync: ${lastSync?.let {localClock(it)}?:"Never"}")
            TextButton(enabled=health.granted.isNotEmpty(),onClick={vm.healthSync()}){Text("Sync Health Connect")}
        }}
    }
    val nutrition=records.filter {it.kind in setOf("Nutrition","NutritionEvent")&&it.source=="health_connect"}
    val nutritionNow=Instant.now()
    val nutritionDay=jamesDayWindow(records,nutritionNow,ZoneId.systemDefault())
    val nutritionToday=nutritionToday(nutrition,nutritionDay,nutritionNow)
    val nutritionSync=records.firstOrNull {it.recordId=="source:health_connect_nutrition"}?.data()
    cards.add {JamesCard("Nutrition via Health Connect",if(nutrition.isEmpty())"No records received"else "${nutrition.size} meal records") {
        Text("Permission: ${nutritionSync?.text("nutritionPermission")?:if(health.granted.any {it.contains("READ_NUTRITION")})"Granted" else "Not granted"}")
        nutritionSync?.text("lastSync")?.takeIf {it.isNotBlank()}?.let {Text("Last query: ${localClock(it)}")}
        nutritionSync?.let {Muted("Returned: ${it.number("nutritionRecordsReturned",0.0).toInt()} · Accepted: ${it.number("recordsAccepted",0.0).toInt()}")}
        if(nutrition.isNotEmpty()) Muted("Current James Day: ${nutritionToday.meals.size} records · ${nutritionToday.calories?.let { "${it.toInt()} kcal" } ?: "energy unavailable"}")
        if(nutrition.isEmpty())Muted("No supported Nutrition records have been returned by Health Connect yet. Samsung Health can display app-synced food that is not necessarily written back as a Health Connect Nutrition record.")
        else {Text("Providers: ${nutrition.map {it.data().text("provider")}.filter {it.isNotBlank()}.distinct().joinToString()}");Muted("Provider names come from Health Connect provenance. Generic records are not relabelled as MyNetDiary.")}
    }}
    val work=uk.co.james.work.deriveCurrentWorkState(records)
    val workMeta=records.firstOrNull {it.recordId=="shift-tracker-integration"}?.data()
    cards.add {JamesCard("Shift Tracker",if(vm.app.work.endpointAvailable())"Connected · Contract v2" else "Shift Tracker 2.2.65 not detected") {Text("James OS receiver: READY · Contract v2");Text("Current state: ${work.mode.name.replace("_"," ")}");workMeta?.text("lastSuccessfulIngest")?.takeIf {it.isNotBlank()}?.let {Muted("Last import: ${localClock(it)}")};Muted(if(vm.app.work.endpointAvailable())"Shift Tracker remains authoritative. No test shifts are created here." else "Install or update Shift Tracker 2.2.65, then reconnect it here. No test shifts are created here.");TextButton(onClick={vm.reconcileShiftTracker()}){Text("RECONCILE NOW")}}}
    val whoopConfigured by vm.whoopConfigured.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val whoop=records.firstOrNull {it.recordId=="source:whoop"}?.data()
    val whoopLast=whoop?.text("lastSuccess").takeIf { !it.isNullOrBlank() }?:whoop?.text("lastSync").orEmpty()
    cards.add {JamesCard("WHOOP API",if(!whoopConfigured)"Not connected"else when(whoop?.text("status")){"synced"->"Connected · ${providerSyncStatus(whoopLast)}";"error"->"Needs attention";else->"Finish sign-in"}) {
        Muted("Recovery, sleep, Strain, workouts and overnight Health Monitor readings: HRV, resting heart rate, respiratory rate, blood oxygen and skin temperature. History imports the last 28 days. WHOOP remains authoritative.")
        whoop?.text("lastSync")?.takeIf {it.isNotBlank()}?.let {Muted("Last sync: ${displayDate(dayOf(it))} · ${localClock(it)}")}
        whoop?.text("error")?.takeIf {it.isNotBlank()}?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(enabled=!busy,onClick={vm.whoopConnect()}){Text(if(whoopConfigured)"Reconnect WHOOP"else "Connect WHOOP")}
        if(whoopConfigured){TextButton(enabled=!busy,onClick={vm.whoopSync()}){Text(if(whoop?.text("status")=="synced")"Sync WHOOP"else "Finish connection & sync")};TextButton(enabled=!busy,onClick={vm.whoopDisconnect()}){Text("Disconnect")}}
        Muted("Sign in with your ChatGPT owner account, then authorise WHOOP. Return here to finish. WHOOP does not provide its live Stress Monitor score through this API.")
    }}
    AdaptiveCards(cards)
}
@Composable fun ImportScreen(vm:JamesViewModel,choose:()->Unit,export:(String?)->Unit) {
    val plan by vm.plan.collectAsStateWithLifecycle();val merge by vm.merge.collectAsStateWithLifecycle();val history by vm.imports.collectAsStateWithLifecycle();val busy by vm.busy.collectAsStateWithLifecycle();val records by vm.records.collectAsStateWithLifecycle()
    val cards=mutableListOf<@Composable ()->Unit>()
    cards.add {PageTitle("Import Centre","YOUR HISTORY COMES WITH YOU")}
    cards.add {JamesCard("Import existing RUT data"){Muted("Choose a RUT, James web or James Android JSON backup. The original file and a pre-import snapshot are preserved.");Button(enabled=!busy,onClick=choose){Text("Choose backup / file")};plan?.let {p->Text("Source: ${p.source}");Text("Records found: ${p.rows.size}");Text("Routines / event definitions: ${p.routines}");Text("Historical entries: ${p.entries}");Text("Date range: ${p.dateRange}");Text("Validation: passed");merge?.let {m->Text("${m.additions.size} to add · ${m.duplicates} duplicates ignored");Text("${m.conflicts.size} conflicts: existing James records retained");m.conflicts.take(10).forEach {Muted(it)}};Button(enabled=!busy,onClick={vm.import()}){Text("Import")};TextButton(onClick={vm.refreshPreview()}){Text("Refresh preview")};TextButton(onClick={vm.cancelImport()}){Text("Cancel")}}}}
    cards.add {JamesCard("Backups"){Button(onClick={export(null)}){Text("Export all James data")};Muted("Restore uses the same validated, additive import. Conflicting records are never silently replaced.")}}
    records.firstOrNull {it.store=="metadata"&&it.recordId=="archive-cleanup"}?.raw()?.obj("value")?.let {cleanup->cards.add {JamesCard("Private archive cleanup","DIAGNOSTICS") {Text("Backups retained: ${cleanup.number("backupFilesRetained").toInt()}");Text("Orphans found / cleaned: ${cleanup.number("orphanFilesFound").toInt()} / ${cleanup.number("orphanFilesCleaned").toInt()}");Text("Staged imports retained: ${cleanup.number("stagedImportsRetained").toInt()}");Text("Cleanup failures: ${cleanup.number("cleanupFailures").toInt()}");Muted("Last cleanup: ${cleanup.text("lastCleanup","Not run")}")}}}
    cards.add {JamesCard("Future imports"){Muted("Shift Tracker and Gig Tracker need verified backup formats before importing. Unsupported files are rejected without changing your data.")}}
    history.forEach {h->cards.add {JamesCard(h.source,h.timestamp){Text("${h.found} found · ${h.added} added · ${h.duplicates} duplicates");Text("${h.conflicts} conflicts retained · Errors: ${h.errors.ifBlank {"None"}}");TextButton(onClick={export(h.originalArchiveId)}){Text("Export original backup")}}}}
    AdaptiveCards(cards)
}
@Composable fun LocationScreen(vm:JamesViewModel,records:List<StoredRecord>,requestLocation:()->Unit,requestActivity:()->Unit,settings:()->Unit) {
    val enabled by vm.locationEnabled.collectAsStateWithLifecycle()
    val allDay by vm.allDayLocation.collectAsStateWithLifecycle()
    val activity by vm.activityEnabled.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val calibration by vm.placeCalibration.collectAsStateWithLifecycle()
    val currentOwnership by vm.currentOwnership.collectAsStateWithLifecycle()
    val pendingPlaceSave by vm.pendingPlaceSave.collectAsStateWithLifecycle()
    val pendingPlaceMerge by vm.pendingPlaceMerge.collectAsStateWithLifecycle()
    var name by rememberSaveable {mutableStateOf("")}
    var category by rememberSaveable {mutableStateOf("Home")}
    val visits=uk.co.james.location.authoritativeVisits(records).sortedByDescending {it.timestamp}
    val savedPlaces=records.filter {it.kind=="Place"&&it.data().text("status")!="MERGED"}
    val likelyDuplicatePlaces=remember(savedPlaces) {
        savedPlaces.indices.flatMap {firstIndex->savedPlaces.drop(firstIndex+1).mapNotNull {second->
            val first=savedPlaces[firstIndex];val firstData=first.data();val secondData=second.data();val distance=FloatArray(1)
            Location.distanceBetween(firstData.number("latitude"),firstData.number("longitude"),secondData.number("latitude"),secondData.number("longitude"),distance)
            if(distance[0]<=50f&&uk.co.james.location.placeNamesSupportSameIdentity(firstData.text("title"),secondData.text("title"))) first to second else null
        }}
    }
    val unknownVisits=visits.filter {visit->visit.data().text("title","Unknown place").let {it.isBlank()||it=="Unknown place"}}
    val current=records.firstOrNull {it.kind=="LocationAnchor"}
    val activeContext=records.filter {it.kind=="ContextPeriod"&&it.data().text("end").isBlank()}.maxByOrNull {it.timestamp}
    fun visitTime(minutes:Long)="${minutes/60}h ${minutes%60}m"
    AdaptiveCards(listOf(
        {PageTitle("Places","PRIVATE, LIGHTWEIGHT AND USEFUL")},
        {JamesCard("Tracking & diagnostics",if(allDay)"Low-power tracking active"else "Off") {
            Muted("James checks for a low-power location about every five minutes. A stop only appears after you have stayed there for at least five minutes—this is a places timeline, not a permanent route trace.")
            TextButton(onClick=requestLocation){Text("Allow location")}
            TextButton(onClick=settings){Text("Android settings: choose Allow all the time")}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {Text(if(allDay)"Building today’s places"else "Build my places timeline",modifier=Modifier.padding(top=13.dp));Switch(allDay,onCheckedChange={value->vm.action {vm.app.location.setAllDay(value)}})}
            Button(onClick={vm.navigate("Location map")}){Text("Open map")}
            Muted("Tracking continues with the screen off while this is on. James stores completed visits rather than an endless location trail; timing and position are approximate.")
            current?.let {anchor->
                val d=anchor.data()
                val passiveSeen=runCatching {d.text("passiveLastSeen").takeIf {it.isNotBlank()}?.let(Instant::parse)}.getOrNull()
                val passiveAge=passiveSeen?.let {uk.co.james.location.ageText(java.time.Duration.between(it,Instant.now()).coerceAtLeast(java.time.Duration.ZERO))}
                val geofence=d.text("placeSource")=="GEOFENCE"
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                Text("Current place: ${d.text("placeName","Unknown place")}")
                Muted("Here ${visitTime(d.number("durationMin").toLong())} · ${d.text("movement","Stationary")} · ${if(geofence)"confirmed by geofence" else "accuracy ${d.number("accuracy").toInt()}m"}")
                Muted(if(passiveAge==null)"No passive location fix is attached to this current visit." else "Latest passive fix: $passiveAge old · ${d.text("passiveProvider","Fused low-power")}")
                Muted("Place evidence: ${d.text("placeConfidence","UNKNOWN").lowercase().replaceFirstChar(Char::uppercase)} · ${d.text("placeSource","PASSIVE").lowercase().replace('_',' ')}")
                activeContext?.data()?.text("visitType")?.let {Muted("Context: ${it.lowercase().replace('_',' ').replaceFirstChar(Char::uppercase)}")}
                Muted("Time ownership: ${currentOwnership?.data()?.text("ownership")?:"UNKNOWN"} · ${currentOwnership?.data()?.text("ownershipSource")?:"No confirmation"}")
            }
            CurrentOwnershipControls(vm)
        }},
        {JamesCard("Recent places",if(visits.isEmpty())"No completed stops yet"else "${visits.size} recorded") {
            visits.take(14).forEach {visit->val data=visit.data();TextButton(onClick={vm.open("PlaceVisit",visit)},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=8.dp)) {Column(Modifier.fillMaxWidth()) {Text(if(data.text("title","Unknown place")=="Unknown place")"Where was this?" else data.text("title"));Muted("${visitTime(data.number("durationMin").toLong())} · ${data.text("category","Unclassified")} · ${data.text("activity","Unknown")}")}}}
            if(visits.isEmpty())Muted("A visit appears once James has seen you stay in the same area for at least five minutes. You can add what you did and a note afterwards.")
        }},
        {JamesCard("Time ownership","Only James confirms ownership") {
            val now=java.time.Instant.now();val day=uk.co.james.time.jamesDayWindow(records,now)
            // Route history is bounded; the dedicated active ownership stream
            // supplies the authoritative live segment while it is open.
            val ownershipRecords=records.filterNot {it.kind=="OwnershipPeriod"&&it.data().text("end").isBlank()}+listOfNotNull(currentOwnership)
            val intervals=uk.co.james.location.ownershipIntervals(ownershipRecords,day.start,now)
            val summary=uk.co.james.location.ownershipSummary(intervals)
            Text("Autonomous ${visitTime(summary.autonomousMinutes)} · Constrained ${visitTime(summary.constrainedMinutes)}")
            Muted("Unknown ${visitTime(summary.unknownMinutes)} · ${summary.interruptions} interruption${if(summary.interruptions==1)"" else "s"} · longest autonomous block ${visitTime(summary.longestAutonomousBlockMinutes)}")
        }},
        {val unresolved=visits.filter {it.data().text("ownership","UNKNOWN")=="UNKNOWN"}.take(3);if(unresolved.isNotEmpty()) JamesCard("Periods to review","${unresolved.size} ownership decision${if(unresolved.size==1)"" else "s"}") {
            Muted("Only completed visits with useful duration appear here. Unknown stays Unknown until you choose.")
            unresolved.forEach {visit->val data=visit.data();Text("${data.text("title","Unknown place")} · ${visitTime(data.number("durationMin").toLong())}");Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){listOf("AUTONOMOUS" to "PERSONAL","COMMITTED" to "OBLIGATION","CONSTRAINED" to "CONSTRAINED","WORK" to "WORK").forEach {(value,label)->TextButton(onClick={vm.setVisitOwnership(visit,value)},modifier=Modifier.weight(1f)){Text(label,style=MaterialTheme.typography.labelSmall)}}}}
        }},
        {if(unknownVisits.isNotEmpty()) JamesCard("Where was this?","${unknownVisits.size} stop${if(unknownVisits.size==1)"" else "s"} need${if(unknownVisits.size==1)"s" else ""} a name") {
            Muted("Choose a saved place to label a completed stop. James will use that label automatically next time you are nearby.")
            unknownVisits.take(5).forEach {visit->
                val data=visit.data()
                Text("Stayed about ${visitTime(data.number("durationMin").toLong())}")
                if(savedPlaces.isEmpty()) TextButton(onClick={vm.open("PlaceVisit",visit)}){Text("Name this stop")}
                else {
                    savedPlaces.take(6).forEach {place->TextButton(onClick={vm.action {vm.app.location.labelVisit(visit.recordId,place.recordId)}}){Text("This was ${place.data().text("title")}")}}
                    TextButton(onClick={vm.open("PlaceVisit",visit)}){Text("Use another name")}
                }
            }
        }},
        {current?.takeIf {it.data().text("placeConfidence") in setOf("","LOW","UNKNOWN","AMBIGUOUS")&&savedPlaces.isNotEmpty()}?.let {JamesCard("Where are you?","Current place is not confidently resolved") {
            Muted("Choose an existing saved place for this live visit. This will not create a new place or change Time Ownership.")
            savedPlaces.take(6).forEach {place->TextButton(onClick={vm.confirmCurrentPlace(place)},modifier=Modifier.fillMaxWidth()){Text("This is ${place.data().text("title")}")}}
            TextButton(onClick={}){Text("Leave as Unknown")}
        }}},
        {JamesCard("Known places",if(enabled)"Geofences enabled"else "Geofences off") {
            Muted("Save places such as Home or Work. Future visits nearby can use that name and time category automatically.")
            Switch(enabled,onCheckedChange={value->vm.action {vm.app.location.setEnabled(value)}})
            OutlinedTextField(name,{name=it},label={Text("Current place name")},modifier=Modifier.fillMaxWidth())
            Choice("Place category",category,listOf("Home","Work","Family","Shopping","Gym","Food","Leisure","Health / appointment","Personal","Other")){category=it}
            calibration?.let {state->Text(state.title,style=MaterialTheme.typography.labelLarge);Muted(state.detail)}
            Button(enabled=name.isNotBlank()&&!busy,onClick={vm.saveCurrentPlace(name,category)}){Text(if(calibration?.inProgress==true)"Getting precise location…" else "Save current place")}
            pendingPlaceSave?.let {pending->
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                Text("Nearby saved place",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                Muted("${pending.existingTitle} is ${pending.distanceMetres.toInt()}m away.${if(pending.sameName)" The names look similar." else " It may still be a separate nearby place."}")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick={vm.resolveCurrentPlaceSave(true)},modifier=Modifier.weight(1f)){Text("UPDATE EXISTING")}
                    OutlinedButton(onClick={vm.resolveCurrentPlaceSave(false)},modifier=Modifier.weight(1f)){Text("SAVE SEPARATE")}
                }
                TextButton(onClick=vm::dismissCurrentPlaceSave){Text("CANCEL")}
            }
            Muted("A saved place needs a fresh fix within ±${uk.co.james.location.PLACE_CALIBRATION_MAX_ACCURACY_METRES.toInt()}m. Passive five-minute tracking is not weakened for calibration.")
            savedPlaces.forEach {Text("${it.data().text("title")} · ${it.data().text("category","Unclassified")}")}
            likelyDuplicatePlaces.forEach {(first,second)->
                val canonical=if(first.data().text("category","Unclassified")=="Unclassified"&&second.data().text("category","Unclassified")!="Unclassified") second else first
                val duplicate=if(canonical===first)second else first
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                Text("Possible duplicate",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                Muted("${first.data().text("title")} and ${second.data().text("title")} are nearby with matching names. Merging preserves the duplicate and repoints factual history to one canonical place.")
                OutlinedButton(onClick={vm.requestPlaceMerge(canonical,duplicate)},modifier=Modifier.fillMaxWidth()){Text("MERGE INTO ${canonical.data().text("title").uppercase()}")}
            }
            pendingPlaceMerge?.let {merge->
                HorizontalDivider(Modifier.padding(vertical=8.dp))
                Text("Merge saved places?",fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
                Muted("Keep ${merge.canonicalTitle}. ${merge.duplicateTitle} becomes preserved merge history; visits and current place links move to the kept place.")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=vm::mergePendingPlaces,modifier=Modifier.weight(1f)){Text("MERGE")}
                    OutlinedButton(onClick=vm::dismissPlaceMerge,modifier=Modifier.weight(1f)){Text("CANCEL")}
                }
            }
        }
        },
        {JamesCard("Movement recognition",if(activity)"Enabled"else "Disabled") {
            Muted("Android can identify likely walking, running and driving transitions. It cannot know the purpose of an activity.")
            TextButton(onClick=requestActivity){Text("Request activity permission")}
            Switch(activity,onCheckedChange={value->vm.action {vm.app.location.setActivity(value)}})
        }}
    ))
}

@Composable private fun AlgorithmsSettingsCard(vm:JamesViewModel) {
    JamesCard("Algorithms","VERSIONED JAMES OS COMPONENTS") {
        Muted("James’s score engines are versioned like internal firmware. Versions describe the calculation, while calibration versions describe James-specific tuning.");Button(onClick={vm.navigate("Calibration Lab")}){Text("CALIBRATION LAB")};Muted("Teach James OS what James actually feels. Feedback never changes a score immediately.")
        JamesAlgorithmRegistry.entries.forEach {entry->
            TextButton(onClick={vm.navigate("Algorithm:${entry.id}")},modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(vertical=7.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.displayName,style=MaterialTheme.typography.titleSmall)
                        Text("v${entry.algorithmVersion} · Calibration v${entry.calibrationVersion}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(entry.status.name,color=when(entry.status){AlgorithmStatus.ACTIVE->stateMint;AlgorithmStatus.EXPERIMENTAL->jamesAmber;AlgorithmStatus.LEARNING->jamesBlue;AlgorithmStatus.CALIBRATING->jamesAmber;AlgorithmStatus.DEPRECATED->MaterialTheme.colorScheme.onSurfaceVariant},style=MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable fun AlgorithmDetailScreen(vm:JamesViewModel,id:String) {
    val entry=JamesAlgorithmRegistry.get(id)
    if(entry==null) {AdaptiveCards(listOf({PageTitle("Algorithm","NOT FOUND")},{JamesCard("Unavailable"){Muted("This component is not registered on this James OS version.")}}));return}
    val records by vm.records.collectAsStateWithLifecycle()
    val bodySettings by vm.bodyBatterySettings.collectAsStateWithLifecycle()
    val energySettings by vm.energyTimeSettings.collectAsStateWithLifecycle()
    val stressCheck by vm.wearStressCheck.collectAsStateWithLifecycle()
    val battery=if(id=="body_battery") bodyBattery(records) else null
    val rightNow=if(id in setOf("live_energy","sleepiness","energy_sustainability","crash_risk","time_pressure"))rightNowSummary(records,energySettings)else null
    val lowMood=if(id=="low_mood_load") mentalWellbeing(records).lowMood else null
    val lowMoodEvidenceSummary=lowMood?.let {lowMoodEvidence(records,it)}
    val cards=mutableListOf<@Composable ()->Unit>()
    cards.add {PageTitle(entry.displayName,"JAMES OS ALGORITHM")}
    cards.add {JamesCard("Component status",entry.status.name) {
        Text("Algorithm v${entry.algorithmVersion}",style=MaterialTheme.typography.headlineSmall)
        Text("Calibration v${entry.calibrationVersion}")
        Muted("Updated ${entry.updated} · Input schema ${entry.inputSchemaVersion} · Output schema ${entry.outputSchemaVersion}")
        Muted(if(entry.supportsHistoricalRecalculation)"Original outputs retain their version. Future recalculation is non-destructive." else "Historical recalculation is not supported yet.")
    }}
    val calibrationEvents=remember(records,id){records.mapNotNull(uk.co.james.calibration.JamesCalibrationEngine::parse).filter {it.algorithmId==id}}
    val calibrationQuality=remember(calibrationEvents){uk.co.james.calibration.JamesCalibrationEngine.evaluate(calibrationEvents)}
    if(uk.co.james.calibration.JamesCalibrationCatalog.get(id)?.supportsCalibration==true)cards.add {JamesCard("Personal calibration",calibrationQuality.quality.name.replace('_',' ')) {
        Text(calibrationEvents.size.toString()+" observations")
        calibrationQuality.meanAbsoluteError?.let {Text("Typical error: ±"+String.format(java.util.Locale.UK,"%.1f",it)+" points")}
        Button(enabled=vm.calibrationTarget(id)!=null,onClick={vm.navigate("Calibration:"+id)}){Text("CALIBRATE / OPEN LAB")}
        Muted("Current input confidence and historical calibration accuracy are separate.")
    }}
    cards.add {JamesCard("How it works"){Text(entry.description);Text("Inputs",style=MaterialTheme.typography.labelLarge);entry.inputs.forEach {Text("• $it")}}}
    if(id=="low_mood_load"&&lowMood!=null&&lowMoodEvidenceSummary!=null) {
        cards.add {JamesCard("Why this score","LOW MOOD · ${lowMood.score}/100") {
            Text("${lowMood.label} · mood confidence ${lowMoodEvidenceSummary.moodConfidence}")
            Text("Input coverage: ${lowMoodEvidenceSummary.inputCoverage}")
            Text("Direct mood evidence: ${lowMoodEvidenceSummary.directEvidence}")
            Text("Prior/base: ${lowMoodEvidenceSummary.baseScore}")
            if(lowMoodEvidenceSummary.calibrationEffect!=0)Text("Calibration effect: ${if(lowMoodEvidenceSummary.calibrationEffect>0)"+" else ""}${lowMoodEvidenceSummary.calibrationEffect}") else Text("Calibration effect: none")
            Text("Trend: insufficient trend evidence")
            Muted("This is a non-diagnostic estimate of low/flat mood. Missing direct evidence is not evidence that you feel happy.")
            Text("CONTRIBUTORS",style=MaterialTheme.typography.labelLarge)
            lowMood.contributors.sortedByDescending {kotlin.math.abs(it.contribution)}.forEach {item->Text("${if(item.contribution>=0)"+" else ""}${String.format(java.util.Locale.UK,"%.1f",item.contribution)} ${item.source}");Muted(item.explanation)}
            if(lowMoodEvidenceSummary.missingEvidence.isNotEmpty()) {Text("MISSING EVIDENCE",style=MaterialTheme.typography.labelLarge);lowMoodEvidenceSummary.missingEvidence.forEach {Muted("• $it")}}
            Button(onClick={vm.navigate("Calibration:low_mood_load")},modifier=Modifier.fillMaxWidth()){Text("RATE LOW / FLAT MOOD")}
        }}
    }
    if(id=="body_battery") {
        cards.add {JamesCard("Calibration controls","YOU CAN CHANGE THIS WITHOUT CHANGING THE ALGORITHM") {
            SensorSwitch("Apply bounded recovery readiness adjustment",bodySettings.recoveryAwareStrain){enabled->vm.bodyBatterySettings(bodySettings.copy(recoveryAwareStrain=enabled))}
            Muted("When enabled, low or high WHOOP Recovery changes Strain cost by at most -10% to +12%. The nonlinear curve itself is unchanged.")
        }}
    }
    if(battery?.strainDiagnostics!=null) {
        val d=battery.strainDiagnostics
        cards.add {JamesCard("Live diagnostics","BODY BATTERY v${battery.algorithmVersion}") {
            Text("James Day ID: ${d.jamesDayId}")
            battery.trace?.let {trace->
                Text("James Day start: ${trace.jamesDayStart}")
                Text("Accepted main sleep: ${trace.acceptedMainSleepId?:"Unavailable"}")
                Text("Context unit: ${trace.contextTimeUnit}")
                trace.contextTimeSeconds.filterValues {it>0L}.forEach {(name,seconds)->Text("$name: $seconds seconds (${seconds/60} min)")}
                Text("Previous persistence: ${trace.previousPersistenceStatus} · ${trace.previousPersistenceReason.ifBlank {"no rejection"}}")
            }
            Text("WHOOP cycle: ${d.whoopCycleId?:"Awaiting"}")
            Text("Raw WHOOP Strain: ${d.rawWhoopStrain?.let {String.format(java.util.Locale.UK,"%.1f",it)}?:"Unavailable"} · ${d.category}")
            Text("Transformed total Reserve cost: -${String.format(java.util.Locale.UK,"%.1f",d.transformedTotalCost)}")
            Text("Already accounted: -${String.format(java.util.Locale.UK,"%.1f",d.alreadyAccountedCost)}")
            Text("Latest incremental debit: -${String.format(java.util.Locale.UK,"%.1f",d.latestIncrementalDebit)}")
            Text("Recovery modifier: ${if(d.recoveryModifier>=0)"+" else ""}${(d.recoveryModifier*100).toInt()}%")
            Text("WHOOP timestamp: ${d.whoopTimestamp?:"Unavailable"}")
            Muted(d.sourceReconciliation)
        }}
    }
    if(id=="anxiety_load") {
        cards.add {JamesCard("Stress refresh diagnostics","PROTOCOL v${uk.co.james.wear.CompanionContract.STRESS_CHECK_PROTOCOL_VERSION}") {
            Text("Request ID: ${stressCheck.requestId.ifBlank {"None"}}")
            Text("State: ${stressCheck.stage}")
            Text("Requested: ${stressCheck.requestedAt.takeIf {it>0L}?:"—"}")
            Text("Watch acknowledged: ${stressCheck.acknowledgedAt.takeIf {it>0L}?:"—"}")
            Text("Measurement started: ${stressCheck.startedAt.takeIf {it>0L}?:"—"}")
            Text("Measurement completed: ${stressCheck.measurementCompletedAt.takeIf {it>0L}?:"—"}")
            Text("Stress persisted: ${stressCheck.stressPersistedAt.takeIf {it>0L}?:"—"}")
            Text("Anxiety recalculated: ${stressCheck.anxietyRecalculatedAt.takeIf {it>0L}?:"—"}")
            Muted(stressCheck.message.ifBlank {"No manual refresh has run in this app process."})
        }}
    }
    rightNow?.let {summary->
        val metric=when(id){"live_energy"->summary.liveEnergy;"sleepiness"->summary.sleepiness;"energy_sustainability"->summary.sustainability;"crash_risk"->summary.crashRisk;else->summary.timePressure}
        cards.add {JamesCard("Live diagnostics","${metric.label} · ${metric.score}/100") {
            Text("Calculated: ${metric.calculatedAt}")
            Text("Confidence: ${metric.confidence}")
            if(id=="time_pressure") {
                Text("Evidence state: ${metric.evidenceState.replace('_',' ')}")
                when(metric.evidenceState) {
                    "NO_KNOWN_CONSTRAINT"->Muted("No upcoming fixed constraint detected. This is limited evidence, not a measured zero or a claim about Time Ownership.")
                    "DIRECT"->Muted("A fresh direct Time Pressure check-in supports this presentation.")
                    else->Muted("This is inferred from known time constraints; Time Ownership remains a separate measure.")
                }
                Text("Prior/base: ${metric.baseScore}")
                metric.contributors.firstOrNull {it.name=="Personal calibration"}?.let {Text("Calibration effect: ${if(it.contribution>0)"+" else ""}${String.format(java.util.Locale.UK,"%.1f",it.contribution)}")}
            }
            Text("James Day: ${summary.jamesDay.id}")
            Text("Body Battery: ${summary.bodyBattery?:"Unavailable"} · Mental Reserve: ${summary.mentalReserve}")
            Text("Awake: ${summary.awakeMinutes/60}h ${summary.awakeMinutes%60}m · Time That Was Mine: ${summary.personalMinutes}m")
            if(id=="sleepiness")summary.sleepinessDetails.let {sleepy->
                Text("Underlying sleep pressure: ${sleepy.underlyingPressure} · Raw model: ${sleepy.rawExpressedSleepiness} · Production Sleepiness: ${sleepy.expressedSleepiness}")
                if(sleepy.calibrationApplied)Muted("An accepted personal calibration is included in the production Sleepiness value.")
                Text("Main sleep: ${sleepy.mainSleepMinutes?.roundToInt()?.let {"$it min"}?:"pending/unavailable"} · ${sleepy.mainSleepSource?:"unknown source"}")
                Text("Wake: ${sleepy.wakeAt} · recent baseline: ${sleepy.baselineSleepMinutes?.roundToInt()?.let {"$it min"}?:"learning"}")
                Text("Wake contribution: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.wakeContribution)} · short sleep: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.shortSleepContribution)}")
                Text("Recent shortfall: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.recentShortfallContribution)} · circadian: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.circadianContribution)}")
                Text("Nap modifier: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.napModifier)} · caffeine modifier: ${String.format(java.util.Locale.UK,"%+.1f",sleepy.caffeineModifier)}")
                Text("Sleep source freshness: ${sleepy.sourceFreshness}")
                sleepy.latestCaffeineAt?.let {Text("Latest supported caffeine: $it · ${sleepy.caffeineSource?:"source retained"}")}
                Muted("Estimated Sleepiness is a personal experimental estimate, not a diagnosis. Caffeine and naps affect expressed Sleepiness only; they do not erase underlying sleep pressure.")
            }
            summary.nextConstraint?.let {constraint->Text("Next constraint: ${constraint.title} at ${constraint.startsAt}");Text("Until: ${constraint.minutesUntil}m · preparation ${constraint.preparationMinutes}m · travel ${constraint.travelMinutes}m · usable ${constraint.usableMinutes}m")}?:Text("Next constraint: unavailable")
            summary.nutrition.latestMealAt?.let {Text("Latest meal: $it · ${summary.nutrition.source?:"Health Connect"}")}
            metric.contributors.forEach {item->Text("${item.name}: ${String.format(java.util.Locale.UK,"%+.1f",item.contribution)}");Muted("${item.explanation}${item.observedAt?.let {stamp->" · $stamp"}?:""}${if(item.freshnessMultiplier<.999)" · freshness ${(item.freshnessMultiplier*100).toInt()}%"else""}")}
        }}
    }
    cards.add {JamesCard("What changed") {entry.changes.forEach {change->Text("v${change.version} · ${change.date}",style=MaterialTheme.typography.titleSmall);Text(change.title,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary);change.details.forEach {Muted("• $it")}}}}
    AdaptiveCards(cards)
}
