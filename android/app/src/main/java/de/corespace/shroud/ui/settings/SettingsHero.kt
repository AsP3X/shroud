package de.corespace.shroud.ui.settings

import android.os.Build
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.corespace.shroud.ui.components.Avatar
import de.corespace.shroud.ui.components.AvatarPalette
import de.corespace.shroud.ui.components.ShroudIcon
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.BrandColors
import de.corespace.shroud.ui.theme.ShroudIcons
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Settings root's collapsing profile hero, as numbers (`SettingsView.swift:41-143`;
 * settings-lock §3.3, ported verbatim). Everything is in dp and driven by one input, the scroll
 * offset of the cards; nothing animates on its own. Pure, so `SettingsHeroMathTest` pins it.
 *
 * At rest the hero is 178 dp tall: avatar 88, name line 30, handle line 20 and their gaps. The
 * first 134 dp of scrolling collapse it into the 44 dp compact bar: the avatar shrinks, blurs and
 * leaves upwards, the name travels to the bar's centre and shrinks to 17 sp, and in the last 15 %
 * the travelling name cross-fades into the bar's own title ([SettingsHeroFrame.barTitleProgress]).
 */
object SettingsHeroMath {
    /** The collapsed bar: a centred title only, no side buttons (`SettingsView.swift:44`). */
    const val COMPACT_BAR_HEIGHT = 44f
    const val AVATAR_EXPANDED_SIZE = 88f
    const val HERO_TOP_PADDING = 12f
    const val AVATAR_TO_NAME_GAP = 12f
    const val NAME_EXPANDED_LINE = 30f
    const val HANDLE_LINE = 20f
    const val HANDLE_GAP = 4f
    const val HERO_BOTTOM_PADDING = 12f
    const val NAME_EXPANDED_SIZE = 26f
    const val NAME_COLLAPSED_SIZE = 17f

    /** Scroll changes smaller than this are not applied (`SettingsView.swift:267`). */
    const val OFFSET_EPSILON = 0.2f

    /** Avatar (32) initials on the 88 dp avatar (`SettingsView.swift:308`). */
    const val AVATAR_FONT_SIZE = 32f

    /** The full hero: 12 + 88 + 12 + 30 + 4 + 20 + 12 = 178 (`SettingsView.swift:57-65`). */
    const val EXPANDED_HERO_HEIGHT: Float =
        HERO_TOP_PADDING + AVATAR_EXPANDED_SIZE + AVATAR_TO_NAME_GAP + NAME_EXPANDED_LINE + HANDLE_GAP + HANDLE_LINE + HERO_BOTTOM_PADDING

    /** Scroll distance that maps progress 0 → 1: 134 (`SettingsView.swift:68-70`). */
    val collapseDistance: Float = max(1f, EXPANDED_HERO_HEIGHT - COMPACT_BAR_HEIGHT)

    /**
     * The spacer above the cards: the hero minus the compact bar, whose 44 dp is already the pinned
     * top inset of the scroll content (`SettingsView.swift:232-236`).
     */
    val contentSpacer: Float = EXPANDED_HERO_HEIGHT - COMPACT_BAR_HEIGHT

    /** 0 at rest, 1 once collapsed into the compact bar (`SettingsView.swift:73-75`). */
    fun progress(scrollOffset: Float): Float = min(1f, max(0f, scrollOffset / collapseDistance))

    /** Whether a new scroll offset is far enough from the applied one to redraw the hero (`SettingsView.swift:267`). */
    fun shouldApply(applied: Float, new: Float): Boolean = kotlin.math.abs(new - applied) > OFFSET_EPSILON

