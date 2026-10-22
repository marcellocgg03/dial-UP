package dev.t2u.upnp

// Plan 03-26: a standalone fake speaker on the LAN. RED skeleton: the option parser is not written yet.

data class FakeRendererOptions(
    val name: String,
    val port: Int,
    val bind: String,
    val sink: String,
    val playMimes: Set<String>?,
    val startDelayMs: Long,
)

sealed interface FakeRendererParse {
    data class Run(val options: FakeRendererOptions) : FakeRendererParse

    data object Help : FakeRendererParse

    data class Invalid(val message: String) : FakeRendererParse
}

fun parseFakeRendererOptions(args: List<String>, defaultBind: () -> String?): FakeRendererParse =
    FakeRendererParse.Invalid("not implemented")

fun isPrivateIpv4(ip: String): Boolean = false

fun fakeRendererUdn(name: String): String = ""

fun expandSink(list: String): String = ""
