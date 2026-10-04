package de.corespace.shroud.ui.settings.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.about.LicensedComponent
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsMetrics
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.CancellationException

/** Copy of the Open-Source Licenses list and detail (iOS `OpenSourceLicensesView.swift`). */
object LicensesCopy {
    const val TITLE = "Open-Source Licenses"
    const val INTRO = "Shroud is built with these open-source components. Tap one to read its license."
    const val LOAD_FAILED = "Couldn’t load the licenses."
    const val DETAIL_FALLBACK_TITLE = "License"

    /** "2.0.1 · Apache-2.0"; a pooled entry counts its modules: "163 modules · Apache-2.0". */
    fun summary(component: LicensedComponent): String = "${versionText(component)} · ${component.license}"

    /** TalkBack for a list row: "Haze, version 2.0.1, Apache-2.0" or "AndroidX libraries, 163 modules, Apache-2.0". */
    fun spoken(component: LicensedComponent): String {
        val version = component.version?.let { "version $it" } ?: modules(component)
        return "${component.name}, $version, ${component.license}"
    }

    /** What the detail lists under the summary: the Maven modules, or the files it ships as. */
    fun artifactsHeading(component: LicensedComponent): String = if (component.kind == "Library") "Modules" else "Ships as"

    private fun versionText(component: LicensedComponent): String = component.version ?: modules(component)

    private fun modules(component: LicensedComponent): String =
        if (component.artifacts.size == 1) "1 module" else "${component.artifacts.size} modules"
}

/** The list's load state: [components] null while loading (nothing is drawn); [failed] when the asset was unreadable. */
@Immutable
data class LicensesState(val components: List<LicensedComponent>?, val failed: Boolean = false)

/**
 * Settings › About Shroud › Open-Source Licenses: every third-party component of the app, A–Z
 * (`assets/licenses/third_party.json`, written by `scripts/generate_licenses.py`). A row opens that
 * component's license ([onOpen] with its id).
 */
@Composable
fun LicensesScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val licenses = LocalAppContainer.current.update.licenses
    val state by produceState(LicensesState(null), licenses) {
        value = try {
            LicensesState(licenses.components())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LicensesState(emptyList(), failed = true)
        }
    }
    LicensesContent(state, onBack, onOpen)
}

/**
 * The list's drawing: `PushedScreen("Open-Source Licenses")`, a 14-spaced column (padding h 16, top
 * 8, 24 at the end): the intro 14 `textSecondary` (padding h 14, top 4), then one card of rows —
 * name 16 `textPrimary`, "version · license" 13 `textSecondary`, the chevron; dividers inset 14.
 */
