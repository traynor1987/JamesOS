package uk.co.james.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrimaryNavigationTest {
    @Test fun primaryNavigationMakesSettingsTheFifthTopLevelDestination() {
        assertEquals(
            listOf("Today", "Timeline", "Me", "Insights", "Settings"),
            JamesPrimaryNavigation.destinations.map { it.route }
        )
    }

    @Test fun novaRemainsOutsidePrimaryNavigation() {
        assertFalse(JamesPrimaryNavigation.destinations.any { it.route == "Nova" })
        assertTrue(JamesPrimaryNavigation.destinations.single { it.route == "Settings" }.settingsHome)
    }
}
