package ahjd.icomod.features.gifpicker

import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import ahjd.icomod.features.settings.ui.fillRounded
import ahjd.icomod.features.settings.ui.lerpColor
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.util.Util
import java.net.URI
import kotlin.math.exp

/**
 * GIF picker. Dark-gray panel with yellow accents anchored bottom-right above
 * the chat input. Rounded corners + animated hover on cells/buttons.
 *
 * Click behaviour: insert the gif token into the chat input at the end of any
 * existing text and re-open ChatScreen. User decides where/how to send.
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

    private data class HdrButton(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val label: String,
        val idleBg: Int, val hoverBg: Int,
        val idleBorder: Int, val hoverBorder: Int,
        val idleText: Int, val hoverText: Int,
    ) {
        var hoverT: Float = 0f
    }

    private var btnClose: HdrButton? = null
    private var btnSubmit: HdrButton? = null

    private var cellHoverT: FloatArray = FloatArray(0)
    private var lastNs: Long = 0L

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

        btnClose = HdrButton(
            x = panelX + panelW - pad - 20,
            y = btnY, w = 20, h = btnH,
            label = "X",
            idleBg = WarmPalette.DANGER_FAINT,
            hoverBg = (WarmPalette.DANGER and 0x00FFFFFF) or (0x66 shl 24),
            idleBorder = WarmPalette.DANGER,
            hoverBorder = WarmPalette.DANGER_BRIGHT,
            idleText = WarmPalette.DANGER_BRIGHT,
            hoverText = 0xFFFFFFFF.toInt(),
        )

        val submitW = 80
        btnSubmit = HdrButton(
            x = panelX + panelW - pad - 20 - gap - submitW,
            y = btnY, w = submitW, h = btnH,
            label = "+ Submit",
            idleBg = WarmPalette.SUCCESS_FAINT,
            hoverBg = (WarmPalette.SUCCESS and 0x00FFFFFF) or (0x66 shl 24),
            idleBorder = WarmPalette.SUCCESS,
            hoverBorder = WarmPalette.SUCCESS_BRIGHT,
            idleText = WarmPalette.SUCCESS_BRIGHT,
            hoverText = 0xFFFFFFFF.toInt(),
        )
    }

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(context, mouseX, mouseY, delta)

        // Animation dt
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1_000_000_000.0).toFloat()
        lastNs = now
        val k = 1f - exp(-dt * 14f)

        // Panel — rounded dark-gray card, plain orange border
        fillRounded(context, panelX, panelY, panelW, panelH, WarmPalette.CARD)

        // Header strip with plain divider
        context.fill(panelX + 2, panelY + 2, panelX + panelW - 2, panelY + headerH, WarmPalette.SURFACE)
        context.fill(panelX + 2, panelY + headerH - 1, panelX + panelW - 2, panelY + headerH, WarmPalette.ACCENT)

        drawRoundedBorder(context, panelX, panelY, panelW, panelH, WarmPalette.ACCENT)

        // Title
        context.drawTextWithShadow(
            textRenderer,
            Text.literal("GIFs  §8(${entries.size})"),
            panelX + pad, panelY + (headerH - 8) / 2, WarmPalette.ACCENT_BRIGHT
        )

        renderHdrButton(context, btnClose, mouseX, mouseY, k)
        renderHdrButton(context, btnSubmit, mouseX, mouseY, k)

        if (entries.isEmpty()) {
            val msg = when {
                GifCatalog.lastError != null -> "§cServer unreachable"
                GifCatalog.version == 0     -> "§7Loading catalog..."
                else                         -> "§7No GIFs in catalog"
            }
            context.drawTextWithShadow(
                textRenderer, Text.literal(msg),
                panelX + pad, panelY + headerH + pad + 4, WarmPalette.MUTED
            )
            return
        }

        // Resize cell hover array if needed
        if (cellHoverT.size != entries.size) cellHoverT = FloatArray(entries.size)

        val gridY0 = panelY + headerH + pad
        for ((i, entry) in entries.withIndex()) {
            val row = i / cols
            val col = i % cols
            val x = panelX + pad + col * (thumb + gap)
            val y = gridY0 + (row - scrollRows) * (thumb + gap)
            if (y + thumb < gridY0 || y > panelY + panelH - pad) continue

            val hovered = mouseX in x..(x + thumb) && mouseY in y..(y + thumb)
            val target = if (hovered) 1f else 0f
            cellHoverT[i] += (target - cellHoverT[i]) * k
            val hov = cellHoverT[i]

            val bg = lerpColor(WarmPalette.INPUT, WarmPalette.RAISED, hov)
            fillRounded(context, x, y, thumb, thumb, bg)

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
                    localFile == null                    -> "..."
                    else                                 -> "decoding"
                }
                drawCellLabel(context, label, entry.name, x, y)
            }

            val border = lerpColor(WarmPalette.BORDER, WarmPalette.ACCENT_BRIGHT, hov)
            drawRoundedBorder(context, x, y, thumb, thumb, border)
        }

        val sub = btnSubmit
        if (sub != null && mouseX in sub.x..(sub.x + sub.w) && mouseY in sub.y..(sub.y + sub.h)) {
            context.drawTextWithShadow(
                textRenderer, Text.literal("§7Submit GIFs at icomod.xyz"),
                panelX + pad, panelY + panelH + 4, WarmPalette.MUTED
            )
        }
    }

    private fun renderHdrButton(ctx: DrawContext, btn: HdrButton?, mouseX: Int, mouseY: Int, k: Float) {
        btn ?: return
        val hovered = mouseX in btn.x..(btn.x + btn.w) && mouseY in btn.y..(btn.y + btn.h)
        val target = if (hovered) 1f else 0f
        btn.hoverT += (target - btn.hoverT) * k
        val t = btn.hoverT

        val bg = lerpColor(btn.idleBg, btn.hoverBg, t)
        val border = lerpColor(btn.idleBorder, btn.hoverBorder, t)
        val text = lerpColor(btn.idleText, btn.hoverText, t)

        fillRounded(ctx, btn.x, btn.y, btn.w, btn.h, bg)
        drawRoundedBorder(ctx, btn.x, btn.y, btn.w, btn.h, border)

        val tw = textRenderer.getWidth(btn.label)
        ctx.drawTextWithShadow(textRenderer, Text.literal(btn.label),
            btn.x + (btn.w - tw) / 2, btn.y + (btn.h - 8) / 2, text)
    }

    private fun drawFilenameFallback(ctx: DrawContext, name: String, x: Int, y: Int) {
        val short = if (name.length > 8) name.substring(0, 7) + "…" else name
        ctx.drawTextWithShadow(textRenderer, Text.literal(short), x + 4, y + thumb / 2 - 4, WarmPalette.MUTED)
    }

    private fun drawCellLabel(ctx: DrawContext, status: String, name: String, x: Int, y: Int) {
        ctx.drawTextWithShadow(textRenderer, Text.literal(status), x + 4, y + 4, WarmPalette.DIM)
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
        val selectedName = entry.name + GifSize.configuredDefault().name
        val client = MinecraftClient.getInstance()
        val newText = if (trimmed.isEmpty()) selectedName else "${trimmed.trimEnd()} $selectedName"
        client.setScreen(ChatScreen(newText, false))
    }

    override fun shouldPause(): Boolean = false
    override fun close() { MinecraftClient.getInstance().setScreen(ChatScreen(initialChatText, false)) }
}
