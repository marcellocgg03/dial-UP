package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryFactsTest {
    @Test
    fun anExitInfoCarriesTheMemoryTheProcessHeldInMegabytes() {
        val f = exitInfoFields(3, 0, 1000L, "low mem", 400, pssKb = 307_200, rssKb = 512_000)
        assertEquals(3, f["reason"])
        assertEquals(400, f["importance"])
        assertEquals(300L, f["pssMb"])
        assertEquals(500L, f["rssMb"])
    }

    @Test
    fun anExitInfoLeavesOutMemoryTheSystemDidNotRecord() {
        val f = exitInfoFields(10, 0, 1000L, null, 100, pssKb = 0, rssKb = -1)
        assertFalse("pssMb" in f)
        assertFalse("rssMb" in f)
        assertTrue("reason" in f)
    }

    @Test
    fun theHeartbeatMemoryFieldsAreInMegabytes() {
        val mb = 1024L * 1024L
        val f = memoryFields(
            availBytes = 800 * mb, totalBytes = 8192 * mb, thresholdBytes = 300 * mb, lowMemory = true,
            javaUsedBytes = 40 * mb, javaMaxBytes = 512 * mb, nativeBytes = 90 * mb, pssKb = 204_800,
        )
        assertEquals(
            mapOf(
                "memAvailMb" to 800L, "memTotalMb" to 8192L, "memThresholdMb" to 300L, "memLow" to true,
                "javaUsedMb" to 40L, "javaMaxMb" to 512L, "nativeMb" to 90L, "pssMb" to 200L,
            ),
            f,
        )
    }

    @Test
    fun trimLevelsHaveNamesAndAnUnknownOneIsItsNumber() {
        assertEquals("RUNNING_CRITICAL", trimLevelName(15))
        assertEquals("BACKGROUND", trimLevelName(40))
        assertEquals("COMPLETE", trimLevelName(80))
        assertEquals("7", trimLevelName(7))
    }
}
