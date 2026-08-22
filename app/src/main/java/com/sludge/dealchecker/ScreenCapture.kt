package com.sludge.dealchecker

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.util.Log

/**
 * Holds one MediaProjection session and hands back a single frame on demand.
 *
 * The virtual display is created once and kept, so Android only asks for capture consent when the
 * bubble starts rather than on every tap.
 */
class ScreenCapture(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val density: Int,
    private val handler: Handler
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    fun start() {
        if (display != null) return
        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = r
        display = projection.createVirtualDisplay(
            "DealCheckerCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
    }

    /** Grabs the most recent frame. Returns null if the compositor hasn't produced one yet. */
    fun grab(): Bitmap? {
        val r = reader ?: return null
        var image: Image? = null
        try {
            image = r.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(buffer)
            val out = if (padded.width != width) {
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                cropped
            } else padded
            return out
        } catch (e: Exception) {
            Log.w("ScreenCapture", "grab failed: ${e.message}")
            return null
        } finally {
            image?.close()
        }
    }

    fun release() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        display = null
        reader = null
    }
}
