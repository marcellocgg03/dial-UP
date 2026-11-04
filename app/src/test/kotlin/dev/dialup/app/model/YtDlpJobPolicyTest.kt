// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class YtDlpJobPolicyTest {
    @Test
    fun aBridgeSwitchedOffSkipsWhateverElseIsTrue() {
        for (serviceUp in listOf(true, false)) {
            for (casting in listOf(0, 1, 3)) {
                assertEquals(YtDlpJobAction.SKIP_OFF, ytdlpJobAction(false, serviceUp, casting))
            }
        }
    }

    @Test
    fun aCastInProgressPostponesTheJob() {
        assertEquals(YtDlpJobAction.POSTPONE_CASTING, ytdlpJobAction(true, true, 1))
        assertEquals(YtDlpJobAction.POSTPONE_CASTING, ytdlpJobAction(true, true, 2))
    }

    @Test
    fun aRunningIdleServiceUsesTheInBridgeUpdater() {
        assertEquals(YtDlpJobAction.IN_BRIDGE, ytdlpJobAction(true, true, 0))
    }

    @Test
    fun noServiceMeansTheOfflineUpdater() {
        assertEquals(YtDlpJobAction.OFFLINE, ytdlpJobAction(true, false, 0))
        assertEquals(YtDlpJobAction.OFFLINE, ytdlpJobAction(true, false, 2))
    }

    @Test
    fun wireNamesAreStable() {
        assertEquals(listOf("off", "casting", "in-bridge", "offline"), YtDlpJobAction.entries.map { it.wire })
    }
}
