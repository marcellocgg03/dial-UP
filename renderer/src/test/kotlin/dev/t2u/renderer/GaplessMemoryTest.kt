package dev.t2u.renderer

// Kotlin original (plan 04-06, D-08, D-13), GPL-3.0-only: the rules for the stored answer to "does this speaker take
// SetNextAVTransportURI". Pure functions, no renderer and no speaker. Every test is block-bodied (JUnit skips an
// expression body that returns a value).

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class GaplessMemoryTest {
    private val m = speakerModel("Acme", "Speaker 1", "S1")
    private val t = 1_700_000_000_000L

    @Test
    @DisplayName("the speaker model is the three description fields joined with a bar")
    fun speakerModelJoinsTheThreeFields() {
        assertEquals("Acme|Speaker 1|S1", m)
        assertEquals("||", speakerModel("", "", ""))
    }

    @Test
    @DisplayName("a stored value has the exact shape supported, model, at")
    fun theStoredShape() {
        val v = gaplessMemory(true, m, t)
        assertEquals(setOf("supported", "model", "at"), v.keys)
        assertEquals(JsonPrimitive(true), v["supported"])
        assertEquals(JsonPrimitive(m), v["model"])
        assertEquals(JsonPrimitive(t), v["at"])
    }

    @Test
    @DisplayName("a stored true is trusted whatever its age")
    fun aStoredTrueIsTrusted() {
        assertEquals(true, readGaplessMemory(gaplessMemory(true, m, t), m, t))
        assertEquals(true, readGaplessMemory(gaplessMemory(true, m, t), m, t + 10 * GAPLESS_REPROBE_MS))
    }

    @Test
    @DisplayName("a stored false is trusted for seven days, then unknown so the speaker is asked once more")
    fun aStoredFalseIsTrustedForSevenDays() {
        assertEquals(false, readGaplessMemory(gaplessMemory(false, m, t), m, t))
        assertEquals(false, readGaplessMemory(gaplessMemory(false, m, t), m, t + GAPLESS_REPROBE_MS - 1))
        assertNull(readGaplessMemory(gaplessMemory(false, m, t), m, t + GAPLESS_REPROBE_MS + 1))
    }

    @Test
    @DisplayName("a value stored for another model is unknown")
    fun anotherModelIsUnknown() {
        assertNull(readGaplessMemory(gaplessMemory(true, speakerModel("Acme", "Speaker 2", "S1"), t), m, t))
        assertNull(readGaplessMemory(gaplessMemory(false, speakerModel("Other", "Speaker 1", "S1"), t), m, t))
    }

    @Test
    @DisplayName("a value of the wrong shape or type is unknown, never an error")
    fun wrongShapesAreUnknown() {
        assertNull(readGaplessMemory(null, m, t))
        assertNull(readGaplessMemory(JsonNull, m, t))
        assertNull(readGaplessMemory(JsonPrimitive("true"), m, t))
        assertNull(readGaplessMemory(JsonPrimitive(1), m, t))
        assertNull(readGaplessMemory(JsonArray(emptyList()), m, t))
        assertNull(readGaplessMemory(JsonObject(emptyMap()), m, t))
        assertNull(readGaplessMemory(buildJsonObject { put("model", m); put("at", t) }, m, t), "supported missing")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", "true"); put("model", m); put("at", t) }, m, t), "supported a string")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", 1); put("model", m); put("at", t) }, m, t), "supported a number")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", true); put("model", 5); put("at", t) }, m, t), "model a number")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", true); put("at", t) }, m, t), "model missing")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", true); put("model", m); put("at", "soon") }, m, t), "at a string")
        assertNull(readGaplessMemory(buildJsonObject { put("supported", true); put("model", m) }, m, t), "at missing")
    }

    @Test
    @DisplayName("the model of a stored value is read only from a string field of an object")
    fun theStoredModelIsReadStrictly() {
        assertEquals(m, gaplessMemoryModel(gaplessMemory(true, m, t)))
        assertNull(gaplessMemoryModel(null))
        assertNull(gaplessMemoryModel(JsonPrimitive(m)))
        assertNull(gaplessMemoryModel(buildJsonObject { put("model", 5) }))
    }
}
