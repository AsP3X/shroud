package de.corespace.shroud.ui.settings.push

import de.corespace.shroud.core.push.Distributor
import de.corespace.shroud.core.push.NoPushReason
import de.corespace.shroud.core.push.PushCopy
import de.corespace.shroud.core.push.PushDelivery
import de.corespace.shroud.core.push.UnifiedPushState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Delivery section and screen's words for every state of K6 `PushDelivery` (plan §2.4
 * W3-PUSH "Delivery UI copy"): every [NoPushReason] has its sentence, the card's fixed strings are
 * verbatim, and the section row names the path that delivers.
 */
class PushDeliveryCopyTest {
    private val ntfy = Distributor("io.heckel.ntfy", "ntfy")
    private val nextPush = Distributor("org.unifiedpush.distributor.nextpush", "NextPush")

    private fun delivery(up: UnifiedPushState, background: Boolean = false, battery: Boolean = false) =
        PushDelivery(up, backgroundConnection = background, batteryUnrestricted = battery)

    private fun unavailable(reason: NoPushReason) = UnifiedPushState.Unavailable(reason)

    /** Every reason, in the enum's order, with its sentence (`this phone` from the device noun). */
    @Test
    fun everyNoPushReasonHasItsSentence() {
        val expected = mapOf(
            NoPushReason.NoDistributorInstalled to "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection.",
            NoPushReason.NoneChosen to "Choose a UnifiedPush distributor, or turn on Background connection.",
            NoPushReason.DistributorFailed to "The UnifiedPush distributor could not register this phone. Try again, or turn on Background connection.",
            NoPushReason.DistributorCannotEncrypt to "That UnifiedPush distributor cannot encrypt notifications. Choose another, or turn on Background connection.",
            NoPushReason.ServerRefusedHost to "This server doesn’t send to that UnifiedPush distributor.",
            NoPushReason.ServerHasNoWebPush to "This server is not set up to send notifications to Android phones.",
            NoPushReason.NotificationsOff to "Notifications are off for Shroud. Turn them on in Android Settings.",
        )
        assertEquals("a sentence for every reason core has", NoPushReason.entries.toSet(), expected.keys)
        for (reason in NoPushReason.entries) {
            assertEquals(reason.name, expected.getValue(reason), DeliveryCopy.reason(reason, "phone"))
        }
        assertEquals(
            "The UnifiedPush distributor could not register this tablet. Try again, or turn on Background connection.",
            DeliveryCopy.reason(NoPushReason.DistributorFailed, "tablet"),
        )
    }

    /**
     * The section's reason and the test notification's (core `PushCopy`, the K6 copy split) are
     * the same sentence for every reason on a phone, so the two places never disagree.
     */
    @Test
    fun theSectionSaysWhatTheTestNotificationSays() {
        for (reason in NoPushReason.entries) {
            val state = DeliveryState(delivery(unavailable(reason)))
            assertEquals(reason.name, PushCopy.noDeliveryReason(state.delivery), state.sectionFooter("phone"))
        }
    }

    /** The fixed strings of the W3-PUSH card, verbatim. */
    @Test
    fun theCardsStrings() {
        assertEquals("Delivery", DeliveryCopy.ROW)
        assertEquals("Delivery", DeliveryCopy.TITLE)
        assertEquals("Background connection", DeliveryCopy.BACKGROUND_CONNECTION)
        assertEquals("Off while Shroud is closed", DeliveryCopy.OFF_WHILE_CLOSED)
        assertEquals("Push distributor", DeliveryCopy.PUSH_DISTRIBUTOR)
        assertEquals("None", DeliveryCopy.NONE)
        assertEquals(
            "Keeps a connection to your server open so messages and calls arrive without a push distributor. Uses more battery and shows a permanent notification.",
            DeliveryCopy.BACKGROUND_FOOTER,
        )
        assertEquals("No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection.", DeliveryCopy.NO_DISTRIBUTOR)
        assertEquals("This server doesn’t send to that UnifiedPush distributor.", DeliveryCopy.REFUSED_HOST)
    }

