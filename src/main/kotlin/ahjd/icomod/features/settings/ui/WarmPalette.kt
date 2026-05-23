package ahjd.icomod.features.settings.ui

import net.minecraft.client.gui.DrawContext

/**
 * Dark-gray surface palette with yellow accents and red/green semantic colors.
 * All values are 0xAARRGGBB ints ready to hand to [DrawContext.fill].
 *
 * Naming is historical (Warm*) — kept stable so call sites don't churn.
 */
object WarmPalette {
    const val BG              = 0xFF161616.toInt()
    const val SURFACE         = 0xFF1F1F1F.toInt()
    const val CARD            = 0xFF242424.toInt()
    const val RAISED          = 0xFF2E2E2E.toInt()
    const val INPUT           = 0xFF181818.toInt()
    const val BORDER          = 0xFF3A3A3A.toInt()
    const val BORDER_SOFT     = 0xFF555555.toInt()
    const val BORDER_HAIRLINE = 0xFF2A2A2A.toInt()

    const val TEXT      = 0xFFE8E8E8.toInt()
    const val MUTED     = 0xFFAAAAAA.toInt()
    const val DIM       = 0xFF777777.toInt()
    const val FAINT     = 0xFF555555.toInt()

    const val ACCENT        = 0xFFFF8C28.toInt()
    const val ACCENT_BRIGHT = 0xFFFFB060.toInt()
    const val ACCENT_DEEP   = 0xFFB85A10.toInt()
    const val ACCENT_FAINT  = 0x22FF8C28
    const val ACCENT_HOVER  = 0x44FF8C28
    const val ACCENT_EDGE   = 0x88FF8C28.toInt()

    const val SUCCESS        = 0xFF4ECC5C.toInt()
    const val SUCCESS_BRIGHT = 0xFF7AE08A.toInt()
    const val SUCCESS_FAINT  = 0x334ECC5C

    const val DANGER         = 0xFFE05555.toInt()
    const val DANGER_BRIGHT  = 0xFFF07A7A.toInt()
    const val DANGER_FAINT   = 0x33E05555.toInt()

    const val SCRIM = 0xC0000000.toInt()
}

/** Single-pixel rectangle border (square corners). */
fun drawBorder(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
    ctx.fill(x, y, x + w, y + 1, color)
    ctx.fill(x, y + h - 1, x + w, y + h, color)
    ctx.fill(x, y, x + 1, y + h, color)
    ctx.fill(x + w - 1, y, x + w, y + h, color)
}

fun drawInsetBorder(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
    drawBorder(ctx, x + 1, y + 1, w - 2, h - 2, color)
}

/**
 * Filled rectangle with 2-pixel rounded corners. Implemented as three stacked
 * fills that skip the corner pixels — no texture, no shader, deterministic
 * pixel grid.
 */
fun fillRounded(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
    if (w < 4 || h < 4) { ctx.fill(x, y, x + w, y + h, color); return }
    ctx.fill(x + 2, y, x + w - 2, y + 2, color)
    ctx.fill(x, y + 2, x + w, y + h - 2, color)
    ctx.fill(x + 2, y + h - 2, x + w - 2, y + h, color)
    // Inner-corner softening pixels
    ctx.fill(x + 1, y + 1, x + 2, y + 2, color)
    ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, color)
    ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, color)
    ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color)
}

/** 1-pixel border tracing a 2-pixel rounded rectangle. */
fun drawRoundedBorder(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, color: Int) {
    if (w < 4 || h < 4) { drawBorder(ctx, x, y, w, h, color); return }
    ctx.fill(x + 2, y, x + w - 2, y + 1, color)            // top
    ctx.fill(x + 2, y + h - 1, x + w - 2, y + h, color)    // bottom
    ctx.fill(x, y + 2, x + 1, y + h - 2, color)            // left
    ctx.fill(x + w - 1, y + 2, x + w, y + h - 2, color)    // right
    // Corner stub pixels
    ctx.fill(x + 1, y + 1, x + 2, y + 2, color)
    ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, color)
    ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, color)
    ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color)
}

/**
 * Heavyweight ornamental border for panel chrome. Drawn entirely from
 * rectangles so it scales to any size without textures.
 *
 * Composition:
 *   1. Outer 1px edge line, skipping 14px corner zones.
 *   2. Corner filigree (14×14 per corner): outer L arm + inner echo arm
 *      4px inset + diagonal staircase connecting them + 2×2 pip in the elbow.
 *   3. Inner accent line, 4px inset, broken at each midpoint to host a motif.
 *   4. Edge midpoint sunburst on all four sides: 3-px tick on the edge,
 *      a 5-wide diamond pushed inward, and two flanking pips on the edge.
 *
 * `color` paints the primary line work; `accent` paints the lighter inner
 * echo, pips, and diamonds. Default `accent` is `color` lightened 35%.
 */
