package de.corespace.shroud.ui.onboarding

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import de.corespace.shroud.ui.components.brandTileShape
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import kotlin.math.min

/**
 * The Welcome → Sign Up / Log In zoom (`OnboardingHeroTransition.swift`; settings-lock addendum
 * *OnboardingHeroTransition.swift*, decision O-1 option A): the pushed screen grows out of the brand
 * mark and shrinks back into it on Back. The lock screen's mark is a source too, for the phrase push
 * (`LockScreenView.swift:202-203`).
 *
 * As on iOS, the screens only mark themselves ([onboardingHeroSource], [onboardingHeroDestination]);
 * the host that animates the onboarding stack provides the scopes ([ProvideOnboardingHero], iOS
 * `RootView`'s `onboardingNamespace`). Without a host — previews, screen tests, a shell that does
 * not wire it — and under Reduce Motion both modifiers do nothing and the host's own transition runs.
 *
 * Host recipe (W3-SHELL's onboarding stack):
 * ```
 * SharedTransitionLayout {
 *     AnimatedContent(top, transitionSpec = { … }) { route ->
 *         ProvideOnboardingHero(this@SharedTransitionLayout, this@AnimatedContent) { when (route) { … } }
 *     }
 * }
 * ```
 * For a push or pop between the root (Welcome / Lock) and Sign Up / Log In the host's own
 * content transition should be a plain fade (the zoom carries the motion); Sign Up ↔ Log In keeps
 * the slide (the addendum: a slide reads better than iOS's second zoom).
 */
object OnboardingHeroId {
    /** `OnboardingHeroID.brand` (`OnboardingHeroTransition.swift:4-6`). */
    const val BRAND = "onboardingBrandHero"
}

/** The scopes one onboarding screen shares its hero in: the host's shared-transition scope and that screen's visibility. */
@Stable
class OnboardingHero(
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
)

/** iOS `EnvironmentValues.onboardingNamespace` (`:8-18`): null outside a host, which turns the zoom off. */
val LocalOnboardingHero = compositionLocalOf<OnboardingHero?> { null }

/** Lets the screen in [content] take part in the zoom of the host's [sharedTransitionScope]. */
@Composable
fun ProvideOnboardingHero(
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOnboardingHero provides OnboardingHero(sharedTransitionScope, animatedVisibilityScope), content = content)
}

/**
 * The brand mark the pushed screen zooms from (`onboardingHeroSource`, `:24-26, 34-44`): Welcome's
 * 80 dp mark, the lock screen's 100 dp one. [size] gives the tile's corners.
 */
fun Modifier.onboardingHeroSource(size: Dp): Modifier = composed {
    val hero = LocalOnboardingHero.current
    if (hero == null || ShroudTheme.reduceMotion) return@composed this
    with(hero.sharedTransitionScope) {
        this@composed.sharedBounds(
            rememberSharedContentState(OnboardingHeroId.BRAND),
            hero.animatedVisibilityScope,
            enter = fadeIn(Motion.fade()),
            exit = fadeOut(Motion.fade()),
            boundsTransform = HeroBounds,
            clipInOverlayDuringTransition = OverlayClip(brandTileShape(size)),
        )
    }
}

/**
 * The whole Sign Up / Log In screen as the zoom's destination (`onboardingHeroDestination`,
 * `:28-31, 46-56`): it scales out of the mark's frame (rounded corners opening up to the window) and
 * back into it.
 */
fun Modifier.onboardingHeroDestination(): Modifier = composed {
    val hero = LocalOnboardingHero.current
    if (hero == null || ShroudTheme.reduceMotion) return@composed this
    with(hero.sharedTransitionScope) {
        this@composed.sharedBounds(
            rememberSharedContentState(OnboardingHeroId.BRAND),
            hero.animatedVisibilityScope,
            enter = fadeIn(Motion.fade()),
            exit = fadeOut(Motion.fade()),
            boundsTransform = HeroBounds,
            clipInOverlayDuringTransition = OverlayClip(HeroZoomShape),
        )
    }
}

/** The zoom's bounds ride `Motion.standard`, as iOS pushes onboarding with it (`AppRouter.swift:84-94`). */
private val HeroBounds = BoundsTransform { _: Rect, _: Rect -> Motion.standard<Rect>() as FiniteAnimationSpec<Rect> }

/**
 * The clip of the growing screen: the 80 dp tile's 22.4 dp corners at the mark's size, opening up
 * as the frame grows ([heroCornerRadius]).
 */
private val HeroZoomShape: Shape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { heroCornerRadius(size.minDimension / density.density).dp.toPx() }
        return RoundedCornerShape(with(density) { radius.toDp() }).createOutline(size, layoutDirection, density)
    }
}

/**
 * Corner radius in dp of the zooming frame whose smaller side is [minSideDp]: the brand tile's 28 %
 * up to 80 dp (22.4 dp, the Welcome mark exactly), then shrinking with the size (≈ 4 dp at a phone's
 * width), so the window's corners are nearly square when the overlay hands over.
 */
fun heroCornerRadius(minSideDp: Float): Float {
    if (minSideDp <= 0f) return 0f
    val tile = 0.28f * minSideDp
    val opening = BRAND_RADIUS_DP * BRAND_SIZE_DP / minSideDp
    return min(tile, opening)
}

private const val BRAND_SIZE_DP = 80f
private const val BRAND_RADIUS_DP = 0.28f * BRAND_SIZE_DP