    /** The empty state and None are how a phone starts out; every other reason is a failure. */
    @Test
    fun whichReasonsAreProblems() {
        val neutral = setOf(NoPushReason.NoDistributorInstalled, NoPushReason.NoneChosen)
        for (reason in NoPushReason.entries) assertEquals(reason.name, reason !in neutral, DeliveryCopy.isProblem(reason))
    }

    /** The section row's value: the distributor, else the background connection, else off. */
    @Test
    fun theSectionValue() {
        val registered = UnifiedPushState.Registered(ntfy.packageName, "ntfy")
        assertEquals("ntfy", DeliveryState(delivery(registered)).summary)
        assertEquals("the distributor wins over the background connection", "ntfy", DeliveryState(delivery(registered, background = true)).summary)
        assertEquals("Background connection", DeliveryState(delivery(unavailable(NoPushReason.NoDistributorInstalled), background = true)).summary)
        assertEquals("Background connection", DeliveryState(delivery(UnifiedPushState.Registering(ntfy.packageName), background = true)).summary)
        assertEquals(
            "a registration on its way names its distributor",
            "ntfy",
            DeliveryState(delivery(UnifiedPushState.Registering(ntfy.packageName)), listOf(ntfy)).summary,
        )
        assertEquals("io.heckel.ntfy", DeliveryState(delivery(UnifiedPushState.Registering(ntfy.packageName))).summary)
        for (reason in NoPushReason.entries) {
            val state = DeliveryState(delivery(unavailable(reason)))
            assertEquals(reason.name, "Off while Shroud is closed", state.summary)
            assertTrue(reason.name, state.isOff)
        }
        assertEquals("Off while Shroud is closed", DeliveryState(delivery(UnifiedPushState.Unknown)).summary)
        assertFalse(DeliveryState(delivery(registered)).isOff)
        assertFalse(DeliveryState(delivery(UnifiedPushState.Registering(ntfy.packageName))).isOff)
        assertFalse(DeliveryState(delivery(unavailable(NoPushReason.ServerRefusedHost), background = true)).isOff)
        assertEquals("Delivery, Off while Shroud is closed", DeliveryCopy.rowLabel(DeliveryCopy.OFF_WHILE_CLOSED))
    }

    /** The reason sits under the section only while nothing delivers and the reason is known. */
    @Test
    fun theSectionFooter() {
        assertEquals(DeliveryCopy.NO_DISTRIBUTOR, DeliveryState(delivery(unavailable(NoPushReason.NoDistributorInstalled))).sectionFooter("phone"))
        assertNull(DeliveryState(delivery(unavailable(NoPushReason.NoDistributorInstalled), background = true)).sectionFooter("phone"))
        assertNull(DeliveryState(delivery(UnifiedPushState.Registered(ntfy.packageName, "ntfy"))).sectionFooter("phone"))
        assertNull(DeliveryState(delivery(UnifiedPushState.Registering(ntfy.packageName))).sectionFooter("phone"))
        assertNull("not looked at yet: no reason to give", DeliveryState(delivery(UnifiedPushState.Unknown)).sectionFooter("phone"))
    }

    /** The picker: the distributors installed, then None; the value from core's state. */
    @Test
    fun thePicker() {
        val none = DeliveryState(delivery(unavailable(NoPushReason.NoDistributorInstalled)))
        assertFalse("nothing installed: plain None", none.canChoose)
        assertEquals(emptyList<DistributorChoice>(), none.options)
        assertEquals(DistributorChoice.None, none.choice)

        val several = DeliveryState(delivery(unavailable(NoPushReason.NoneChosen)), listOf(ntfy, nextPush))
        assertTrue(several.canChoose)
        assertEquals(
            listOf(DistributorChoice.App(ntfy.packageName, "ntfy"), DistributorChoice.App(nextPush.packageName, "NextPush"), DistributorChoice.None),
            several.options,
        )
        assertEquals(DistributorChoice.None, several.choice)

        val registered = DeliveryState(delivery(UnifiedPushState.Registered(ntfy.packageName, "ntfy")), listOf(ntfy, nextPush))
        assertEquals("the ticked option", registered.options.first(), registered.choice)
        val registering = DeliveryState(delivery(UnifiedPushState.Registering(nextPush.packageName)), listOf(ntfy, nextPush))
        assertEquals(registering.options[1], registering.choice)

        // Core reports these without the distributor it chose: the picker says so instead of guessing.
        for (reason in NoPushReason.entries - setOf(NoPushReason.NoDistributorInstalled, NoPushReason.NoneChosen)) {
            val state = DeliveryState(delivery(unavailable(reason)), listOf(ntfy))
            assertEquals(reason.name, DistributorChoice.NotConnected, state.choice)
            assertFalse(reason.name, state.choice in state.options)
        }
        assertEquals("Not connected", DistributorChoice.NotConnected.label)
        assertEquals("None", DistributorChoice.None.label)
        assertEquals(DistributorChoice.None, DeliveryState(delivery(UnifiedPushState.Unknown), listOf(ntfy)).choice)
    }

