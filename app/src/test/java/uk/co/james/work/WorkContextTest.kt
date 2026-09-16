package uk.co.james.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.co.james.data.JamesRepository
import uk.co.james.database.JamesDatabase

@RunWith(RobolectricTestRunner::class)
@Config(application=android.app.Application::class)
class WorkContextTest {
    private val now = Instant.parse("2026-09-14T20:00:00Z")
    private lateinit var context: Context
    private lateinit var db: JamesDatabase
    private lateinit var repository: JamesRepository
    private lateinit var provider: WorkContextProvider

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, JamesDatabase::class.java).allowMainThreadQueries().build()
        repository = JamesRepository(context, db)
        provider = WorkContextProvider(context, repository)
    }
    @After fun close() = db.close()

    private fun event(id: String, type: WorkEventType, at: String, revision: Long = 1, deleted: Boolean = false) =
        CanonicalWorkEvent(id, "shift-a", type, Instant.parse(at), revision, deleted,
            deliveryType = if (type == WorkEventType.DELIVERY_STARTED) "SINGLE" else null,
            externalDeliveryId = if (type in setOf(WorkEventType.DELIVERY_STARTED, WorkEventType.RETURNED_TO_STORE)) "delivery-a" else null)

    @Test fun strictParserRejectsUnsupportedInput() {
        assertNull(WorkPayload.parse("""{"contractVersion":2}""", now))
        assertNull(WorkPayload.parse("""{"contractVersion":1,"eventId":"a","shiftId":"s","eventType":"NOPE","occurredAt":"2026-09-14T12:00:00Z","revision":1}""", now))
        assertNull(WorkPayload.parse("""{"contractVersion":1,"eventId":"a","shiftId":"s","eventType":"SHIFT_STARTED","occurredAt":"2026-09-16T12:00:00Z","revision":1}""", now))
    }

    @Test fun roomIngestionPersistsCanonicalEvents() = runBlocking {
        val history = listOf(
            event("start", WorkEventType.SHIFT_STARTED, "2026-09-14T11:02:00Z"),
            event("delivery-start", WorkEventType.DELIVERY_STARTED, "2026-09-14T12:14:00Z"),
            event("returned", WorkEventType.RETURNED_TO_STORE, "2026-09-14T12:31:00Z"),
            event("break-start", WorkEventType.BREAK_STARTED, "2026-09-14T14:10:00Z"),
            event("break-end", WorkEventType.BREAK_ENDED, "2026-09-14T14:30:00Z"),
            event("end", WorkEventType.SHIFT_ENDED, "2026-09-14T19:07:00Z")
        )
        assertEquals(6, provider.ingest(history, now))
        assertEquals(6, workEvents(repository.stateInputs(now)).size)
    }

    @Test fun reconciliationRepairsMissedEventsWithoutReplayDuplicates() = runBlocking {
        val missed = listOf(
            event("start", WorkEventType.SHIFT_STARTED, "2026-09-14T11:02:00Z"),
            event("end", WorkEventType.SHIFT_ENDED, "2026-09-14T19:07:00Z")
        )
        val fake = object : WorkHistoryProvider {
            override suspend fun currentState() = emptyList<CanonicalWorkEvent>()
            override suspend fun eventsSince(cursor: String?, maxPageSize: Int): WorkHistoryPage =
                if (cursor == null) WorkHistoryPage(missed.take(1), "next", false)
                else WorkHistoryPage(missed.drop(1), null, true)
        }
        assertEquals(2, provider.reconcile(fake))
        assertEquals(0, provider.reconcile(fake))
        assertEquals(WorkMode.OFF_WORK, deriveCurrentWorkState(repository.stateInputs(now), now).mode)
    }

    @Test fun stateTransitionsRemainGeneric() {
        assertEquals("Delivery started", workEventTitle(WorkEventType.DELIVERY_STARTED))
        assertEquals("Back at store", workEventTitle(WorkEventType.RETURNED_TO_STORE))
        assertEquals(WorkMode.OFF_WORK, modeAfter(WorkEventType.SHIFT_ENDED, WorkMode.DELIVERY))
    }

    @Test fun rotaIsPlannedEvidenceAndNeverAnActualShift() {
        val rota=CanonicalRotaEntry("rota-a","shift-a",Instant.parse("2026-09-15T17:00:00Z"),Instant.parse("2026-09-15T23:00:00Z"),1)
        assertEquals("WORK",rota.plannedOwnership)
        assertEquals("UPCOMING",rota.status)
    }

    @Test fun clockedShiftCreatesAndClosesOneActualWorkOwnership() = runBlocking {
        provider.ingest(listOf(
            event("start",WorkEventType.SHIFT_STARTED,"2026-09-14T17:56:00Z"),
            event("end",WorkEventType.SHIFT_ENDED,"2026-09-14T23:14:00Z")
        ),now)
        val ownership=repository.stateInputs(now).single {it.recordId=="shift-tracker-work:shift-a"}
        assertEquals("WORK",ownership.data().text("ownership"))
        assertEquals("2026-09-14T17:56:00Z",ownership.data().text("start"))
        assertEquals("2026-09-14T23:14:00Z",ownership.data().text("end"))
    }
}
