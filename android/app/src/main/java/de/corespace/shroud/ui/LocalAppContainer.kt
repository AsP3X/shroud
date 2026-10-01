package de.corespace.shroud.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import de.corespace.shroud.AppContainer

/**
 * The process's [AppContainer] for composables (00-plan §1.7.13). Provided once at the root of
 * every activity's content (`MainActivity`, later `CallActivity`); it never changes while the
 * process lives, hence a static local. Composables read what they need through it instead of
 * having the container threaded through every signature.
 */
val LocalAppContainer: ProvidableCompositionLocal<AppContainer> = staticCompositionLocalOf {
    error("LocalAppContainer is not provided: wrap the activity's content in CompositionLocalProvider(LocalAppContainer provides container)")
}
