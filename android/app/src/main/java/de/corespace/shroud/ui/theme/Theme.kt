package de.corespace.shroud.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

val LocalShroudColors = staticCompositionLocalOf { LightColors }

/** True when the user turned animations off (Settings → Accessibility → Remove animations). */
val LocalReduceMotion = staticCompositionLocalOf { false }

object ShroudTheme {
    val colors: ShroudColors
        @Composable @ReadOnlyComposable
        get() = LocalShroudColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReduceMotion.current
}

/**
 * The app theme. No Material theme underneath: every surface draws its own chrome from these
 * tokens, as the design and `docs/android-plan.md` ("Same app, not a Material app") ask.
 */
@Composable
fun ShroudTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val selection = remember(colors) {
        TextSelectionColors(handleColor = colors.accent, backgroundColor = colors.accent.copy(alpha = 0.3f))
    }
    CompositionLocalProvider(
        LocalShroudColors provides colors,
        LocalReduceMotion provides reduceMotion,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}
