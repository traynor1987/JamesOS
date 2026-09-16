package uk.co.james.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.*
import uk.co.james.JamesApplication
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.imports.*
import uk.co.james.health.HealthStatus
import uk.co.james.location.diagnostic
import uk.co.james.updates.*
import uk.co.james.state.mentalWellbeing
import uk.co.james.state.MentalWellbeingSummary
import uk.co.james.state.bodyBattery
import uk.co.james.state.rightNowSummary
import uk.co.james.state.JamesAlgorithmRegistry
import uk.co.james.calibration.*
import java.io.File

data class PlaceCalibrationUiState(val title:String,val detail:String,val inProgress:Boolean)
data class RouteRecordsState(val key:String,val records:List<StoredRecord>,val loaded:Boolean)
data class HistoryReadiness(val loaded:Boolean,val hasUserHistory:Boolean)
data class CurrentHealthInputsReadiness(val loaded:Boolean)

/** A route result is valid only for the exact route/date/range request which
 * produced it.  Bottom navigation changes synchronously, while Room's new
 * query emits asynchronously; without this identity a new screen could briefly
 * render the previous screen's data as if it were its own. */
internal fun routeRequestKey(route:String,date:String,mapRange:Int)="$route|$date|$mapRange"
internal fun RouteRecordsState.matches(requestKey:String)=key==requestKey

/** Route changes must expose an explicit loading boundary instead of allowing
 * a destination to reuse the previous destination's bounded Room snapshot. */
internal fun routeRecords(key:String,query:Flow<List<StoredRecord>>):Flow<RouteRecordsState> = flow {
    emit(RouteRecordsState(key,emptyList(),false))
    emitAll(query.map {RouteRecordsState(key,it,true)})
}

