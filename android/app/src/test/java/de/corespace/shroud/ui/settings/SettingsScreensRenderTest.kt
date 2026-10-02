package de.corespace.shroud.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.ViewGroup
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.R
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.transcription.TranscriptionInstallState
import de.corespace.shroud.core.transcription.TranscriptionLanguage
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.LocalGlassBackdrop
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.components.rememberGlassBackdrop
import de.corespace.shroud.ui.shell.FloatingTabBar
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.shell.MainTab
import de.corespace.shroud.ui.shell.ShellLayoutMath
import de.corespace.shroud.ui.theme.ShroudTheme
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CompletableDeferred
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
import java.util.Locale

/**
 * Renders every state of the W3-SETTINGS-A screens to a PNG under
 * `android/app/build/outputs/c6-screens/`, for the design pass to lay next to the frames `PQtB1`
 * (Settings), `pt7Mu` (Scrolled), `nRZIR` (Dark), `JOwZ5` (360), `oEQcT` / `PQ996` (Appearance),
 * `uG2Sv` (Transcription) and `N6l9Rb` (Server), and the states those frames do not draw (the
 * download card, the icon failure line, the server's error, Saving, Saved and Change server?
 * states, the Log Out sheet). The pictures are not committed; a few pixel checks prove each state
 * drew what it should.
 *
 * The screens sit where the shell puts them: under a 52 dp status bar and over a 24 dp gesture bar
 * (the design's insets); the Settings root has the floating tab bar 20 dp off the bottom with its
 * clearance published, as `MainShell` does, and the pushed screens have none (the tab bar hides
 * while a Settings route is pushed). 2× density, so a PNG is the frame at 824 × 1830 px. Motion
 * is reduced so every animation has settled. Software rendering draws no backdrop blur: glass
 * shows its translucent fill only.
 *
 * The renders run only when asked — `SHROUD_RENDER_SCREENS=1` in the environment of the Gradle call
 * (`SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*SettingsScreensRenderTest'`) — so the
 * shared unit-test JVM is not loaded with two dozen full-screen renders on every run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreensRenderTest {
    private val hosts = ArrayList<ComposeHarness>()
    private val frame = mutableIntStateOf(0)

    @get:Rule
    val testName = TestName()

    private val rendersWanted: Boolean = System.getenv(RENDER_ENV) == "1"

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render the Settings A states", rendersWanted)
    }

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    // ---- data: the design's profile (PQtB1) ----

    private val designRoot = SettingsRootState(
        username = "noah_vorberg",
        userId = "3F2504E0-4F89-41D3-9A0C-0305E82C3301",
        deviceCount = 3,
        notificationsSummary = "On",
        themeTitle = ColorTheme.System.title,
        serverSubtitle = SettingsCopy.OFFICIAL_SERVER_SUBTITLE,
        isLoggingOut = false,
        deviceNoun = DeviceNoun.PHONE,
    )

    private val local = ServerConfiguration.localDevelopment("10.0.2.2", 8080)

    /** Whisper's languages as the picker lists them on an English phone (device language first, then A–Z). */
    private val whisperLanguages: List<Locale> = TranscriptionPicker.order(
        TranscriptionLanguage.WHISPER_CODES.let { codes -> listOf("en") + (codes - "en") }.map(Locale::forLanguageTag),
        preferredTags = listOf("en-US"),
        displayLocale = Locale.ENGLISH,
    )

    // ---- the host ----

    /** The Settings root where the shell puts it: under the status bar, over the floating tab bar. */
    private fun root(state: SettingsRootState, dark: Boolean = false, scroll: ScrollState? = null): ComposeHarness = host(dark) {
        val backdrop = rememberGlassBackdrop()
        val barBottom = ShellLayoutMath.barBottom(aboveKeyboard = false, imeBottom = 0.dp, gestureNavigation = true, navigationBarBottom = NAV_BAR)
        val clearance = ShellLayoutMath.tabBarClearance(barVisible = true, barBottom = barBottom, isSearching = false)
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalTabBarClearance provides clearance, LocalGlassBackdrop provides backdrop) {
                Box(Modifier.fillMaxSize().hazeSource(backdrop)) {
                    if (scroll != null) {
                        SettingsRootContent(state, onRoute = {}, onOpenCalls = if (state.isLoggingOut) null else ({}), onLogOut = {}, scrollState = scroll)
                    } else {
                        SettingsRootContent(state, onRoute = {}, onOpenCalls = if (state.isLoggingOut) null else ({}), onLogOut = {})
                    }
                }
            }
            CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                FloatingTabBar(
                    selection = MainTab.Settings,
                    onSelect = {},
                    isSearching = false,
                    onSearchingChange = {},
                    query = "",
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

    private fun host(dark: Boolean = false, content: @Composable () -> Unit): ComposeHarness {
        val ui = ComposeHarness(dark = dark) {
            frame.intValue
            OverlayHost { content() }
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
        // Robolectric runs no view traversal here: a draw makes the Compose view measure and lay
        // out, so the bounds a step reads are the current ones.
        root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        idle()
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
        ui.settle()
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        save(bitmap, name)
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/outputs/c6-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun SemanticsNode.click() = config[SemanticsActions.OnClick].action!!.invoke()

    private fun ComposeHarness.clickText(text: String) {
        nodes().first { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && SemanticsActions.OnClick in node.config }.click()
        settle()
    }

    private fun ComposeHarness.clickLabel(label: String) {
        nodes().first { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.startsWith(label) } == true && SemanticsActions.OnClick in node.config }.click()
        settle()
    }

    /** dp → px of the 2× render. */
    private fun px(ui: ComposeHarness, dp: Float): Int = (dp * ui.activity.resources.displayMetrics.density).toInt()

    private fun rgb(color: Int) = color and 0xFFFFFF

    // ---- Settings root ----

    @Test
    fun settingsLight() {
        val ui = root(designRoot)
        val bitmap = render(ui, "01-settings-PQtB1")
        // The grouped background between the cards.
        assertEquals(0xF2F2F7, rgb(bitmap.getPixel(px(ui, 8f), px(ui, 400f))))
        assertTrue(ui.nodesWithText("Signed in as @noah_vorberg").isNotEmpty())
    }

    @Test
    fun settingsHalfCollapsed() {
        render(root(designRoot, scroll = ScrollState(67 * 2)), "02-settings-scrolled-half")
    }

    @Test
    fun settingsCollapsed() {
        val ui = root(designRoot, scroll = ScrollState(240 * 2))
        render(ui, "03-settings-scrolled-pt7Mu")
    }

    @Test
    fun settingsDark() {
        val ui = root(designRoot.copy(themeTitle = ColorTheme.Dark.title), dark = true)
        val bitmap = render(ui, "04-settings-dark-nRZIR")
        assertEquals(0x1C1C1E, rgb(bitmap.getPixel(px(ui, 8f), px(ui, 400f))))
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun settings360() {
        render(root(designRoot), "05-settings-360-JOwZ5")
    }

    @Test
    fun settingsSigningOut() {
        // While the wipe runs: "Signing out…" with its spinner; the count not loaded; Recent Calls "Soon" without a Calls tab.
        render(root(designRoot.copy(isLoggingOut = true, deviceCount = null)), "06-settings-signing-out")
    }

    @Test
    fun settingsLogOutSheet() {
        val ui = root(designRoot)
        ui.clickLabel("Log Out")
        assertTrue(ui.describe(), ui.nodesWithText("Log out of Shroud?").isNotEmpty())
        render(ui, "07-settings-log-out-sheet")
    }

    @Test
    fun settingsSelfHostedAndNotificationsOff() {
        val state = designRoot.copy(
            notificationsSummary = "Off",
            themeTitle = ColorTheme.Light.title,
            serverSubtitle = SettingsCopy.serverSubtitle(local),
            username = "niklas_v",
        )
        render(root(state), "08-settings-self-hosted-off")
    }

    // ---- Appearance ----

    @Test
    fun appearanceLight() {
        val ui = host { AppearanceContent(ColorTheme.System, {}, BrandLogoStyle.Detailed, {}, failure = null, deviceNoun = DeviceNoun.PHONE, onBack = {}) }
        render(ui, "09-appearance-oEQcT")
    }

    @Test
    fun appearanceDarkSimple() {
        val ui = host(dark = true) { AppearanceContent(ColorTheme.Dark, {}, BrandLogoStyle.Simple, {}, failure = null, deviceNoun = DeviceNoun.PHONE, onBack = {}) }
        render(ui, "10-appearance-dark-simple-PQ996")
    }

    @Test
    fun appearanceFailure() {
        val ui = host { AppearanceContent(ColorTheme.Light, {}, BrandLogoStyle.Detailed, {}, failure = AppearanceCopy.LOGO_FAILURE, deviceNoun = DeviceNoun.PHONE, onBack = {}) }
        render(ui, "11-appearance-icon-failure")
    }

    // ---- Transcription ----

    private fun downloading(fraction: Double, determinate: Boolean) =
        TranscriptionInstallState(TranscriptionInstallState.Phase.Downloading, fraction, determinate, languageName = null, messageId = null)

    @Test
    fun transcriptionAutomatic() {
        val ui = host { TranscriptionContent(TranscriptionInstallState.Idle, whisperLanguages, selection = null, onChoose = {}, onBack = {}) }
        render(ui, "12-transcription-uG2Sv")
        assertEquals("English", TranscriptionPicker.displayName(whisperLanguages.first(), Locale.ENGLISH))
    }

    @Test
    fun transcriptionDownloadingDeterminate() {
        val ui = host { TranscriptionContent(downloading(0.42, true), whisperLanguages, selection = Locale.forLanguageTag("de-DE"), onChoose = {}, onBack = {}) }
        assertTrue(ui.nodesWithText("Downloading Whisper… 42%").isNotEmpty())
        render(ui, "13-transcription-downloading-42")
    }

    @Test
    fun transcriptionDownloadingStarting() {
        // Determinate at 0 %: no percentage yet, and the bar shows a 2 % sliver.
        val ui = host { TranscriptionContent(downloading(0.0, true), whisperLanguages, selection = null, onChoose = {}, onBack = {}) }
        assertTrue(ui.nodesWithText("Downloading Whisper…").isNotEmpty())
        render(ui, "14-transcription-downloading-0")
    }

    @Test
    fun transcriptionDownloadingIndeterminate() {
        val ui = host { TranscriptionContent(downloading(0.0, false), whisperLanguages, selection = null, onChoose = {}, onBack = {}) }
        render(ui, "15-transcription-downloading-indeterminate")
    }

    @Test
    fun transcriptionDark() {
        val ui = host(dark = true) { TranscriptionContent(TranscriptionInstallState.Idle, whisperLanguages, selection = Locale.forLanguageTag("fr"), onChoose = {}, onBack = {}) }
        render(ui, "16-transcription-dark")
    }

    // ---- Server ----

    @Test
    fun serverOfficial() {
        val ui = host { ServerSettingsPage(ServerConfiguration.official, ServerConfiguration.official, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        render(ui, "17-server-official")
    }

    @Test
    fun serverSelfHosted() {
        val ui = host { ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        render(ui, "18-server-self-hosted-N6l9Rb")
    }

    @Test
    fun serverError() {
        val blank = ServerConfiguration(ServerConnectionMode.SelfHosted, "", "8080", "/api/v1", false)
        val ui = host { ServerSettingsPage(blank, ServerConfiguration.official, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        ui.clickLabel("Save")
        assertTrue(ui.describe(), ui.nodesWithText("Enter a host or IP address.").isNotEmpty())
        render(ui, "19-server-error")
    }

    @Test
    fun serverChangeSheet() {
        val ui = host { ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        ui.clickLabel("Official Shroud server")
        ui.clickLabel("Save")
        assertTrue(ui.describe(), ui.nodesWithText("Change server?").isNotEmpty())
        render(ui, "20-server-change-sheet")
    }

    @Test
    fun serverSavingAndSaved() {
        val beats = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var beat = 0
        val ui = host {
            ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}, pause = { beats[beat++].await() })
        }
        ui.clickLabel("Save")
        assertTrue(ui.describe(), ui.nodesWithText("Saving…").isNotEmpty())
        render(ui, "21-server-saving")
        beats[0].complete(Unit)
        ui.settle()
        assertTrue(ui.describe(), ui.nodesWithText("Saved").isNotEmpty())
        render(ui, "22-server-saved")
        beats[1].complete(Unit)
    }

    @Test
    fun serverDark() {
        val ui = host(dark = true) { ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        render(ui, "23-server-dark")
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-xhdpi")
    fun server360() {
        val ui = host { ServerSettingsPage(local, local, signedIn = true, save = {}, onSignOut = {}, onBack = {}) }
        render(ui, "24-server-360")
    }

    // ---- the logo ----

    @Test
    fun brandMarks() {
        val ui = host {
            Column(
                Modifier.fillMaxSize().background(ShroudTheme.colors.backgroundGrouped).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                for (style in BrandLogoStyle.entries) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (size in listOf(100, 80, 64, 48, 44)) BrandLogoMark(size.dp, style = style)
                    }
                }
            }
        }
        render(ui, "25-brand-marks-detailed-simple")
    }

    @Test
    fun launcherIcons() {
        // The two adaptive icons as the launcher masks them (the platform's default mask), side by side.
        val context = hosts.firstOrNull()?.activity ?: host { }.activity
        val size = 432
        val bitmap = Bitmap.createBitmap(size * 2 + 48, size + 32, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        for ((index, id) in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_simple).withIndex()) {
            val icon = context.getDrawable(id) as AdaptiveIconDrawable
            val left = 16 + index * (size + 16)
            icon.setBounds(left, 16, left + size, 16 + size)
            icon.draw(canvas)
        }
        save(bitmap, "26-launcher-icons-detailed-simple")
    }

    private companion object {
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"
        val STATUS_BAR = 52.dp
        val NAV_BAR = 24.dp
    }
}
