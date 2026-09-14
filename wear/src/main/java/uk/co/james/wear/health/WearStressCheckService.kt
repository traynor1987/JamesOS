package uk.co.james.wear.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import uk.co.james.wear.JamesWearApplication

/**
 * A short, user-requested foreground service started by an incoming Data Layer
 * message. This is the supported way to keep the 45-second Samsung collection
 * alive when the Wear Activity is not visible.
 */
class WearStressCheckService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val coordinator get()=(application as JamesWearApplication).stressChecks
    private var stopJob:Job?=null

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action!=ACTION_REMOTE_STRESS_CHECK)return START_NOT_STICKY
        startForeground(NOTIFICATION_ID,notification("Measuring…"))
        val requestId=intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val requestedAt=intent.getLongExtra(EXTRA_REQUESTED_AT,0L)
        if(requestId.isBlank()||requestedAt<=0L) {
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId);return START_NOT_STICKY
        }
        val accepted=coordinator.startRemoteCheck(requestId,requestedAt)
        if(!accepted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        stopJob?.cancel()
        stopJob=scope.launch {
            var activeSeen=false
            coordinator.active.collect { active->
                if(active) {
                    activeSeen=true
                    updateNotification("Measuring…")
                }
                if(activeSeen&&!active) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    stopJob?.cancel()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){stopJob?.cancel();scope.cancel();super.onDestroy()}

    private fun updateNotification(text:String) {
        (getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID,notification(text))
    }
    private fun notification(text:String):Notification {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,"James OS sensor checks",NotificationManager.IMPORTANCE_LOW))
        return NotificationCompat.Builder(this,CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("James Stress Check")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_REMOTE_STRESS_CHECK="uk.co.james.wear.action.REMOTE_STRESS_CHECK"
        const val EXTRA_REQUEST_ID="stressCheckRequestId"
        const val EXTRA_REQUESTED_AT="stressCheckRequestedAt"
        private const val CHANNEL_ID="james_sensor_check"
        private const val NOTIFICATION_ID=4201
    }
}
