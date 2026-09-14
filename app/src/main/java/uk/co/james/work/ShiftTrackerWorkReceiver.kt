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
        if (intent.action != ShiftTrackerWorkContract.ACTION_EVENT) return
        val event = intent.getStringExtra(ShiftTrackerWorkContract.EXTRA_PAYLOAD)?.let(WorkPayload::parse) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as JamesApplication).work.ingest(listOf(event)) }
            finally { pending.finish() }
        }
    }
}
