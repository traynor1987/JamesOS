package uk.co.james

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test
import uk.co.james.core.*
import uk.co.james.database.StoredRecord
import uk.co.james.state.*

class NutritionResponseTest {
    private val now=Instant.parse("2026-09-13T12:00:00Z")
    private fun row(kind:String,data:kotlinx.serialization.json.JsonObject,at:Instant,id:String)=
        StoredRecord.from("personalRecords",personal(kind,data,id,"health_connect",at.toString()).changed("updatedAt" to p(at.toString())))

    @Test fun waterAloneDoesNotCreateAResponse() {
        val water=row("Hydration",fields("volumeMl" to p(500)),now.minus(Duration.ofMinutes(45)),"water")
        assertEquals(NutritionResponseConfidence.NO_EVIDENCE,nutritionResponseEvidence(listOf(water),now).confidence)
    }

    @Test fun observedImprovementAfterWaterIsPossibleNotCausation() {
        val low=row("WellbeingCheckIn",fields("energy" to p("LOW")),now.minus(Duration.ofMinutes(90)),"low")
        val water=row("Hydration",fields("volumeMl" to p(500)),now.minus(Duration.ofMinutes(55)),"water")
        val high=row("WellbeingCheckIn",fields("energy" to p("HIGH")),now.minus(Duration.ofMinutes(20)),"high")
        val evidence=nutritionResponseEvidence(listOf(low,water,high),now)
        assertEquals(NutritionResponseConfidence.POSSIBLE,evidence.confidence)
        assertEquals("hydration",evidence.kind)
        val reserve=mentalWellbeing(listOf(low,water,high),clock=now,zone=ZoneOffset.UTC).reserve
        val response=reserve.contributors.single {it.source=="Hydration response"}
        assertEquals(1.0,response.contribution,.01)
        assertTrue(response.explanation.contains("not proof of causation"))
    }
}