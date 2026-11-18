package com.mobileharness.code.sidekick

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Android-specific I/O for building Sidekick attachments from a content URI or a freshly captured
 * bitmap. The heavy lifting (image decode/scale, text extraction) is deliberately kept here so the
 * pure, unit-tested classification and message-building logic in [SidekickInput] stays JVM-only.
 */
object SidekickAttachmentIO {
    /** Reads the display name and MIME type declared by a content URI's provider. */
    fun metadata(resolver: ContentResolver, uri: Uri): Pair<String, String> {
        var name = "attachment"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 }
                    ?.let { name = cursor.getString(it) ?: name }
            }
        }
        val mime = resolver.getType(uri).orEmpty()
        return name to mime
    }

    /** Decodes an image URI with power-of-two subsampling so a huge photo cannot OOM the app. */
    fun decodeImage(resolver: ContentResolver, uri: Uri, targetDim: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetDim || bounds.outHeight / (sample * 2) >= targetDim) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    /** Downscales and JPEG-compresses a bitmap into a base64 string (no data-URL prefix). */
    fun bitmapToBase64(bitmap: Bitmap, maxDim: Int = 1024, quality: Int = 82): String {
        val scaled = scaleDown(bitmap, maxDim)
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Reads a text file, capping the number of bytes so a giant log cannot exhaust memory. */
    fun readTextFile(resolver: ContentResolver, uri: Uri, maxBytes: Int): String {
        val input = resolver.openInputStream(uri) ?: return ""
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val count = stream.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                if (count < 0) break
                out.write(chunk, 0, count)
                total += count
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxDim: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (max(width, height) <= maxDim) return bitmap
        val scale = maxDim.toFloat() / max(width, height)
        return Bitmap.createScaledBitmap(
            bitmap,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}
