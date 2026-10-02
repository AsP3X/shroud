package de.corespace.shroud.ui.contacts

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.corespace.shroud.core.contacts.QrMatrix
import de.corespace.shroud.ui.components.ShroudText
import de.corespace.shroud.ui.theme.ShroudTheme
import de.corespace.shroud.ui.theme.inter

/**
 * A plain QR code of [payload] (iOS `QRCodeView`, `ios/shroud/ShroudUI/Components/QRCodeImage.swift:19-46`;
 * contacts §5.7): the [QrMatrix] modules (level M, 1-module quiet zone) as a one-pixel-per-module
 * bitmap built once per payload, scaled to [size] without filtering (iOS `.interpolation(.none)`),
 * inside 12 dp of white, clipped to a 16 dp rounded square — dark on white in both themes (inverted
 * codes do not scan everywhere). When the payload cannot be encoded: a [size] square of
 * `backgroundGrouped` saying "Could not create QR". TalkBack: "QR code".
 */
@Composable
fun QrCodeView(payload: String, modifier: Modifier = Modifier, size: Dp = 220.dp) {
    val image = remember(payload) { qrImage(payload) }
    val shape = RoundedCornerShape(16.dp)
    val described = Modifier.clearAndSetSemantics { contentDescription = ContactsCopy.QR_CODE }
    if (image != null) {
        Box(
            modifier
                .then(described)
                .clip(shape)
                .background(Color.White)
                .padding(12.dp),
        ) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.None,
            )
        }
    } else {
        val colors = ShroudTheme.colors
        Box(
            modifier
                .then(described)
                .size(size)
                .clip(shape)
                .background(colors.backgroundGrouped),
            contentAlignment = Alignment.Center,
        ) {
            ShroudText(ContactsCopy.QR_FAILED, inter(13f), colors.textSecondary)
        }
    }
}

/** One pixel per module; null when [payload] cannot be encoded. */
private fun qrImage(payload: String): ImageBitmap? {
    val matrix = QrMatrix.encode(payload) ?: return null
    return try {
        Bitmap.createBitmap(matrix.toArgb(), matrix.size, matrix.size, Bitmap.Config.ARGB_8888).asImageBitmap()
    } catch (_: IllegalArgumentException) {
        null
    }
}
