package ahjd.icomod.features.gifpicker

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.net.URI

/**
 * Discord-style GIF picker. Opens as a full screen with a dark panel anchored
 * bottom-right above where the chat input would be, showing a grid of thumbnails.
 *
 * Click behaviour: insert the gif token into the chat input at the end of any
 * existing text and re-open ChatScreen. User decides where/how to send — this
 * keeps the picker chat-channel-agnostic (works with /p, /g, /msg, public chat,
 * or any future channel prefix Wynncraft adds) without us having to special-case
 * any of them.
 */
class GifPickerScreen(private val initialChatText: String) : Screen(Text.literal("GIF Picker")) {

    private val cols = 3
    private val thumb = 80
    private val gap = 4
    private val pad = 8
    private val headerH = 28

    private var entries: List<GifEntry> = emptyList()
    private var scrollRows = 0
    private var refreshed = false

    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0

    // Custom header buttons — no vanilla ButtonWidget
    private data class HdrButton(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val label: String,
        val accentColor: Int,
        val hoverColor: Int,
    )

    private var btnClose: HdrButton? = null
    private var btnSubmit: HdrButton? = null

    override fun init() {
        super.init()
        entries = GifCatalog.entries

        if (!refreshed) {
            refreshed = true
            GifCatalog.refreshAsync().thenRun {
                MinecraftClient.getInstance().execute { entries = GifCatalog.entries }
            }
        }

        panelW = pad * 2 + cols * thumb + (cols - 1) * gap
        val visibleRows = 5
        panelH = headerH + pad + visibleRows * thumb + (visibleRows - 1) * gap + pad
        panelX = width - panelW - 8
        panelY = height - panelH - 14

        val btnH = 18
        val btnY = panelY + (headerH - btnH) / 2

        // Close button — right edge
        btnClose = HdrButton(
            x = panelX + panelW - pad - 20,
            y = btnY, w = 20, h = btnH,
            label = "✕",
            accentColor = 0xFF3A1410.toInt(),
            hoverColor  = 0xFF8A2818.toInt(),
        )

        // Submit button — left of close
        val submitW = 80
        btnSubmit = HdrButton(
            x = panelX + panelW - pad - 20 - gap - submitW,
            y = btnY, w = submitW, h = btnH,
            label = "+ Submit",
            accentColor = 0xFF4A2410.toInt(),
            hoverColor  = 0xFF8A4818.toInt(),
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // Panel background
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xF0140A06.toInt())

        // Header strip
        context.fill(panelX, panelY, panelX + panelW, panelY + headerH, 0xFF1F0E07.toInt())

        // Header border bottom separator — ember line
        context.fill(panelX, panelY + headerH - 1, panelX + panelW, panelY + headerH, 0xFF6A2810.toInt())

        // Outer panel border — burnt orange
        drawRectBorder(context, panelX, panelY, panelW, panelH, 0xFF6A2810.toInt())
        // Inner accent border — deep ember shadow
        drawRectBorder(context, panelX + 1, panelY + 1, panelW - 2, panelH - 2, 0xFF2A0E06.toInt())

        // Title — amber
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("GIFs  §8(${entries.size})"),
            panelX + pad, panelY + (headerH - 8) / 2, 0xFFE8B070.toInt()
        )

        // Header buttons
        renderHdrButton(context, btnClose, mouseX, mouseY)
        renderHdrButton(context, btnSubmit, mouseX, mouseY)

        if (entries.isEmpty()) {
            val msg = when {
                GifCatalog.lastError != null -> "§cServer unreachable"
                GifCatalog.version == 0     -> "§7Loading catalog..."
                else                         -> "§7No GIFs in catalog"
            }
            context.drawTextWithShadow(
                textRenderer, Text.literal(msg),
                panelX + pad, panelY + headerH + pad + 4, 0xFFAAAAAA.toInt()
            )
            return
        }

        val gridY0 = panelY + headerH + pad
        for ((i, entry) in entries.withIndex()) {
            val row = i / cols
            val col = i % cols
            val x = panelX + pad + col * (thumb + gap)
            val y = gridY0 + (row - scrollRows) * (thumb + gap)
            if (y + thumb < gridY0 || y > panelY + panelH - pad) continue

            val hovered = mouseX in x..(x + thumb) && mouseY in y..(y + thumb)

            // Cell background — subtle warm gradient via two fills
            context.fill(x, y, x + thumb, y + thumb / 2, if (hovered) 0xFF2A1810.toInt() else 0xFF1F100A.toInt())
            context.fill(x, y + thumb / 2, x + thumb, y + thumb, if (hovered) 0xFF221008.toInt() else 0xFF170A06.toInt())

            val localFuture = GifCache.ensureLocalAsync(entry)
            val localFile = localFuture.getNow(null)
            val ct = localFile?.let { GifThumbnail.get(it, entry.name) }
            if (ct != null) {
                val frame = ct.frameAt(System.currentTimeMillis())
                val ratio = minOf(thumb.toDouble() / frame.width, thumb.toDouble() / frame.height)
                val dispW = maxOf(1, (frame.width * ratio).toInt())
                val dispH = maxOf(1, (frame.height * ratio).toInt())
                val drawX = x + (thumb - dispW) / 2
                val drawY = y + (thumb - dispH) / 2
                GifDraw.drawScaled(context, frame.id, drawX, drawY, dispW, dispH, frame.width, frame.height)
            } else {
                val label = when {
                    localFuture.isCompletedExceptionally -> "err"
                    localFile == null                    -> "…"
                    else                                 -> "decoding"
                }
                drawCellLabel(context, label, entry.name, x, y)
            }

            if (hovered) drawRectBorder(context, x, y, thumb, thumb, 0xFFB04020.toInt())
            else         drawRectBorder(context, x, y, thumb, thumb, 0xFF3A1810.toInt())
        }

