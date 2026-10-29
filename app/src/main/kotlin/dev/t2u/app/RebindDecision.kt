package dev.t2u.app

// Plan 05-10: what CastFeature does once the 2 s network debounce ends. One pure function so the decision table is
// tested on the JVM. GPL-3.0-only.

enum class RebindAction {
    /** The net-rebind switch is off: nothing is frozen, moved or restarted (the no-rebind experiment stays pure). */
    SKIP_DISABLED,

    /** No Wi-Fi address right now: keep the bridge as it is (renderers are frozen) and wait for the next address change. */
    MARK_LOST_ONLY,

    /** Move the running bridge onto the new address; receivers, sessions, tokens and loaded tracks are kept. */
    REBIND_IN_PLACE,

    /** There is no bridge yet: start one. */
    START,

    /** reload-config: stop the bridge without clearing the speakers and start it again so the edited file applies. */
    RESTART,

    /** Only for the retry reason: a bridge exists or there is no Wi-Fi address, so there is nothing to start. */
    NOTHING,
}

/**
 * @param reason `config` (reload-config), `retry` (plan 05-19: CastFeature looks again for a bridge that failed to start)
 * or a [NetworkMonitor] event name (`lost`, `available`, `linkPropertiesChanged`)
 * @param wifiIp the Wi-Fi IPv4 address when the debounce ended, null when there is none
 */
fun rebindAction(reason: String, rebindEnabled: Boolean, hasBridge: Boolean, wifiIp: String?): RebindAction {
    if (reason == "config") return if (hasBridge) RebindAction.RESTART else RebindAction.START
    // the one start path that does not follow a Wi-Fi change, so the switch (which governs following changes) is ignored
    if (reason == "retry") return if (!hasBridge && wifiIp != null) RebindAction.START else RebindAction.NOTHING
    if (!rebindEnabled) return RebindAction.SKIP_DISABLED
    if (wifiIp == null) return RebindAction.MARK_LOST_ONLY
    return if (hasBridge) RebindAction.REBIND_IN_PLACE else RebindAction.START
}
