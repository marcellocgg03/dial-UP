package dev.dialup.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuardedRunTest {
    @Test
    fun runGuardedReturnsTrueWhenTheBlockCompletes() = runBlocking {
        var ran = 0
        var reported = 0
        val ok = runGuarded("start-bridge", { _, _ -> reported++ }) { ran++ }
        assertTrue(ok)
        assertEquals(1, ran)
        assertEquals(0, reported)
    }

    @Test
    fun runGuardedReportsAFailureAndReturnsFalse() = runBlocking {
        val seen = mutableListOf<Pair<String, Throwable>>()
        val boom = IllegalStateException("boom")
        val ok = runGuarded("start-bridge", { w, t -> seen.add(w to t) }) { throw boom }
        assertEquals(false, ok)
        assertEquals(1, seen.size)
        assertEquals("start-bridge", seen[0].first)
        assertTrue(seen[0].second === boom)
    }

    @Test
    fun runGuardedRethrowsCancellation() {
        var reported = 0
        assertFailsWith<CancellationException> {
            runBlocking { runGuarded("start-bridge", { _, _ -> reported++ }) { throw CancellationException("stop") } }
        }
        assertEquals(0, reported)
    }

    @Test
    fun runGuardedSurvivesAReportThatThrows() = runBlocking {
        val ok = runGuarded("start-bridge", { _, _ -> throw IllegalStateException("log is broken") }) {
            throw IllegalStateException("boom")
        }
        assertEquals(false, ok)
    }

    @Test
    fun featureErrorFieldsCutsTheMessageAt200Characters() {
        val fields = featureErrorFields("start-bridge", IllegalStateException("x".repeat(500)))
        assertEquals("start-bridge", fields["where"])
        assertEquals("java.lang.IllegalStateException", fields["error"])
        assertEquals(200, (fields["message"] as String).length)
        assertNull(featureErrorFields("w", IllegalStateException())["message"])
    }

    @Test
    fun theHandlerReportsAnUncaughtFailureAsUncaught() {
        val latch = CountDownLatch(1)
        val seen = java.util.concurrent.CopyOnWriteArrayList<Pair<String, Throwable>>()
        val boom = IllegalStateException("boom")
        val scope = CoroutineScope(SupervisorJob() + loggingExceptionHandler { w, t -> seen.add(w to t); latch.countDown() })
        runBlocking { scope.launch { throw boom }.join() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, seen.size)
        assertEquals("uncaught", seen[0].first)
        assertTrue(seen[0].second === boom)
    }

    @Test
    fun theHandlerIgnoresCancellation() {
        var reported = 0
        val scope = CoroutineScope(SupervisorJob() + loggingExceptionHandler { _, _ -> reported++ })
        runBlocking {
            val job = scope.launch { throw CancellationException("stop") }
            job.join()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.let { it.cancel(); it.join() }
        }
        assertEquals(0, reported)
    }

    @Test
    fun theHandlerSurvivesAReportThatThrows() {
        val scope = CoroutineScope(SupervisorJob() + loggingExceptionHandler { _, _ -> throw IllegalStateException("log is broken") })
        val done = CountDownLatch(1)
        runBlocking {
            scope.launch { throw IllegalStateException("boom") }.join()
            scope.launch { done.countDown() }.join()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        scope.cancel()
    }

    @Test
    fun launchFinishingRunsTheBodyThenFinishOnce() {
        val order = java.util.concurrent.CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(SupervisorJob())
        runBlocking { scope.launchFinishing({ order.add("finish") }) { order.add("body") }.join() }
        assertEquals(listOf("body", "finish"), order.toList())
        scope.cancel()
    }

    @Test
    fun launchFinishingFinishesOnAScopeCancelledBeforeTheCall() {
        val finished = AtomicInteger()
        val ran = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()
        runBlocking { scope.launchFinishing({ finished.incrementAndGet() }) { ran.incrementAndGet() }.join() }
        assertEquals(0, ran.get())
        assertEquals(1, finished.get())
    }

    @Test
    fun launchFinishingFinishesOnceWhenTheBodyThrowsAndDoesNotSwallowIt() {
        val finished = AtomicInteger()
        val seen = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val boom = IllegalStateException("boom")
        val scope = CoroutineScope(SupervisorJob() + loggingExceptionHandler { _, t -> seen.add(t) })
        runBlocking { scope.launchFinishing({ finished.incrementAndGet() }) { throw boom }.join() }
        assertEquals(1, finished.get())
        assertEquals(1, seen.size)
        assertTrue(seen[0] === boom)
        scope.cancel()
    }

    @Test
    fun launchFinishingFinishesOnceWhenTheScopeIsCancelledMidBody() {
        val finished = AtomicInteger()
        val started = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob())
        val job = scope.launchFinishing({ finished.incrementAndGet() }) {
            started.countDown()
            awaitCancellation()
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        scope.cancel()
        runBlocking { job.join() }
        assertEquals(1, finished.get())
    }
}
