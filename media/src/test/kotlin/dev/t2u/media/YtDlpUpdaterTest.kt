package dev.t2u.media

// The background yt-dlp update schedule (PLAY-02, quick 261028-ago). GPL-3.0-only.

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YtDlpUpdaterTest {
    private class FakeGateway : YtDlpGateway {
        val updates = CopyOnWriteArrayList<String>()
        val updateThreads = CopyOnWriteArrayList<String>()
        @Volatile var failure: ((Int) -> Throwable?) = { null }
        @Volatile var versionFailure: Throwable? = null
        @Volatile var current = "2026.01.01"
        @Volatile var latest: String? = null
        @Volatile var latestFailure: Throwable? = null
        val latestChannels = CopyOnWriteArrayList<String>()

        override fun latestVersion(channel: String): String? {
            latestChannels += channel
            latestFailure?.let { throw it }
            return latest
        }

        override fun run(call: YtDlpCall): GatewayResult = error("not used")

        override fun version(): String? {
            versionFailure?.let { throw it }
            return current
        }

        /** Plan 07-09: runs at the start of every update, so a test can hold the gateway busy. */
        @Volatile var onUpdate: (() -> Unit)? = null

        override fun update(channel: String): String {
            updateThreads += Thread.currentThread().name
            updates += channel
            onUpdate?.invoke()
            failure(updates.size)?.let { throw it }
            current = "2026.02.02"
            return "DONE"
        }
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "yt-dlp-updater-test") }
    private val dispatcher = executor.asCoroutineDispatcher()

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    /** Records the waits and cancels the loop once [stopAfter] pauses were requested. */
    private class Pauses(private val stopAfter: Int) {
        val waits = CopyOnWriteArrayList<Long>()
        val pause: suspend (Long) -> Unit = {
            waits += it
            if (waits.size >= stopAfter) throw CancellationException("test over")
        }
    }

    private fun updater(
        gateway: FakeGateway,
        pauses: Pauses,
        events: MutableList<Map<String, Any?>> = CopyOnWriteArrayList(),
        channel: () -> String = { "stable" },
        onEvent: (Map<String, Any?>) -> Unit = { events += it },
    ) = YtDlpUpdater(gateway, channel, onEvent, pause = pauses.pause, dispatcher = dispatcher)

    private fun runLoop(u: YtDlpUpdater) = runBlocking {
        try {
            u.run()
        } catch (_: CancellationException) {
            // the test's pause ended it
        }
    }

    @Test
    fun theFirstWaitIsTheStartDelayThenTheDailyOne() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(updater(g, pauses, events))
        assertEquals(listOf(30_000L, 24L * 3600 * 1000, 24L * 3600 * 1000), pauses.waits.toList())
        assertEquals(listOf("stable", "stable"), g.updates.toList())
        assertEquals(listOf("start", "scheduled"), events.map { it["trigger"] })
        val first = events.first()
        assertEquals(true, first["ok"])
        assertEquals("2026.01.01", first["before"])
        assertEquals("2026.02.02", first["after"])
        assertEquals("DONE", first["status"])
        assertEquals("stable", first["channel"])
        assertTrue(first["ms"] is Long)
        assertNull(first["error"])
    }

    @Test
    fun aFailureKeepsTheSignedUrlOutAndRetriesAfterAnHourThenGoesBackToDaily() {
        val g = FakeGateway()
        g.failure = { n ->
            if (n == 1) {
                java.io.IOException("download of https://github.com/yt-dlp/releases/download/x/yt-dlp?token=SECRETSECRET failed " + "x".repeat(500))
            } else {
                null
            }
        }
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(updater(g, pauses, events))
        assertEquals(listOf(30_000L, 3600_000L, 24L * 3600 * 1000), pauses.waits.toList())
        val failed = events.first()
        assertEquals(false, failed["ok"])
        assertEquals("FAILED", failed["status"])
        val error = failed["error"] as String
        assertTrue(error.contains("github.com"), error)
        assertFalse(error.contains("SECRET"), error)
        assertFalse(error.contains("/releases/"), error)
        assertTrue(error.length <= 300)
        assertEquals(true, events[1]["ok"])
    }

    @Test
    fun theChannelIsReadBeforeEveryAttemptAndAnInvalidOneFallsBackToStable() {
        val g = FakeGateway()
        val values = ArrayDeque(listOf("stable", "nightly", "bogus", "THROW"))
        val pauses = Pauses(stopAfter = 5)
        runLoop(
            updater(g, pauses, channel = {
                val v = values.removeFirst()
                if (v == "THROW") throw IllegalStateException("no config") else v
            }),
        )
        assertEquals(listOf("stable", "nightly", "stable", "stable"), g.updates.toList())
    }

    @Test
    fun theGatewayRunsOnTheInjectedDispatcher() {
        val g = FakeGateway()
        runLoop(updater(g, Pauses(stopAfter = 2)))
        // the coroutine debug agent may append " @coroutine#N" to the thread name
        assertEquals(1, g.updateThreads.size)
        assertTrue(g.updateThreads.single().startsWith("yt-dlp-updater-test"), g.updateThreads.toString())
    }

    @Test
    fun cancellingWhileItWaitsEndsItWithoutAnUpdate() {
        val g = FakeGateway()
        runLoop(updater(g, Pauses(stopAfter = 1)))
        assertTrue(g.updates.isEmpty())
    }

    @Test
    fun aThrowingSinkAndAThrowingVersionDoNotStopTheLoop() {
        val g = FakeGateway()
        g.versionFailure = IllegalStateException("no python yet")
        val seen = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 3)
        runLoop(
            updater(g, pauses, onEvent = {
                seen += it
                throw IllegalStateException("sink broke")
            }),
        )
        assertEquals(2, g.updates.size)
        assertNull(seen.first()["before"])
        assertEquals(true, seen.first()["ok"])
    }

    // ----- plan 05-11: elapsed clock, runIfDue, busy postponement ----------------------------------

    /** A hand-advanced clock; [pause] adds its argument to it, like real time passing while the loop waits. */
    private class Clock(private val stopAfter: Int = Int.MAX_VALUE) {
        @Volatile var now = 0L
        val waits = CopyOnWriteArrayList<Long>()
        val pause: suspend (Long) -> Unit = {
            waits += it
            if (waits.size >= stopAfter) throw CancellationException("test over")
            now += it
        }
    }

    private fun clocked(
        g: FakeGateway,
        clock: Clock,
        events: MutableList<Map<String, Any?>> = CopyOnWriteArrayList(),
        casting: () -> Boolean = { false },
        store: YtDlpRecordStore? = null,
        wall: () -> Long = { WALL },
        busy: () -> Boolean = { false },
    ) = YtDlpUpdater(
        g,
        { "stable" },
        { events += it },
        pause = clock.pause,
        dispatcher = dispatcher,
        elapsedMs = { clock.now },
        maxPauseMs = 900_000L,
        busy = busy,
        casting = casting,
        record = store,
        wallMs = wall,
    )

    private class MemoryStore(var value: YtDlpRecord = YtDlpRecord()) : YtDlpRecordStore {
        val saves = CopyOnWriteArrayList<YtDlpRecord>()
        override fun load(): YtDlpRecord = value
        override fun save(record: YtDlpRecord) {
            saves += record
            value = record
        }
    }

    @Test
    fun withAnElapsedClockTheLoopPausesInStepsOfAtMostFifteenMinutes() {
        val g = FakeGateway()
        val clock = Clock(stopAfter = 5)
        runLoop(clocked(g, clock))
        // 30 s start delay, the update, then the 24 h interval in 15 minute steps
        assertEquals(listOf(30_000L, 900_000L, 900_000L, 900_000L, 900_000L), clock.waits.toList())
        assertEquals(1, g.updates.size)
    }

    @Test
    fun aDeepSleepInsideOnePauseMakesTheNextCheckUpdateAtOnce() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock(stopAfter = 4)
        var slept = false
        val u = YtDlpUpdater(
            g, { "stable" }, { events += it },
            pause = { ms ->
                clock.pause(ms)
                // the phone sleeps through 25 hours during the second pause
                if (clock.waits.size == 2 && !slept) {
                    slept = true
                    clock.now += 25L * 3600 * 1000
                }
            },
            dispatcher = dispatcher,
            elapsedMs = { clock.now },
            maxPauseMs = 900_000L,
        )
        runLoop(u)
        // pauses: 30 s, 15 min (slept 25 h inside it), then at once the second update, then 15 min again
        assertEquals(2, g.updates.size)
        assertEquals(listOf("start", "scheduled"), events.map { it["trigger"] })
        assertEquals(listOf(30_000L, 900_000L, 900_000L), clock.waits.take(3))
    }

    @Test
    fun runIfDueReturnsNullBeforeRunBeginsAndBeforeTheDeadline() {
        val g = FakeGateway()
        val clock = Clock()
        val u = YtDlpUpdater(
            g, { "stable" }, {},
            pause = { throw CancellationException("stop at the first pause") },
            dispatcher = dispatcher,
            elapsedMs = { clock.now },
        )
        assertNull(runBlocking { u.runIfDue() })
        // run() sets its deadline (30 s away) and stops at its first pause
        runLoop(u)
        assertNull(runBlocking { u.runIfDue() })
        assertTrue(g.updates.isEmpty())
    }

    @Test
    fun anAlarmRunAfterTheDeadlineUpdatesOnceAndTheLoopDoesNotRepeatIt() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock()
        var viaAlarm: YtDlpUpdateOutcome? = null
        var calls = 0
        lateinit var u: YtDlpUpdater
        u = YtDlpUpdater(
            g, { "stable" }, { events += it },
            pause = { ms ->
                calls++
                if (calls == 1) {
                    // inside the first pause the deadline passes and the alarm's tick runs the update
                    clock.now += ms
                    viaAlarm = u.runIfDue()
                } else {
                    // the loop wakes for the same deadline and must find it handled
                    throw CancellationException("test over")
                }
            },
            dispatcher = dispatcher,
            elapsedMs = { clock.now },
            maxPauseMs = 900_000L,
        )
        runLoop(u)
        assertEquals(1, g.updates.size)
        assertEquals(true, viaAlarm?.ok)
        assertEquals(listOf("start"), events.map { it["trigger"] })
    }

    @Test
    fun aBusyLookupPostponesTheUpdateInStepsAndLogsItOnce() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock(stopAfter = 6)
        var busyChecks = 0
        runLoop(clocked(g, clock, events) { ++busyChecks <= 3 })
        // 30 s start delay, three 60 s waits while busy, the update, then the interval pause
        assertEquals(listOf(30_000L, 60_000L, 60_000L, 60_000L, 900_000L), clock.waits.toList().take(5))
        assertEquals(1, g.updates.size)
        assertEquals(1, events.count { it["status"] == "POSTPONED" })
        assertEquals("POSTPONED", events.first()["status"])
        assertEquals(true, events.last()["ok"])
    }

    @Test
    fun aLookupThatNeverEndsPostponesTheUpdateForeverAndNeverForcesIt() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock(stopAfter = 14)
        runLoop(clocked(g, clock, events) { true })
        val steps = clock.waits.toList()
        assertEquals(30_000L, steps.first())
        assertEquals(List(12) { 60_000L }, steps.subList(1, 13))
        assertTrue(g.updates.isEmpty())
        assertEquals(1, events.count { it["status"] == "POSTPONED" })
        assertEquals("busy", events.first()["reason"])
    }

    @Test
    fun aCastPostponesTheUpdateOnceAndItRunsAtTheNextRecheckWhenTheCastEnds() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock(stopAfter = 7)
        var checks = 0
        runLoop(clocked(g, clock, events, casting = { ++checks <= 5 }))
        assertEquals(1, events.count { it["status"] == "POSTPONED" })
        assertEquals("casting", events.first()["reason"])
        assertEquals(listOf(30_000L, 60_000L, 60_000L, 60_000L, 60_000L, 60_000L, 900_000L), clock.waits.toList())
        assertEquals(1, g.updates.size)
        assertEquals("start", events.last()["trigger"])
        assertEquals(true, events.last()["ok"])
    }

    @Test
    fun aCastForeverNeverUpdates() {
        val g = FakeGateway()
        val clock = Clock(stopAfter = 40)
        runLoop(clocked(g, clock, casting = { true }))
        assertTrue(g.updates.isEmpty())
    }

    // ----- plan 07.1-04: the persisted record is the due criterion ---------------------------------------

    private companion object {
        const val WALL = 1_700_000_000_000L
        const val HOUR = 3_600_000L
        const val MIN = 60_000L
    }

    @Test
    fun aRecentSuccessSkipsTheStartUpdateAndTheNextDeadlineIsAboutSeventeenHoursAway() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = WALL - HOUR, lastAttemptWallMs = WALL - HOUR))
        val clock = Clock(stopAfter = 3)
        runLoop(clocked(g, clock, events, store = store))
        assertTrue(g.updates.isEmpty())
        assertEquals(1, events.size)
        assertEquals("SKIPPED", events.single()["status"])
        assertEquals("recent", events.single()["reason"])
        assertEquals("start", events.single()["trigger"])
        // after the 30 s start delay the remaining wait is 17 h, taken in 15 minute steps
        assertEquals(listOf(30_000L, 900_000L, 900_000L), clock.waits.toList())
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun theSkippedDeadlineIsRecheckedSeventeenHoursLaterAndThenUpdates() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = WALL - HOUR))
        val clock = Clock(stopAfter = 80)
        runLoop(clocked(g, clock, events, store = store, wall = { WALL + clock.now }))
        // 30 s start delay, then 16 h 59 min 30 s in 68 steps of at most 15 minutes, then the update
        assertEquals(1, g.updates.size)
        assertEquals(30_000L, clock.waits.first())
        assertEquals(17L * HOUR, clock.waits.take(69).sum())
        assertEquals("SKIPPED", events.first()["status"])
        assertEquals("scheduled", events.last()["trigger"])
        assertEquals(true, events.last()["ok"])
    }

    @Test
    fun aFailedAttemptTenMinutesAgoWaitsTheHourOut() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = null, lastAttemptWallMs = WALL - 10 * MIN))
        val clock = Clock(stopAfter = 4)
        runLoop(clocked(g, clock, events, store = store, wall = { WALL + clock.now }))
        // start delay 30 s, then the 49.5 min that remain of the hour in 15 minute steps; the attempt comes afterwards
        assertEquals("SKIPPED", events.first()["status"])
        assertEquals("retry-wait", events.first()["reason"])
        assertTrue(g.updates.isEmpty())
    }

    @Test
    fun theAttemptIsMadeOnceTheHourHasPassed() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = null, lastAttemptWallMs = WALL - 10 * MIN))
        val clock = Clock(stopAfter = 12)
        runLoop(clocked(g, clock, events, store = store, wall = { WALL + clock.now }))
        assertEquals(1, g.updates.size)
        assertEquals("SKIPPED", events.first()["status"])
        assertEquals(true, events.last()["ok"])
    }

    @Test
    fun everyAutomaticAttemptSavesTheAttemptAndASuccessAlsoSavesTheSuccess() {
        val g = FakeGateway()
        val store = MemoryStore()
        val clock = Clock(stopAfter = 2)
        runLoop(clocked(g, clock, store = store, wall = { WALL + clock.now }))
        assertEquals(1, g.updates.size)
        assertEquals(listOf(YtDlpRecord(null, WALL + 30_000L), YtDlpRecord(WALL + 30_000L, WALL + 30_000L)), store.saves.toList())
    }

    @Test
    fun aFailedAttemptKeepsTheOldSuccess() {
        val g = FakeGateway()
        g.failure = { IllegalStateException("offline") }
        val old = WALL - 30 * HOUR
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = old))
        val clock = Clock(stopAfter = 2)
        runLoop(clocked(g, clock, store = store, wall = { WALL }))
        assertEquals(1, g.updates.size)
        assertEquals(listOf(YtDlpRecord(old, WALL)), store.saves.toList())
    }

    @Test
    fun aRecordFromTheFutureIsDue() {
        val g = FakeGateway()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = WALL + 5 * HOUR, lastAttemptWallMs = WALL + 5 * HOUR))
        val clock = Clock(stopAfter = 2)
        runLoop(clocked(g, clock, store = store))
        assertEquals(1, g.updates.size)
    }

    @Test
    fun aThrowingStoreDoesNotStopTheUpdate() {
        val g = FakeGateway()
        val store = object : YtDlpRecordStore {
            override fun load(): YtDlpRecord = throw IllegalStateException("disk")
            override fun save(record: YtDlpRecord) = throw IllegalStateException("disk")
        }
        val clock = Clock(stopAfter = 2)
        runLoop(clocked(g, clock, store = store))
        assertEquals(1, g.updates.size)
    }

    // ----- look-ahead: is a newer yt-dlp published? (installs nothing, allowed while casting) ---------------

    @Test
    fun ytdlpNewerComparesDottedVersionsPartByPart() {
        assertTrue(ytdlpNewer("2026.11.01", "2026.10.15"))
        assertTrue(ytdlpNewer("2026.11.07.021234", "2026.11.07"))
        assertTrue(ytdlpNewer("2026.11.07.021300", "2026.11.07.021234"))
        assertFalse(ytdlpNewer("2026.11.01", "2026.11.01"))
        assertFalse(ytdlpNewer("2026.10.15", "2026.11.01"), "an older release is not an update")
        assertFalse(ytdlpNewer("2026.11.07", "2026.11.07.021234"), "stable older than the installed nightly")
        assertTrue(ytdlpNewer("2026.11.01", null), "an unreadable installed version is older than anything")
        assertTrue(ytdlpNewer("2026.11.01rc", "2026.11.01"), "an unparsable version that differs counts as newer")
        assertFalse(ytdlpNewer("2026.11.01rc", "2026.11.01rc"))
    }

    @Test
    fun checkReportsANewerVersionAndInstallsNothingEvenWhileCasting() {
        val g = FakeGateway().also { it.latest = "2026.03.03" }
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val u = YtDlpUpdater(g, { "nightly" }, { events += it }, dispatcher = dispatcher, casting = { true })
        val c = runBlocking { u.check() }
        assertEquals(YtDlpCheck("2026.01.01", "2026.03.03", "nightly", true), c)
        assertEquals(listOf("nightly"), g.latestChannels)
        assertTrue(g.updates.isEmpty())
        assertTrue(events.isEmpty())
    }

    @Test
    fun checkSaysUpToDateWhenTheLatestIsNotNewer() {
        val g = FakeGateway().also { it.latest = "2026.01.01" }
        val c = runBlocking { updater(g, Pauses(1)).check() }
        assertEquals(false, c.available)
    }

    @Test
    fun checkOffersStableWhenTheInstalledBuildIsANewerNightly() {
        val g = FakeGateway().also {
            it.current = "2026.11.07.021234"
            it.latest = "2026.11.01"
        }
        val c = runBlocking { updater(g, Pauses(1), channel = { "stable" }).check() }
        assertEquals(true, c.available, "switching from nightly to stable is an update even though stable is numerically older")
        val same = FakeGateway().also {
            it.current = "2026.11.01"
            it.latest = "2026.11.01"
        }
        assertEquals(false, runBlocking { updater(same, Pauses(1), channel = { "stable" }).check() }.available)
        val nightly = FakeGateway().also {
            it.current = "2026.11.07.021234"
            it.latest = "2026.11.06.010101"
        }
        assertEquals(false, runBlocking { updater(nightly, Pauses(1), channel = { "nightly" }).check() }.available, "an older nightly is still no update on nightly")
    }

    @Test
    fun nightlyVersionsAreTheOnesWithASixDigitTimePart() {
        assertTrue(isNightlyVersion("2026.11.07.021234"))
        assertFalse(isNightlyVersion("2026.11.01"))
        assertFalse(isNightlyVersion("2026.11.01.1"))
        assertFalse(isNightlyVersion(null))
        assertFalse(isNightlyVersion("2026.11.07.02123x"))
    }

    @Test
    fun checkIsUnknownWhenTheLatestCannotBeRead() {
        val g = FakeGateway()
        assertNull(runBlocking { updater(g, Pauses(1)).check() }.available)
        g.latestFailure = java.io.IOException("offline")
        val c = runBlocking { updater(g, Pauses(1)).check() }
        assertNull(c.available)
        assertNull(c.latest)
        g.versionFailure = IllegalStateException("no yt-dlp")
        assertNull(runBlocking { updater(g, Pauses(1)).check() }.current)
    }

    // ----- plan 07.1-04: Update now during a cast, and updateIfDue ------------------------------------------

    @Test
    fun aManualUpdateWhileCastingIsRefusedWithNoGatewayCallAndNoRecordWrite() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore()
        var casting = true
        val u = YtDlpUpdater(g, { "stable" }, { events += it }, dispatcher = dispatcher, casting = { casting }, record = store, wallMs = { WALL })
        val refused = runBlocking { u.updateNow() }
        assertEquals(false, refused.ok)
        assertEquals(YTDLP_STATUS_CASTING, refused.status)
        assertEquals("CASTING", refused.status)
        assertTrue(g.updates.isEmpty())
        assertTrue(store.saves.isEmpty())
        assertEquals(listOf("CASTING"), events.map { it["status"] })
        casting = false
        val ran = runBlocking { u.updateNow() }
        assertEquals(true, ran.ok)
        assertEquals(1, g.updates.size)
        assertEquals(YtDlpRecord(WALL, WALL), store.value)
    }

    @Test
    fun updateIfDueDoesNothingWhenTheRecordIsRecent() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = WALL - HOUR))
        val u = YtDlpUpdater(g, { "stable" }, { events += it }, dispatcher = dispatcher, record = store, wallMs = { WALL })
        assertNull(runBlocking { u.updateIfDue("job") })
        assertTrue(g.updates.isEmpty())
        assertEquals("SKIPPED", events.single()["status"])
    }

    @Test
    fun updateIfDuePostponesWhileCastingOrBusy() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        var casting = true
        var busy = false
        val u = YtDlpUpdater(g, { "stable" }, { events += it }, dispatcher = dispatcher, casting = { casting }, busy = { busy })
        val first = runBlocking { u.updateIfDue("job") }
        assertEquals("POSTPONED", first?.status)
        assertEquals(false, first?.ok)
        casting = false
        busy = true
        assertEquals("POSTPONED", runBlocking { u.updateIfDue("job") }?.status)
        assertTrue(g.updates.isEmpty())
        assertEquals(listOf("casting", "busy"), events.map { it["reason"] })
    }

    @Test
    fun updateIfDueRunsOneUpdateWithTheJobTriggerAndSavesTheRecord() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val store = MemoryStore(YtDlpRecord(lastSuccessWallMs = WALL - 30 * HOUR))
        val u = YtDlpUpdater(g, { "stable" }, { events += it }, dispatcher = dispatcher, record = store, wallMs = { WALL })
        val outcome = runBlocking { u.updateIfDue("job") }
        assertEquals(true, outcome?.ok)
        assertEquals("job", outcome?.trigger)
        assertEquals(1, g.updates.size)
        assertEquals(YtDlpRecord(WALL, WALL), store.value)
        // and a second call right after is not due any more
        assertNull(runBlocking { u.updateIfDue("job") })
        assertEquals(1, g.updates.size)
    }

    @Test
    fun updateIfDueNeverOverlapsARunningScheduledUpdate() {
        val g = FakeGateway()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        g.onUpdate = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val pauses = Pauses(stopAfter = 2)
        val u = updater(g, pauses)
        val loop = thread(name = "scheduled-run") { runLoop(u) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val outcome = runBlocking { u.updateIfDue("job") }
            assertEquals("BUSY", outcome?.status)
            assertEquals(1, g.updates.size)
        } finally {
            release.countDown()
            loop.join(10_000)
        }
        assertEquals(1, g.updates.size)
    }

    // ----- plan 07-09: Update now from the app (GUI-07) -------------------------------------------------

    @Test
    fun aManualUpdateRunsOnceAndReportsVersions() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val u = YtDlpUpdater(g, { "nightly" }, { events += it }, dispatcher = dispatcher)
        val outcome = runBlocking { u.updateNow() }
        assertEquals(listOf("nightly"), g.updates.toList())
        assertEquals(true, outcome.ok)
        assertEquals("manual", outcome.trigger)
        assertEquals("nightly", outcome.channel)
        assertEquals("2026.01.01", outcome.before)
        assertEquals("2026.02.02", outcome.after)
        assertEquals(listOf("manual"), events.map { it["trigger"] })
    }

    @Test
    fun aManualUpdateDuringAScheduledOneIsRefusedAsBusy() {
        val g = FakeGateway()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        g.onUpdate = {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val pauses = Pauses(stopAfter = 2)
        val u = updater(g, pauses, events)
        val loop = thread(name = "scheduled-run") { runLoop(u) }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the scheduled update reached the gateway")
            val outcome = runBlocking { u.updateNow() }
            assertEquals(false, outcome.ok)
            assertEquals("BUSY", outcome.status)
            assertEquals("manual", outcome.trigger)
            assertEquals(1, g.updates.size)
            assertTrue(events.any { it["status"] == "BUSY" }, "the refusal is in the event log: $events")
        } finally {
            release.countDown()
            loop.join(10_000)
        }
        assertEquals(1, g.updates.size)
    }

    @Test
    fun aManualUpdateDuringALookupIsRefusedAsBusy() {
        val g = FakeGateway()
        val u = YtDlpUpdater(g, { "stable" }, {}, dispatcher = dispatcher, busy = { true })
        val outcome = runBlocking { u.updateNow() }
        assertEquals("BUSY", outcome.status)
        assertEquals(false, outcome.ok)
        assertTrue(g.updates.isEmpty())
    }

    @Test
    fun aSuccessfulManualUpdateMovesTheNextScheduledRunAFullIntervalAway() {
        val g = FakeGateway()
        val events = CopyOnWriteArrayList<Map<String, Any?>>()
        val clock = Clock(stopAfter = 5)
        var manual: YtDlpUpdateOutcome? = null
        val u = YtDlpUpdater(
            g, { "stable" }, { events += it },
            pause = { ms ->
                // inside the first pause (the 30 s start delay) the author taps Update now
                if (clock.waits.isEmpty()) manual = runBlocking { updaterRef.updateNow() }
                clock.pause(ms)
            },
            dispatcher = dispatcher,
            elapsedMs = { clock.now },
            maxPauseMs = 900_000L,
        ).also { updaterRef = it }
        runLoop(u)
        assertEquals(true, manual?.ok)
        // the start-delay deadline passed during the pause, but the manual update moved it a day away: no scheduled run
        assertEquals(1, g.updates.size)
        assertEquals(listOf("manual"), events.map { it["trigger"] })
    }

    private lateinit var updaterRef: YtDlpUpdater

    @Test
    fun versionReturnsTheGatewaysVersionAndNullWhenItThrows() {
        val g = FakeGateway()
        val u = YtDlpUpdater(g, { "stable" }, {}, dispatcher = dispatcher)
        assertEquals("2026.01.01", runBlocking { u.version() })
        g.versionFailure = IllegalStateException("no python yet")
        assertNull(runBlocking { u.version() })
    }
}
