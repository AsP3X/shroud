package de.corespace.shroud.ui.components

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import kotlin.math.max

/**
 * Metrics of the glass bars (`GlassBar.swift:13-32`; shell-chats §6.2): the system toolbar's
 * 44 dp controls, 16 dp side inset, 8 dp between controls, 6 dp under the row.
 */
object GlassBarMetrics {
    /** Control diameter (`GlassBar.swift:15`). */
    val controlSize: Dp = 44.dp

    /** Horizontal bar inset (`GlassBar.swift:17`). */
    val horizontalInset: Dp = 16.dp

    /** Gap between neighbouring controls (`GlassBar.swift:20`). */
    val spacing: Dp = 8.dp

    /** Room under the control row before content starts (`GlassBar.swift:22`). */
    val bottomPadding: Dp = 6.dp

    /** Gap between the status bar and a main bar's row (design `Header` pad [4,16,10,16]; shell-chats §7). */
    val topPadding: Dp = 4.dp

    /** Gap from a main bar's row to the header search field: the design's 12, not iOS's 6 (shell-chats D13). */
    val headerGap: Dp = 12.dp

    /** iOS SF 17 semibold glyph → a 20 dp icon (design `New Chat Glyph` 20). */
    val glyphSize: Dp = 20.dp

    /** Capsule label: 16 sp Medium, 16 dp side padding (`GlassBar.swift:30, 73`). */
    const val LABEL_SIZE = 16f
    val capsulePadding: Dp = 16.dp

    /** The centred title: 17 sp SemiBold, shrinking to 80 % before it truncates (`GlassBar.swift:31, 251`). */
    const val TITLE_SIZE = 17f
    const val TITLE_MIN_SCALE = 0.8f

    /** A disabled control fades to 40 % instead of vanishing, so the bar keeps its shape (`GlassBar.swift:80-82`). */
    const val DISABLED_ALPHA = 0.4f

    /**
     * Width a centred title keeps free on each side for a cluster of [controls] glass controls
     * plus the gap to the title (`GlassBar.swift:23-27`): 44·max(1, controls) + 8 (default 96).
     */
    fun sideReserve(controls: Int): Dp = controlSize * max(1, controls) + spacing

    /** Height of a main bar under the status bar: top 4 + row 44 + bottom 6. */
    val mainBarHeight: Dp get() = topPadding + controlSize + bottomPadding
}

/**
 * How a [GlassBarButton] looks (`GlassBar.swift:48-57`): a glyph in a circle, a word in a capsule
 * (both accent on regular glass), or [Prominent] — accent-tinted glass with a white glyph or word,
 * for the one action a bar wants pressed (Save, Send).
 */
enum class GlassBarButtonStyle { Circle, Capsule, Prominent }

/** Set by [GlassBarGroup]: its buttons share the group's one capsule instead of drawing their own. */
private val LocalGlassBarGrouped = staticCompositionLocalOf { false }

/**
 * One glass bar control (`GlassBar.swift:59-123`; shell-chats §6.2; design `Nav Row`): [icon] in a
 * 44 dp circle, or [label] in a 44 dp capsule (16 sp Medium, padding h 16). Regular glass with an
 * `accent` glyph, or `Prominent` accent glass with a white one. The glass is always a capsule (a
 * square frame makes the circle), so a [GlassBarGroup] fuses neighbours into one.
 *
 * Press: [haptic] on press-down and the interactive glass swell, no scale or dim of the label
 * (iOS `PressableButtonStyle(scale: 1, dimming: 0)`). Disabled: fades to 40 % with `Motion.fade`.
 * A changed [label] cross-fades (iOS `.contentTransition(.opacity)`, Contacts' "A–Z" ⇄ "Z–A").
 * TalkBack: one button named [contentDescription]. The 44 dp control already gets Compose's 48 dp
 * minimum touch target.
 */
