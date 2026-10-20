package dev.t2u.lounge

// Port of yt-cast-receiver 2.1.0 lib/utils/DataStore (MIT, Copyright (c) Patrick Kan).

import kotlinx.serialization.json.JsonElement

/**
 * Key-value storage the lounge [Session] uses to keep a receiver's identity across restarts
 * (`mdxContext.cl` / `mdxContext.m` with screenId and deviceId, the DIAL `app.pid`). One instance
 * is bound to one speaker (UDN). Implementations write atomically (temp file then rename).
 */
interface DataStore {
    /** Returns the stored value, or null when the key is missing. Never throws on storage errors (they read as missing). */
    suspend fun get(key: String): JsonElement?

    /** Stores [value] under [key]; a null value removes the key. Storage errors are logged by the implementation, not thrown. */
    suspend fun set(key: String, value: JsonElement?)
}
