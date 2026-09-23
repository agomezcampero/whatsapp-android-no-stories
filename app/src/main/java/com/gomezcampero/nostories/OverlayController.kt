package com.gomezcampero.nostories

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Owns the single view that covers the status row: a rectangle painted in the
 * toolbar colour that also swallows taps, so a story can't be opened by
 * accident.
 *
 * It is a TYPE_ACCESSIBILITY_OVERLAY window, which an accessibility service may
 * add without the "display over other apps" permission.
 */
class OverlayController(private val context: Context) {

    private companion object {
        /** Translucent red: obvious, and you can read what is under it. */
        const val SEE_THROUGH_COLOR = 0x60FF0000
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val options = DebugOptions(context)

    private var overlay: View? = null
    private val shownBounds = Rect()

    /** Shows the cover over [bounds], moving it only when [bounds] changed. */
    fun show(bounds: Rect) {
        val existing = overlay
        if (existing == null) {
            val view = View(context).apply {
                setBackgroundColor(coverColor())
                // A clickable view consumes the taps that land on it.
                isClickable = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // addView throws if the service is on its way out.
            runCatching { windowManager.addView(view, layoutFor(bounds)) }
                .onSuccess {
                    overlay = view
                    shownBounds.set(bounds)
                }
            return
        }

        // Cheap, and the only way a flipped debug switch reaches the screen.
        existing.setBackgroundColor(coverColor())

        if (shownBounds == bounds) return
        runCatching { windowManager.updateViewLayout(existing, layoutFor(bounds)) }
            .onSuccess { shownBounds.set(bounds) }
    }

    fun hide() {
        val view = overlay ?: return
        overlay = null
        shownBounds.setEmpty()
        runCatching { windowManager.removeView(view) }
    }

    /** Repaints after a light/dark mode switch. */
    fun refreshColor() {
        overlay?.setBackgroundColor(coverColor())
    }

    private fun layoutFor(bounds: Rect) = WindowManager.LayoutParams(
        bounds.width(),
        bounds.height(),
        bounds.left,
        bounds.top,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        // Translucent throughout, or the see-through colour's alpha is ignored.
        PixelFormat.TRANSLUCENT,
    ).apply {
        // Screen coordinates, to match AccessibilityNodeInfo.getBoundsInScreen.
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = context.getString(R.string.overlay_window_title)
    }

    /** Resolved per call so values-night and the debug switch both apply. */
    private fun coverColor() =
        if (options.seeThrough) SEE_THROUGH_COLOR else context.getColor(R.color.story_row_cover)
}
