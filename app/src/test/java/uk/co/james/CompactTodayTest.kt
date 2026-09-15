package uk.co.james

import org.junit.Assert.*
import org.junit.Test
import uk.co.james.settings.EnergyTimeSettings
import uk.co.james.ui.compactCurrentSummary
import uk.co.james.ui.compactPromotions

class CompactTodayTest {
    @Test fun contradictoryStateGetsOneUsefulDeterministicSummary() {
        assertEquals(
            "You're fairly energised, but Sleepiness is high and your underlying reserves are limited.",
            compactCurrentSummary(liveEnergy=76,sleepiness=81,bodyBattery=42,mentalReserve=28)
        )
        assertNull(compactCurrentSummary(liveEnergy=55,sleepiness=22,bodyBattery=68,mentalReserve=70))
    }

    @Test fun promotedItemsAreStableBoundedAndQuietWhenLow() {
        val settings=EnergyTimeSettings()
        val extreme=compactPromotions(90,"HIGH",92,"VERY HIGH",52,"Work",true,32,settings)
        assertEquals(2,extreme.size)
        assertEquals(listOf("crash","pressure"),extreme.map {it.id})
        assertTrue(compactPromotions(12,"LOW",18,"VERY LOW",null,null,false,0,settings).isEmpty())
    }

    @Test fun disabledPresentationMetricsCannotBePromoted() {
        val settings=EnergyTimeSettings(crashRisk=false,timePressure=false)
        assertTrue(compactPromotions(100,"HIGH",100,"VERY HIGH",5,"Constraint",false,0,settings).isEmpty())
    }
}
