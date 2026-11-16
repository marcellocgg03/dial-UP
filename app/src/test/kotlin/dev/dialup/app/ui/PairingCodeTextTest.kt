// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The TV code is shown in groups of three, the way the YouTube apps print it. */
class PairingCodeTextTest {
    @Test
    fun `a digit code is grouped by three`() {
        assertEquals("123 456 789 012", formatPairingCode("123456789012"))
        assertEquals("123 456 789", formatPairingCode("123456789"))
    }

    @Test
    fun `anything else is shown as it came`() {
        assertEquals("ABC123", formatPairingCode("ABC123"))
        assertEquals("12", formatPairingCode("12"))
        assertEquals("", formatPairingCode(""))
    }
}
