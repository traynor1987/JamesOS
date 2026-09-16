package uk.co.james.work

import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import uk.co.james.core.changed
import uk.co.james.core.fields
import uk.co.james.core.flag
import uk.co.james.core.json
import uk.co.james.core.number
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.core.text
import uk.co.james.core.validTime
import uk.co.james.data.JamesRepository
import uk.co.james.database.StoredRecord
import uk.co.james.time.jamesDayWindow
import java.time.Duration
import java.time.Instant

/** Contract constants are deliberately generic: James OS does not contain Domino's workflow. */
object ShiftTrackerWorkContract {
    const val VERSION = 2
    /** The stable Shift Tracker package is the only app permitted to answer a
     * reconciliation request.  Never select an arbitrary receiver for this
     * protected, factual integration. */
    const val SENDER_PACKAGE = "site.chatgpt.traynor1987.dominosshifttracker.stable"
    const val PERMISSION = "uk.co.james.permission.SHIFT_TRACKER_WORK_CONTEXT"
    const val ACTION_EVENT = "uk.co.james.action.SHIFT_TRACKER_WORK_EVENT"
    const val ACTION_ROTA = "uk.co.james.action.SHIFT_TRACKER_ROTA"
    const val ACTION_RECONCILE = "uk.co.james.action.SHIFT_TRACKER_RECONCILE"
    const val EXTRA_PAYLOAD = "uk.co.james.extra.SHIFT_TRACKER_WORK_PAYLOAD"
    const val MAX_PAYLOAD_BYTES = 24 * 1024
    const val MAX_PAGE_SIZE = 200
    const val INITIAL_HISTORY_DAYS = 40L
    const val STALE_SHIFT_HOURS = 18L
}

/** Keeps package-manager discovery conservative even when another app declares
 * the public action. */
internal fun shiftTrackerReceiverPackage(packages: Iterable<String>): String? =
    packages.firstOrNull { it == ShiftTrackerWorkContract.SENDER_PACKAGE }

enum class WorkEventType {
    SHIFT_STARTED, SHIFT_ENDED, BREAK_STARTED, BREAK_ENDED,
    DELIVERY_STARTED, DELIVERY_COMPLETED, RETURNED_TO_STORE,
    TASK_STARTED, TASK_ENDED, WORK_STATE_CORRECTION
}

enum class WorkMode { OFF_WORK, WORKING, AT_STORE, DELIVERY, BREAK, TASK, RECONCILIATION_REQUIRED, UNKNOWN }

data class CanonicalWorkEvent(
    val externalEventId: String,
    val externalShiftId: String,
    val eventType: WorkEventType,
    val occurredAt: Instant,
    val revision: Long,
    val deleted: Boolean = false,
    val deliveryType: String? = null,
    val externalDeliveryId: String? = null,
    val externalBreakId: String? = null,
    val externalTaskId: String? = null,
    val taskType: String? = null
)

/** A rota is an intention supplied by Shift Tracker, never proof that a shift
 * occurred.  Its stable identity is deliberately separate from event IDs so
 * later clock events can reconcile it without overwriting either record. */
data class CanonicalRotaEntry(
    val externalRotaId:String,
    val externalShiftId:String,
    val startsAt:Instant,
    val endsAt:Instant,
    val revision:Long,
    val deleted:Boolean=false,
    val preparationMinutes:Long=0
) {
    init { require(endsAt>startsAt); require(revision>=0); require(preparationMinutes in 0..240) }
    val plannedOwnership:String get()="WORK"
    val status:String get()=if(deleted)"CANCELLED" else "UPCOMING"
}

data class WorkSession(
    val externalShiftId: String,
    val start: Instant,
    val end: Instant?,
    val shiftMinutes: Long,
    val workingMinutes: Long,
    val breakMinutes: Long,
    val deliveryMinutes: Long,
    val taskMinutes: Long,
    val storeMinutes: Long?,
    val uncertain: Boolean
)

data class CurrentWorkState(
    val mode: WorkMode,
    val externalShiftId: String? = null,
    val since: Instant? = null,
    val freshness: String = "UNAVAILABLE",
    val confidence: String = "NONE",
    val reconciliationNeeded: Boolean = false,
    val session: WorkSession? = null
)

