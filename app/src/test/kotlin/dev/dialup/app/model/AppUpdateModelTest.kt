// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdateModelTest {
    private val cur = "0.9.0-beta.1"
    private val h = 60L * 60 * 1000
    private fun entry(tag: String) =
        """{"tag_name": "$tag", "html_url": "https://github.com/marcellocgg03/dial-UP/releases/tag/$tag", "draft": false, "prerelease": false}"""
    private fun list(vararg tags: String) = "[" + tags.joinToString(",") { entry(it) } + "]"

    @Test
    fun outcomeOfTellsTheCasesApart() {
        assertEquals(CheckOutcome.UpToDate(null), outcomeOf(FetchResult.Body("[]"), cur))
        val newer = outcomeOf(FetchResult.Body(list("v0.9.0")), cur)
        assertEquals("v0.9.0", (newer as CheckOutcome.Newer).release.tag)
        assertEquals(CheckOutcome.UpToDate("v0.9.0-beta.1"), outcomeOf(FetchResult.Body(list("v0.9.0-beta.1")), cur))
        assertEquals(CheckOutcome.Failed, outcomeOf(FetchResult.Body("garbage"), cur))
        assertEquals(CheckOutcome.Failed, outcomeOf(FetchResult.Http(500), cur))
        assertEquals(CheckOutcome.Failed, outcomeOf(FetchResult.TooLarge, cur))
        assertEquals(CheckOutcome.RateLimited, outcomeOf(FetchResult.RateLimited, cur))
        assertEquals(CheckOutcome.Offline, outcomeOf(FetchResult.Offline, cur))
    }

    @Test
    fun autoCheckDueFollowsTheThrottle() {
        val now = 100 * h
        assertTrue(autoCheckDue(UpdateRecord(), now))
        assertFalse(autoCheckDue(UpdateRecord(now - 12 * h + 60_000, now - 12 * h + 60_000), now))
        assertTrue(autoCheckDue(UpdateRecord(now - 12 * h, now - 12 * h), now))
        assertFalse(autoCheckDue(UpdateRecord(now - 13 * h, now - 30 * 60_000L), now))
        assertTrue(autoCheckDue(UpdateRecord(now - 13 * h, now - h), now))
        assertTrue(autoCheckDue(UpdateRecord(now + h, now + h), now))
    }

    @Test
    fun cardReleaseNeedsAutoOnAndANewerRecordedTag() {
        val url = "https://github.com/marcellocgg03/dial-UP/releases/tag/v0.9.0"
        val rec = UpdateRecord(1, 1, "v0.9.0", url)
        assertEquals(UpdateCard("v0.9.0", url), cardRelease(true, rec, cur))
        assertNull(cardRelease(false, rec, cur))
        assertNull(cardRelease(true, rec, "0.9.0"))
        assertNull(cardRelease(true, rec, "1.0.0"))
        assertNull(cardRelease(true, rec, "dev"))
        assertEquals(UpdateCard("v0.9.0", RELEASES_PAGE_URL), cardRelease(true, rec.copy(latestUrl = "https://evil.example/"), cur))
    }

    @Test
    fun theStableChannelNeverSeesANightlyAndTheNightlyChannelSeesTheNewestOfAll() {
        val tags = list("v1.1.0", "v1.1.1-nightly.20261114", "v1.1.1-nightly.20261113")
        assertEquals("v1.1.0", newestRelease(tags)?.tag)
        assertEquals("v1.1.0", newestRelease(tags, UpdateChannel.STABLE)?.tag)
        assertEquals("v1.1.1-nightly.20261114", newestRelease(tags, UpdateChannel.NIGHTLY)?.tag)
        // a stable release newer than the newest nightly wins on both channels
        val later = list("v1.1.2", "v1.1.1-nightly.20261114")
        assertEquals("v1.1.2", newestRelease(later, UpdateChannel.NIGHTLY)?.tag)
        // a nightly ranks above the stable release it grew from
        assertEquals("v1.1.1-nightly.20261114", newestRelease(list("v1.1.1", "v1.1.1-nightly.20261114"), UpdateChannel.NIGHTLY)?.tag)
        assertEquals(true, newestRelease(tags, UpdateChannel.NIGHTLY)?.nightly)
    }

    @Test
    fun theRollingNightlyIsReadFromItsTitleOnTheNightlyChannelOnly() {
        fun rolling(name: String, prerelease: Boolean = true) =
            """{"tag_name": "nightly", "name": "$name", "html_url": "https://github.com/marcellocgg03/dial-UP/releases/tag/nightly", "draft": false, "prerelease": $prerelease}"""
        val json = "[" + rolling("DialUP 1.1.1-nightly.20261114 (nightly)") + "," + entry("v1.1.0") + "]"
        val found = newestRelease(json, UpdateChannel.NIGHTLY)!!
        assertEquals("v1.1.1-nightly.20261114", found.tag)
        assertEquals(true, found.nightly)
        assertEquals("https://github.com/marcellocgg03/dial-UP/releases/tag/nightly", found.url)
        assertEquals("v1.1.0", newestRelease(json, UpdateChannel.STABLE)?.tag)
        // a title without a version, a release that is not flagged pre-release, and a stable that is newer all lose
        assertEquals("v1.1.0", newestRelease("[" + rolling("DialUP nightly") + "," + entry("v1.1.0") + "]", UpdateChannel.NIGHTLY)?.tag)
        assertEquals("v1.1.0", newestRelease("[" + rolling("DialUP 1.1.1-nightly.20261114", prerelease = false) + "," + entry("v1.1.0") + "]", UpdateChannel.NIGHTLY)?.tag)
        // the old counter form of the title is no longer read
        assertEquals("v1.1.0", newestRelease("[" + rolling("DialUP 1.1.1-nightly.4") + "," + entry("v1.1.0") + "]", UpdateChannel.NIGHTLY)?.tag)
        assertEquals("v1.1.2", newestRelease("[" + rolling("DialUP 1.1.1-nightly.20261114") + "," + entry("v1.1.2") + "]", UpdateChannel.NIGHTLY)?.tag)
        assertEquals("v1.1.1-nightly.20261115", (outcomeOf(FetchResult.Body(json.replace("nightly.20261114", "nightly.20261115")), "1.1.1-nightly.20261114", UpdateChannel.NIGHTLY) as CheckOutcome.Newer).release.tag)
    }

    @Test
    fun aStableAppIsNotOfferedItsOwnNightlyAndANightlyIsOfferedTheNext() {
        val tags = list("v1.1.0", "v1.1.1-nightly.20261114")
        assertEquals(CheckOutcome.UpToDate("v1.1.0"), outcomeOf(FetchResult.Body(tags), "1.1.0", UpdateChannel.STABLE))
        assertEquals("v1.1.1-nightly.20261114", (outcomeOf(FetchResult.Body(tags), "1.1.0", UpdateChannel.NIGHTLY) as CheckOutcome.Newer).release.tag)
        assertEquals("v1.1.1-nightly.20261114", (outcomeOf(FetchResult.Body(list("v1.1.1-nightly.20261114")), "1.1.1-nightly.20261113", UpdateChannel.NIGHTLY) as CheckOutcome.Newer).release.tag)
        // a nightly of 1.1.1 is told about the next stable release on either channel, but not about 1.1.1 itself
        assertEquals("v1.1.2", (outcomeOf(FetchResult.Body(list("v1.1.2")), "1.1.1-nightly.20261114", UpdateChannel.STABLE) as CheckOutcome.Newer).release.tag)
        assertFalse(outcomeOf(FetchResult.Body(list("v1.1.1")), "1.1.1-nightly.20261114", UpdateChannel.STABLE) is CheckOutcome.Newer)
    }

    @Test
    fun aRecordReadForTheOtherChannelIsNeitherShownNorTrusted() {
        val nightly = UpdateRecord(100, 100, "v1.1.1-nightly.12", "https://github.com/marcellocgg03/dial-UP/releases/tag/v1.1.1-nightly.12", "nightly")
        assertEquals(null, cardRelease(true, nightly, "1.1.0", UpdateChannel.STABLE))
        assertEquals("v1.1.1-nightly.12", cardRelease(true, nightly, "1.1.0", UpdateChannel.NIGHTLY)?.tag)
        assertTrue(autoCheckDue(nightly, 101, UpdateChannel.STABLE))
        assertFalse(autoCheckDue(nightly, 101, UpdateChannel.NIGHTLY))
        // an old record without a channel is a stable one
        assertTrue(UpdateRecord(1, 1, "v1.1.0").isFor(UpdateChannel.STABLE))
        assertFalse(UpdateRecord(1, 1, "v1.1.0").isFor(UpdateChannel.NIGHTLY))
        assertEquals(nightly, parseUpdateRecord(updateRecordJson(nightly)))
    }

    @Test
    fun theRecordRoundTripsAndToleratesJunk() {
        val r = UpdateRecord(1L, 2L, "v1", "https://x")
        assertEquals(r, parseUpdateRecord(updateRecordJson(r)))
        assertEquals(UpdateRecord(), parseUpdateRecord("nope"))
        assertEquals(UpdateRecord(), parseUpdateRecord("[1]"))
    }

    @Test
    fun messagesAreOneLine() {
        val rel = newestRelease(list("v0.9.0"))!!
        assertEquals("DialUP v0.9.0 is available", appUpdateMessage(CheckOutcome.Newer(rel), cur))
        assertEquals("DialUP is up to date ($cur)", appUpdateMessage(CheckOutcome.UpToDate(null), cur))
        assertEquals("GitHub is limiting requests from this network; try again later", appUpdateMessage(CheckOutcome.RateLimited, cur))
        assertEquals("Could not check for updates: no connection", appUpdateMessage(CheckOutcome.Offline, cur))
        assertEquals("Could not check for updates", appUpdateMessage(CheckOutcome.Failed, cur))
    }

    private class Env(initial: UpdateRecord = UpdateRecord(), var result: FetchResult = FetchResult.Body("[]")) {
        var now = 100L * 60 * 60 * 1000
        var fetches = 0
        var saved: UpdateRecord = initial
        var gate: CompletableDeferred<Unit>? = null
        var failure: Throwable? = null
        fun model(scope: CoroutineScope) = AppUpdateModel(
            "0.9.0-beta.1",
            fetch = { fetches++; gate?.await(); failure?.let { throw it }; result },
            load = { saved },
            save = { saved = it },
            wallMs = { now },
            scope = scope,
        )
    }

    @Test
    fun checkNowRunsEvenWithAutoOffAndRecordsTheResult() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        val m = env.model(backgroundScope)
        val o = m.checkNow()
        assertEquals("v0.9.0", (o as CheckOutcome.Newer).release.tag)
        assertEquals(1, env.fetches)
        assertEquals(o, m.state.value.outcome)
        assertEquals("v0.9.0", m.state.value.record.latestTag)
        assertEquals(env.now, env.saved.lastCheckWallMs)
        assertFalse(m.state.value.checking)
    }

    @Test
    fun twoCheckNowAtOnceShareOneRequest() = runTest {
        val env = Env()
        env.gate = CompletableDeferred()
        val m = env.model(backgroundScope)
        val a = async { m.checkNow() }
        val b = async { m.checkNow() }
        testScheduler.runCurrent()
        env.gate!!.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, env.fetches)
    }

    @Test
    fun aCallerThatLeavesDoesNotFailTheOneWaitingWithIt() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        env.gate = CompletableDeferred()
        val m = env.model(backgroundScope)
        val a = launch { m.checkNow() }
        val b = async { m.checkNow() }
        testScheduler.runCurrent()
        a.cancel()
        env.gate!!.complete(Unit)
        val o = b.await()
        assertEquals("v0.9.0", (o as CheckOutcome.Newer).release.tag)
        assertEquals(1, env.fetches)
    }

    @Test
    fun theCheckFinishesAfterItsOnlyCallerLeft() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        env.gate = CompletableDeferred()
        val m = env.model(backgroundScope)
        val a = launch { m.checkNow() }
        testScheduler.runCurrent()
        assertTrue(m.state.value.checking)
        a.cancel()
        testScheduler.runCurrent()
        assertTrue(m.state.value.checking)
        env.gate!!.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, env.fetches)
        assertFalse(m.state.value.checking)
        assertEquals(env.now, env.saved.lastCheckWallMs)
        assertEquals("v0.9.0", env.saved.latestTag)
        // nobody was waiting, so no manual outcome was recorded
        assertNull(m.state.value.outcome)
    }

    @Test
    fun aFailedCheckReachesEveryWaiter() = runTest {
        val env = Env()
        env.gate = CompletableDeferred()
        env.failure = IllegalStateException("boom")
        val m = env.model(backgroundScope)
        val a = async { runCatching { m.checkNow() } }
        val b = async { runCatching { m.checkNow() } }
        testScheduler.runCurrent()
        env.gate!!.complete(Unit)
        val ra = a.await().exceptionOrNull()
        val rb = b.await().exceptionOrNull()
        assertTrue(ra is IllegalStateException, "first waiter got $ra")
        assertTrue(rb is IllegalStateException, "second waiter got $rb")
        assertEquals(1, env.fetches)
        assertFalse(m.state.value.checking)
        // the next check starts a new request: the failure was not kept
        env.failure = null
        env.gate = null
        assertEquals(CheckOutcome.UpToDate(null), m.checkNow())
        assertEquals(2, env.fetches)
    }

    @Test
    fun anAutomaticCheckJoinsARunningCheckNow() = runTest {
        val env = Env()
        env.gate = CompletableDeferred()
        val m = env.model(backgroundScope)
        val a = async { m.checkNow() }
        testScheduler.runCurrent()
        val b = async { m.autoCheck(true) }
        testScheduler.runCurrent()
        env.gate!!.complete(Unit)
        val oa = a.await()
        assertEquals(oa, b.await())
        assertEquals(1, env.fetches)
    }

    @Test
    fun autoCheckIsSilentAndThrottled() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        val m = env.model(backgroundScope)
        assertNull(m.autoCheck(false))
        assertEquals(0, env.fetches)
        assertTrue(m.autoCheck(true) is CheckOutcome.Newer)
        assertEquals(1, env.fetches)
        assertNull(m.state.value.outcome)
        assertEquals("v0.9.0", m.state.value.record.latestTag)
        env.now += 11 * 60 * 60 * 1000L
        assertNull(m.autoCheck(true))
        assertEquals(1, env.fetches)
        env.now += 2 * 60 * 60 * 1000L
        m.autoCheck(true)
        assertEquals(2, env.fetches)
    }

    @Test
    fun aRateLimitedAnswerRecordsTheAttemptButNotACheck() = runTest {
        val env = Env(UpdateRecord(5L, 5L, "v0.8.0", null), FetchResult.RateLimited)
        val m = env.model(backgroundScope)
        assertEquals(CheckOutcome.RateLimited, m.checkNow())
        assertEquals(5L, m.state.value.record.lastCheckWallMs)
        assertEquals(env.now, m.state.value.record.lastAttemptWallMs)
        assertEquals("v0.8.0", m.state.value.record.latestTag)
    }
}
