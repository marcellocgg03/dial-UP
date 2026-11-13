package dev.t2u.media

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure eviction, pin, pairing and scan tests for [FileCache]: temp directories, no ffmpeg. */
class FileCacheTest {
    @TempDir lateinit var root: File

    private val dir get() = File(root, "cache")
    private var limit = 100L
    private val events = ArrayList<Map<String, Any?>>()
    private val cache by lazy { FileCache(dir, { limit }, { events.add(it) }) }

    private val idA = "aaaaaaaaaaa"
    private val idB = "bbbbbbbbbbb"

    private fun put(name: String, size: Int, modified: Long): File {
        dir.mkdirs()
        return File(dir, name).also {
            it.writeBytes(ByteArray(size))
            it.setLastModified(modified)
        }
    }

    private fun names(): List<String> = dir.list().orEmpty().filter { it != ".uncached" }.sorted()

    @Test
    fun aCorruptTransientManifestIsReportedAndTheCacheStartsWithNone() {
        dir.mkdirs()
        // not valid UTF-8: readLines throws MalformedInputException
        File(dir, ".transient").writeBytes(byteArrayOf(0x61, 0xFF.toByte(), 0xFE.toByte(), 0x80.toByte(), 0x0A))
        val c = FileCache(dir, { limit }, { events.add(it) })
        assertEquals(0L, c.info().temporaryBytes)
        assertTrue(events.any { it["event"] == "cache-transient-unreadable" }, events.toString())
        // and the cache still works afterwards
        val part = put("$idB.source.download", 10, 2_000)
        assertTrue(c.publish(part, File(dir, "$idB.source")))
    }

    @Test
    fun aTransientManifestWithJunkLinesKeepsOnlyValidCacheNames() {
        dir.mkdirs()
        File(dir, ".transient").writeText("../escape.m4a\n\n$idA-mp4-0.m4a\nnot a cache file\n")
        val c = FileCache(dir, { limit }, { events.add(it) })
        put("$idA-mp4-0.m4a", 10, 1_000)
        assertTrue(c.isTransient(File(dir, "$idA-mp4-0.m4a")))
        assertFalse(c.isTransient(File(dir, "escape.m4a")))
    }

    @Test
    fun admissionEvictsOldUnusedFilesAndUsesActualSize() {
        put("$idA-mp4-0.m4a", 60, 1_000)
        val part = put("$idB.source.download", 50, 2_000)
        assertTrue(cache.publish(part, File(dir, "$idB.source")))
        assertFalse(File(dir, "$idA-mp4-0.m4a").exists())
        assertEquals(50L, cache.info().bytes)
        assertEquals(0L, cache.info().temporaryBytes)
    }

    @Test
    fun oversizedFileIsTemporaryAndDoesNotEvictExistingCache() {
        put("$idA-mp4-0.m4a", 60, 1_000)
        val part = put("$idB.source.download", 101, 2_000)
        val dest = File(dir, "$idB.source")
        val first = cache.pin(dest)
        val second = cache.pin(dest)
        assertFalse(cache.publish(part, dest))
        assertEquals(60L, cache.info().bytes)
        assertEquals(101L, cache.info().temporaryBytes)
        cache.discardWhenUnused(dest)
        first()
        assertTrue(dest.exists(), "another reader still owns the file")
        second()
        assertFalse(dest.exists())
        assertEquals(60L, cache.info().bytes)
    }

    @Test
    fun admittedFileFitsExactlyAndPinnedEntriesAreNeverEvicted() {
        val a = put("$idA.source", 60, 1_000)
        val release = cache.pin(a)
        val b = File(dir, "$idB.source")
        assertTrue(cache.publish(put("first.download", 40, 2_000), b))
        assertEquals(100L, cache.info().bytes)
        val c = File(dir, "ccccccccccc.source")
        assertFalse(cache.publish(put("second.download", 50, 3_000), c))
        assertTrue(a.exists())
        assertTrue(b.exists(), "do not evict files when pinned data prevents admission")
        assertEquals(100L, cache.info().bytes)
        assertEquals(50L, cache.info().temporaryBytes)
        cache.discardWhenUnused(c)
        release()
    }