    /** The line under the distributor card for each state. */
    @Test
    fun theDistributorFooter() {
        assertEquals(
            DeliveryLine("Connected through ntfy. Each push is encrypted for this phone, so ntfy can’t read it.", problem = false),
            DeliveryState(delivery(UnifiedPushState.Registered(ntfy.packageName, "ntfy"))).distributorFooter("phone"),
        )
        assertEquals(
            DeliveryLine("Connecting to NextPush…", problem = false),
            DeliveryState(delivery(UnifiedPushState.Registering(nextPush.packageName)), listOf(nextPush)).distributorFooter("phone"),
        )
        assertEquals(
            DeliveryLine(DeliveryCopy.NO_DISTRIBUTOR, problem = false),
            DeliveryState(delivery(unavailable(NoPushReason.NoDistributorInstalled))).distributorFooter("phone"),
        )
        assertEquals(
            DeliveryLine(DeliveryCopy.REFUSED_HOST, problem = true),
            DeliveryState(delivery(unavailable(NoPushReason.ServerRefusedHost), background = true)).distributorFooter("phone"),
        )
        assertNull(DeliveryState(delivery(UnifiedPushState.Unknown)).distributorFooter("phone"))
    }

    /** The battery row belongs to the background connection. */
    @Test
    fun theBatteryRow() {
        assertFalse(DeliveryState(delivery(UnifiedPushState.Unknown, background = false, battery = false)).showsBattery)
        val optimized = DeliveryState(delivery(UnifiedPushState.Unknown, background = true, battery = false))
        assertTrue(optimized.showsBattery)
        assertFalse(optimized.batteryUnrestricted)
        assertTrue(DeliveryState(delivery(UnifiedPushState.Unknown, background = true, battery = true)).batteryUnrestricted)
    }

    @Test
    fun theIntroAndBatteryWords() {
        assertEquals(
            "While Shroud is closed, notifications reach this phone through Google Play when it is available, through a UnifiedPush distributor you install, such as ntfy, or through a background connection to your server.",
            DeliveryCopy.intro("phone"),
        )
        assertEquals("Battery use", DeliveryCopy.BATTERY)
        assertEquals("Unrestricted", DeliveryCopy.BATTERY_UNRESTRICTED)
        assertEquals("Optimized", DeliveryCopy.BATTERY_OPTIMIZED)
        val play = Distributor("de.corespace.shroud", "Google Play", embedded = true)
        assertEquals(
            DeliveryLine(
                "Connected through Google Play. The notification is encrypted for this phone. Google can see that one was delivered, and its size, and cannot read it.",
                problem = false,
            ),
            DeliveryState(delivery(UnifiedPushState.Registered(play.packageName, play.label)), listOf(play)).distributorFooter("phone"),
        )
        // Firebase is not a name we show. The reason sentences stay free of Google: that path is the embedded distributor's own line.
        val quiet = NoPushReason.entries.map { DeliveryCopy.reason(it, "phone") } + listOf(
            DeliveryCopy.BACKGROUND_FOOTER, DeliveryCopy.BATTERY_WARNING, DeliveryCopy.connected("ntfy", "phone"),
        )
        for (text in quiet) for (word in listOf("Google", "FCM", "Firebase", "Apple")) assertFalse(text, text.contains(word))
        assertFalse(DeliveryCopy.intro("phone").contains("Firebase"))
        assertFalse(DeliveryCopy.connected("Google Play", "phone", embedded = true).contains("Firebase"))
    }
}
