package com.gomezcampero.nostories

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/**
 * Everything that knows what WhatsApp's status/story row looks like lives here.
 * When a WhatsApp update moves or renames the row, this file is the only one
 * that should need a change.
 *
 * Three strategies, cheapest first:
 *
 *  1. [findByViewId] - the id learned on this device (see [RowIdMemory]).
 *     One call, no tree walk, and the usual case after the first sighting.
 *  2. [findByStructure] - what the row *is*, rather than what it says: a strip
 *     near the top holding several tiles of about the same width, sitting on a
 *     shared baseline, side by side, at least a couple of them clickable. No
 *     text is involved, so this works whatever language the phone is in and
 *     survives WhatsApp rewording its labels.
 *  3. [findByLabel] - a last resort that looks for an avatar described as
 *     "Your status" / "Tu estado" and climbs to the row holding it. Only earns
 *     its keep when the row is down to a single tile, which the shape pass
 *     deliberately will not match.
 *
 * Whatever finds the row, the caller stores its id, so the next run is back to
 * strategy 1.
 */
object StatusRowLocator {

    /** The row sits in the top bar, never below this fraction of the screen. */
    private const val TOP_REGION = 0.35f

    /**
     * A row of avatars is short, but never a hairline. The upper bound has to
     * stay well under the height of the whole top bar (title, row and search
     * together), or a climb happily covers all three.
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

    /** How many tiles make a row. Two is enough, and one is not a strip. */
    private const val MIN_TILES = 2

    /** At least this many of them must be tappable, or it is not the row. */
    private const val MIN_CLICKABLE_TILES = 2

    /** Tiles start at the same height, give or take this much of their own. */
    private const val BASELINE_TOLERANCE = 0.25f

    /** And are about as wide as each other, give or take this much. */
    private const val WIDTH_TOLERANCE = 0.4f

    /** Neighbours may overlap by this much of a tile before it stops being a row. */
    private const val OVERLAP_TOLERANCE = 0.25f

    /** An avatar with a name under it: roughly square, or taller than wide. */
    private const val MIN_TILE_ASPECT = 0.3f
    private const val MAX_TILE_ASPECT = 2.5f

    /** A horizontally scrollable strip is the likeliest candidate of all. */
    private const val SCROLLABLE_BONUS = 3
    private const val MAX_TILE_SCORE = 5

    /** Ancestors to try when climbing from an avatar to the row holding it. */
    private const val MAX_CLIMB = 5

    /** Cap on the scans so a deep tree can never stall an event. */
    private const val MAX_NODES = 800

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

    private val ACCENTS = "\\p{Mn}+".toRegex()

    /** Resolves a known id. Cheap: no tree walk on our side. */
    fun findByViewId(root: AccessibilityNodeInfo, id: String, screen: Rect): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)
            .firstOrNull { it.isVisibleToUser && isRow(boundsOf(it), screen) }

    /**
     * Finds the row by its shape: the best-scoring strip of side-by-side tiles
     * in the top band of the screen.
     */
    fun findByStructure(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        forEachNodeInTopBand(root, screen) { node, bounds ->
            if (isRow(bounds, screen) && holdsTiles(node)) {
                val score = scoreOf(node)
                val tighter = best != null && bounds.height() < boundsOf(best!!).height()
                if (score > bestScore || (score == bestScore && tighter)) {
                    best = node
                    bestScore = score
                }
            }
        }
        return best
    }

    /** Finds a labelled avatar in the top bar and climbs to the row holding it. */
    fun findByLabel(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? {
        forEachNodeInTopBand(root, screen) { node, _ ->
            if (isStatusAvatar(node)) rowAround(node, screen)?.let { return it }
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

    /**
     * Breadth-first over everything that starts inside the top band, capped at
     * [MAX_NODES]. Anything starting below the band can neither be the row nor
     * contain it, so the chat list - the bulk of the tree - is never walked.
     */
    private inline fun forEachNodeInTopBand(
        root: AccessibilityNodeInfo,
        screen: Rect,
        visit: (AccessibilityNodeInfo, Rect) -> Unit,
    ) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++

            val bounds = boundsOf(node)
            if (bounds.top >= screen.height() * TOP_REGION) continue

            visit(node, bounds)

            for (i in 0 until node.childCount) {
                queue.addLast(node.getChild(i) ?: continue)
            }
        }
    }

    /**
     * True when this node's children look like a row of avatars: enough of
     * them, tappable, tile-shaped, level with each other and side by side.
     */
    private fun holdsTiles(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount < MIN_TILES) return false

        val tiles = ArrayList<Rect>(node.childCount)
        var clickable = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val bounds = boundsOf(child)
            if (bounds.isEmpty) continue
            if (isTappable(child)) clickable++
            tiles.add(bounds)
        }

        if (tiles.size < MIN_TILES) return false
        if (clickable < MIN_CLICKABLE_TILES) return false
        return tileShaped(tiles) && level(tiles) && evenlyWide(tiles) && sideBySide(tiles)
    }

    /**
     * A tile is frequently a plain container with the clickable part inside
     * it, so look one level down before concluding nothing here can be
     * tapped. Only ever reached for nodes that already look like a row, so
     * the extra lookups are rare.
     */
    private fun isTappable(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return true
        for (i in 0 until node.childCount) {
            if (node.getChild(i)?.isClickable == true) return true
        }
        return false
    }

    private fun tileShaped(tiles: List<Rect>): Boolean = tiles.all {
        val aspect = it.width().toFloat() / it.height()
        aspect >= MIN_TILE_ASPECT && aspect <= MAX_TILE_ASPECT
    }

    private fun level(tiles: List<Rect>): Boolean {
        val top = tiles.first().top
        val tolerance = tiles.first().height() * BASELINE_TOLERANCE
        return tiles.all { abs(it.top - top) <= tolerance }
    }

    private fun evenlyWide(tiles: List<Rect>): Boolean {
        val median = tiles.map { it.width() }.sorted()[tiles.size / 2]
        if (median <= 0) return false
        return tiles.all { abs(it.width() - median) <= median * WIDTH_TOLERANCE }
    }

    private fun sideBySide(tiles: List<Rect>): Boolean {
        val ordered = tiles.sortedBy { it.left }
        for (i in 1 until ordered.size) {
            val previous = ordered[i - 1]
            val overlap = previous.right - ordered[i].left
            if (overlap > previous.width() * OVERLAP_TOLERANCE) return false
        }
        return true
    }

    private fun scoreOf(node: AccessibilityNodeInfo): Int =
        (if (node.isScrollable) SCROLLABLE_BONUS else 0) + min(node.childCount, MAX_TILE_SCORE)

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
