// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseListTest {
    private val base = "https://github.com/marcellocgg03/dial-UP/releases/tag/"

    private fun entry(tag: String, url: String = base + tag, draft: Boolean = false, pre: Boolean = false) =
        """{"id": 1, "tag_name": "$tag", "html_url": "$url", "draft": $draft, "prerelease": $pre,
            "published_at": "2026-11-04T10:00:00Z", "assets": [], "author": {"login": "x"}}"""

    private val list = "[" + listOf(
        entry("v0.9.0-beta.1", pre = true),
        entry("v0.9.0-beta.10", pre = true),
        entry("v0.9.0-beta.2"),
        entry("v9.9.9", draft = true),
        entry("nightly"),
        entry("v5.0.0", url = "https://evil.example/x"),
    ).joinToString(",") + "]"

    @Test
    fun anEmptyListHasNoRelease() {
        assertNull(newestRelease("[]"))
    }

    @Test
    fun malformedTextIsNotAnError() {
        assertNull(newestRelease("{\"a\":1}"))
        assertNull(newestRelease("[{\"tag_name\": \"v1.0.0\", "))
        assertNull(newestRelease(""))
        assertNull(newestRelease("[1, \"a\", null]"))
    }

    @Test
    fun picksTheMaximumByVersionWhateverTheOrder() {
        val items = listOf(
            entry("v0.9.0-beta.1", pre = true),
            entry("v0.9.0-beta.10", pre = true),
            entry("v0.9.0-beta.2"),
            entry("v9.9.9", draft = true),
            entry("nightly"),
            entry("v5.0.0", url = "https://evil.example/x"),
        )
        for (perm in listOf(items, items.reversed(), items.shuffled(java.util.Random(7)))) {
            val r = assertNotNull(newestRelease("[" + perm.joinToString(",") + "]"))
            assertEquals("v0.9.0-beta.10", r.tag)
            assertTrue(r.prerelease)
            assertEquals(base + "v0.9.0-beta.10", r.url)
        }
        assertEquals("v0.9.0-beta.10", newestRelease(list)!!.tag)
    }

    @Test
    fun allowsOnlyReleasePagesOfThisRepository() {
        assertTrue(isAllowedReleaseUrl(base + "v0.9.0-beta.1"))
        assertTrue(isAllowedReleaseUrl("https://GitHub.com/MarcelloCGG03/DIAL-up/releases/tag/v1"))
        for (u in listOf(
            "http://github.com/marcellocgg03/dial-UP/releases/tag/v1",
            "https://github.com.evil.example/marcellocgg03/dial-UP/releases/tag/v1",
            "https://user@github.com/marcellocgg03/dial-UP/releases/tag/v1",
            "https://github.com@evil.example/marcellocgg03/dial-UP/releases/tag/v1",
            "https://github.com/other/dial-UP/releases/tag/v1",
            "https://github.com/marcellocgg03/dial-UP/releases/../../evil/x",
            "https://github.com/marcellocgg03/dial-UP/releases/tag/%2e%2e/x",
            "https://github.com:8443/marcellocgg03/dial-UP/releases/tag/v1",
            "https://github.com/marcellocgg03/dial-UP/releases",
            "https://github.com/marcellocgg03/dial-UP/releases/",
            "not a url",
            "",
        )) {
            assertFalse(isAllowedReleaseUrl(u), u)
        }
    }

    @Test
    fun safeUrlFallsBackToTheReleasesPage() {
        assertEquals(RELEASES_PAGE_URL, safeReleaseUrl(null))
        assertEquals(RELEASES_PAGE_URL, safeReleaseUrl("https://evil.example/x"))
        assertEquals(base + "v1.0.0", safeReleaseUrl(base + "v1.0.0"))
    }

    @Test
    fun theApiUrlIsHttpsToGithub() {
        assertTrue(RELEASES_API_URL.startsWith("https://api.github.com/repos/marcellocgg03/dial-UP/releases"))
    }
}
