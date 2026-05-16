package ahjd.icomod.features.gifpicker

import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHudLine

/**
 * Helpers for the ChatHud mixin: extract line text, match it against the catalog,
 * and overlay the gif at a given chat-space position.
 *
 * Match rule: any token in the line matching `[a-z0-9_-]+\.(png|jpe?g|gif)(XS|S|M|L)?`
 * (case-insensitive) that resolves to a catalog entry. Handles Wynncraft prefixes like
 * `[Guild] Bob: foo.gifS`.
 *
 * Coordinates are in CHAT-SPACE (i.e. caller has already pushed a matrix scaled by chat scale).
 */
object ChatGifRenderer {
    private val NAME_RE = Regex("([a-z0-9_-]+\\.(?:png|jpe?g|gif))(XS|S|M|L)?\\b", RegexOption.IGNORE_CASE)

    data class Match(val entry: GifEntry, val size: GifSize, val prefix: String, val token: String)

    fun findGif(line: ChatHudLine.Visible): Match? {
        return findGif(extractPlainText(line))
    }

    fun findGif(text: String): Match? {
        if (text.isEmpty()) return null
        for (m in NAME_RE.findAll(text)) {
            val name = m.groups[1]?.value ?: continue
            val entry = GifCatalog.byName(name) ?: continue
            val size = GifSize.parse(m.groups[2]?.value)
            return Match(entry, size, text.substring(0, m.range.first), m.value)
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

    private fun extractPlainText(line: ChatHudLine.Visible): String {
        val sb = StringBuilder()
        line.content().accept { _, _, codePoint ->
            sb.appendCodePoint(codePoint)
            true
        }
        return sb.toString()
    }
}
