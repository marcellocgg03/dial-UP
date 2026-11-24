// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugApplicationTest {
    private fun frame(cls: String, method: String, file: String, line: Int) =
        StackTraceElement(cls, method, file, line)

    @Test
    fun aLineNamesTheKindAndAtMostThreeAppFrames() {
        val stack = listOf(
            frame("java.io.File", "exists", "File.java", 800),
            frame("android.os.StrictMode", "onReadFromDisk", "StrictMode.java", 1700),
            frame("dev.dialup.app.ShareLogActivity", "onCreate", "ShareLogActivity.kt", 25),
            frame("dev.t2u.upnp.AtomicFiles", "writeTextAtomic", "AtomicFiles.kt", 70),
            frame("android.app.Activity", "performCreate", "Activity.java", 8000),
            frame("dev.dialup.app.Settings", "update", "Settings.kt", 310),
            frame("dev.dialup.app.MainActivity", "onCreate", "MainActivity.kt", 99),
        )
        val line = strictModeLine("DiskReadViolation", stack)
        assertEquals(
            "DiskReadViolation ShareLogActivity.onCreate:25 AtomicFiles.writeTextAtomic:70 Settings.update:310",
            line,
        )
        assertTrue(line.startsWith("DiskReadViolation "))
        assertFalse(line.contains("java."), line)
        assertFalse(line.contains("android."), line)
        assertFalse(line.contains("MainActivity"), "only the first three app frames: $line")
    }

    @Test
    fun aStackWithNoAppFrameSaysSo() {
        val stack = listOf(
            frame("java.io.File", "exists", "File.java", 800),
            frame("android.os.Looper", "loop", "Looper.java", 300),
        )
        assertEquals("NetworkViolation no app frame", strictModeLine("NetworkViolation", stack))
        assertTrue(strictModeLine("NetworkViolation", emptyList()).endsWith("no app frame"))
    }

    @Test
    fun theLineHoldsNothingButTheKindAndFrames() {
        // A frame whose file name is a path: only class, method and line may reach the line.
        val stack = listOf(frame("dev.dialup.app.Settings", "update", "/data/user/0/dev.dialup.debug/files/settings.json", 12))
        val line = strictModeLine("DiskWriteViolation", stack)
        assertEquals("DiskWriteViolation Settings.update:12", line)
        assertFalse(line.contains("/"), line)
        assertFalse(line.contains("settings.json"), line)
    }
}
