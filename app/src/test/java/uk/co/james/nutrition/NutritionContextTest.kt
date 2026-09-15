package uk.co.james.nutrition

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NutritionContextTest {
    private val t=Instant.parse("2026-09-12T09:32:00Z")
    @Test fun mynetdiaryProvenanceIsOnlyShownWhenPackageProvesIt() {
        assertEquals("MyNetDiary via Health Connect", healthConnectNutritionSource("com.fourtechnologies.mynetdiary.android"))
        assertEquals("com.example.food", healthConnectNutritionSource("com.example.food"))
        assertEquals("Health Connect", healthConnectNutritionSource(null))
    }
    @Test fun missingNutrientsRemainUnknown() {
        val event=NutritionEvent("a","Health Connect",null,t,t,null,t,"day",null,800.0,null,null,null,null,null,null,null,null)
        val summary=nutritionSummary(listOf(event),emptyList())
        assertEquals(800.0,summary.energyKcal)
        assertNull(summary.proteinGrams)
        assertNull(summary.caffeineMg)
    }
    @Test fun hydrationIsSummedWithoutInventingATarget() {
        val one=HydrationEvent("a","Health Connect",null,t,t,null,t,"day",500.0)
        val two=HydrationEvent("b","Health Connect",null,t,t,null,t,"day",750.0)
        assertEquals(1250.0,nutritionSummary(emptyList(),listOf(one,two)).waterMl)
    }
    @Test fun multipleMealsWithMissingMacrosKeepThe1240KcalTotal() {
        val first=NutritionEvent("a","Samsung Health",null,t,t,null,t,"day",null,500.0,null,null,null,null,null,null,null,null)
        val second=NutritionEvent("b","Samsung Health",null,t,t,null,t,"day",null,740.0,null,null,null,null,null,null,null,null)
        val summary=nutritionSummary(listOf(first,second),emptyList())
        assertEquals(1240.0,summary.energyKcal)
        assertNull(summary.proteinGrams)
        assertNull(summary.carbsGrams)
        assertNull(summary.fatGrams)
    }
}
