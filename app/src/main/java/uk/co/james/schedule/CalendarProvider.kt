package uk.co.james.schedule

import android.content.ContentResolver
import android.provider.CalendarContract
import java.time.Instant

/** Bounded, read-only Calendar Provider reader. It queries event *instances*,
 * so recurrence masters never become one giant scheduled commitment. */
class CalendarProvider(private val resolver:ContentResolver) {
    data class Calendar(val id:String,val name:String)
    fun calendars():List<Calendar> = resolver.query(CalendarContract.Calendars.CONTENT_URI,arrayOf(CalendarContract.Calendars._ID,CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),null,null,"${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c -> buildList { while(c.moveToNext()) { val id=c.getString(0); val name=c.getString(1); if(!id.isNullOrBlank()&&!name.isNullOrBlank())add(Calendar(id,name.take(80))) } } }.orEmpty()
    fun instances(from:Instant,to:Instant,enabledCalendarIds:Set<String>):List<CalendarInstance> {
        if(enabledCalendarIds.isEmpty())return emptyList()
        val uri=CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toEpochMilli().toString()).appendPath(to.toEpochMilli().toString()).build()
        val columns=arrayOf(CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.CALENDAR_ID,CalendarContract.Instances.TITLE,CalendarContract.Instances.BEGIN,CalendarContract.Instances.END,CalendarContract.Instances.ALL_DAY,CalendarContract.Instances.AVAILABILITY,CalendarContract.Instances.LAST_DATE)
        return resolver.query(uri,columns,null,null,"${CalendarContract.Instances.BEGIN} ASC")?.use {c->
            generateSequence {if(c.moveToNext()) c else null}.mapNotNull {row->
                val calendarId=row.getString(1)?:return@mapNotNull null
                if(calendarId !in enabledCalendarIds)return@mapNotNull null
                val start=Instant.ofEpochMilli(row.getLong(3));val end=Instant.ofEpochMilli(row.getLong(4));if(!end.isAfter(start))return@mapNotNull null
                CalendarInstance(row.getString(0)?:return@mapNotNull null,calendarId,row.getString(2).orEmpty(),start,end,row.getInt(5)!=0,when(row.getInt(6)){CalendarContract.Events.AVAILABILITY_BUSY->"BUSY";CalendarContract.Events.AVAILABILITY_FREE->"FREE";CalendarContract.Events.AVAILABILITY_TENTATIVE->"TENTATIVE";else->null},row.getLong(7))
            }.toList()
        }.orEmpty()
    }
}
