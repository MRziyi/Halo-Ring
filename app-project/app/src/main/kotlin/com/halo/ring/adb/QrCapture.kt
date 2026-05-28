package com.halo.ring.adb

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay

/**
 * Captures the screen via [MediaProjection] and scans frames for the Android wireless ADB
 * pairing QR code (`WIFI:T:ADB;S:<mDNS-instance>;P:<SPAKE2-password>;;`).
 *
 * Why this exists instead of the overlay approach: Settings declares
 * `HIDE_NON_SYSTEM_OVERLAY_WINDOWS`, which causes Android to suppress all
 * `TYPE_APPLICATION_OVERLAY` windows from non-system apps while Settings is in the
 * foreground — addView() succeeds silently, but the content is never rendered.
 * MediaProjection captures the rendered framebuffer and is unaffected by that restriction.
 *
 * On Rokid YodaOS (Android 12) the one-token-per-session enforcement that Android 14
 * added for MediaProjection does not apply, so this works with a single consent dialog.
 *
 * The caller is responsible for calling [release] when done (success or failure).
 */
class QrCapture(context: Context, private val projection: MediaProjection) {

    data class AdbQrPayload(val serviceName: String, val password: String)

    private val metrics = context.resources.displayMetrics
    private val reader = ImageReader.newInstance(
        metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 3)
    private val display = projection.createVirtualDisplay(
        "halo_qr_scan",
        metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface, null, null)

    /**
     * Poll frames every 400 ms until a valid ADB QR code is decoded or [timeoutMs] elapses.
     * Returns null on timeout (caller should surface an error and call [release]).
     */
    suspend fun scanUntilFound(timeoutMs: Long = 25_000): AdbQrPayload? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val image = reader.acquireLatestImage()
            if (image != null) {
                val payload = runCatching { decodeFrame(image) }.getOrNull()
                image.close()
                if (payload != null) {
                    Log.i(TAG, "QR decoded: service=${payload.serviceName}")
                    return payload
                }
            }
            delay(400)
        }
        Log.w(TAG, "QR scan timed out after ${timeoutMs}ms")
        return null
    }

    fun release() {
        try { display.release() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
        try { projection.stop() } catch (_: Exception) {}
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────────

    private fun decodeFrame(image: android.media.Image): AdbQrPayload? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = image.width
        val h = image.height
        val buf = plane.buffer

        // Build ARGB int[] for ZXing's RGBLuminanceSource, handling rowStride padding.
        val pixels = IntArray(w * h)
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until h) {
            buf.position(row * rowStride)
            val readable = rowStride.coerceAtMost(buf.remaining())
            buf.get(rowBytes, 0, readable)
            for (col in 0 until w) {
                val off = col * pixelStride
                val r = rowBytes[off].toInt() and 0xFF
                val g = rowBytes[off + 1].toInt() and 0xFF
                val b = rowBytes[off + 2].toInt() and 0xFF
                pixels[row * w + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val src = RGBLuminanceSource(w, h, pixels)
        val bmp = BinaryBitmap(HybridBinarizer(src))
        val text = try {
            MultiFormatReader().decode(bmp).text
        } catch (_: NotFoundException) {
            return null
        }
        return parsePayload(text)
    }

    companion object {
        private const val TAG = "QrCapture"

        // Format: WIFI:T:ADB;S:<service-instance>;P:<spake2-password>;;
        // S = mDNS instance name advertised on _adb-tls-pairing._tcp.
        // P = SPAKE2 pairing password (passed to AdbBootstrap.pairWithCode)
        fun parsePayload(qrText: String): AdbQrPayload? {
            if (!qrText.startsWith("WIFI:T:ADB;")) return null
            val s = qrText.substringAfter("S:", "").substringBefore(";")
            val p = qrText.substringAfter("P:", "").substringBefore(";")
            return if (s.isNotBlank() && p.isNotBlank()) AdbQrPayload(s, p) else null
        }
    }
}
