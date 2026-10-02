package de.corespace.shroud.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import de.corespace.shroud.R
import de.corespace.shroud.core.appearance.BrandLogoPreference
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.PackageManagerLauncherAliases
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/**
 * The launcher icon switch on the merged manifest and the real package manager (settings-lock
 * §8.4, §18.3 `BrandLogoPreferenceTest`): exactly one of `.LauncherDetailed` / `.LauncherSimple`
 * is a launcher entry, the switch enables the new alias before it disables the old one, and the two
 * adaptive icons draw the two looks of `design/icon/shroud-icon(-simple).svg`.
 *
 * The device half — that the Pixel launcher actually swaps the home-screen icon — is
 * `LauncherIconDeviceTest` (androidTest); a Samsung launcher is checked by hand (S12).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager = context.packageManager

    private fun alias(style: BrandLogoStyle) = ComponentName(context.packageName, PackageManagerLauncherAliases.aliasName(style))

    private fun launcherEntries(): List<String> =
        packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
            0,
        ).map { it.activityInfo.name }

    @Test
    fun theDetailedAliasIsTheOnlyLauncherEntryAtFirst() {
        assertEquals(listOf(PackageManagerLauncherAliases.aliasName(BrandLogoStyle.Detailed)), launcherEntries())
        assertEquals(BrandLogoStyle.Detailed, BrandLogoPreference(context).style.value)
    }

    @Test
    fun choosingSimpleSwapsTheLauncherEntryAndBack() = runTest {
        val preference = BrandLogoPreference(PackageManagerLauncherAliases(context), UnconfinedTestDispatcher(testScheduler))
        preference.choose(BrandLogoStyle.Simple)
        assertEquals(BrandLogoStyle.Simple, preference.style.value)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, packageManager.getComponentEnabledSetting(alias(BrandLogoStyle.Simple)))
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packageManager.getComponentEnabledSetting(alias(BrandLogoStyle.Detailed)))
        assertEquals(listOf(PackageManagerLauncherAliases.aliasName(BrandLogoStyle.Simple)), launcherEntries())
        // A new process reads the launcher's state, not a stored flag (`BrandLogoMark.swift:33-47`).
        assertEquals(BrandLogoStyle.Simple, BrandLogoPreference(context).style.value)

        preference.choose(BrandLogoStyle.Detailed)
        assertEquals(listOf(PackageManagerLauncherAliases.aliasName(BrandLogoStyle.Detailed)), launcherEntries())
        assertEquals(BrandLogoStyle.Detailed, BrandLogoPreference(context).style.value)
    }

    /**
     * Change request CR-1 (W3-INT, manifest owner): `.LauncherSimple` still names
     * `@mipmap/ic_launcher`. Remove the `@Ignore` once it names `@mipmap/ic_launcher_simple`.
     */
    @Ignore("CR-1: the manifest's .LauncherSimple alias must name @mipmap/ic_launcher_simple (W3-INT)")
    @Test
    fun eachAliasCarriesItsOwnIcon() {
        val detailed = packageManager.getActivityInfo(alias(BrandLogoStyle.Detailed), PackageManager.MATCH_DISABLED_COMPONENTS)
        val simple = packageManager.getActivityInfo(alias(BrandLogoStyle.Simple), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals(R.mipmap.ic_launcher, detailed.icon)
        assertEquals(R.mipmap.ic_launcher_simple, simple.icon)
    }

    @Test
    fun theSimpleIconIsTheFlatVeilWithoutTheGlow() {
        val detailed = render(R.mipmap.ic_launcher)
        val simple = render(R.mipmap.ic_launcher_simple)
        save(detailed, "ic_launcher.png")
        save(simple, "ic_launcher_simple.png")
        val size = detailed.width
        // Icon space u (0..1024 of the visible square) → canvas pixel: (170.67 + u · 2/3) / 1024 · size.
        fun px(u: Float) = ((170.67f + u * 2f / 3f) / 1024f * size).toInt()

        // The glow (white 18 % at (512, 307)) lifts the detailed backdrop above the veil.
        val glowDetailed = detailed.getPixel(px(512f), px(150f))
        val glowSimple = simple.getPixel(px(512f), px(150f))
        assertTrue("glow ${hex(glowDetailed)} vs ${hex(glowSimple)}", luminance(glowDetailed) > luminance(glowSimple) + 8)

        // The simple veil is flat white; the detailed cloth shades to #E4E3FF at the bottom and under the folds.
        assertEquals(Color.WHITE, simple.getPixel(px(512f), px(500f)) or 0xFF000000.toInt())
        val clothLow = detailed.getPixel(px(330f), px(745f))
        assertTrue("cloth ${hex(clothLow)}", Color.blue(clothLow) > Color.red(clothLow))

        // The detailed veil drops a shadow below its hem; the simple one does not.
        val belowHem = px(810f) to px(330f)
        val shadowed = detailed.getPixel(belowHem.second, belowHem.first)
        val plain = simple.getPixel(belowHem.second, belowHem.first)
        assertTrue("shadow ${hex(shadowed)} vs ${hex(plain)}", luminance(shadowed) + 6 < luminance(plain))

        // Same gradient underneath where nothing else draws (bottom-right corner of the visible square).
        val corner = px(1000f)
        val a = detailed.getPixel(corner, corner)
        val b = simple.getPixel(corner, corner)
        assertTrue("corner ${hex(a)} vs ${hex(b)}", abs(luminance(a) - luminance(b)) <= 3)
        assertNotEquals(detailed.getPixel(px(512f), px(500f)), Color.TRANSPARENT)
    }

    private fun render(id: Int, size: Int = 432): Bitmap {
        val drawable: Drawable = context.getDrawable(id)!!
        assertTrue("$id is an adaptive icon", drawable is AdaptiveIconDrawable)
        val icon = drawable as AdaptiveIconDrawable
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The full 108 dp layers, unmasked: background, then foreground.
        for (layer in listOf(icon.background, icon.foreground)) {
            layer.setBounds(0, 0, size, size)
            layer.draw(canvas)
        }
        return bitmap
    }

    /** Leaves the renders in build/ for a look by eye (not an assertion). */
    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("build/reports/launcher-icons").apply { mkdirs() }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun luminance(color: Int): Int = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

    private fun hex(color: Int) = "#%08X".format(color)
}
