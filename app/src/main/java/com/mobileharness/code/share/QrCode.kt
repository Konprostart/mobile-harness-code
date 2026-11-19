package com.mobileharness.code.share

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Local QR generation via ZXing core (Apache-2.0, no transitive dependencies).
 *
 * Produces a square boolean matrix (`true` = dark module) that already includes
 * a quiet zone, so it can be rendered directly in Compose. It never calls an
 * online API, and it fails closed — returning `null` on blank/oversized input
 * or any encoding error — so the UI can render a fallback instead of a broken
 * or unscannable code.
 */
object QrCode {
    /** Fail-closed payload cap: QR versions stop at 40; bound length to avoid pathological input. */
    private const val MAX_TEXT_LENGTH = 1024
    private const val QUIET_ZONE_MODULES = 4

    /**
     * Encodes [text] into a square boolean matrix, or returns null on invalid
     * input or an encoding failure. [ecLevel] defaults to M (~15% correction),
     * which is plenty for a screen-rendered QR and keeps the code compact.
     */
    fun encode(
        text: String,
        ecLevel: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Array<BooleanArray>? {
        if (text.isBlank() || text.length > MAX_TEXT_LENGTH) return null
        val matrix = try {
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ecLevel,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                    EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
                ),
            )
        } catch (_: Exception) {
            return null
        }
        val width = matrix.width
        val height = matrix.height
        if (width <= 0 || height <= 0 || width != height) return null
        return Array(height) { y -> BooleanArray(width) { x -> matrix.get(x, y) } }
    }
}
