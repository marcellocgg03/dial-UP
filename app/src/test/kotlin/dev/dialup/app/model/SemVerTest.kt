// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemVerTest {
    private fun v(s: String) = assertNotNull(SemVer.parse(s), s)

    @Test
    fun parsesPlainPrefixedAndBuildVersions() {
        assertEquals(SemVer(0, 9, 0, listOf("beta", "1")), v("0.9.0-beta.1"))
        assertEquals(v("0.9.0-beta.1"), v("v0.9.0-beta.1"))
        assertEquals(SemVer(1, 2, 3, emptyList()), v("V1.2.3+build.7"))
    }

    @Test
    fun refusesWhatIsNotSemantic() {
        for (s in listOf("", "1.2", "1.2.3.4", "01.2.3", "1.2.3-", "1.2.3-beta..1", "1.2.3-01", "latest", "1.2.x", "99999999999.0.0")) {
            assertNull(SemVer.parse(s), "'$s'")
        }
    }

    @Test
    fun ordersByTheSemverRules() {
        val order = listOf("0.9.0-beta.1", "0.9.0-beta.2", "0.9.0-beta.10", "0.9.0-rc.1", "0.9.0", "0.9.1-beta.1", "0.10.0", "1.0.0")
        for (i in 0 until order.size - 1) {
            assertTrue(v(order[i]) < v(order[i + 1]), "${order[i]} < ${order[i + 1]}")
        }
        assertEquals(0, v("1.0.0+a").compareTo(v("1.0.0+b")))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-alpha.1"))
        assertTrue(v("1.0.0-1") < v("1.0.0-a"))
    }

    @Test
    fun toStringDropsBuildMetadata() {
        assertEquals("0.9.0-beta.1", v("v0.9.0-beta.1+x").toString())
    }
}
