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

    @Test fun handledTodayPreparationFailureRetainsStageMessageAndFirstJamesFrame() {
        val error=NullPointerException("current main sleep missing").apply {
            stackTrace=arrayOf(
                StackTraceElement("uk.co.james.state.SleepinessKt","sleepiness", "Sleepiness.kt", 126),
                StackTraceElement("outside.Library","run", "Library.kt", 12)
            )
        }
        val details=localDiagnosticDetails("today_preparation",error)
        assertEquals("today_preparation",details.stage)
        assertEquals("java.lang.NullPointerException",details.exceptionType)
        assertEquals("current main sleep missing",details.message)
        assertEquals("SleepinessKt.sleepiness:126",details.firstJamesFrame)
    }
}
