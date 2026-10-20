package dev.t2u.lounge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MessageTest {
    private fun state(
        status: PlayerStatus,
        current: Video? = null,
        position: Double = 12.5,
        duration: Double = 200.0,
        cpn: String = "abcdef0123456789",
    ) = PlayerState(
        status = status,
        queue = PlaylistState(id = null, videoIds = emptyList(), previous = null, current = current, next = null, autoplay = null),
        position = position,
        duration = duration,
        volume = Volume(30, false),
        cpn = cpn,
    )

    @Test
    fun nowPlayingForAPlayingStateCarriesTheVideoAndItsPlaceInTheList() {
        val video = Video("X", null, VideoContext(playlistId = "L", index = 2))
        val m = OutgoingMessage.nowPlaying(5, state(PlayerStatus.PLAYING, video))
        assertEquals("nowPlaying", m.name)
        assertEquals(5, m.aid)
        assertEquals(
            listOf("currentTime", "duration", "cpn", "loadedTime", "videoId", "state", "seekableStartTime", "seekableEndTime", "listId", "currentIndex"),
            m.payload.keys.toList(),
        )
        assertEquals(200.0, m.payload["loadedTime"])
        assertEquals(1, m.payload["state"])
        assertEquals("X", m.payload["videoId"])
        assertEquals("L", m.payload["listId"])
        assertEquals(2, m.payload["currentIndex"])
    }

    @Test
    fun nowPlayingWithoutAVideoHasLoadedTimeOnlyWhileActive() {
        for (status in listOf(PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.LOADING)) {
            val m = OutgoingMessage.nowPlaying(null, state(status))
            assertEquals(listOf("loadedTime"), m.payload.keys.toList(), status.name)
        }
        assertTrue(OutgoingMessage.nowPlaying(null, state(PlayerStatus.STOPPED)).payload.isEmpty())
        assertTrue(OutgoingMessage.nowPlaying(null, null).payload.isEmpty())
    }

    @Test
    fun otherMessagesMatchMessageJs() {
        val sc = OutgoingMessage.onStateChange(1, state(PlayerStatus.PAUSED))
        assertEquals(
            listOf("state", "currentTime", "duration", "loadedTime", "seekableStartTime", "seekableEndTime", "cpn"),
            sc.payload.keys.toList(),
        )
        assertEquals(200.0, sc.payload["loadedTime"])
        assertEquals(0, OutgoingMessage.onStateChange(1, state(PlayerStatus.STOPPED)).payload["loadedTime"])

        val vol = OutgoingMessage.onVolumeChanged(2, Volume(42, true))
        assertEquals(mapOf<String, Any?>("volume" to 42, "muted" to true), vol.payload)

        assertEquals(mapOf<String, Any?>("autoplayMode" to "ENABLED"), OutgoingMessage.onAutoplayModeChanged(3, AutoplayMode.ENABLED).payload)
        assertEquals(mapOf<String, Any?>("autoplayMode" to "UNSUPPORTED"), OutgoingMessage.onAutoplayModeChanged(3, null).payload)

        val nav = OutgoingMessage.onHasPreviousNextChanged(4, NavInfo(hasPrevious = true, hasNext = false, autoplayMode = AutoplayMode.DISABLED))
        assertEquals(mapOf<String, Any?>("hasPrevious" to true, "hasNext" to false), nav.payload)
        assertEquals(mapOf<String, Any?>("hasPrevious" to false, "hasNext" to false), OutgoingMessage.onHasPreviousNextChanged(4, null).payload)

        assertEquals(mapOf<String, Any?>("videoId" to "V"), OutgoingMessage.autoplayUpNext(6, "V").payload)
        assertTrue(OutgoingMessage.autoplayUpNext(6, null).payload.isEmpty())

        val bye = OutgoingMessage.loungeScreenDisconnected()
        assertNull(bye.aid)
        assertEquals("loungeScreenDisconnected", bye.name)
        assertTrue(bye.payload.isEmpty())
    }

    @Test
    fun postDataUsesCountOfsAndDoubleUnderscoreSc() {
        val video = Video("X", null, VideoContext(playlistId = "L", index = 2))
        val pairs = sendMessagePostData(
            listOf(OutgoingMessage.nowPlaying(1, state(PlayerStatus.PLAYING, video, position = 14.393)), OutgoingMessage.onVolumeChanged(1, Volume(30, false))),
            ofs = 4,
        )
        assertEquals("count" to "2", pairs[0])
        assertEquals("ofs" to "4", pairs[1])
        assertEquals("req0__sc" to "nowPlaying", pairs[2])
        assertTrue(("req0_currentTime" to "14.393") in pairs)
        assertTrue(("req0_duration" to "200") in pairs, "an integral number prints without .0")
        assertTrue(("req1__sc" to "onVolumeChanged") in pairs)
        assertTrue(("req1_volume" to "30") in pairs)
        assertTrue(("req1_muted" to "false") in pairs, "booleans print as true/false")
        assertTrue(pairs.indexOf("req0__sc" to "nowPlaying") < pairs.indexOf("req1__sc" to "onVolumeChanged"))
    }

    @Test
    fun formEncodeIsTheWhatwgSerializer() {
        assertEquals("a+b=c%26d", formEncode(listOf("a b" to "c&d")))
        assertEquals("k=%C5%91+%7E+*-._", formEncode(listOf("k" to "ő ~ *-._")))
        assertEquals("k=%F0%9F%98%80", formEncode(listOf("k" to "😀")))
        assertEquals("x=1&y=2", formEncode(listOf("x" to "1", "y" to "2")))
    }

    @Test
    fun jsNumberPrintsLikeJavaScript() {
        assertEquals("1", jsNumber(1.0))
        assertEquals("0", jsNumber(0.0))
        assertEquals("0", jsNumber(-0.0))
        assertEquals("14.393", jsNumber(14.393))
        assertEquals("0.30000000000000004", jsNumber(0.1 + 0.2))
        assertEquals("123456789012", jsNumber(123456789012.0))
        assertEquals("0.000001", jsNumber(0.000001))
        assertEquals("1e-7", jsNumber(1e-7))
        assertEquals("1e+21", jsNumber(1e21))
        assertEquals("-2.5", jsNumber(-2.5))
        assertEquals("NaN", jsNumber(Double.NaN))
    }

    @Test
    fun ourFieldsMatchEveryRecordedPostFromTheNodeReceiver() {
        val text = MessageTest::class.java.getResourceAsStream("/fixtures/outgoing.jsonl")?.use { String(it.readBytes(), Charsets.UTF_8) }
            ?: fail("outgoing.jsonl is not on the classpath")
        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue(lines.size > 10)
        for (line in lines) {
            val fields = Json.parseToJsonElement(line).jsonObject["fields"]!!.jsonObject
                .entries.map { it.key to it.value.jsonPrimitive.content }
            val count = fields.first { it.first == "count" }.second.toInt()
            val ofs = fields.first { it.first == "ofs" }.second.toInt()
            val messages = (0 until count).map { i ->
                val name = fields.first { it.first == "req${i}__sc" }.second
                val f = fields.filter { it.first.startsWith("req${i}_") && it.first != "req${i}__sc" }
                    .associate { it.first.removePrefix("req${i}_") to it.second }
                build(name, f)
            }
            assertEquals(fields, sendMessagePostData(messages, ofs), "recorded post: $line")
        }
    }

    /** Builds our message from the state a recorded message describes. */
    private fun build(name: String, f: Map<String, String>): OutgoingMessage {
        fun status(code: String) = PlayerStatus.entries.first { it.code == code.toInt() }
        return when (name) {
            "nowPlaying" -> if ("videoId" in f) {
                val ctx = VideoContext(playlistId = f["listId"], index = f["currentIndex"]?.toInt(), ctt = f["ctt"], params = f["params"])
                OutgoingMessage.nowPlaying(
                    null,
                    state(status(f.getValue("state")), Video(f.getValue("videoId"), null, ctx), f.getValue("currentTime").toDouble(), f.getValue("duration").toDouble(), f.getValue("cpn")),
                )
            } else if ("loadedTime" in f) {
                OutgoingMessage.nowPlaying(null, state(PlayerStatus.PLAYING, null, duration = f.getValue("loadedTime").toDouble()))
            } else {
                OutgoingMessage.nowPlaying(null, state(PlayerStatus.STOPPED))
            }
            "onStateChange" -> OutgoingMessage.onStateChange(
                null,
                state(status(f.getValue("state")), null, f.getValue("currentTime").toDouble(), f.getValue("duration").toDouble(), f.getValue("cpn")),
            )
            "onVolumeChanged" -> OutgoingMessage.onVolumeChanged(null, Volume(f.getValue("volume").toInt(), f["muted"]?.toBooleanStrict() ?: false))
            "onAutoplayModeChanged" -> OutgoingMessage.onAutoplayModeChanged(null, AutoplayMode.valueOf(f.getValue("autoplayMode")))
            "onHasPreviousNextChanged" ->
                OutgoingMessage.onHasPreviousNextChanged(null, NavInfo(f.getValue("hasPrevious").toBooleanStrict(), f.getValue("hasNext").toBooleanStrict(), AutoplayMode.ENABLED))
            "autoplayUpNext" -> OutgoingMessage.autoplayUpNext(null, f["videoId"])
            "loungeScreenDisconnected" -> OutgoingMessage.loungeScreenDisconnected()
            else -> fail("unknown recorded message $name")
        }
    }

    @Test
    fun incompleteBindDataNamesTheMissingFieldsOnly() {
        val bp = BindParams("dev", "name", "ytcr", "cl", "T2U", "Test", java.util.Random(1))
        val e = assertFailsWith<IncompleteLoungeDataException> { bp.toQueryString(BindType.RPC) }
        assertEquals(listOf("loungeIdToken", "SID", "gsessionid"), e.missing)
    }
}
