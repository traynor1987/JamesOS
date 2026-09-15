package uk.co.james.state

import java.time.Duration
import java.time.Instant
import uk.co.james.database.StoredRecord
import uk.co.james.core.*

/**
 * Personal, deliberately conservative observation reconstruction. A logged
 * drink or meal is never a score input by itself: it becomes a possible
 * response only when James has separately reported a higher Energy value.
 */
enum class NutritionResponseConfidence { NO_EVIDENCE, POSSIBLE, REPEATED_ASSOCIATION, PERSONALISED_PATTERN }

data class NutritionResponseEvidence(
    val confidence:NutritionResponseConfidence,
    val observations:Int=0,
    val latestAt:String?=null,
    val kind:String?=null
)

private fun StoredRecord.instant()=runCatching { Instant.parse(timestamp) }.getOrNull()
private fun energyValue(value:String)=when(value.uppercase()) {
    "VERY LOW"->1; "LOW"->2; "OKAY", "MODERATE"->3; "HIGH"->4; "VERY HIGH"->5; else->null
}

fun nutritionResponseEvidence(records:List<StoredRecord>,clock:Instant=Instant.now()):NutritionResponseEvidence {
    val reports=records.filter {it.kind=="WellbeingCheckIn"}.mapNotNull {row->
        val at=row.instant(); val value=energyValue(row.data().text("energy"))
        if(at!=null&&value!=null&&at<=clock) Triple(at,value,row) else null
    }.sortedBy {it.first}
    var meal=0; var water=0; var latest:Instant?=null; var latestKind:String?=null
    reports.forEachIndexed {index, after->
        val before=reports.subList(0,index).lastOrNull {
            Duration.between(it.first,after.first) in Duration.ofMinutes(15)..Duration.ofHours(4)
        }?:return@forEachIndexed
        if(after.second<=before.second)return@forEachIndexed
        val context=records.filter {it.kind in setOf("Nutrition","NutritionEvent","Hydration","HydrationEvent")}
            .filter {event->event.instant()?.let {it in before.first..after.first}==true}
        if(context.any {it.kind in setOf("Hydration","HydrationEvent")}) { water++; latest=after.first; latestKind="hydration" }
        else if(context.any {it.kind in setOf("Nutrition","NutritionEvent")}) { meal++; latest=after.first; latestKind="nutrition" }
    }
    val count=meal+water
    val confidence=when {
        count>=8->NutritionResponseConfidence.PERSONALISED_PATTERN
        count>=3->NutritionResponseConfidence.REPEATED_ASSOCIATION
        count>0->NutritionResponseConfidence.POSSIBLE
        else->NutritionResponseConfidence.NO_EVIDENCE
    }
    return NutritionResponseEvidence(confidence,count,latest?.toString(),latestKind)
}