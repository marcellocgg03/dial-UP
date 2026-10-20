package dev.t2u.dial

import java.util.UUID

/** Where a variant's LOCATION host or bind address comes from. */
sealed interface LocationHost {
    /** The phone's Wi-Fi IPv4 address, known only at run time. */
    data object WifiIp : LocationHost

    data class Fixed(val host: String) : LocationHost
}

/** One diagnostic DIAL receiver of the Phase 1 spike. Names are ASCII so they never look like the Docker server's entries. */
data class DialVariant(
    val id: String,
    val name: String,
    val port: Int,
    val serverHeader: String,
    val uuid: String,
    val locationHost: LocationHost,
    val bindHost: LocationHost,
)

/**
 * The four spike variants (plan 01-04 variant table). Differences from A, the Node-parity baseline:
 * B has an Android SERVER header, C a stable name-based UUID, D a 127.0.0.1 LOCATION and bind.
 * [newUuid] supplies the random UUIDs of A, B and D.
 */
fun spikeVariants(osVersion: String, androidRelease: String, newUuid: () -> String): List<DialVariant> {
    val linuxServer = "Linux/$osVersion UPnP/1.1 famium/0.0.1"
    return listOf(
        DialVariant("A", "T2U-SPIKE A base", 3100, linuxServer, newUuid(), LocationHost.WifiIp, LocationHost.WifiIp),
        DialVariant("B", "T2U-SPIKE B server", 3101, "Android/$androidRelease UPnP/1.1 Tube2U/0.1", newUuid(), LocationHost.WifiIp, LocationHost.WifiIp),
        DialVariant(
            "C",
            "T2U-SPIKE C uuid",
            3102,
            linuxServer,
            UUID.nameUUIDFromBytes("t2u-spike-C".toByteArray()).toString(),
            LocationHost.WifiIp,
            LocationHost.WifiIp,
        ),
        DialVariant("D", "T2U-SPIKE D loc", 3103, linuxServer, newUuid(), LocationHost.Fixed("127.0.0.1"), LocationHost.Fixed("127.0.0.1")),
    )
}
