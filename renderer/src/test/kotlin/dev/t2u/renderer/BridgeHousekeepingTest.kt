package dev.t2u.renderer

// D-06 and Phase 1 carry-over (d), plan 05-02: Bridge.housekeeping(trigger) re-announces in LAN mode only, says why it
// does not in phone mode, proves (or repairs) the responder's own SSDP listener and refreshes discovery. Same loopback
// rig as the other whole-bridge tests (BridgeHarness): no LAN, no device. GPL-3.0-only.

import dev.t2u.dial.DialAccessMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeHousekeepingTest {
    private val h = BridgeHarness()

    @AfterEach
    fun tearDown() {
        h.close()
    }

    private fun after(marker: Int, kind: String) = h.events.drop(marker).filter { it.first == kind }.map { it.second }

    @Test
    fun phoneModeSaysItSkippedTheAnnounceAndProbesTheListener() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("test"), "a run that ran returns true")

        val reannounce = after(marker, "reannounce").single()
        assertEquals("phone", reannounce["mode"])
        assertEquals("phone-only", reannounce["skipped"])
        assertEquals("test", reannounce["trigger"])
        val probe = after(marker, "ssdp-probe").single()
        assertEquals(true, probe["ok"])
        assertEquals("test", probe["trigger"])
        assertTrue(
            after(marker, "ssdp-tx").none { it["dest"] == "notify" },
            "phone mode never puts ssdp:alive on the wire: ${after(marker, "ssdp-tx")}",
        )
    }

    @Test
    fun lanModeSendsAliveForEveryReceiver() = h.run {
        h.accessMode = DialAccessMode.LAN
        val bridge = h.newBridge()
        bridge.start()
        h.launch(bridge)

        val marker = h.events.size
        assertTrue(bridge.housekeeping("test"))

        val reannounce = after(marker, "reannounce").single()
        assertEquals("lan", reannounce["mode"])
        assertEquals(1, reannounce["devices"])
        assertEquals("test", reannounce["trigger"])
        val alive = after(marker, "ssdp-tx").filter { it["dest"] == "notify" && it["nts"] == "ssdp:alive" }
        assertEquals(5, alive.size, "five DIAL notification types for the one receiver: $alive")
        assertEquals(true, after(marker, "ssdp-probe").single()["ok"])
    }

    @Test
    fun theTimerRunsHousekeepingByItselfAndEachRunSearchesForSpeakers() = h.run {
        val bridge = h.newBridge(housekeepingIntervalMs = 300)
        bridge.start()
        h.awaitUntil("two timer runs", timeoutMs = 2000) {
            h.events.count { it.first == "housekeeping" && it.second["trigger"] == "timer" && it.second["ran"] == true } >= 2
        }
        val runs = h.events.filter { it.first == "housekeeping" && it.second["trigger"] == "timer" && it.second["ran"] == true }
        assertTrue(runs.all { it.second["searched"] == true }, "every run searched: $runs")
    }

    @Test
    fun eachRunSendsOneMediaRendererSearch() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        h.awaitUntil("the start-up search") { h.searchesSeen.get() >= 1 }
        val before = h.searchesSeen.get()
        assertTrue(bridge.housekeeping("test"))
        h.awaitUntil("the housekeeping search") { h.searchesSeen.get() > before }
        val ran = h.events.last { it.first == "housekeeping" }.second
        assertEquals(true, ran["ran"])
        assertEquals(true, ran["searched"])
        assertEquals("test", ran["trigger"])
    }

    @Test
    fun aSecondTriggerRightAfterARunIsSkippedExceptAdb() = h.run {
        val now = AtomicLong(1_000_000L)
        val bridge = h.newBridge(clock = { now.get() })
        bridge.start()

        assertTrue(bridge.housekeeping("test"))
        now.addAndGet(5_000)
        assertFalse(bridge.housekeeping("alarm"), "an alarm tick 5 s after a run is a duplicate")
        val skipped = h.events.last { it.first == "housekeeping" }.second
        assertEquals("recent", skipped["skipped"])
        assertEquals(5_000L, skipped["ranAgoMs"])
        assertEquals("alarm", skipped["trigger"])

        assertTrue(bridge.housekeeping("adb"), "the adb trigger always runs")

        now.addAndGet(HOUSEKEEPING_INTERVAL_MS / 2 + 1)
        assertTrue(bridge.housekeeping("alarm"), "half an interval later it runs again")
    }

    @Test
    fun aStoppedBridgeDoesNothing() = h.run {
        val bridge = h.newBridge()
        bridge.start()
        bridge.stop()
        val marker = h.events.size
        assertFalse(bridge.housekeeping("test"))
        assertEquals(emptyList(), h.events.drop(marker))
    }
}
