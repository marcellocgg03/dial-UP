// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.t2u.media.YtDlpRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YtDlpRecordFileTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun aSavedRecordLoadsBackAndLeavesNoTempFile() {
        val store = YtDlpRecordFile(dir)
        store.save(YtDlpRecord(5L, 7L))
        assertEquals(YtDlpRecord(5L, 7L), store.load())
        assertTrue(File(dir, "ytdlp-update.json").isFile)
        assertFalse(File(dir, "ytdlp-update.json.tmp").exists())
        assertEquals(listOf("ytdlp-update.json"), dir.list()!!.toList())
    }

    @Test
    fun aMissingFileLoadsAsAnEmptyRecord() {
        assertEquals(YtDlpRecord(null, null), YtDlpRecordFile(dir).load())
    }

    @Test
    fun garbageLoadsAsAnEmptyRecordWithoutThrowing() {
        File(dir, "ytdlp-update.json").writeText("garbage")
        assertEquals(YtDlpRecord(null, null), YtDlpRecordFile(dir).load())
    }

    @Test
    fun aSaveIntoAnUnwritablePathDoesNotThrow() {
        val file = File(dir, "plain")
        file.writeText("x")
        YtDlpRecordFile(File(file, "sub")).save(YtDlpRecord(1L, 2L))
    }

    @Test
    fun theProcessWideRecordIsOneInstancePerCall() {
        assertSame(YtDlpRecords.of(dir), YtDlpRecords.of(dir))
    }
}