@Composable
fun GlassBarButton(
    icon: ImageVector?,
    contentDescription: String,
    onClick: () -> Unit,
    style: GlassBarButtonStyle = GlassBarButtonStyle.Circle,
    label: String? = null,
    enabled: Boolean = true,
    haptic: Haptic = Haptic.Light,
    modifier: Modifier = Modifier,
) {
    val colors = ShroudTheme.colors
    val prominent = style == GlassBarButtonStyle.Prominent
    val capsule = label != null && (style != GlassBarButtonStyle.Circle || icon == null)
    val grouped = LocalGlassBarGrouped.current
    val ink = if (prominent) Color.White else colors.accent
    val interaction = remember { MutableInteractionSource() }
    PressHaptic(interaction, haptic, enabled)
    val alpha by animateFloatAsState(
        if (enabled) 1f else GlassBarMetrics.DISABLED_ALPHA,
        Motion.fade(),
        label = "glassBarButtonAlpha",
    )
    val glass = when {
        prominent -> Modifier.kitBarGlass(CircleShape, prominent = true, interactive = enabled)
        grouped -> Modifier
        else -> Modifier.kitBarGlass(CircleShape, prominent = false, interactive = enabled)
    }
    Box(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .then(glass)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .clearAndSetSemantics { this.contentDescription = contentDescription }
            .heightIn(min = GlassBarMetrics.controlSize)
            .widthIn(min = GlassBarMetrics.controlSize)
            .then(if (capsule) Modifier.padding(horizontal = GlassBarMetrics.capsulePadding) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (capsule) {
            AnimatedContent(
                targetState = label.orEmpty(),
                transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
                contentAlignment = Alignment.Center,
                label = "glassBarLabel",
            ) { text ->
                ShroudText(text, inter(GlassBarMetrics.LABEL_SIZE, FontWeight.Medium), ink, maxLines = 1)
            }
        } else if (icon != null) {
            ShroudIcon(icon, ink, size = GlassBarMetrics.glyphSize)
        }
    }
}

/** Plays [haptic] when a press lands on [interaction] (iOS ticks on press-down, `Motion.swift:93-96`). */
@Composable
private fun PressHaptic(interaction: MutableInteractionSource, haptic: Haptic, enabled: Boolean) {
    val view = LocalView.current
    val currentHaptic by rememberUpdatedState(haptic)
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(interaction) {
        interaction.interactions.collect { event ->
            if (event is PressInteraction.Press && currentEnabled) view.perform(currentHaptic)
        }
    }
}

/**
 * Neighbouring bar controls fused into one glass capsule (`GlassBar.swift:175-193`; design
 * `Call Group` 88 × 44): Contacts' QR + Add, the chat's Video + Call. Each [GlassBarButton] inside
 * stays its own 44 dp button with its own label; the group draws the one capsule behind them.
 */
@Composable
fun GlassBarGroup(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.kitBarGlass(CircleShape, prominent = false),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalGlassBarGrouped provides true) {
            content()
        }
    }
}

/**
 * The control row of a bar (`GlassBar.swift:197-240`; shell-chats §6.3): [leading] and [trailing]
 * clusters, and an optional [title]. At least 44 dp high, 16 dp side inset, 6 dp under it.
 *
 * The title is centred on the **screen**, not between the clusters, so it stays put when a side
 * gains or loses a button; it is inset by [sideReserve] on both sides (two controls plus the gap
 * by default, `GlassBarMetrics.sideReserve(2)` = 96) so a long title shrinks and truncates before
 * it runs under a cluster. The conversation header passes `sideReserve(1)` for Notes
 * (`ConversationView.swift:705`). Without a title it is the plain leading / trailing row the
 * onboarding screens use. No backdrop: content passing under it is faded by [ScrollEdgeEffect].
 */
