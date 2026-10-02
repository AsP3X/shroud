package de.corespace.shroud.core.media.edit

import android.graphics.ColorMatrix

/**
 * Filter looks as 4×5 sRGB matrices (conversation-compose-media §10.3). iOS uses Core Image;
 * these are the Android stand-ins, kept in one place so a later pass can fit them to iOS swatches.
 * Intensity is a linear mix back toward identity, the same shape as `CIDissolveTransition`.
 */
internal object FilterRecipes {
    fun matrix(filter: MediaFilter, intensity: Float): ColorMatrix? {
        if (filter == MediaFilter.None || intensity <= 0.001f) return null
        val full = recipe(filter)
        if (intensity >= 0.999f) return full
        val src = full.array.copyOf()
        val mixed = FloatArray(IDENTITY.size)
        for (i in IDENTITY.indices) mixed[i] = IDENTITY[i] + (src[i] - IDENTITY[i]) * intensity
        return ColorMatrix(mixed)
    }

    private fun recipe(filter: MediaFilter): ColorMatrix = when (filter) {
        MediaFilter.None -> ColorMatrix()
        MediaFilter.Vivid -> saturated(1.45f).also { it.postConcat(contrast(1.12f)) }
        MediaFilter.Warm -> gains(1.08f, 1.02f, 0.88f)
        MediaFilter.Cool -> gains(0.90f, 0.99f, 1.12f)
        MediaFilter.Fade -> saturated(0.75f).also { it.postConcat(fadeTone()) }
        MediaFilter.Dramatic -> saturated(0.9f).also {
            it.postConcat(contrast(1.10f))
            it.postConcat(gains(1.06f, 1f, 0.90f))
        }
        MediaFilter.Mono -> saturated(0f)
        MediaFilter.Noir -> saturated(0f).also { it.postConcat(contrast(1.35f)) }
    }

    private fun saturated(saturation: Float) = ColorMatrix().apply { setSaturation(saturation) }

    /** Scale about mid-grey (128), the sRGB stand-in for `CIColorControls` contrast. */
    private fun contrast(scale: Float): ColorMatrix {
        val offset = 128f * (1f - scale)
        return ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, offset,
                0f, scale, 0f, 0f, offset,
                0f, 0f, scale, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
    }

    private fun gains(red: Float, green: Float, blue: Float) = ColorMatrix(
        floatArrayOf(
            red, 0f, 0f, 0f, 0f,
            0f, green, 0f, 0f, 0f,
            0f, 0f, blue, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    /** `x·0.82 + 26`, plus 4 on red (`CIPhotoEffectFade` stand-in). */
    private fun fadeTone() = ColorMatrix(
        floatArrayOf(
            0.82f, 0f, 0f, 0f, 30f,
            0f, 0.82f, 0f, 0f, 26f,
            0f, 0f, 0.82f, 0f, 26f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )

    private val IDENTITY = floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}
