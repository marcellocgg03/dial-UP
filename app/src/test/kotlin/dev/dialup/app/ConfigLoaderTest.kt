package dev.dialup.app

import dev.t2u.renderer.Config
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SVC-07 / DISC-04: files/config.json decides the effective Config; every failure gives the defaults. */
class ConfigLoaderTest {
    @TempDir
    lateinit var dir: File

    private fun write(text: String): File = File(dir, CONFIG_FILE).apply { writeText(text) }

    private val defaults get() = Config(dataDir = File(dir, "cast"))

    @Test
    fun validKeysChangeTheEffectiveConfig() {
        write("""{"outputFormat":"mp4","exclude":["lamp"],"ffmpegLogLevel":"debug"}""")
        val r = loadAppConfig(dir)
        assertTrue(r.found)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertEquals("mp4", r.config.outputFormat)
        assertFalse(r.config.nameFilter.accepts("Lamp"))
        assertTrue(r.config.nameFilter.accepts("Kitchen"))
        assertEquals("debug", r.config.ffmpegLogLevel)
        assertEquals(File(dir, "cast"), r.config.dataDir)
    }

    @Test
    fun missingFileGivesThePlainDefaults() {
        val r = loadAppConfig(dir)
        assertFalse(r.found)
        assertTrue(r.warnings.isEmpty())
        assertEquals("auto", r.config.outputFormat)
        assertTrue(r.config.nameFilter.include.isEmpty())
        assertTrue(r.config.nameFilter.exclude.isEmpty())
        assertTrue(r.config.ytdlpVerbose)
        assertEquals("error", r.config.ffmpegLogLevel)
        assertEquals(defaults, r.config)
    }

    @Test
    fun invalidContentKeepsTheDefaultsAndWarnsWithoutValues() {
        val secretPattern = "SECRETPATTERN("
        val secretText = "SECRETVALUE"
        val cases = listOf(
            "{not json $secretText" to "not valid JSON",
            """["$secretText"]""" to "not a JSON object",
            """{"outputFormat":"$secretText"}""" to "outputFormat",
            """{"ytdlpVerbose":"$secretText"}""" to "ytdlpVerbose",
            """{"startTimeoutMs":99999999}""" to "startTimeoutMs",
            """{"include":["$secretPattern"]}""" to "include[0]",
            """{"nameSuffix":"$secretText"}""" to "nameSuffix",
        )
        for ((content, mention) in cases) {
            write(content)
            val r = loadAppConfig(dir)
            assertTrue(r.found, content)
            assertEquals(defaults, r.config, content)
            assertTrue(r.warnings.isNotEmpty(), content)
            assertTrue(r.warnings.any { mention in it }, "$content -> ${r.warnings}")
            for (w in r.warnings) {
                assertFalse(secretText in w, w)
                assertFalse(secretPattern in w, w)
                assertFalse("99999999" in w, w)
            }
        }
    }

    @Test
    fun aFileOverSixtyFourKibIsIgnoredWithOneWarning() {
        write("""{"outputFormat":"mp4","pad":"${"x".repeat(70 * 1024)}"}""")
        val r = loadAppConfig(dir)
        assertTrue(r.found)
        assertEquals(1, r.warnings.size, r.warnings.toString())
        assertEquals(defaults, r.config)
    }

    @Test
    fun summaryHoldsTheSettingsAndCountsButNoPatternText() {
        write(
            """{"outputFormat":"mp3","startTimeoutMs":15000,"pollIntervalMs":1500,"mp3Bitrate":"256k",""" +
                """"ytdlpVerbose":true,"ffmpegLogLevel":"info","include":["LIVINGROOMSECRET","kitchen"],"exclude":"LAMPSECRET","bogus":1}""",
        )
        val r = loadAppConfig(dir)
        val s: JsonObject = configSummary(r)
        assertEquals("mp3", s["format"]!!.jsonPrimitive.content)
        assertEquals(15000L, s["startTimeoutMs"]!!.jsonPrimitive.long)
        assertEquals(1500L, s["pollIntervalMs"]!!.jsonPrimitive.long)
        assertEquals("256k", s["mp3Bitrate"]!!.jsonPrimitive.content)
        assertTrue(s["ytdlpVerbose"]!!.jsonPrimitive.boolean)
        assertEquals("info", s["ffmpegLogLevel"]!!.jsonPrimitive.content)
        assertTrue(s["prefetch"]!!.jsonPrimitive.boolean)
        assertTrue(s["gapless"]!!.jsonPrimitive.boolean)
        assertEquals(256, s["cacheMb"]!!.jsonPrimitive.int)
        assertEquals(2, s["includeCount"]!!.jsonPrimitive.int)
        assertEquals(1, s["excludeCount"]!!.jsonPrimitive.int)
        assertTrue(s["found"]!!.jsonPrimitive.boolean)
        val warnings: JsonArray = s["warnings"]!!.jsonArray
        assertEquals(r.warnings, warnings.map { (it as JsonPrimitive).content })
        assertTrue(warnings.isNotEmpty())
        val text = s.toString()
        assertFalse("LIVINGROOMSECRET" in text)
        assertFalse("LAMPSECRET" in text)
        assertFalse("kitchen" in text)
    }

