package de.corespace.shroud.ui.settings.about

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import de.corespace.shroud.core.about.LicensedComponent
import de.corespace.shroud.core.about.OpenSourceLicenses
import de.corespace.shroud.ui.components.ComposeHarness
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The bundled license list (`assets/licenses/third_party.json`, `scripts/generate_licenses.py`) and
 * its two screens: every entry has a text, the libraries the app declares are listed, and the list
 * and detail read as TalkBack should hear them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenTest {
    /** The module's own assets, read the way the app reads them. */
    private val licenses = OpenSourceLicenses(open = { path -> File("src/main/assets", path).inputStream() })

    private fun load(): List<LicensedComponent> {
        var result: List<LicensedComponent> = emptyList()
        runTest(UnconfinedTestDispatcher()) { result = licenses.components() }
        return result
    }

    @Test
    fun theCatalogCoversWhatTheApkShips() = runTest(UnconfinedTestDispatcher()) {
        val components = licenses.components()
        assertEquals("ids are unique", components.size, components.map { it.id }.toSet().size)
        assertEquals("sorted by name", components.map { it.name.lowercase() }.sorted(), components.map { it.name.lowercase() })
        for (component in components) {
            assertTrue("${component.name} has a license", component.license.isNotBlank())
            assertTrue("${component.name} lists what it ships as", component.artifacts.isNotEmpty())
            assertTrue("${component.name}: ${component.kind}", component.kind in OpenSourceLicenses.KIND_ORDER)
            assertTrue("${component.name}'s text is not empty", licenses.text(component).isNotBlank())
        }
        val modules = components.filter { it.kind == "Library" }.flatMap { it.artifacts }.map { it.substringBeforeLast(':') }.toSet()
        // A sample of what app/build.gradle.kts declares, and what they pull in.
        for (module in listOf(
            "com.squareup.okhttp3:okhttp", "org.bouncycastle:bcprov-jdk18on", "io.github.webrtc-sdk:android",
            "com.google.zxing:core", "dev.chrisbanes.haze:haze", "androidx.compose.foundation:foundation",
            "org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains.kotlinx:kotlinx-serialization-json",
            "org.unifiedpush.android:embedded-fcm-distributor", "androidx.media3:media3-exoplayer", "com.google.guava:guava",
        )) {
            assertTrue("$module is listed", module in modules)
        }
        // What Gradle does not see.
        val names = components.map { it.name }
        for (name in listOf("whisper.cpp", "ggml", "WebRTC", "BoringSSL", "LLVM libc++", "Inter", "JetBrains Mono", "Lucide icons", "Phosphor Icons", "BIP-39 English word list")) {
            assertTrue("$name is listed", name in names)
        }
        // AndroidX is pooled under one license, with every module named.
        val androidx = components.single { it.id == "androidx-apache-2.0" }
        assertEquals(null, androidx.version)
        assertTrue(androidx.artifacts.size > 100)
        assertTrue(androidx.artifacts.all { it.startsWith("androidx.") })
    }

    @Test
    fun theListReadsEachComponentAndOpensIt() {
        val components = load()
        val opened = ArrayList<String>()
        val ui = ComposeHarness { LicensesContent(LicensesState(components), onBack = {}, onOpen = { opened += it }) }
        assertTrue(ui.nodesWithText("Shroud is built with these open-source components. Tap one to read its license.").isNotEmpty())
        val haze = components.single { it.id == "haze" }
        assertEquals("Haze, version ${haze.version}, Apache-2.0", LicensesCopy.spoken(haze))
        val row = ui.node(LicensesCopy.spoken(haze))
        row.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("haze"), opened)
        val androidx = components.single { it.id == "androidx-apache-2.0" }
        assertEquals("${androidx.artifacts.size} modules · Apache-2.0", LicensesCopy.summary(androidx))
        assertTrue(ui.nodes().any { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("AndroidX libraries, ${androidx.artifacts.size} modules, Apache-2.0") })
    }

    @Test
    fun theListWaitsAndSaysWhenItCannotLoad() {
        val loading = ComposeHarness { LicensesContent(LicensesState(null), onBack = {}, onOpen = {}) }
        assertTrue(loading.nodesWithText(LicensesCopy.LOAD_FAILED).isEmpty())
        val failed = ComposeHarness { LicensesContent(LicensesState(emptyList(), failed = true), onBack = {}, onOpen = {}) }
        assertTrue(failed.nodesWithText("Couldn’t load the licenses.").isNotEmpty())
    }

    @Test
    fun theDetailShowsTheSummaryModulesAndFullText() {
        val components = load()
        val okhttp = components.single { it.name == "OkHttp" }
        var text = ""
        runTest(UnconfinedTestDispatcher()) { text = licenses.text(okhttp) }
        val ui = ComposeHarness { LicenseDetailContent(LicenseDetailState(okhttp, text), onBack = {}) }
        // The bar and the summary both name it; the summary's is a heading.
        val heading = ui.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.map { t -> t.text } == listOf("OkHttp") && SemanticsProperties.Heading in it.config }
        assertFalse(heading.config.contains(SemanticsActions.OnClick))
        assertTrue(ui.nodesWithText("${okhttp.version} · Apache-2.0").isNotEmpty())
        assertTrue(ui.nodesWithText("Modules").isNotEmpty())
        assertTrue(ui.nodesWithText("com.squareup.okhttp3:okhttp:${okhttp.version}").isNotEmpty())
        assertTrue(ui.nodesWithText("Apache License").isNotEmpty())
        assertTrue(ui.nodesWithText("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION").isNotEmpty())

        val font = components.single { it.name == "Inter" }
        val fontUi = ComposeHarness { LicenseDetailContent(LicenseDetailState(font, "OFL"), onBack = {}) }
        assertTrue(fontUi.nodesWithText("Ships as").isNotEmpty())
        assertTrue(fontUi.nodesWithText("4.1 · OFL-1.1").isNotEmpty())
    }
}
