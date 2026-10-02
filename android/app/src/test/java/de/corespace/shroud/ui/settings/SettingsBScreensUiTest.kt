package de.corespace.shroud.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.notifications.NotificationPrefsState
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.settings.SettingsBFixtures.chrome
import de.corespace.shroud.ui.settings.SettingsBFixtures.designDevices
import de.corespace.shroud.ui.settings.SettingsBFixtures.pixel
import de.corespace.shroud.ui.settings.devices.DeviceDetailSheet
import de.corespace.shroud.ui.settings.devices.DeviceRemovals
import de.corespace.shroud.ui.settings.devices.DevicesContent
import de.corespace.shroud.ui.settings.devices.DevicesCopy
import de.corespace.shroud.ui.settings.devices.DevicesUiState
import de.corespace.shroud.ui.settings.notifications.NotificationsContent
import de.corespace.shroud.ui.settings.notifications.NotificationsCopy
import de.corespace.shroud.ui.settings.notifications.NotificationsSettingsState
import de.corespace.shroud.ui.settings.notifications.SystemNotice
import de.corespace.shroud.ui.settings.privacy.PrivacyCallbacks
import de.corespace.shroud.ui.settings.privacy.PrivacyContent
import de.corespace.shroud.ui.settings.privacy.PrivacyCopy
import de.corespace.shroud.ui.settings.privacy.PrivacyState
import de.corespace.shroud.ui.settings.push.DeliveryCopy
import de.corespace.shroud.ui.settings.push.DeliverySectionContent
import de.corespace.shroud.ui.settings.push.DeliveryState
import de.corespace.shroud.ui.settings.push.DistributorChoice
import de.corespace.shroud.ui.settings.push.PushDeliveryContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.UUID

