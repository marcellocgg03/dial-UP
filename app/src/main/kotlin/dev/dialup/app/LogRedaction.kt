// SPDX-License-Identifier: GPL-3.0-only
// Original Kotlin code for DialUP (Android port of Tube2U[PnP]).
package dev.dialup.app

private val STREAM_TOKEN_PATH = Regex("(/stream/)([0-9a-f]{6})[0-9a-f]{26}")

/**
 * [uri] with the stream token of a `/stream/<token>.<ext>` path cut to its first six characters. The token is the only
 * control over a track that is playing; the log is shared by design, and six characters still tell two tokens apart.
 */
internal fun redactStreamToken(uri: String): String = STREAM_TOKEN_PATH.replace(uri) { "${it.groupValues[1]}${it.groupValues[2]}.." }