@Composable
fun GlassBarRow(
    leading: @Composable RowScope.() -> Unit = {},
    title: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    sideReserve: Dp = GlassBarMetrics.sideReserve(2),
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = GlassBarMetrics.horizontalInset, end = GlassBarMetrics.horizontalInset, bottom = GlassBarMetrics.bottomPadding)
            .heightIn(min = GlassBarMetrics.controlSize),
        contentAlignment = Alignment.Center,
    ) {
        if (title != null) {
            Box(Modifier.padding(horizontal = sideReserve), contentAlignment = Alignment.Center) { title() }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(GlassBarMetrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * The plain centred bar title (`GlassBar.swift:242-255`): 17 sp SemiBold `textPrimary`, one line,
 * shrinking to 80 % before it truncates, a heading for TalkBack. [progress] is its opacity, for a
 * title that hands over from a hero (the Settings root, `SettingsView.swift:259-262`); a title at
 * 0 is hidden from TalkBack as well.
 */
@Composable
fun GlassBarTitle(text: String, progress: Float = 1f, modifier: Modifier = Modifier) {
    val color = ShroudTheme.colors.textPrimary
    val max = GlassBarMetrics.TITLE_SIZE
    BasicText(
        text = text,
        modifier = modifier
            .graphicsLayer { alpha = progress.coerceIn(0f, 1f) }
            .then(
                if (progress <= 0f) {
                    Modifier.clearAndSetSemantics {}
                } else {
                    Modifier.clearAndSetSemantics {
                        this.contentDescription = text
                        heading()
                    }
                },
            ),
        style = inter(max, FontWeight.SemiBold).copy(color = color, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = (max * GlassBarMetrics.TITLE_MIN_SCALE).sp,
            maxFontSize = max.sp,
            stepSize = 0.25.sp,
        ),
    )
}

/** Which edge a [ScrollEdgeEffect] softens. */
enum class ScrollEdge { Top, Bottom }

/**
 * The scroll edge effect under a pinned bar (`GlassBar.swift:257-276`, iOS
 * `scrollEdgeEffectStyle(.soft)`; shell-chats §6.4): content scrolls under the status bar and the
 * bar, and this fades it so the bar's title and controls stay legible. Shown only while content is
 * actually under the bar ([visible]), faded in and out with `Motion.fade` (memory: Liquid Glass bar
 * gotchas — the effect only applies under a bar with visible content).
 *
 * API 31+ with a [backdrop] (the scroll content marked with Haze's `hazeSource`): a progressive
 * backdrop blur, 20 dp at the screen edge down to none at the bar's inner edge, under a
 * `background @ 0.85 → 0` gradient. API 30 or no backdrop: the gradient alone, `background @ 0.95
 * → 0`. [extent] covers the status bar plus the bar. Decorative, never hit-testable.
 */
@Composable
fun ScrollEdgeEffect(
    visible: Boolean,
    extent: Dp,
    backdrop: HazeState?,
    modifier: Modifier = Modifier,
    edge: ScrollEdge = ScrollEdge.Top,
) {
    val shown by animateFloatAsState(if (visible) 1f else 0f, Motion.fade(), label = "scrollEdge")
    if (shown <= 0f) return
    val background = ShroudTheme.colors.background
    val blurs = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val peak = if (blurs) ScrollEdgeEffectSpec.BLURRED_PEAK else ScrollEdgeEffectSpec.FLAT_PEAK
    val towardsContent = listOf(background.copy(alpha = peak), background.copy(alpha = 0f))
    val brush = Brush.verticalGradient(if (edge == ScrollEdge.Top) towardsContent else towardsContent.reversed())
    val blur = if (blurs && backdrop != null) {
        val style = remember(background, edge) {
            HazeBlurStyle {
                blurRadius(ScrollEdgeEffectSpec.blurRadius)
                backgroundColor(background)
                noiseFactor(0f)
                colorEffects(emptyList())
                progressive(
                    if (edge == ScrollEdge.Top) {
                        HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
                    } else {
                        HazeProgressive.verticalGradient(startIntensity = 0f, endIntensity = 1f)
                    },
                )
            }
        }
        Modifier.hazeBlur(input = HazeInput.Sources(backdrop), style = style)
    } else {
        Modifier
    }
    Box(
        modifier
            .clearAndSetSemantics {}
            .fillMaxWidth()
            .height(extent)
            .graphicsLayer { alpha = shown }
            .then(blur)
            .background(brush),
    )
}

/**
 * Marks scroll content as the [backdrop] a [ScrollEdgeEffect] blurs. Only where the platform blurs
 * (API 31+): below that the effect is a plain gradient and recording the content would be waste.
 */
internal fun Modifier.edgeEffectSource(backdrop: HazeState): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hazeSource(backdrop) else this

/** The numbers of [ScrollEdgeEffect] (shell-chats §6.4). */
object ScrollEdgeEffectSpec {
    /** Gradient peak over a blurred backdrop. */
    const val BLURRED_PEAK = 0.85f

    /** Gradient peak without a blur (API 30): text behind must not show through. */
    const val FLAT_PEAK = 0.95f

    /** Blur at the screen edge. */
    val blurRadius: Dp = 20.dp
}

@Preview(name = "Glass bars · 412", widthDp = 412)
@Composable
private fun GlassBarPreview() = GlassBarSamples(dark = false)

@Preview(name = "Glass bars · 360 · dark", widthDp = 360)
@Composable
private fun GlassBarDarkPreview() = GlassBarSamples(dark = true)

@Composable
private fun GlassBarSamples(dark: Boolean) {
    ShroudTheme(dark = dark) {
        Column(Modifier.background(ShroudTheme.colors.background).padding(vertical = 12.dp)) {
            GlassBarRow(
                leading = { GlassBarButton(null, "Sorted A to Z", {}, GlassBarButtonStyle.Capsule, label = "A–Z") },
                title = { GlassBarTitle("Contacts") },
                trailing = {
                    GlassBarGroup {
                        GlassBarButton(ShroudIcons.Search, "My QR code", {})
                        GlassBarButton(ShroudIcons.HeartFill, "Add contact", {})
                    }
                },
            )
            GlassBarRow(
                title = { GlassBarTitle("A very long bar title that has to shrink and truncate") },
                trailing = { GlassBarButton(ShroudIcons.CaretRight, "New chat", {}, GlassBarButtonStyle.Prominent) },
            )
            GlassBarRow(leading = { GlassBarButton(ShroudIcons.CaretLeftBold, "Back", {}, enabled = false) })
        }
    }
}
