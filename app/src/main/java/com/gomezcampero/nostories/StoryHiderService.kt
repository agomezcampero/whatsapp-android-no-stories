package com.gomezcampero.nostories

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
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
        const val TAG = "NoStories"

        /**
         * A window change arrives before WhatsApp has finished laying the
         * screen out, so the row is often not there yet, and coming back from
         * the background can take a while to settle. Look again a few times,
         * stopping as soon as the row turns up: one pass at a fixed delay was
         * not enough, and if nothing else moves on screen there is no other
         * event to ride on.
         */
        val SETTLE_DELAYS_MS = longArrayOf(200L, 700L, 1500L)

        /** How often a working cover re-describes itself for the report. */
        const val COVER_REPORT_INTERVAL_MS = 1000L
    }

    /**
     * What a lookup established. [Unknown] is the important one: it means we
     * did not actually look this time, which is not the same as the row being
     * gone. Treating the two alike makes the cover blink, because WhatsApp
     * sends content events far faster than the scan is allowed to run.
     */
    private sealed interface Lookup {
        data class Found(val bounds: Rect) : Lookup
        data object Gone : Lookup
        data object Unknown : Lookup
    }

    private var overlay: OverlayController? = null
    private var sampler: BackgroundSampler? = null

    /** The row we found last time, re-read with refresh() instead of a walk. */
    private var cachedRow: AccessibilityNodeInfo? = null

    private var lastDeepScanAt = 0L
    private var lastCoverReportAt = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val settle = Runnable { runSettlePass() }
    private var settlePass = 0

    private var screen = Rect()

    override fun onServiceConnected() {
        overlay = OverlayController(this)
        sampler = BackgroundSampler(this)
        cachedRow = null
        screen = displayBounds()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val overlay = overlay ?: return
        val eventPackage = event?.packageName ?: return

        // Our own overlay window must never be read as "some other app is on
        // top now", or showing the cover would immediately hide it again.
        if (packageName.contentEquals(eventPackage)) return

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

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) armSettlePasses()

        val deepScanAllowed = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            System.currentTimeMillis() - lastDeepScanAt >= DEEP_SCAN_INTERVAL_MS

        when (val lookup = lookUpRow(root, deepScanAllowed)) {
            is Lookup.Found -> overlay.show(lookup.bounds)
            Lookup.Gone -> {
                // A row that vanishes while the cover is up is usually
                // WhatsApp rebuilding the list rather than the row going
                // away, so take the cover down but go back and check.
                val wasCovered = overlay.isShowing()
                overlay.hide()
                if (wasCovered) armSettlePasses()
            }
            // Leave the cover exactly where it is until we actually know.
            Lookup.Unknown -> Unit
        }
    }

    private fun armSettlePasses() {
        handler.removeCallbacks(settle)
        settlePass = 0
        handler.postDelayed(settle, SETTLE_DELAYS_MS[0])
    }

    /** Looks again while the screen settles, giving up once the row is found. */
    private fun runSettlePass() {
        val found = recheck()
        settlePass++
        if (!found && settlePass < SETTLE_DELAYS_MS.size) {
            handler.postDelayed(settle, SETTLE_DELAYS_MS[settlePass])
        }
    }

    /** A fresh look, ignoring the scan throttle. True once the cover is up. */
    private fun recheck(): Boolean {
        val overlay = overlay ?: return false
        val root = rootInActiveWindow ?: return false
        if (!WHATSAPP.contentEquals(root.packageName ?: "")) return false

        return when (val lookup = lookUpRow(root, deepScanAllowed = true)) {
            is Lookup.Found -> {
                overlay.show(lookup.bounds)
                true
            }
            Lookup.Gone -> {
                overlay.hide()
                false
            }
            Lookup.Unknown -> false
        }
    }

    private fun lookUpRow(root: AccessibilityNodeInfo, deepScanAllowed: Boolean): Lookup {
        cachedRow?.let { row ->
            if (row.refresh()) {
                val bounds = StatusRowLocator.boundsOf(row)
                if (row.isVisibleToUser && StatusRowLocator.isPlausible(bounds, screen)) {
                    return found(row, root)
                }
                // The node is still there and we have just read its current
                // bounds: it is not showing a row now. That is an answer, not
                // a shrug. Answering Unknown here is what left the cover
                // parked over the toolbar once the row scrolled up behind it.
                cachedRow = null
                return Lookup.Gone
            }
            cachedRow = null
        }

        // The row is only ever resolved by a known id. The shape and label
        // passes below no longer decide what to cover - they had no way to
        // tell the row from a toolbar of icons, and the cost of being wrong
        // is covering a button you need. They diagnose instead.
        StatusRowLocator.findBySeedId(root, screen)?.let {
            cachedRow = it
            return found(it, root)
        }

        // Missing the id tells us nothing on its own - the row may simply have
        // been renamed - so without a scan we have no answer, and saying
        // "gone" here is what used to make the cover blink.
        if (!deepScanAllowed) return Lookup.Unknown

        if (StatusRowLocator.looksLikeChatsScreen(root)) reportWhatIsOnScreen(root)
        return Lookup.Gone
    }

    /**
     * Records what the Chats screen looks like when no known id resolved,
     * which is how a renamed row gets found again. Nothing here covers
     * anything: it names a candidate, and that name goes in ROW_VIEW_IDS.
     */
    private fun reportWhatIsOnScreen(root: AccessibilityNodeInfo) {
        lastDeepScanAt = System.currentTimeMillis()

        val candidates = mutableListOf<String>()
        val others = mutableListOf<String>()
        var foundBy = "shape"
        val row = StatusRowLocator.findByStructure(root, screen) { rowShaped, line ->
            (if (rowShaped) candidates else others).add(line)
        } ?: StatusRowLocator.findByLabel(root, screen)?.also { foundBy = "label" }

        Diagnostics.write(
            context = this,
            header = listOf(
                "screen=$screen",
                "no known id resolved - nothing was covered",
                "best guess=${row?.viewIdResourceName ?: "nothing"} " +
                    "bounds=${row?.let(StatusRowLocator::boundsOf) ?: "-"}",
                "guessed by=${if (row == null) "-" else foundBy}",
            ),
            candidates = candidates,
            others = others,
        )
        Log.d(TAG, "no known status row id on screen; best guess ${row?.viewIdResourceName}")
    }

    /**
     * Works out what to cover, and every so often writes down how it got
     * there. Reporting the working case matters as much as reporting the
     * broken one: whether the collapsed row is still made of these tiles is
     * exactly what decides if covering the tiles is the right idea.
     */
    private fun found(row: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Lookup.Found {
        val cover = StatusRowLocator.coverBounds(row, root)

        sampler?.sample(StatusRowLocator.boundsOf(row), screen) { color ->
            overlay?.useSampledColor(color)
        }

        val now = System.currentTimeMillis()
        if (now - lastCoverReportAt >= COVER_REPORT_INTERVAL_MS) {
            lastCoverReportAt = now
            Diagnostics.write(
                context = this,
                header = listOf(
                    "screen=$screen",
                    "covering by id=${row.viewIdResourceName}",
                    "row bounds=${StatusRowLocator.boundsOf(row)}",
                    "cover bounds=$cover",
                    "row children=${row.childCount} scrollable=${row.isScrollable}",
                    "row actions=${StatusRowLocator.describeActions(row)}",
                ),
                candidates = StatusRowLocator.describeTiles(row),
            )
        }
        return Lookup.Found(cover)
    }

    private fun forgetRow() {
        cachedRow = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation moves the row; night mode changes the colour it should be.
        screen = displayBounds()
        forgetRow()
        // The background behind the row has probably changed with the theme.
        overlay?.useSampledColor(null)
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
        handler.removeCallbacks(settle)
        overlay?.hide()
        forgetRow()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(settle)
        overlay?.hide()
        overlay = null
        forgetRow()
        super.onDestroy()
    }
}
