package ahjd.icomod.features.gifpicker

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Discord-style GIF picker. Opens as a full screen with a dark panel anchored
 * bottom-right above where the chat input would be, showing a grid of thumbnails.
 *
 * Click behaviour:
 *  - If [initialChatText] starts with `/g ` or `/p `, append the gif name and re-open
 *    ChatScreen with the modified text so the user can edit/send themselves.
 *  - Otherwise, send the gif name as a chat message immediately and close the picker.
 */
class GifPickerScreen(private val initialChatText: String) : Screen(Text.literal("GIF Picker")) {

    private val cols = 3
    private val thumb = 80
    private val gap = 4
    private val pad = 8
    private val headerH = 24

    private var entries: List<GifEntry> = emptyList()
    private var scrollRows = 0
    private var refreshed = false

    private var panelX = 0
    private var panelY = 0
    private var panelW = 0
    private var panelH = 0

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

        addDrawableChild(
            ButtonWidget.builder(Text.literal("âœ•")) { close() }
                .dimensions(panelX + panelW - 22, panelY + 2, 20, 20)
                .build()
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // Panel background + border
        context.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xE0101010.toInt())
        drawRectBorder(context, panelX, panelY, panelW, panelH, 0xFF404040.toInt())

        context.drawTextWithShadow(
            textRenderer,
            Text.literal("GIFs (${entries.size})"),
            panelX + pad, panelY + 8, 0xFFFFFFFF.toInt()
        )

        if (entries.isEmpty()) {
            val msg = GifCatalog.lastError?.let { "Server unreachable: $it" } ?: "No gifs in catalog"
            context.drawTextWithShadow(textRenderer, Text.literal(msg), panelX + pad, panelY + headerH + pad, 0xFFAAAAAA.toInt())
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
            context.fill(x, y, x + thumb, y + thumb, if (hovered) 0xFF303030.toInt() else 0xFF1E1E1E.toInt())

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
                    localFile == null -> "â€¦"
                    else -> "decoding"
                }
                drawCellLabel(context, label, entry.name, x, y)
            }

            if (hovered) drawRectBorder(context, x, y, thumb, thumb, 0xFFFFFFFF.toInt())
        }
    }

    private fun drawRectBorder(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int, colour: Int) {
        ctx.fill(x, y, x + w, y + 1, colour)                 // top
        ctx.fill(x, y + h - 1, x + w, y + h, colour)         // bottom
        ctx.fill(x, y, x + 1, y + h, colour)                 // left
        ctx.fill(x + w - 1, y, x + w, y + h, colour)         // right
    }

    private fun drawFilenameFallback(ctx: DrawContext, name: String, x: Int, y: Int) {
        val short = if (name.length > 8) name.substring(0, 7) + "â€¦" else name
        ctx.drawTextWithShadow(textRenderer, Text.literal(short), x + 4, y + thumb / 2 - 4, 0xFFCCCCCC.toInt())
    }

    private fun drawCellLabel(ctx: DrawContext, status: String, name: String, x: Int, y: Int) {
        ctx.drawTextWithShadow(textRenderer, Text.literal(status), x + 4, y + 4, 0xFF888888.toInt())
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
            val mouseX = click.x()
            val mouseY = click.y()
            val gridY0 = panelY + headerH + pad
            for ((i, entry) in entries.withIndex()) {
                val row = i / cols
                val col = i % cols
                val x = panelX + pad + col * (thumb + gap)
                val y = gridY0 + (row - scrollRows) * (thumb + gap)
                if (mouseX in x.toDouble()..(x + thumb).toDouble()
                    && mouseY in y.toDouble()..(y + thumb).toDouble()
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
