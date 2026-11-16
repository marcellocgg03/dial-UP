// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The words of the manual pairing feature: the menu item, the dialog and the hint in the Add renderer prompt stay in step. */
class PairingTextsTest {
    @Test
    fun `the add by address hint names the menu item the card really has`() {
        assertEquals("Pair manually with code", PAIR_MENU_LABEL)
        assertTrue(PAIR_MENU_LABEL in ADD_BY_ADDRESS_PAIR_HINT, ADD_BY_ADDRESS_PAIR_HINT)
        assertTrue("YouTube/YouTube Music still cannot discover the renderer" in ADD_BY_ADDRESS_PAIR_HINT)
    }

    @Test
    fun `the dialog says when it is useful`() {
        assertTrue("blocks YouTube/YouTube Music from discovering renderers" in PAIR_DESCRIPTION)
    }

    @Test
    fun `each code is labelled with its own app`() {
        assertEquals("YouTube code", pairingCodeLabel("YouTube"))
        assertEquals("YouTube Music code", pairingCodeLabel("YouTube Music"))
    }

    @Test
    fun `the clipboard gets the digits alone`() {
        assertEquals("123456789012", pairingCodeForClipboard("123 456 789 012"))
        assertEquals("123456789012", pairingCodeForClipboard("123456789012"))
        assertFalse(pairingCodeForClipboard(formatPairingCode("123456789012")).contains(' '))
    }
}
