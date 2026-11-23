package dev.t2u.media

// The process-wide yt-dlp install lock (APP WR-08, D-08). GPL-3.0-only.

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YtDlpInstallLockTest {
    @Test
    fun aSecondInstallWhileOneRunsIsRefused() {
        val lock = YtDlpInstallLock()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = thread(name = "install-a") {
            lock.install {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            assertTrue(inside.await(5, TimeUnit.SECONDS), "the first install started")
            val thrown = AtomicReference<Throwable?>()
            val second = thread(name = "install-b") {
                try {
                    lock.install { error("the second install must not run") }
                } catch (t: Throwable) {
                    thrown.set(t)
                }
            }
            second.join(5_000)
            val t = assertNotNull(thrown.get(), "the second install failed")
            assertTrue(t is IllegalStateException, t.toString())
            assertTrue(t.message.orEmpty().contains("another yt-dlp update is running"), t.message)
        } finally {
            release.countDown()
            first.join(5_000)
        }
        assertEquals("again", lock.install { "again" })
    }

    @Test
    fun theLockIsFreeAfterABlockThrows() {
        val lock = YtDlpInstallLock()
        val failure = runCatching { lock.install { throw java.io.IOException("download failed") } }.exceptionOrNull()
        assertTrue(failure is java.io.IOException, failure.toString())
        assertEquals(7, lock.install { 7 })
    }

    @Test
    fun aNestedInstallOnTheSameThreadIsRefusedToo() {
        val lock = YtDlpInstallLock()
        val nested = lock.install {
            runCatching { lock.install { "inner" } }.exceptionOrNull()
        }
        assertTrue(nested is IllegalStateException, nested.toString())
        assertEquals("after", lock.install { "after" })
    }
}