@Composable
fun LicensesContent(state: LicensesState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val colors = ShroudTheme.colors
    PushedScreen(LicensesCopy.TITLE, onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ShroudText(
                LicensesCopy.INTRO,
                inter(14f),
                colors.textSecondary,
                Modifier
                    .fillMaxWidth()
                    .padding(start = SettingsMetrics.textInset, end = SettingsMetrics.textInset, top = 4.dp),
            )
            // Nothing while the list loads: it is read from the APK in a few milliseconds.
            val components = state.components
            if (components != null) SettingsCard {
                when {
                    state.failed -> ShroudText(
                        LicensesCopy.LOAD_FAILED,
                        inter(15f),
                        colors.textSecondary,
                        Modifier.fillMaxWidth().padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
                    )
                    else -> components.forEachIndexed { index, component ->
                        if (index > 0) InsetDivider(SettingsMetrics.textInset)
                        LicenseRow(component) { onOpen(component.id) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One component: name and summary, the chevron; padding h 14, v 10; one TalkBack stop. */
@Composable
private fun LicenseRow(component: LicensedComponent, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .highlightRow(onClick = onClick)
            .clearAndSetSemantics { contentDescription = LicensesCopy.spoken(component) }
            .padding(horizontal = SettingsMetrics.textInset, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(component.name, inter(16f), colors.textPrimary)
            ShroudText(LicensesCopy.summary(component), inter(13f), colors.textSecondary)
        }
        ShroudIcon(ShroudIcons.CaretRight, colors.chevron, size = 13.dp)
    }
}

/** The detail's load state: [component] and [text] null while loading (nothing is drawn); [failed] when unreadable. */
@Immutable
data class LicenseDetailState(val component: LicensedComponent?, val text: String?, val failed: Boolean = false)

/** One component's license ([id] in `third_party.json`), selectable so it can be copied. */
@Composable
fun LicenseDetailScreen(id: String, onBack: () -> Unit) {
    val licenses = LocalAppContainer.current.update.licenses
    val state by produceState(LicenseDetailState(null, null), licenses, id) {
        value = try {
            val component = licenses.component(id)
            if (component == null) LicenseDetailState(null, null, failed = true) else LicenseDetailState(component, licenses.text(component))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            LicenseDetailState(null, null, failed = true)
        }
    }
    LicenseDetailContent(state, onBack)
}

/**
 * The detail's drawing: `PushedScreen(<name>)`, a 14-spaced column (padding h 16, top 8, 24 at the
 * end). A summary card (padding 14): the name 17 SemiBold (a heading), "version · license" 13
 * `textSecondary`, then "Modules" / "Ships as" 13 Medium `textSecondary` over the artifacts 12 mono
 * `textSecondary`. Then the license text 12 mono `textPrimary` in its own card, padding 14.
 */
@Composable
fun LicenseDetailContent(state: LicenseDetailState, onBack: () -> Unit) {
    val colors = ShroudTheme.colors
    val component = state.component
    PushedScreen(component?.name ?: LicensesCopy.DETAIL_FALLBACK_TITLE, onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when {
                state.failed -> SettingsCard {
                    ShroudText(
                        LicensesCopy.LOAD_FAILED,
                        inter(15f),
                        colors.textSecondary,
                        Modifier.fillMaxWidth().padding(horizontal = SettingsMetrics.textInset, vertical = 12.dp),
                    )
                }
                component == null -> Unit
                else -> {
                    SummaryCard(component)
                    val text = state.text
                    if (text != null) {
                        SettingsCard {
                            SelectionContainer {
                                ShroudText(text, inter(12f, monospaced = true), colors.textPrimary, Modifier.fillMaxWidth().padding(14.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SummaryCard(component: LicensedComponent) {
    val colors = ShroudTheme.colors
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ShroudText(component.name, inter(17f, FontWeight.SemiBold), colors.textPrimary, Modifier.semantics { heading() })
            ShroudText(LicensesCopy.summary(component), inter(13f), colors.textSecondary)
            Spacer(Modifier.height(6.dp))
            ShroudText(LicensesCopy.artifactsHeading(component), inter(13f, FontWeight.Medium), colors.textSecondary)
            SelectionContainer {
                ShroudText(component.artifacts.joinToString("\n"), inter(12f, monospaced = true, lineSpacing = 2f), colors.textSecondary)
            }
        }
    }
}

private val previewComponents = listOf(
    LicensedComponent("androidx-apache-2.0", "AndroidX libraries", null, "Apache-2.0", "Apache-2.0.txt", "Library", List(163) { "androidx.core:core:1.19.1" }),
    LicensedComponent("haze", "Haze", "2.0.1", "Apache-2.0", "Haze-LICENSE.txt", "Library", listOf("dev.chrisbanes.haze:haze:2.0.1")),
    LicensedComponent("inter", "Inter", "4.1", "OFL-1.1", "Inter-OFL.txt", "Font", listOf("res/font/inter_*.ttf")),
)

@Preview(name = "Licenses · 412", widthDp = 412, heightDp = 700)
@Composable
private fun LicensesPreview() {
    ShroudTheme(dark = false) { LicensesContent(LicensesState(previewComponents), onBack = {}, onOpen = {}) }
}

@Preview(name = "License · 360 · dark", widthDp = 360, heightDp = 700)
@Composable
private fun LicenseDetailPreview() {
    ShroudTheme(dark = true) {
        LicenseDetailContent(LicenseDetailState(previewComponents[1], "Apache License\nVersion 2.0, January 2004"), onBack = {})
    }
}
