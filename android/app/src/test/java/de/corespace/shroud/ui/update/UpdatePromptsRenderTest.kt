package de.corespace.shroud.ui.update

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.core.update.UpdateCheckOutcome
import de.corespace.shroud.core.update.UpdatePrompt
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.onboarding.WelcomeScreen
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
 * Renders the update prompts to PNGs under `android/app/build/outputs/update-screens/` for the
 * design pass: the offer over Welcome (with and without a link), and "Update required" (with a
 * link, without one, checking), light and dark. Same frame as the Settings renders: 412 × 915 dp at
 * 2×, a 52 dp status bar and a 24 dp gesture bar, motion reduced.
 *
 * Only when asked: `SHROUD_RENDER_SCREENS=1 gw :app:testDebugUnitTest --tests '*UpdatePromptsRenderTest'`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdatePromptsRenderTest {
    private val hosts = ArrayList<ComposeHarness>()

    @Before
    fun onlyWhenAsked() {
        assumeTrue("set $RENDER_ENV=1 to render the update prompts", System.getenv(RENDER_ENV) == "1")
    }

    @After
    fun tearDown() {
        hosts.forEach { host ->
            host.activity.findViewById<ViewGroup>(android.R.id.content).removeAllViews()
            host.idle()
        }
    }

    private val official = ServerConfiguration.localDevelopment("10.0.2.2", 8080).copy(mode = ServerConnectionMode.Official)
    private val link = "https://shroud.corespace.de/download"

    @Test
    fun renderTheOffer() {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            render(dark, "available-$suffix") { Offer(UpdatePrompt.Available("0.1.0", "0.2.0", link)) }
            render(dark, "available-no-link-$suffix") { Offer(UpdatePrompt.Available("0.1.0", "0.2.0", null)) }
        }
    }

    @Test
    fun renderTheBlock() {
        for (dark in listOf(false, true)) {
            val suffix = if (dark) "dark" else "light"
            render(dark, "required-$suffix") { Block(UpdatePrompt.Required("0.1.0", "0.2.0", link)) }
            render(dark, "required-no-link-$suffix") { Block(UpdatePrompt.Required("0.1.0", "0.2.0", null)) }
            render(dark, "required-checking-$suffix") { Block(UpdatePrompt.Required("0.1.0", "0.2.0", link), checking = true) }
            render(dark, "required-no-link-checking-$suffix") { Block(UpdatePrompt.Required("0.1.0", null, null), checking = true) }
        }
    }

    /** The offer where it shows most often: over Welcome. */
    @Composable
    private fun Offer(prompt: UpdatePrompt.Available) {
        WelcomeScreen(server = official, onStartMessaging = {}, onLogIn = {}, onOpenServerSettings = {})
        UpdatePromptLayer(prompt, checking = false, offersDialog = true, onUpdate = { true }, onLater = {}, onCheckAgain = { UpdateCheckOutcome.Skipped })
    }

    @Composable
    private fun Block(prompt: UpdatePrompt.Required, checking: Boolean = false) {
        WelcomeScreen(server = official, onStartMessaging = {}, onLogIn = {}, onOpenServerSettings = {})
        UpdatePromptLayer(prompt, checking = checking, offersDialog = true, onUpdate = { true }, onLater = {}, onCheckAgain = { UpdateCheckOutcome.Skipped })
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
        val dir = File("build/outputs/update-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val RENDER_ENV = "SHROUD_RENDER_SCREENS"
        const val STATUS_BAR = 52
        const val NAV_BAR = 24
    }
}