fun drawOrnamentalBorder(
    ctx: DrawContext,
    x: Int, y: Int, w: Int, h: Int,
    color: Int,
    accent: Int = lerpColor(color, 0xFFFFFFFF.toInt(), 0.35f),
) {
    if (w < 40 || h < 40) { drawRoundedBorder(ctx, x, y, w, h, color); return }

    val cz = 14  // corner zone
    val x2 = x + w
    val y2 = y + h
    val mxC = (x + x2) / 2

    // Outer edges. Only the top edge breaks for a midpoint motif; the other
    // three edges are unbroken (single clean line, no side ornaments).
    ctx.fill(x + cz, y, mxC - 5, y + 1, color)
    ctx.fill(mxC + 5, y, x2 - cz, y + 1, color)
    ctx.fill(x + cz, y2 - 1, x2 - cz, y2, color)
    ctx.fill(x, y + cz, x + 1, y2 - cz, color)
    ctx.fill(x2 - 1, y + cz, x2, y2 - cz, color)

    // Corner filigree
    drawCornerFiligree(ctx, x, y,           +1, +1, color, accent)
    drawCornerFiligree(ctx, x2 - 1, y,      -1, +1, color, accent)
    drawCornerFiligree(ctx, x, y2 - 1,      +1, -1, color, accent)
    drawCornerFiligree(ctx, x2 - 1, y2 - 1, -1, -1, color, accent)

    // Top-only midpoint sunburst
    drawEdgeSunburstH(ctx, mxC, y, +1, color, accent)
}

/**
 * Corner filigree anchored at ([ax],[ay]) (a pixel on the outer corner)
 * extending [dx], [dy] (each ±1) into the panel.
 *
 * Pattern, 14×14, dx=+1, dy=+1 (top-left case mirrored otherwise):
 *   - Outer arms: full 14px line along top edge (row 0) and left edge (col 0)
 *   - Diagonal staircase: 3 pixels at (1,1), (2,2), (3,3)
 *   - Inner echo arms: 8px line along row 3 (cols 3..11) and col 3 (rows 3..11)
 *   - Tip flourishes: 2-pixel notches at ends of echo arms
 *   - Elbow pip: 2×2 square at (6,6)..(7,7)
 *   - Far-corner accent pip: 1px at (10,10)
 */
private fun drawCornerFiligree(
    ctx: DrawContext,
    ax: Int, ay: Int,
    dx: Int, dy: Int,
    color: Int, accent: Int,
) {
    fun px(ox: Int, oy: Int, c: Int) {
        val px = ax + ox * dx
        val py = ay + oy * dy
        ctx.fill(px, py, px + 1, py + 1, c)
    }
    fun line(ox1: Int, oy1: Int, ox2: Int, oy2: Int, c: Int) {
        // Inclusive in our pattern space; convert to a 1px-thick fill rect.
        val lx = minOf(ax + ox1 * dx, ax + ox2 * dx)
        val ly = minOf(ay + oy1 * dy, ay + oy2 * dy)
        val rx = maxOf(ax + ox1 * dx, ax + ox2 * dx) + 1
        val ry = maxOf(ay + oy1 * dy, ay + oy2 * dy) + 1
        ctx.fill(lx, ly, rx, ry, c)
    }

    // Outer arms (14px each)
    line(0, 0, 13, 0, color)
    line(0, 0, 0, 13, color)

    // Diagonal staircase from corner to elbow
    px(1, 1, accent)
    px(2, 2, accent)
    px(3, 3, color)

    // Inner echo arms (8px), 3px inset
    line(3, 3, 11, 3, accent)
    line(3, 3, 3, 11, accent)

    // Tip flourishes — 2px notch turning inward at end of each echo arm
    px(11, 4, accent)
    px(4, 11, accent)
    px(10, 5, color)
    px(5, 10, color)

    // Elbow pip (2×2)
    line(6, 6, 7, 7, accent)

    // Outer corner accent pip
    px(10, 10, accent)
}

/**
 * Sunburst midpoint motif for top/bottom edges. [cx] is the edge midpoint x;
 * [edgeY] is the row containing the edge line; [inDir] is +1 if "inside" is
 * below the edge (top edge), -1 if above (bottom edge).
 */