    @Test
    fun summaryOfTheDefaultsWhenThereIsNoFile() {
        val s = configSummary(loadAppConfig(dir))
        assertEquals("auto", s["format"]!!.jsonPrimitive.content)
        assertTrue(s["prefetch"]!!.jsonPrimitive.boolean)
        assertTrue(s["gapless"]!!.jsonPrimitive.boolean)
        assertEquals(256, s["cacheMb"]!!.jsonPrimitive.int)
        assertEquals(0, s["includeCount"]!!.jsonPrimitive.int)
        assertEquals(0, s["excludeCount"]!!.jsonPrimitive.int)
        assertFalse(s["found"]!!.jsonPrimitive.boolean)
        assertTrue(s["warnings"]!!.jsonArray.isEmpty())
    }

    @Test
    fun prefetchGaplessAndCacheMbTravelFromTheFileIntoTheConfigAndTheSummary() {
        write("""{"prefetch":false,"gapless":false,"cacheMb":64}""")
        val r = loadAppConfig(dir)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertFalse(r.config.prefetch)
        assertFalse(r.config.gapless)
        assertEquals(64, r.config.cacheMb)
        val s = configSummary(r)
        assertFalse(s["prefetch"]!!.jsonPrimitive.boolean)
        assertFalse(s["gapless"]!!.jsonPrimitive.boolean)
        assertEquals(64, s["cacheMb"]!!.jsonPrimitive.int)
        val f = configEvidenceFields(r)
        assertEquals(false, f["prefetch"])
        assertEquals(64L, f["cacheMb"])
    }

    @Test
    fun logLevelTravelsFromTheFileIntoTheSummaryAndTheEvidenceFields() {
        assertEquals("info", configSummary(loadAppConfig(dir))["logLevel"]!!.jsonPrimitive.content)
        write("""{"logLevel":"debug"}""")
        val r = loadAppConfig(dir)
        assertTrue(r.warnings.isEmpty(), r.warnings.toString())
        assertEquals("debug", r.config.logLevel)
        assertEquals("debug", configSummary(r)["logLevel"]!!.jsonPrimitive.content)
        assertEquals("debug", configEvidenceFields(r)["logLevel"])
    }

    @Test
    fun aDirectoryNamedConfigJsonGivesTheDefaultsAndOneWarning() {
        File(dir, CONFIG_FILE).mkdirs()
        val r = loadAppConfig(dir)
        assertEquals(defaults, r.config)
        assertEquals(1, r.warnings.size, r.warnings.toString())
    }

    @Test
    fun anUnreadableFileGivesTheDefaultsAndOneWarning() {
        val f = write("""{"outputFormat":"mp4"}""")
        val changed = f.setReadable(false, false)
        assumeFalse(!changed || f.canRead(), "running as a user that can read anything")
        try {
            val r = loadAppConfig(dir)
            assertEquals(defaults, r.config)
            assertEquals(1, r.warnings.size, r.warnings.toString())
        } finally {
            f.setReadable(true, false)
        }
    }

    @Test
    fun evidenceFieldsArePlainValues() {
        write("""{"outputFormat":"mp4","ytdlpVerbose":true,"include":"kitchenSECRET","zzz":1}""")
        val f = configEvidenceFields(loadAppConfig(dir))
        assertEquals("mp4", f["format"])
        assertEquals(true, f["ytdlpVerbose"])
        assertEquals(1L, f["includeCount"])
        assertEquals(true, f["found"])
        val w = f["warnings"] as List<*>
        assertEquals(1, w.size)
        assertTrue(w.all { it is String })
        assertFalse("kitchenSECRET" in f.toString())
    }
}