class JamesViewModel(application: Application,private val saved: SavedStateHandle): AndroidViewModel(application) {
    private var lastQuietHealthSync=0L
    private var lastQuietWhoopSync=0L
    val app=application as JamesApplication
    val repo=app.repository
    val records=repo.records.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val wellbeingSettings=app.preferences.wellbeing.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),uk.co.james.settings.WellbeingSettings())
    val energyTimeSettings=app.preferences.energyTime.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),uk.co.james.settings.EnergyTimeSettings())
    private val stateRecordsSnapshot=flow {
        emit(RouteRecordsState("current-health",emptyList(),false))
        emitAll(repo.dao.observeStateInputs(java.time.Instant.now().minus(java.time.Duration.ofDays(40)).toString())
            .map { RouteRecordsState("current-health",it,true) })
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),RouteRecordsState("current-health",emptyList(),false))
    /** Derived snapshots are retained for history/display but must never
     * invalidate the bounded source-evidence stream that persists them. */
    private val stateRecords=stateRecordsSnapshot.map {todayPreparationInputRecords(it.records)}
        .distinctUntilChanged()
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val currentHealthInputsReadiness=stateRecordsSnapshot.map {CurrentHealthInputsReadiness(it.loaded)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),CurrentHealthInputsReadiness(false))
    val historyReadiness=repo.dao.observeHasUserHistory().map {HistoryReadiness(true,it)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),HistoryReadiness(false,false))
    val wellbeing=combine(stateRecords,wellbeingSettings) { rows, settings->uk.co.james.state.mentalWellbeing(rows,settings) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),MentalWellbeingSummary(today(),uk.co.james.state.WellbeingOutput(0,"VERY LOW","LEARNING", "LEARNING",emptyList()),uk.co.james.state.WellbeingOutput(0,"VERY LOW","LEARNING","LEARNING",emptyList()),uk.co.james.state.WellbeingOutput(50,"OKAY","LEARNING","LEARNING",emptyList()),0,0,0,0,0))
    val imports=repo.imports.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val theme=app.preferences.theme.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),"system")
    val compactToday=app.preferences.compactToday.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),true)
    val locationEnabled=app.preferences.location.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    val activityEnabled=app.preferences.activity.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    val allDayLocation=app.preferences.allDay.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    val bodyBatterySettings=app.preferences.bodyBattery.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),uk.co.james.settings.BodyBatterySettings())
    val wearSensors=app.preferences.wearSensors.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),uk.co.james.settings.WearSensorSettings())
    val route=saved.getStateFlow("route","Today")
    val tab=saved.getStateFlow("tab","Today")
    val date=saved.getStateFlow("date",today())
    val locationMapRange=saved.getStateFlow("location-map-range",1)
    /** Only the visible route observes its semantic time window.  The 40 day
     * scoring window remains bounded; Timeline never wakes up the whole history. */
    val screenRequestKey=combine(route,date,locationMapRange) { screen, selected, mapRange -> routeRequestKey(screen,selected,mapRange) }
        .stateIn(viewModelScope,SharingStarted.Eagerly,routeRequestKey("Today",today(),1))
    val screenRecords=combine(route,date,locationMapRange) { screen, selected, mapRange -> Triple(screen,selected,mapRange) }
        .flatMapLatest { (screen,selected,mapRange) ->
            val requestKey=routeRequestKey(screen,selected,mapRange)
            if (screen in setOf("Location","Location map")) {
                val end=java.time.LocalDate.parse(selected).plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
                val days=if(screen=="Location map") mapRange.toLong() else 14L
                return@flatMapLatest routeRecords(requestKey,repo.dao.observePlacesContext(end.minus(java.time.Duration.ofDays(days)).toString(),end.toString()))
            }
            val selectedDay=runCatching { java.time.LocalDate.parse(selected) }.getOrElse { java.time.LocalDate.now() }
            val days=when(screen) { "Timeline" -> 3L; "Insights", "Weekly review" -> 8L; "Me" -> 90L; else -> 40L }
            // Timeline needs a small boundary context around its selected civil
            // date in order to resolve the actual James Day, not a whole history.
            val start=if(screen=="Timeline") selectedDay.minusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant() else selectedDay.minusDays(days-1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
            val end=if(screen=="Timeline") selectedDay.plusDays(2).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant() else java.time.Instant.now().plus(java.time.Duration.ofMinutes(5))
            routeRecords(requestKey,repo.dao.observeRouteWindow(start.toString(),end.toString()))
        }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),RouteRecordsState("",emptyList(),false))
    val dialog=saved.getStateFlow("dialog","")
    val draft=saved.getStateFlow("draft","{}")
    val message=MutableStateFlow("")
    val todayPreparationRetry=MutableStateFlow(0)
    val localCrashReport=MutableStateFlow(app.crashDiagnostics.read())
    val busy=MutableStateFlow(false)
    val placeCalibration=MutableStateFlow<PlaceCalibrationUiState?>(null)
    val plan=MutableStateFlow<ImportPlan?>(null)
    val merge=MutableStateFlow<MergeResult?>(null)
    val health=MutableStateFlow(HealthStatus(false,emptySet(),"Checking availability…"))
    val update=MutableStateFlow<AppUpdate?>(null)
    val updateProgress=MutableStateFlow("")
    val downloaded=MutableStateFlow<File?>(null)
    private val updater=ApkUpdater(app)
    private val wearUpdater=uk.co.james.wear.WearReleaseUpdater(app,app.wear)
    private var wearRelease:uk.co.james.wear.WearRelease?=null
    private var wearApk:File?=null
    val wearStatus=app.wear.status
    val wearStressCheck=app.wear.stressCheck
    val wearTransfer=app.wear.transfer
    private val updateCredentials=UpdateCredentials(app)
    val githubAccess=MutableStateFlow(updateCredentials.configured())
    fun saveGithubAccess(token:String)=action {withContext(Dispatchers.IO){updateCredentials.save(token)};githubAccess.value=true;message.value="GitHub access saved on this device."}
    fun removeGithubAccess()=action {withContext(Dispatchers.IO){updateCredentials.clear()};githubAccess.value=false;update.value=null;downloaded.value=null;message.value="GitHub access removed."}
    init {saved.get<String>("staged-import")?.let {path->action {preview(path)}};refreshPermissions();viewModelScope.launch {stateRecords.filter {it.isNotEmpty()}.debounce(5000).collectLatest {runCatching {app.wear.publish(it)}}};viewModelScope.launch {combine(stateRecords,wellbeingSettings) { rows, settings->rows to settings }.filter {it.second.enabled&&uk.co.james.state.hasWellbeingEvidence(it.first)}.debounce(8000).collectLatest {(rows,settings)->runCatching {repo.persistWellbeing(uk.co.james.state.mentalWellbeing(rows,settings))}}};viewModelScope.launch {stateRecords.filter {it.isNotEmpty()}.debounce(6000).collectLatest {rows->runCatching {repo.persistBodyBattery(bodyBattery(rows,trigger="records_refresh"))}}};viewModelScope.launch {combine(stateRecords,energyTimeSettings){rows,settings->rows to settings}.filter {uk.co.james.state.hasRightNowEvidence(it.first)}.debounce(9000).collectLatest {(rows,settings)->runCatching {repo.persistRightNow(rightNowSummary(rows,settings))}}};viewModelScope.launch {runCatching {app.wear.refreshConnection()}};viewModelScope.launch(Dispatchers.IO) {runCatching {repo.recoverInterruptedCalibrationAnalysis();repo.ensureCalibrationProfiles();repo.bootstrapCalibrationEvidence()}}}
    fun action(job:suspend ()->Unit) {if(!busy.compareAndSet(false,true))return;viewModelScope.launch {try{job()}catch(e:CancellationException){throw e}catch(e:Exception){message.value=e.message?:"The change could not be saved."}finally{busy.value=false}}}
    fun saveCurrentPlace(name:String,category:String)=action {
        placeCalibration.value=PlaceCalibrationUiState("Checking current location…","Using an existing fix immediately only when it is fresh and accurate.",true)
        try {
            val fix=app.location.addCurrentPlace(name,category) {
                placeCalibration.value=PlaceCalibrationUiState("Getting precise location…","Passive tracking is low power; calibration is requesting one fresh high-accuracy fix.",true)
            }
            val detail=fix.diagnostic(java.time.Instant.now())
            placeCalibration.value=PlaceCalibrationUiState("Place saved",detail,false)
            message.value="Saved ${name.trim()} · $detail"
        } catch(e:Exception) {
            placeCalibration.value=PlaceCalibrationUiState("Place not saved",e.message?:"Couldn't obtain a suitable calibration fix.",false)
            throw e
        }
    }
    fun navigate(value: String,main: Boolean=false) {app.crashDiagnostics.setRoute(value);saved["route"]=value;if(main)saved["tab"]=value}
    fun recordUiPhase(phase:String) { app.crashDiagnostics.setUiPhase(phase) }
    /** A contained preparation failure is diagnostic evidence, not an app crash.
     * Keep its technical frame locally so the next beta report names the exact
     * source line without retaining any personal payload. */
    fun recordTodayPreparationFailure(error:Throwable) {
        app.crashDiagnostics.record(error,"today_preparation")
        localCrashReport.value=app.crashDiagnostics.read()
        recordUiPhase("today_preparation_failed:${error.javaClass.simpleName}")
    }
    fun retryTodayPreparation() { todayPreparationRetry.value++ }
    fun clearLocalCrashReport() { app.crashDiagnostics.clear();localCrashReport.value=null }
    fun date(value: String) {if(validDate(value))saved["date"]=value}
    fun locationMapRange(days:Int) { if(days in setOf(1,2,7)) saved["location-map-range"]=days }
    fun close() {saved["dialog"]="";saved["draft"]="{}";saved["editor-original"]="";saved["editor-store"]=""}
    fun open(kind: String,entry: StoredRecord?=null) {
        saved["dialog"]=kind;saved["editor-original"]=entry?.rawJson?:"";saved["editor-store"]=entry?.store?:if(kind=="Template")"eventTemplates" else if(kind=="RutEvent")"loggedEvents" else if(kind=="Note")"dailyNotes" else if(kind=="WeekReflection")"metadata" else "personalRecords"
        val raw=entry?.raw()?:when(kind) {
            "Note" -> fields("date" to p(date.value),"text" to p(""),"updatedAt" to p(now()))
            "WeekReflection" -> fields("key" to p("week-reflection:"+java.time.LocalDate.parse(date.value).let {it.minusDays((it.dayOfWeek.value-1).toLong())}),"value" to fields("text" to p(""),"updatedAt" to p(now())))
            "Template" -> fields("id" to p(id()),"title" to p(""),"emoji" to p("✨"),"points" to p(25),"category" to p("Other"),"type" to p("positive"),"enabled" to p(true),"isDefault" to p(false),"order" to p(records.value.count {it.store=="eventTemplates"}))
            "ContextPeriod" -> personal("ContextPeriod",fields("title" to p("Context"),"visitType" to p("UNKNOWN"),"start" to p(now()),"end" to p(""),"date" to p(date.value),"contextSource" to p("JAMES_CONFIRMED"),"note" to p("")))
            "LifeFactActivity" -> personal("LifeFactActivity",fields("title" to p(""),"activityType" to p("OTHER"),"start" to p(now()),"end" to p(""),"date" to p(date.value),"activitySource" to p("JAMES_CONFIRMED"),"note" to p("")))
            else -> personal(kind,fields("date" to p(date.value),"title" to p(""),"category" to p(if(kind=="TimeBlock")"Coding" else "Personal"),"period" to p("Morning"),"days" to JsonArray((0..6).map {p(it)}),"startDate" to p(today()),"archived" to p(false),"mood" to p(""),"energy" to p(""),"good" to p(""),"bad" to p(""),"important" to p(""),"note" to p(""),"end" to p(now())))
        }
        saved["draft"]=raw.toString()
    }
    /** New context/fact logging deliberately does not write a Rut point event. */
    fun startContext(type:String,placeId:String?=null,placeName:String?=null)=action {
        val visit=runCatching {uk.co.james.state.VisitType.valueOf(type)}.getOrElse {error("Unknown context type.")}
        val stamp=now()
        val day=uk.co.james.time.jamesDayWindow(records.value,java.time.Instant.parse(stamp))
        val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        val raw=personal("ContextPeriod",fields(
            "title" to p("Context: "+visit.name.lowercase().replaceFirstChar {it.uppercase()}),
            "visitType" to p(visit.name),"start" to p(stamp),"end" to p(""),
            "placeId" to (placeId?.let(::p)?:JsonNull),"placeName" to (placeName?.let(::p)?:JsonNull),
            "visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),
            "contextSource" to p("manual"),"jamesDayId" to p(day.id),
            "algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.CONTEXT_LOAD_VERSION),
            "calibrationVersion" to p("1.0.0")
        ),timestamp=stamp)
        repo.save("personalRecords",raw);message.value="Context started."
    }
    fun endContext()=action {
        val active=records.value.filter {it.kind=="ContextPeriod"}.mapNotNull {row->
            val start=row.data().text("start").takeIf(::validTime)?.let {java.time.Instant.parse(it)}?:return@mapNotNull null
            val end=row.data().text("end").takeIf(::validTime)?.let {java.time.Instant.parse(it)}
            row.takeIf {start<=java.time.Instant.now()&&(end==null||end>java.time.Instant.now())}
        }.maxByOrNull {it.timestamp}?:return@action
        val stamp=now()
        repo.save(active.store,active.raw().changed("data" to active.data().changed("end" to p(stamp)),"updatedAt" to p(stamp)),active.rawJson)
        records.value.filter {it.kind=="ContextDifficultInterval"&&it.data().text("contextId")==active.recordId&&it.data().text("end").isBlank()}.forEach {row->
            repo.save(row.store,row.raw().changed("data" to row.data().changed("end" to p(stamp)),"updatedAt" to p(stamp)),row.rawJson)
        }
        message.value="Context ended."
    }
    fun markDifficult()=action {
        val active=uk.co.james.state.currentContext(records.value)
        val context=active.active
        if(context==null) { message.value="Start or set a context first."; return@action }
        if(active.difficultActive)return@action
        val stamp=now()
        repo.save("personalRecords",personal("ContextDifficultInterval",fields("contextId" to p(context.id),"start" to p(stamp),"end" to p(""),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.CONTEXT_LOAD_VERSION)),timestamp=stamp))
        message.value="Difficult active."
    }
    fun endDifficult()=action {
        val row=records.value.filter {it.kind=="ContextDifficultInterval"&&it.data().text("end").isBlank()}.maxByOrNull {it.timestamp}?:return@action
        val stamp=now()
        repo.save(row.store,row.raw().changed("data" to row.data().changed("end" to p(stamp)),"updatedAt" to p(stamp)),row.rawJson)
        message.value="Difficult period ended."
    }
    fun logLifeActivity(activity:String)=action {
        require(activity in listOf("Gym","Gaming","Cinema","Walk","Personal project","Went out","Other"))
        val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        val context=uk.co.james.state.currentContext(records.value).active
        val day=uk.co.james.time.jamesDayWindow(records.value,java.time.Instant.parse(stamp))
        repo.save("personalRecords",personal("LifeFactActivity",fields(
            "title" to p(activity),"activityType" to p(activity.uppercase().replace(" ","_")),
            "start" to p(stamp),"end" to p(""),"date" to p(today()),
            "visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"contextId" to p(context?.id?:""),
            "jamesDayId" to p(day.id),"activitySource" to p("JAMES_CONFIRMED"),
            "algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.LIFE_BALANCE_VERSION)
        ),timestamp=stamp))
        message.value="Activity recorded."
    }
    /** A correction is separate user evidence; the GPS observation and inferred
     * place remain intact.  Unknown is intentionally the default. */
    fun setVisitOwnership(visit:StoredRecord,ownership:String)=action {
        require(ownership in uk.co.james.location.TimeOwnership.entries.map {it.name})
        val stamp=now()
        val changed=visit.raw().changed("data" to visit.data().changed("ownership" to p(ownership),"ownershipSource" to p("JAMES_CORRECTION"),"ownershipCorrectedAt" to p(stamp)),"updatedAt" to p(stamp))
        repo.save(visit.store,changed,visit.rawJson)
        repo.save("personalRecords",personal("ContextCorrection",fields("visitId" to p(visit.recordId),"field" to p("ownership"),"value" to p(ownership),"source" to p("JAMES_CORRECTION")),source="manual",timestamp=stamp))
        message.value="Time ownership updated."
    }
    /** Subjective ownership is its own period record. It can live inside one
     * physical Visit without forcing fake location splits. */
    fun setCurrentOwnership(ownership:String)=action {
        val selected=runCatching {uk.co.james.location.TimeOwnership.valueOf(ownership)}.getOrElse {error("Unknown time ownership.")}
        val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        val open=records.value.filter {it.kind=="OwnershipPeriod"&&it.data().text("end").isBlank()}
        uk.co.james.location.changeOwnership(open.map {it.recordId},selected)
        repo.transitionOwnership(open,ownershipPeriod(selected,stamp,anchor?.recordId.orEmpty(),"James confirmed current time ownership."))
        message.value="${ownershipLabel(selected)} time recorded from now."
    }
    /** Closing is a first-class action.  The next period is explicitly Unknown,
     * never a fabricated obligation and never a silently running Personal timer. */
    fun endCurrentOwnership()=action {
        val open=records.value.filter {it.kind=="OwnershipPeriod"&&it.data().text("end").isBlank()}
        val active=open.maxByOrNull {it.timestamp}?:run {message.value="No active time ownership to end.";return@action}
        val current=runCatching {uk.co.james.location.TimeOwnership.valueOf(active.data().text("ownership","UNKNOWN"))}.getOrDefault(uk.co.james.location.TimeOwnership.UNKNOWN)
        val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        uk.co.james.location.endOwnership(active.recordId,current)
        repo.transitionOwnership(open,ownershipPeriod(uk.co.james.location.TimeOwnership.UNKNOWN,stamp,anchor?.recordId.orEmpty(),"James ended ${ownershipLabel(current)} time; subsequent ownership is unknown."))
        message.value="${ownershipLabel(current)} time ended. Ownership is unknown from now."
    }
    private fun ownershipPeriod(ownership:uk.co.james.location.TimeOwnership,stamp:String,anchorId:String,provenance:String)=personal("OwnershipPeriod",fields(
        "start" to p(stamp),"end" to p(""),"ownership" to p(ownership.name),"ownershipSource" to p("JAMES_CONFIRMED"),
        "visitId" to p(""),"anchorId" to p(anchorId),"context" to p("UNKNOWN"),"provenance" to p(provenance)
    ),source="manual",timestamp=stamp)
    private fun ownershipLabel(ownership:uk.co.james.location.TimeOwnership)=when(ownership) {
        uk.co.james.location.TimeOwnership.AUTONOMOUS->"Personal"
        uk.co.james.location.TimeOwnership.COMMITTED->"Obligation"
        uk.co.james.location.TimeOwnership.CONSTRAINED->"Constrained"
        uk.co.james.location.TimeOwnership.WORK->"Work"
        uk.co.james.location.TimeOwnership.UNKNOWN->"Unknown"
    }
    /** An interruption is preserved separately from ownership. Starting one
     * closes the current personal segment and opens a committed segment so the
     * daily ledger remains one non-overlapping source of truth. */
    fun interruptCurrentTime(reason:String="OTHER")=action {
        require(reason in listOf("SOMEONE_NEEDED_ME","CHORE_ERRAND","WORK","PHONE_CALL","APPOINTMENT","TRAVEL","CHOSE_TO_STOP","TIRED","OTHER","UNKNOWN"))
        val open=records.value.filter {it.kind=="OwnershipPeriod"&&it.data().text("end").isBlank()}.maxByOrNull {it.timestamp}
        if(open?.data()?.text("ownership")!="AUTONOMOUS") { message.value="Set Personal time before recording an interruption."; return@action }
        val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        val interruption=personal("VisitInterruption",fields("visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"interruptedOwnershipPeriodId" to p(open.recordId),"reason" to p(reason),"start" to p(stamp),"end" to p(""),"source" to p("JAMES_CORRECTION"),"provenance" to p("James confirmed interruption.")),source="manual",timestamp=stamp)
        val committed=personal("OwnershipPeriod",fields("start" to p(stamp),"end" to p(""),"ownership" to p("COMMITTED"),"ownershipSource" to p("JAMES_CONFIRMED"),"interruptionId" to p(interruption.text("id")),"visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"provenance" to p("Interruption is active.")),source="manual",timestamp=stamp)
        repo.transitionOwnership(listOf(open),committed,listOf("personalRecords" to interruption))
        message.value="Interruption recorded."
    }
    fun resumeCurrentTime()=action {
        val interruption=records.value.filter {it.kind=="VisitInterruption"&&it.data().text("end").isBlank()}.maxByOrNull {it.timestamp}?:run {message.value="No active interruption.";return@action}
        val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
        val closes=records.value.filter {it.kind=="OwnershipPeriod"&&it.data().text("end").isBlank()}
        val resumed=personal("OwnershipPeriod",fields("start" to p(stamp),"end" to p(""),"ownership" to p("AUTONOMOUS"),"ownershipSource" to p("JAMES_CONFIRMED"),"resumesInterruptionId" to p(interruption.recordId),"visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"provenance" to p("James resumed personal time.")),source="manual",timestamp=stamp)
        val closedInterruption=interruption.raw().changed("data" to interruption.data().changed("end" to p(stamp)),"updatedAt" to p(stamp))
        repo.transitionOwnership(closes,resumed,listOf(interruption.store to closedInterruption))
        message.value="Personal time resumed."
    }
    fun addVisitInterruption(visitId:String,reason:String)=action {
        require(reason in listOf("SOMEONE_NEEDED_ME","CHORE_ERRAND","WORK","PHONE_CALL","APPOINTMENT","TRAVEL","CHOSE_TO_STOP","TIRED","OTHER","UNKNOWN"))
        val visit=repo.dao.get("personalRecords",visitId)?:return@action; val stamp=now(); val d=visit.data()
        repo.save("personalRecords",personal("VisitInterruption",fields("visitId" to p(visit.recordId),"reason" to p(reason),"start" to p(stamp),"end" to JsonNull,"source" to p("JAMES_CORRECTION"),"jamesDayId" to p(d.text("jamesDayId"))),source="manual",timestamp=stamp))
        message.value="Interruption recorded."
    }
    fun mergeVisitWithPrevious(visit:StoredRecord)=action {
        val start=visit.data().text("start").takeIf(::validTime)?.let(java.time.Instant::parse)?:return@action
        val previous=repo.dao.visitsBetween(start.minus(java.time.Duration.ofHours(12)).toString(),start.toString()).filter {it.recordId!=visit.recordId&&it.data().text("placeId")==visit.data().text("placeId")}.maxByOrNull {it.timestamp}?:return@action
        val p=previous.data();val v=visit.data();val end=v.text("end").takeIf(::validTime)?:return@action
        val stamp=now()
        val previousEvidence="visit-evidence:${previous.recordId}:$stamp";val visitEvidence="visit-evidence:${visit.recordId}:$stamp"
        repo.save("personalRecords",personal("VisitEvidenceSnapshot",fields("visitId" to p(previous.recordId),"rawVisit" to previous.raw(),"capturedFor" to p("MERGE"),"capturedAt" to p(stamp)),recordId=previousEvidence,source="correction",timestamp=stamp))
        repo.save("personalRecords",personal("VisitEvidenceSnapshot",fields("visitId" to p(visit.recordId),"rawVisit" to visit.raw(),"capturedFor" to p("MERGE"),"capturedAt" to p(stamp)),recordId=visitEvidence,source="correction",timestamp=stamp))
        val merged=previous.raw().changed("data" to p.changed("end" to p(end),"durationMin" to p(java.time.Duration.between(java.time.Instant.parse(p.text("start")),java.time.Instant.parse(end)).toMinutes()),"mergedVisitIds" to p(visit.recordId),"correctionSource" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp))
        val superseded=visit.raw().changed("data" to v.changed("supersededByVisitId" to p(previous.recordId),"correctionSource" to p("JAMES_CORRECTION"),"supersededAt" to p(stamp)),"updatedAt" to p(stamp))
        repo.save(previous.store,merged,previous.rawJson);repo.save(visit.store,superseded,visit.rawJson)
        repo.save("personalRecords",personal("VisitCorrection",fields("visitId" to p(visit.recordId),"action" to p("MERGED_INTO"),"targetVisitId" to p(previous.recordId),"source" to p("JAMES_CORRECTION"),"previousRawEvidenceId" to p(visitEvidence),"targetRawEvidenceId" to p(previousEvidence),"reversible" to p(true)),source="manual",timestamp=stamp));message.value="Visits merged; original evidence retained."
    }
    fun splitVisit(visit:StoredRecord)=action {
        val d=visit.data();val start=d.text("start").takeIf(::validTime)?.let(java.time.Instant::parse)?:return@action;val end=d.text("end").takeIf(::validTime)?.let(java.time.Instant::parse)?:return@action
        if(java.time.Duration.between(start,end).toMinutes()<10)return@action
        val mid=start.plusSeconds(java.time.Duration.between(start,end).seconds/2);val stamp=now()
        val evidenceId="visit-evidence:${visit.recordId}:$stamp"
        repo.save("personalRecords",personal("VisitEvidenceSnapshot",fields("visitId" to p(visit.recordId),"rawVisit" to visit.raw(),"capturedFor" to p("SPLIT"),"capturedAt" to p(stamp)),recordId=evidenceId,source="correction",timestamp=stamp))
        val first=visit.raw().changed("data" to d.changed("end" to p(mid.toString()),"durationMin" to p(java.time.Duration.between(start,mid).toMinutes()),"correctionSource" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp))
        val second=personal("PlaceVisit",d.changed("start" to p(mid.toString()),"end" to p(end.toString()),"durationMin" to p(java.time.Duration.between(mid,end).toMinutes()),"splitFromVisitId" to p(visit.recordId),"correctionSource" to p("JAMES_CORRECTION")),source="manual",timestamp=mid.toString())
        repo.save(visit.store,first,visit.rawJson);repo.save("personalRecords",second);repo.save("personalRecords",personal("VisitCorrection",fields("visitId" to p(visit.recordId),"action" to p("SPLIT"),"targetVisitId" to p(second.text("id")),"source" to p("JAMES_CORRECTION"),"rawEvidenceId" to p(evidenceId),"reversible" to p(true)),source="manual",timestamp=stamp));message.value="Visit split at its midpoint; original evidence retained."
    }
    /** Reversal restores the snapshots rather than deleting any observed or
     * derived row. The correction stays as an auditable, explicitly reverted
     * decision, so a later import or review can still explain the history. */
    fun revertLatestVisitCorrection(visit:StoredRecord)=action {
        val correction=records.value.filter { row->
            row.kind=="VisitCorrection" && row.data().text("revertedAt").isBlank() &&
                (row.data().text("visitId")==visit.recordId||row.data().text("targetVisitId")==visit.recordId)
        }.maxByOrNull {it.timestamp}?:run {message.value="No reversible visit correction found.";return@action}
        val data=correction.data(); val stamp=now()
        suspend fun restore(snapshotId:String) {
            val snapshot=repo.dao.get("personalRecords",snapshotId)?:return
            val original=snapshot.data().obj("rawVisit")
            if(original.isEmpty())return
            val current=repo.dao.get("personalRecords",original.text("id"))
            repo.save("personalRecords",original,current?.rawJson)
        }
        when(data.text("action")) {
            "MERGED_INTO" -> {
                restore(data.text("previousRawEvidenceId"))
                restore(data.text("targetRawEvidenceId"))
            }
            "SPLIT" -> {
                restore(data.text("rawEvidenceId"))
                val created=repo.dao.get("personalRecords",data.text("targetVisitId"))
                if(created!=null) repo.save(created.store,created.raw().changed("data" to created.data().changed("supersededByVisitId" to p(data.text("visitId")),"supersededAt" to p(stamp),"correctionSource" to p("REVERSAL")),"updatedAt" to p(stamp)),created.rawJson)
            }
            else -> {message.value="That correction cannot be reversed automatically.";return@action}
        }
        repo.save(correction.store,correction.raw().changed("data" to data.changed("revertedAt" to p(stamp),"revertedBy" to p("JAMES_CORRECTION")),"updatedAt" to p(stamp)),correction.rawJson)
        message.value="Visit correction reverted; all evidence remains preserved."
    }
    fun logDifficultInteraction(type:String,person:String?=null)=action {
        val subtype=runCatching {uk.co.james.state.DifficultInteractionType.valueOf(type)}.getOrElse {error("Unknown interaction type.")}
        val context=uk.co.james.state.currentContext(records.value).active
        val stamp=now()
        repo.save("personalRecords",personal("DifficultInteraction",fields("title" to p(subtype.name.lowercase().replace('_',' ').replaceFirstChar {it.uppercase()}),"subtype" to p(subtype.name),"person" to (person?.takeIf {it.isNotBlank()}?.let(::p)?:JsonNull),"contextId" to (context?.id?.let(::p)?:JsonNull),"date" to p(today()),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.CONTEXT_LOAD_VERSION)),timestamp=stamp))
        message.value="Difficult interaction recorded."
    }
    // Kept for compatibility with historical UI callers; future records are facts, not point pulls.
    fun logJamesShout(source:String)=logDifficultInteraction("RAISED_VOICE",source)
    fun logJamesShoutPhrase(phrase:String)=logDifficultInteraction(when(phrase) {"Fuck off"->"INSULT_HOSTILITY";"Cunt"->"INSULT_HOSTILITY";else->"CONTROLLING"},null)
    fun wellbeingSettings(v:uk.co.james.settings.WellbeingSettings)=action {app.preferences.wellbeing(v);message.value="Mental wellbeing settings saved."}
    fun energyTimeSettings(v:uk.co.james.settings.EnergyTimeSettings)=action {app.preferences.energyTime(v);message.value="Energy & Time settings saved."}
    fun compactToday(value:Boolean)=action {app.preferences.compactToday(value);message.value=if(value)"Compact Today selected." else "Classic Today selected."}
    fun bodyBatterySettings(v:uk.co.james.settings.BodyBatterySettings)=action {
        app.preferences.bodyBattery(v);repo.setting("body-battery-recovery-aware-strain",p(v.recoveryAwareStrain))
        message.value="Body Battery calibration setting saved."
    }
    fun resetWellbeingBaseline()=action {repo.resetWellbeingBaseline();message.value="Personal wellbeing baseline reset. Your health and timeline data remain untouched."}
    fun wellbeingCheckIn(mood:String,energy:String,anxiety:String)=action {
        require(wellbeingSettings.value.enabled&&wellbeingSettings.value.checkIns){"Mood check-ins are turned off in Mental wellbeing settings."}
        require(mood in listOf("VERY LOW","LOW","OKAY","GOOD","GREAT"))
        val before=rightNowSummary(records.value,energyTimeSettings.value)
        val raw=personal("WellbeingCheckIn",fields("mood" to p(mood),"energy" to p(energy),"anxiety" to p(anxiety),"date" to p(today()),"algorithmVersion" to p(uk.co.james.state.WELLBEING_ALGORITHM_VERSION),"liveEnergyBefore" to p(before.liveEnergy.score),"bodyBattery" to (before.bodyBattery?.let(::p)?:JsonNull),"mentalReserve" to p(before.mentalReserve),"sleepMinutes" to (records.value.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Sleep"}.maxByOrNull {it.timestamp}?.data()?.number("value")?.let(::p)?:JsonNull),"recovery" to (records.value.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="Recovery"}.maxByOrNull {it.timestamp}?.data()?.number("value")?.let(::p)?:JsonNull),"awakeMinutes" to p(before.awakeMinutes),"context" to p(before.nextConstraint?.title?:"")))
        repo.save("personalRecords",raw);message.value="Wellbeing check-in saved."
    }
    fun timePressureCheckIn(value:String)=action {
        require(value in listOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY"))
        val before=rightNowSummary(records.value,energyTimeSettings.value)
        val raw=personal("TimePressureCheckIn",fields("title" to p("Time pressure check-in"),"pressure" to p(value),"date" to p(today()),"predictionBefore" to p(before.timePressure.score),"nextConstraint" to p(before.nextConstraint?.title?:""),"usableMinutes" to (before.nextConstraint?.usableMinutes?.let(::p)?:JsonNull),"personalMinutes" to p(before.personalMinutes),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.TIME_PRESSURE_VERSION),"calibrationVersion" to p("1.0.0")))
        repo.save("personalRecords",raw);message.value="Time pressure check-in saved."
    }
    fun energyCheckIn(value:String)=action {
        require(value in listOf("VERY LOW","LOW","OKAY","HIGH","VERY HIGH"))
        val before=rightNowSummary(records.value,energyTimeSettings.value)
        val nutrition=before.nutrition
        val raw=personal("WellbeingCheckIn",fields("mood" to p(""),"energy" to p(value),"anxiety" to p(""),"date" to p(today()),"liveEnergyBefore" to p(before.liveEnergy.score),"bodyBattery" to (before.bodyBattery?.let(::p)?:JsonNull),"mentalReserve" to p(before.mentalReserve),"awakeMinutes" to p(before.awakeMinutes),"recentContext" to p(before.nextConstraint?.title?:""),"lastMealAt" to (nutrition.latestMealAt?.let(::p)?:JsonNull),"minutesSinceMeal" to (nutrition.minutesSinceMeal?.let(::p)?:JsonNull),"recentMealKcal" to (nutrition.recentEnergyKcal?.let(::p)?:JsonNull),"hydrationTodayMl" to (nutrition.hydrationMl?.let(::p)?:JsonNull),"lastHydrationAt" to (nutrition.latestHydrationAt?.let(::p)?:JsonNull),"caffeineTodayMg" to (nutrition.caffeineMg?.let(::p)?:JsonNull),"lastCaffeineAt" to (nutrition.latestCaffeineAt?.let(::p)?:JsonNull),"nutritionSource" to p(nutrition.source?:""),"jamesDayId" to p(before.jamesDay.id),"algorithmVersion" to p(uk.co.james.state.JamesAlgorithmRegistry.LIVE_ENERGY_VERSION),"calibrationVersion" to p("1.0.0")))
        repo.save("personalRecords",raw);message.value="Energy check-in saved."
    }

    data class CalibrationTarget(val score:Int,val confidence:String,val jamesDayId:String?,val algorithmVersion:String,val calibrationVersion:String,val calibrationSetId:String="")
    fun calibrationTarget(algorithmId:String):CalibrationTarget? {
        val rows=records.value;val entry=JamesAlgorithmRegistry.get(algorithmId)?:return null
        val right=if(algorithmId in setOf("live_energy","sleepiness","energy_sustainability","crash_risk","time_pressure"))rightNowSummary(rows,energyTimeSettings.value)else null
        val target=when(algorithmId) {
            "body_battery"->bodyBattery(rows).let {b->b.value?.let {CalibrationTarget(it,b.confidence,b.trace?.jamesDayId,b.algorithmVersion,b.calibrationVersion,b.calibrationSetId.orEmpty())}}
            "live_energy"->right?.liveEnergy?.let {CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "sleepiness"->right?.sleepiness?.let {CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "energy_sustainability"->right?.sustainability?.let {CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "crash_risk"->right?.crashRisk?.let {CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "time_pressure"->right?.timePressure?.let {CalibrationTarget(it.score,it.confidence,right.jamesDay.id,it.algorithmVersion,it.calibrationVersion,it.calibrationSetId)}
            "mental_reserve"->wellbeing.value.reserve.let {CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.value.calibrationVersions["mental_reserve"]?:entry.calibrationVersion,wellbeing.value.calibrationSetIds["mental_reserve"].orEmpty())}
            "anxiety_load"->wellbeing.value.anxiety.let {CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.value.calibrationVersions["anxiety_load"]?:entry.calibrationVersion,wellbeing.value.calibrationSetIds["anxiety_load"].orEmpty())}
            "low_mood_load"->wellbeing.value.lowMood.let {CalibrationTarget(it.score,it.confidence,null,entry.algorithmVersion,wellbeing.value.calibrationVersions["low_mood_load"]?:entry.calibrationVersion,wellbeing.value.calibrationSetIds["low_mood_load"].orEmpty())}
            "context_load"->uk.co.james.state.currentContext(rows).let {val active=JamesCalibrationEngine.activeCalibration(rows,"context_load",entry.calibrationVersion,entry.algorithmVersion);CalibrationTarget(it.score,it.confidence,it.active?.jamesDayId,entry.algorithmVersion,active.version,active.setId)}
            "life_balance"->uk.co.james.state.lifeBalance(rows).current.score?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"life_balance",entry.calibrationVersion,entry.algorithmVersion);CalibrationTarget(it,"ROLLING",null,entry.algorithmVersion,active.version,active.setId)}
            "life_balance_v2"->uk.co.james.state.lifeBalanceV2(rows).current.score?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"life_balance_v2",entry.calibrationVersion,entry.algorithmVersion);CalibrationTarget(it,"OWNERSHIP COVERAGE",null,entry.algorithmVersion,active.version,active.setId)}
            "james_stress"->rows.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="James Stress"}.maxByOrNull {it.timestamp}?.let {val active=JamesCalibrationEngine.activeCalibration(rows,"james_stress",entry.calibrationVersion,entry.algorithmVersion);CalibrationTarget(JamesCalibrationEngine.applyActiveScore(it.data().number("value").toInt().coerceIn(0,100),rows,"james_stress"),"SOURCE",it.data().text("jamesDayId").ifBlank {null},entry.algorithmVersion,active.version,active.setId)}
            else->null
        }?:return null
        return target
    }
    fun calibrationOptions(algorithmId:String)=when(algorithmId) {
        "body_battery"->listOf("MUCH TOO HIGH","A LITTLE TOO HIGH","ABOUT RIGHT","A LITTLE TOO LOW","MUCH TOO LOW")
        "mental_reserve"->listOf("BRAIN GONE","MENTALLY TIRED","ABOUT RIGHT","MORE CAPACITY THAN THIS")
        "live_energy"->listOf("NO ENERGY","LOW","ABOUT RIGHT","BUZZING")
        "sleepiness"->listOf("PREDICTION TOO HIGH","ABOUT RIGHT","PREDICTION TOO LOW")
        "anxiety_load","james_stress"->listOf("NONE","LOW","MODERATE","HIGH")
        "time_pressure"->listOf("NOT AT ALL","A LITTLE","SOMEWHAT","A LOT","EXTREMELY")
        "context_load"->listOf("NOT AT ALL","A LITTLE","MODERATE","A LOT","EXTREME")
        "life_balance"->listOf("DEFINITELY NOT","MOSTLY NOT","MIXED","MOSTLY YES","DEFINITELY YES")
        "life_balance_v2"->listOf("HARDLY ANY OF MY TIME FELT LIKE MINE","A LITTLE OF MY TIME FELT LIKE MINE","MIXED","MOST OF MY TIME FELT LIKE MINE","MY TIME LARGELY FELT LIKE MINE")
        "low_mood_load"->listOf("NOT LOW OR FLAT","A LITTLE LOW OR FLAT","NOTICEABLY LOW OR FLAT","VERY LOW OR FLAT","EXTREMELY LOW OR FLAT")
        else->listOf("TOO HIGH","ABOUT RIGHT","TOO LOW")
    }
    fun calibrate(algorithmId:String,target:CalibrationTarget,submissionId:String,feedback:String,note:String="",capacity:String?=null,sleepiness:String?=null)=action {
        if(algorithmId in setOf("low_mood_load","life_balance","life_balance_v2")) {
            val last=records.value.filter {it.kind=="CalibrationEvent"&&it.data().text("algorithmId")==algorithmId&&!it.data().flag("ignored")}.maxByOrNull {it.timestamp}
            require(last?.timestamp?.let {runCatching {java.time.Duration.between(java.time.Instant.parse(it),java.time.Instant.now()).toDays()>=6}.getOrDefault(true)}!=false){"Longitudinal calibration is weekly; a recent observation already covers this period."}
        }
        val structured=when(algorithmId) {"sleepiness"->sleepiness?.takeIf {it.isNotBlank()}?:feedback;else->capacity?.takeIf {it.isNotBlank()}?:feedback}
        var snapshot=withContext(Dispatchers.Default){calibrationSnapshot(records.value,target.score,target.confidence,target.jamesDayId)}
        snapshot=snapshot.changed("comparisonFeedback" to p(feedback),"primaryTarget" to p(JamesCalibrationCatalog.get(algorithmId)?.feedbackDimension?:""),"capacity" to (capacity?.let(::p)?:JsonNull),"sleepiness" to (sleepiness?.let(::p)?:JsonNull),"evidenceConfidence" to p("DIRECT_HIGH"))
        if(algorithmId in setOf("low_mood_load","life_balance","life_balance_v2"))snapshot=snapshot.changed("feedbackWindowStart" to p(java.time.Instant.now().minus(java.time.Duration.ofDays(if(algorithmId in setOf("life_balance","life_balance_v2"))14 else 7)).toString()),"feedbackWindowEnd" to p(now()),"temporalAlignment" to p("LONGITUDINAL_RETROSPECTIVE"))
        if(algorithmId=="low_mood_load") {
            val current=wellbeing.value.lowMood
            val evidence=uk.co.james.state.lowMoodEvidence(records.value,current)
            snapshot=snapshot.changed(
                "lowMoodMoodConfidence" to p(evidence.moodConfidence),
                "lowMoodDirectEvidence" to p(evidence.directEvidence),
                "lowMoodInputCoverage" to p(evidence.inputCoverage),
                "lowMoodContributors" to kotlinx.serialization.json.JsonArray(current.contributors.map {item->fields("source" to p(item.source),"contribution" to p(item.contribution),"direction" to p(item.direction),"included" to p(item.included))})
            )
        }
        val raw=JamesCalibrationEngine.event(algorithmId,target.score.toDouble(),structured,target.algorithmVersion,target.calibrationVersion,target.jamesDayId,snapshot,note=note,calibrationSetId=target.calibrationSetId,recordId="calibration-event:"+submissionId)
        repo.saveCalibrationEvent(raw)
        val count=records.value.count {it.kind=="CalibrationEvent"&&it.data().text("algorithmId")==algorithmId}+1
        message.value="Calibration saved. Observation #$count — James OS will compare it with similar observations."
    }
    fun analyseCalibration(algorithmId:String)=action {
        repo.recordCalibrationAnalysis(algorithmId,"ANALYSING")
        try {
            val entry=JamesAlgorithmRegistry.get(algorithmId)?:error("Algorithm unavailable.")
            val events=withContext(Dispatchers.Default){records.value.mapNotNull(JamesCalibrationEngine::parse).filter {it.algorithmId==algorithmId}}
            val active=JamesCalibrationEngine.activeCalibration(records.value,algorithmId,entry.calibrationVersion,entry.algorithmVersion)
            val dependencies=JamesCalibrationEngine.dependencyVersions(records.value,algorithmId)
            val candidate=withContext(Dispatchers.Default){JamesCalibrationEngine.candidate(events,algorithmId,active.version,active.parameters,baseCalibrationSetId=active.setId,dependencyVersions=dependencies)}
            if(candidate==null) {repo.recordCalibrationAnalysis(algorithmId,"COMPLETE","More varied evidence is required.");message.value="More varied calibration evidence is needed before a safe candidate can be generated.";return@action}
            val result=withContext(Dispatchers.Default){JamesCalibrationEngine.backTest(events,candidate)}
            val tested=if(result.robustValidation&&result.regressions.isEmpty()&&(result.improvementPercent?:0.0)>0)CandidateStatus.TESTED else CandidateStatus.DRAFT
            repo.saveAnalysedCandidate(candidate,result,tested);repo.recordCalibrationAnalysis(algorithmId,"COMPLETE",if(tested==CandidateStatus.TESTED)"Tested candidate available." else "Validation limited or regression detected.")
            message.value=if(tested==CandidateStatus.TESTED)"Tested candidate available for review."else"Analysis complete. Candidate remains draft because validation is limited or a regression was found."
        } catch(e:CancellationException) {repo.recordCalibrationAnalysis(algorithmId,"FAILED","Analysis cancelled safely.");throw e
        } catch(e:Exception) {repo.recordCalibrationAnalysis(algorithmId,"FAILED",e.message?:"Analysis failed safely.");throw e}
    }
    fun activateCalibration(candidateId:String)=action {
        val newVersion=repo.activateCalibrationCandidate(candidateId);message.value="Calibration $newVersion activated. Future scores use it; history is unchanged."
    }
    fun rollbackCalibration(algorithmId:String)=action {
        repo.rollbackCalibration(algorithmId);message.value="Previous calibration restored. Observations and history were preserved."
    }

    fun editCalibration(recordId:String,feedback:String,note:String)=action {
        val row=records.value.firstOrNull {it.kind=="CalibrationEvent"&&it.recordId==recordId}?:error("Observation unavailable.")
        val d=row.data();val prediction=d.number("prediction")
        val observed=JamesCalibrationEngine.normalizeObserved(d.text("algorithmId"),feedback,prediction)?:error("Unsupported calibration response.")
        val error=prediction-observed
        val direction=when {error>2->ErrorDirection.OVERESTIMATED;error < -2->ErrorDirection.UNDERESTIMATED;else->ErrorDirection.MATCHED}
        val changed=d.changed("feedback" to p(feedback),"observed" to p(observed),"error" to p(error),"absoluteError" to p(kotlin.math.abs(error)),"direction" to p(direction.name),"note" to p(note.take(240)),"editedAt" to p(now()))
        repo.mutateCalibrationEvidence(row.raw().changed("data" to changed,"updatedAt" to p(now())),algorithmId=d.text("algorithmId"));message.value="Calibration feedback updated. Existing candidates were invalidated; source data was not changed."
    }
    fun restoreDefaultCalibration(algorithmId:String)=action {
        repo.restoreDefaultCalibration(algorithmId);message.value="Default calibration restored. Feedback and history remain available."
    }

    fun ignoreCalibration(recordId:String,ignored:Boolean)=action {
        val row=records.value.firstOrNull {it.kind=="CalibrationEvent"&&it.recordId==recordId}?:return@action
        repo.mutateCalibrationEvidence(row.raw().changed("data" to row.data().changed("ignored" to p(ignored)),"updatedAt" to p(now())),algorithmId=row.data().text("algorithmId"));message.value=if(ignored)"Observation excluded and existing candidates invalidated."else"Observation included again; run analysis for a fresh candidate."
    }
    fun deleteCalibration(recordId:String)=action {val row=records.value.firstOrNull {it.kind=="CalibrationEvent"&&it.recordId==recordId}?:return@action;repo.mutateCalibrationEvidence(null,recordId,row.data().text("algorithmId"));message.value="Calibration observation deleted and candidates invalidated. Underlying source data was not changed."}

    fun openStateCheckIn() {
        this.open("MoodEntry")
        val raw=json.parseToJsonElement(draft.value).jsonObject
        val snapshot=uk.co.james.state.stateSummary(records.value).snapshot()
        saved["draft"]=raw.changed("metadata" to fields("stateAtCheckIn" to snapshot)).toString()
    }
    fun change(key: String,value: JsonElement,top: Boolean=false) {val raw=json.parseToJsonElement(draft.value).jsonObject;if(dialog.value=="WeekReflection"){saved["draft"]=raw.changed("value" to raw.obj("value").changed(key to value,"updatedAt" to p(now()))).toString();return};saved["draft"]=if(top)raw.changed(key to value).toString() else raw.changed("data" to raw.obj("data").changed(key to value)).toString()}
    fun saveDraft() = action {
        var raw=json.parseToJsonElement(draft.value).jsonObject
        if(dialog.value in listOf("Template","RutEvent")) {
            raw=raw.changed("points" to p(raw.text("points").toLongOrNull()?:error("Enter whole signed points.")))
            if(dialog.value=="Template" && raw.text("recoveryTitle").isNotBlank()) raw=raw.changed("recoveryPoints" to p(raw.text("recoveryPoints").toLongOrNull()?:error("Enter recovery points.")))
        }
        if(dialog.value !in listOf("Template"))raw=raw.changed("updatedAt" to p(now()))
        if(dialog.value=="Routine")require(raw.obj("data").text("title").isNotBlank()){"Give the routine a name."}
        if(dialog.value=="MoodEntry")require(uk.co.james.state.stateChoices.any {(key,choices)->raw.obj("data").text(key) in choices}) {"Choose at least one signal. Leave the others blank."}
        if(dialog.value in listOf("Event","LifeFactActivity"))require(raw.obj("data").text("title").isNotBlank()) {"Describe the moment."}
        // New semantic records share the same current evidence anchor and James
        // Day.  They stay distinct dimensions, but later Timeline/detail views
        // can join them without guessing from unrelated history.
        if(dialog.value in listOf("ContextPeriod","LifeFactActivity")) {
            val stamp=now(); val anchor=records.value.firstOrNull {it.kind=="LocationAnchor"}
            val context=uk.co.james.state.currentContext(records.value).active
            val day=uk.co.james.time.jamesDayWindow(records.value,java.time.Instant.parse(stamp))
            val extras=if(dialog.value=="LifeFactActivity") fields(
                "visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"contextId" to p(context?.id?:""),
                "jamesDayId" to p(day.id),"activitySource" to p("JAMES_CONFIRMED")
            ) else fields("visitId" to p(""),"anchorId" to p(anchor?.recordId?:""),"jamesDayId" to p(day.id),"contextSource" to p("JAMES_CONFIRMED"))
            raw=raw.changed("data" to raw.obj("data").changed(*extras.entries.map {it.key to it.value}.toTypedArray()))
        }
        val store=saved.get<String>("editor-store")?:"personalRecords"
        val original=saved.get<String>("editor-original")?.ifBlank {null}?.let {json.parseToJsonElement(it).jsonObject}
        repo.save(store,raw,original?.toString())
        // Editing a Visit changes a user-facing interpretation, never the GPS
        // observation. Preserve a small correction trail for place/context,
        // activity and ownership so import/export and later learning can tell
        // manual evidence from the detector's original inference.
        if(dialog.value=="PlaceVisit"&&original!=null) {
            val before=original.obj("data");val after=raw.obj("data");val stamp=now()
            listOf("title","placeId","context","activity","ownership").filter {before.text(it)!=after.text(it)}.forEach {field->
                repo.save("personalRecords",personal("ContextCorrection",fields("visitId" to p(raw.text("id")),"field" to p(field),"previousValue" to p(before.text(field)),"value" to p(after.text(field)),"source" to p("JAMES_CORRECTION"),"provenance" to p("Visit detail edit.")),source="manual",timestamp=stamp))
            }
        }
        close();message.value="Saved."
    }
    fun chooseImport(uri: Uri)=action {val path=repo.stage(uri);saved["staged-import"]=path;preview(path);navigate("Import Centre")}
    private suspend fun preview(path: String) {val (p,fingerprint)=repo.loadPlan(path);plan.value=p;merge.value=BackupCodec.merge(p,repo.dao.all());saved["import-fingerprint"]=fingerprint}
    fun refreshPreview()=action {saved.get<String>("staged-import")?.let {preview(it)}}
    fun cancelImport(){saved.get<String>("staged-import")?.let(repo::releaseStaged);plan.value=null;merge.value=null;saved.remove<String>("staged-import");saved.remove<String>("import-fingerprint")}
    fun import()=action {val p=plan.value?:error("Choose a backup first.");val staged=saved.get<String>("staged-import");val result=repo.import(p,saved["import-fingerprint"]?:"");
        repo.dao.get("settings","theme")?.raw()?.text("value")?.takeIf {it in listOf("light","dark","system")}?.let {app.preferences.theme(it)}
        if(staged!=null)repo.discardStaged(staged);cancelImport();message.value="Imported ${result.additions.size} records. ${result.duplicates} duplicates ignored; ${result.conflicts.size} conflicts kept unchanged."
    }
    fun export(uri: Uri)=action {val archive=saved.get<String>("export-archive");if(archive.isNullOrBlank())repo.exportTo(uri) else repo.exportArchive(uri,archive);saved.remove<String>("export-archive");message.value="Backup exported."}
    fun prepareExport(archiveId: String?=null){saved["export-archive"]=archiveId?:""}
    fun start(points: Long)=action {repo.start(points);repo.seedDefaults()}
    fun log(template: StoredRecord,pull: StoredRecord?=null)=action {repo.log(template.raw(),date.value,linked=pull?.raw())}
    fun markDay(status: String)=action {repo.markDay(date.value,status);message.value="Day marked $status."}
    fun setAside(pull: StoredRecord,closed: Boolean)=action {val key="closed-pull:${pull.recordId}";val old=repo.dao.get("metadata",key);if(closed)repo.save("metadata",fields("key" to p(key),"value" to fields("closedAt" to p(now()))),old?.rawJson) else repo.dao.delete("metadata",key)}
    fun pin(template: StoredRecord)=action {val old=repo.dao.get("settings","favourites")?.raw()?.array("value")?.mapNotNull {(it as? JsonPrimitive)?.content}?.toMutableList()?: mutableListOf();if(template.recordId in old)old.remove(template.recordId)else {require(old.size<12){"You can pin up to 12 events."};old.add(template.recordId)};repo.setting("favourites",JsonArray(old.map {p(it)}))}
    fun theme(value: String)=action {app.preferences.theme(value);repo.setting("theme",p(value))}
    fun refreshPermissions(){viewModelScope.launch {health.value=runCatching {app.health.status()}.getOrElse {HealthStatus(false,emptySet(),it.message?:"Unavailable")}}}
    private suspend fun refreshCalendarSchedule() {
        val selected=repo.stateInputs().filter {it.kind=="CalendarSelection"&&it.data().flag("enabled")}.map {it.data().text("calendarId")}.toSet()
        app.calendar.refresh(selected)
    }
    fun onAppResumed(){refreshPermissions();whoopSyncOnForeground();action { refreshCalendarSchedule() }}
    fun calendarSelection(calendarId:String,name:String,enabled:Boolean)=action {
        repo.save("personalRecords",personal("CalendarSelection",fields("calendarId" to p(calendarId),"calendarName" to p(name.take(80)),"enabled" to p(enabled),"updatedAt" to p(now())),recordId="calendar-selection:$calendarId",source="manual"))
        refreshCalendarSchedule()
    }
    fun refreshCalendar()=action { refreshCalendarSchedule();message.value="Calendar refreshed." }
    fun classifyCalendarCommitment(commitmentId:String,ownership:String,applyToSimilar:Boolean)=action {
        app.calendar.classify(commitmentId,ownership,applyToSimilar)
        refreshCalendarSchedule()
    }
    fun dismissCalendarClassification(commitmentId:String)=action {
        app.calendar.dismissPrompt(commitmentId,java.time.Instant.now().plus(java.time.Duration.ofDays(14)))
        message.value="Left unknown for now. James OS will not ask again for two weeks."
    }
    fun deleteCalendarRule(ruleId:String)=action { repo.dao.delete("personalRecords",ruleId);refreshCalendarSchedule() }
    private fun whoopSyncOnForeground() {
        if(!app.whoop.configured())return
        val source=records.value.firstOrNull {it.recordId=="source:whoop"}?.data()
        val lastValue=source?.text("lastSuccess").takeIf { !it.isNullOrBlank() }?:source?.text("lastSync").orEmpty()
        val last=lastValue.takeIf {it.isNotBlank()}?.let {runCatching {java.time.Instant.parse(it)}.getOrNull()}
        val ownershipMigrationNeeded=records.value.any {row->
            row.kind=="HealthMetric"&&row.source=="whoop"&&row.data().text("metric")=="Strain"&&
                row.data().text("whoopCycleId").isBlank()&&row.data().text("recordStart").isBlank()
        }
        if(ownershipMigrationNeeded||last==null||java.time.Duration.between(last,java.time.Instant.now())>=java.time.Duration.ofMinutes(30))viewModelScope.launch {runCatching {app.whoop.sync()}}
    }
    val whoopConfigured=MutableStateFlow(app.whoop.configured())
    fun whoopConnect()=action {
        val url=app.whoop.connectUrl();whoopConfigured.value=true
        app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun whoopSync()=action {val result=app.whoop.sync();message.value=result.summary;uk.co.james.sync.BackgroundJobs.scheduleWhoop(app)}
    fun whoopSyncQuietly() {
        if(!app.whoop.configured())return
        val now=System.currentTimeMillis()
        val sleepPriority=runCatching {bodyBattery(records.value).sleepProcessing}.getOrDefault(false)
        val minimum=if(sleepPriority)10*60*1000L else 30*60*1000L
        if(now-lastQuietWhoopSync<minimum)return
        lastQuietWhoopSync=now
        viewModelScope.launch {try {app.whoop.sync()}catch(_:Exception){}}
    }
    fun whoopDisconnect()=action {app.whoop.disconnect();whoopConfigured.value=false;uk.co.james.sync.BackgroundJobs.stopWhoop(app);message.value="WHOOP disconnected. Imported history is kept."}
    fun requestWearStressCheck()=action {
        if(app.wear.stressCheck.value.active) {
            message.value="Sensor check already in progress."
            return@action
        }
        val previousStress=records.value.filter {it.kind=="HealthMetric"&&it.data().text("metric")=="James Stress"}
            .maxByOrNull {it.updatedAt.ifBlank {it.timestamp}}?.data()?.number("value")
        val started=app.wear.requestStressCheck(previousStress,wellbeing.value.anxiety.score)
        message.value=if(started) "Request sent to your watch. Keep it snug and still for about 45 seconds." else "Your watch is not connected. Reconnect it and try again."
    }
    fun wearSensors(v:uk.co.james.settings.WearSensorSettings)=action {app.preferences.wearSensors(v);if(!app.wear.sendSensorSettings(v))message.value="Watch settings saved here; connect your watch to apply them."}
    fun reconcileShiftTracker()=action {message.value=if(app.work.requestReconciliation())"Shift Tracker reconciliation requested." else "Shift Tracker sender is waiting for Part 2."}
    fun healthSync()=action {app.health.sync();val nutrition=app.nutrition.sync();refreshPermissions();message.value="Health data synced. Nutrition: ${nutrition.nutritionReturned} food records returned; ${nutrition.recordsPersisted} records saved."}
    fun healthSyncQuietly() {
        val now=System.currentTimeMillis()
        if(now-lastQuietHealthSync<4*60*1000L)return
        lastQuietHealthSync=now
        viewModelScope.launch {try {app.health.sync();app.nutrition.sync();refreshPermissions()}catch(_:Exception){}}
    }
    fun healthDisconnect()=action {app.health.disconnect();refreshPermissions();message.value="Health access revoked. Imported history remains on this device."}
    fun updateCheck()=action {
        update.value=null;downloaded.value=null
        updateProgress.value="Checking…"
        try {update.value=updater.check();updateProgress.value=if(update.value==null)"James OS is up to date." else "Update available: ${update.value!!.name}"} catch(e:CancellationException){updateProgress.value="Check cancelled.";throw e} catch(e:Exception){updateProgress.value=e.message?:"Could not check for updates. Try again."}
    }
    fun downloadUpdate()=action {
        downloaded.value=null
        try {downloaded.value=updater.download(update.value?:error("Check for updates first.")){updateProgress.value="Downloading $it%"};updateProgress.value="Verified. Ready to install."} catch(e:CancellationException){updateProgress.value="Download cancelled.";throw e} catch(e:Exception){updateProgress.value=e.message?:"Download failed. Try again."}
    }
    fun installIntent()=updater.installIntent(downloaded.value?:error("Download the update first."))
    fun wearSync()=action {app.wear.publish(records.value);message.value="Watch snapshot sent."}
    fun wearCheckUpdate()=action {wearRelease=wearUpdater.check();if(wearRelease==null)message.value="Watch is up to date." else app.wear.transfer.value="Watch update available: ${wearRelease!!.version}"}
    fun wearDownloadAndSend()=action {val release=wearRelease?:wearUpdater.check()?:error("Watch is up to date.");wearRelease=release;val cached=wearApk?.takeIf {it.isFile};wearApk=cached?:wearUpdater.download(release){app.wear.transfer.value="Downloading $it%"};app.wear.transfer.value="Verified. Preparing watch";wearUpdater.send(release,wearApk!!){app.wear.transfer.value="Sending $it%"}}
    fun wearOpenReadyUpdate()=action {val node=app.wear.refreshConnection().nodeId.ifBlank {error("No connected watch.")};com.google.android.gms.wearable.Wearable.getMessageClient(app).sendMessage(node,"/james/v1/update/open-ready",ByteArray(0)).await();message.value="Opening the saved watch update."}
    fun wearOpenSettings()=action {val node=app.wear.refreshConnection().nodeId.ifBlank {error("No connected watch.")};com.google.android.gms.wearable.Wearable.getMessageClient(app).sendMessage(node,"/james/v1/open/settings",ByteArray(0)).await();message.value="Opening James OS on the watch."}
}
