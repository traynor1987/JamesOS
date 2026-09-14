package uk.co.james.nutrition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
internal fun JsonObject.text(key:String):String=(get(key) as? JsonPrimitive)?.content.orEmpty()
