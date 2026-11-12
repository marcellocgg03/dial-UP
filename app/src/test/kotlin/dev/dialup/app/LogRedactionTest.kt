package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogRedactionTest {
    private val pathId = "0123456789abcdef0123456789abcdef"

    @Test
    fun aStreamTokenIsCutToSixCharactersAndTheRestOfThePathStays() {
        assertEquals("/stream/012345...mp3", redactStreamToken("/stream/$pathId.mp3"))
        assertEquals("/stream/012345...adts?x=1", redactStreamToken("/stream/$pathId.adts?x=1"))
        assertFalse(pathId.substring(6) in redactStreamToken("/stream/$pathId.mp3"))
    }

    @Test
    fun otherPathsAreUntouched() {
        for (uri in listOf("/ytcr/apps/YouTube", "/stream/short.mp3", "/", "/other/$pathId", "")) {
            assertEquals(uri, redactStreamToken(uri), uri)
        }
    }
}