data class WorkHistoryPage(val events: List<CanonicalWorkEvent>, val nextCursor: String?, val complete: Boolean)
interface WorkHistoryProvider {
    suspend fun currentState(): List<CanonicalWorkEvent>
    suspend fun eventsSince(cursor: String?, maxPageSize: Int): WorkHistoryPage
}

/** Strict parser shared by production receiver and reconciliation tests. */
object WorkPayload {
    fun parse(payload: String, clock: Instant = Instant.now()): CanonicalWorkEvent? {
        if (payload.toByteArray(Charsets.UTF_8).size > ShiftTrackerWorkContract.MAX_PAYLOAD_BYTES) return null
        val body = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        if (body.number("contractVersion", -1.0).toInt() !in setOf(1, ShiftTrackerWorkContract.VERSION)) return null
        val eventId = body.text("eventId").trim()
        val shiftId = body.text("shiftId").trim()
        val type = runCatching { WorkEventType.valueOf(body.text("eventType")) }.getOrNull() ?: return null
        val occurredAt = runCatching { Instant.parse(body.text("occurredAt")) }.getOrNull() ?: return null
        val revision = body.number("revision", -1.0).toLong()
        if (eventId.length !in 1..128 || shiftId.length !in 1..128 || revision !in 0..1_000_000L) return null
        if (occurredAt < Instant.parse("2000-01-01T00:00:00Z") || occurredAt > clock.plus(Duration.ofHours(24))) return null
        fun optional(name: String, max: Int): String? = body.text(name).trim().takeIf { it.isNotEmpty() && it.length <= max }
        return CanonicalWorkEvent(
            externalEventId = eventId,
            externalShiftId = shiftId,
            eventType = type,
            occurredAt = occurredAt,
            revision = revision,
            deleted = body.flag("deleted"),
            deliveryType = optional("deliveryType", 16)?.takeIf { it in setOf("SINGLE", "DOUBLE") },
            externalDeliveryId = optional("deliveryId", 128),
            externalBreakId = optional("breakId", 128),
            externalTaskId = optional("taskId", 128),
            taskType = optional("taskType", 64)
        )
    }

    fun from(record: StoredRecord): CanonicalWorkEvent? {
        if (record.kind != "WorkEvent" || record.source != "shift_tracker") return null
        val data = record.data()
        return runCatching {
            CanonicalWorkEvent(
                data.text("externalEventId"), data.text("externalShiftId"),
                WorkEventType.valueOf(data.text("eventType")), Instant.parse(data.text("occurredAt")),
                data.number("revision", -1.0).toLong(), data.flag("deleted"),
                data.text("deliveryType").ifBlank { null }, data.text("externalDeliveryId").ifBlank { null },
                data.text("externalBreakId").ifBlank { null }, data.text("externalTaskId").ifBlank { null },
                data.text("taskType").ifBlank { null }
            )
        }.getOrNull()?.takeIf { it.externalEventId.isNotBlank() && it.externalShiftId.isNotBlank() && it.revision >= 0 }
    }
}

/** Separate planned-shift payload.  It is deliberately not an actual event. */
object RotaPayload {
    fun parse(payload:String,clock:Instant=Instant.now()):List<CanonicalRotaEntry>? {
        if(payload.toByteArray(Charsets.UTF_8).size>ShiftTrackerWorkContract.MAX_PAYLOAD_BYTES)return null
        val body=runCatching {json.parseToJsonElement(payload).jsonObject}.getOrNull()?:return null
        if(body.number("contractVersion",-1.0).toInt()!=ShiftTrackerWorkContract.VERSION)return null
        val values=body["entries"] as? kotlinx.serialization.json.JsonArray?:return null
        if(values.size>ShiftTrackerWorkContract.MAX_PAGE_SIZE)return null
        return values.mapNotNull { element->
            val item=element as? JsonObject?:return@mapNotNull null
            val id=item.text("rotaId").trim();val shift=item.text("shiftId").trim()
            val start=runCatching {Instant.parse(item.text("start"))}.getOrNull();val end=runCatching {Instant.parse(item.text("end"))}.getOrNull()
            val revision=item.number("revision",-1.0).toLong();if(id.length !in 1..128||shift.length !in 1..128||start==null||end==null||revision !in 0..1_000_000L||start>clock.plus(Duration.ofDays(14)))return@mapNotNull null
            runCatching {CanonicalRotaEntry(id,shift,start,end,revision,item.flag("deleted"),item.number("preparationMinutes",0.0).toLong().coerceIn(0,240))}.getOrNull()
        }.takeIf { it.isNotEmpty() }
    }
}

