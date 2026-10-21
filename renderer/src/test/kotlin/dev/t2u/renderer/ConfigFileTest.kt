package dev.t2u.renderer

// Tests for the optional config.json parser (DISC-04 name filter, SVC-07 forced format), GPL-3.0-only.

import org.junit.jupiter.api.Test
import java.io.File
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
}
