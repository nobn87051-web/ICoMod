package ahjd.icomod.features.gifpicker

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHudLine
import java.util.WeakHashMap

/**
 * Helpers for the ChatHud mixin: extract line text, match it against the catalog,
 * and overlay the gif at a given chat-space position.
 *
 * Match rule: any token in the line matching `[a-z0-9_-]+\.(png|jpe?g|gif)(XS|S|M|L)?`
 * (case-insensitive) that resolves to a catalog entry. Handles Wynncraft prefixes like
 * `[Guild] Bob: foo.gifS`.
 *
 * Coordinates are in CHAT-SPACE (i.e. caller has already pushed a matrix scaled by chat scale).
 *
 * Performance: this object is called for EVERY visible chat line EVERY render frame
 * (60+/sec) from [ahjd.icomod.mixin.ChatHudMixin]. Two micro-optimisations matter here:
 *  - [findGif] memoises results per visible line via [lineCache] (Fix #4).
 *  - Extraction reuses a thread-local [StringBuilder] and short-circuits on lines
 *    shorter than [MIN_TOKEN_LEN] characters (Fix #5).
 */
object ChatGifRenderer {
    private val NAME_RE = Regex("([a-z0-9_-]+\\.(?:png|jpe?g|gif))(XS|S|M|L)?\\b", RegexOption.IGNORE_CASE)

    /**
     * Shortest possible matching token is `a.gif` = 5 characters. Lines shorter
     * than this can't contain a hit, so we skip the regex pass entirely.
     */
    private const val MIN_TOKEN_LEN = 5

    data class Match(val entry: GifEntry, val size: GifSize, val prefix: String, val token: String)

    /**
     * Reusable per-thread plain-text buffer. The render path calls
     * [findGif] dozens of times per frame; allocating a fresh [StringBuilder]
     * each call was producing ~1700 short-lived allocs/sec on a typical chat.
     * ThreadLocal because the message-receive path ([GifChatPadding]) also
     * calls into the regex code from a non-render thread.
     */
    private val plainTextBuf = ThreadLocal.withInitial { StringBuilder(64) }

    /**
     * Per-line memo for [findGif]. Keyed by identity ([ChatHudLine.Visible] is
     * held alive by Minecraft's chat history and reused across frames), so weak
     * references let entries vanish naturally when chat scrolls past.
     *
     * The map can't store nulls, so [NO_MATCH] is the sentinel for "computed,
     * no match found" — a very common outcome that we still want cached.
     *
     * Invalidation: bumping [GifCatalog.version] (e.g. a refresh adds new
     * entries) makes any cached "no match" stale. The version check at the top
     * of [findGif] drops the cache on mismatch — cheap, lazy, and runs on the
     * render thread without coordination.
     *
     * Thread model: only [findGif]([ChatHudLine.Visible]) touches [lineCache],
     * and that's render-thread only. The string-overload [findGif]([String])
     * (used by [GifChatPadding] off the render thread) goes straight to
     * [findGifInternal] without touching the cache, so no synchronisation
     * is required.
     */
    private val lineCache: WeakHashMap<ChatHudLine.Visible, Match> = WeakHashMap(256)
    private var cachedAtVersion: Int = -1
    private val NO_MATCH: Match = Match(GifEntry("__sentinel__", "", 0L), GifSize.DEFAULT, "", "")

    fun findGif(line: ChatHudLine.Visible): Match? {
        val ver = GifCatalog.version
        if (ver != cachedAtVersion) {
            lineCache.clear()
            cachedAtVersion = ver
        }
        lineCache[line]?.let { return if (it === NO_MATCH) null else it }

        val match = computeFindGif(line)
        lineCache[line] = match ?: NO_MATCH
        return match
    }

    fun findGif(text: String): Match? {
        if (text.length < MIN_TOKEN_LEN) return null
        return findGifInternal(text)
    }

    /** Extracts plain text from [line] using the shared buffer, then runs the match. */
    private fun computeFindGif(line: ChatHudLine.Visible): Match? {
        val sb = plainTextBuf.get().also { it.setLength(0) }
        line.content().accept { _, _, codePoint ->
            sb.appendCodePoint(codePoint)
            true
        }
        if (sb.length < MIN_TOKEN_LEN) return null
        // Regex.findAll accepts CharSequence so we don't pay for sb.toString()
        // unless we actually found a match (the substring call below).
        return findGifInternal(sb)
    }

    private fun findGifInternal(text: CharSequence): Match? {
        for (m in NAME_RE.findAll(text)) {
            val name = m.groups[1]?.value ?: continue
            val entry = GifCatalog.byName(name) ?: continue
            val size = GifSize.parse(m.groups[2]?.value)
            // Only materialise a String here, once we know we're returning a hit.
            val asString = text.toString()
            return Match(entry, size, asString.substring(0, m.range.first), m.value)
        }
        return null
    }

    /**
     * Draws the gif at (x, y) in chat-space, fitting within the requested size's box,
     * preserving aspect ratio. The texture is at source resolution; we matrix-scale at draw
     * time to keep it sharp. Returns true if drawn, false if asset isn't ready.
     */
    fun drawAt(ctx: DrawContext, entry: GifEntry, size: GifSize, x: Int, y: Int): Boolean {
        return drawAt(ctx, entry, size, x, y, null, null)
    }

    fun drawAtClipped(
        ctx: DrawContext,
        entry: GifEntry,
        size: GifSize,
        x: Int,
        y: Int,
        clipTop: Int,
        clipBottom: Int
    ): Boolean {
        return drawAt(ctx, entry, size, x, y, clipTop, clipBottom)
    }

    private fun drawAt(
        ctx: DrawContext,
        entry: GifEntry,
        size: GifSize,
        x: Int,
        y: Int,
        clipTop: Int?,
        clipBottom: Int?
    ): Boolean {
        val file = GifCache.ensureLocalAsync(entry).getNow(null) ?: return false
        val ct = GifThumbnail.get(file, entry.name) ?: return false
        val frame = ct.frameAt(System.currentTimeMillis())

        // Aspect-fit into the overlay box (in chat-space units)
        val ratio = minOf(size.maxWidth.toDouble() / frame.width, size.height.toDouble() / frame.height)
        val dispW = maxOf(1, (frame.width * ratio).toInt())
        val dispH = maxOf(1, (frame.height * ratio).toInt())

        if (clipTop != null && clipBottom != null) {
            val clippedTop = maxOf(y, clipTop)
            val clippedBottom = minOf(y + dispH, clipBottom)
            if (clippedBottom <= clippedTop) return false

            ctx.enableScissor(x - 1, clippedTop - 1, x + dispW + 1, clippedBottom + 1)
            try {
                ctx.fill(x - 1, y - 1, x + dispW + 1, y + dispH + 1, 0xFF000000.toInt())
                GifDraw.drawScaled(ctx, frame.id, x, y, dispW, dispH, frame.width, frame.height)
            } finally {
                ctx.disableScissor()
            }
            return true
        }

        // Opaque background so chat text behind doesn't bleed through
        ctx.fill(x - 1, y - 1, x + dispW + 1, y + dispH + 1, 0xFF000000.toInt())

        GifDraw.drawScaled(ctx, frame.id, x, y, dispW, dispH, frame.width, frame.height)
        return true
    }
}
