package uk.co.james.ui

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class SettingsNavigationTest {
    @Test fun directoryHasEverySettingsDomainAndAUniqueRoute() {
        val destinations=JamesSettingsNavigation.destinations
        assertEquals(setOf("display","connections","watch","places","james-os","backup","updates","diagnostics"),destinations.map {it.id}.toSet())
        assertEquals(destinations.size,destinations.map {it.route}.toSet().size)
    }

    @Test fun providerAndRestoreManagementKeepTheirSingleAuthoritativeDestinations() {
        val byId=JamesSettingsNavigation.destinations.associateBy {it.id}
        assertEquals("Connections",byId.getValue("connections").route)
        assertEquals("Import Centre",byId.getValue("backup").route)
        assertTrue("whoop" in byId.getValue("connections").keywords)
        assertTrue("restore" in byId.getValue("backup").keywords)
    }

    @Test fun settingsDetailsAreRecognisedForBackNavigation() {
        assertTrue(JamesSettingsNavigation.isSettingsDetail("Settings:Watch"))
        assertTrue(JamesSettingsNavigation.isSettingsDetail("Connections"))
        assertTrue(JamesSettingsNavigation.isSettingsDetail("Algorithm:body_battery"))
    }
}