fun workEvents(records: List<StoredRecord>): List<CanonicalWorkEvent> =
    records.mapNotNull(WorkPayload::from).filterNot { it.deleted }.sortedWith(compareBy<CanonicalWorkEvent> { it.occurredAt }.thenBy { it.revision }.thenBy { it.externalEventId })

fun workEventTitle(type: WorkEventType): String = when (type) {
    WorkEventType.SHIFT_STARTED -> "Work started"
    WorkEventType.SHIFT_ENDED -> "Work ended"
    WorkEventType.BREAK_STARTED -> "Break started"
    WorkEventType.BREAK_ENDED -> "Work resumed"
    WorkEventType.DELIVERY_STARTED -> "Delivery started"
    WorkEventType.DELIVERY_COMPLETED, WorkEventType.RETURNED_TO_STORE -> "Back at store"
    WorkEventType.TASK_STARTED -> "Work task started"
    WorkEventType.TASK_ENDED -> "Work task ended"
    WorkEventType.WORK_STATE_CORRECTION -> "Work state corrected"
}

internal fun modeAfter(event: WorkEventType, previous: WorkMode): WorkMode = when (event) {
    WorkEventType.SHIFT_STARTED -> WorkMode.AT_STORE
    WorkEventType.SHIFT_ENDED -> WorkMode.OFF_WORK
    WorkEventType.BREAK_STARTED -> WorkMode.BREAK
    WorkEventType.BREAK_ENDED, WorkEventType.DELIVERY_COMPLETED, WorkEventType.RETURNED_TO_STORE, WorkEventType.TASK_ENDED -> WorkMode.AT_STORE
    WorkEventType.DELIVERY_STARTED -> WorkMode.DELIVERY
    WorkEventType.TASK_STARTED -> WorkMode.TASK
    WorkEventType.WORK_STATE_CORRECTION -> if (previous == WorkMode.OFF_WORK) WorkMode.WORKING else previous
}

fun workSessions(records: List<StoredRecord>, clock: Instant = Instant.now()): List<WorkSession> = workEvents(records)
    .groupBy { it.externalShiftId }
    .mapNotNull { (shiftId, events) ->
        val ordered = events.sortedWith(compareBy<CanonicalWorkEvent> { it.occurredAt }.thenBy { it.revision })
        val start = ordered.firstOrNull { it.eventType == WorkEventType.SHIFT_STARTED } ?: return@mapNotNull null
        val endEvent = ordered.lastOrNull { it.eventType == WorkEventType.SHIFT_ENDED && it.occurredAt >= start.occurredAt }
        val until = endEvent?.occurredAt ?: clock
        if (until < start.occurredAt) return@mapNotNull null
        var mode = WorkMode.AT_STORE
        var cursor = start.occurredAt
        var breakMinutes = 0L
        var deliveryMinutes = 0L
        var taskMinutes = 0L
        ordered.filter { it.occurredAt >= start.occurredAt && it.occurredAt <= until }.forEach { event ->
            val minutes = Duration.between(cursor, event.occurredAt).toMinutes().coerceAtLeast(0)
            when (mode) {
                WorkMode.BREAK -> breakMinutes += minutes
                WorkMode.DELIVERY -> deliveryMinutes += minutes
                WorkMode.TASK -> taskMinutes += minutes
                else -> Unit
            }
            mode = modeAfter(event.eventType, mode)
            cursor = event.occurredAt
        }
        val trailing = Duration.between(cursor, until).toMinutes().coerceAtLeast(0)
        when (mode) {
            WorkMode.BREAK -> breakMinutes += trailing
            WorkMode.DELIVERY -> deliveryMinutes += trailing
            WorkMode.TASK -> taskMinutes += trailing
            else -> Unit
        }
        val shiftMinutes = Duration.between(start.occurredAt, until).toMinutes().coerceAtLeast(0)
        val workingMinutes = (shiftMinutes - breakMinutes).coerceAtLeast(0)
        WorkSession(
            shiftId, start.occurredAt, endEvent?.occurredAt, shiftMinutes, workingMinutes, breakMinutes,
            deliveryMinutes, taskMinutes, (workingMinutes - deliveryMinutes - taskMinutes).coerceAtLeast(0),
            endEvent == null || ordered.none { it.eventType == WorkEventType.SHIFT_STARTED }
        )
    }.sortedByDescending { it.start }

