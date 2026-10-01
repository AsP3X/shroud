package de.corespace.shroud.core.appearance

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The logo's two looks (`BrandLogoStyle`, `ios/shroud/ShroudUI/Components/BrandLogoMark.swift:4-28`); the
 * subtitles are the Appearance rows' (settings-lock §8.1).
 */
enum class BrandLogoStyle(val title: String, val subtitle: String) {
    /** The veil with its folds and shading — the default icon. */
    Detailed("Detailed", "The veil with its folds and shading."),

    /** One flat shape. */
    Simple("Simple", "One flat shape."),
}

/**
 * The launcher entry points, one `activity-alias` per [BrandLogoStyle] (`.LauncherDetailed`
 * enabled by default, `.LauncherSimple` disabled; 00-plan §5.3). Behind an interface for JVM tests.
 */
interface LauncherAliases {
    /** Whether [style]'s alias is enabled, the manifest default included. */
    fun isEnabled(style: BrandLogoStyle): Boolean

    /** Applies the changes in order (enable the new alias first, then disable the old one). */
    fun apply(changes: List<Pair<BrandLogoStyle, Boolean>>)
}

/**
 * Which logo the home screen shows (`BrandLogoPreference`, `BrandLogoMark.swift:30-59`;
 * settings-lock §8.4, plan H, S12). The launcher's state is the source of truth, read once at start,
 * as iOS reads `alternateIconName`; a Log Out does not reset it (the component state survives the
 * wipe, like the iOS icon).
 *
 * Switching enables the new alias **first** and only then disables the old one, so the app always
 * has a launcher entry; both without killing the app. Some launchers drop a home-screen shortcut whose
 * alias is disabled (the user re-adds it from the drawer) — to be checked on Pixel and Samsung (S12).
 */
class BrandLogoPreference(
    private val aliases: LauncherAliases,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** On the app's own aliases. */
    constructor(context: Context) : this(PackageManagerLauncherAliases(context))

    private val styleState = MutableStateFlow(
        if (runCatching { aliases.isEnabled(BrandLogoStyle.Simple) }.getOrDefault(false)) BrandLogoStyle.Simple else BrandLogoStyle.Detailed,
    )
    private val changing = MutableStateFlow(false)

    val style: StateFlow<BrandLogoStyle> = styleState.asStateFlow()

    /** A switch is running; the Appearance screen ignores taps meanwhile. */
    val isChanging: StateFlow<Boolean> = changing.asStateFlow()

    /**
     * Switches the launcher icon to [style]. Ignored when it is already [style] or a switch runs.
     * Throws when the package manager refuses; the screen then shows "The app icon couldn’t be
     * changed. Try again." (settings-lock §8.1). Main-confined.
     */
    suspend fun choose(style: BrandLogoStyle) {
        if (style == styleState.value || changing.value) return
        changing.value = true
        try {
            val old = styleState.value
            withContext(io) { aliases.apply(listOf(style to true, old to false)) }
            styleState.value = style
        } finally {
            changing.value = false
        }
    }
}

/**
 * [LauncherAliases] on the package manager: `setComponentEnabledSetting` with `DONT_KILL_APP`; on
 * API 33+ one `setComponentEnabledSettings` batch, written synchronously.
 */
class PackageManagerLauncherAliases(context: Context) : LauncherAliases {
    private val packageManager: PackageManager = context.packageManager
    private val packageName: String = context.packageName

    override fun isEnabled(style: BrandLogoStyle): Boolean =
        when (packageManager.getComponentEnabledSetting(component(style))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == BrandLogoStyle.Detailed
            else -> false
        }

    override fun apply(changes: List<Pair<BrandLogoStyle, Boolean>>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(
                changes.map { (style, enabled) ->
                    PackageManager.ComponentEnabledSetting(component(style), state(enabled), PackageManager.DONT_KILL_APP or PackageManager.SYNCHRONOUS)
                },
            )
        } else {
            for ((style, enabled) in changes) {
                packageManager.setComponentEnabledSetting(component(style), state(enabled), PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun component(style: BrandLogoStyle): ComponentName = ComponentName(packageName, aliasName(style))

    private fun state(enabled: Boolean): Int =
        if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    companion object {
        /** The alias classes of the manifest (relative to the application id `de.corespace.shroud`). */
        fun aliasName(style: BrandLogoStyle): String = when (style) {
            BrandLogoStyle.Detailed -> "de.corespace.shroud.LauncherDetailed"
            BrandLogoStyle.Simple -> "de.corespace.shroud.LauncherSimple"
        }
    }
}