private fun drawEdgeSunburstH(
    ctx: DrawContext,
    cx: Int, edgeY: Int, inDir: Int,
    color: Int, accent: Int,
) {
    fun row(d: Int, lx: Int, rx: Int, c: Int) {
        val py = edgeY + d * inDir
        ctx.fill(cx + lx, py, cx + rx + 1, py + 1, c)
    }
    // Tick on the edge line itself — 3px bright
    row(0, -1, 1, accent)
    // Flank pips on edge line — 1px each
    row(0, -4, -4, color)
    row(0, 4, 4, color)
    // Diamond pushed inward, 5-wide (rows +1..+5)
    row(1, -2, 2, color)
    row(2, -3, 3, accent)
    row(3, -2, 2, color)
    row(4, -1, 1, color)
    row(5, 0, 0, accent)
}

/** Sunburst midpoint motif for left/right edges (mirror of H). */
private fun drawEdgeSunburstV(
    ctx: DrawContext,
    edgeX: Int, cy: Int, inDir: Int,
    color: Int, accent: Int,
) {
    fun col(d: Int, ty: Int, by: Int, c: Int) {
        val px = edgeX + d * inDir
        ctx.fill(px, cy + ty, px + 1, cy + by + 1, c)
    }
    col(0, -1, 1, accent)
    col(0, -4, -4, color)
    col(0, 4, 4, color)
    col(1, -2, 2, color)
    col(2, -3, 3, accent)
    col(3, -2, 2, color)
    col(4, -1, 1, color)
    col(5, 0, 0, accent)
}

/**
 * Ornamental divider line — horizontal band with a centered fleur motif and
 * flanking spiral scrollwork. Drawn on row [y]; uses 5 rows of vertical space
 * (y-2 .. y+2) for the centerpiece and small bumps for scrollwork.
 */
fun drawOrnamentalDivider(ctx: DrawContext, x: Int, y: Int, w: Int, color: Int) {
    if (w < 40) { ctx.fill(x, y, x + w, y + 1, color); return }
    val accent = lerpColor(color, 0xFFFFFFFF.toInt(), 0.35f)
    val cx = x + w / 2
    val x2 = x + w

    // Main line, broken in three places: center motif and two scrollworks
    val scrollOffset = 22
    val centerGap = 9
    ctx.fill(x, y, cx - scrollOffset - 4, y + 1, color)
    ctx.fill(cx - scrollOffset + 4, y, cx - centerGap, y + 1, color)
    ctx.fill(cx + centerGap, y, cx + scrollOffset - 4, y + 1, color)
    ctx.fill(cx + scrollOffset + 4, y, x2, y + 1, color)

    // --- Center fleur (9-wide × 5-tall) ---
    // Vertical stem above and below
    ctx.fill(cx, y - 2, cx + 1, y - 1, accent)
    ctx.fill(cx, y + 1, cx + 1, y + 2, accent)
    // Diamond body
    ctx.fill(cx - 1, y - 1, cx + 2, y, color)
    ctx.fill(cx - 4, y, cx + 5, y + 1, color)
    ctx.fill(cx - 1, y + 1, cx + 2, y + 2, color)
    // Inner accent dot
    ctx.fill(cx, y, cx + 1, y + 1, accent)
    // Outer flank pips
    ctx.fill(cx - 7, y, cx - 6, y + 1, accent)
    ctx.fill(cx + 6, y, cx + 7, y + 1, accent)

    // --- Side scrollworks (3-wide spirals) at ±scrollOffset ---
    for (s in intArrayOf(-1, +1)) {
        val sx = cx + s * scrollOffset
        // Curl: 3 pixels on the line + 1 pixel above (top of spiral) +
        //       1 below (tail). Mirrored by sign of s.
        ctx.fill(sx - 2, y, sx + 3, y + 1, color)
        ctx.fill(sx - 1, y - 1, sx, y, accent)
        ctx.fill(sx + 1, y + 1, sx + 2, y + 2, accent)
    }
}

/**
 * Lerp two 0xAARRGGBB colors per channel. `t` clamped to [0,1].
 * Used for hover crossfades.
 */
fun lerpColor(a: Int, b: Int, t: Float): Int {
    val tc = t.coerceIn(0f, 1f)
    val aa = (a ushr 24) and 0xFF
    val ar = (a ushr 16) and 0xFF
    val ag = (a ushr 8) and 0xFF
    val ab = a and 0xFF
    val ba = (b ushr 24) and 0xFF
    val br = (b ushr 16) and 0xFF
    val bg = (b ushr 8) and 0xFF
    val bb = b and 0xFF
    val ra = (aa + ((ba - aa) * tc)).toInt()
    val rr = (ar + ((br - ar) * tc)).toInt()
    val rg = (ag + ((bg - ag) * tc)).toInt()
    val rb = (ab + ((bb - ab) * tc)).toInt()
    return (ra shl 24) or (rr shl 16) or (rg shl 8) or rb
}
