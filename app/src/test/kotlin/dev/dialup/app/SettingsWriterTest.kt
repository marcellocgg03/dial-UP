package dev.dialup.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SettingsWriterTest {
    @Test
    fun postsRunInTheOrderTheyWereMade() {
        val writer = SerialWriter("t2u-test-writer") { _, _ -> }
        try {
            val seen = Collections.synchronizedList(mutableListOf<Int>())
            val start = CountDownLatch(1)
            val threads = listOf(0, 1000).map { base ->
                thread {
                    start.await()
                    for (i in 0 until 200) writer.post { seen.add(base + i) }
                }
            }
            start.countDown()
            threads.forEach { it.join() }
            runBlocking { writer.run { } }
            assertEquals(400, seen.size)
            assertEquals((0 until 200).toList(), seen.filter { it < 1000 })
            assertEquals((1000 until 1200).toList(), seen.filter { it >= 1000 })
        } finally {
            writer.close()
        }
    }

    @Test
    fun runReturnsAfterEveryEarlierPost() {
        val writer = SerialWriter("t2u-test-writer") { _, _ -> }
        try {
            val flag = AtomicBoolean(false)
            writer.post {
                Thread.sleep(150)
                flag.set(true)
            }
            val seen = runBlocking { writer.run { flag.get() } }
            assertTrue(seen)
        } finally {
            writer.close()
        }
    }

    @Test
    fun aFailingPostIsReportedAndTheNextOneRuns() {
        val reports = Collections.synchronizedList(mutableListOf<Pair<String, Throwable>>())
        val writer = SerialWriter("t2u-test-writer") { where, t -> reports.add(where to t) }
        try {
            val ran = CountDownLatch(1)
            writer.post { throw IllegalStateException("boom") }
            writer.post { ran.countDown() }
            assertTrue(ran.await(5, TimeUnit.SECONDS))
            runBlocking { writer.run { } }
            assertEquals(1, reports.size)
            assertTrue(reports[0].second is IllegalStateException)
        } finally {
            writer.close()
        }
    }

    @Test
    fun postsRunOffTheCallingThread() {
        val writer = SerialWriter("t2u-test-writer") { _, _ -> }
        try {
            val name = runBlocking { writer.run { Thread.currentThread().name } }
            assertEquals("t2u-test-writer", name)
            assertNotEquals(Thread.currentThread().name, name)
        } finally {
            writer.close()
        }
    }
}
