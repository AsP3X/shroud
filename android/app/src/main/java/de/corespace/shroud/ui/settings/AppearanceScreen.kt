package de.corespace.shroud.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.appearance.BrandLogoStyle
import de.corespace.shroud.core.appearance.ColorTheme
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.IconTile
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SectionFooter
import de.corespace.shroud.ui.components.SectionHeader
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.TilePalette
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Settings › Appearance (iOS `AppearanceSettingsView`, `ios/shroud/Features/Main/AppearanceSettingsView.swift:11-200`;
 * settings-lock §8.1; design `Appearance` `oEQcT`, `· Dark` `PQ996`): the colour theme (System,
 * Light, Dark) and the logo (Detailed, Simple).
 *
 * The theme is kept on this phone only (`ColorThemePreference`, prefs `shroud.appearance`); the
 * root applies it (shell §3.12), cross-fading the window. The logo switches the launcher icon
 * through the `activity-alias` pair (`BrandLogoPreference`, settings-lock §8.4) — no system alert,
 * unlike iOS — and every in-app [BrandLogoMark] follows it. A refused switch shows "The app icon
 * couldn’t be changed. Try again." (`:168-179`).
 *
 * The switch runs in the app scope, not the screen's: once the package manager has flipped the
 * aliases, the preference must record it even if the user has already left the screen.
 */
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val themes = container.auth.colorTheme
    val logos = container.auth.brandLogo
    val theme by themes.theme.collectAsState()
    val style by logos.style.collectAsState()
    var failure by remember { mutableStateOf<String?>(null) }
    val noun = DeviceNoun.current(LocalContext.current)
    AppearanceContent(
        theme = theme,
        onTheme = { themes.choose(it) },
        logo = style,
        onLogo = { chosen ->
            // `choose(_:)` (`AppearanceSettingsView.swift:168-179`): same style or a switch running → nothing.
            if (chosen != logos.style.value && !logos.isChanging.value) {
                failure = null
                container.appScope.launch {
                    try {
                        logos.choose(chosen)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failure = AppearanceCopy.LOGO_FAILURE
                    }
                }
            }
        },
        failure = failure,
        deviceNoun = noun,
        onBack = onBack,
    )
}

/** Copy of Settings › Appearance (settings-lock §8.1). */
object AppearanceCopy {
    const val TITLE = "Appearance"
    const val LOGO_FOOTER = "The logo on your home screen and inside the app."
    const val LOGO_FAILURE = "The app icon couldn’t be changed. Try again."

    /** [A] the theme footer with this device's noun (iOS "iPhone"/"iPad", `AppearanceSettingsView.swift:24`). */
    fun themeFooter(deviceNoun: String): String =
        "System follows your $deviceNoun’s light or dark setting. The choice applies to this $deviceNoun only."

    /** A theme row's glyph and tile (`ColorTheme.systemImage` / `iconBackground`; design `oEQcT`). */
    fun themeIcon(theme: ColorTheme): ImageVector = when (theme) {
        ColorTheme.System -> ShroudIcons.CircleHalfFill
        ColorTheme.Light -> ShroudIcons.SunFill
        ColorTheme.Dark -> ShroudIcons.MoonFill
    }

    @Composable
    fun themeTint(theme: ColorTheme): Color = when (theme) {
        ColorTheme.System -> ShroudTheme.colors.textSecondary
        ColorTheme.Light -> TilePalette.amber
        ColorTheme.Dark -> TilePalette.indigo
    }
}

/**
 * The Appearance screen's drawing: `PushedScreen("Appearance")`, a 14-spaced column (padding h 16,
 * top 8, 24 at the end) with the THEME and LOGO cards, their footers and the [failure] line
 * (`AppearanceSettingsView.swift:16-49`).
 */
@Composable
fun AppearanceContent(
    theme: ColorTheme,
    onTheme: (ColorTheme) -> Unit,
    logo: BrandLogoStyle,
    onLogo: (BrandLogoStyle) -> Unit,
    failure: String?,
    deviceNoun: String,
    onBack: () -> Unit,
) {
    val colors = ShroudTheme.colors
    PushedScreen(AppearanceCopy.TITLE, onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader("Theme")
                SettingsCard {
                    ColorTheme.entries.forEachIndexed { index, item ->
                        if (index > 0) InsetDivider(56.dp)
                        ThemeRow(item, selected = item == theme) { onTheme(item) }
                    }
                }
            }
            SectionFooter(AppearanceCopy.themeFooter(deviceNoun), pullUp = 6.dp)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionHeader("Logo")
                SettingsCard {
                    BrandLogoStyle.entries.forEachIndexed { index, item ->
                        if (index > 0) InsetDivider(70.dp)
                        LogoRow(item, selected = item == logo) { onLogo(item) }
                    }
                }
            }
            SectionFooter(AppearanceCopy.LOGO_FOOTER, pullUp = 6.dp)

            if (failure != null) {
                ShroudText(failure, inter(13f), colors.danger, Modifier.padding(horizontal = 14.dp))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A theme row (`AppearanceSettingsView.swift:70-105`): tile, title, the check on the selected one.
 * The row press already ticks (no extra haptic); TalkBack hears the title and "selected".
 */
@Composable
private fun ThemeRow(theme: ColorTheme, selected: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .highlightRow(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(AppearanceCopy.themeIcon(theme), AppearanceCopy.themeTint(theme))
        ShroudText(theme.title, inter(16f), colors.textPrimary, Modifier.weight(1f))
        SelectionCheck(selected)
    }
}

/**
 * A logo row (`AppearanceSettingsView.swift:126-159`): the 44 dp mark in that style, title and
 * description, the check. TalkBack reads the title, then the description that tells the two apart
 * (iOS's hint).
 */
@Composable
private fun LogoRow(style: BrandLogoStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = ShroudTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .highlightRow(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandLogoMark(44.dp, style = style)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(style.title, inter(16f), colors.textPrimary)
            ShroudText(style.subtitle, inter(13f), colors.textSecondary)
        }
        SelectionCheck(selected)
    }
}

/**
 * The selected row's check: Phosphor `check-bold` 14 dp in `accent`, swapping in and out with
 * `Motion.iconSwap` on `Motion.snappy` (iOS `.animation(Motion.snappy, value:)` around
 * `.transition(Motion.iconSwap)`); a fade under Reduce Motion. Decorative: the row says "selected".
 */
@Composable
internal fun RowScope.SelectionCheck(selected: Boolean) {
    val transition = Motion.iconSwap.respecting(ShroudTheme.reduceMotion)
    AnimatedVisibility(selected, enter = transition.enter, exit = transition.exit) {
        ShroudIcon(ShroudIcons.CheckBold, ShroudTheme.colors.accent, size = 14.dp)
    }
}

@Preview(name = "Appearance · 412", widthDp = 412, heightDp = 700)
@Composable
private fun AppearancePreview() {
    ShroudTheme(dark = false) {
        AppearanceContent(ColorTheme.System, {}, BrandLogoStyle.Detailed, {}, failure = null, deviceNoun = DeviceNoun.PHONE, onBack = {})
    }
}

@Preview(name = "Appearance · 360 · dark · failure", widthDp = 360, heightDp = 700)
@Composable
private fun AppearanceDarkPreview() {
    ShroudTheme(dark = true) {
        AppearanceContent(ColorTheme.Dark, {}, BrandLogoStyle.Simple, {}, failure = AppearanceCopy.LOGO_FAILURE, deviceNoun = DeviceNoun.PHONE, onBack = {})
    }
}
