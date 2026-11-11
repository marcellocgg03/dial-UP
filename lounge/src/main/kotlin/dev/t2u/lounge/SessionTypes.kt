package dev.t2u.lounge

import java.io.IOException

enum class SessionStatus { STOPPED, STARTING, RUNNING, REFRESHING, STOPPING }

/** A lounge endpoint answered with a status other than 2xx. [path] has no query (it would hold credentials). */
class LoungeHttpException(val status: Int, val path: String) : IOException("HTTP $status from $path")

/** The session could not be established or kept; the message names the client, the cause carries the detail. */
class SessionException(message: String, cause: Throwable? = null) : Exception(message, cause)

internal const val FAREWELL_TIMEOUT_MS = 2000L
internal const val MIN_STREAM_MS = 1000L
