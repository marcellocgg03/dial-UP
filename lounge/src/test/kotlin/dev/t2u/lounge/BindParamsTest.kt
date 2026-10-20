package dev.t2u.lounge

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BindParamsTest {
    private fun params() = BindParams("dev-1", "T2U capture", "ytcr", "cl", "T2U", "Capture", Random(42)).also {
        it.updateWithLoungeToken("TOKEN")
    }

    private fun query(q: String): Map<String, String> = q.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun encodeStrictSortsKeysAndEncodesEverythingButUnreserved() {
        assertEquals("a=1&b=x%20y&c=%2Cd%21%27%28%29%2A~", encodeStrict(mapOf("c" to ",d!'()*~", "b" to "x y", "a" to 1)))
        assertEquals("A=1&a=2", encodeStrict(mapOf("a" to 2, "A" to 1)), "code unit order: capitals first")
        assertEquals("k=%C5%91", encodeStrict(mapOf("k" to "ő")))
        assertEquals("a=1", encodeStrict(mapOf("a" to 1, "gone" to null)))
        assertEquals("m=3&n=14.393", encodeStrict(mapOf("n" to 14.393, "m" to 3.0)))
    }

    @Test
    fun initQueryHasTheRecordedShape() {
        val bp = params()
        val q = bp.toQueryString(BindType.INIT_SESSION)
        val keys = q.split('&').map { it.substringBefore('=') }
        assertEquals(keys.sorted(), keys)
        val m = query(q)
        assertEquals("LOUNGE_SCREEN", m["device"])
        assertEquals("dsp%2Cmic%2Cdpa%2Cntb", m["capabilities"])
        assertEquals("T2U%20capture", m["name"])
        assertEquals("TOKEN", m["loungeIdToken"])
        assertEquals("", m["obfuscatedGaiaId"])
        assertEquals("m", m["cst"])
        assertEquals("2", m["mdxVersion"])
        assertEquals("8", m["VER"])
        assertEquals("1", m["CVER"])
        assertTrue(m.getValue("RID").toInt() in 41000..49999)
        assertEquals(12, m.getValue("zx").length)
        // deviceInfo is the compact JSON of the default info plus brand and model, in that key order
        val info = java.net.URLDecoder.decode(m.getValue("deviceInfo"), "UTF-8")
        assertEquals(
            """{"year":0,"os":"Windows","osVersion":"10.0","chipset":"","clientName":"TVHTML5","dialAdditionalDataSupportLevel":"unsupported","mdxDialServerType":"MDX_DIAL_SERVER_TYPE_UNKNOWN","brand":"T2U","model":"Capture"}""",
            info,
        )
    }

    @Test
    fun ridGrowsAndAidFollowsTheMessages() {
        val bp = params()
        val rid0 = query(bp.toQueryString(BindType.INIT_SESSION)).getValue("RID").toInt()
        val rid1 = query(bp.toQueryString(BindType.INIT_SESSION)).getValue("RID").toInt()
        assertEquals(rid0 + 1, rid1)

        bp.updateWithMessage(IncomingMessage(0, "c", JsonArray(listOf(JsonPrimitive("SIDX"), JsonPrimitive(""), JsonPrimitive(8)))))
        bp.updateWithMessage(IncomingMessage(1, "S", JsonPrimitive("GSX")))
        bp.updateWithMessage(IncomingMessage(24, "pause", null))
        bp.updateWithMessage(IncomingMessage(10, "noop", null))
        val rpc = query(bp.toQueryString(BindType.RPC))
        assertEquals("rpc", rpc["RID"])
        assertEquals("SIDX", rpc["SID"])
        assertEquals("GSX", rpc["gsessionid"])
        assertEquals("24", rpc["AID"])
        assertEquals("0", rpc["CI"])
        assertEquals("xmlhttp", rpc["TYPE"])

        // a message that answers command 30 acknowledges it; a message without one takes the next id
        assertEquals("30", query(bp.toQueryString(BindType.SEND_MESSAGE, 30)).getValue("AID"))
        assertEquals("30", query(bp.toQueryString(BindType.SEND_MESSAGE, null)).getValue("AID"))
        assertEquals("31", query(bp.toQueryString(BindType.SEND_MESSAGE, null)).getValue("AID"))
    }

    @Test
    fun resetForgetsTheSessionAndRestartsAid() {
        val bp = params()
        bp.updateWithMessage(IncomingMessage(9, "pause", null))
        bp.reset()
        assertEquals(LoungeConstants.AID_START, bp.aid)
        val e = kotlin.runCatching { bp.toQueryString(BindType.INIT_SESSION) }.exceptionOrNull()
        assertTrue(e is IncompleteLoungeDataException)
    }
}
