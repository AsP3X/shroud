package de.corespace.shroud.ui.media.compose

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.media.edit.FilterRecipe
import de.corespace.shroud.core.media.edit.MediaEditRenderer
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter
import de.corespace.shroud.core.model.Haptic
import de.corespace.shroud.ui.components.ShroudSlider
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.components.SliderDefaults
import de.corespace.shroud.ui.components.pressable
import de.corespace.shroud.ui.theme.MediaColors
import de.corespace.shroud.ui.theme.Motion
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter
import de.corespace.shroud.ui.theme.rememberHaptics
import kotlin.math.floor

/** Thumbnails are rendered from a copy this small, so the strip never touches the full preview (`MediaFilterStrip.swift:97-115`). */
internal const val FILTER_THUMB_EDGE = 160

/** The strip's presets: [MediaEditRenderer.filters] in their order, matched to [MediaFilter] by id. */
internal fun filterChoices(recipes: List<FilterRecipe>): List<Pair<MediaFilter, String>> =
    recipes.mapNotNull { recipe -> MediaFilter.entries.firstOrNull { it.name == recipe.id }?.let { it to recipe.title } }

/** "{intensity·100 rounded down}%" (`MediaFilterStrip.swift:32`). */
internal fun intensityLabel(intensity: Float): String = "${floor(intensity * 100f + 1e-4f).toInt()}%"

/**
 * The live filter picker (conversation-compose-media §14; iOS `MediaFilterStrip`): an intensity
 * slider while a filter is on, then a tile per preset showing the photo through it.
 *
 * [image] is the photo's raw preview; the thumbnails are rendered once per image off the main
 * thread through [renderer] (the same recipes the send bakes), each at full intensity from a
 * 160 px copy. Picking a preset (other than Original) sets the intensity back to 100 %.
 */
@Composable
internal fun MediaFilterStrip(
    image: Bitmap,
    renderer: MediaEditRenderer,
    filter: MediaFilter,
    intensity: Float,
    onFilter: (MediaFilter) -> Unit,
    onIntensity: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    val reduce = ShroudTheme.reduceMotion
    val choices = remember(renderer) { filterChoices(renderer.filters) }
    var thumbnails by remember { mutableStateOf<Map<MediaFilter, Bitmap>>(emptyMap()) }
    LaunchedEffect(image, renderer) {
        val small = renderer.preview(image, MediaEdits.Identity, FILTER_THUMB_EDGE)
        val rendered = HashMap<MediaFilter, Bitmap>()
        for ((preset, _) in choices) rendered[preset] = renderer.preview(small, MediaEdits(filter = preset), FILTER_THUMB_EDGE)
        thumbnails = rendered
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AnimatedVisibility(
            visible = filter != MediaFilter.None,
            enter = if (reduce) fadeIn(Motion.reduced()) else fadeIn(Motion.standard()) + slideInVertically(Motion.standard()) { it },
            exit = if (reduce) fadeOut(Motion.reduced()) else fadeOut(Motion.standard()) + slideOutVertically(Motion.standard()) { it },
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The captions are for the eye; TalkBack reads the slider's own name and value.
                ShroudText("Intensity", inter(12f, FontWeight.SemiBold), Color.White.copy(alpha = 0.7f), Modifier.clearAndSetSemantics {})
                ShroudSlider(
                    value = intensity,
                    onValueChange = onIntensity,
                    label = "Filter intensity",
                    modifier = Modifier.weight(1f),
                    tint = MediaColors.blue,
                    trackColor = SliderDefaults.trackColor(dark = true),
                )
                ShroudText(
                    intensityLabel(intensity),
                    inter(12f, FontWeight.SemiBold, tabularDigits = true),
                    Color.White.copy(alpha = 0.7f),
                    Modifier.width(40.dp).clearAndSetSemantics {},
                    textAlign = TextAlign.End,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for ((preset, title) in choices) {
                FilterTile(
                    title = title,
                    thumbnail = thumbnails[preset],
                    selected = preset == filter,
                ) {
                    haptic(Haptic.Light)
                    onFilter(preset)
                }
            }
        }
    }
}

/**
 * One preset: a 58 dp tile radius 10 (`chrome` until its thumbnail lands), ringed 2.5 dp `blue` and
 * grown to 1.06 when chosen, over a 10 sp semibold label, `blue` when chosen else white 70 %
 * (`MediaFilterStrip.swift:46-83`).
 */
@Composable
private fun FilterTile(title: String, thumbnail: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val grown by animateFloatAsState(if (selected) 1.06f else 1f, Motion.snappy(), label = "filterTile")
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .pressable(scale = 0.9f, dimming = 0f, haptic = Haptic.None, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = title
                if (selected) this.selected = true
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .scale(grown)
                .size(58.dp)
                .clip(shape)
                .background(MediaColors.chrome)
                .border(2.5.dp, if (selected) MediaColors.blue else Color.Transparent, shape),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        ShroudText(
            title,
            inter(10f, FontWeight.SemiBold),
            if (selected) MediaColors.blue else Color.White.copy(alpha = 0.7f),
            Modifier.clearAndSetSemantics {},
        )
    }
}
