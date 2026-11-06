package dev.t2u.renderer

// Tests for the optional config.json parser (DISC-04 name filter, SVC-07 forced format), GPL-3.0-only.

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun ytdlpVerbose() {
        assertTrue(parse("""{"ytdlpVerbose":true}""").config.ytdlpVerbose)
        assertFalse(parse("""{"ytdlpVerbose":false}""").config.ytdlpVerbose)
        for (bad in listOf("\"yes\"", "1", "null")) {
            val r = parse("""{"ytdlpVerbose":$bad}""")
            assertTrue(r.config.ytdlpVerbose, bad)
            assertEquals(1, r.warnings.size, bad)
        }
    }

    @Test
    fun ytdlpChannelIsStableOrNightlyOnly() {
        assertEquals("stable", parse("{}").config.ytdlpChannel)
        for (ok in listOf("stable", "nightly")) {
            val r = parse("""{"ytdlpChannel":"$ok"}""")
            assertEquals(ok, r.config.ytdlpChannel)
            assertEquals(emptyList(), r.warnings, ok)
        }
        for (bad in listOf("\"beta\"", "\"\"", "1", "true")) {
            val r = parse("""{"ytdlpChannel":$bad}""")
            assertEquals("stable", r.config.ytdlpChannel, bad)
            assertEquals(1, r.warnings.size, bad)
            assertContains(r.warnings.single(), "ytdlpChannel")
            assertFalse(r.warnings.single().contains("beta"), "the warning does not echo the value")
        }
        val e = assertFailsWith<IllegalArgumentException> { base.copy(ytdlpChannel = "beta").validate() }
        assertContains(e.message.orEmpty(), "ytdlpChannel")
    }

    @Test
    fun logLevelIsOffInfoOrDebugInExactLowercase() {
        assertEquals("info", parse("{}").config.logLevel)
        for (ok in listOf("off", "info", "debug")) {
            val r = parse("""{"logLevel":"$ok"}""")
            assertEquals(ok, r.config.logLevel)
            assertEquals(emptyList(), r.warnings, ok)
        }
        for (bad in listOf("\"verbose\"", "\"OFF\"", "1", "null", "true", "\"SECRETVALUE\"")) {
            val r = parse("""{"logLevel":$bad}""")
            assertEquals("info", r.config.logLevel, bad)
            assertEquals(1, r.warnings.size, bad)
            assertContains(r.warnings.single(), "logLevel")
            assertFalse("verbose" in r.warnings.single() || "OFF" in r.warnings.single() || "SECRETVALUE" in r.warnings.single(), "the warning does not echo the value")
        }
        for (ok in listOf("off", "info", "debug")) base.copy(logLevel = ok).validate()
        val e = assertFailsWith<IllegalArgumentException> { base.copy(logLevel = "loud").validate() }
        assertContains(e.message.orEmpty(), "logLevel")
    }

    @Test
    fun prefetchAndGaplessAreStrictBooleans() {
        val defaults = parse("{}").config
        assertTrue(defaults.prefetch)
        assertTrue(defaults.gapless)
        val off = parse("""{"prefetch":false,"gapless":false}""")
        assertFalse(off.config.prefetch)
        assertFalse(off.config.gapless)
        assertEquals(emptyList(), off.warnings)
        for (key in listOf("prefetch", "gapless")) {
            for (bad in listOf("\"false\"", "1", "0", "null", "\"SECRETVALUE\"")) {
                val r = parse("""{"$key":$bad}""")
                assertTrue(r.config.prefetch, "$key $bad")
                assertTrue(r.config.gapless, "$key $bad")
                assertEquals(1, r.warnings.size, "$key $bad")
                assertContains(r.warnings[0], key)
                assertFalse("SECRETVALUE" in r.warnings[0], r.warnings[0])
            }
        }
    }

    @Test
    fun cacheMbIsAWholeNumberFromZeroTo32768() {
        assertEquals(256, parse("{}").config.cacheMb)
        assertEquals(64, parse("""{"cacheMb":64}""").config.cacheMb)
        assertEquals(0, parse("""{"cacheMb":0}""").config.cacheMb)
        assertEquals(32768, parse("""{"cacheMb":32768}""").config.cacheMb)
        assertEquals(emptyList(), parse("""{"cacheMb":0}""").warnings)
        for (bad in listOf("-1", "40000", "32769", "12.5", "\"64\"", "null", "true", "\"SECRETVALUE\"")) {
            val r = parse("""{"cacheMb":$bad}""")
            assertEquals(256, r.config.cacheMb, bad)
            assertEquals(1, r.warnings.size, bad)
            assertContains(r.warnings[0], "cacheMb")
            assertFalse("SECRETVALUE" in r.warnings[0], r.warnings[0])
            assertFalse("40000" in r.warnings[0], r.warnings[0])
        }
    }

    @Test
    fun configValidateRefusesACacheMbOutsideItsRange() {
        base.copy(cacheMb = 0).validate()
        base.copy(cacheMb = 32768).validate()
        for (bad in listOf(-1, 32769)) {
            val e = kotlin.runCatching { base.copy(cacheMb = bad).validate() }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException, "cacheMb $bad")
            assertContains(e.message ?: "", "cacheMb")
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
    fun removedFlacOutputFormatWarnsAndUsesDefault() {
        val r = parse("""{"outputFormat":"flac"}""")
        assertEquals(base.outputFormat, r.config.outputFormat)
        assertEquals(1, r.warnings.size)
        assertContains(r.warnings.single(), "outputFormat")
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
            """{"include":["[BADREGEX"],"outputFormat":"BADFORMAT","startTimeoutMs":"BADSTR","pollIntervalMs":99999999,"mp3Bitrate":"BADRATE","ytdlpVerbose":"BADBOOL","ffmpegLogLevel":"BADLEVEL","zzz":1}""",
        )
        assertTrue(r.warnings.size >= 8)
        assertNoLeak(r, "BADREGEX", "BADFORMAT", "BADSTR", "99999999", "BADRATE", "BADBOOL", "BADLEVEL")
        r.config.validate()
    }

    @Test
    fun aFullValidFileAppliesEverythingAndValidates() {
        val r = parse(
            """{"include":"living","exclude":["lamp"],"outputFormat":"mp4","startTimeoutMs":20000,"pollIntervalMs":1000,
            "mp3Bitrate":"320k","ytdlpVerbose":true,"ffmpegLogLevel":"info"}""",
        )
        assertEquals(emptyList(), r.warnings)
        val c = r.config
        assertEquals("mp4", c.outputFormat)
        assertEquals(20000L, c.startTimeoutMs)
        assertEquals(1000L, c.pollIntervalMs)
        assertEquals("320k", c.mp3Bitrate)
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
