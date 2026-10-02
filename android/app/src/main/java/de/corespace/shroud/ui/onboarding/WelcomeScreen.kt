package de.corespace.shroud.ui.onboarding

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.net.ServerConfiguration
import de.corespace.shroud.core.net.ServerConnectionMode
import de.corespace.shroud.ui.components.BrandLogoMark
import de.corespace.shroud.ui.components.GlassBarRow
import de.corespace.shroud.ui.components.GlassCircleButton
import de.corespace.shroud.ui.components.GroupedScreen
import de.corespace.shroud.ui.components.ListEntranceHost
import de.corespace.shroud.ui.components.PrimaryButton
import de.corespace.shroud.ui.components.ScreenInset
import de.corespace.shroud.ui.components.SecondaryButton
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.components.entranceRow
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import kotlinx.coroutines.delay

/**
 * Welcome (`WelcomeView.swift`; settings-lock addendum *WelcomeView.swift*; design `Welcome` `oOR2X`).
 *
 * The arrival (logo, copy, CTAs) plays once per Welcome: `arrived` is saveable, so a Welcome the
 * shell keeps under Sign Up / Log In (with a saveable-state holder per route) does not replay it on
 * return (W1). The feature tiles use the shared list entrance (W2); the connection hint is static
 * (W3). The post-auth toast and the orphan reconcile belong to the shell's root (W4, W5). The logo
 * is the zoom source of Sign Up and Log In when the shell hosts the onboarding zoom (W8).
 */
@Composable
fun WelcomeScreen(
    server: ServerConfiguration,
    onStartMessaging: () -> Unit,
    onLogIn: () -> Unit,
    onOpenServerSettings: () -> Unit,
) {
    val colors = ShroudTheme.colors
    val reduce = ShroudTheme.reduceMotion
    // The app's first frame: logo and copy settle (`Motion.gentle`, 50 ms in, `:41-53`), then the CTAs arrive.
    var arrived by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (arrived) return@LaunchedEffect
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
                            // Accent 22 %, blur 16, y 10 (`:98`, W7): a drop shadow, not an elevation.
                            .dropShadow(brandTileShape(80.dp), Shadow(radius = 16.dp, color = colors.accent, offset = DpOffset(0.dp, 10.dp), alpha = 0.22f))
                            // The zoom source of Sign Up and Log In (`:97`, W8).
                            .onboardingHeroSource(80.dp),
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
                // The shared list entrance, rows 2-4, timed from the screen's appearance (`:121-131`, W2).
                ListEntranceHost(key = Unit) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FeatureTile(ShroudIcons.LockFill, "End-to-end encrypted", "Messages decrypt only on your devices", Modifier.entranceRow(2))
                        FeatureTile(ShroudIcons.AudioLines, "Voice messages", "Encrypted audio with on-device transcription", Modifier.entranceRow(3))
                        FeatureTile(ShroudIcons.PhoneFill, "Secure calls", "Voice and video with WebRTC encryption", Modifier.entranceRow(4))
                    }
                }
                // Static: on screen from the first frame (W3).
                ConnectionHint(server)
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
                // iOS accessibility identifiers `welcome.startMessaging`, `welcome.logIn` (W6).
                PrimaryButton("Start Messaging", onStartMessaging, Modifier.testTag("welcome.startMessaging"))
                SecondaryButton("Log In", onLogIn, Modifier.testTag("welcome.logIn"))
            }
        }
    }
}

/** A feature tile (`featureTile`, `:161-187`): 40 dp glyph square, title and subtitle, one TalkBack stop. */
@Composable
private fun FeatureTile(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    Row(
        modifier
            .fillMaxWidth()
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

/** The server this phone talks to (`connectionHint`, `:133-159`): one TalkBack element "Current server …". */
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
