package de.corespace.shroud.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import de.corespace.shroud.R
import de.corespace.shroud.core.appearance.BrandLogoPreference
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.PackageManagerLauncherAliases
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device acceptance of the launcher icon switch (W3-SETTINGS-A; settings-lock §8.4, S12): on the
 * emulator's Pixel launcher, choosing a logo leaves exactly one Shroud entry, the chosen alias,
 * with that style's icon — the list launchers build their grids from ([LauncherApps]) — and the
 * app drawer still shows Shroud. Screenshots of the drawer go to the app's cache for the
 * acceptance record: `adb exec-out run-as de.corespace.shroud cat cache/acceptance/launcher-<style>.png`.
 *
 * Run it without the uninstall `connectedDebugAndroidTest` does at the end (it would wipe a
 * signed-in test account on a shared emulator): install both APKs with `adb install -r`, then
 * `adb shell am instrument -w -e class de.corespace.shroud.ui.settings.LauncherIconDeviceTest
 * de.corespace.shroud.test/androidx.test.runner.AndroidJUnitRunner`.
 *
 * Samsung One UI is checked by hand (no device here): some launchers drop a home-screen shortcut
 * whose alias is disabled. The icon assertions need the manifest change CR-1 (`.LauncherSimple`
 * names `@mipmap/ic_launcher_simple`).
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val preference = BrandLogoPreference(context)

    @After
    fun backToDetailed() = runBlocking {
        preference.choose(BrandLogoStyle.Detailed)
        device.pressHome()
    }

    @Test
    fun theLauncherListsOnlyTheChosenStyle() = runBlocking {
        for (style in listOf(BrandLogoStyle.Simple, BrandLogoStyle.Detailed)) {
            preference.choose(style)
            assertEquals(style, preference.style.value)
            val entries = launcherEntries()
            assertEquals(style.name, listOf(PackageManagerLauncherAliases.aliasName(style)), entries.map { it.className })
            val info = context.packageManager.getActivityInfo(entries.single(), 0)
            val expectedIcon = if (style == BrandLogoStyle.Simple) R.mipmap.ic_launcher_simple else R.mipmap.ic_launcher
            assertEquals(style.name, expectedIcon, info.iconResource)
            assertDrawerShowsShroud(style)
        }
    }

    /** The launcher-visible activities of this app, as `LauncherApps` gives them to the home screen. */
    private fun launcherEntries(): List<ComponentName> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        // The package manager applies the switch synchronously (API 33+) or within moments.
        val deadline = System.currentTimeMillis() + 5_000
        var entries = launcherApps.getActivityList(context.packageName, Process.myUserHandle()).map { it.componentName }
        while (entries.size != 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            entries = launcherApps.getActivityList(context.packageName, Process.myUserHandle()).map { it.componentName }
        }
        return entries
    }

    /**
     * Opens the all-apps drawer of the default launcher and finds Shroud in it. That there is one
     * entry, not two, is [launcherEntries]' job: the Pixel drawer may also list Shroud in its row
     * of predicted apps, so counting labels here would not tell a stale alias apart.
     */
    private fun assertDrawerShowsShroud(style: BrandLogoStyle) {
        device.pressHome()
        device.waitForIdle()
        // Swipe up from the bottom third: the Pixel launcher's all-apps gesture.
        device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5, device.displayWidth / 2, device.displayHeight / 5, 20)
        val found = device.wait(Until.hasObject(By.text("Shroud")), 5_000)
        assertTrue("Shroud missing from the drawer after switching to ${style.name}", found)
        val dir = File(context.cacheDir, "acceptance").apply { mkdirs() }
        device.takeScreenshot(File(dir, "launcher-${style.name.lowercase()}.png"))
        assertTrue(context.packageManager.getComponentEnabledSetting(ComponentName(context.packageName, PackageManagerLauncherAliases.aliasName(style))) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }
}
