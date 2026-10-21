package dev.t2u.media

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure JVM tests of the bounded range cache (plan 02-16, threats T-02-56 and T-02-57): small caps, no ffmpeg. */
class PipeRangeCacheTest {
    @TempDir lateinit var tmp: File

    private val dir get() = File(tmp, "range-cache")

    private fun token(n: Int) = "%032x".format(n)

    private fun cache(
        perToken: Long = 100,
        total: Long = 250,
        parts: Int = 2,
        enabled: Boolean = true,
    ) = PipeRangeCache(dir, PipeRangeConfig(enabled, perToken, total, 1000, parts))

    /** Writes [bytes] bytes through a tee and completes it; null when the cache refused or the tee gave up. */
    private fun fill(c: PipeRangeCache, token: String, bytes: Int): CachedStream? {
        val tee = c.openTee(token) ?: return null
        if (bytes > 0 && !tee.write(ByteArray(bytes) { it.toByte() }, 0, bytes)) return null
        return tee.complete()
    }

    private fun files(): List<String> = dir.listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test
    @DisplayName("a promoted run is found by its token with its size")
    fun aPromotedRunIsFoundByItsTokenWithItsSize() {
        val c = cache()
        val got = fill(c, token(1), 60)
        assertNotNull(got)
        assertEquals(60, got.size)
        assertEquals(60, c.lookup(token(1))?.size)
        assertEquals(listOf("${token(1)}.bin"), files())
        assertEquals(PipeRangeStats(1, 60), c.stats())
        assertNull(c.lookup(token(2)))
    }

    @Test
    @DisplayName("a second promote for a cached token keeps the first stream and deletes the second file")
    fun aSecondPromoteForACachedTokenKeepsTheFirstStreamAndDeletesTheSecondFile() {
        val c = cache()
        fill(c, token(1), 60)
        val part = assertNotNull(c.newPartFile())
        part.writeBytes(ByteArray(80))
        val kept = c.promote(token(1), part)
        assertEquals(60, kept?.size)
        assertFalse(part.exists())
        assertEquals(listOf("${token(1)}.bin"), files())
    }

    @Test
    @DisplayName("an empty part promotes to nothing and leaves no file")
    fun anEmptyPartPromotesToNothingAndLeavesNoFile() {
        val c = cache()
        assertNull(fill(c, token(1), 0))
        val part = assertNotNull(c.newPartFile())
        assertNull(c.promote(token(2), part))
        assertEquals(emptyList(), files())
        assertEquals(PipeRangeStats(0, 0), c.stats())
    }

    @Test
    @DisplayName("past the total cap the least recently used run is deleted first and a lookup refreshes recency")
    fun pastTheTotalCapTheLeastRecentlyUsedRunIsDeletedFirstAndALookupRefreshesRecency() {
        val c = cache(perToken = 100, total = 250)
        fill(c, token(1), 100)
        fill(c, token(2), 100)
        assertNotNull(c.lookup(token(1))) // token 2 is now the least recently used
        fill(c, token(3), 100)
        assertNotNull(c.lookup(token(1)))
        assertNull(c.lookup(token(2)))
        assertNotNull(c.lookup(token(3)))
        assertEquals(listOf("${token(1)}.bin", "${token(3)}.bin"), files())
        assertEquals(PipeRangeStats(2, 200), c.stats())
    }

    @Test
    @DisplayName("a run that would pass the per-token cap is abandoned, its file deleted and the token remembered as too big")
    fun aRunThatWouldPassThePerTokenCapIsAbandonedItsFileDeletedAndTheTokenRememberedAsTooBig() {
        val c = cache(perToken = 100, total = 250)
        val tee = assertNotNull(c.openTee(token(1)))
        assertTrue(tee.write(ByteArray(60), 0, 60))
        assertFalse(tee.write(ByteArray(60), 0, 60), "60 + 60 passes the cap of 100")
        assertEquals(emptyList(), files())
        assertNull(tee.complete())
        assertTrue(c.isOversize(token(1)))
        assertNull(c.openTee(token(1)), "a too-big token is not teed again")
        c.drop(token(1))
        assertFalse(c.isOversize(token(1)))
        assertNotNull(c.openTee(token(1))?.also { it.abort() })
    }

    @Test
    @DisplayName("abort deletes the part file and can be called again")
    fun abortDeletesThePartFileAndCanBeCalledAgain() {
        val c = cache()
        val tee = assertNotNull(c.openTee(token(1)))
        tee.write(ByteArray(10), 0, 10)
        assertEquals(1, files().size)
        tee.abort()
        tee.abort()
        assertEquals(emptyList(), files())
        assertFalse(tee.write(ByteArray(1), 0, 1), "a finished tee takes no more bytes")
    }

