package dev.dialup.app

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every `feature-error` event the app writes goes through [featureErrorFields] or [featureErrorLine], so its message
 * is cut to 200 characters (the evidence log is shared by the user and a message can carry paths, URLs or names).
 * A source sweep: a new hand-built map at an event call fails here.
 */
class FeatureErrorSitesTest {
    /** Files still to be routed; the plan ends with this empty. */
    private val PENDING_FILES: Set<String> = setOf(
        "ShareLogActivity.kt", "HeldLocks.kt", "CastNotifier.kt", "SpeakerStores.kt", "BridgeService.kt", "CastFeature.kt",
    )

    private fun mainSources(): File {
        val candidates = listOf(File("src/main/kotlin"), File("app/src/main/kotlin"))
        return candidates.first { it.isDirectory }
    }

    /** The text of the call that encloses the literal at [from], up to its closing parenthesis. */
    private fun callText(text: String, from: Int): String {
        var depth = 1
        var i = from
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        return text.substring(from, i)
    }

    @Test
    fun everyFeatureErrorEventUsesACappedHelper() {
        val literal = "\"feature-error\""
        val offenders = mutableListOf<String>()
        var sites = 0
        mainSources().walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            if (file.name in PENDING_FILES) return@forEach
            val text = file.readText()
            var at = text.indexOf(literal)
            while (at >= 0) {
                sites++
                val call = callText(text, at + literal.length)
                if (!call.contains("featureErrorFields(") && !call.contains("featureErrorLine(")) {
                    val line = text.substring(0, at).count { it == '\n' } + 1
                    offenders += "${file.name}:$line"
                }
                at = text.indexOf(literal, at + literal.length)
            }
        }
        assertTrue(sites > 0, "the sweep found no feature-error site, so it looks in the wrong place")
        assertEquals(emptyList(), offenders, "feature-error events built by hand (use featureErrorFields or featureErrorLine)")
    }
}
