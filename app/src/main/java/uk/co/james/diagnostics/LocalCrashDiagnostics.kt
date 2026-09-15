package uk.co.james.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import java.time.Instant

/**
 * Local-only beta diagnostics.  It deliberately stores no record payloads,
 * health values, locations, notes, credentials or provider responses.
 */
data class LocalCrashReport(
    val occurredAt:String,
    val stage:String,
    val exceptionType:String,
    val message:String,
    val jamesFrames:List<String>,
    val route:String,
    val previousRoute:String,
    val uiPhase:String,
    val appVersion:String,
    val databaseSchemaVersion:Int
)

/**
 * The same privacy-safe technical summary is used for uncaught crashes and
 * contained component failures. It intentionally never serialises record
 * data, health values, coordinates, notes, or provider payloads.
 */
internal data class LocalDiagnosticDetails(
    val stage:String,
    val exceptionType:String,
    val message:String,
    val jamesFrames:List<String>
) {
    val firstJamesFrame:String? get()=jamesFrames.firstOrNull()
}

internal fun jamesStackFrames(error:Throwable):List<String> = error.stackTrace
    .filter { it.className.startsWith("uk.co.james") }
    .take(16)
    .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

internal fun localDiagnosticDetails(stage:String,error:Throwable)=LocalDiagnosticDetails(
    stage=stage,
    exceptionType=error.javaClass.name,
    message=error.message.orEmpty().take(300),
    jamesFrames=jamesStackFrames(error)
)

class LocalCrashDiagnostics(private val context:Context) {
    private val prefs=context.getSharedPreferences("local-crash-diagnostics",Context.MODE_PRIVATE)
    @Volatile private var route="launch"
    @Volatile private var previousRoute=""
    @Volatile private var uiPhase="starting"

    fun install() {
        val previous=Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread,error ->
            runCatching { record(error) }
            previous?.uncaughtException(thread,error)
        }
    }

    fun setRoute(value:String) { if(route!=value) { previousRoute=route;route=value } }
    fun setUiPhase(value:String) { uiPhase=value.take(120) }

    fun record(error:Throwable,stage:String="uncaught") {
        val version=runCatching {
            context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()
        }.getOrDefault("unknown")
        val details=localDiagnosticDetails(stage,error)
        val frames=details.jamesFrames.joinToString("\n")
        prefs.edit().putString("occurredAt",Instant.now().toString())
            .putString("stage",details.stage)
            .putString("exceptionType",details.exceptionType)
            .putString("message",details.message)
            .putString("frames",frames)
            .putString("route",route)
            .putString("previousRoute",previousRoute)
            .putString("uiPhase",uiPhase)
            .putString("version",version)
            .putInt("schema",6)
            .apply()
    }

    fun read():LocalCrashReport? {
        val occurredAt=prefs.getString("occurredAt","").orEmpty()
        if(occurredAt.isBlank()) return null
        return LocalCrashReport(
            occurredAt,prefs.getString("stage","uncaught").orEmpty(),prefs.getString("exceptionType","").orEmpty(),prefs.getString("message","").orEmpty(),
            prefs.getString("frames","").orEmpty().lineSequence().filter {it.isNotBlank()}.toList(),
            prefs.getString("route","").orEmpty(),prefs.getString("previousRoute","").orEmpty(),prefs.getString("uiPhase","").orEmpty(),
            prefs.getString("version","").orEmpty(),prefs.getInt("schema",0)
        )
    }
    fun clear() { prefs.edit().clear().apply() }
}
