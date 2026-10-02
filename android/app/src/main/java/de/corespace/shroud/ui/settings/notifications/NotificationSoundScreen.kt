package de.corespace.shroud.ui.settings.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import de.corespace.shroud.core.devices.DeviceNoun
import de.corespace.shroud.core.notifications.NotificationSound
import de.corespace.shroud.ui.LocalAppContainer
import de.corespace.shroud.ui.components.Appear
import de.corespace.shroud.ui.components.InsetDivider
import de.corespace.shroud.ui.components.PushedScreen
import de.corespace.shroud.ui.components.SettingsCard
import de.corespace.shroud.ui.components.SettingsMetrics
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.highlightRow
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Settings › Notifications and Sounds › Sound (iOS `NotificationSoundPicker`,
 * `ios/shroud/Features/Main/NotificationsSettingsView.swift:460-538`; settings-lock §6.5,
 * notifications-push §5.15).
 *
 * Human: Seven sounds; each plays as it is picked (quietly skipped while the phone is on silent or
 * vibrate) and becomes the sound of message notifications at once — Android fixes a channel's sound,
 * so the app replaces its channels with ones that carry the new sound (N8). If the user picked
 * another sound for the channel in Android Settings, a line says so: that one wins for notifications.
 *
 * Agent: writes `NotificationPreferences.sound`; the notifications controller recreates the
 * channels; the server hears the choice 500 ms later (errors ignored).
 */
@Composable
fun NotificationSoundScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifications = container.notifications
    val controller = notifications.controller
    val session = container.auth.sessionController.session
    val noun = remember(context) { DeviceNoun.current(context) }
    val model = remember(container) {
        NotificationSoundModel(
            preferences = notifications.preferences,
            play = notifications.soundPlayer::play,
            pushSettings = controller::pushSettings,
            token = { session.value?.token },
            scope = scope,
        )
    }
    val prefs by notifications.preferences.state.collectAsState()
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val differs = remember(prefs.sound, prefs.badge, resumes) {
        runCatching { notifications.channels.messagesSoundDiffers() }.getOrDefault(false)
    }
    PushedScreen(NotificationsCopy.SOUND_TITLE, onBack) {
        NotificationSoundContent(selected = prefs.sound, noun = noun, soundDiffers = differs, onPick = model::pick)
    }
}

/**
 * The card of seven rows and the footer (`:468-495`): Column spacing 8, padding h 16, top 8; rows
 * h 14 v 13 with the selected one ticked (`check` 15 `accent`, `Motion.iconSwap` on `snappy`),
 * dividers inset 14.
 */
@Composable
internal fun NotificationSoundContent(
    selected: NotificationSound,
    noun: String,
    soundDiffers: Boolean,
    onPick: (NotificationSound) -> Unit,
) {
    val colors = ShroudTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SettingsCard {
            NotificationSound.entries.forEachIndexed { index, sound ->
                SoundRow(sound, sound == selected, onPick)
                if (index < NotificationSound.entries.lastIndex) InsetDivider(14.dp)
            }
        }
        ShroudText(
            NotificationsCopy.soundFooter(noun),
            inter(13f),
            colors.textSecondary,
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsMetrics.textInset),
        )
        if (soundDiffers) {
            ShroudText(
                NotificationsCopy.SOUND_DIFFERS,
                inter(13f),
                colors.textSecondary,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsMetrics.textInset),
            )
        }
    }
}

@Composable
private fun SoundRow(sound: NotificationSound, selected: Boolean, onPick: (NotificationSound) -> Unit) {
    val colors = ShroudTheme.colors
    val transition = Motion.iconSwap.respecting(ShroudTheme.reduceMotion)
    Row(
        Modifier
            .fillMaxWidth()
            .highlightRow(onClick = { onPick(sound) })
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudText(sound.title, inter(16f), colors.textPrimary, Modifier.weight(1f))
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Appear(visible = selected, enter = transition.enter, exit = transition.exit) {
                ShroudIcon(ShroudIcons.CheckBold, colors.accent, size = 15.dp)
            }
        }
    }
}
