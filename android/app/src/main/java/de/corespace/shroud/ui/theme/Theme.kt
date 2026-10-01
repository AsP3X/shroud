package de.corespace.shroud.ui.theme

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * Nest `ShroudTheme(dark = true)` around surfaces that are dark in both appearances.
 */
@Composable
fun ShroudTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    val reduceMotion = rememberReduceMotion()
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

/**
 * Android's Reduce Motion: "Remove animations" sets the animator duration scale to 0. Read live,
 * like SwiftUI's `accessibilityReduceMotion`: a content observer on the setting recomposes the
 * theme when it changes while the app runs (design-inventory addendum Theme, shell-chats §15.3).
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember(resolver) { mutableStateOf(readReduceMotion(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduce = readReduceMotion(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        // A change between the first read and the registration is not missed.
        reduce = readReduceMotion(resolver)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduce
}

private fun readReduceMotion(resolver: ContentResolver): Boolean =
    isReduceMotion(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f))

/** "Remove animations" (or the developer option at "Animation off") sets the scale to exactly 0. */
fun isReduceMotion(animatorDurationScale: Float): Boolean = animatorDurationScale == 0f
