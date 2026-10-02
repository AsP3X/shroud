package de.corespace.shroud.ui.chats

import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.toastBottomPadding
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * When the list re-reads on its own (`ChatMute.isActive`, NM:60-64; `ChatListFormatting.timeLabel`,
 * CLF:32-42) and the bottom insets of the tab (shell-chats §4.2, §10.15).
 */
class ChatsClockTest {
    /** 2026-09-21T14:13:20Z, a Monday. */
    private val now = Instant.ofEpochSecond(1_790_000_000)

    @Test
    fun withoutMutesTheNextChangeIsLocalMidnight() {
        assertEquals(Instant.parse("2026-09-22T00:00:00Z"), ChatsClock.nextChange(now, emptyList(), ZoneOffset.UTC))
        // Berlin is UTC+2 in September: midnight there is 22:00 UTC.
        assertEquals(Instant.parse("2026-09-21T22:00:00Z"), ChatsClock.nextChange(now, emptyList(), ZoneId.of("Europe/Berlin")))
    }

    @Test
    fun aMuteEndingBeforeMidnightComesFirst() {
        val end = now.plusSeconds(3_600)
        assertEquals(end, ChatsClock.nextChange(now, listOf(null, now.plusSeconds(7_200), end), ZoneOffset.UTC))
    }

    @Test
    fun foreverMutesAndPastEndsNeverWakeTheList() {
        val midnight = Instant.parse("2026-09-22T00:00:00Z")
        assertEquals(midnight, ChatsClock.nextChange(now, listOf(null, now.minusSeconds(5), now), ZoneOffset.UTC))
        assertEquals(midnight, ChatsClock.nextChange(now, listOf(now.plusSeconds(86_400 * 3)), ZoneOffset.UTC))
    }

    @Test
    fun theWaitIsNeverShorterThanAQuarterSecond() {
        assertEquals(3_600_000L, ChatsClock.delayMillis(now, now.plusSeconds(3_600)))
        assertEquals(ChatsClock.MIN_DELAY_MS, ChatsClock.delayMillis(now, now.plusMillis(10)))
        assertEquals(ChatsClock.MIN_DELAY_MS, ChatsClock.delayMillis(now, now.minusSeconds(1)))
    }

    @Test
    fun theListClearsTheTabBarOrTheSystemBar() {
        assertEquals(104.dp, ChatsLayout.listBottomPadding(tabBarClearance = 104.dp, systemBottom = 24.dp))
        // The bar is hidden (header search with the keyboard up): sit above the keyboard.
        assertEquals(320.dp, ChatsLayout.listBottomPadding(tabBarClearance = 0.dp, systemBottom = 320.dp))
    }

    @Test
    fun theToastFloats20DpOverTheTabBar() {
        // ToastHost adds 20 + the system inset itself; the lift makes it 20 + the clearance.
        val lift = ChatsLayout.toastLift(tabBarClearance = 104.dp, systemBottom = 24.dp)
        assertEquals(80.dp, lift)
        assertEquals(124.dp, toastBottomPadding(0.dp, 24.dp, lift))
        assertEquals(0.dp, ChatsLayout.toastLift(tabBarClearance = 0.dp, systemBottom = 24.dp))
        assertEquals(0.dp, ChatsLayout.toastLift(tabBarClearance = 20.dp, systemBottom = 320.dp))
    }
}
