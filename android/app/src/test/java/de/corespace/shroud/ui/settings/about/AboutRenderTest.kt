package de.corespace.shroud.ui.settings.about

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.core.about.AppVersion
import de.corespace.shroud.core.about.LicensedComponent
import de.corespace.shroud.core.about.OpenSourceLicenses
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.settings.SettingsCopy
import de.corespace.shroud.ui.settings.SettingsRootContent
import de.corespace.shroud.ui.settings.SettingsRootState
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Settings › About Shroud to PNGs under `android/app/build/outputs/about-screens/` for the
 * design pass: the Settings root with the About row (top and scrolled to it, with the update dot),
 * About in every update state, the licenses list and two details, light and dark. Same frame as the
 * Settings renders: 412 × 915 dp at 2×, a 52 dp status bar and a 24 dp gesture bar, motion reduced.
 *
 * Only when asked: `SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*AboutRenderTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutRenderTest {
    private val hosts = ArrayList<ComposeHarness>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render About", System.getenv(RENDER_ENV) == "1")
    }

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    private val link = "https://shroud.corespace.de/download"
    private val about = AboutState(AppVersion("0.1.0", 1), AboutUpdateStatus.Current, AboutCopy.OFFICIAL_SERVER, "0.1.0")
    private val root = SettingsRootState(
        username = "noah_vorberg",
        userId = "3F2504E0-4F89-41D3-9A0C-0305E82C3301",
        deviceCount = 3,
        notificationsSummary = "On",
        themeTitle = "System",
        serverSubtitle = SettingsCopy.OFFICIAL_SERVER_SUBTITLE,
        isLoggingOut = false,
        deviceNoun = DeviceNoun.PHONE,
        appVersion = "0.1.0",
        hasUpdate = true,
    )

    private val licenses = OpenSourceLicenses(open = { path -> File("src/main/assets", path).inputStream() })

    @Test
    fun renderTheSettingsRow() {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            render(dark, "settings-row-$suffix") {
                SettingsRootContent(root, onRoute = {}, onOpenCalls = {}, onLogOut = {}, scrollState = rememberScrollState(Int.MAX_VALUE))
            }
            render(dark, "settings-row-current-$suffix") {
                SettingsRootContent(root.copy(hasUpdate = false), onRoute = {}, onOpenCalls = {}, onLogOut = {}, scrollState = rememberScrollState(Int.MAX_VALUE))
            }
        }
    }

    @Test
    fun renderAbout() {
        val states = listOf(
            "current" to about,
            "available" to about.copy(status = AboutUpdateStatus.Available("0.2.0", link)),
            "available-no-link" to about.copy(status = AboutUpdateStatus.Available("0.2.0", null)),
            "failed" to about.copy(status = AboutUpdateStatus.Failed),
            "checking" to about.copy(status = AboutUpdateStatus.Checking),
            "required" to about.copy(status = AboutUpdateStatus.Required),
            "unchecked" to about.copy(status = AboutUpdateStatus.Unchecked, serverVersion = null),
            "self-hosted" to about.copy(serverAddress = "shroud.example.org", serverVersion = null),
        )
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            for ((name, state) in states) {
                render(dark, "about-$name-$suffix") {
                    AboutContent(state, onBack = {}, onCheckForUpdates = {}, onUpdate = {}, onSourceCode = {}, onOpenLicenses = {})
                }
            }
        }
    }

    @Test
    fun renderTheLicenses() {
        var components: List<LicensedComponent> = emptyList()
        val texts = HashMap<String, String>()
        runTest(UnconfinedTestDispatcher()) {
            components = licenses.components()
            for (id in listOf("haze", "androidx-apache-2.0")) texts[id] = licenses.text(components.single { it.id == id })
        }
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            render(dark, "licenses-$suffix") { LicensesContent(LicensesState(components), onBack = {}, onOpen = {}) }
            for (id in listOf("haze", "androidx-apache-2.0")) {
                val component = components.single { it.id == id }
                render(dark, "license-$id-$suffix") { LicenseDetailContent(LicenseDetailState(component, texts.getValue(id)), onBack = {}) }
            }
        }
    }

    private fun render(dark: Boolean, name: String, content: @Composable () -> Unit) {
        val ui = ComposeHarness(dark = dark) { OverlayHost { content() } }
        hosts += ui
        val density = ui.activity.resources.displayMetrics.density
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (STATUS_BAR * density).toInt(), 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (NAV_BAR * density).toInt()))
            .build()
        ViewCompat.dispatchApplyWindowInsets(ui.root, insets)
        repeat(3) {
            ui.idle()
            ui.root.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        }
        val root = ui.root
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/outputs/about-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"
        const val STATUS_BAR = 52
        const val NAV_BAR = 24
    }
}
