package de.corespace.shroud.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.GroupedScreen
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay

/** Welcome (`WelcomeView.swift`, `Welcome` in the design). */
@Composable
fun WelcomeScreen(
    server: ServerConfiguration,
    onStartMessaging: () -> Unit,
    onLogIn: () -> Unit,
    onOpenServerSettings: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    // The app's first frame: logo, copy and tiles settle in reading order, then the CTAs arrive.
    var arrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        arrived = true
    }
    val arrival by animateFloatAsState(if (arrived) 1f else 0f, Motion.respecting(reduce, Motion.gentle()), label = "arrival")
    val lift = if (reduce) 0f else 1f - arrival

    GroupedScreen {
        Column(Modifier.fillMaxSize()) {
            GlassBarRow(trailing = {
                GlassCircleButton(ShroudIcons.GearSixFill, "Server settings", onOpenServerSettings)
            })
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenInset)
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BrandLogoMark(
                        80.dp,
                        Modifier
                            .graphicsLayer {
                                val s = if (reduce) 1f else 0.7f + 0.3f * arrival
                                scaleX = s
                                scaleY = s
                                alpha = arrival
                            }
                            .shadow(24.dp, brandTileShape(80.dp), ambientColor = colors.accent.copy(alpha = 0.22f), spotColor = colors.accent.copy(alpha = 0.22f)),
                    )
                    Column(
                        Modifier.graphicsLayer {
                            alpha = arrival
                            translationY = 14.dp.toPx() * lift
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ShroudText(
                            "Private messaging,\nfully encrypted",
                            inter(32f, FontWeight.Bold),
                            colors.textPrimary,
                            Modifier.semantics { heading() },
                            textAlign = TextAlign.Center,
                        )
                        ShroudText(
                            "No phone number. No email. Just your username and a 12-word encryption phrase.",
                            inter(15f, lineSpacing = 4f),
                            colors.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureTile(ShroudIcons.LockFill, "End-to-end encrypted", "Messages decrypt only on your devices", 2, arrived)
                    FeatureTile(ShroudIcons.AudioLines, "Voice messages", "Encrypted audio with on-device transcription", 3, arrived)
                    FeatureTile(ShroudIcons.PhoneFill, "Secure calls", "Voice and video with WebRTC encryption", 4, arrived)
                }
                ConnectionHint(server, Modifier.graphicsLayer { alpha = arrival })
            }
            Column(
                Modifier
                    .padding(horizontal = ScreenInset, vertical = 12.dp)
                    .graphicsLayer {
                        alpha = arrival
                        translationY = 20.dp.toPx() * lift
                    },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PrimaryButton("Start Messaging", onStartMessaging)
                SecondaryButton("Log In", onLogIn)
            }
        }
    }
}

/** One row of the entrance stagger: 30 ms per row, 10 dp lift (`entranceRow`). */
@Composable
private fun FeatureTile(icon: ImageVector, title: String, subtitle: String, index: Int, arrived: Boolean) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    val progress = remember { Animatable(0f) }
    LaunchedEffect(arrived) {
        if (!arrived) return@LaunchedEffect
        delay(30L * index)
        progress.animateTo(1f, Motion.respecting(reduce, Motion.gentle()))
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = progress.value
                translationY = if (reduce) 0f else 10.dp.toPx() * (1f - progress.value)
            }
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(14.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
            ShroudIcon(icon, colors.accent, size = 19.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ShroudText(title, inter(15f, FontWeight.SemiBold), colors.textPrimary)
            ShroudText(subtitle, inter(13f), colors.textSecondary)
        }
    }
}

@Composable
private fun ConnectionHint(server: ServerConfiguration, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val official = server.mode == ServerConnectionMode.Official
    val label = if (official) "Official Shroud server" else server.selfHostedPreview
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.background)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clearAndSetSemantics { contentDescription = "Current server $label" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShroudIcon(if (official) ShroudIcons.SealCheckFill else ShroudIcons.HardDriveFill, colors.accent, size = 14.dp)
        ShroudText(label, inter(12f, FontWeight.Medium), colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
