package de.corespace.shroud.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.core.devices.DeviceRow
import de.corespace.shroud.core.keys.BiometricLabel
import de.corespace.shroud.core.notifications.NotificationPrefsState
import de.corespace.shroud.core.notifications.NotificationSound
import de.corespace.shroud.core.push.UnifiedPushState
import de.corespace.shroud.core.storage.AutoLockDelay
import de.corespace.shroud.ui.components.ActionSheet
import de.corespace.shroud.ui.components.ActionSheetItem
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.settings.SettingsBFixtures.chrome
import de.corespace.shroud.ui.settings.SettingsBFixtures.designDevices
import de.corespace.shroud.ui.settings.SettingsBFixtures.ipad
import de.corespace.shroud.ui.settings.SettingsBFixtures.mac
import de.corespace.shroud.ui.settings.SettingsBFixtures.pc
import de.corespace.shroud.ui.settings.SettingsBFixtures.pixel
import de.corespace.shroud.ui.settings.devices.DeviceDetailSheet
import de.corespace.shroud.ui.settings.devices.DeviceRemovals
import de.corespace.shroud.ui.settings.devices.DevicesContent
import de.corespace.shroud.ui.settings.devices.DevicesCopy
import de.corespace.shroud.ui.settings.devices.DevicesUiState
import de.corespace.shroud.ui.settings.devices.RefreshablePushedScreen
import de.corespace.shroud.ui.settings.notifications.NotificationSoundContent
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
import de.corespace.shroud.ui.settings.push.PushDeliveryContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every state of Settings › Devices, Device Details, Notifications and Sounds, Sound,
 * Delivery and Privacy and Security (C7 W3-SETTINGS-B, C15 W3-PUSH UI half) to a PNG under
 * `android/app/build/outputs/c7-screens/`, for the design pass (C16) to lay next to the frames
 * `Gchkq` (Devices), `nUbf0` (Device Details), `j7NxDm` (Notifications and Sounds), `jnjw6`
 * (Notifications — Denied), `YkjlJ` / `Xri1G` (Privacy and Security, redrawn from iOS) and the new
 * Delivery frames. The pictures are not committed; the assertions check the pixels that prove a
 * state drew what it should (the device tiles' kind colours, the screen background).
 *
 * The screen sits where the shell puts a pushed Settings screen: under a 52 dp status bar, over a
 * 24 dp gesture bar (the design's insets), inside the app's overlay host so sheets, menus and
 * dialogs draw above it. 2× density. Long screens render at their full height (the frames
 * `j7NxDm` and `YkjlJ` are taller than a phone too). Motion is reduced so every animation has
 * settled. Software rendering draws no blur: glass shows its translucent fill only.
 *
 * The renders run only when asked — `SHROUD_RENDER_SCREENS=1` in the environment of the Gradle
 * call (`SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*SettingsBScreensRenderTest'`)
 * — so the shared unit-test JVM is not loaded with forty full-screen renders on every run.
 * [ALWAYS] runs every time: the device tiles' colours (the green tile of an Android device, kind 4)
 * are behaviour the suite guards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBScreensRenderTest {
    private val hosts = ArrayList<ComposeHarness>()
    private val frame = mutableIntStateOf(0)

    @get:Rule
    val testName = TestName()

    private val rendersWanted: Boolean = System.getenv(RENDER_ENV) == "1"

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render the Settings B states", rendersWanted || testName.methodName in ALWAYS)
    }

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    // ---- the host ----

    private fun screen(dark: Boolean = false, content: @Composable () -> Unit): ComposeHarness {
        val ui = ComposeHarness(dark = dark) {
            frame.intValue
            OverlayHost { Box(Modifier.fillMaxSize()) { content() } }
        }
        hosts += ui
        applyDesignInsets(ui)
        ui.settle()
        return ui
    }

    private fun ComposeHarness.settle() {
        frame.intValue++
        idle()
        idle()
        // Robolectric runs no view traversal here: a draw makes the Compose view lay out, so the
        // bounds a step reads are the current ones.
        root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        idle()
    }

    private fun applyDesignInsets(ui: ComposeHarness) {
        val density = ui.activity.resources.displayMetrics.density
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (STATUS_BAR.value * density).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (NAV_BAR.value * density).toInt()))
            .build()
        ViewCompat.dispatchApplyWindowInsets(ui.root, insets)
    }

    private fun render(ui: ComposeHarness, name: String): Bitmap {
        ui.settle()
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        if (rendersWanted) {
            val dir = File("build/outputs/c7-screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

    private fun ComposeHarness.click(node: SemanticsNode) {
        node.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    private fun ComposeHarness.clickLabel(label: String) = click(node(label))

    private fun px(ui: ComposeHarness, dp: Float): Int = (dp * ui.activity.resources.displayMetrics.density).toInt()

    private fun rgb(color: Int) = color and 0xFFFFFF

    /** The fill of a device row's 30 dp tile: 4 dp into it, beside the glyph, at the row's middle. */
    private fun tileFill(ui: ComposeHarness, bitmap: Bitmap, name: String): Int {
        val row = ui.nodes().single { it.texts().firstOrNull() == name && SemanticsActions.OnClick in it.config }
        val bounds = row.boundsInRoot
        return rgb(bitmap.getPixel(bounds.left.toInt() + px(ui, 14f + 4f), bounds.center.y.toInt()))
    }

    // ---- Devices ----

    private fun devices(state: DevicesUiState, dark: Boolean = false, overlay: @Composable () -> Unit = {}): ComposeHarness = screen(dark) {
        RefreshablePushedScreen(DevicesCopy.TITLE, onBack = {}, onRefresh = {}) {
            DevicesContent(state, "phone", SettingsBFixtures::timeLabel, onRetry = {}, onOpen = {}, onRemove = {}, onRemoveAll = {})
        }
        overlay()
    }

    private fun details(device: DeviceRow, isRevoking: Boolean = false): @Composable () -> Unit = {
        DeviceDetailSheet(
            device = device,
            isRevoking = isRevoking,
            noun = "phone",
            lastActive = { DevicesCopy.lastActive(it, SettingsBFixtures::timeLabel) },
            formatDate = SettingsBFixtures::longDate,
            onRename = { _, _ -> null },
            onCopyId = {},
            onRevoke = {},
            onDismiss = {},
        )
    }

    /** `Gchkq`: this phone (Android, kind 4) and the two others; each tile in its kind's colour. */
    @Test
    fun devicesDesign() {
        val ui = devices(SettingsBFixtures.devices(designDevices))
        val bitmap = render(ui, "c7-01-devices-Gchkq")
        assertEquals("the Android phone's tile is green (P4)", ANDROID_GREEN, tileFill(ui, bitmap, "Pixel 9a"))
        assertEquals("a web browser's is orange", WEB_ORANGE, tileFill(ui, bitmap, "Chrome on Mac"))
        assertEquals("an iPad's is blue", APPLE_BLUE, tileFill(ui, bitmap, "iPad Air"))
        assertTrue("the light grouped background", Color.red(bitmap.getPixel(px(ui, 4f), px(ui, 300f))) > 200)
    }

    @Test
    fun devicesLoading() {
        render(devices(SettingsBFixtures.loading), "c7-02-devices-loading")
    }

    @Test
    fun devicesLoadError() {
        render(devices(SettingsBFixtures.loadFailed), "c7-03-devices-load-error")
    }

    @Test
    fun devicesAlone() {
        render(devices(SettingsBFixtures.devices(listOf(pixel))), "c7-04-devices-no-other-devices")
    }

    @Test
    @Config(qualifiers = "w412dp-h1100dp-port-xhdpi")
    fun devicesAtTheLimit() {
        val ui = devices(SettingsBFixtures.devices(listOf(pixel, chrome, ipad, mac, pc)))
        val bitmap = render(ui, "c7-05-devices-at-the-limit")
        assertEquals("a Mac's tile is purple", COMPUTER_PURPLE, tileFill(ui, bitmap, "Niklas’s MacBook"))
        assertEquals("a computer's tile is purple", COMPUTER_PURPLE, tileFill(ui, bitmap, "Windows desktop"))
    }

    @Test
    fun devicesRemovingWithAnError() {
        val state = SettingsBFixtures.devices(
            designDevices,
            error = "The Internet connection appears to be offline.",
            removals = DeviceRemovals(revokingIds = setOf(chrome.id)),
        )
        render(devices(state), "c7-06-devices-removing-and-error")
    }

    @Test
    fun devicesRemovingAll() {
        render(devices(SettingsBFixtures.devices(designDevices, removals = DeviceRemovals(isRevokingAll = true))), "c7-07-devices-removing-all")
    }

    @Test
    fun devicesRemoveConfirmation() {
        val ui = devices(SettingsBFixtures.devices(designDevices)) {
            ActionSheet(
                visible = true,
                title = DevicesCopy.confirmTitle("Chrome on Mac"),
                message = DevicesCopy.CONFIRM_MESSAGE_ONE,
                items = listOf(ActionSheetItem(DevicesCopy.REMOVE, destructive = true) {}),
                onDismiss = {},
            )
        }
        render(ui, "c7-08-devices-remove-confirmation")
    }

    @Test
    fun devicesRemoveAllConfirmation() {
        val ui = devices(SettingsBFixtures.devices(designDevices)) {
            ActionSheet(
                visible = true,
                title = DevicesCopy.CONFIRM_ALL_TITLE,
                message = DevicesCopy.confirmAllMessage("phone"),
                items = listOf(ActionSheetItem(DevicesCopy.confirmAllButton(2), destructive = true) {}),
                onDismiss = {},
            )
        }
        render(ui, "c7-09-devices-remove-all-confirmation")
    }

    /** `nUbf0`, this phone: the 64 dp green phone tile, "Android app", the Log Out note. */
    @Test
    fun deviceDetailsThisAndroidPhone() {
        val ui = devices(SettingsBFixtures.devices(designDevices), overlay = details(pixel))
        val bitmap = render(ui, "c7-10-device-details-android-nUbf0")
        val name = ui.nodes().single { it.texts() == listOf("Pixel 9a") }.boundsInRoot
        val tileCentreY = name.top.toInt() - px(ui, 10f + 32f)
        val tileLeft = ui.root.width / 2 - px(ui, 32f)
        assertEquals("the details tile is green too", ANDROID_GREEN, rgb(bitmap.getPixel(tileLeft + px(ui, 6f), tileCentreY)))
    }

    @Test
    fun deviceDetailsAnotherDevice() {
        render(devices(SettingsBFixtures.devices(designDevices), overlay = details(chrome)), "c7-11-device-details-web")
    }

    @Test
    fun deviceDetailsRemoving() {
        render(devices(SettingsBFixtures.devices(designDevices), overlay = details(chrome, isRevoking = true)), "c7-12-device-details-removing")
    }

    @Test
    fun deviceDetailsRename() {
        val ui = devices(SettingsBFixtures.devices(designDevices), overlay = details(pixel))
        ui.clickLabel("Rename Pixel 9a")
        render(ui, "c7-13-device-details-rename-dialog")
    }

    @Test
    fun devicesDark() {
        val ui = devices(SettingsBFixtures.devices(designDevices), dark = true)
        val bitmap = render(ui, "c7-14-devices-dark")
        assertEquals(ANDROID_GREEN, tileFill(ui, bitmap, "Pixel 9a"))
        assertTrue(Color.red(bitmap.getPixel(px(ui, 4f), px(ui, 300f))) < 40)
    }

    // ---- Notifications and Sounds ----

    private fun notifications(
        delivery: DeliveryState = SettingsBFixtures.ntfyRegistered,
        notice: SystemNotice? = null,
        state: NotificationsSettingsState = NotificationsSettingsState(),
        muted: Boolean = true,
        dark: Boolean = false,
        overlay: @Composable () -> Unit = {},
    ): ComposeHarness = screen(dark) {
        PushedScreen(NotificationsCopy.TITLE, onBack = {}) {
            NotificationsContent(
                prefs = NotificationPrefsState(),
                state = state,
                notice = notice,
                noun = "phone",
                unifiedPush = delivery.delivery.unifiedPush is UnifiedPushState.Registered,
                muted = if (muted) SettingsBFixtures.mutedChats else emptyList(),
                deliverySection = { DeliverySectionContent(delivery, "phone", onOpen = {}) },
                onSwitch = { _, _ -> },
                onOpenSound = {},
                onNoticeAction = {},
                onUnmute = {},
                onSendTest = {},
                onReset = {},
            )
        }
        overlay()
    }

    @Test
    @Config(qualifiers = "w412dp-h1560dp-port-xhdpi")
    fun notificationsDesign() {
        render(notifications(), "c7-15-notifications-j7NxDm")
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-port-xhdpi")
    fun notificationsAllowAndNoDelivery() {
        render(notifications(SettingsBFixtures.noneInstalled, SystemNotice.AllowNotifications), "c7-16-notifications-allow-and-delivery-off")
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-port-xhdpi")
    fun notificationsDenied() {
        render(notifications(notice = SystemNotice.AppBlocked), "c7-17-notifications-denied-jnjw6")
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-port-xhdpi")
    fun notificationsChannelOff() {
        render(notifications(notice = SystemNotice.MessagesChannelOff), "c7-18-notifications-channel-off")
    }

    @Test
    @Config(qualifiers = "w412dp-h1700dp-port-xhdpi")
    fun notificationsRestricted() {
        render(notifications(SettingsBFixtures.backgroundOptimized, SystemNotice.BackgroundRestricted), "c7-19-notifications-restricted-background-connection")
    }

    @Test
    @Config(qualifiers = "w412dp-h1560dp-port-xhdpi")
    fun notificationsSendingNoMutes() {
        render(notifications(SettingsBFixtures.refusedHost, state = NotificationsSettingsState(isTesting = true), muted = false), "c7-20-notifications-sending-no-mutes-refused-host")
    }

    @Test
    @Config(qualifiers = "w412dp-h1600dp-port-xhdpi")
    fun notificationsTestResult() {
        val result = "No UnifiedPush distributor installed. Install one such as ntfy, or turn on Background connection."
        render(notifications(SettingsBFixtures.noneInstalled, state = NotificationsSettingsState(testResult = result)), "c7-21-notifications-test-result-no-delivery")
    }

    @Test
    fun notificationsResetConfirmation() {
        val ui = notifications {
            ActionSheet(
                visible = true,
                title = NotificationsCopy.RESET_TITLE,
                message = NotificationsCopy.RESET_MESSAGE,
                items = listOf(ActionSheetItem(NotificationsCopy.RESET_BUTTON, destructive = true) {}),
                onDismiss = {},
            )
        }
        render(ui, "c7-22-notifications-reset-confirmation")
    }

    @Test
    @Config(qualifiers = "w412dp-h1560dp-port-xhdpi")
    fun notificationsDark() {
        render(notifications(SettingsBFixtures.backgroundUnrestricted, dark = true), "c7-23-notifications-dark")
    }

    @Test
    fun soundPicker() {
        val ui = screen {
            PushedScreen(NotificationsCopy.SOUND_TITLE, onBack = {}) {
                NotificationSoundContent(selected = NotificationSound.Chime, noun = "phone", soundDiffers = true, onPick = {})
            }
        }
        render(ui, "c7-24-sound-picker-with-channel-differs")
    }

    // ---- Delivery ----

    private fun delivery(state: DeliveryState, dark: Boolean = false): ComposeHarness = screen(dark) {
        PushedScreen(DeliveryCopy.TITLE, onBack = {}) {
            PushDeliveryContent(state, "phone", onChoose = {}, onBackgroundConnection = {}, onOpenBatterySettings = {})
        }
    }

    @Test
    fun deliveryNoneInstalled() {
        render(delivery(SettingsBFixtures.noneInstalled), "c7-25-delivery-none-installed")
    }

    @Test
    fun deliveryOneRegistered() {
        render(delivery(SettingsBFixtures.ntfyRegistered), "c7-26-delivery-one-registered")
    }

    @Test
    fun deliverySeveralToChoose() {
        val ui = delivery(SettingsBFixtures.severalToChoose)
        render(ui, "c7-27-delivery-several-to-choose")
        ui.clickLabel(DeliveryCopy.PUSH_DISTRIBUTOR)
        render(ui, "c7-28-delivery-several-menu-open")
    }

    @Test
    fun deliveryRegistering() {
        render(delivery(SettingsBFixtures.ntfyRegistering), "c7-29-delivery-registering")
    }

    @Test
    fun deliveryRefusedHost() {
        render(delivery(SettingsBFixtures.refusedHost), "c7-30-delivery-server-refused-host")
    }

    @Test
    fun deliveryFailed() {
        render(delivery(SettingsBFixtures.distributorFailed), "c7-31-delivery-distributor-failed")
    }

    @Test
    fun deliveryBackgroundOptimized() {
        render(delivery(SettingsBFixtures.backgroundOptimized), "c7-32-delivery-background-on-battery-optimized")
    }

    @Test
    fun deliveryBothUnrestricted() {
        render(delivery(SettingsBFixtures.bothPaths), "c7-33-delivery-both-paths-battery-unrestricted")
    }

    @Test
    fun deliveryDark() {
        render(delivery(SettingsBFixtures.backgroundOptimized, dark = true), "c7-34-delivery-dark")
    }

    // ---- Privacy and Security ----

    private val noPrivacyActions = PrivacyCallbacks({}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})

    private fun privacy(
        state: PrivacyState = PrivacyState(),
        hasLoaded: Boolean = true,
        biometric: BiometricLabel? = BiometricLabel.Fingerprint,
        softwareKeystore: Boolean = false,
        dark: Boolean = false,
        overlay: @Composable () -> Unit = {},
    ): ComposeHarness = screen(dark) {
        PushedScreen(PrivacyCopy.TITLE, onBack = {}) {
            PrivacyContent(
                state = state,
                settings = SettingsBFixtures.privacy,
                hasLoaded = hasLoaded,
                blocked = listOf(SettingsBFixtures.promo),
                autoLock = AutoLockDelay.Immediately,
                hideCapture = true,
                linkPreviews = true,
                relayCalls = false,
                biometric = biometric,
                softwareKeystore = softwareKeystore,
                noun = "phone",
                callbacks = noPrivacyActions,
            )
        }
        overlay()
    }

    @Test
    @Config(qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyLoaded() {
        render(privacy(softwareKeystore = true), "c7-35-privacy-YkjlJ-with-keystore-notice")
    }

    @Test
    @Config(qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyLoading() {
        render(privacy(hasLoaded = false, biometric = null), "c7-36-privacy-loading-screen-lock-only")
    }

    @Test
    @Config(qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyLoadFailed() {
        render(privacy(state = PrivacyState(loadFailed = true), hasLoaded = false), "c7-37-privacy-load-failed")
    }

    @Test
    fun privacyAutoLockMenu() {
        val ui = privacy()
        ui.clickLabel(PrivacyCopy.AUTO_LOCK)
        render(ui, "c7-38-privacy-auto-lock-menu")
    }

    @Test
    fun privacyResetQrConfirmation() {
        val ui = privacy {
            ActionSheet(
                visible = true,
                title = PrivacyCopy.RESET_QR_TITLE,
                message = PrivacyCopy.RESET_QR_MESSAGE,
                items = listOf(ActionSheetItem(PrivacyCopy.RESET, destructive = true) {}),
                onDismiss = {},
            )
        }
        render(ui, "c7-39-privacy-reset-qr-confirmation")
    }

    /** API 30: the device-protection subtitle says the protection stays on; glass without blur. */
    @Test
    @Config(sdk = [30], qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyApi30() {
        render(privacy(), "c7-40-privacy-api30")
    }

    @Test
    @Config(sdk = [33], qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyApi33() {
        render(privacy(), "c7-41-privacy-api33")
    }

    @Test
    @Config(qualifiers = "w412dp-h2100dp-port-xhdpi")
    fun privacyDark() {
        render(privacy(softwareKeystore = true, dark = true), "c7-42-privacy-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun delivery360() {
        render(delivery(SettingsBFixtures.backgroundOptimized), "c7-43-delivery-360")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun devices360() {
        render(devices(SettingsBFixtures.devices(designDevices)), "c7-44-devices-360")
    }

    private companion object {
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"

        /** Run on every unit-test run, renders wanted or not. */
        val ALWAYS = setOf("devicesDesign", "deviceDetailsThisAndroidPhone")
        val STATUS_BAR = 52.dp
        val NAV_BAR = 24.dp

        // `DevicesView.swift:848-856` (core `DeviceKind.tintArgb`).
        const val ANDROID_GREEN = 0x2FA85B
        const val WEB_ORANGE = 0xF76B1C
        const val APPLE_BLUE = 0x2E8FE0
        const val COMPUTER_PURPLE = 0x9B4AE6
    }
}