fun deriveCurrentWorkState(records: List<StoredRecord>, clock: Instant = Instant.now()): CurrentWorkState {
    val events = workEvents(records)
    val active = events.filter { it.eventType == WorkEventType.SHIFT_STARTED }.sortedByDescending { it.occurredAt }.firstOrNull { start ->
        events.none { it.externalShiftId == start.externalShiftId && it.eventType == WorkEventType.SHIFT_ENDED && it.occurredAt >= start.occurredAt }
    } ?: return CurrentWorkState(WorkMode.OFF_WORK, freshness = "NO_ACTIVE_SHIFT", confidence = "HIGH")
    val shiftEvents = events.filter { it.externalShiftId == active.externalShiftId && it.occurredAt >= active.occurredAt }
    val last = shiftEvents.maxWithOrNull(compareBy<CanonicalWorkEvent> { it.occurredAt }.thenBy { it.revision })
        ?: return CurrentWorkState(WorkMode.RECONCILIATION_REQUIRED, active.externalShiftId, active.occurredAt, "MALFORMED", "LOW", true)
    val session = workSessions(records, clock).firstOrNull { it.externalShiftId == active.externalShiftId }
    if (Duration.between(active.occurredAt, clock) > Duration.ofHours(ShiftTrackerWorkContract.STALE_SHIFT_HOURS)) {
        return CurrentWorkState(WorkMode.RECONCILIATION_REQUIRED, active.externalShiftId, last.occurredAt, "STALE", "LOW", true, session)
    }
    return CurrentWorkState(modeAfter(last.eventType, WorkMode.AT_STORE), active.externalShiftId, last.occurredAt, "FRESH", "HIGH", false, session)
}

