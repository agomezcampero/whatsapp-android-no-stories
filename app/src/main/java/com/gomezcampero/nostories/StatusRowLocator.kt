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

    /**
     * Ids confirmed against a real WhatsApp build, tried ahead of everything
     * else because a verified id beats anything the shape rules work out.
     *
     * Confirmed from a detection report: a RecyclerView of five 178x237
     * tiles at Rect(0, 294 - 1080, 573) on the Chats tab. Not to be confused
     * with updates_list, which is the Updates tab and stays visible.
     *
     * Expect this to stop resolving when WhatsApp updates; that is not a
     * failure, it just falls through to [findByStructure] until a new id is
     * added here.
     */
    private val ROW_VIEW_IDS = listOf(
        "com.whatsapp:id/status_list",
    )

    /**
     * Ids that only turn up on the Chats screen. The shape and label passes
     * are only allowed to run when one of these is present.
     *
     * Without that, they will happily match things elsewhere in WhatsApp
     * that are also strips of evenly sized, level, tappable tiles - the
     * toolbar that appears when you select a message, for one, which put the
     * cover over the copy and delete buttons. Covering the wrong thing is
     * worse than covering nothing, so this fails closed: if WhatsApp renames
     * these, the passes stop running rather than start guessing.
     */
    private const val SEARCH_BAR_ID = "com.whatsapp:id/my_search_bar"

    private val CHATS_SCREEN_IDS = listOf(
        SEARCH_BAR_ID,
        "com.whatsapp:id/conversations_coordinator_layout",
    )

    private val ACCENTS = "\\p{Mn}+".toRegex()

    /**
     * The area actually worth covering: the tiles, not the view holding them.
     *
     * When the header collapses, the row keeps its full-width layout while
     * its contents shrink to a cluster of circles, so covering the view
     * itself takes the search bar and the overflow menu with it. The visible
     * tiles are where the faces are, expanded or collapsed.
     *
     * As a backstop the cover is kept clear of the search bar, whose position
     * we can ask for directly.
     */
    fun coverBounds(row: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Rect {
        val tiles = Rect()
        for (i in 0 until row.childCount) {
            val child = row.getChild(i) ?: continue
            if (!child.isVisibleToUser) continue
            val bounds = boundsOf(child)
            if (bounds.isEmpty) continue
            if (tiles.isEmpty) tiles.set(bounds) else tiles.union(bounds)
        }

        val cover = if (tiles.isEmpty) boundsOf(row) else tiles

        val searchBarTop = root.findAccessibilityNodeInfosByViewId(SEARCH_BAR_ID)
            .firstOrNull()
            ?.let { boundsOf(it).top }
        if (searchBarTop != null && searchBarTop > cover.top && cover.bottom > searchBarTop) {
            cover.bottom = searchBarTop
        }
        return cover
    }

    /** One line per child of the row, for a report of a working cover. */
    fun describeTiles(row: AccessibilityNodeInfo): List<String> =
        (0 until row.childCount).map { i ->
            val child = row.getChild(i)
            if (child == null) {
                "tile $i = null"
            } else {
                "tile $i id=${child.viewIdResourceName} class=${child.className} " +
                    "bounds=${boundsOf(child)} visible=${child.isVisibleToUser} " +
                    "text=${child.contentDescription ?: child.text ?: ""}"
            }
        }

    /** Whether the screen on show is the chat list, rather than a chat. */
    fun looksLikeChatsScreen(root: AccessibilityNodeInfo): Boolean =
        CHATS_SCREEN_IDS.any { root.findAccessibilityNodeInfosByViewId(it).isNotEmpty() }

    /** Resolves whichever shipped id this WhatsApp build still answers to. */
    fun findBySeedId(root: AccessibilityNodeInfo, screen: Rect): AccessibilityNodeInfo? =
        ROW_VIEW_IDS.firstNotNullOfOrNull { findByViewId(root, it, screen) }

    /** Resolves a known id. Cheap: no tree walk on our side. */
    fun findByViewId(root: AccessibilityNodeInfo, id: String, screen: Rect): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(id)
            .firstOrNull { it.isVisibleToUser && isPlausible(boundsOf(it), screen) }

    /**
     * The check for a node we resolved by id, where the id already settles
     * what it is. All that is left to confirm is that it is on screen, in the
     * top band, and not so tall that covering it would swallow the list.
     *
     * [isRow] is deliberately not used here: when the header collapses, the
     * row shrinks to a cluster of overlapping circles far too narrow to pass
     * it, and covering that is still the right thing to do.
     */
    fun isPlausible(bounds: Rect, screen: Rect): Boolean =
        !bounds.isEmpty &&
            bounds.top < screen.height() * TOP_REGION &&
            bounds.height() <= screen.height() * MAX_HEIGHT

    /**
     * Finds the row by its shape: the best-scoring strip of side-by-side tiles
     * in the top band of the screen.
     *
     * [log] gets a line for *every* node in the top band, flagged with whether
     * it was row-shaped enough to reach the tile checks. Everything is
     * reported, not just the candidates, because a row that fails [isRow] is
     * exactly the case the shape rules got wrong - and it would be invisible
     * in a report that only listed what passed.
     */
    fun findByStructure(
        root: AccessibilityNodeInfo,
        screen: Rect,
        log: ((rowShaped: Boolean, line: String) -> Unit)? = null,
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0

        forEachNodeInTopBand(root, screen) { node, bounds ->
            if (isRow(bounds, screen)) {
                val verdict = inspectTiles(node)
                val score = scoreOf(node)
                log?.invoke(true, describe(node, bounds, verdict, score))

                val tighter = best != null && bounds.height() < boundsOf(best!!).height()
                if (verdict.accepted && (score > bestScore || (score == bestScore && tighter))) {
                    best = node
                    bestScore = score
                }
            } else {
                log?.invoke(false, outline(node, bounds, screen))
            }
        }
        return best
    }

    /** A node that never reached the tile checks, and what stopped it. */
    private fun outline(node: AccessibilityNodeInfo, bounds: Rect, screen: Rect): String =
        "id=${node.viewIdResourceName} class=${node.className} bounds=$bounds " +
            "children=${node.childCount} scrollable=${node.isScrollable} " +
            "text=${node.contentDescription ?: node.text ?: ""} -> not row shaped " +
            "(${whyNotARow(bounds, screen)})"

    private fun whyNotARow(bounds: Rect, screen: Rect): String = when {
        bounds.isEmpty -> "empty"
        bounds.width() < screen.width() * MIN_WIDTH -> "too narrow"
        bounds.height() < screen.height() * MIN_HEIGHT -> "too short"
        bounds.height() > screen.height() * MAX_HEIGHT -> "too tall"
        else -> "not wide enough for its height"
    }

    private fun describe(
        node: AccessibilityNodeInfo,
        bounds: Rect,
        verdict: TileVerdict,
        score: Int,
    ): String = "id=${node.viewIdResourceName} class=${node.className} bounds=$bounds " +
        "children=${node.childCount} tiles=${verdict.tiles} tappable=${verdict.tappable} " +
        "scrollable=${node.isScrollable} score=$score -> ${verdict.reason}\n" +
        "    tiles: ${verdict.shapes}"

    private class TileVerdict(
        val accepted: Boolean,
        val reason: String,
        val tiles: Int = 0,
        val tappable: Int = 0,
        val shapes: String = "",
    )

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
     * Whether this node's children look like a row of avatars: enough of them,
     * tappable, tile-shaped, level with each other and side by side. Reports
     * the first rule that rejected them, so a report says what went wrong.
     */
    private fun inspectTiles(node: AccessibilityNodeInfo): TileVerdict {
        if (node.childCount < MIN_TILES) return TileVerdict(false, "only ${node.childCount} children")

        val tiles = ArrayList<Rect>(node.childCount)
        var tappable = 0
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val bounds = boundsOf(child)
            if (bounds.isEmpty) continue
            if (isTappable(child)) tappable++
            tiles.add(bounds)
        }

        val shapes = tiles.joinToString(" ") { "${it.width()}x${it.height()}@${it.left},${it.top}" }
        fun verdict(ok: Boolean, reason: String) = TileVerdict(ok, reason, tiles.size, tappable, shapes)

        if (tiles.size < MIN_TILES) return verdict(false, "only ${tiles.size} tiles with bounds")
        if (tappable < MIN_CLICKABLE_TILES) return verdict(false, "only $tappable tappable")
        if (!tileShaped(tiles)) return verdict(false, "tiles not avatar shaped")
        if (!level(tiles)) return verdict(false, "tiles not level")
        if (!evenlyWide(tiles)) return verdict(false, "tiles unevenly wide")
        if (!sideBySide(tiles)) return verdict(false, "tiles not side by side")
        // The row of stories scrolls sideways. A toolbar of icons does not,
        // and is otherwise indistinguishable from it.
        if (!node.isScrollable) return verdict(false, "not scrollable")
        return verdict(true, "accepted")
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
