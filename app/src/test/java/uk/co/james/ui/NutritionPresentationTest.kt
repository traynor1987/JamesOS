package uk.co.james.ui

import java.time.Instant
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.james.core.changed
import uk.co.james.core.fields
import uk.co.james.core.p
import uk.co.james.core.personal
import uk.co.james.database.StoredRecord
import uk.co.james.time.JamesDayWindow

class NutritionPresentationTest {
    private val clock=Instant.parse("2026-09-13T23:28:00Z")
    private val day=JamesDayWindow(
        id="james-day:1757759400000",
        start=Instant.parse("2026-09-13T07:50:00Z"),
        end=clock,
        acceptedMainSleepId="main-sleep",
        acceptedMainSleepStart=Instant.parse("2026-09-12T23:58:00Z"),
        whoopCycleId=null,
        displayDate="2026-09-13"
    )

    private fun meal(id:String,at:Instant,kcal:Double)=StoredRecord.from(
        "personalRecords",
        personal(
            "NutritionEvent",
            fields(
                "jamesDayId" to p(day.id),
                "energyKcal" to p(kcal),
                "proteinGrams" to JsonNull,
                "carbsGrams" to JsonNull,
                "fatGrams" to JsonNull
            ),
            id,
            "health_connect",
            at.toString()
        ).changed("updatedAt" to p(at.toString()))
    )

    @Test fun currentJamesDayUsesPersistedNutritionOwnershipAndKeepsPartialMacrosUnknown() {
        // The first source timestamp has been normalised before the recorded
        // sleep boundary, but ingestion already resolved both meals to this
        // accepted James Day. Presentation must honour that owner.
        val result=nutritionToday(
            listOf(
                meal("breakfast",Instant.parse("2026-09-13T07:45:00Z"),500.0),
                meal("lunch",Instant.parse("2026-09-13T12:30:00Z"),740.0)
            ),
            day,
            clock
        )

        assertEquals(1240.0,result.calories ?: Double.NaN,0.001)
        assertEquals(2,result.meals.size)
        assertNull(result.protein)
        assertNull(result.carbs)
        assertNull(result.fat)
    }
}