    /** Every value the hero draws at [scrollOffset] (dp, ≥ 0 at rest). */
    fun frame(scrollOffset: Float): SettingsHeroFrame {
        val p = progress(scrollOffset)
        // `SettingsView.swift:96-101`: from its resting centre to above the compact bar.
        val avatarRest = HERO_TOP_PADDING + AVATAR_EXPANDED_SIZE / 2
        val avatarGone = -AVATAR_EXPANDED_SIZE * 0.55f
        // `SettingsView.swift:105-114`: the name reaches the bar a little before p = 1.
        val nameRest = HERO_TOP_PADDING + AVATAR_EXPANDED_SIZE + AVATAR_TO_NAME_GAP + NAME_EXPANDED_LINE / 2
        val nameBar = COMPACT_BAR_HEIGHT / 2
        val nameT = min(1f, p * 1.05f)
        val nameCenterY = nameRest + (nameBar - nameRest) * nameT
        return SettingsHeroFrame(
            progress = p,
            stickyHeight = max(COMPACT_BAR_HEIGHT, EXPANDED_HERO_HEIGHT - scrollOffset),
            avatarScale = 1f - 0.78f * p,
            avatarBlur = 20f * p,
            avatarOpacity = max(0f, 1f - p.pow(1.25f) * 1.08f),
            avatarCenterY = avatarRest + (avatarGone - avatarRest) * p,
            nameCenterY = nameCenterY,
            nameFontSize = NAME_EXPANDED_SIZE - (NAME_EXPANDED_SIZE - NAME_COLLAPSED_SIZE) * p * p,
            nameIsBold = p <= 0.5f,
            handleOpacity = max(0f, 1f - p * 2f),
            handleCenterY = nameCenterY + NAME_EXPANDED_LINE / 2 + HANDLE_GAP + HANDLE_LINE / 2,
            badgeOpacity = max(0f, 1f - p * 2.4f),
            barTitleProgress = min(1f, max(0f, (p - 0.85f) / 0.15f)),
        )
    }
}

/**
 * One frame of the hero ([SettingsHeroMath.frame]); lengths in dp from the top of the hero area
 * (just under the status bar), font size in sp.
 *
 * @property nameIsBold Bold while expanded, SemiBold past halfway (`SettingsView.swift:122-124`).
 * @property barTitleProgress how far the travelling name has become the compact bar's title: its
 *   own opacity is `1 − barTitleProgress`, the bar title's is `barTitleProgress` (`SettingsView.swift:138-143`).
 */
@Immutable
data class SettingsHeroFrame(
    val progress: Float,
    val stickyHeight: Float,
    val avatarScale: Float,
    val avatarBlur: Float,
    val avatarOpacity: Float,
    val avatarCenterY: Float,
    val nameCenterY: Float,
    val nameFontSize: Float,
    val nameIsBold: Boolean,
    val handleOpacity: Float,
    val handleCenterY: Float,
    val badgeOpacity: Float,
    val barTitleProgress: Float,
)

/**
 * Who the Settings hero shows (`SettingsView.swift:155-175`; settings-lock §3.3; the web does the
 * same, `format.ts:89-98`). Pure.
 */
object SettingsIdentity {
    /** Shown when there is no username (`SettingsView.swift:163`). */
    const val FALLBACK_NAME = "Shroud User"

    /** Shown as the handle when there is no username (`SettingsView.swift:170`). */
    const val FALLBACK_HANDLE = "@user"

    /**
     * The username as a name (`SettingsView.swift:155-164`): `_` becomes a space, each word gets
     * its first character upper-cased and the rest lower-cased (Swift `uppercased()` /
     * `lowercased()`, locale-free; a "character" is a grapheme cluster), joined with single
     * spaces. "niklas_v" → "Niklas V". No username → [FALLBACK_NAME].
     */
    fun displayName(username: String?): String {
        if (username.isNullOrEmpty()) return FALLBACK_NAME
        return username
            .replace('_', ' ')
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                val first = firstGraphemeLength(word)
                word.substring(0, first).uppercase(Locale.ROOT) + word.substring(first).lowercase(Locale.ROOT)
            }
    }

    /** `@username`, or [FALLBACK_HANDLE] (`SettingsView.swift:166-171`). */
    fun handle(username: String?): String = if (username.isNullOrEmpty()) FALLBACK_HANDLE else "@$username"

    /** The hero avatar's initials, from the display name (`SettingsView.swift:173-175`, `AvatarView.swift:67-79`). */
    fun initials(displayName: String): String = AvatarPalette.initials(displayName)

    private fun firstGraphemeLength(word: String): Int {
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(word)
        val end = iterator.next()
        return if (end == BreakIterator.DONE) word.length else end
    }
}

