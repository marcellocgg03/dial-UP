package dev.t2u.upnp

// Original Kotlin tests, GPL-3.0-only: the contract of the speaker-text helpers (SpeakerText.kt).

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeakerTextTest {
    @Test
    fun `a text of exactly 200 characters is returned unchanged`() {
        val text = "a".repeat(200)
        assertEquals(text, cleanSpeakerText(text))
    }

    @Test
    fun `a text of 201 characters is cut to 199 and an ellipsis`() {
        val out = cleanSpeakerText("a".repeat(201))
        assertEquals("a".repeat(199) + "\u2026", out)
        assertEquals(200, out.length)
    }

    @Test
    fun `control characters at the edges are trimmed away`() {
        assertEquals("Kitchen", cleanSpeakerText("\u0000Kitchen\u001F"))
    }

    @Test
    fun `tab newline and the C1 next-line become spaces inside the text`() {
        assertEquals("a b c d", cleanSpeakerText("a\tb\nc\u0085d"))
    }

    @Test
    fun `a text of control characters only gives an empty text`() {
        assertEquals("", cleanSpeakerText("\u0000\u0007\n\t\u0085"))
    }

    @Test
    fun `a surrogate pair is never split by the cut`() {
        val out = cleanSpeakerText("a".repeat(198) + "\uD83D\uDE00" + "b".repeat(10))
        assertTrue(out.length <= 200, "length ${out.length}")
        assertTrue(out.endsWith("\u2026"), out)
        for (i in out.indices) {
            if (Character.isHighSurrogate(out[i])) assertTrue(i + 1 < out.length && Character.isLowSurrogate(out[i + 1]), "unpaired high surrogate at $i")
            if (Character.isLowSurrogate(out[i])) assertTrue(i > 0 && Character.isHighSurrogate(out[i - 1]), "unpaired low surrogate at $i")
        }
    }

    @Test
    fun `a smaller max is honoured with the ellipsis inside it`() {
        val out = cleanSpeakerText("x".repeat(50), max = 10)
        assertEquals(10, out.length)
        assertTrue(out.endsWith("\u2026"), out)
    }

    @Test
    fun `applying the helper to its own output changes nothing`() {
        val once = cleanSpeakerText("Bad\tthing\n" + "q".repeat(5000))
        assertEquals(once, cleanSpeakerText(once))
    }

    @Test
    fun `speakerFailureText cleans and bounds an exception message`() {
        val out = speakerFailureText(IllegalStateException("Bad\n" + "q".repeat(5000)))
        assertTrue(out.length <= 200, "length ${out.length}")
        assertTrue(out.none { Character.isISOControl(it) }, out)
        assertTrue(out.endsWith("\u2026"), out)
    }

    @Test
    fun `speakerFailureText falls back to the class simple name`() {
        assertEquals("RuntimeException", speakerFailureText(RuntimeException()))
        assertEquals("RuntimeException", speakerFailureText(RuntimeException("\u0007")))
    }
}
