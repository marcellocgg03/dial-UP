package dev.t2u.media

import java.security.SecureRandom

/**
 * The feed tokens a [MediaServer] has handed to ffmpeg: token to video ID. A token is a secret capability for one ffmpeg
 * attempt, a 128-bit random number as lowercase hex.
 *
 * A token is in use from ffmpeg's first fetch ([lookup]) until the attempt's process has ended ([release]): ffmpeg
 * reconnects inside one process (after a pause, at the URL cut), however many newer tokens were made meanwhile. Only
 * tokens never used are evicted by age, oldest first (never the one just made), when the table passes [max]; a token in use is never evicted, so
 * with every entry in use the table grows past [max] (the ffmpeg permit limit bounds it, and each is released at process end).
 *
 * Every access is under one lock.
 */
internal class FeedTokens(private val max: Int, private val random: SecureRandom = SecureRandom()) {
    private class Entry(val videoId: String, var used: Boolean = false)

    private val entries = LinkedHashMap<String, Entry>()

    /** Makes a new token for [videoId] and drops the oldest unused ones while the table is over its cap. */
    fun register(videoId: String): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        synchronized(entries) {
            entries[token] = Entry(videoId)
            if (entries.size > max) {
                val oldest = entries.entries.iterator()
                while (entries.size > max && oldest.hasNext()) {
                    val e = oldest.next()
                    // the token just made is not used yet, but it is about to be handed to ffmpeg
                    if (!e.value.used && e.key != token) oldest.remove()
                }
            }
        }
        return token
    }

    /** The video ID of [token], or null for a token never made, evicted or released; the token is in use from now on. */
    fun lookup(token: String): String? = synchronized(entries) {
        entries[token]?.also { it.used = true }?.videoId
    }

    /** Removes [token]: its attempt's ffmpeg has ended (or never started). */
    fun release(token: String) {
        synchronized(entries) { entries.remove(token) }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}
