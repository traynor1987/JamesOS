package uk.co.james.wear.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[34])
class ObservationDeliveryTest {
    private lateinit var db:WearDatabase
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Application>(),WearDatabase::class.java).allowMainThreadQueries().build()}
    @After fun close(){db.close()}
    @Test fun acknowledgeKeepsLocalHistoryButRemovesOnlyAcknowledgedRowsFromRetryQueue()=runBlocking {
        val dao=db.dao();dao.putObservation(ObservationEntity("one","heart_rate",60.0,"bpm",100));dao.putObservation(ObservationEntity("two","heart_rate",65.0,"bpm",200))
        dao.attempted(listOf("one"));dao.acknowledge(listOf("one"));dao.acknowledge(listOf("one","unknown"))
        assertEquals(listOf("two"),dao.pending().map {it.id})
        assertEquals(1,dao.pendingCount().first())
        assertEquals(listOf("two"),dao.pendingSince(0).map {it.id})
        assertEquals(listOf("two","one"),dao.recent("heart_rate").first().map {it.id})
        assertEquals(-1L,dao.putObservation(ObservationEntity("one","heart_rate",60.0,"bpm",100)))
        assertEquals(1,dao.pendingCount().first())
    }
    @Test fun purgeNeverDropsUnsentObservations()=runBlocking {
        val dao=db.dao();dao.putObservation(ObservationEntity("pending","steps",10.0,"steps",100,createdAt=100));dao.putObservation(ObservationEntity("sent","steps",5.0,"steps",50,createdAt=50))
        dao.acknowledge(listOf("sent"));dao.purge(200)
        assertEquals(listOf("pending"),dao.pending().map {it.id})
        assertEquals(listOf("pending"),dao.recent("steps").first().map {it.id})
    }
}
