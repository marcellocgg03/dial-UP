package dev.t2u.upnp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonFieldsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `stringOrNull gives the content of a JSON string`() {
        assertEquals("Kitchen", obj("""{"name":"Kitchen"}""").stringOrNull("name"))
    }

    @Test
    fun `stringOrNull keeps an empty string empty`() {
        assertEquals("", obj("""{"name":""}""").stringOrNull("name"))
    }

    @Test
    fun `stringOrNull ignores numbers booleans and null`() {
        val o = obj("""{"n":12,"f":1.5,"b":true,"z":null}""")
        assertNull(o.stringOrNull("n"))
        assertNull(o.stringOrNull("f"))
        assertNull(o.stringOrNull("b"))
        assertNull(o.stringOrNull("z"))
    }

    @Test
    fun `stringOrNull ignores objects arrays and missing keys`() {
        val o = obj("""{"o":{"a":"b"},"a":["x"]}""")
        assertNull(o.stringOrNull("o"))
        assertNull(o.stringOrNull("a"))
        assertNull(o.stringOrNull("missing"))
    }

    @Test
    fun `objectOrNull gives a nested object`() {
        val o = obj("""{"watch":{"index":3}}""")
        assertEquals(obj("""{"index":3}"""), o.objectOrNull("watch"))
    }

    @Test
    fun `objectOrNull ignores anything else and missing keys`() {
        val o = obj("""{"s":"x","n":1,"z":null,"a":[{}]}""")
        assertNull(o.objectOrNull("s"))
        assertNull(o.objectOrNull("n"))
        assertNull(o.objectOrNull("z"))
        assertNull(o.objectOrNull("a"))
        assertNull(o.objectOrNull("missing"))
    }
}
