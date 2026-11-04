// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
        fun model() = AppUpdateModel(
            "0.9.0-beta.1",
            fetch = { fetches++; gate?.await(); result },
            load = { saved },
            save = { saved = it },
            wallMs = { now },
        )
    }

    @Test
    fun checkNowRunsEvenWithAutoOffAndRecordsTheResult() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        val m = env.model()
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
        val m = env.model()
        val a = async { m.checkNow() }
        val b = async { m.checkNow() }
        testScheduler.runCurrent()
        env.gate!!.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, env.fetches)
    }

    @Test
    fun autoCheckIsSilentAndThrottled() = runTest {
        val env = Env(result = FetchResult.Body(list("v0.9.0")))
        val m = env.model()
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
        val m = env.model()
        assertEquals(CheckOutcome.RateLimited, m.checkNow())
        assertEquals(5L, m.state.value.record.lastCheckWallMs)
        assertEquals(env.now, m.state.value.record.lastAttemptWallMs)
        assertEquals("v0.8.0", m.state.value.record.latestTag)
    }
}
