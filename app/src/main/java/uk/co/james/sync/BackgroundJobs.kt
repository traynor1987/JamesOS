package uk.co.james.sync
import android.content.Context
import androidx.work.*
import uk.co.james.JamesApplication
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
class BackupWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result = try {(applicationContext as JamesApplication).repository.autoBackup();Result.success()} catch(e: Exception){if(runAttemptCount<3)Result.retry() else Result.failure()}
}
class PlacesWorker(context: Context,params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {val app=applicationContext as JamesApplication;return try {app.location.restore();if(app.preferences.activity.first())app.location.setActivity(true);Result.success()}catch(e:SecurityException){Result.failure()}catch(e:Exception){if(runAttemptCount<3)Result.retry() else Result.failure()}}
}
class WhoopWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params){
 override suspend fun doWork():Result {
  val app=applicationContext as JamesApplication
  if(!app.whoop.configured())return Result.success()
  return try{app.whoop.sync();Result.success()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:uk.co.james.whoop.WhoopException){if(e.code==401)Result.failure()else if(runAttemptCount<3)Result.retry()else Result.failure()}catch(e:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
 }
}
class HealthWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val app=applicationContext as JamesApplication
        return try {
            if(!runCatching {app.health.status().granted.isEmpty()}.getOrDefault(true)) {
                app.health.sync()
                // Nutrition is optional: partial grants remain a successful health sync.
                app.nutrition.sync()
            }
            // The existing bounded 15-minute job also advances time-based Sleepiness from
            // persisted authoritative timestamps. No new polling or foreground service.
            val rows=app.repository.stateInputs()
            if(rows.isNotEmpty())app.repository.persistRightNow(uk.co.james.state.rightNowSummary(rows,app.preferences.energyTime.first()))
            Result.success()
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(e:Exception){if(runAttemptCount<3)Result.retry()else Result.failure()}
    }
}
object BackgroundJobs {
    fun scheduleWhoop(context:Context){WorkManager.getInstance(context).enqueueUniquePeriodicWork("james-whoop-sync",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<WhoopWorker>(30,TimeUnit.MINUTES).setConstraints(networkConstraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build())}
    fun stopWhoop(context:Context){WorkManager.getInstance(context).cancelUniqueWork("james-whoop-sync")}
    fun schedule(context: Context) {
        val manager=WorkManager.getInstance(context)
        manager.enqueueUniquePeriodicWork("james-daily-backup",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<BackupWorker>(24,TimeUnit.HOURS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
        manager.enqueueUniquePeriodicWork("james-health-sync",ExistingPeriodicWorkPolicy.UPDATE,PeriodicWorkRequestBuilder<HealthWorker>(15,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,15,TimeUnit.MINUTES).build())
    }
    fun restorePlaces(context: Context){WorkManager.getInstance(context).enqueueUniqueWork("james-restore-places",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<PlacesWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())}
    val networkConstraints: Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()
}
interface ReadOnlySource { val source: String; suspend fun read(cursor: String?): SourcePage; suspend fun disconnect() }
data class SourcePage(val records: List<kotlinx.serialization.json.JsonObject>,val nextCursor: String?)
