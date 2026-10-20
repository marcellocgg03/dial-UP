package dev.t2u.upnp

/**
 * Where the pure-JVM modules report what happened. :app maps it onto EvidenceLog.event; tests use
 * [NoEvents] or a list collector. Implementations must not throw (callers still guard against it).
 * Never put signed URLs, tokens or pairing codes into [fields].
 */
fun interface EventSink {
    fun event(kind: String, fields: Map<String, Any?>)
}

/** An [EventSink] that drops everything. */
val NoEvents: EventSink = EventSink { _, _ -> }
