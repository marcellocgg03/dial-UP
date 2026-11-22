// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

import dev.t2u.dial.DialRequest

/**
 * The fields of the `http-req` event for a served DIAL request. [DialRequest.body] and [DialRequest.uri] were already
 * redacted once, where DialServer stored them (the pairing code is a secret for the lifetime of the pairing), so they are
 * logged as received: a second pass would measure the marker text and report a wrong length.
 */
internal fun dialHttpReqFields(req: DialRequest): Map<String, Any?> = mapOf(
    "server" to "dial",
    "method" to req.method,
    "uri" to req.uri,
    "httpVersion" to req.httpVersion,
    "src" to "${req.remoteHost}:${req.remotePort}",
    "status" to req.status,
    "ua" to req.headers["User-Agent"],
    "host" to req.headers["Host"],
    "origin" to req.headers["Origin"],
    "contentType" to req.headers["Content-Type"],
    "body" to req.body,
)
