package uk.co.james.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HealthCapabilitiesTest {
    @Test fun every_code_read_capability_has_one_runtime_permission_and_manifest_declaration() {
        assertEquals(HealthCapabilities.all.size,HealthCapabilities.all.map {it.label}.toSet().size)
        assertEquals(HealthCapabilities.all.size,HealthCapabilities.all.map {it.readPermission}.toSet().size)
        val manifest=File("src/main/AndroidManifest.xml").readText()
        HealthCapabilities.all.forEach {capability->
            assertTrue("Missing manifest permission for ${capability.label}",manifest.contains(capability.manifestPermission))
        }
    }
}
