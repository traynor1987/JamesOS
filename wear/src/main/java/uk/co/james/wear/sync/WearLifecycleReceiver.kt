package uk.co.james.wear.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.james.wear.BuildConfig
import uk.co.james.wear.JamesWearApplication
import uk.co.james.wear.data.TransferStage
import uk.co.james.wear.data.UpdateState
import uk.co.james.wear.health.HealthServicesProvider

/** Restores passive collection after reboot and confirms a user-approved package update. */
class WearLifecycleReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        val result=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val app=context.applicationContext as JamesWearApplication
                val repo=app.repository
                File(repo.updateDir(),"incoming.part").takeIf {it.exists()}?.delete()
                if(intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) {
                    val node=context.getSharedPreferences("wear-update",Context.MODE_PRIVATE).getString("node","").orEmpty()
                    val message="James OS updated to ${BuildConfig.VERSION_NAME}."
                    repo.update(UpdateState(TransferStage.UPDATED,BuildConfig.VERSION_NAME,100,message))
                    if(node.isNotBlank())WearSync(context,repo).updateStatus(node,TransferStage.UPDATED,BuildConfig.VERSION_NAME,100,message)
                    runCatching {WearSync(context,repo).sendStatus(node.ifBlank {null})}
                    File(repo.updateDir(),"james-wear.apk").takeIf {it.exists()}?.delete()
                    repo.clearReadyUpdate()
                }
                if(repo.passiveRegistered)runCatching {HealthServicesProvider(context).register()}.onFailure {repo.passive(false)}
                runCatching {WearSync(context,repo).uploadPending()}
            } finally {result.finish()}
        }
    }
}
