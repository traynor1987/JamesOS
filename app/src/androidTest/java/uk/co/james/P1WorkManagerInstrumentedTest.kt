package uk.co.james

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import uk.co.james.sync.BackgroundJobs

@RunWith(AndroidJUnit4::class)
class P1WorkManagerInstrumentedTest {
    @Test fun schedulingTwiceDoesNotQueueDuplicatePeriodicSyncs(){
        val context=ApplicationProvider.getApplicationContext<Context>()
        runCatching {WorkManagerTestInitHelper.initializeTestWorkManager(context,androidx.work.Configuration.Builder().setExecutor(SynchronousExecutor()).build())}
        BackgroundJobs.schedule(context);BackgroundJobs.schedule(context)
        val manager=WorkManager.getInstance(context)
        assertEquals(1,manager.getWorkInfosForUniqueWork("james-health-sync").get().size)
        assertEquals(1,manager.getWorkInfosForUniqueWork("james-daily-backup").get().size)
    }
}
