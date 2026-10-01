package de.corespace.shroud.core.contacts

import com.google.zxing.WriterException
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * The modules of a share link's QR code, as iOS draws it (`QRCodeImage.swift:6-17`; contacts §5.7):
 * error correction **M**, the payload's UTF-8 bytes in byte mode with no ECI segment (ZXing's
 * `Encoder` without a `CHARACTER_SET` hint, as CoreImage for an ASCII URL), the smallest version
 * that fits, and a **1-module quiet zone** on every side — CoreImage's output size (a 40-byte
 * official link → version 3, 29 + 2 modules; a link with a port and a 16-character code → version 4,
 * 33 + 2). The mask may differ from CoreImage's, so codes are not pixel-identical; the payload and
 * the level are what count.
 *
 * Pure (ZXing core, no network, no Android types). `QrCodeView` (W3-CONTACTS-UI) draws [toArgb] once
 * per payload at the view size with nearest-neighbour scaling, always dark on white in both themes
 * (inverted codes do not scan everywhere), and shows "Could not create QR" when [encode] is null.
 */
class QrMatrix private constructor(
    /** Modules per side, quiet zone included. */
    val size: Int,
    private val dark: BooleanArray,
) {
    /** Whether the module at column [x], row [y] is dark; the quiet zone is light. */
    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until size && y in 0 until size) { "module out of range" }
        return dark[y * size + x]
    }

    /**
     * ARGB pixels, row-major, [scale] pixels per module ([size] × [scale] square), for
     * `Bitmap.createBitmap(pixels, side, side, ARGB_8888)`.
     */
    fun toArgb(scale: Int = 1, darkColor: Int = BLACK, lightColor: Int = WHITE): IntArray {
        require(scale >= 1) { "scale must be at least 1" }
        val side = size * scale
        val pixels = IntArray(side * side)
        for (py in 0 until side) {
            val row = (py / scale) * size
            for (px in 0 until side) pixels[py * side + px] = if (dark[row + px / scale]) darkColor else lightColor
        }
        return pixels
    }

    companion object {
        /** Light modules around the symbol (CoreImage's margin). */
        const val QUIET_ZONE = 1
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        /** The code for [payload], or null when it cannot be encoded (empty or too long for level M). */
        fun encode(payload: String): QrMatrix? {
            if (payload.isEmpty()) return null
            val code = try {
                Encoder.encode(payload, ErrorCorrectionLevel.M)
            } catch (_: WriterException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
            val symbol = code.matrix ?: return null
            val size = symbol.width + 2 * QUIET_ZONE
            val dark = BooleanArray(size * size)
            for (y in 0 until symbol.height) {
                for (x in 0 until symbol.width) {
                    if (symbol.get(x, y).toInt() == 1) dark[(y + QUIET_ZONE) * size + x + QUIET_ZONE] = true
                }
            }
            return QrMatrix(size, dark)
        }
    }
}
