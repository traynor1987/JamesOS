package uk.co.james.routines

import kotlinx.serialization.json.*
import uk.co.james.core.*
import java.time.*

object Ledger {
    fun validateEvent(e: JsonObject) {
        require(listOf("id", "title", "category").all { e.text(it).isNotBlank() }) { "Event identity, title or category is missing." }
        require(listOf("timestamp", "createdAt", "updatedAt").all { validTime(e.text(it)) } && validDate(e.text("localDate"))) { "Invalid event date." }
        val points = e.number("points", Double.NaN)
        require(points.isFinite() && points % 1.0 == 0.0 && kotlin.math.abs(points) <= 9007199254740991.0) { "Invalid points." }
        require(e.text("type") in listOf("initial", "positive", "negative", "recovery"))
        require(e.text("type") == "initial" || if (e.text("type") == "negative") points < 0 else points > 0) { "Points do not match the event direction." }
        require(!Instant.parse(e.text("timestamp")).isAfter(Instant.now().plusSeconds(60))) { "Event is in the future." }
        val dateMiddle = LocalDate.parse(e.text("localDate")).atTime(12,0).toInstant(ZoneOffset.UTC)
        require(kotlin.math.abs(Duration.between(dateMiddle, Instant.parse(e.text("timestamp"))).toHours()) <= 36) { "Event date and timestamp disagree." }
        if (e.text("type") == "recovery") require(e.text("linkedEventId").isNotBlank())
        else require(e.text("linkedEventId").isBlank())
        if (e.containsKey("note")) require((e["note"] as? JsonPrimitive)?.isString == true)
    }
    fun validateTemplate(t: JsonObject) {
        require(listOf("id", "title", "category", "emoji").all { t.text(it).isNotBlank() }) { "Invalid event template." }
        require((t["enabled"] as? JsonPrimitive)?.booleanOrNull != null && (t["isDefault"] as? JsonPrimitive)?.booleanOrNull != null)
        require(t.number("order", Double.NaN).isFinite())
        val points = t.number("points", Double.NaN)
        require(points.isFinite() && points % 1.0 == 0.0 && t.text("type") in listOf("positive", "negative"))
        require(if(t.text("type")=="positive") points>0 else points<0)
        if (t.text("recoveryTitle").isNotBlank()) require(t.number("recoveryPoints")>0)
    }
    fun validate(events: List<JsonObject>) {
        require(events.map { it.text("id") }.distinct().size == events.size) { "Duplicate event ID." }
        require(events.isEmpty() || events.count { it.text("type") == "initial" } == 1) { "The ledger must have exactly one starting point." }
        events.forEach(::validateEvent)
        val seen = mutableSetOf<String>()
        events.filter { it.text("type") == "recovery" }.forEach { r ->
            val pull = events.find { it.text("id") == r.text("linkedEventId") }
            require(pull != null && pull.text("type") == "negative") { "Recovery has no matching Rut Pull." }
            require(!Instant.parse(r.text("timestamp")).isBefore(Instant.parse(pull.text("timestamp")))) { "Recovery precedes its Rut Pull." }
            require(seen.add(r.text("linkedEventId"))) { "A Rut Pull has more than one recovery." }
        }
    }
    fun score(events: List<JsonObject>) = events.sumOf { it.number("points").toLong() }
    fun stage(score: Long): String = when { score<=-751 -> "Deep Rut"; score<=-501 -> "Stuck"; score<=-251 -> "Fighting It"; score<=-1 -> "Climbing Out";score<=249 -> "Breaking Free";score<=499 -> "Momentum";score<=749 -> "Building My Life";score<=999 -> "Thriving";else -> "Out of the Rut" }
    fun recovered(events: List<JsonObject>) = events.filter { it.text("type")=="recovery" }.map { it.text("linkedEventId") }.toSet()
    fun period(events: List<JsonObject>, from: String, to: String) = events.filter { it.text("type")!="initial" && it.text("localDate") in from..to }
    fun averageRecoveryMinutes(events: List<JsonObject>): Double? = events.filter { it.text("type")=="recovery" && it.text("timeAccuracy")!="approximate" }.mapNotNull { r ->
        events.find { it.text("id")==r.text("linkedEventId") && it.text("timeAccuracy")!="approximate" }?.let { Duration.between(Instant.parse(it.text("timestamp")), Instant.parse(r.text("timestamp"))).toMinutes().toDouble() }
    }.takeIf { it.isNotEmpty() }?.average()
    fun missedDays(events: List<JsonObject>, reviewed: Set<String>): List<String> {
        val first = events.minOfOrNull { it.text("localDate") } ?: return emptyList()
        val recorded = events.filter { it.text("type")!="initial" }.map { it.text("localDate") }.toSet()
        return (1L..30L).map { LocalDate.now().minusDays(it).toString() }.filter { it>=first && it !in reviewed && it !in recorded }.take(14)
    }
}
object Habits {
    fun due(r: JsonObject, date: String): Boolean { val d=r.obj("data");return r.text("kind")=="Routine" && !d.flag("archived") && date>=d.text("startDate") && d.array("days").any { (it as? JsonPrimitive)?.intOrNull == LocalDate.parse(date).dayOfWeek.value%7 } }
    fun complete(all: List<JsonObject>, id: String, date: String) = all.any { it.text("kind")=="RoutineCompletion" && it.obj("data").let { d -> d.text("routineId")==id && d.text("date")==date && d.flag("completed") } }
    fun stats(r: JsonObject, all: List<JsonObject>, until: String = today()): Triple<Int,Int,Int> {
        var date=LocalDate.parse(until);val first=LocalDate.parse(r.obj("data").text("startDate"));var streak=0;var done=0;var missed=0;var broken=false
        val active=r.changed("data" to r.obj("data").changed("archived" to p(false)))
        while (!date.isBefore(first)) { val day=date.toString();if(due(active,day)){if(complete(all,r.text("id"),day)){done++;if(!broken)streak++}else if(day<until){missed++;broken=true}};date=date.minusDays(1) }
        return Triple(streak,done,missed)
    }
}
