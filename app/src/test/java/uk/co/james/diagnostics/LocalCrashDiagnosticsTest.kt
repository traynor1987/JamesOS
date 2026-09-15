package uk.co.james.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCrashDiagnosticsTest {
    @Test fun keepsOnlyJamesFramesAndNeverSerialisesThrowablePayloads() {
        val error=IllegalStateException("bad route state").apply {
            stackTrace=arrayOf(
                StackTraceElement("uk.co.james.ui.JamesRootKt","JamesRoot", "JamesRoot.kt", 31),
                StackTraceElement("outside.Library","run", "Library.kt", 12)
            )
        }
        val frames=jamesStackFrames(error)
        assertEquals(listOf("JamesRootKt.JamesRoot:31"),frames)
        assertTrue(frames.none {it.contains("outside")})
    }
}