class WorkContextProvider(private val context: Context, private val repository: JamesRepository) {
    private suspend fun materializeActualWork(events:List<CanonicalWorkEvent>,receivedAt:Instant) {
        events.groupBy {it.externalShiftId}.forEach { (shiftId,updates) ->
            val start=updates.filter {it.eventType==WorkEventType.SHIFT_STARTED&&!it.deleted}.maxWithOrNull(compareBy<CanonicalWorkEvent>{it.revision}.thenBy{it.occurredAt})
            val end=updates.filter {it.eventType==WorkEventType.SHIFT_ENDED&&!it.deleted}.maxWithOrNull(compareBy<CanonicalWorkEvent>{it.revision}.thenBy{it.occurredAt})
            val recordId="shift-tracker-work:$shiftId"
            val old=repository.dao.get("personalRecords",recordId)
            val oldData=old?.data()
            // A James-confirmed interval is stronger semantic evidence and is
            // never overwritten by an arriving provider replay.
            if(oldData?.text("ownershipSource")=="JAMES_CONFIRMED")return@forEach
            val startAt=start?.occurredAt?:oldData?.text("start")?.takeIf(::validTime)?.let(Instant::parse)?:return@forEach
            val startId=start?.externalEventId?:oldData?.text("sourceEventId").orEmpty()
            val startRevision=start?.revision?:oldData?.number("providerRevision",0.0)?.toLong()?:0L
            val actualEnd=end?.occurredAt?.takeIf {it>=startAt}
            val raw=personal("OwnershipPeriod",fields(
                "start" to p(startAt.toString()),"end" to p(actualEnd?.toString()?:oldData?.text("end").orEmpty()),
                "ownership" to p("WORK"),"ownershipSource" to p("SHIFT_TRACKER_ACTUAL"),
                "externalShiftId" to p(shiftId),"sourceEventId" to p(startId),
                "endEventId" to (end?.externalEventId?.let(::p)?:oldData?.get("endEventId")?:JsonNull),"providerRevision" to p(maxOf(startRevision,end?.revision?:0)),
                "provenance" to p("Actual Shift Tracker clock state."),"updatedAt" to p(receivedAt.toString())
            ),recordId,"shift_tracker",startAt.toString()).changed("externalId" to p(shiftId),"updatedAt" to p(receivedAt.toString()))
            repository.dao.put(StoredRecord.from("personalRecords",raw))
        }
    }
    /** Stores only the planned rota projection.  Callers reconcile a later
     * clock-in/out through [ingest]; this method never creates ownership. */
    suspend fun ingestRota(entries:List<CanonicalRotaEntry>, receivedAt:Instant=Instant.now()):Int {
        var accepted=0
        repository.db.withTransaction {
            entries.forEach { entry ->
                val recordId="shift-tracker-rota:"+entry.externalRotaId
                val old=repository.dao.get("personalRecords",recordId)
                if(old!=null&&old.data().number("revision",-1.0).toLong()>=entry.revision)return@forEach
                val raw=personal("ScheduledCommitment",fields(
                    "externalCommitmentId" to p(entry.externalRotaId),"externalShiftId" to p(entry.externalShiftId),
                    "title" to p("Work"),"start" to p(entry.startsAt.toString()),"end" to p(entry.endsAt.toString()),
                    "plannedOwnership" to p(entry.plannedOwnership),"status" to p(entry.status),"fixedConstraint" to p(!entry.deleted),
                    "preparationMinutes" to p(entry.preparationMinutes),"revision" to p(entry.revision),"receivedAt" to p(receivedAt.toString()),
                    "reconciliationState" to p("UNKNOWN"),"contractVersion" to p(ShiftTrackerWorkContract.VERSION),"titleSource" to p("SHIFT_TRACKER_ROTA")
                ),recordId,"shift_tracker",entry.startsAt.toString()).changed("externalId" to p(entry.externalRotaId),"updatedAt" to p(receivedAt.toString()))
                repository.dao.put(StoredRecord.from("personalRecords",raw));accepted++
            }
        }
        return accepted
    }
    suspend fun ingest(events: List<CanonicalWorkEvent>, receivedAt: Instant = Instant.now(), cursor: String? = null): Int {
        require(events.size <= ShiftTrackerWorkContract.MAX_PAGE_SIZE)
        var accepted = 0
        repository.db.withTransaction {
            events.forEach { event ->
                val recordId = "shift-tracker-event:" + event.externalEventId
                val old = repository.dao.get("personalRecords", recordId)?.let(WorkPayload::from)
                if (old != null && old.revision >= event.revision) return@forEach
                val day = jamesDayWindow(repository.stateInputs(event.occurredAt), event.occurredAt).id
                val raw = personal(
                    "WorkEvent",
                    fields(
                        "externalEventId" to p(event.externalEventId), "externalShiftId" to p(event.externalShiftId),
                        "eventType" to p(event.eventType.name), "occurredAt" to p(event.occurredAt.toString()),
                        "receivedAt" to p(receivedAt.toString()), "revision" to p(event.revision), "deleted" to p(event.deleted),
                        "deliveryType" to (event.deliveryType?.let(::p) ?: JsonNull),
                        "externalDeliveryId" to (event.externalDeliveryId?.let(::p) ?: JsonNull),
                        "externalBreakId" to (event.externalBreakId?.let(::p) ?: JsonNull),
                        "externalTaskId" to (event.externalTaskId?.let(::p) ?: JsonNull),
                        "taskType" to (event.taskType?.let(::p) ?: JsonNull),
                        "jamesDayId" to p(day), "contractVersion" to p(ShiftTrackerWorkContract.VERSION),
                        "title" to p(workEventTitle(event.eventType))
                    ),
                    recordId = recordId, source = "shift_tracker", timestamp = event.occurredAt.toString()
                ).changed("externalId" to p(event.externalEventId), "updatedAt" to p(receivedAt.toString()))
                repository.dao.put(StoredRecord.from("personalRecords", raw))
                // Concise factual context for Timeline/Today.  This is not an
                // ownership row and carries no Balance points; Work ownership
                // remains the separately materialised shift interval.
                val activity=when(event.eventType) {
                    WorkEventType.BREAK_STARTED -> "Work break"
                    WorkEventType.DELIVERY_STARTED -> "Work delivery"
                    WorkEventType.RETURNED_TO_STORE,WorkEventType.DELIVERY_COMPLETED -> "Back at store"
                    else -> null
                }
                if(activity!=null) {
                    val context=personal("LifeFactActivity",fields("title" to p(activity),"activity" to p(activity),"start" to p(event.occurredAt.toString()),"ownershipContext" to p("WORK"),"externalShiftId" to p(event.externalShiftId),"sourceEventId" to p(event.externalEventId),"provenance" to p("Shift Tracker factual activity.")),recordId="shift-tracker-activity:${event.externalEventId}",source="shift_tracker",timestamp=event.occurredAt.toString())
                    repository.dao.put(StoredRecord.from("personalRecords",context))
                }
                accepted++
            }
            materializeActualWork(events,receivedAt)
            val state = personal(
                "WorkIntegrationState",
                fields(
                    "contractVersion" to p(ShiftTrackerWorkContract.VERSION),
                    "lastSuccessfulIngest" to p(receivedAt.toString()),
                    "lastCursor" to (cursor?.let(::p) ?: JsonNull),
                    "eventsAccepted" to p(accepted),
                    "sourceAvailability" to p(if (endpointAvailable()) "AVAILABLE" else "WAITING_FOR_SHIFT_TRACKER_UPDATE")
                ),
                recordId = "shift-tracker-integration", source = "shift_tracker", timestamp = receivedAt.toString()
            )
            repository.dao.put(StoredRecord.from("personalRecords", state))
        }
        return accepted
    }

