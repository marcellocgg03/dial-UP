// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.dialup.app.model.UpdateRecord
import dev.dialup.app.model.cardRelease
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** The notice is driven by [cardRelease]; the job ids must stay apart because they are persisted OS state. */
class UpdateNotifierTest {
    private val record = UpdateRecord(1, 1, "v1.2.0", "https://github.com/x/y/releases/tag/v1.2.0")

    @Test
    fun aNewerRecordedReleaseGivesTheTagToTellAndAnOlderOneNothing() {
        assertEquals("v1.2.0", cardRelease(true, record, "1.1.0")?.tag)
        assertNull(cardRelease(true, record, "1.2.0"))
        assertNull(cardRelease(false, record, "1.1.0"))
    }

    @Test
    fun theTwoDailyJobsHaveDifferentIds() {
        assertNotEquals(YTDLP_JOB_ID, UPDATE_JOB_ID)
    }
}