        // Submit tooltip on hover
        val sub = btnSubmit
        if (sub != null && mouseX in sub.x..(sub.x + sub.w) && mouseY in sub.y..(sub.y + sub.h)) {
            context.drawTextWithShadow(
                textRenderer, Text.literal("§7Submit GIFs at icomod.xyz"),
                panelX + pad, panelY + panelH + 4, 0xFFAAAAAA.toInt()
            )
        }
    }

    private fun renderHdrButton(ctx: DrawContext, btn: HdrButton?, mouseX: Int, mouseY: Int) {
        btn ?: return
        val hovered = mouseX in btn.x..(btn.x + btn.w) && mouseY in btn.y..(btn.y + btn.h)
        val bg = if (hovered) btn.hoverColor else btn.accentColor

        // Background fill
        ctx.fill(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, bg)

        // Border — brighter ember on hover
        val borderCol = if (hovered) 0xFFB04020.toInt() else 0xFF5A2010.toInt()
        drawRectBorder(ctx, btn.x, btn.y, btn.w, btn.h, borderCol)

        // Centered label text — amber
        val tw = textRenderer.getWidth(btn.label)
        val tx = btn.x + (btn.w - tw) / 2
        val ty = btn.y + (btn.h - 8) / 2
        ctx.drawTextWithShadow(textRenderer, Text.literal(btn.label), tx, ty, 0xFFE8B070.toInt())
    }

    private fun drawRectBorder(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, colour: Int) {
        ctx.fill(x,         y,         x + w,     y + 1,     colour) // top
        ctx.fill(x,         y + h - 1, x + w,     y + h,     colour) // bottom
        ctx.fill(x,         y,         x + 1,     y + h,     colour) // left
        ctx.fill(x + w - 1, y,         x + w,     y + h,     colour) // right
    }

    private fun drawFilenameFallback(ctx: DrawContext, name: String, x: Int, y: Int) {
        val short = if (name.length > 8) name.substring(0, 7) + "…" else name
        ctx.drawTextWithShadow(textRenderer, Text.literal(short), x + 4, y + thumb / 2 - 4, 0xFFE0B080.toInt())
    }

    private fun drawCellLabel(ctx: DrawContext, status: String, name: String, x: Int, y: Int) {
        ctx.drawTextWithShadow(textRenderer, Text.literal(status), x + 4, y + 4, 0xFF8A5538.toInt())
        drawFilenameFallback(ctx, name, x, y)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double): Boolean {
        if (mouseX in panelX.toDouble()..(panelX + panelW).toDouble()
            && mouseY in panelY.toDouble()..(panelY + panelH).toDouble()
        ) {
            scrollRows = (scrollRows - vertical.toInt()).coerceAtLeast(0)
                .coerceAtMost(maxOf(0, (entries.size + cols - 1) / cols - 1))
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical)
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            val mx = click.x()
            val my = click.y()

            btnClose?.let {
                if (mx in it.x.toDouble()..(it.x + it.w).toDouble() && my in it.y.toDouble()..(it.y + it.h).toDouble()) { close(); return true }
            }
            btnSubmit?.let {
                if (mx in it.x.toDouble()..(it.x + it.w).toDouble() && my in it.y.toDouble()..(it.y + it.h).toDouble()) {
                    Util.getOperatingSystem().open(URI("https://icomod.xyz"))
                    return true
                }
            }

            val gridY0 = panelY + headerH + pad
            for ((i, entry) in entries.withIndex()) {
                val row = i / cols
                val col = i % cols
                val x = panelX + pad + col * (thumb + gap)
                val y = gridY0 + (row - scrollRows) * (thumb + gap)
                if (mx in x.toDouble()..(x + thumb).toDouble()
                    && my in y.toDouble()..(y + thumb).toDouble()
                ) {
                    handleSelection(entry)
                    return true
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    private fun handleSelection(entry: GifEntry) {
        val trimmed = initialChatText.trim()
        val selectedName = entry.name + GifSize.DEFAULT.name
        val client = MinecraftClient.getInstance()
        val newText = if (trimmed.isEmpty()) selectedName else "${trimmed.trimEnd()} $selectedName"
        client.setScreen(ChatScreen(newText, false))
    }

    override fun shouldPause(): Boolean = false
    override fun close() { MinecraftClient.getInstance().setScreen(ChatScreen(initialChatText, false)) }
}
