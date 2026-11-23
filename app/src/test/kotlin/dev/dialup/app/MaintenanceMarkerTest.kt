// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaintenanceMarkerTest {
    @TempDir
    lateinit var dir: File

    private fun crumb() = File(dir, "bridge-maintenance")

    @Test
    fun beginWritesTheBreadcrumbAndEndClearsIt() {
        val state = MaintenanceState(crumb())
        assertFalse(state.isActive)
        state.begin()
        assertTrue(state.isActive)
        assertTrue(crumb().exists())
        assertTrue(state.end())
        assertFalse(state.isActive)
        assertFalse(crumb().exists())
    }

    @Test
    fun overlappingWorkStaysActiveUntilTheLastEnd() {
        val state = MaintenanceState(crumb())
        state.begin()
        state.begin()
        assertFalse(state.end(), "the first end is not the last")
        assertTrue(state.isActive)
        assertTrue(crumb().exists())
        assertTrue(state.end(), "the last end says so")
        assertFalse(state.isActive)
        assertFalse(crumb().exists())
    }

    @Test
    fun aBreadcrumbFromAnEarlierProcessIsClearedAndReported() {
        crumb().writeText("1")
        val state = MaintenanceState(crumb())
        assertTrue(state.clearStale())
        assertFalse(crumb().exists())
        assertFalse(state.isActive, "a trace is never a gate")
        assertFalse(state.clearStale())
    }

    @Test
    fun anUnwritableBreadcrumbNeverBlocksOrThrows() {
        val parentIsAFile = File(dir, "plain").also { it.writeText("x") }
        val state = MaintenanceState(File(parentIsAFile, "bridge-maintenance"))
        state.begin()
        assertTrue(state.isActive, "the in-memory gate holds without the file")
        state.end()
        assertFalse(state.isActive)
    }

    @Test
    fun anEndWithoutABeginIsHarmless() {
        val state = MaintenanceState(crumb())
        assertFalse(state.end())
        assertFalse(state.isActive)
        state.begin()
        assertTrue(state.isActive, "the count never went below zero")
        assertEquals(true, state.end())
    }
}
