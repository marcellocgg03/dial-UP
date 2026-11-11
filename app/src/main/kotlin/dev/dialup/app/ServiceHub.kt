package dev.dialup.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-level handle so the command receiver can reach the running service. */
object ServiceHub {
    private val serviceState = MutableStateFlow<BridgeService?>(null)

    /** The running service as a flow, so the GUI follows it live. */
    val serviceFlow: StateFlow<BridgeService?> = serviceState.asStateFlow()

    var service: BridgeService?
        get() = serviceState.value
        set(value) {
            serviceState.value = value
        }

    /** The cast feature once the service has started its features; null otherwise. */
    val cast = MutableStateFlow<CastFeature?>(null)
}
