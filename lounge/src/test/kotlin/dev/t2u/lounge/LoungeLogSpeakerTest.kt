package dev.t2u.lounge

import dev.t2u.upnp.EventSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LoungeLogSpeakerTest {
    @Test
    fun everyEventCarriesTheSpeakerWhenOneIsGiven() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        val log = LoungeLog(EventSink { kind, fields -> events.add(kind to fields) }, "m", "[DialUP] Lamp")
        log.session("refresh")
        log.http("GET", "/api/lounge/bc/bind", 410, 5)
        assertEquals(listOf("[DialUP] Lamp", "[DialUP] Lamp"), events.map { it.second["speaker"] })
    }

    @Test
    fun noSpeakerFieldWithoutOne() {
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        LoungeLog(EventSink { kind, fields -> events.add(kind to fields) }, "m").session("refresh")
        assertFalse(events.single().second.containsKey("speaker"))
    }
}
