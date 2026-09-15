package uk.co.james.core

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.time.*
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; isLenient = false }
fun JsonObject.text(key: String, fallback: String = ""): String = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.number(key: String, fallback: Double = 0.0): Double = (get(key) as? JsonPrimitive)?.doubleOrNull ?: fallback
fun JsonObject.flag(key: String, fallback: Boolean = false): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback
fun JsonObject.obj(key: String): JsonObject = get(key) as? JsonObject ?: JsonObject(emptyMap())
fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
fun fields(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))
fun JsonObject.changed(vararg pairs: Pair<String, JsonElement>) = JsonObject(this + pairs.toMap())
fun p(value: String) = JsonPrimitive(value)
fun p(value: Number) = JsonPrimitive(value)
fun p(value: Boolean) = JsonPrimitive(value)
fun now(): String = Instant.now().toString()
fun today(): String = LocalDate.now().toString()
fun id(): String = UUID.randomUUID().toString()
fun dayOf(timestamp: String): String = Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate().toString()
fun validTime(value: String) = runCatching { Instant.parse(value) }.isSuccess
fun validDate(value: String) = runCatching { LocalDate.parse(value) }.isSuccess
fun canonical(value: JsonElement): String = when (value) {
    is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { p(it.key).toString() + ":" + canonical(it.value) }
    is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
    else -> value.toString()
}
fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun personal(kind: String, data: JsonObject, recordId: String = id(), source: String = "manual", timestamp: String = now()): JsonObject = fields(
    "id" to p(recordId), "kind" to p(kind), "data" to data, "source" to p(source), "timestamp" to p(timestamp),
    "createdAt" to p(now()), "updatedAt" to p(now()), "confidence" to JsonNull, "metadata" to fields()
)
val rutStores = listOf("settings", "eventTemplates", "loggedEvents", "dailyNotes", "milestones", "metadata")
val backupStores = rutStores + listOf("personalRecords","archives")
fun keyFor(store: String, raw: JsonObject) = raw.text(when (store) { "settings", "metadata" -> "key"; "dailyNotes" -> "date"; else -> "id" })
