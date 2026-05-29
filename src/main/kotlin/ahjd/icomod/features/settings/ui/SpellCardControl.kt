package ahjd.icomod.features.settings.ui

import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text

/**
 * Composite control for [ahjd.icomod.features.settings.SettingItem.SpellCardItem].
 *
 * Owns three sub-controls (enable toggle, file dropdown, preview button) and
 * paints the surrounding card chrome: a darker card surface, a colored left
 * stripe keyed to the spell's class, a small "class pill" badge, and a
 * "Preview" affordance.
 *
 * Click dispatch: [ahjd.icomod.features.settings.SettingsScreen] calls
 * [hit] / [dispatchClick] like any other control. The composite forwards
 * clicks to whichever sub-control actually contains the cursor.
 */
class SpellCardControl(
    override var x: Int,
    override var y: Int,
    override val w: Int,
    override val h: Int,
    val title: String,
    val classKind: String,
    val accent: Int,
    val classifierHint: String?,
    val toggle: ToggleControl,
    val cycle: CycleControl,
    val preview: ActionControl,
    val slider: SliderControl,
) : WarmControl() {

    init {
        layoutChildren()
    }

    fun relayout(newX: Int, newY: Int) {
        x = newX; y = newY
        layoutChildren()
    }

    private fun layoutChildren() {
        // Top cluster: [toggle] [cycle] [preview] right-aligned, 6px gutters, 10px right inset.
        val topY = y + 8
        var rx = x + w - 10

        preview.x = rx - PREVIEW_W; preview.y = topY
        rx -= PREVIEW_W + GUTTER

        cycle.x = rx - CYCLE_W; cycle.y = topY
        rx -= CYCLE_W + GUTTER

        toggle.x = rx - TOGGLE_W; toggle.y = topY

        // Bottom row: slider right-aligned, leaving room for "100%" label after it.
        // Reserve ~32px for label so the slider doesn't clip its readout.
        val sliderY = y + h - SLIDER_H - 8
        val labelReserve = 32
        slider.x = x + w - SLIDER_W - labelReserve - 4
        slider.y = sliderY
    }

    fun dispatchClick(mx: Double, my: Double, button: Int): Boolean {
        val target = when {
            toggle.hit(mx, my)  -> toggle
            cycle.hit(mx, my)   -> cycle
            preview.hit(mx, my) -> preview
            slider.hit(mx, my)  -> slider
            else -> return false
        }
        when (button) {
            0 -> {
                if (target === slider) slider.updateFromMouse(mx)
                else target.onLeft()
            }
            1 -> target.onRight()
        }
        return true
    }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        // Card surface
        fillRounded(ctx, x, y, w, h, WarmPalette.RAISED)
        drawRoundedBorder(ctx, x, y, w, h, WarmPalette.BORDER)

        // Class-colored left stripe (4px wide, full card height minus corners).
        ctx.fill(x + 2, y + 4, x + 6, y + h - 4, accent)

        // Class pill badge
        val tr = textRenderer()
        val badgeText = classKind.uppercase()
        val badgeTextW = tr.getWidth(badgeText)
        val badgeW = badgeTextW + 12
        val badgeH = 12
        val badgeX = x + 14
        val badgeY = y + 8
        val badgeBg = (accent and 0x00FFFFFF) or (0x33 shl 24)
        val badgeBorder = (accent and 0x00FFFFFF) or (0x88 shl 24).toInt()
        fillRounded(ctx, badgeX, badgeY, badgeW, badgeH, badgeBg)
        drawRoundedBorder(ctx, badgeX, badgeY, badgeW, badgeH, badgeBorder)
        ctx.drawText(tr, Text.literal(badgeText),
            badgeX + 6, badgeY + (badgeH - 8) / 2, accent, false)

        // Spell title
        val titleX = badgeX + badgeW + 8
        val titleY = y + 8
        ctx.drawTextWithShadow(tr, Text.literal(title), titleX, titleY, WarmPalette.TEXT)

        // Classifier hint underneath the title
        if (!classifierHint.isNullOrBlank()) {
            val hintY = y + 24
            val maxW = (toggle.x - titleX - 8).coerceAtLeast(20)
            val shown = if (tr.getWidth(classifierHint) <= maxW) classifierHint
                        else tr.trimToWidth(classifierHint, maxW - tr.getWidth("...")) + "..."
            ctx.drawText(tr, Text.literal(shown), titleX, hintY, WarmPalette.DIM, false)
        }

        // "Volume" label aligned with slider row, on the card's left side.
        val volLabelY = slider.y + (slider.h - 8) / 2
        ctx.drawText(tr, Text.literal("Volume"),
            x + 14, volLabelY, WarmPalette.MUTED, false)

        toggle.render(ctx, mx, my)
        cycle.render(ctx, mx, my)
        preview.render(ctx, mx, my)
        slider.render(ctx, mx, my)
    }

    companion object {
        const val CARD_H = 70
        const val CTRL_H = 20
        const val TOGGLE_W = 56
        const val CYCLE_W = 150
        const val PREVIEW_W = 68
        const val GUTTER = 6
        const val SLIDER_W = 180
        const val SLIDER_H = 14
    }
}

/**
 * Decorative subsection header strip used by [SettingItem.SectionHeaderItem].
 * Also serves as a click target for collapse/expand when wired by the
 * containing screen; [collapsed] toggles the disclosure caret only --
 * the screen owns the actual fold state and click routing.
 */
fun drawSectionHeaderStrip(
    ctx: DrawContext,
    x: Int, y: Int, w: Int, h: Int,
    label: String,
    accent: Int,
    collapsed: Boolean = false,
) {
    val tr = net.minecraft.client.MinecraftClient.getInstance().textRenderer
    val textY = y + (h - 8) / 2
    // Left tick
    ctx.fill(x + 4, textY - 1, x + 6, textY + 9, accent)
    // Disclosure caret: "+" when collapsed, "-" when open. Plain ASCII so
    // font availability isn't an issue.
    val caret = if (collapsed) "+" else "-"
    ctx.drawTextWithShadow(tr, Text.literal(caret), x + 12, textY, accent)
    // Label
    val labelText = label.uppercase()
    val labelX = x + 12 + tr.getWidth(caret) + 6
    ctx.drawTextWithShadow(tr, Text.literal(labelText), labelX, textY, accent)
    val labelW = tr.getWidth(labelText)
    // Trailing line — fades from accent to transparent for a soft edge.
    val lineLeft = labelX + labelW + 8
    val lineRight = x + w - 4
    if (lineRight > lineLeft + 4) {
        val midY = textY + 3
        ctx.fill(lineLeft, midY, lineRight, midY + 1,
            (accent and 0x00FFFFFF) or (0x55 shl 24))
    }
}
