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
    val exceptionType:String,
    val message:String,
    val jamesFrames:List<String>,
    val route:String,
    val previousRoute:String,
    val uiPhase:String,
    val appVersion:String,
    val databaseSchemaVersion:Int
)

internal fun jamesStackFrames(error:Throwable):List<String> = error.stackTrace
    .filter { it.className.startsWith("uk.co.james") }
    .take(16)
    .map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

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

    fun record(error:Throwable) {
        val version=runCatching {
            context.packageManager.getPackageInfo(context.packageName,0).versionName.orEmpty()
        }.getOrDefault("unknown")
        val frames=jamesStackFrames(error).joinToString("\n")
        prefs.edit().putString("occurredAt",Instant.now().toString())
            .putString("exceptionType",error.javaClass.name)
            .putString("message",error.message.orEmpty().take(300))
            .putString("frames",frames)
            .putString("route",route)
            .putString("previousRoute",previousRoute)
            .putString("uiPhase",uiPhase)
            .putString("version",version)
            .putInt("schema",5)
            .apply()
    }

    fun read():LocalCrashReport? {
        val occurredAt=prefs.getString("occurredAt","").orEmpty()
        if(occurredAt.isBlank()) return null
        return LocalCrashReport(
            occurredAt,prefs.getString("exceptionType","").orEmpty(),prefs.getString("message","").orEmpty(),
            prefs.getString("frames","").orEmpty().lineSequence().filter {it.isNotBlank()}.toList(),
            prefs.getString("route","").orEmpty(),prefs.getString("previousRoute","").orEmpty(),prefs.getString("uiPhase","").orEmpty(),
            prefs.getString("version","").orEmpty(),prefs.getInt("schema",0)
        )
    }
    fun clear() { prefs.edit().clear().apply() }
}
