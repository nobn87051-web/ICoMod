package ahjd.icomod.features.emote

import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import ahjd.icomod.features.settings.ui.fillRounded
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text

/**
 * Type-to-filter picker over a list of strings. Used by the emote-wheel slot
 * settings: opens over the [SettingsScreen][ahjd.icomod.features.settings.SettingsScreen],
 * filters [options] as you type, click a row to pick. A "(none)" row clears the
 * binding. Returns to [parent] on pick or ESC.
 */
class EmotePickerScreen(
    private val parent: Screen,
    private val pickTitle: String,
    private val options: List<String>,
    private val current: String,
    private val onPick: (String) -> Unit,
) : Screen(Text.literal("Pick")) {

    private val panelW = 220
    private val rowH = 14
    private val visibleRows = 10
    private lateinit var search: TextFieldWidget
    private var filtered: List<String> = options
    private var scroll = 0

    private var panelX = 0
    private var panelY = 0

    override fun init() {
        super.init()
        panelX = (width - panelW) / 2
        panelY = height / 2 - (visibleRows * rowH + 60) / 2
        search = TextFieldWidget(textRenderer, panelX + 8, panelY + 26, panelW - 16, 14, Text.literal("search"))
        search.setMaxLength(48)
        search.setChangedListener { applyFilter(it) }
        addDrawableChild(search)
        setInitialFocus(search)
        applyFilter("")
    }

    private fun applyFilter(q: String) {
        val needle = q.trim().lowercase()
        filtered = if (needle.isEmpty()) options
        else options.filter { it.lowercase().contains(needle) }
        scroll = 0
    }

    /** Rows = a leading "(none)" clear option + filtered matches. */
    private fun rows(): List<String> = listOf(NONE) + filtered

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(ctx, mouseX, mouseY, delta)
        ctx.fill(0, 0, width, height, WarmPalette.SCRIM)

        val panelH = 26 + 18 + visibleRows * rowH + 8
        fillRounded(ctx, panelX, panelY, panelW, panelH, WarmPalette.CARD)
        drawRoundedBorder(ctx, panelX, panelY, panelW, panelH, WarmPalette.ACCENT)

        ctx.drawTextWithShadow(textRenderer, Text.literal(pickTitle), panelX + 8, panelY + 8, WarmPalette.ACCENT_BRIGHT)

        // Search box chrome (widget paints its own text)
        fillRounded(ctx, panelX + 8, panelY + 24, panelW - 16, 18, WarmPalette.INPUT)
        drawRoundedBorder(ctx, panelX + 8, panelY + 24, panelW - 16, 18, WarmPalette.BORDER_SOFT)
        search.render(ctx, mouseX, mouseY, delta)

        val listTop = panelY + 48
        val all = rows()
        scroll = scroll.coerceIn(0, maxOf(0, all.size - visibleRows))
        for (r in 0 until visibleRows) {
            val idx = scroll + r
            if (idx >= all.size) break
            val value = all[idx]
            val y = listTop + r * rowH
            val hovered = mouseX in (panelX + 8)..(panelX + panelW - 8) && mouseY in y..(y + rowH)
            val isCurrent = value == current || (value == NONE && current.isBlank())
            if (hovered) ctx.fill(panelX + 8, y, panelX + panelW - 8, y + rowH, WarmPalette.ACCENT_HOVER)
            val color = when {
                value == NONE -> WarmPalette.DIM
                isCurrent -> WarmPalette.ACCENT_BRIGHT
                hovered -> 0xFFFFFFFF.toInt()
                else -> WarmPalette.TEXT
            }
            ctx.drawTextWithShadow(textRenderer, Text.literal(value), panelX + 12, y + 3, color)
        }
    }

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0) {
            val all = rows()
            val listTop = panelY + 48
            val mx = click.x().toInt()
            val my = click.y().toInt()
            if (mx in (panelX + 8)..(panelX + panelW - 8)) {
                val r = (my - listTop) / rowH
                if (r in 0 until visibleRows) {
                    val idx = scroll + r
                    if (idx in all.indices) {
                        val picked = all[idx]
                        onPick(if (picked == NONE) "" else picked)
                        MinecraftClient.getInstance().setScreen(parent)
                        return true
                    }
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, h: Double, v: Double): Boolean {
        scroll = (scroll - v.toInt()).coerceIn(0, maxOf(0, rows().size - visibleRows))
        return true
    }

    override fun close() {
        MinecraftClient.getInstance().setScreen(parent)
    }

    override fun shouldPause(): Boolean = false

    companion object {
        private const val NONE = "(None)"
    }
}
