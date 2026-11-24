// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.t2u.media.YtDlpRecord
import dev.t2u.media.YtDlpRecordStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class LazyYtDlpRecordStoreTest {
    private class Counting : YtDlpRecordStore {
        var saved = YtDlpRecord(1L, 2L)
        var loads = 0
        var saves = 0

        override fun load(): YtDlpRecord {
            loads++
            return saved
        }

        override fun save(record: YtDlpRecord) {
            saves++
            saved = record
        }
    }

    @Test
    fun buildingItFindsNoStore() {
        // CastFeature.start builds it on the service's main thread; finding the store looks up the files directory
        var resolved = 0
        LazyYtDlpRecordStore { resolved++; Counting() }
        assertEquals(0, resolved)
    }

    @Test
    fun theStoreIsFoundOnFirstUseAndOnlyOnce() {
        var resolved = 0
        val inner = Counting()
        val lazy = LazyYtDlpRecordStore { resolved++; inner }
        assertEquals(YtDlpRecord(1L, 2L), lazy.load())
        lazy.save(YtDlpRecord(5L, 7L))
        assertEquals(YtDlpRecord(5L, 7L), lazy.load())
        assertEquals(1, resolved)
        assertEquals(2, inner.loads)
        assertEquals(1, inner.saves)
    }

    @Test
    fun aFirstSaveFindsTheStoreToo() {
        var resolved = 0
        val inner = Counting()
        LazyYtDlpRecordStore { resolved++; inner }.save(YtDlpRecord(9L, 9L))
        assertEquals(1, resolved)
        assertEquals(YtDlpRecord(9L, 9L), inner.saved)
    }
}
