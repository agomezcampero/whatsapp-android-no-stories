package com.gomezcampero.nostories

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Covers the row of status/story avatars at the top of WhatsApp's Chats screen.
 *
 * Per event the work is: one string comparison, then a refresh of the node we
 * found last time, and a window move only when the row actually moved. The
 * bounded tree scan in [StatusRowLocator.findByLabel] is the last resort and is
 * throttled to [DEEP_SCAN_INTERVAL_MS].
 */
class StoryHiderService : AccessibilityService() {

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val DEEP_SCAN_INTERVAL_MS = 350L
    }

    private var overlay: OverlayController? = null

    /** The row we found last time, re-read with refresh() instead of a walk. */
    private var cachedRow: AccessibilityNodeInfo? = null
    private var lastDeepScanAt = 0L

    private var screen = Rect()

    override fun onServiceConnected() {
        overlay = OverlayController(this)
        cachedRow = null
        screen = displayBounds()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val overlay = overlay ?: return
        val eventPackage = event?.packageName ?: return

        // Cheapest possible filter, and the reason packageNames is not set in
        // accessibility_service_config.xml: we need the window change that
        // fires when WhatsApp goes away, so the cover goes with it.
        if (!WHATSAPP.contentEquals(eventPackage)) {
            forgetRow()
            overlay.hide()
            return
        }

        val root = rootInActiveWindow
        if (root == null || !WHATSAPP.contentEquals(root.packageName ?: "")) {
            // WhatsApp is not the app on top, whatever this event says.
            forgetRow()
            overlay.hide()
            return
        }

        val deepScanAllowed = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            System.currentTimeMillis() - lastDeepScanAt >= DEEP_SCAN_INTERVAL_MS

        val bounds = rowBounds(root, deepScanAllowed)
        if (bounds == null) overlay.hide() else overlay.show(bounds)
    }

    private fun rowBounds(root: AccessibilityNodeInfo, deepScanAllowed: Boolean): Rect? {
        cachedRow?.let { row ->
            if (row.refresh() && row.isVisibleToUser) {
                val bounds = StatusRowLocator.boundsOf(row)
                if (StatusRowLocator.isRow(bounds, screen)) return bounds
            }
            cachedRow = null
        }

        val row = StatusRowLocator.findById(root, screen)
            ?: if (deepScanAllowed) {
                lastDeepScanAt = System.currentTimeMillis()
                StatusRowLocator.findByLabel(root, screen)
            } else {
                null
            }

        cachedRow = row
        return row?.let(StatusRowLocator::boundsOf)
    }

    private fun forgetRow() {
        cachedRow = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation moves the row; night mode changes the colour it should be.
        screen = displayBounds()
        forgetRow()
        overlay?.refreshColor()
    }

    private fun displayBounds(): Rect =
        runCatching { getSystemService(WindowManager::class.java).currentWindowMetrics.bounds }
            .getOrElse {
                // A service is not a visual context; on some devices window
                // metrics are refused. The heuristics only need rough numbers.
                val metrics = resources.displayMetrics
                Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
            }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.hide()
        forgetRow()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay?.hide()
        overlay = null
        forgetRow()
        super.onDestroy()
    }
}
