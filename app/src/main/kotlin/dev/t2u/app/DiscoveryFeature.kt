// Owner: Plan 01-04 (SSDP responder and DIAL servers) replaces the body of this class.
package dev.t2u.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DiscoveryFeature : SpikeFeature {
    override val name = "discovery"
    override val commands: Set<String> = emptySet()

    override fun start(host: SpikeHost) {}

    override fun stop() {}

    override fun handle(cmd: String, args: Map<String, String>) = CommandReply(false, """{"error":"unknown command"}""")

    override fun state(): JsonObject = buildJsonObject { put("ready", false) }
}
