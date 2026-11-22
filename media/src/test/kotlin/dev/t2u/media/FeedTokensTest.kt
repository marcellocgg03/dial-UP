package dev.t2u.media

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The feed token table without ffmpeg or sockets. */
class FeedTokensTest {
    @Test
    @DisplayName("a token in use survives any number of newer ones; the table stays at its cap plus the tokens in use")
    fun anInUseTokenSurvivesManyNewerOnes() {
        val tokens = FeedTokens(64)
        val first = tokens.register("video-1")
        assertEquals("video-1", tokens.lookup(first), "ffmpeg fetched it: it is in use from now on")
        repeat(100) {
            tokens.register("video-${it + 2}")
            assertTrue(tokens.size <= 64 + 1, "size ${tokens.size}")
        }
        assertEquals("video-1", tokens.lookup(first), "a reconnect of the same process still finds its token")
    }

    @Test
    @DisplayName("when the table is full the oldest token never used is evicted first")
    fun theOldestUnusedTokenIsEvictedFirst() {
        val tokens = FeedTokens(3)
        val a = tokens.register("a")
        val b = tokens.register("b")
        val c = tokens.register("c")
        assertEquals("b", tokens.lookup(b))
        val d = tokens.register("d")
        assertNull(tokens.lookup(a), "the oldest unused one went")
        assertEquals("b", tokens.lookup(b))
        assertEquals("c", tokens.lookup(c))
        assertEquals("d", tokens.lookup(d))
    }

    @Test
    @DisplayName("a released token is gone")
    fun aReleasedTokenIsGone() {
        val tokens = FeedTokens(64)
        val t = tokens.register("video")
        assertEquals("video", tokens.lookup(t))
        tokens.release(t)
        assertNull(tokens.lookup(t))
        assertEquals(0, tokens.size)
        tokens.release(t) // releasing twice is harmless
    }

    @Test
    @DisplayName("with every token in use nothing is evicted, not even the newest")
    fun everyTokenInUseEvictsNothing() {
        val tokens = FeedTokens(2)
        val made = (1..3).map { n -> tokens.register("v$n").also { assertEquals("v$n", tokens.lookup(it)) } }
        made.forEachIndexed { i, t -> assertEquals("v${i + 1}", tokens.lookup(t)) }
        assertEquals(3, tokens.size)
    }

    @Test
    @DisplayName("a token is 128 random bits as lowercase hex and two tokens differ")
    fun aTokenIs128BitHex() {
        val tokens = FeedTokens(64)
        val a = tokens.register("v")
        val b = tokens.register("v")
        assertTrue(Regex("[0-9a-f]{32}").matches(a), a)
        assertTrue(a != b)
    }
}
