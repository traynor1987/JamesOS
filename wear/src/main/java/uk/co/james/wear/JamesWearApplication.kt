package uk.co.james.wear

import android.app.Application
import uk.co.james.wear.data.WearRepository
import uk.co.james.wear.health.WearStressCheckCoordinator

class JamesWearApplication:Application(){
    val repository by lazy {WearRepository(this)}
    /** Application-scoped so a Data Layer request works while the watch UI is closed. */
    val stressChecks by lazy {WearStressCheckCoordinator(this,repository)}
}