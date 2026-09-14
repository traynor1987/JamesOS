package uk.co.james.nutrition

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import uk.co.james.core.*
import java.time.Instant

/** Boundary mapper: unavailable source fields remain null, never zero. */
fun nutritionRecord(id:String,provider:String?,start:Instant,end:Instant,modified:Instant?,jamesDayId:String,mealType:String?,values:Map<String,Double?>):JsonObject =
 personal("NutritionEvent",fields("title" to p(mealType?.replaceFirstChar {it.uppercase()}?:"Meal"),"date" to p(dayOf(end.toString())),"start" to p(start.toString()),"end" to p(end.toString()),"jamesDayId" to p(jamesDayId),"mealType" to (mealType?.let(::p)?:JsonNull),"provider" to p(healthConnectNutritionSource(provider)),"sourcePackage" to (provider?.let(::p)?:JsonNull),"energyKcal" to (values["energyKcal"]?.let(::p)?:JsonNull),"carbsGrams" to (values["carbsGrams"]?.let(::p)?:JsonNull),"proteinGrams" to (values["proteinGrams"]?.let(::p)?:JsonNull),"fatGrams" to (values["fatGrams"]?.let(::p)?:JsonNull),"saturatedFatGrams" to (values["saturatedFatGrams"]?.let(::p)?:JsonNull),"sugarGrams" to (values["sugarGrams"]?.let(::p)?:JsonNull),"fibreGrams" to (values["fibreGrams"]?.let(::p)?:JsonNull),"sodiumMg" to (values["sodiumMg"]?.let(::p)?:JsonNull),"caffeineMg" to (values["caffeineMg"]?.let(::p)?:JsonNull),"ingestedAt" to p(now()),"lastModified" to (modified?.let {p(it.toString())}?:JsonNull)),"hc:nutrition:$id","health_connect",end.toString()).changed("externalId" to p(id),"updatedAt" to p((modified?:end).toString()),"metadata" to fields("dataOrigin" to (provider?.let(::p)?:JsonNull),"transport" to p("Health Connect")))

fun hydrationRecord(id:String,provider:String?,start:Instant,end:Instant,modified:Instant?,jamesDayId:String,volumeMl:Double):JsonObject =
 personal("HydrationEvent",fields("title" to p("Hydration"),"date" to p(dayOf(end.toString())),"start" to p(start.toString()),"end" to p(end.toString()),"jamesDayId" to p(jamesDayId),"volumeMl" to p(volumeMl),"provider" to p(healthConnectNutritionSource(provider)),"sourcePackage" to (provider?.let(::p)?:JsonNull),"ingestedAt" to p(now()),"lastModified" to (modified?.let {p(it.toString())}?:JsonNull)),"hc:hydration:$id","health_connect",end.toString()).changed("externalId" to p(id),"updatedAt" to p((modified?:end).toString()))