    @Test
    fun abandonedTemporaryFilesAreRemovedOnRestart() {
        val dest = File(dir, "$idA.source")
        assertFalse(cache.publish(put("source.download", 101, 1_000), dest))
        val restarted = FileCache(dir, { limit })
        assertEquals(101L, restarted.info().temporaryBytes)
        restarted.scan()
        assertFalse(dest.exists())
        assertEquals(0L, restarted.info().temporaryBytes)
    }

    @Test
    fun releaseBeforePublicationRemovesTemporaryFileOnceBuildReleasesIt() {
        val dest = File(dir, "$idA.source")
        val build = cache.pin(dest)
        cache.discardWhenUnused(dest)
        assertFalse(cache.publish(put("source.download", 101, 1_000), dest))
        assertTrue(dest.exists())
        build()
        assertFalse(dest.exists())
    }

    @Test
    fun concurrentAdmissionsNeverExceedTheBudget() {
        val a = File(dir, "$idA.source")
        val b = File(dir, "$idB.source")
        val releaseA = cache.pin(a)
        val releaseB = cache.pin(b)
        val partA = put("a.download", 80, 1_000)
        val partB = put("b.download", 80, 1_000)
        val ready = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Boolean> { ready.await(); cache.publish(partA, a) }
            val second = workers.submit<Boolean> { ready.await(); cache.publish(partB, b) }
            ready.countDown()
            val results = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
            assertEquals(1, results.count { it })
            assertEquals(80L, cache.info().bytes)
            assertEquals(80L, cache.info().temporaryBytes)
            cache.discardWhenUnused(a)
            cache.discardWhenUnused(b)
            releaseA()
            releaseB()
            assertEquals(0L, cache.info().temporaryBytes)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun evictionOrderIsOldestModifiedFirstTiesByName() {
        put("$idA-mp4-0.m4a", 40, 3_000)
        put("$idB-mp4-0.m4a", 40, 1_000)
        put("ccccccccccc-mp4-0.m4a", 40, 1_000)
        // 120 bytes against 100: one file has to go; b and c tie on time, b sorts first
        assertEquals(1, cache.prune())
        assertEquals(listOf("$idA-mp4-0.m4a", "ccccccccccc-mp4-0.m4a"), names())
    }

    @Test
    fun aNameInKeepSurvivesEvenWhenItAloneExceedsTheLimit() {
        put("$idA-mp4-0.m4a", 500, 1_000)
        put("$idA.source", 500, 1_000)
        put("$idB-mp4-0.m4a", 500, 2_000)
        limit = 1
        cache.prune(keep = setOf("$idB-mp4-0.m4a"))
        assertEquals(listOf("$idB-mp4-0.m4a"), names())
        limit = 1
        assertEquals(0, cache.prune(keep = setOf("$idB-mp4-0.m4a")))
        assertEquals(listOf("$idB-mp4-0.m4a"), names())
    }

    @Test
    fun aPinnedNameIsSkippedByPruneAndByClear() {
        val a = put("$idA-mp4-0.m4a", 80, 1_000)
        put("$idB-mp4-0.m4a", 80, 2_000)
        val release = cache.pin(a)
        assertTrue(cache.isPinned(a.name))
        limit = 1
        cache.prune()
        assertEquals(listOf("$idA-mp4-0.m4a"), names(), "the pinned, older file stays; the unpinned one went")
        assertEquals(0, cache.clear())
        assertEquals(listOf("$idA-mp4-0.m4a"), names())
        assertEquals(1, events.last()["keptInUse"])
        release()
        assertFalse(cache.isPinned(a.name))
        assertEquals(1, cache.clear())
        assertEquals(emptyList(), names())
    }

    @Test
    fun pinReturnsAOneShotReleaseAndTouchesTheFile() {
        val a = put("$idA-mp4-0.m4a", 10, 1_000)
        val first = cache.pin(a)
        val second = cache.pin(a)
        assertTrue(a.lastModified() > 1_000_000L, "pin touches the file: ${a.lastModified()}")
        first()
        first() // a second call must not release the second holder
        assertTrue(cache.isPinned(a.name))
        second()
        assertFalse(cache.isPinned(a.name))
    }

    @Test
    fun pinOfAFileOutsideTheCacheDirectoryIsANoOp() {
        val outside = File(root, "elsewhere").also { it.mkdirs() }.let { File(it, "$idA-mp4-0.m4a").apply { writeBytes(ByteArray(1)) } }
        val release = cache.pin(outside)
        assertFalse(cache.isPinned(outside.name))
        release()
    }

    @Test
    fun evictingTheOnlyBuiltFileOfAnIdAlsoDeletesItsSource() {
        put("$idA.source", 50, 1_000)
        put("$idA-mp4-0.m4a", 50, 1_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 60
        cache.prune()
        assertEquals(listOf("$idB-mp4-0.m4a"), names(), "source goes in the same step")
    }

    @Test
    fun theSourceStaysWhenBusyPinnedOrKept() {
        for (case in listOf("busy", "pinned", "kept")) {
            dir.deleteRecursively()
            val src = put("$idA.source", 50, 1_500)
            put("$idA-mp4-0.m4a", 50, 1_000)
            put("$idB-mp4-0.m4a", 50, 2_000)
            limit = 60
            var release: (() -> Unit)? = null
            if (case == "pinned") release = cache.pin(src)
            cache.prune(keep = if (case == "kept") setOf(src.name) else emptySet(), busy = { id -> case == "busy" && id == idA })
            release?.invoke()
            assertTrue("$idA.source" in names(), "$case: the source stays")
            assertFalse("$idA-mp4-0.m4a" in names(), "$case: the built file went")
        }
    }

    @Test
    fun aSourceWhoseIdStillHasAnotherBuiltFileStays() {
        put("$idA.source", 50, 500) // the oldest entry, but its video still has a build
        put("$idA-mp4-0.m4a", 50, 1_000)
        put("$idA-mp4-30.m4a", 50, 3_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 160
        cache.prune()
        assertEquals(listOf("$idA-mp4-30.m4a", "$idA.source", "$idB-mp4-0.m4a").sorted(), names())
    }

    @Test
    fun anOrphanSourceIsEvictedAsAnOrdinaryLruEntry() {
        put("$idA.source", 50, 1_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 60
        cache.prune()
        assertEquals(listOf("$idB-mp4-0.m4a"), names())
    }

    @Test
    fun theLimitSupplierIsReadOnEveryPrune() {
        put("$idA-mp4-0.m4a", 50, 1_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 1_000
        assertEquals(0, cache.prune())
        limit = 60
        assertEquals(1, cache.prune(), "the second prune follows the new value")
        assertEquals(listOf("$idB-mp4-0.m4a"), names())
        assertEquals(60L, cache.info().limitBytes)
    }

    @Test
    fun tempFilesAreNeitherCountedNorDeletedByPruneOrClear() {
        put("$idA-mp4-0.m4a.part", 500, 1_000)
        put("$idA.source.download", 500, 1_000)
        put("$idA.source.download.info.json", 500, 1_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 1_000
        val info = cache.info()
        assertEquals(1, info.files)
        assertEquals(50L, info.bytes)
        limit = 1
        cache.prune()
        assertEquals(3, names().size, "only the finished file went: ${names()}")
        assertEquals(0, cache.clear())
        assertEquals(3, names().size)
    }

    @Test
    fun aFileDeletedBetweenTheListingAndTheDeletionIsSkippedWithoutAnException() {
        put("$idA-mp4-0.m4a", 50, 1_000)
        put("$idA.source", 50, 1_000)
        val b = put("$idB-mp4-0.m4a", 50, 2_000)
        limit = 1
        // busy(idA) runs after a's deletion started the pairing: remove b behind the cache's back
        cache.prune(busy = { b.delete(); false })
        assertEquals(emptyList(), names())
    }

    @Test
    fun scanKeepsFinishedFilesDropsTheRestAndNeverTouchesASiblingDirectory() {
        val sibling = File(root, "sibling").also { it.mkdirs() }
        File(sibling, "x.m4a").writeBytes(ByteArray(10))
        File(sibling, "x.part").writeBytes(ByteArray(10))
        put("$idA-mp4-0.m4a", 100, 1_000)
        put("$idA.source", 50, 1_000)
        put("$idB-flac-0.flac", 0, 1_000)
        put("$idB-flac-0.flac.part", 10, 1_000)
        put("$idB.source.download", 10, 1_000)
        put("$idB.source.download.info.json", 10, 1_000)
        put("$idB.source.download.ytdl", 10, 1_000)
        put("foreign.txt", 10, 1_000)
        limit = 1_000
        val info = cache.scan()
        assertEquals(listOf("$idA-mp4-0.m4a", "$idA.source"), names())
        assertEquals(2, info.files)
        val scan = events.single { it["event"] == "cache-scan" }
        assertEquals(2, scan["kept"])
        assertEquals(150L, scan["keptBytes"])
        assertEquals(6, scan["removed"])
        cache.prune()
        cache.clear()
        cache.deleteTempFiles()
        assertEquals(listOf("x.m4a", "x.part"), sibling.list()!!.sorted(), "a sibling directory is untouched")
    }

    @Test
    fun scanCreatesAMissingDirectoryAndPrunesToTheCap() {
        limit = 60
        assertEquals(0, cache.scan().files)
        assertTrue(dir.isDirectory)
        put("$idA-mp4-0.m4a", 50, 1_000)
        put("$idB-mp4-0.m4a", 50, 2_000)
        assertEquals(1, cache.scan().files)
        assertEquals(listOf("$idB-mp4-0.m4a"), names())
    }

    @Test
    fun aZeroLimitNeverEvictsFilesThatAlreadyExisted() {
        put("$idA-mp4-0.m4a", 500, 1_000)
        put("$idA.source", 500, 1_000)
        limit = 0
        cache.scan()
        assertEquals(0, cache.prune())
        assertEquals(listOf("$idA-mp4-0.m4a", "$idA.source"), names())
    }

    @Test
    fun aZeroLimitDropsFilesCreatedAfterTheScanOnceTheNextBuildIsKept() {
        put("$idA-mp4-0.m4a", 500, 1_000)
        limit = 0
        cache.scan()
        put("$idB-mp4-0.m4a", 500, 2_000)
        // the build just finished: its file is in keep, so it stays for now and counts as a file made under the zero limit
        assertEquals(0, cache.prune(keep = setOf("$idB-mp4-0.m4a")))
        put("ccccccccccc-mp4-0.m4a", 500, 3_000)
        assertEquals(0, cache.prune(keep = setOf("ccccccccccc-mp4-0.m4a")), "the playing track and the next one stay")
        put("ddddddddddd-mp4-0.m4a", 500, 4_000)
        assertEquals(1, cache.prune(keep = setOf("ddddddddddd-mp4-0.m4a")))
        assertEquals(listOf("$idA-mp4-0.m4a", "ccccccccccc-mp4-0.m4a", "ddddddddddd-mp4-0.m4a"), names())
    }

    @Test
    fun aZeroLimitDeleteNewFilesRemovesOnlyTheOnesMadeUnderIt() {
        put("$idA-mp4-0.m4a", 500, 1_000)
        limit = 0
        cache.scan()
        put("$idB-mp4-0.m4a", 500, 2_000)
        cache.prune(keep = setOf("$idB-mp4-0.m4a"))
        assertEquals(1, cache.dropUncachedFiles())
        assertEquals(listOf("$idA-mp4-0.m4a"), names())
    }

    @Test
    fun aFileMadeWhileTheLimitWasPositiveStaysWhenTheLimitDropsToZero() {
        limit = 1_000
        cache.scan()
        put("$idA-mp4-0.m4a", 500, 1_000)
        cache.prune(keep = setOf("$idA-mp4-0.m4a"))
        limit = 0
        put("$idB-mp4-0.m4a", 500, 2_000)
        cache.prune(keep = setOf("$idB-mp4-0.m4a"))
        assertEquals(1, cache.dropUncachedFiles())
        assertEquals(listOf("$idA-mp4-0.m4a"), names())
    }

    @Test
    fun migrateMovesFinishedFilesOnceAndNeverOverwrites() {
        val old = File(root, "old").also { it.mkdirs() }
        File(old, "$idA-mp4-0.m4a").writeBytes(ByteArray(10))
        File(old, "$idA.source").writeBytes(ByteArray(20))
        File(old, "leftover.part").writeBytes(ByteArray(5))
        val new = File(root, "new").also { it.mkdirs() }
        File(new, "$idA.source").writeBytes(ByteArray(99))
        assertEquals(1, migrateCacheDir(old, new))
        assertEquals(listOf("$idA-mp4-0.m4a", "$idA.source"), new.list().orEmpty().sorted())
        assertEquals(99L, File(new, "$idA.source").length(), "an existing file is not replaced")
        assertEquals(emptyList(), old.list().orEmpty().toList(), "legacy duplicates and unfinished files are removed")
        assertEquals(0, migrateCacheDir(old, new))
        assertEquals(0, migrateCacheDir(File(root, "missing"), new))
    }

    @Test
    fun migrationPreservesAndMergesZeroLimitManifest() {
        val old = File(root, "old").also { it.mkdirs() }
        val new = File(root, "new").also { it.mkdirs() }
        File(old, ".uncached").writeText("$idA.source\n")
        File(old, "$idA.source").writeBytes(ByteArray(10))
        File(new, ".uncached").writeText("$idB.source\n")
        File(new, "$idB.source").writeBytes(ByteArray(10))

        assertEquals(1, migrateCacheDir(old, new))
        assertEquals(listOf("$idA.source", "$idB.source"), File(new, ".uncached").readLines())
        assertEquals(emptyList(), old.list().orEmpty().toList())
    }

    @Test
    fun migrationDoesNotDeleteLegacyFileWhenDestinationIsADirectory() {
        val old = File(root, "old").also { it.mkdirs() }
        val new = File(root, "new").also { it.mkdirs() }
        val src = File(old, "$idA.source").also { it.writeBytes(ByteArray(10)) }
        File(new, src.name).mkdirs()

        assertFailsWith<IOException> { migrateCacheDir(old, new) }
        assertEquals(10L, src.length())
    }

    @Test
    fun migratingMediaBufferRemovesOnlyOldRangeCacheFiles() {
        val old = File(root, "old")
        val files = File(old, "files").also { it.mkdirs() }
        val ranges = File(old, "range-cache").also { it.mkdirs() }
        File(files, "$idA.source").writeBytes(ByteArray(10))
        File(ranges, "stream.bin").writeBytes(ByteArray(10))
        File(ranges, "stream.part").writeBytes(ByteArray(10))
        File(ranges, "other.txt").writeBytes(ByteArray(10))
        val new = File(root, "new")

        migrateMediaBufferDir(old, new)
        assertTrue(File(new, "files/$idA.source").isFile)
        assertEquals(listOf("other.txt"), ranges.list().orEmpty().toList())
        File(new, "files/$idA.source").delete()
        migrateMediaBufferDir(old, new)
        assertFalse(File(new, "files/$idA.source").exists(), "cleared files must not reappear")
    }

    @Test
    fun aZeroLimitKeepsTheTrackThatPlaysAndTheOneQueuedNext() {
        limit = 0
        cache.scan()
        put("$idA-mp4-0.m4a", 500, 1_000)
        put("$idA.source", 500, 1_000)
        cache.prune(keep = setOf("$idA-mp4-0.m4a", "$idA.source"))
        put("$idB-mp4-0.m4a", 500, 2_000)
        put("$idB.source", 500, 2_000)
        assertEquals(0, cache.prune(keep = setOf("$idB-mp4-0.m4a", "$idB.source")), "the playing track survives the next build")
        put("ccccccccccc-mp4-0.m4a", 500, 3_000)
        assertEquals(2, cache.prune(keep = setOf("ccccccccccc-mp4-0.m4a")), "the track before the playing one goes")
        assertEquals(listOf("$idB-mp4-0.m4a", "$idB.source", "ccccccccccc-mp4-0.m4a"), names())
    }

    @Test
    fun filesMadeUnderZeroStayUncachedAcrossARestartOfTheCache() {
        limit = 0
        cache.scan()
        put("$idA-mp4-0.m4a", 500, 1_000)
        cache.prune(keep = setOf("$idA-mp4-0.m4a"))
        val restarted = FileCache(dir, { limit }, { events.add(it) })
        restarted.scan()
        assertEquals(emptyList(), names(), "the restart did not adopt the file as a kept one")
        assertEquals(0, restarted.dropUncachedFiles())
    }
}
