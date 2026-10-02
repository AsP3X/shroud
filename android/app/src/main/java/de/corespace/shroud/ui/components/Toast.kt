package de.corespace.shroud.ui.components

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.shell.LocalTabBarClearance
import de.corespace.shroud.ui.theme.DarkColors
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay

/**
 * A transient notice (`ShroudUI/Components/ToastBanner.swift:8-37`): screens only ever show one; the
 * host clears it after [durationMillis]. Showing a toast again — even an equal one — restarts the
 * timer instead of being cut short by the previous one (`:5-7`).
 *
 * [action] makes it the Android permission toast (design `u3il8T`, conversation-compose-media §4.7):
 * a dark pill with a trailing button ("Settings"), up for [ACTION_MS].
 */
@Immutable
data class Toast(
    val message: String,
    val style: Style = Style.Success,
    val durationMillis: Long = SUCCESS_MS,
    val action: Action? = null,
) {
    enum class Style {
        /** Something the user asked for happened ("Copied", "Chat deleted") (`ToastBanner.swift:10-11`). */
        Success,

        /** Neutral notice ("coming soon", "saved, the server gets it later") (`:12-13`). */
        Info,

        /** Something went wrong; the text says what (`:14-15`). */
        Failure,
    }

    /** The trailing button of an action toast. Tapping it runs [onAction] and dismisses the toast. */
    @Immutable
    class Action(val title: String, val onAction: () -> Unit)

    companion object {
        /** `Toast(_:)` default, 1.8 s (`ToastBanner.swift:23`). */
        const val SUCCESS_MS = 1_800L

        /** `Toast.info`, 1.8 s (`ToastBanner.swift:34`). */
        const val INFO_MS = 1_800L

        /** `Toast.failure`, 2.4 s: errors are read, not glanced at (`ToastBanner.swift:29-30`). */
        const val FAILURE_MS = 2_400L

        /** The permission toast stays 4 s so there is time to reach "Settings" (design `u3il8T`). */
        const val ACTION_MS = 4_000L

        fun success(text: String, ms: Long = SUCCESS_MS): Toast = Toast(text, Style.Success, ms)

        fun info(text: String, ms: Long = INFO_MS): Toast = Toast(text, Style.Info, ms)

        fun failure(text: String, ms: Long = FAILURE_MS): Toast = Toast(text, Style.Failure, ms)

        /** "Microphone access is off" + "Settings" (conversation-compose-media §4.7). */
        fun withAction(text: String, actionTitle: String, onAction: () -> Unit, ms: Long = ACTION_MS): Toast =
            Toast(text, Style.Info, ms, Action(actionTitle, onAction))
    }
}

/**
 * The toast a screen shows. [show] replaces the current one and restarts the timer; [dismiss]
 * clears it early (iOS sets the binding to nil, `ToastBanner.swift:116-117`).
 */
@Stable
class ToastState {
    /** Referential: every shown toast is its own value, like iOS's `id` (`ToastBanner.swift:18`). */
    var current: Toast? by mutableStateOf(null, referentialEqualityPolicy())
        private set

    /** Bumped by every [show]: one timer per shown toast, even for equal toasts (`ToastBanner.swift:18`). */
    var generation: Long by mutableLongStateOf(0L)
        private set

    fun show(toast: Toast) {
        current = toast
        generation++
    }

    fun dismiss() {
        current = null
    }

    /**
     * Clears the toast shown as [generation] — unless a newer toast replaced it: an old timer must
     * never clear its successor (`ToastBanner.swift:108-110`).
     */
    fun expire(generation: Long) {
        if (this.generation == generation) current = null
    }

    /**
     * Waits out the current toast ([timeoutMillis] of it; its own duration by default) and then
     * expires it. The host runs this once per [generation]; a newer toast cancels it.
     */
    suspend fun runTimer(timeoutMillis: (Toast) -> Long = { it.durationMillis }) {
        val toast = current ?: return
        val shown = generation
        delay(timeoutMillis(toast))
        expire(shown)
    }
}

@Composable
fun rememberToastState(): ToastState = remember { ToastState() }

/**
 * Floats the current toast over the bottom of the screen and clears it after its duration
 * (`ToastBanner.swift:85-112`, shell-chats §10.15).
 *
 * - Bottom padding = 20 dp + the floating tab bar's clearance ([LocalTabBarClearance], the full
 *   distance from the screen bottom to the bar's top, published by the shell over a tab root) when
 *   there is one, else the system bottom inset (navigation bar, or the keyboard while it is up —
 *   iOS overlays sit inside the keyboard safe area) + [bottomInset], the extra lift over chrome the
 *   host draws itself (a chat's composer) ([toastBottomPadding]). Pushed screens and sheets see a
 *   clearance of 0.
 * - Enters with `Motion.riseFromBottom` on [Motion.bouncy] ("a toast should feel like it landed",
 *   `:103-104`); Reduce Motion fades. Consecutive toasts swap their content in place.
 * - Informational toasts are not hit-testable: taps go through to the controls underneath
 *   (`:98-99`). Only an action toast's button takes a tap.
 * - TalkBack announces the message (polite live region); the timeout honours the user's
 *   accessibility timeout setting ([AccessibilityManager.getRecommendedTimeoutMillis]).
 *
 * Place it last inside the screen's root `Box`, so it draws on top, and outside any inset padding:
 * it adds the system bottom inset itself.
 */
