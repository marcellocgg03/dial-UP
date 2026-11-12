package dev.dialup.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GuestNotifierTest {
    @Test
    fun `the text names the address and the speaker`() {
        assertEquals("192.168.86.31 started a cast on Kitchen", guestNoticeText("192.168.86.31", "Kitchen"))
    }

    @Test
    fun `one address keeps one id, another address gets another, and none is the bridge notification`() {
        assertEquals(guestNoticeId("192.168.86.31"), guestNoticeId("192.168.86.31"))
        assertNotEquals(guestNoticeId("192.168.86.31"), guestNoticeId("192.168.86.32"))
        assertTrue(guestNoticeId("192.168.86.31") > CastNotifier.NOTIFICATION_ID)
    }
}