/**
 * The W3-SETTINGS-B screens and the Delivery UI (C7 + C15) as TalkBack and a finger reach them, in
 * every state they draw: what each row says, what is pressable, what a press does. Robolectric
 * hosts the real content composables on fixed states ([SettingsBFixtures]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBScreensUiTest {
    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun SemanticsNode.described(): String? = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun SemanticsNode.isDisabled(): Boolean = SemanticsProperties.Disabled in config

    private fun SemanticsNode.clickable(): Boolean = SemanticsActions.OnClick in config

    private fun ComposeHarness.has(text: String): Boolean = nodesWithText(text).isNotEmpty()

    /** The one pressable node whose merged text starts with [title]. */
    private fun ComposeHarness.pressable(title: String): SemanticsNode =
        nodes().single { it.texts().firstOrNull() == title && it.clickable() }

    // ---- Delivery section (Notifications and Sounds, top) ----

    @Test
    fun theDeliveryRowNamesThePathAndOpensTheScreen() {
        var opened = 0
        var state by mutableStateOf(SettingsBFixtures.ntfyRegistered)
        val ui = ComposeHarness { Column { DeliverySectionContent(state, DeviceNoun.PHONE, onOpen = { opened++ }) } }
        val row = ui.node("Delivery, ntfy")
        assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))
        row.click()
        assertEquals(1, opened)
        assertFalse("nothing to explain while ntfy delivers", ui.has(DeliveryCopy.NO_DISTRIBUTOR))

        state = SettingsBFixtures.backgroundUnrestricted
        ui.idle()
        ui.node("Delivery, Background connection")
        assertFalse("the background connection delivers: no reason under the row", ui.has(DeliveryCopy.NO_DISTRIBUTOR))

        state = SettingsBFixtures.noneInstalled
        ui.idle()
        ui.node("Delivery, Off while Shroud is closed")
        assertTrue("the empty state says why", ui.has(DeliveryCopy.NO_DISTRIBUTOR))

        state = SettingsBFixtures.refusedHost
        ui.idle()
        assertTrue(ui.has("This server doesn’t send to that UnifiedPush distributor."))
    }

    // ---- Delivery screen ----

    private fun deliveryScreen(
        state: DeliveryState,
        chosen: MutableList<DistributorChoice> = ArrayList(),
        background: MutableList<Boolean> = ArrayList(),
        battery: IntArray = IntArray(1),
    ): ComposeHarness = ComposeHarness {
        PushDeliveryContent(
            state = state,
            noun = "phone",
            onChoose = { chosen += it },
            onBackgroundConnection = { background += it },
            onOpenBatterySettings = { battery[0]++ },
        )
    }

    @Test
    fun noDistributorInstalledIsAPlainNoneWithTheEmptyState() {
        val ui = deliveryScreen(SettingsBFixtures.noneInstalled)
        assertTrue(ui.has(DeliveryCopy.intro("phone")))
        val row = ui.nodes().single { it.texts().firstOrNull() == DeliveryCopy.PUSH_DISTRIBUTOR }
        assertEquals("nothing to choose: the value is text", listOf("Push distributor", "None"), row.texts())
        assertFalse(row.clickable())
        assertTrue(ui.nodes().none { it.config.getOrNull(SemanticsProperties.Role) == Role.DropdownList })
        assertTrue(ui.has("No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."))
        assertTrue(ui.has(DeliveryCopy.BACKGROUND_FOOTER))
        assertTrue("the battery row waits for the background connection", ui.nodesWithText(DeliveryCopy.BATTERY).isEmpty())
    }

    @Test
    fun severalDistributorsOfferAMenu() {
        val chosen = ArrayList<DistributorChoice>()
        val ui = deliveryScreen(SettingsBFixtures.severalToChoose, chosen = chosen)
        val picker = ui.node(DeliveryCopy.PUSH_DISTRIBUTOR)
        assertEquals(Role.DropdownList, picker.config.getOrNull(SemanticsProperties.Role))
        assertEquals("None", picker.config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(ui.has(DeliveryCopy.NONE_CHOSEN))
        picker.click()
        ui.idle()
        // ntfy, NextPush, then None.
        for (title in listOf("ntfy", "NextPush")) assertTrue(title, ui.nodes().any { it.texts() == listOf(title) && it.clickable() })
        ui.nodes().single { it.texts() == listOf("NextPush") && it.clickable() }.click()
        ui.idle()
        assertEquals(listOf<DistributorChoice>(DistributorChoice.App(SettingsBFixtures.nextPush.packageName, "NextPush")), chosen)
    }

    @Test
    fun aRegisteredDistributorSaysItIsConnected() {
        val ui = deliveryScreen(SettingsBFixtures.ntfyRegistered)
        assertEquals("ntfy", ui.node(DeliveryCopy.PUSH_DISTRIBUTOR).config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(ui.has("Connected through ntfy. Each push is encrypted for this phone, so ntfy can’t read it."))
    }

    @Test
    fun registeringAndTheProblems() {
        assertTrue(deliveryScreen(SettingsBFixtures.ntfyRegistering).has("Connecting to ntfy…"))
        val refused = deliveryScreen(SettingsBFixtures.refusedHost)
        assertTrue(refused.has("This server doesn’t send to that UnifiedPush distributor."))
        assertEquals("Not connected", refused.node(DeliveryCopy.PUSH_DISTRIBUTOR).config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(deliveryScreen(SettingsBFixtures.distributorFailed).has(DeliveryCopy.distributorFailed("phone")))
    }

    @Test
    fun theBackgroundConnectionSwitchAndTheBattery() {
        val background = ArrayList<Boolean>()
        val battery = IntArray(1)
        val off = deliveryScreen(SettingsBFixtures.noneInstalled, background = background)
        val toggle = off.node(DeliveryCopy.BACKGROUND_CONNECTION)
        assertEquals("Off", toggle.config.getOrNull(SemanticsProperties.StateDescription))
        toggle.click()
        assertEquals(listOf(true), background)

        val optimized = deliveryScreen(SettingsBFixtures.backgroundOptimized, battery = battery)
        assertEquals("On", optimized.node(DeliveryCopy.BACKGROUND_CONNECTION).config.getOrNull(SemanticsProperties.StateDescription))
        val row = optimized.node("Battery use, Optimized")
        row.click()
        assertEquals(1, battery[0])
        assertTrue(optimized.has(DeliveryCopy.BATTERY_WARNING))

        val unrestricted = deliveryScreen(SettingsBFixtures.backgroundUnrestricted, battery = battery)
        val still = unrestricted.nodes().single { it.texts() == listOf("Battery use", "Unrestricted") }
        assertFalse("nothing to fix: the row is not a button", still.clickable())
        assertFalse(unrestricted.has(DeliveryCopy.BATTERY_WARNING))
    }

    // ---- Devices ----

    private fun devicesScreen(
        state: DevicesUiState,
        opened: MutableList<DeviceRow> = ArrayList(),
        removed: MutableList<DeviceRow> = ArrayList(),
        removeAll: IntArray = IntArray(1),
    ): ComposeHarness = ComposeHarness {
        DevicesContent(
            state = state,
            noun = "phone",
            timeLabel = SettingsBFixtures::timeLabel,
            onRetry = {},
            onOpen = { opened += it },
            onRemove = { removed += it },
            onRemoveAll = { removeAll[0]++ },
        )
    }

    @Test
    fun devicesLoadingAndLoadError() {
        assertTrue(devicesScreen(SettingsBFixtures.loading).has(DevicesCopy.LOADING))
        val failed = devicesScreen(SettingsBFixtures.loadFailed)
        assertTrue(failed.has("Can't load devices"))
        assertTrue(failed.has("The Internet connection appears to be offline."))
        failed.node("Try again")
    }

    @Test
    fun theDeviceListReadsAndActs() {
        val opened = ArrayList<DeviceRow>()
        val removed = ArrayList<DeviceRow>()
        val removeAll = IntArray(1)
        val ui = devicesScreen(SettingsBFixtures.devices(designDevices), opened, removed, removeAll)
        assertTrue(ui.has("THIS DEVICE"))
        assertTrue(ui.has("OTHER DEVICES — 2"))
        assertTrue(ui.has("Active now · This phone"))
        assertTrue(ui.has("Last active 9:37"))
        assertTrue(ui.has("3 of 5"))
        assertTrue(ui.has("You can sign in on 2 more devices. A logged-out device stays listed until it signs in again or you remove it."))
        ui.pressable("Pixel 9a").click()
        assertEquals(listOf(pixel), opened)
        ui.node("Remove Chrome on Mac").click()
        assertEquals(listOf(chrome), removed)
        assertTrue("this phone has no Remove", ui.nodes().none { it.described() == "Remove Pixel 9a" })
        ui.pressable(DevicesCopy.REMOVE_ALL).click()
        assertEquals(1, removeAll[0])
    }

    @Test
    fun devicesWhileRemovingAndAfterAFailure() {
        val removing = SettingsBFixtures.devices(
            designDevices,
            error = "1 of 2 devices could not be removed. The Internet connection appears to be offline.",
            removals = DeviceRemovals(revokingIds = setOf(chrome.id)),
        )
        val ui = devicesScreen(removing)
        assertTrue("the removing row has a spinner, not a button", ui.nodes().none { it.described() == "Remove Chrome on Mac" })
        ui.node("Remove iPad Air")
        assertTrue(ui.has("1 of 2 devices could not be removed. The Internet connection appears to be offline."))
        assertTrue("Remove All waits for the running removal", ui.nodes().single { it.texts().firstOrNull() == DevicesCopy.REMOVE_ALL }.isDisabled())

        val all = devicesScreen(SettingsBFixtures.devices(designDevices, removals = DeviceRemovals(isRevokingAll = true)))
        assertTrue(all.has(DevicesCopy.REMOVING_ALL))
        assertTrue(all.nodes().none { it.described()?.startsWith("Remove ") == true })
    }

    @Test
    fun aloneAndAtTheLimit() {
        val alone = devicesScreen(SettingsBFixtures.devices(listOf(pixel)))
        assertTrue(alone.has(DevicesCopy.NO_OTHER_DEVICES))
        assertTrue(alone.has("OTHER DEVICES"))
        assertTrue("nothing to remove", alone.nodesWithText(DevicesCopy.REMOVE_ALL).isEmpty())
        val full = devicesScreen(SettingsBFixtures.devices(listOf(pixel, chrome, SettingsBFixtures.ipad, SettingsBFixtures.mac, SettingsBFixtures.pc)))
        assertTrue(full.has("5 of 5"))
        assertTrue(full.has("Your account is at the limit."))
        assertTrue(full.has("Last active Sep 20, 2026"))
        assertTrue("never seen: the link date", full.has("Linked Aug 30, 2026"))
    }

    @Test
    fun theDetailsOfThisAndAnotherDevice() {
        var device by mutableStateOf<DeviceRow?>(pixel)
        val revoked = ArrayList<DeviceRow>()
        val ui = ComposeHarness {
            DeviceDetailSheet(
                device = device,
                isRevoking = false,
                noun = "phone",
                lastActive = { DevicesCopy.lastActive(it, SettingsBFixtures::timeLabel) },
                formatDate = SettingsBFixtures::longDate,
                onRename = { _, _ -> null },
                onCopyId = {},
                onRevoke = { revoked += it },
                onDismiss = { device = null },
            )
        }
        assertTrue(ui.has("This phone · Active now"))
        assertTrue("sealed kind 4", ui.nodes().any { it.texts() == listOf("Type", "Android app") })
        assertTrue(ui.nodes().any { it.texts() == listOf("Last active", "Now") })
        assertTrue(ui.has(DevicesCopy.currentDeviceNote("phone")))
        assertTrue("Log Out removes this phone", ui.nodesWithText(DevicesCopy.REMOVE_DEVICE).isEmpty())
        ui.node("Rename Pixel 9a")
        ui.node(DevicesCopy.COPY_DEVICE_ID)

        device = chrome
        ui.idle()
        assertTrue(ui.nodes().any { it.texts() == listOf("Type", "Web browser") })
        assertTrue(ui.nodes().any { it.texts() == listOf("Linked", "14 September 2026 at 20:49") })
        ui.pressable(DevicesCopy.REMOVE_DEVICE).click()
        assertEquals(listOf(chrome), revoked)
    }

    // ---- Notifications and Sounds ----

    private fun notificationsScreen(
        notice: SystemNotice? = null,
        prefs: NotificationPrefsState = NotificationPrefsState(),
        state: NotificationsSettingsState = NotificationsSettingsState(),
        muted: Boolean = true,
        actions: MutableList<SystemNotice> = ArrayList(),
        unmuted: MutableList<UUID> = ArrayList(),
        tests: IntArray = IntArray(1),
    ): ComposeHarness = ComposeHarness {
        NotificationsContent(
            prefs = prefs,
            state = state,
            notice = notice,
            noun = "phone",
            unifiedPush = true,
            muted = if (muted) SettingsBFixtures.mutedChats else emptyList(),
            deliverySection = { DeliverySectionContent(SettingsBFixtures.ntfyRegistered, "phone", onOpen = {}) },
            onSwitch = { _, _ -> },
            onOpenSound = {},
            onNoticeAction = { actions += it },
            onUnmute = { unmuted += it },
            onSendTest = { tests[0]++ },
            onReset = {},
        )
    }

    @Test
    fun theDeliverySectionComesFirst() {
        val ui = notificationsScreen()
        val order = ui.nodes().map { it.described() ?: it.texts().firstOrNull() }
        val delivery = order.indexOf("Delivery, ntfy")
        val show = order.indexOf(NotificationsCopy.SHOW_NOTIFICATIONS)
        assertTrue("both drawn: $order", delivery >= 0 && show >= 0)
        assertTrue("Delivery above Show Notifications", delivery < show)
        assertTrue(ui.has(NotificationsCopy.pushFooter("phone", unifiedPush = true)))
    }

    @Test
    fun oneSystemCardAtATimeWithItsAction() {
        val cases = mapOf(
            SystemNotice.AllowNotifications to (NotificationsCopy.ALLOW_TITLE to NotificationsCopy.ALLOW_BUTTON),
            SystemNotice.AppBlocked to (NotificationsCopy.BLOCKED_TITLE to NotificationsCopy.OPEN_SETTINGS),
            SystemNotice.MessagesChannelOff to (NotificationsCopy.CHANNEL_OFF_TITLE to NotificationsCopy.OPEN_SETTINGS),
            SystemNotice.BackgroundRestricted to (NotificationsCopy.RESTRICTED_TITLE to NotificationsCopy.OPEN_SETTINGS),
        )
        for ((notice, words) in cases) {
            val actions = ArrayList<SystemNotice>()
            val ui = notificationsScreen(notice = notice, actions = actions)
            assertTrue(notice.name, ui.has(words.first))
            for (other in cases.keys - notice) assertTrue("$notice hides $other", ui.nodesWithText(cases.getValue(other).first).isEmpty())
            ui.nodes().single { it.texts() == listOf(words.second) && it.clickable() }.click()
            assertEquals(listOf(notice), actions)
        }
        assertTrue(notificationsScreen(notice = null).nodesWithText(NotificationsCopy.OPEN_SETTINGS).isEmpty())
    }

    @Test
    fun mutedChatsTheTestRowAndItsResult() {
        val unmuted = ArrayList<UUID>()
        val tests = IntArray(1)
        val ui = notificationsScreen(unmuted = unmuted, tests = tests, state = NotificationsSettingsState(testResult = "Sent. It should arrive in a moment."))
        ui.node("Unmute Mom").click()
        assertEquals(listOf(SettingsBFixtures.mom.id), unmuted)
        assertTrue(ui.has("Muted until 18:00"))
        ui.pressable(NotificationsCopy.SEND_TEST).click()
        assertEquals(1, tests[0])
        assertTrue(ui.has("Sent. It should arrive in a moment."))

        val sending = notificationsScreen(state = NotificationsSettingsState(isTesting = true), muted = false)
        assertTrue(sending.has(NotificationsCopy.SENDING))
        assertTrue(sending.nodes().single { it.texts().firstOrNull() == NotificationsCopy.SENDING }.isDisabled())
        assertTrue(sending.has(NotificationsCopy.NO_MUTED_CHATS))

        val off = notificationsScreen(prefs = NotificationPrefsState(enabled = false))
        assertTrue("Show Notifications off: no test", off.nodes().single { it.texts().firstOrNull() == NotificationsCopy.SEND_TEST }.isDisabled())
    }

    // ---- Privacy and Security ----

    private fun privacyScreen(
        state: PrivacyState = PrivacyState(),
        hasLoaded: Boolean = true,
        softwareKeystore: Boolean = false,
        retries: IntArray = IntArray(1),
        lockNow: IntArray = IntArray(1),
        unblocked: MutableList<UUID> = ArrayList(),
    ): ComposeHarness = ComposeHarness {
        PrivacyContent(
            state = state,
            settings = SettingsBFixtures.privacy,
            hasLoaded = hasLoaded,
            blocked = listOf(SettingsBFixtures.promo),
            autoLock = AutoLockDelay.Immediately,
            hideCapture = true,
            linkPreviews = true,
            relayCalls = false,
            biometric = null,
            softwareKeystore = softwareKeystore,
            noun = "phone",
            callbacks = PrivacyCallbacks(
                onAutoLock = {},
                onHideCapture = {},
                onRetry = { retries[0]++ },
                onFlip = { _, _ -> },
                onResetShareCode = {},
                onLinkPreviews = {},
                onRelayCalls = {},
                onLockNow = { lockNow[0]++ },
                onUnblock = { unblocked += it.userId },
            ),
        )
    }

    @Test
    fun privacyWaitsForTheServersValues() {
        val loading = privacyScreen(hasLoaded = false)
        assertTrue(loading.has(PrivacyCopy.LOADING))
        assertTrue("server switches wait", loading.node("Read receipts").isDisabled())
        assertFalse("local switches do not", loading.node(PrivacyCopy.HIDE_CAPTURE).isDisabled())
        val retries = IntArray(1)
        val failed = privacyScreen(state = PrivacyState(loadFailed = true), hasLoaded = false, retries = retries)
        assertTrue(failed.has(PrivacyCopy.LOAD_FAILED))
        failed.pressable(PrivacyCopy.TRY_AGAIN).click()
        assertEquals(1, retries[0])
        val loaded = privacyScreen()
        assertTrue(loaded.nodesWithText(PrivacyCopy.LOADING).isEmpty())
        assertFalse(loaded.node("Read receipts").isDisabled())
    }

    @Test
    fun privacyRowsLockNowBlockedAndTheKeystoreNotice() {
        val lockNow = IntArray(1)
        val unblocked = ArrayList<UUID>()
        val ui = privacyScreen(lockNow = lockNow, unblocked = unblocked, softwareKeystore = true)
        assertEquals("Immediately", ui.node(PrivacyCopy.AUTO_LOCK).config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(ui.has(PrivacyCopy.autoLockFootnote(null)))
        // The kit's switch row reads its title and state only (the subtitle is not spoken: C18).
        assertEquals("On", ui.node(PrivacyCopy.HIDE_CAPTURE).config.getOrNull(SemanticsProperties.StateDescription))
        ui.pressable(PrivacyCopy.LOCK_NOW).click()
        assertEquals(1, lockNow[0])
        ui.node("Unblock promo_deals").click()
        assertEquals(listOf(SettingsBFixtures.promo.userId), unblocked)
        assertTrue(ui.has(PrivacyCopy.softwareKeystoreNotice("phone")))
        assertTrue(privacyScreen(softwareKeystore = false).nodesWithText(PrivacyCopy.softwareKeystoreNotice("phone")).isEmpty())
    }
}
