package de.corespace.shroud.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.Haptic
import de.corespace.shroud.ui.components.LabeledField
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.SectionCaption
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.ShroudToggle
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * Server settings, opened from Welcome's gear (`ServerSettingsSheet.swift`, onboarding context;
 * `Server Settings — Welcome` in the design). Official vs. self-hosted host / port / path.
 */
@Composable
fun ServerSettingsContent(
    initial: ServerConfiguration,
    onSave: (ServerConfiguration) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = ShroudTheme.colors
    var draft by remember(initial) { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShroudText("Server", inter(28f, FontWeight.Bold), colors.textPrimary, Modifier.semantics { heading() })
            ShroudText("Use the official Shroud network or connect to your own self-hosted server.", inter(14f), colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCaption("CONNECTION")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeOption(
                    selected = draft.mode == ServerConnectionMode.Official,
                    title = "Official Shroud server",
                    subtitle = "Managed by Shroud · always up to date",
                    badge = ShroudIcons.SealCheckFill,
                ) {
                    draft = draft.copy(mode = ServerConnectionMode.Official)
                    error = null
                }
                ModeOption(
                    selected = draft.mode == ServerConnectionMode.SelfHosted,
                    title = "Self-hosted",
                    subtitle = "Your Docker / private server",
                    badge = ShroudIcons.HardDriveFill,
                ) {
                    draft = draft.copy(mode = ServerConnectionMode.SelfHosted)
                    error = null
                }
            }
        }
        AnimatedVisibility(
            draft.mode == ServerConnectionMode.SelfHosted,
            enter = fadeIn(Motion.standard()) + expandVertically(Motion.standard(), expandFrom = Alignment.Top),
            exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.standard(), shrinkTowards = Alignment.Top),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionCaption("SELF-HOSTED DETAILS")
                LabeledField("Address / host", ShroudIcons.Globe, draft.host, { draft = draft.copy(host = it) })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LabeledField(
                        "Port",
                        ShroudIcons.Hash,
                        draft.port,
                        { value -> draft = draft.copy(port = value.filter(Char::isDigit).take(5)) },
                        Modifier.weight(1f),
                        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    )
                    LabeledField("API path", ShroudIcons.Folder, draft.apiPath, { draft = draft.copy(apiPath = it) }, Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    ShroudText("Use HTTPS", inter(15f, FontWeight.Medium), colors.textPrimary, Modifier.weight(1f))
                    ShroudToggle(draft.useHTTPS, { draft = draft.copy(useHTTPS = it) }, "Use HTTPS")
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.backgroundGrouped)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShroudIcon(ShroudIcons.Link, colors.textSecondary, size = 14.dp)
                    ShroudText(draft.selfHostedPreview, inter(12f, FontWeight.Medium, monospaced = true), colors.textSecondary, maxLines = 2)
                }
            }
        }
        AnimatedVisibility(error != null, enter = fadeIn(Motion.standard()) + expandVertically(Motion.standard()), exit = fadeOut(Motion.fade()) + shrinkVertically(Motion.standard())) {
            ShroudText(error.orEmpty(), inter(13f, FontWeight.Medium), colors.dangerText)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(colors.accentSoft)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ShroudIcon(ShroudIcons.InfoFill, colors.accentText, size = 16.dp)
            ShroudText(
                if (draft.mode == ServerConnectionMode.Official) {
                    "Official uses Shroud’s managed infrastructure. Self-hosted never leaves your network except as you configure."
                } else {
                    "For Docker Compose on an emulator use 10.0.2.2 and port 8080. On a physical device, use your computer’s LAN IP."
                },
                inter(12f),
                colors.accentText,
            )
        }
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Save", onClick = {
                val validation = draft.validationError()
                if (validation != null) error = validation else onSave(draft)
            }, showsArrow = false)
            SecondaryButton("Cancel", onCancel)
        }
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, subtitle: String, badge: ImageVector, onSelect: () -> Unit) {
    val colors = ShroudTheme.colors
    val fill by animateColorAsState(if (selected) colors.accentSoft else colors.backgroundGrouped, Motion.standard(), label = "modeFill")
    val rim by animateColorAsState(if (selected) colors.accent.copy(alpha = 0.35f) else Color.Transparent, Motion.standard(), label = "modeRim")
    val scale by animateFloatAsState(if (selected) 1f else 0.99f, Motion.standard(), label = "modeScale")
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(scale = 0.98f, haptic = Haptic.Soft, role = Role.RadioButton, onClick = onSelect)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(fill)
            .border(1.5.dp, rim, shape)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                contentDescription = "$title. $subtitle"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(if (selected) 0.dp else 2.dp, if (selected) Color.Transparent else colors.textSecondary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(15f, FontWeight.SemiBold), colors.textPrimary)
            ShroudText(subtitle, inter(12f), colors.textSecondary)
        }
        ShroudIcon(badge, if (selected) colors.accent else colors.textSecondary, size = 18.dp)
    }
}
