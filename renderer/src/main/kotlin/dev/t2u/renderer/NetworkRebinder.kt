package dev.t2u.renderer

// Losing and regaining the network for Bridge, GPL-3.0-only. Extracted from Bridge, bodies unchanged. Bridge stays the
// composition root: it owns the components, the started/stopped flags, networkDown and [netLock]; this class reads and
// replaces them through [NetworkRebinderHost] at the moment of use and takes the same Mutex instance as [BridgeRepair],
// so a move still excludes a housekeeping repair exactly as before.

import dev.t2u.dial.DialAccessPolicy
import dev.t2u.upnp.Ssdp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [NetworkRebinder] needs beyond what [BridgeRepairHost] gives the repair pass: the started flag, the right to mark the
 * network down and to replace the bridge's network, and the single-argument search. Every member is read when used.
 */
internal interface NetworkRebinderHost : BridgeRepairHost {
    val started: AtomicBoolean

    override var networkDown: Boolean

    override var net: BridgeNetwork

    /** One multicast search on the bridge's current client. */
    suspend fun multicastSearch(trigger: String): Boolean
}

internal class NetworkRebinder(private val host: NetworkRebinderHost, private val deps: BridgeDeps, private val netLock: Mutex) {
    private val started: AtomicBoolean get() = host.started
    private val stopped: AtomicBoolean get() = host.stopped
    private var networkDown: Boolean
        get() = host.networkDown
        set(value) {
            host.networkDown = value
        }
    private var net: BridgeNetwork
        get() = host.net
        set(value) {
            host.net = value
        }
    private val registry get() = host.registry
    private val accessPolicy get() = host.accessPolicy
    private val mediaServer get() = host.mediaServer
    private var responder
        get() = host.responder
        set(value) {
            host.responder = value
        }
    private var ssdp
        get() = host.ssdp
        set(value) {
            host.ssdp = value
        }

    private fun buildResponder(network: BridgeNetwork, policy: DialAccessPolicy) = host.buildResponder(network, policy)

    private fun clientEvents(reg: RendererRegistry) = host.clientEvents(reg)

    private suspend fun multicastSearch(trigger: String) = host.multicastSearch(trigger)

    private suspend fun step(name: String, phase: String, block: suspend () -> Unit) = host.step(name, phase, block)

    private fun emit(kind: String, vararg fields: Pair<String, Any?>) = host.emit(kind, *fields)

    /** See [Bridge.networkLost]. */
    suspend fun networkLost() {
        netLock.withLock {
            if (!started.get() || stopped.get()) return
            networkDown = true
            emit("bridge", "event" to "network-lost")
            for (receiver in host.receivers) receiver.onNetworkLost()
            // polls pause on every renderer: what a broken stream does on the speaker is not mirrored to the phone
            for (r in registry?.renderers().orEmpty()) step("renderer", "network-lost") { r.onNetworkLost() }
        }
    }

    /** See [Bridge.rebindNetwork]. */
    suspend fun rebindNetwork(newNet: BridgeNetwork) {
        netLock.withLock {
            if (!started.get() || stopped.get()) return
            val oldIp = net.wifiIp.hostAddress.orEmpty()
            val newIp = newNet.wifiIp.hostAddress.orEmpty()
            val policy = accessPolicy
            val reg = registry
            val oldResponder = responder
            val oldClient = ssdp

            // Same-address returns need no listener restart; stopping its HTTP engine would break active stream connections.
            if (oldIp != newIp || mediaServer?.isListening() == false) {
                step("media", "rebind") { rebindMedia(newIp) }
            }
            // a stopped responder is never left installed: the field is cleared as soon as the old one is stopped, and only a
            // replacement that started is assigned; housekeeping rebuilds what a failed step leaves missing
            step("responder-stop", "rebind") {
                try {
                    withContext(Dispatchers.IO) { oldResponder?.stop() }
                } finally {
                    responder = null
                }
            }
            // the old listeners are closed before the new ones open, so a same-address move never fights itself for the socket
            net = newNet
            if (policy != null) {
                step("responder-start", "rebind") {
                    responder = withContext(Dispatchers.IO) { buildResponder(newNet, policy) }
                }
            }
            step("ssdp-client", "rebind") {
                try {
                    withContext(Dispatchers.IO) { oldClient?.stop() }
                } finally {
                    ssdp = null
                }
                if (reg != null) {
                    val fresh = Ssdp(listOf(newNet.wifiIp), clientEvents(reg), newNet.ssdpSearchTarget)
                    withContext(Dispatchers.IO) { fresh.start() }
                    // start() reports a failed bind through onError and does not throw: a deaf client is not installed
                    if (fresh.isBound()) ssdp = fresh else withContext(Dispatchers.IO) { fresh.stop() }
                }
            }
            var moved = 0
            for (receiver in host.receivers) {
                val running = receiver.status == "running"
                step("dial", "rebind") { receiver.rebindDial(newIp) }
                if (running) moved++
            }
            // the renderers learn the new address (they re-set the loaded track in their own scopes, one slow speaker holds up no other)
            for (r in registry?.renderers().orEmpty()) step("renderer", "rebind") { r.onNetworkChanged(newIp) }
            for (receiver in host.receivers) receiver.onNetworkReturned()
            networkDown = false
            step("search", "rebind") { multicastSearch("rebind") }
            emit("bridge", "event" to "rebound", "oldIp" to oldIp, "newIp" to newIp, "receivers" to moved)
        }
    }

    /**
     * Moves the media server to [host], trying again after each [BridgeDeps.mediaRebindBackoffMs] delay when the bind
     * fails. One `media-rebind` event per attempt. The last failure is rethrown, so [step] logs it as before. The caller holds
     * [netLock], which a retry holds for the length of the waits.
     */
    private suspend fun rebindMedia(host: String) {
        val media = mediaServer ?: return
        val waits = deps.mediaRebindBackoffMs
        var attempt = 0
        while (true) {
            attempt++
            try {
                withContext(Dispatchers.IO) { media.rebindHost(host) }
                emit("media-rebind", "attempt" to attempt, "ok" to true)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emit("media-rebind", "attempt" to attempt, "ok" to false, "error" to t.javaClass.simpleName)
                if (attempt > waits.size || stopped.get()) throw t
                delay(waits[attempt - 1])
                if (stopped.get()) return
            }
        }
    }
}
