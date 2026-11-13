// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.ui

import dev.t2u.renderer.ManualAddResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** GUI-04 (plan 07-12): every way an add by address can end has its own line in the dialog. */
class AddSpeakerTextTest {
    @Test
    fun `each outcome says what happened`() {
        assertEquals("That is not a valid IP address on this LAN", manualResultText(null, "speaker.local"))
        assertEquals("That is not an address on this Wi-Fi", manualResultText(ManualAddResult.Refused, "8.8.8.8"))
        assertEquals("Added Kitchen", manualResultText(ManualAddResult.Added("u", "Kitchen"), "192.168.1.40"))
        assertEquals("Kitchen is already listed", manualResultText(ManualAddResult.AlreadyListed("u", "Kitchen"), "192.168.1.40"))
        assertEquals("That renderer is hidden; restore it from Hidden", manualResultText(ManualAddResult.Hidden("u"), "192.168.1.40"))
        assertEquals("No answer from 192.168.1.99", manualResultText(ManualAddResult.NoAnswer, "192.168.1.99"))
        assertEquals("because", manualResultText(ManualAddResult.NotUsable("because"), "192.168.1.40"))
    }
}