    fun endpointPackage(): String? {
        // Do not use MATCH_DEFAULT_ONLY: a protected receiver is not an
        // activity launch target and legitimately does not declare CATEGORY_DEFAULT.
        val intent = Intent(ShiftTrackerWorkContract.ACTION_RECONCILE)
            .setPackage(ShiftTrackerWorkContract.SENDER_PACKAGE)
        val packages = context.packageManager.queryBroadcastReceivers(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
        return shiftTrackerReceiverPackage(packages)
    }

    fun endpointAvailable(): Boolean = endpointPackage() != null

    fun requestReconciliation(): Boolean {
        val packageName = endpointPackage() ?: return false
        val request = fields(
            "contractVersion" to p(ShiftTrackerWorkContract.VERSION),
            "cursor" to p(""),
            "maxPageSize" to p(ShiftTrackerWorkContract.MAX_PAGE_SIZE),
            "initialHistoryDays" to p(ShiftTrackerWorkContract.INITIAL_HISTORY_DAYS)
        ).toString()
        context.sendBroadcast(
            Intent(ShiftTrackerWorkContract.ACTION_RECONCILE)
                .setPackage(packageName)
                .putExtra(ShiftTrackerWorkContract.EXTRA_PAYLOAD, request),
            ShiftTrackerWorkContract.PERMISSION
        )
        return true
    }

    suspend fun reconcile(provider: WorkHistoryProvider): Int {
        var accepted = ingest(provider.currentState())
        var cursor: String? = null
        do {
            val page = provider.eventsSince(cursor, ShiftTrackerWorkContract.MAX_PAGE_SIZE)
            require(page.events.size <= ShiftTrackerWorkContract.MAX_PAGE_SIZE)
            accepted += ingest(page.events, cursor = page.nextCursor)
            cursor = page.nextCursor
        } while (!page.complete && cursor != null)
        return accepted
    }
}
