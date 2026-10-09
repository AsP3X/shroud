package de.corespace.shroud.ui.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.ShroudApplication
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.ComposeHarness
import de.corespace.shroud.ui.components.OverlayHost
import de.corespace.shroud.ui.shell.LocalShellNavigation
import de.corespace.shroud.ui.shell.SettingsRoute
import de.corespace.shroud.ui.shell.ShellNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every Settings route opens its screen (iOS `SettingsView.navigationDestination`,
 * `SettingsView.swift:196-219`; plan §1.7.13), on the real container signed out: each pushed screen
 * shows its own title in the bar, Back pops, and Notifications pushes the Sound picker and the
 * Android-only Delivery screen through the shell's Settings stack. The B screens are C7's; this only
 * checks they are reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SettingsDestinationTest {
    private val app = ApplicationProvider.getApplicationContext<ShroudApplication>()

    /**
     * The bar title each route's screen shows; null for Saved Messages, the Notes chat (the
     * conversation area's screen). Exhaustive, so a new route fails to compile here until it is listed.
     */
    private fun title(route: SettingsRoute): String? = when (route) {
        SettingsRoute.Server -> "Server"
        SettingsRoute.Transcription -> "Transcription"
        SettingsRoute.Appearance -> "Appearance"
        SettingsRoute.Devices -> "Devices"
        SettingsRoute.Notifications -> "Notifications and Sounds"
        SettingsRoute.NotificationSound -> "Sound"
        SettingsRoute.PrivacySecurity -> "Privacy and Security"
        SettingsRoute.DeleteAccount -> "Delete Account"
        SettingsRoute.PushDelivery -> "Delivery"
        SettingsRoute.SavedMessages -> null
        SettingsRoute.About -> "About Shroud"
        SettingsRoute.Licenses -> "Open-Source Licenses"
        is SettingsRoute.License -> "Haze"
    }

    private val expected = listOf(
        SettingsRoute.Server,
        SettingsRoute.Transcription,
        SettingsRoute.Appearance,
        SettingsRoute.Devices,
        SettingsRoute.Notifications,
        SettingsRoute.NotificationSound,
        SettingsRoute.PrivacySecurity,
        SettingsRoute.DeleteAccount,
        SettingsRoute.PushDelivery,
        SettingsRoute.About,
        SettingsRoute.Licenses,
        SettingsRoute.License("haze"),
    ).map { it to title(it)!! }

    @Test
    fun a_everyRouteShowsItsScreenAndBackPops() {
        val navigation = RecordingNavigation()
        var route: SettingsRoute by mutableStateOf(SettingsRoute.Server)
        var popped = 0
        val ui = ComposeHarness {
            CompositionLocalProvider(LocalAppContainer provides app.container, LocalShellNavigation provides navigation) {
                OverlayHost { SettingsDestination(route, onBack = { popped++ }) }
            }
        }
        for ((shown, title) in expected) {
            route = shown
            ui.idle()
            // The bar's title is one node labelled with it. The license screens read their asset on
            // the IO dispatcher first, so they get a moment.
            fun bar() = ui.nodes().filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(title) }
            var waited = 0
            while (bar().isEmpty() && waited++ < 100) {
                Thread.sleep(20)
                ui.idle()
            }
            val bar = bar()
            assertTrue("$shown shows \"$title\": ${ui.describe()}", bar.isNotEmpty())
            val back = ui.nodes().single { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Back") }
            val before = popped
            back.config[SemanticsActions.OnClick].action!!.invoke()
            ui.idle()
            assertEquals("$shown: Back pops", before + 1, popped)
        }
    }

    @Test
    fun b_notificationsPushesTheSoundPickerAndDelivery() {
        val navigation = RecordingNavigation()
        val ui = ComposeHarness {
            CompositionLocalProvider(LocalAppContainer provides app.container, LocalShellNavigation provides navigation) {
                OverlayHost { SettingsDestination(SettingsRoute.Notifications, onBack = {}) }
            }
        }
        val sound = ui.nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Sound,") == true && SemanticsActions.OnClick in node.config
        } ?: error("no Sound row: ${ui.describe()}")
        sound.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(listOf<SettingsRoute>(SettingsRoute.NotificationSound), navigation.pushed)
        val delivery = ui.nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Delivery,") == true && SemanticsActions.OnClick in node.config
        } ?: error("no Delivery row: ${ui.describe()}")
        delivery.config[SemanticsActions.OnClick].action!!.invoke()
        ui.idle()
        assertEquals(listOf(SettingsRoute.NotificationSound, SettingsRoute.PushDelivery), navigation.pushed)
    }

    /** Runs last: the screens above left nothing behind that stops later compositions in this JVM. */
    @Test
    fun z_laterCompositionsStillRecompose() {
        var flag by mutableStateOf(false)
        val seen = ArrayList<Boolean>()
        val ui = ComposeHarness { seen += flag }
        flag = true
        ui.idle()
        assertEquals(listOf(false, true), seen)
    }

    private class RecordingNavigation : ShellNavigation by ShellNavigation.Detached {
        val pushed = ArrayList<SettingsRoute>()

        override fun push(route: SettingsRoute) {
            pushed += route
        }
    }
}
