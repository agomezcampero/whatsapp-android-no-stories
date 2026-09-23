package com.gomezcampero.nostories

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

/**
 * Everything that knows what WhatsApp's status/story row looks like lives here.
 * When a WhatsApp update moves or renames the row, this file is the only one
 * that should need a change.
 *
 * Two strategies, cheapest first:
 *
 *  1. [findById] - a direct view id lookup, which the framework resolves
 *     without us walking the tree.
 *  2. [findByLabel] - a bounded breadth-first scan for an avatar labelled
 *     "Your status" / "Tu estado" etc., then a climb to the row that holds it.
 *
 * The ids in [ROW_VIEW_IDS] are unverified guesses until a real dump lands in
 * docs/wa-dump.xml (see README). Until then strategy 2 does the work; adding
 * the confirmed id makes it a one-call lookup.
 */
object StatusRowLocator {

    /** Candidate resource ids for the avatar row, most specific first. */
    private val ROW_VIEW_IDS = listOf(
        "com.whatsapp:id/status_list",
        "com.whatsapp:id/status_tab_container",
        "com.whatsapp:id/my_status_row",
    )

    /**
     * Words that appear on the row's avatars, in English and Spanish, already
     * lower-cased and stripped of accents to match [normalize].
     */
    private val ROW_LABELS = listOf(
        "your status",
        "my status",
        "status update",
        "add status",
        "tu estado",
        "mi estado",
        "anadir estado",
        "agregar estado",
        "actualizacion de estado",
    )

    /** The row sits in the top bar, never below this fraction of the screen. */
    private const val TOP_REGION = 0.35f

    /**
     * A row of avatars is short, but never a hairline. The upper bound has to
     * stay well under the height of the whole top bar (title, row and search
     * together), or the climb in [rowAround] happily covers all three.
     */
    private const val MIN_HEIGHT = 0.04f
    private const val MAX_HEIGHT = 0.18f

    /**
     * And it is much wider than it is tall. This is what tells a strip of
     * avatars apart from the block of chrome that contains it.
     */
    private const val MIN_ASPECT = 2.5f

    /** It spans most of the width; a single avatar does not. */
    private const val MIN_WIDTH = 0.5f
    private const val FULL_WIDTH = 0.8f

    /** Ancestors to try when climbing from an avatar to the row holding it. */
    private const val MAX_CLIMB = 5

    /** Cap on the fallback scan so a deep tree can never stall an event. */
    private const val MAX_NODES = 800

    private val ACCENTS = "\\p{Mn}+".toRegex()

    /** Resolves the row by view id. Cheap: no tree walk on our side. */
    fun findById(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        for (id in ROW_VIEW_IDS) {
            val matches = root.findAccessibilityNodeInfosByViewId(id)
            val row = matches.firstOrNull { isRow(boundsOf(it), screen) && it.isVisibleToUser }
            if (row != null) return row
        }
        return null
    }

    /** Finds a labelled avatar in the top bar and climbs to the row holding it. */
    fun findByLabel(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            if (isStatusAvatar(node) && boundsOf(node).top < screen.height() * TOP_REGION) {
                rowAround(node, screen)?.let { return it }
            }

            for (i in 0 until node.childCount) {
                queue.addLast(node.getChild(i) ?: continue)
            }
        }
        return null
    }

    /** True while [bounds] still look like the avatar row on this screen. */
    fun isRow(bounds: Rect, screen: Rect): Boolean {
        if (bounds.isEmpty) return false
        if (bounds.top >= screen.height() * TOP_REGION) return false
        if (bounds.width() < screen.width() * MIN_WIDTH) return false
        if (bounds.height() < screen.height() * MIN_HEIGHT) return false
        if (bounds.height() > screen.height() * MAX_HEIGHT) return false
        return bounds.width() >= bounds.height() * MIN_ASPECT
    }

    fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    private fun isStatusAvatar(node: AccessibilityNodeInfo): Boolean {
        val label = normalize(node.contentDescription ?: node.text ?: return false)
        return ROW_LABELS.any { label.contains(it) }
    }

    /**
     * Walks up from an avatar to the container holding the whole row.
     *
     * It stops at the *innermost* ancestor that is row-shaped and spans the
     * screen, because that is the strip itself - keep climbing and the next
     * one up is the block holding the title bar and the search box too, which
     * is wide and near the top and would otherwise pass [isRow].
     */
    private fun rowAround(avatar: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = avatar
        var narrow: AccessibilityNodeInfo? = null
        var hops = 0

        while (node != null && hops <= MAX_CLIMB) {
            val bounds = boundsOf(node)
            if (isRow(bounds, screen)) {
                if (bounds.width() >= screen.width() * FULL_WIDTH) return node
                // Row-shaped but not full width: remember the first one as a
                // fallback, and keep looking for something that spans.
                if (narrow == null) narrow = node
            }
            node = node.parent
            hops++
        }
        return narrow
    }

    private fun normalize(text: CharSequence): String =
        ACCENTS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase()
}
