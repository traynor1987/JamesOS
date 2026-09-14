package uk.co.james.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant

/** UI-only clock: suspends when the Activity stops; background collection uses native workers. */
@Composable fun foregroundMinute():Instant {
    val owner=LocalLifecycleOwner.current
    val time by produceState(Instant.now(),owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(isActive){value=Instant.now();delay(60_000)}
        }
    }
    return time
}
