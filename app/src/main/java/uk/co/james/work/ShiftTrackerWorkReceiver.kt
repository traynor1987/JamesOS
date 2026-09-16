package uk.co.james.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.james.JamesApplication

/** Exported endpoint; Android enforces the signature permission before this code runs. */
class ShiftTrackerWorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val payload=intent.getStringExtra(ShiftTrackerWorkContract.EXTRA_PAYLOAD)?:return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val work=(context.applicationContext as JamesApplication).work
                when(intent.action) {
                    ShiftTrackerWorkContract.ACTION_EVENT -> WorkPayload.parse(payload)?.let {work.ingest(listOf(it))}
                    ShiftTrackerWorkContract.ACTION_ROTA -> RotaPayload.parse(payload)?.let {work.ingestRota(it)}
                }
            }
            finally { pending.finish() }
        }
    }
}
