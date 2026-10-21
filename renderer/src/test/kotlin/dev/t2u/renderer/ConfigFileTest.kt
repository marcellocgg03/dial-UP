package dev.t2u.renderer

// Tests for the optional config.json parser (DISC-04 name filter, SVC-07 forced format), GPL-3.0-only.

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigFileTest {
    private val base = Config(dataDir = File("data"))

    @Test
    fun includeExcludeAndForcedFormatBecomeAConfigWithAWorkingFilter() {
        val r = parseConfigFile("""{"include":"living|kitchen","exclude":["lamp"],"outputFormat":"mp4"}""", base)
        assertTrue(r.found)
        assertEquals(emptyList(), r.warnings)
        assertEquals("mp4", r.config.outputFormat)
        val f = r.config.nameFilter
        assertTrue(f.accepts("Living Room"))
        assertTrue(f.accepts("KITCHEN"))
        assertTrue(f.accepts("My kitchen speaker"))
        assertFalse(f.accepts("Bedroom"))
        assertFalse(f.accepts("Living Room Lamp"))
        r.config.validate()
    }

    @Test
    fun filterWithoutPatternsAcceptsEverything() {
        assertTrue(NameFilter().accepts("Anything"))
        assertTrue(NameFilter.ALLOW_ALL.accepts(""))
    }

    @Test
    fun excludeOnlyAcceptsWhatItDoesNotMatch() {
        val f = NameFilter(exclude = listOf("^bath"))
        assertTrue(f.accepts("Kitchen"))
        assertFalse(f.accepts("Bathroom"))
        assertTrue(f.accepts("Main bath")) // anchored: not at the start
    }

    @Test
    fun matchingIsAFindAnywhereAndCaseInsensitive() {
        val f = NameFilter(include = listOf("room"))
        assertTrue(f.accepts("Living ROOM Speaker"))
        assertFalse(f.accepts("Kitchen"))
    }

    // ---- Task 2: full key set, strict types, failure rules ----

    private fun parse(json: String) = parseConfigFile(json, base)

    private fun assertNoLeak(r: ConfigFileResult, vararg secrets: String) {
        for (w in r.warnings) for (x in secrets) assertFalse(x in w, "warning leaks \"$x\": $w")
    }

    @Test
    fun missingFileGivesDefaultsAndNoWarning(@TempDir dir: File) {
        val r = loadConfigFile(File(dir, "config.json"), base)
        assertFalse(r.found)
        assertEquals(base, r.config)
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun loadReadsAPresentFile(@TempDir dir: File) {
        val f = File(dir, "config.json")
        f.writeText("""{"startTimeoutMs":15000}""")
        val r = loadConfigFile(f, base)
        assertTrue(r.found)
        assertEquals(15000L, r.config.startTimeoutMs)
    }

    @Test
    fun unusableFilesGiveOneValueFreeWarning(@TempDir dir: File) {
        val big = "{\"include\":\"" + "BIGSECRET".repeat(8000) + "\"}"
        for (text in listOf("", "   ", "[]", "\"x\"", "{not json SECRETWORD", big)) {
            val f = File(dir, "c.json")
            f.writeText(text)
            val r = loadConfigFile(f, base)
            assertTrue(r.found, text.take(10))
            assertEquals(base, r.config, text.take(10))
            assertEquals(1, r.warnings.size, "${text.take(10)} -> ${r.warnings}")
            assertNoLeak(r, "SECRETWORD", "BIGSECRET")
        }
    }

    @Test
    fun a70KiBFileIsRejectedWithoutParsing(@TempDir dir: File) {
        val f = File(dir, "c.json")
        f.writeText("{\"startTimeoutMs\":15000," + " ".repeat(70 * 1024) + "}")
        val r = loadConfigFile(f, base)
        assertEquals(base, r.config)
        assertEquals(1, r.warnings.size)
    }

    @Test
    fun invalidPatternsAreSkippedByKeyAndIndex() {
        val r = parse("""{"include":["ok","[SECRETPATTERN",5]}""")
        assertEquals(listOf("ok"), r.config.nameFilter.include)
        assertTrue(r.warnings.any { "include[1]" in it })
        assertTrue(r.warnings.any { "include[2]" in it })
        assertNoLeak(r, "SECRETPATTERN")
        assertTrue(r.config.nameFilter.accepts("OK speaker"))
        assertFalse(r.config.nameFilter.accepts("Other"))
        r.config.validate()
    }

    @Test
    fun excludeAcceptsAStringToo() {
        val r = parse("""{"exclude":"lamp"}""")
        assertEquals(listOf("lamp"), r.config.nameFilter.exclude)
    }

    @Test
    fun includeOfTheWrongTypeIsIgnoredWithAWarning() {
        val r = parse("""{"include":{"a":1},"exclude":7}""")
        assertEquals(NameFilter.ALLOW_ALL, r.config.nameFilter)
        assertEquals(2, r.warnings.size)
    }

    @Test
    fun startTimeoutRange() {
        assertEquals(15000L, parse("""{"startTimeoutMs":15000}""").config.startTimeoutMs)
        assertEquals(1000L, parse("""{"startTimeoutMs":1000}""").config.startTimeoutMs)
        assertEquals(120000L, parse("""{"startTimeoutMs":120000}""").config.startTimeoutMs)
        for (bad in listOf("500", "999999", "\"15000\"", "1.5", "true", "null")) {
            val r = parse("""{"startTimeoutMs":$bad}""")
            assertEquals(base.startTimeoutMs, r.config.startTimeoutMs, bad)
            assertEquals(1, r.warnings.size, bad)
            assertContains(r.warnings[0], "startTimeoutMs")
        }
    }

    @Test
    fun pollIntervalRange() {
        assertEquals(500L, parse("""{"pollIntervalMs":500}""").config.pollIntervalMs)
        assertEquals(200L, parse("""{"pollIntervalMs":200}""").config.pollIntervalMs)
        assertEquals(30000L, parse("""{"pollIntervalMs":30000}""").config.pollIntervalMs)
        for (bad in listOf("199", "30001", "\"500\"", "2.5")) {
            val r = parse("""{"pollIntervalMs":$bad}""")
            assertEquals(base.pollIntervalMs, r.config.pollIntervalMs, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun mp3Bitrate() {
        assertEquals("256k", parse("""{"mp3Bitrate":"256k"}""").config.mp3Bitrate)
        assertEquals("64k", parse("""{"mp3Bitrate":"64k"}""").config.mp3Bitrate)
        assertEquals("320k", parse("""{"mp3Bitrate":"320k"}""").config.mp3Bitrate)
        for (bad in listOf("\"fast\"", "\"9999k\"", "\"63k\"", "\"321k\"", "320", "\"-b:a 1k\"", "\"192k -y\"")) {
            val r = parse("""{"mp3Bitrate":$bad}""")
            assertEquals(base.mp3Bitrate, r.config.mp3Bitrate, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun flacBits() {
        assertEquals(24, parse("""{"flacBits":24}""").config.flacBits)
        assertEquals(16, parse("""{"flacBits":16}""").config.flacBits)
        for (bad in listOf("20", "\"24\"", "24.0", "true")) {
            val r = parse("""{"flacBits":$bad}""")
            assertEquals(base.flacBits, r.config.flacBits, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun ytdlpVerbose() {
        assertTrue(parse("""{"ytdlpVerbose":true}""").config.ytdlpVerbose)
        for (bad in listOf("\"yes\"", "1", "null")) {
            val r = parse("""{"ytdlpVerbose":$bad}""")
            assertFalse(r.config.ytdlpVerbose, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun ffmpegLogLevel() {
        assertEquals("debug", parse("""{"ffmpegLogLevel":"debug"}""").config.ffmpegLogLevel)
        for (level in FFMPEG_LOG_LEVELS) assertEquals(level, parse("""{"ffmpegLogLevel":"$level"}""").config.ffmpegLogLevel)
        for (bad in listOf("\"loud\"", "\"DEBUG\"", "3", "\"debug -y\"")) {
            val r = parse("""{"ffmpegLogLevel":$bad}""")
            assertEquals(base.ffmpegLogLevel, r.config.ffmpegLogLevel, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun outputFormatValues() {
        val w = parse("""{"outputFormat":"wav"}""")
        assertEquals(base.outputFormat, w.config.outputFormat)
        assertEquals(1, w.warnings.size)
        assertEquals("auto", parse("""{"outputFormat":"auto"}""").config.outputFormat)
        assertEquals(base.outputFormat, parse("""{"outputFormat":3}""").config.outputFormat)
    }

    @Test
    fun unknownAndDeliberatelyUnreadKeysAreListedOnceByName() {
        val r = parse("""{"nameSuffix":"SECRETSUFFIX","nameTemplate":"SECRETTEMPLATE","foo":"SECRETFOO"}""")
        assertEquals(base, r.config)
        assertEquals(1, r.warnings.size)
        val w = r.warnings[0]
        assertContains(w, "ignored keys:")
        for (k in listOf("nameSuffix", "nameTemplate", "foo")) assertContains(w, k)
        assertNoLeak(r, "SECRETSUFFIX", "SECRETTEMPLATE", "SECRETFOO")
        assertEquals(DOCKER_COEXISTENCE_SUFFIX, r.config.nameSuffix)
    }

    @Test
    fun noWarningEverEchoesRejectedValues() {
        val r = parse(
            """{"include":["[BADREGEX"],"outputFormat":"BADFORMAT","startTimeoutMs":"BADSTR","pollIntervalMs":99999999,"mp3Bitrate":"BADRATE","flacBits":"BADBITS","ytdlpVerbose":"BADBOOL","ffmpegLogLevel":"BADLEVEL","zzz":1}""",
        )
        assertTrue(r.warnings.size >= 8)
        assertNoLeak(r, "BADREGEX", "BADFORMAT", "BADSTR", "99999999", "BADRATE", "BADBITS", "BADBOOL", "BADLEVEL")
        r.config.validate()
    }

    @Test
    fun aFullValidFileAppliesEverythingAndValidates() {
        val r = parse(
            """{"include":"living","exclude":["lamp"],"outputFormat":"flac","startTimeoutMs":20000,"pollIntervalMs":1000,
            "mp3Bitrate":"320k","flacBits":24,"ytdlpVerbose":true,"ffmpegLogLevel":"info"}""",
        )
        assertEquals(emptyList(), r.warnings)
        val c = r.config
        assertEquals("flac", c.outputFormat)
        assertEquals(20000L, c.startTimeoutMs)
        assertEquals(1000L, c.pollIntervalMs)
        assertEquals("320k", c.mp3Bitrate)
        assertEquals(24, c.flacBits)
        assertTrue(c.ytdlpVerbose)
        assertEquals("info", c.ffmpegLogLevel)
        assertNotNull(c.nameFilter)
        c.validate()
    }

    @Test
    fun anEmptyObjectChangesNothing() {
        val r = parse("{}")
        assertEquals(base, r.config)
        assertEquals(emptyList(), r.warnings)
        assertTrue(r.found)
    }
}
