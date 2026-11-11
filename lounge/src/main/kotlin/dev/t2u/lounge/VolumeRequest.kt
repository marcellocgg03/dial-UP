package dev.t2u.lounge

/** The level a setVolume request leads to, and whether the request's own target pointed the wrong way. */
internal data class DirectedVolume(val level: Int, val conflicted: Boolean)

/**
 * The direction rule for a setVolume request. Button requests carry an absolute target and a directional [delta]. The
 * target can be based on an older sender state; a volume-down request (negative delta) never raises the speaker above
 * its [current] level, and a volume-up request (positive delta) never lowers it. Without a delta, or with a zero one,
 * the requested level is kept. [DirectedVolume.conflicted] says the rule changed the requested level.
 */
internal fun directVolume(requested: Int, delta: Int?, current: Int): DirectedVolume {
    val level = when {
        delta != null && delta < 0 -> minOf(requested, current)
        delta != null && delta > 0 -> maxOf(requested, current)
        else -> requested
    }
    return DirectedVolume(level, conflicted = level != requested)
}
