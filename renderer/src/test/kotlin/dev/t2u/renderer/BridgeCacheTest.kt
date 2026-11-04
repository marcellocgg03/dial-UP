// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.t2u.renderer

// GUI-08 (plan 07-09): the app empties the bridge's cache and changes its cap on the running bridge. The cache is the
// builder's <bufferDir>/files; the files are placed before start the way a previous run left them (D-12).
//
// Every test is `= h.run<Unit> { ... }`: JUnit silently skips a test whose expression body returns a value.

import dev.t2u.media.JvmPipeLauncher
import dev.t2u.media.SourceDownloader
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BridgeCacheTest {
    private val h = BridgeHarness()
    private val tmp: File = Files.createTempDirectory("bridge-cache-").toFile().also { it.deleteOnExit() }

    @AfterEach
    fun tearDown() {
        h.close()
        tmp.deleteRecursively()
    }

    private val downloader = SourceDownloader { _, _, target ->
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(8))
    }

    /** A bridge with a file builder over `<tmp>/buffer/files`, which already holds [preexisting] finished files. */
    private fun bridgeWithCache(preexisting: List<String> = emptyList(), cacheMb: (() -> Int)? = null): Bridge {
        val buffer = File(tmp, "buffer").also { it.mkdirs() }
        val files = File(buffer, "files").also { it.mkdirs() }
        for (name in preexisting) File(files, name).writeBytes(ByteArray(1024) { 1 })
        return h.newBridge(
            h.testConfig(outputFormat = "mp4"),
            launcher = JvmPipeLauncher("/usr/bin/ffmpeg"),
            bufferDir = buffer,
            sourceDownloader = downloader,
            cacheMb = cacheMb,
        )
    }

    @Test
    @DisplayName("clearCache removes finished files and reports the count")
    fun clearCacheRemovesFinishedFilesAndReportsTheCount() = h.run<Unit> {
        val bridge = bridgeWithCache(listOf("aaaa.m4a", "bbbb.flac"))
        bridge.start()
        val before = assertNotNull(bridge.cacheInfo())
        assertEquals(2, before.files)
        assertEquals(2048L, before.bytes)

        assertEquals(2, bridge.clearCache())

        val after = assertNotNull(bridge.cacheInfo())
        assertEquals(0, after.files)
        assertEquals(0L, after.bytes)
        assertTrue(File(tmp, "buffer/files").listFiles().orEmpty().none { it.name.endsWith(".m4a") || it.name.endsWith(".flac") })
        assertTrue(h.events.any { it.first == "cache" && it.second["cleared"] == 2 }, "a cache event with the cleared count: ${h.events.map { it.first }}")
    }

    @Test
    @DisplayName("without a file builder there is no cache")
    fun withoutAFileBuilderThereIsNoCache() = h.run<Unit> {
        val bridge = h.newBridge(h.testConfig(outputFormat = "auto"))
        bridge.start()
        assertEquals(0, bridge.clearCache())
        assertNull(bridge.cacheInfo())
    }

    @Test
    @DisplayName("the cap follows the supplier without a restart")
    fun theCapFollowsTheSupplierWithoutARestart() = h.run<Unit> {
        var cap = 64
        val bridge = bridgeWithCache(cacheMb = { cap })
        bridge.start()
        assertEquals(64L * 1_048_576L, assertNotNull(bridge.cacheInfo()).limitBytes)
        cap = 128
        assertEquals(128L * 1_048_576L, assertNotNull(bridge.cacheInfo()).limitBytes)
    }

    @Test
    @DisplayName("a supplier that throws leaves the config.json cap in force")
    fun aThrowingSupplierFallsBackToTheConfigCap() = h.run<Unit> {
        val bridge = bridgeWithCache(cacheMb = { error("settings unreadable") })
        bridge.start()
        assertEquals(256L * 1_048_576L, assertNotNull(bridge.cacheInfo()).limitBytes)
    }
}