/**
 * The hero itself, the sticky chrome over the Settings cards (`SettingsView.swift:301-349`):
 * avatar, name with its verified seal, and handle, each centred on the screen at the heights of
 * [frame]. Never hit-testable (the caller puts it above the scroll content without pointer input).
 * TalkBack hears one heading, "<name>, <handle>", read before the cards (the caller orders it
 * first, iOS `accessibilitySortPriority(1)`).
 *
 * The avatar blurs as it leaves on API 31+; on API 30 `Modifier.blur` does nothing, and scale
 * plus opacity carry the exit (settings-lock §3.3).
 */
@Composable
fun SettingsHero(displayName: String, handle: String, frame: SettingsHeroFrame, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val blur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && frame.avatarBlur > 0f) {
        Modifier.blur(frame.avatarBlur.dp, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }
    Layout(
        content = {
            Avatar(
                initials = SettingsIdentity.initials(displayName),
                size = SettingsHeroMath.AVATAR_EXPANDED_SIZE.dp,
                brush = BrandColors.brandGradient,
                fontSize = SettingsHeroMath.AVATAR_FONT_SIZE.sp,
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = frame.avatarScale
                        scaleY = frame.avatarScale
                        alpha = frame.avatarOpacity
                    }
                    .then(blur),
            )
            HeroName(displayName, frame, Modifier.graphicsLayer { alpha = 1f - frame.barTitleProgress })
            ShroudText(
                handle,
                inter(15f),
                colors.textSecondary,
                Modifier.graphicsLayer { alpha = frame.handleOpacity },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = "$displayName, $handle"
                heading()
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val mid = width / 2
        val loose = Constraints(maxWidth = width)
        val avatar = measurables[0].measure(Constraints())
        // `.frame(maxWidth: midX * 1.4)` (`SettingsView.swift:332`).
        val name = measurables[1].measure(Constraints(maxWidth = (mid * 1.4f).roundToInt().coerceAtLeast(0)))
        val handleText = measurables[2].measure(loose)
        val height = frame.stickyHeight.dp.roundToPx()
        layout(width, height) {
            avatar.place(mid - avatar.width / 2, (frame.avatarCenterY.dp.toPx() - avatar.height / 2f).roundToInt())
            name.place(mid - name.width / 2, (frame.nameCenterY.dp.toPx() - name.height / 2f).roundToInt())
            handleText.place(mid - handleText.width / 2, (frame.handleCenterY.dp.toPx() - handleText.height / 2f).roundToInt())
        }
    }
}

/**
 * The travelling name, shrinking to 75 % before it truncates, with the verified seal hanging off
 * its trailing edge **without taking layout space** (6 dp gap, `nameFontSize × 0.78`), so the name
 * itself stays centred and lands exactly on the bar's title (`SettingsView.swift:317-336`; the
 * design's Phosphor `circle-wavy-check-fill` is `seal-check-fill` in Phosphor 2).
 */
@Composable
private fun HeroName(displayName: String, frame: SettingsHeroFrame, modifier: Modifier = Modifier) {
    val colors = ShroudTheme.colors
    val size = frame.nameFontSize
    Layout(
        content = {
            BasicText(
                text = displayName,
                style = inter(size, if (frame.nameIsBold) FontWeight.Bold else FontWeight.SemiBold).copy(color = colors.textPrimary),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = (size * 0.75f).sp, maxFontSize = size.sp, stepSize = 0.25.sp),
            )
            ShroudIcon(
                ShroudIcons.SealCheckFill,
                colors.accent,
                Modifier.graphicsLayer { alpha = frame.badgeOpacity },
                size = (size * 0.78f).dp,
            )
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val text = measurables[0].measure(constraints.copy(minWidth = 0))
        val badge = measurables[1].measure(Constraints())
        layout(text.width, text.height) {
            text.place(0, 0)
            badge.place(text.width + 6.dp.roundToPx(), (text.height - badge.height) / 2)
        }
    }
}
