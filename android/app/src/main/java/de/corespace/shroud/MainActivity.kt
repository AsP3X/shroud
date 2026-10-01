package de.corespace.shroud

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.ShroudApp
import de.corespace.shroud.ui.theme.ShroudTheme

/**
 * The app's one activity, iOS `WindowGroup { RootView() }` (`ShroudApp.swift:8-12`). Launched
 * through the `.LauncherDetailed` / `.LauncherSimple` aliases (settings-lock §8.4) and by App
 * Links for `https://shroud.corespace.de/u/<code>` (contacts §5.10). `singleTask`: links and
 * notification taps arrive in [onNewIntent] while the app runs; the shell reads [getIntent]
 * (W2/W3 wire the handling). The manifest's `configChanges` keep rotation, folding and dark-mode
 * switches from recreating it, as SwiftUI never recreates `RootView`.
 *
 * Controllers live in the process-wide [AppContainer], never in the activity; the container is
 * provided to the composition through [LocalAppContainer].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ShroudApplication).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                ShroudTheme {
                    ShroudApp(container)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: the newest link or notification tap is the one the shell handles.
        setIntent(intent)
    }
}