@Composable
fun ToastHost(state: ToastState, bottomInset: Dp = 0.dp) {
    val toast = state.current
    val reduce = ShroudTheme.reduceMotion
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    LaunchedEffect(state.generation) {
        state.runTimer { recommendedToastTimeout(it, accessibility) }
    }
    // Keeps the last toast on screen while it animates out.
    var shown by remember { mutableStateOf<Toast?>(null) }
    if (toast != null) shown = toast
    val systemBottom = WindowInsets.navigationBars.union(WindowInsets.ime).asPaddingValues().calculateBottomPadding()
    // Over a tab root the toast floats 20 dp above the floating tab bar (`ToastBanner.swift:97`).
    val bottom = toastBottomPadding(tabBarClearance = LocalTabBarClearance.current, systemBottom = systemBottom, bottomInset = bottomInset)
    val transition = Motion.riseFromBottom.respecting(reduce)
    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = bottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Appear(visible = toast != null, enter = transition.enter, exit = transition.exit) {
            shown?.let { current ->
                if (current.action != null) {
                    ActionToastBanner(current, current.action, onAction = {
                        current.action.onAction()
                        state.dismiss()
                    })
                } else {
                    ToastBanner(current)
                }
            }
        }
    }
}

/**
 * The toast's bottom padding (`ToastBanner.swift:97`, shell-chats §10.15): 20 dp + [tabBarClearance]
 * when the floating tab bar is up, else [systemBottom], + [bottomInset].
 */
fun toastBottomPadding(tabBarClearance: Dp, systemBottom: Dp, bottomInset: Dp): Dp =
    TOAST_BOTTOM_GAP + (if (tabBarClearance > 0.dp) tabBarClearance else systemBottom) + bottomInset

/** Gap between the toast and the bottom chrome (`ToastBanner.swift:97`). */
val TOAST_BOTTOM_GAP = 20.dp

/**
 * How long [toast] stays up: its duration, stretched by the user's accessibility timeout when one
 * is set (an action toast counts as having controls). Null manager = the duration.
 */
fun recommendedToastTimeout(toast: Toast, accessibility: AccessibilityManager?): Long {
    val manager = accessibility ?: return toast.durationMillis
    val recommended = manager.getRecommendedTimeoutMillis(toast.durationMillis.toInt(), toastContentFlags(toast)).toLong()
    return maxOf(recommended, toast.durationMillis)
}

/** The [AccessibilityManager] content flags of [toast]: icon and text, plus controls with an action. */
fun toastContentFlags(toast: Toast): Int =
    if (toast.action != null) {
        AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
    } else {
        AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_TEXT
    }

/**
 * The glass capsule (`ToastBanner.swift:43-83`): icon 16 + message 14 SemiBold, padding 16 / 12.
 * Opaque on every API level: without a backdrop blur the translucent glass let the button under it
 * show through (shell-chats §6.1).
 */
@Composable
private fun ToastBanner(toast: Toast) {
    val colors = ShroudTheme.colors
    Box(
        Modifier
            .glass(if (colors.isDark) colors.bubbleIncoming else colors.background, colors.glassStroke)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .animateContentSize(Motion.bouncy())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Consecutive toasts swap their text in place instead of re-flying the capsule (`:60-61`).
        Crossfade(targetState = toast.style to toast.message, animationSpec = Motion.fade(), label = "toastContent") { (style, message) ->
            val (icon, tint) = when (style) {
                Toast.Style.Success -> ShroudIcons.CheckCircleFill to colors.online
                Toast.Style.Info -> ShroudIcons.InfoFill to colors.accent
                Toast.Style.Failure -> ShroudIcons.WarningCircleFill to colors.danger
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ShroudIcon(icon, tint, size = 16.dp)
                ShroudText(message, inter(14f, FontWeight.SemiBold), colors.textPrimary)
            }
        }
    }
}

/**
 * The permission toast (design `u3il8T`, conversation-compose-media §4.7): a 48 dp dark pill
 * ([MediaColors.toastDark], radius 24, padding 0 / 18) — message 15 white, action 15 SemiBold in the
 * dark `accentText` (#A7A7FA). The pill is 48 dp tall, so the action's hit area is too.
 */
@Composable
private fun ActionToastBanner(toast: Toast, action: Toast.Action, onAction: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MediaColors.toastDark)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(
            toast.message,
            inter(15f),
            Color.White,
            Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .height(48.dp)
                .pressable(onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            ShroudText(action.title, inter(15f, FontWeight.SemiBold), DarkColors.accentText, maxLines = 1)
        }
    }
}
