package de.corespace.shroud.ui.contacts

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.core.model.AddContactOutcome
import de.corespace.shroud.core.model.ChatPeerActivity
import de.corespace.shroud.core.model.ContactsListState
import de.corespace.shroud.core.net.BlockItemDto
import de.corespace.shroud.core.net.ChatMuteDto
import de.corespace.shroud.core.net.PresenceDto
import de.corespace.shroud.testing.FakeAppClock
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.shell.FloatingTabBar
import de.corespace.shroud.ui.shell.LocalIsTabBarSearchActive
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.shell.ShellLayoutMath
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Renders every state of the contacts screens (W3-CONTACTS-UI acceptance) to a PNG under
 * `android/app/build/outputs/c8-screens/`, for the design pass to lay next to the frames `CumKS`
 * (Contacts), `BNBY4` (Contacts · Dark), `l14KDf` (Add Contact), `w6957` (Permission — Camera:
 * the sheet behind the system dialog), `oGuCt` (Camera — Denied), `rqBW3` (My QR Code), `ks0in`
 * (Contact Profile) and `Hueuv` (Contact Profile · Dark), and the iOS states without a frame. The
 * pictures are not committed; a few pixel checks prove each state drew what it should.
 *
 * The tab sits where the shell puts it: under a 52 dp status bar, over a 24 dp gesture bar, with
 * the floating tab bar 20 dp off the bottom and its clearance published (as `MainShell`). The
 * profile is a pushed screen, so no tab bar. 2× density: a PNG is the frame at 824 × 1830 px.
 * Motion is reduced so every animation has settled. Software rendering draws no backdrop blur.
 *
 * Runs only when asked — `SHROUD_RENDER_SCREENS=1` in the Gradle call's environment
 * (`SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*ContactsScreensRenderTest'`) — so the
 * shared unit-test JVM is not loaded with forty full-screen renders on every run. [ALWAYS] runs
 * every time: where the "Request sent" toast lands over the tab bar is behaviour the suite guards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsScreensRenderTest {
    private val zone: ZoneId = ZoneId.systemDefault()

    /** Friday 2 Oct 2026, 10:00 in the device's zone. */
    private val today: LocalDate = LocalDate.of(2026, 10, 2)
    private val clock = FakeAppClock(wallMillis = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli())
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val hosts = ArrayList<ComposeHarness>()
    private val frame = mutableIntStateOf(0)

    @get:Rule
    val testName = TestName()

    private val rendersWanted: Boolean = System.getenv(RENDER_ENV) == "1"

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render the contacts states", rendersWanted || testName.methodName in ALWAYS)
        Settings.System.putString(RuntimeEnvironment.getApplication().contentResolver, Settings.System.TIME_12_24, "24")
    }

    @After
    fun tearDown() {
        actionScope.cancel()
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
        Thread.sleep(SETTLE_REAL_MS)
        hosts.firstOrNull()?.idle()
    }

    // ---- data: the design's roster (CumKS) ----

    private val albert = UUID.fromString("9d4c6e5f-4a7b-4c8d-9e9f-0a1b2c3d4e5f")
    private val arlene = UUID.fromString("7b2a4c3d-2e5f-4a6b-9c7d-8e9f0a1b2c3d")
    private val devon = UUID.fromString("1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed")
    private val dianne = UUID.fromString("6a1f3b2c-1d4e-4f5a-8b6c-7d8e9f0a1b2c")
    private val guy = UUID.fromString("8c3b5d4e-3f6a-4b7c-8d8e-9f0a1b2c3d4e")
    private val jane = ContactsFixtures.JANE
    private val jacob = UUID.fromString("2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f")

    private fun at(time: LocalTime, day: LocalDate = today) = LocalDateTime.of(day, time).atZone(zone).toInstant()

    private fun ports(): FakeContactsPorts = FakeContactsPorts(actionScope, clock).apply {
        session.value = session.value!!.copy(username = "noah_vorberg", shareCode = "NOAH7Q2X")
    }

    /** The design's roster with its presence lines: last seen, online, recording, typing. */
    private fun FakeContactsPorts.designRoster() {
        contacts.contacts.value = listOf(
            ContactsFixtures.contact("Albert Flores", albert),
            ContactsFixtures.contact("Arlene McCoy", arlene),
            ContactsFixtures.contact("Devon Lane", devon),
            ContactsFixtures.contact("Dianne Russell", dianne),
            ContactsFixtures.contact("Guy Hawkins", guy),
            ContactsFixtures.contact("Jacob Jones", jacob),
            ContactsFixtures.contact("Jane Cooper", jane),
        )
        contacts.listState.value = ContactsListState(hasLoaded = true)
        contacts.presence.value = mapOf(
            albert to PresenceDto(albert, online = false, lastSeenAt = at(LocalTime.NOON, LocalDate.of(2026, 9, 21))),
            arlene to PresenceDto(arlene, online = true),
            dianne to PresenceDto(dianne, online = false, lastSeenAt = at(LocalTime.of(9, 41))),
            jacob to PresenceDto(jacob, online = false, lastSeenAt = at(LocalTime.of(21, 3), today.minusDays(1))),
            jane to PresenceDto(jane, online = true),
        )
        peerActivities.value = mapOf(devon to ChatPeerActivity.Recording, guy to ChatPeerActivity.Typing)
    }

    private fun FakeContactsPorts.pending() {
        contacts.incomingRequests.value = listOf(
            ContactsFixtures.request(ContactsFixtures.ZOE, "zoe_quinn"),
            // A request whose card has no handle: the id, truncated in the middle.
            ContactsFixtures.request(UUID.fromString("5f0c3a52-7b1e-4c6d-9a8b-2e4f6d8c0a1b"), null),
        )
    }

    // ---- the hosts ----

    /** The Contacts tab where the shell puts it: under the status bar, over the floating tab bar. */
    private fun tab(ports: FakeContactsPorts, dark: Boolean = false, query: String = "", searching: Boolean = false): ComposeHarness =
        host(dark) {
            val backdrop = rememberGlassBackdrop()
            val barBottom = ShellLayoutMath.barBottom(aboveKeyboard = false, imeBottom = 0.dp, gestureNavigation = true, navigationBarBottom = NAV_BAR)
            val clearance = ShellLayoutMath.tabBarClearance(barVisible = true, barBottom = barBottom, isSearching = searching)
            Box(Modifier.fillMaxSize()) {
                CompositionLocalProvider(
                    LocalTabBarClearance provides clearance,
                    LocalIsTabBarSearchActive provides searching,
                    LocalGlassBackdrop provides backdrop,
                ) {
                    Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                        ContactsTab(ports, query, {}, ContactsTestNavigation())
                    }
                }
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                    FloatingTabBar(
                        selection = MainTab.Contacts,
                        onSelect = {},
                        isSearching = searching,
                        onSearchingChange = {},
                        query = query,
                        onQueryChange = {},
                        searchFocus = remember { FocusRequester() },
                        badges = mapOf(MainTab.Chats to 3),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = barBottom)
                            .padding(horizontal = ShellLayoutMath.barSideInset(aboveKeyboard = false))
                            .widthIn(max = ShellLayoutMath.barMaxWidth),
                    )
                }
            }
        }

    /** The profile as a pushed screen (the tab bar hides while a contacts route is open). */
    private fun profile(ports: FakeContactsPorts, dark: Boolean = false, name: String = "Jane Cooper"): ComposeHarness {
        ports.identities.numbers[jane] = ContactsFixtures.NUMBER
        return host(dark) { ContactProfile(ports, jane, name, onBack = {}, onChatDeleted = {}) }
    }

    private fun host(dark: Boolean, content: @Composable () -> Unit): ComposeHarness {
        val ui = ComposeHarness(dark = dark) {
            frame.intValue
            OverlayHost { content() }
        }
        hosts += ui
        applyDesignInsets(ui)
        ui.settle()
        return ui
    }

    /** The design's status bar (52 dp) and gesture bar (24 dp), dispatched to the Compose view. */
    private fun applyDesignInsets(ui: ComposeHarness) {
        val density = ui.activity.resources.displayMetrics.density
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (STATUS_BAR.value * density).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (NAV_BAR.value * density).toInt()))
            .build()
        ViewCompat.dispatchApplyWindowInsets(ui.root, insets)
    }

    private fun render(ui: ComposeHarness, name: String): Bitmap {
        frame.intValue++
        ui.settle()
        // The staggered entrance waits in real time (kotlinx's `delay` off the main looper); let
        // the last row's 240 ms pass, then run what it posted.
        Thread.sleep(ENTRANCE_REAL_MS)
        ui.settle()
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        if (rendersWanted) {
            val dir = File("build/outputs/c8-screens").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun px(ui: ComposeHarness, dp: Float): Int = (dp * ui.activity.resources.displayMetrics.density).toInt()

    private fun rgb(color: Int) = color and 0xFFFFFF

    private fun setCamera(present: Boolean) {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setSystemFeature(PackageManager.FEATURE_CAMERA_ANY, present)
    }

    // ---- Contacts tab (CumKS, BNBY4) ----

    @Test
    fun contactsLight() {
        val ports = ports().apply { designRoster() }
        val ui = tab(ports)
        val bitmap = render(ui, "01-contacts-CumKS")
        assertEquals("the light `background` under the rows", 0xFFFFFF, rgb(bitmap.getPixel(px(ui, 400f), px(ui, 400f))))
    }

    @Test
    fun contactsDark() {
        val ports = ports().apply { designRoster() }
        val ui = tab(ports, dark = true)
        val bitmap = render(ui, "02-contacts-dark-BNBY4")
        assertTrue(Color.red(bitmap.getPixel(px(ui, 400f), px(ui, 400f))) < 40)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun contacts360() {
        render(tab(ports().apply { designRoster() }), "03-contacts-360")
    }

    @Test
    fun contactsWithRequests() {
        val ports = ports().apply {
            designRoster()
            pending()
        }
        render(tab(ports), "04-contacts-pending")
    }

    @Test
    fun contactsAnsweringARequest() {
        val ports = ports().apply {
            designRoster()
            pending()
            contacts.answerGate = CompletableDeferred()
        }
        val ui = tab(ports)
        ui.exactly("Accept").first().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke()
        render(ui, "05-contacts-answering")
    }

    @Test
    fun contactsSortedZtoA() {
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("Sorted A to Z")
        ui.node("Sorted Z to A")
        render(ui, "06-contacts-z-a")
    }

    @Test
    fun contactsSearch() {
        render(tab(ports().apply { designRoster() }, query = "Ja", searching = true), "07-contacts-search")
    }

    @Test
    fun contactsNoMatches() {
        val ui = tab(ports().apply { designRoster() }, query = "zz")
        assertTrue(ui.shows("No matches"))
        render(ui, "08-contacts-no-matches")
    }

    @Test
    fun contactsLoading() {
        render(tab(ports()), "09-contacts-loading")
    }

    @Test
    fun contactsError() {
        val ports = ports().apply { contacts.listState.value = ContactsListState(hasLoaded = true, error = "Could not connect to the server.") }
        render(tab(ports), "10-contacts-error")
    }

    @Test
    fun contactsEmpty() {
        val ports = ports().apply {
            contacts.listState.value = ContactsListState(hasLoaded = true)
            // A session from before share codes: the footer waits for it.
            session.value = session.value!!.copy(shareCode = null)
        }
        val ui = tab(ports)
        assertTrue(ui.shows("No contacts yet"))
        assertTrue(ui.shows("Loading share code…"))
        render(ui, "11-contacts-empty")
    }

    @Test
    fun contactsFooter() {
        val ports = ports().apply {
            contacts.contacts.value = listOf(ContactsFixtures.contact("Jane Cooper", jane))
            contacts.listState.value = ContactsListState(hasLoaded = true)
        }
        render(tab(ports), "12-contacts-share-footer")
    }

    @Test
    fun requestSentToast() {
        val ports = ports().apply {
            designRoster()
            contacts.addOutcome = { AddContactOutcome.Requested("jane_cooper") }
        }
        val ui = tab(ports)
        ui.tapLabel("Add contact")
        ui.typeInField("jane_cooper")
        ui.tapText("Add")
        assertTrue(ui.describe(), ui.shows("Request sent to jane_cooper"))
        render(ui, "13-contacts-request-sent")
        // The capsule floats 20 dp over the tab bar (`ToastBanner.swift:97`): the bar's clearance is
        // 20 + 64 dp from the screen's bottom, so the capsule ends 104 dp up — as on the Chats tab.
        val capsule = ui.nodes().single { SemanticsProperties.LiveRegion in it.config }
        assertEquals((ui.root.height - px(ui, 104f)).toFloat(), capsule.boundsInRoot.bottom, 2f)
    }

    // ---- Add Contact (l14KDf; the sheet behind w6957's dialog) ----

    @Test
    fun addContact() {
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("Add contact")
        assertTrue(ui.isDisabled("Add"))
        val bitmap = render(ui, "14-add-contact-l14KDf")
        // "Cancel" is flat glass on the sheet: the list's avatars under the sheet never show in it.
        assertGrey(bitmap, ui.exactly("Cancel").single().boundsInRoot.let { it.left.toInt() + px(ui, 8f) to it.center.y.toInt() })
    }

    /** The pixel at [at] carries no colour (an avatar gradient behind glass would). */
    private fun assertGrey(bitmap: Bitmap, at: Pair<Int, Int>) {
        val pixel = bitmap.getPixel(at.first, at.second)
        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        assertTrue("pixel ${Integer.toHexString(pixel)} at $at", channels.max() - channels.min() < 24)
    }

    @Test
    fun addContactDark() {
        val ui = tab(ports().apply { designRoster() }, dark = true)
        ui.tapLabel("Add contact")
        render(ui, "15-add-contact-dark")
    }

    @Test
    fun addContactTyped() {
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("Add contact")
        ui.typeInField("jane_cooper")
        render(ui, "16-add-contact-typed")
    }

    @Test
    fun addContactAppLink() {
        val ports = ports().apply {
            designRoster()
            contacts.pendingInvite.value = "https://shroud.corespace.de/u/JANE4K2Q"
        }
        val ui = tab(ports)
        assertTrue(ui.shows("https://shroud.corespace.de/u/JANE4K2Q"))
        assertEquals(emptyList<String>(), ports.calls("add("))
        render(ui, "17-add-contact-app-link")
    }

    @Test
    fun addContactAdding() {
        val ports = ports().apply {
            designRoster()
            contacts.addGate = CompletableDeferred()
        }
        val ui = tab(ports)
        ui.tapLabel("Add contact")
        ui.typeInField("jane_cooper")
        ui.tapText("Add")
        assertTrue(ui.shows("Adding…"))
        render(ui, "18-add-contact-adding")
    }

    @Test
    fun addContactError() {
        val ports = ports().apply {
            designRoster()
            contacts.addOutcome = { AddContactOutcome.Failed("User not found.") }
        }
        val ui = tab(ports)
        ui.tapLabel("Add contact")
        ui.typeInField("nobody_here")
        ui.tapText("Add")
        assertTrue(ui.shows("User not found."))
        render(ui, "19-add-contact-error")
    }

    // ---- Scanner (oGuCt and the undesigned live / no-camera chrome) ----

    @Test
    fun scannerDenied() {
        val ui = host(dark = false) {
            QrScannerOverlay(phase = ScannerPhase.Denied, onPhaseChange = {}, onCode = {}, onCancel = {})
        }
        val bitmap = render(ui, "20-scanner-denied-oGuCt")
        assertEquals("black, always", 0x000000, rgb(bitmap.getPixel(px(ui, 30f), px(ui, 300f))))
    }

    @Test
    fun scannerNoCamera() {
        setCamera(present = false)
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("Add contact")
        ui.tapText("Scan QR code")
        assertTrue(ui.describe(), ui.shows("No camera available. Paste their link instead."))
        render(ui, "21-scanner-no-camera")
    }

    @Test
    fun scannerLiveChrome() {
        // The camera picture itself cannot be drawn here: black stands in for it.
        val ui = host(dark = true) {
            ShroudTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    ScannerChrome(hint = ContactsCopy.SCANNER_HINT, onClose = {})
                }
            }
        }
        render(ui, "22-scanner-live-chrome")
    }

    // ---- My QR Code (rqBW3) ----

    @Test
    fun myQrCode() {
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("My QR code")
        assertTrue(ui.shows("@noah_vorberg"))
        val bitmap = render(ui, "23-my-qr-rqBW3")
        assertGrey(bitmap, ui.exactly("Done").single().boundsInRoot.let { it.left.toInt() + px(ui, 8f) to it.center.y.toInt() })
    }

    @Test
    fun myQrCodeDark() {
        val ui = tab(ports().apply { designRoster() }, dark = true)
        ui.tapLabel("My QR code")
        val bitmap = render(ui, "24-my-qr-dark")
        // The code stays dark on white in the dark theme: its 12 dp white margin.
        val code = ui.node("QR code").boundsInRoot
        assertEquals(0xFFFFFF, rgb(bitmap.getPixel(code.left.toInt() + px(ui, 6f), code.center.y.toInt())))
    }

    @Test
    fun myQrCodeLoading() {
        val ports = ports().apply {
            designRoster()
            session.value = session.value!!.copy(shareCode = null)
        }
        val ui = tab(ports)
        ui.tapLabel("My QR code")
        assertTrue(ui.shows("Loading your code…"))
        render(ui, "25-my-qr-loading")
    }

    @Test
    fun myQrCodeCopied() {
        val ui = tab(ports().apply { designRoster() })
        ui.tapLabel("My QR code")
        ui.tapLabel("Copy share code")
        assertTrue(ui.shows("Code copied"))
        render(ui, "26-my-qr-copied")
    }

    // ---- Contact Profile (ks0in, Hueuv) ----

    @Test
    fun profileLight() {
        val ports = ports().apply { contacts.presence.value = mapOf(jane to PresenceDto(jane, online = true)) }
        val ui = profile(ports)
        val bitmap = render(ui, "27-profile-ks0in")
        // `backgroundGrouped` edge to edge, white cards on it.
        assertTrue(rgb(bitmap.getPixel(px(ui, 4f), px(ui, 600f))) != 0xFFFFFF)
    }

    @Test
    fun profileDark() {
        val ports = ports().apply { contacts.presence.value = mapOf(jane to PresenceDto(jane, online = true)) }
        render(profile(ports, dark = true), "28-profile-dark-Hueuv")
    }

    @Test
    fun profileVerifiedAndLastSeen() {
        val ports = ports().apply {
            contacts.presence.value = mapOf(jane to PresenceDto(jane, online = false, lastSeenAt = at(LocalTime.of(9, 41))))
            identities.verifiedPeers.value = setOf(jane)
        }
        val ui = profile(ports)
        assertTrue(ui.shows("Verified"))
        render(ui, "29-profile-verified-last-seen")
    }

    @Test
    fun profileBeforePresence() {
        val ui = profile(ports())
        assertTrue(ui.shows("Shroud contact"))
        render(ui, "30-profile-no-presence")
    }

    @Test
    fun profileTyping() {
        val ports = ports().apply { peerActivities.value = mapOf(jane to ChatPeerActivity.Typing) }
        render(profile(ports), "31-profile-typing")
    }

    @Test
    fun profileKeyChanged() {
        val ports = ports().apply {
            contacts.presence.value = mapOf(jane to PresenceDto(jane, online = true))
            identities.change(jane)
        }
        val ui = profile(ports)
        assertTrue(ui.shows("Encryption key changed"))
        render(ui, "32-profile-key-changed")
    }

    @Test
    fun profileTrustSheet() {
        val ports = ports().apply { identities.change(jane) }
        val ui = profile(ports)
        ui.tapText("I verified this contact")
        render(ui, "33-profile-trust-sheet")
    }

    @Test
    fun profileBlockSheet() {
        val ui = profile(ports())
        ui.tapText("Block Jane Cooper")
        render(ui, "34-profile-block-sheet")
    }

    @Test
    fun profileBlocked() {
        val ports = ports().apply { contacts.blocked.value = listOf(BlockItemDto(jane, "Jane Cooper", clock.now())) }
        val ui = profile(ports)
        assertTrue(ui.shows("Unblock Jane Cooper"))
        render(ui, "35-profile-blocked")
    }

    @Test
    fun profileUnblockSheet() {
        val ports = ports().apply { contacts.blocked.value = listOf(BlockItemDto(jane, "Jane Cooper", clock.now())) }
        val ui = profile(ports)
        ui.tapText("Unblock Jane Cooper")
        render(ui, "36-profile-unblock-sheet")
    }

    @Test
    fun profileBlockedToast() {
        val ui = profile(ports())
        ui.tapText("Block Jane Cooper")
        ui.tapText("Block")
        assertTrue(ui.shows("Jane Cooper blocked"))
        render(ui, "37-profile-blocked-toast")
    }

    @Test
    fun profileDeleteSheet() {
        val ui = profile(ports())
        ui.tapText("Delete Chat")
        render(ui, "38-profile-delete-sheet")
    }

    @Test
    fun profileMuteMenu() {
        val ui = profile(ports())
        ui.tapLabel("Mute")
        assertTrue(ui.shows("Until I Turn It Back On"))
        render(ui, "39-profile-mute-menu")
    }

    @Test
    fun profileMuted() {
        val ports = ports().apply { mutes[jane] = ChatMuteDto(until = at(LocalTime.of(14, 30))) }
        val ui = profile(ports)
        assertTrue(ui.shows("Muted until 14:30"))
        render(ui, "40-profile-muted")
    }

    @Test
    fun profileCannotMute() {
        val ports = ports().apply { canMuteChats = false }
        val ui = profile(ports)
        ui.tapLabel("Mute")
        render(ui, "41-profile-mute-needs-chat")
    }

    @Test
    fun profileLongName() {
        val ui = profile(ports(), name = "maximilian_alexander_vorberg")
        render(ui, "42-profile-long-name")
    }

    private companion object {
        const val SETTLE_REAL_MS = 30L
        const val ENTRANCE_REAL_MS = 400L
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"

        /** Run on every unit-test run, renders wanted or not. */
        val ALWAYS = setOf("requestSentToast")
        val STATUS_BAR = 52.dp
        val NAV_BAR = 24.dp
    }
}
