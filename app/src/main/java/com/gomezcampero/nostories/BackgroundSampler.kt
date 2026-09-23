package com.gomezcampero.nostories

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.view.Display

/**
 * Reads the colour WhatsApp is drawing behind the status row, so the cover can
 * be painted that exact colour instead of a guess from colors.xml.
 *
 * This matters more than it sounds: the cover can never be perfectly in step
 * with a view it only hears about through accessibility events, but a
 * rectangle of precisely the right colour, a little out of place over plain
 * background, cannot be seen. What gives a cover away is overlapping content,
 * and that is handled by keeping clear of the controls instead.
 *
 * The system rate limits screenshots to about one a second, so this samples
 * rarely - on connecting, on a theme change, and when the cover first appears
 * - and holds on to what it read.
 */
class BackgroundSampler(private val service: AccessibilityService) {

    private companion object {
        const val TAG = "NoStories"
        const val MIN_INTERVAL_MS = 1500L

        /** How far above the row to read, clear of the avatars themselves. */
        const val ABOVE_ROW_PX = 8
    }

    private var lastAttemptAt = 0L

    /** Samples behind [row], handing the colour to [onColor] if it works. */
    fun sample(row: Rect, screen: Rect, onColor: (Int) -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastAttemptAt < MIN_INTERVAL_MS) return
        lastAttemptAt = now

        // Just above the row, where the header's own background shows.
        val x = (row.centerX()).coerceIn(0, screen.width() - 1)
        val y = (row.top - ABOVE_ROW_PX).coerceIn(0, screen.height() - 1)

        runCatching {
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        readPixel(result, x, y)?.let(onColor)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.d(TAG, "screenshot for colour failed: $errorCode")
                    }
                },
            )
        }
    }

    private fun readPixel(result: AccessibilityService.ScreenshotResult, x: Int, y: Int): Int? {
        val buffer = result.hardwareBuffer
        return try {
            // A hardware bitmap cannot be read directly, so copy one pixel out.
            val screenshot = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            if (x >= screenshot.width || y >= screenshot.height) return null
            val readable = screenshot.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val color = readable.getPixel(x, y)
            readable.recycle()
            color
        } catch (e: Exception) {
            Log.d(TAG, "could not read a pixel: $e")
            null
        } finally {
            buffer.close()
        }
    }
}
