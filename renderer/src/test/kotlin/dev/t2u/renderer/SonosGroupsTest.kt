package dev.t2u.renderer

import dev.t2u.upnp.SonosGroup
import dev.t2u.upnp.SonosMember
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DECK = "rincon_aaa01400_mr"
private const val LAMP = "rincon_bbb01400_mr"

class SonosGroupsTest {
    private val deck = SonosMember(DECK, "Deck", true)
    private val lamp = SonosMember(LAMP, "Lamp", true)
    private val split = listOf(SonosGroup(DECK, listOf(deck)), SonosGroup(LAMP, listOf(lamp)))
    private val joined = listOf(SonosGroup(LAMP, listOf(deck, lamp)))

    /** What each description URL answers; a missing one does not answer. */
    private val answers = HashMap<String, List<SonosGroup>>()
    private val present = hashSetOf(DECK, LAMP)
    private val groups = SonosGroups(
        read = { location -> answers[location] ?: throw IOException("no answer") },
        present = { it in present },
    )
    private val both = mapOf(DECK to "deck", LAMP to "lamp")

    private fun everyoneSays(state: List<SonosGroup>) {
        answers["deck"] = state
        answers["lamp"] = state
    }

    private suspend fun refresh(running: Map<String, String> = both): Boolean = groups.refresh(running)

    @Test
    @DisplayName("a group is one target on its coordinator, named after its rooms in the listed order")
    fun aGroupIsOneTargetOnItsCoordinator() = runBlocking<Unit> {
        everyoneSays(joined)
        refresh()

        assertTrue(groups.isMember(DECK))
        assertFalse(groups.isMember(LAMP))
        assertEquals(setOf(DECK), groups.members.value)
        assertEquals("Deck + Lamp", groups.groupName(LAMP) { null })
        assertNull(groups.groupName(DECK) { null })
        assertEquals("Patio + Lamp", groups.groupName(LAMP) { if (it == DECK) "Patio" else null }, "the author's names count")
    }

    @Test
    @DisplayName("speakers on their own keep their names")
    fun speakersOnTheirOwnKeepTheirNames() = runBlocking<Unit> {
        everyoneSays(split)
        assertFalse(refresh())

        assertFalse(groups.isMember(DECK))
        assertNull(groups.groupName(LAMP) { null })
    }

    @Test
    @DisplayName("a stereo pair is one room: no group name, and the bonded speaker is a member")
    fun aStereoPairIsOneRoom() = runBlocking<Unit> {
        everyoneSays(listOf(SonosGroup(LAMP, listOf(lamp, SonosMember(DECK, "Lamp", false)))))
        refresh()

        assertTrue(groups.isMember(DECK))
        assertNull(groups.groupName(LAMP) { null })
    }

    @Test
    @DisplayName("a member stays listed while its coordinator is no cast target")
    fun aMemberStaysListedWithoutItsCoordinator() = runBlocking<Unit> {
        everyoneSays(joined)
        present.remove(LAMP)
        refresh(mapOf(DECK to "deck"))

        assertFalse(groups.isMember(DECK))
    }

    @Test
    @DisplayName("a split releases the member and asks for searches until it is back")
    fun aSplitReleasesTheMember() = runBlocking<Unit> {
        everyoneSays(joined)
        refresh()
        everyoneSays(split)

        // the member left the registry, so only the coordinator is running; the member is still asked
        assertTrue(refresh(mapOf(LAMP to "lamp")))
        assertFalse(groups.isMember(DECK))
        assertNull(groups.groupName(LAMP) { null })
        assertFalse(refresh(), "back in the registry: no more searches")
    }

    @Test
    @DisplayName("the searches after a split stop when the speaker never comes back")
    fun theSearchesAfterASplitStop() = runBlocking<Unit> {
        everyoneSays(joined)
        refresh()
        everyoneSays(split)

        val searched = (1..10).count { refresh(mapOf(LAMP to "lamp")) }

        assertEquals(4, searched)
    }

    @Test
    @DisplayName("one missed answer changes nothing; two release the member")
    fun twoMissedAnswersReleaseTheMember() = runBlocking<Unit> {
        everyoneSays(joined)
        refresh()
        answers.remove("deck")

        refresh(mapOf(LAMP to "lamp"))
        assertTrue(groups.isMember(DECK))
        assertTrue(refresh(mapOf(LAMP to "lamp")))
        assertFalse(groups.isMember(DECK))
    }

    @Test
    @DisplayName("a speaker is believed only about itself")
    fun aSpeakerIsBelievedOnlyAboutItself() = runBlocking<Unit> {
        // the Deck claims to coordinate a group that holds the Lamp; the Lamp says it is on its own
        answers["deck"] = listOf(SonosGroup(DECK, listOf(deck, lamp)))
        answers["lamp"] = split
        refresh()

        assertFalse(groups.isMember(LAMP))
    }

    @Test
    @DisplayName("a speaker that is not a Sonos is never asked")
    fun aSpeakerThatIsNotASonosIsNeverAsked() = runBlocking<Unit> {
        val asked = ArrayList<String>()
        val other = SonosGroups(read = { asked.add(it); emptyList() }, present = { true })

        other.refresh(mapOf("fake-renderer" to "tv"))

        assertTrue(asked.isEmpty())
    }
}
