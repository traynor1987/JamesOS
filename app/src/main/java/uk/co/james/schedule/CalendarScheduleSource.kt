package uk.co.james.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import uk.co.james.core.changed
import uk.co.james.core.fields
import uk.co.james.core.personal
import uk.co.james.core.text
import uk.co.james.core.flag
import uk.co.james.core.obj
import uk.co.james.core.p
import uk.co.james.data.JamesRepository
import uk.co.james.database.StoredRecord
import java.time.Duration
import java.time.Instant

/** Read-only, bounded Calendar ingestion. Calendar is planned evidence only. */
class CalendarScheduleSource(private val context:Context, private val repository:JamesRepository) {
    companion object { val LOOK_AHEAD:Duration=Duration.ofDays(7); val RECONCILE_HISTORY:Duration=Duration.ofDays(3) }
    fun permitted()=ContextCompat.checkSelfPermission(context,Manifest.permission.READ_CALENDAR)==PackageManager.PERMISSION_GRANTED
    suspend fun refresh(enabledCalendarIds:Set<String>, now:Instant=Instant.now()):Int {
        if(!permitted()||enabledCalendarIds.isEmpty())return 0
        val rows=CalendarProvider(context.contentResolver).instances(now.minus(RECONCILE_HISTORY),now.plus(LOOK_AHEAD),enabledCalendarIds)
        val rules=repository.stateInputs(now).filter {it.kind=="CalendarClassificationRule"&&it.data().flag("enabled")}
        var changed=0
        repository.db.withTransaction {
            val seen=rows.map {it.asCommitment().stableId}.toSet()
            rows.forEach { instance ->
                val incoming=instance.asCommitment(); val old=repository.dao.get("personalRecords",incoming.stableId)
                val oldData=old?.data()
                // A user classification is a local semantic correction, not
                // Calendar-owned provider data. Preserve it across refresh.
                val rule=rules.firstOrNull { rule -> rule.data().text("calendarId")==instance.calendarId&&rule.data().text("title")==instance.title.trim().lowercase() }
                val ownership=oldData?.text("plannedOwnership")?.takeIf { oldData.text("classificationProvenance")=="MANUAL_EVENT" }?:rule?.data()?.text("ownership")?:incoming.plannedOwnership
                val provenance=if(oldData?.text("classificationProvenance")=="MANUAL_EVENT")"MANUAL_EVENT" else if(rule!=null)"USER_RULE" else "UNKNOWN"
                val incomingRaw=incoming.asRecord()
                val raw=incomingRaw.changed("data" to incomingRaw.obj("data").changed(
                    "plannedOwnership" to p(ownership),"classificationProvenance" to p(provenance),
                    "providerAvailability" to p(instance.availability?:"UNKNOWN"),"lastIngestedAt" to p(now.toString())
                ),"updatedAt" to p(now.toString()))
                if(old?.rawJson!=raw.toString()){repository.dao.put(StoredRecord.from("personalRecords",raw));changed++}
            }
            // Provider deletion only retires still-planned Calendar evidence.
            // Independently confirmed Visits/Ownership are separate facts and
            // are intentionally never touched here.
            repository.dao.sourceKindBetween("android_calendar","ScheduledCommitment",now.toString(),now.plus(LOOK_AHEAD).toString()).filter {it.recordId !in seen&&it.data().text("status","UPCOMING")=="UPCOMING"}.forEach { stale ->
                val cancelled=stale.raw().changed(
                    "data" to stale.data().changed("status" to p("CANCELLED"),"fixedConstraint" to p(false),"providerRemovedAt" to p(now.toString())),
                    "updatedAt" to p(now.toString())
                )
                repository.dao.put(StoredRecord.from(stale.store,cancelled));changed++
            }
        }
        return changed
    }
    suspend fun classify(commitmentId:String, ownership:String, applyToSimilar:Boolean=false, now:Instant=Instant.now()) {
        require(ownership in setOf("AUTONOMOUS","WORK","COMMITTED","CONSTRAINED","UNKNOWN"))
        repository.db.withTransaction {
            val row=repository.dao.get("personalRecords",commitmentId)?:return@withTransaction
            val data=row.data(); val raw=row.raw().changed("data" to data.changed("plannedOwnership" to p(ownership),"classificationProvenance" to p("MANUAL_EVENT"),"promptDismissedUntil" to p("")),"updatedAt" to p(now.toString()))
            repository.dao.put(StoredRecord.from(row.store,raw))
            if(applyToSimilar&&data.text("calendarId").isNotBlank()&&data.text("title").isNotBlank()) {
                val rule=personal("CalendarClassificationRule",fields("calendarId" to p(data.text("calendarId")),"title" to p(data.text("title").trim().lowercase()),"ownership" to p(ownership),"enabled" to p(true),"createdAt" to p(now.toString())),recordId="calendar-rule:${data.text("calendarId")}:${data.text("title").trim().lowercase().hashCode()}",source="manual",timestamp=now.toString())
                repository.dao.put(StoredRecord.from("personalRecords",rule))
            }
        }
    }
    suspend fun dismissPrompt(commitmentId:String, until:Instant) = repository.db.withTransaction {
        val row=repository.dao.get("personalRecords",commitmentId)?:return@withTransaction
        repository.dao.put(StoredRecord.from(row.store,row.raw().changed("data" to row.data().changed("promptDismissedUntil" to p(until.toString())),"updatedAt" to p(Instant.now().toString()))))
    }
}
