package uk.co.james.core

import uk.co.james.database.StoredRecord
import uk.co.james.time.duration
import java.time.*
import java.time.format.DateTimeFormatter
import java.text.NumberFormat
import java.util.Locale

fun sourceLabel(source:String):String=when(source.trim()) {
    "com.whoop.android","whoop" -> "WHOOP"
    "com.sec.android.app.shealth","samsung_health" -> "Samsung Health"
    "health_connect" -> "Health Connect"
    "wear" -> "James OS Wear"
    "shift_tracker" -> "Shift Tracker"
    "gig_tracker" -> "Gig Tracker"
    "all" -> "All sources"
    "manual" -> "You"
    "android" -> "Android"
    "gps" -> "Location"
    "james" -> "James OS"
    "rut" -> "Routines"
    else -> if(source.contains('.'))"Health provider"else source.ifBlank {"Health Connect"}
}
fun providerLabel(record:StoredRecord):String {
    val origin=record.data().text("provider")
    val providers=origin.split(',').map {sourceLabel(it.trim())}.distinct().joinToString(" + ")
    return if(record.source=="health_connect"&&origin.isNotBlank()&&providers!="Health Connect")"$providers · Health Connect"else sourceLabel(record.source)
}
fun localClock(timestamp:String,zone:ZoneId=ZoneId.systemDefault()):String=runCatching {Instant.parse(timestamp).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))}.getOrDefault("Time unavailable")
fun displayDate(date:String):String=runCatching {LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE d MMM",Locale.UK))}.getOrDefault(date)
fun healthValue(record:StoredRecord):String {
    val d=record.data();val value=d.number("value");val unit=d.text("unit")
    return when {
        unit=="min" -> duration(value.toLong())
        unit in listOf("steps","bpm","kcal") -> "${NumberFormat.getIntegerInstance(Locale.UK).format(value)} $unit"
        unit=="m" && value>=1000 -> "${String.format(Locale.UK,"%.1f",value/1000)} km"
        else -> "${String.format(Locale.UK,"%.1f",value)} $unit"
    }
}
