package dev.t2u.upnp

import java.net.MulticastSocket

/**
 * Runs [block] against this freshly created socket. If it throws anything, the socket is closed first (a failure of
 * `close` is swallowed) and the original throwable is rethrown, so a failed option, bind or group join can never leave
 * an open socket behind that keeps a UDP port such as 1900 bound.
 */
internal inline fun <S : MulticastSocket, T> S.setupOrClose(block: S.() -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        runCatching { close() }
        throw t
    }