    @Test
    @DisplayName("clear empties the directory and forgets every run")
    fun clearEmptiesTheDirectoryAndForgetsEveryRun() {
        val c = cache()
        fill(c, token(1), 50)
        fill(c, token(2), 50)
        val open = assertNotNull(c.openTee(token(3)))
        open.write(ByteArray(5), 0, 5)
        c.clear()
        assertEquals(emptyList(), files())
        assertNull(c.lookup(token(1)))
        assertEquals(PipeRangeStats(0, 0), c.stats())
        open.abort()
    }

    @Test
    @DisplayName("a new cache removes the part and run files an earlier process left behind")
    fun aNewCacheRemovesThePartAndRunFilesAnEarlierProcessLeftBehind() {
        dir.mkdirs()
        File(dir, "run-1.part").writeBytes(ByteArray(10))
        File(dir, "${token(7)}.bin").writeBytes(ByteArray(10))
        cache()
        assertEquals(emptyList(), files())
    }

    @Test
    @DisplayName("invalid limits are rejected")
    fun invalidLimitsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PipeRangeConfig(maxBytesPerToken = 0) }
        assertFailsWith<IllegalArgumentException> { PipeRangeConfig(maxBytesTotal = -1) }
        assertFailsWith<IllegalArgumentException> { PipeRangeConfig(maxBytesPerToken = 200, maxBytesTotal = 100) }
        assertFailsWith<IllegalArgumentException> { PipeRangeConfig(coldFillTimeoutMs = 0) }
        assertFailsWith<IllegalArgumentException> { PipeRangeConfig(maxConcurrentParts = 0) }
        assertEquals(2, PipeRangeConfig().maxConcurrentParts, "at most two part files at a time by default")
    }

    @Test
    @DisplayName("no more part files are handed out than the concurrent limit, and finishing one frees a slot")
    fun noMorePartFilesAreHandedOutThanTheConcurrentLimitAndFinishingOneFreesASlot() {
        val c = cache(parts = 2)
        val a = assertNotNull(c.openTee(token(1)))
        val b = assertNotNull(c.openTee(token(2)))
        assertNull(c.openTee(token(3)), "a third part file would be unbounded disk use")
        assertNull(c.newPartFile(), "a cold fill needs a slot as well")
        a.abort()
        val fill = assertNotNull(c.newPartFile())
        assertNull(c.newPartFile())
        c.discard(fill)
        assertFalse(fill.exists())
        b.write(ByteArray(10), 0, 10)
        assertNotNull(b.complete())
        assertNotNull(c.openTee(token(4))?.also { it.abort() }, "a promoted run frees its slot")
    }

    @Test
    @DisplayName("a disabled cache tees nothing")
    fun aDisabledCacheTeesNothing() {
        val c = cache(enabled = false)
        assertNull(c.openTee(token(1)))
        assertEquals(emptyList(), files())
    }

    @Test
    @DisplayName("a directory that cannot be created gives no tee and no part file and throws nothing")
    fun aDirectoryThatCannotBeCreatedGivesNoTeeAndNoPartFileAndThrowsNothing() {
        File(tmp, "range-cache").writeText("a regular file where the directory should be")
        val c = cache()
        assertNull(c.openTee(token(1)))
        assertNull(c.newPartFile())
        c.clear()
        assertEquals(PipeRangeStats(0, 0), c.stats())
    }

    @Test
    @DisplayName("a run whose file was deleted from outside is no longer found")
    fun aRunWhoseFileWasDeletedFromOutsideIsNoLongerFound() {
        val c = cache()
        fill(c, token(1), 50)
        File(dir, "${token(1)}.bin").delete()
        assertNull(c.lookup(token(1)))
        assertEquals(PipeRangeStats(0, 0), c.stats())
    }

    @Test
    @DisplayName("dropping a token deletes its file")
    fun droppingATokenDeletesItsFile() {
        val c = cache()
        fill(c, token(1), 50)
        fill(c, token(2), 50)
        c.drop(token(1))
        assertEquals(listOf("${token(2)}.bin"), files())
        assertNull(c.lookup(token(1)))
    }

    @Test
    @DisplayName("the too-big memory is bounded")
    fun theTooBigMemoryIsBounded() {
        val c = cache()
        repeat(1000) { c.markOversize(token(it)) }
        assertTrue(c.isOversize(token(999)))
        assertFalse(c.isOversize(token(0)), "the oldest marks are forgotten")
    }
}
