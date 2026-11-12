package dev.t2u.renderer

// The SOAP calls one Renderer sends to its speaker, GPL-3.0-only.

import dev.t2u.upnp.DeviceDescription
import dev.t2u.upnp.SoapClient
import dev.t2u.upnp.UpnpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient

/**
 * Sends the Renderer's SOAP actions through one [SoapClient] and refuses them once the renderer is destroyed.
 * [description] and [destroyed] are read at every call, so they follow the Renderer's own state; [note] is the
 * Renderer's event helper, so a failed action is logged exactly as before.
 */
internal class SoapTransport(
    http: OkHttpClient,
    private val description: () -> DeviceDescription?,
    private val destroyed: () -> Boolean,
    private val clock: () -> Long,
    private val note: (level: String, message: String) -> Unit,
    allowLoopback: Boolean = false,
) {
    private val soap = SoapClient(http, allowLoopback)

    suspend fun call(svc: String, action: String, vararg params: Pair<String, Any?>): Map<String, String> {
        val service = description()?.services?.get(SERVICE_ID_PREFIX + svc)
            ?: throw UpnpError("$svc service not provided", "ENOSERVICE")
        // A destroyed Renderer sends no more SOAP: the lounge shutdown that follows destroy() reads the position and the
        // volume for its last state report, and a speaker that never answers would hold it for the SOAP timeout per call.
        if (destroyed()) throw UpnpError("the renderer was destroyed", "EDESTROYED")
        return soap.callAction(service, action, params.toList())
    }

    /** A command whose failure the caller reports (false) but that must not throw. */
    suspend fun ok(svc: String, action: String, vararg params: Pair<String, Any?>): Boolean =
        try {
            call(svc, action, *params)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("warn", "$action failed: ${e.message}")
            false
        }

    /** A call whose failure is ignored (null). */
    suspend fun safeCall(svc: String, action: String, vararg params: Pair<String, Any?>): Map<String, String>? =
        try {
            call(svc, action, *params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note("debug", "$action failed (ignored): ${e.message}")
            null
        }

    suspend fun transportState(): String? =
        safeCall(AV_TRANSPORT, "GetTransportInfo", "InstanceID" to 0)?.get("CurrentTransportState")

    suspend fun waitForState(state: String, timeoutMs: Long): Boolean {
        val end = clock() + timeoutMs
        while (clock() < end) {
            if (transportState() == state) return true
            delay(TRANSPORT_WAIT_STEP_MS)
        }
        return false
    }
}
