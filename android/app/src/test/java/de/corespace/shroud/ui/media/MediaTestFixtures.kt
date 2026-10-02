package de.corespace.shroud.ui.media

import android.graphics.Bitmap
import android.graphics.Color
import de.corespace.shroud.core.media.MediaImageSource
import de.corespace.shroud.core.media.edit.FilterRecipe
import de.corespace.shroud.core.media.edit.MediaEditRenderer
import de.corespace.shroud.core.media.edit.MediaEdits
import de.corespace.shroud.core.media.edit.MediaFilter

/** A solid bitmap ([width] × [height]); Robolectric draws real pixels under `GraphicsMode.NATIVE`. */
internal fun solidBitmap(width: Int = 40, height: Int = 30, color: Int = Color.GRAY): Bitmap =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

/** A picked photo whose preview is a small solid bitmap; the source is never read here. */
internal fun pickedPhoto(width: Int = 40, height: Int = 30): PickedPhoto {
    val preview = solidBitmap(width, height)
    return PickedPhoto(preview = preview, source = MediaImageSource.Decoded(preview))
}

/**
 * K11 stand-in: records every preview request and answers with a fresh bitmap of the same size
 * (so a rendered preview is told apart from the raw one), synchronously on the caller's thread.
 * [filters] are core's ids with the titles given, to show the strip takes its names from here.
 */
internal class FakeEditRenderer(
    override val filters: List<FilterRecipe> = MediaFilter.entries.map { FilterRecipe(it.name, it.label) },
) : MediaEditRenderer {
    val previews = mutableListOf<Pair<MediaEdits, Int>>()

    override fun render(image: Bitmap, edits: MediaEdits): Bitmap = image

    override suspend fun preview(source: Bitmap, edits: MediaEdits, maxEdge: Int): Bitmap {
        previews += edits to maxEdge
        return if (edits.isIdentity) source else solidBitmap(source.width, source.height, Color.DKGRAY)
    }
}
