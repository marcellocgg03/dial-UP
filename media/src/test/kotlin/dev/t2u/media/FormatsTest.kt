package dev.t2u.media

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** formats.js: the fallback order, the file formats and mimeOf. */
class FormatsTest {
    @Test
    fun fallbackChainContainsOnlyStreamingFormats() {
        assertEquals(listOf("direct", "adts", "mp3"), FALLBACK_CHAIN)
        assertEquals(listOf("mp4"), FILE_FORMATS)
        assertTrue(FALLBACK_CHAIN.all { it in FORMATS })
    }

    @Test
    fun mimeOfUsesTheFormatMimeAndTheSourceMimeForDirect() {
        assertEquals("audio/aac", mimeOf("adts", "audio/webm"))
        assertEquals("audio/mpeg", mimeOf("mp3", "audio/webm"))
        assertEquals("audio/mp4", mimeOf("mp4", "audio/webm"))
        assertEquals("audio/webm", mimeOf("direct", "audio/webm"))
        assertFailsWith<IllegalArgumentException> { mimeOf("ogg", "audio/ogg") }
    }

    @Test
    fun dlnaFeaturesKeepTheNodeStrings() {
        assertEquals("DLNA.ORG_PN=AAC_ADTS_320;DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaFeatures("adts"))
        assertEquals("DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000", dlnaFeatures("direct"))
    }
}
