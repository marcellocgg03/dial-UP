// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.t2u.dial.DialRequest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DialEventsTest {
    @Test
    fun theHttpReqBodyIsTheStoredBodyUnchanged() {
        val req = DialRequest(
            method = "POST",
            uri = "/ytcr/apps/YouTube",
            httpVersion = "HTTP/1.1",
            remoteHost = "192.168.50.45",
            remotePort = 51234,
            status = 200,
            headers = mapOf(
                "User-Agent" to "YouTube/1",
                "Host" to "192.168.50.38:3000",
                "Origin" to "https://www.youtube.com",
                "Content-Type" to "application/x-www-form-urlencoded",
            ),
            body = "pairingCode=<redacted len 12>&theme=cl",
        )
        val fields = dialHttpReqFields(req)
        // DialServer redacted the body once; a second pass would turn the length into that of the marker text
        assertEquals("pairingCode=<redacted len 12>&theme=cl", fields["body"])
        assertEquals(req.uri, fields["uri"])
        assertEquals("dial", fields["server"])
        assertEquals("POST", fields["method"])
        assertEquals("HTTP/1.1", fields["httpVersion"])
        assertEquals("192.168.50.45:51234", fields["src"])
        assertEquals(200, fields["status"])
        assertEquals("YouTube/1", fields["ua"])
        assertEquals("192.168.50.38:3000", fields["host"])
        assertEquals("https://www.youtube.com", fields["origin"])
        assertEquals("application/x-www-form-urlencoded", fields["contentType"])
    }
}
