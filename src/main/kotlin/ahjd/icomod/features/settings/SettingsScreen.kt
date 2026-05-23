package ahjd.icomod.features.settings

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.settings.ui.ActionControl
import ahjd.icomod.features.settings.ui.CycleControl
import ahjd.icomod.features.settings.ui.GhostControl
import ahjd.icomod.features.settings.ui.TextFieldControl
import ahjd.icomod.features.settings.ui.ToggleControl
import ahjd.icomod.features.settings.ui.WarmControl
import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawOrnamentalBorder
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import ahjd.icomod.features.settings.ui.fillRounded
import ahjd.icomod.features.settings.ui.lerpColor
import kotlin.math.exp
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.input.KeyInput
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * IcoMod settings panel — sidebar tabs (left) + content pane (right) + footer
 * bar with Done / Discard Changes buttons.
 *
 * **Staging.** Edits go into [pending] (a map of changed-but-not-applied
 * values), never directly to [ConfigManager.config]. Controls read their
 * display value through staged getters so the UI reflects pending state.
 * Pressing **Done** applies pending → real config + save; **Discard** clears
 * pending and rebuilds the pane to redraw original values.
 *
 * **Exit guard.** ESC and the close path call [close]; if [pending] is dirty
 * we intercept and show a confirm dialog instead of closing. The dialog
 * offers "Exit Anyway" (red — discards) and "Apply & Save" (blue — applies).
 *
 * [SettingItem.ActionItem] is **not** staged — actions fire immediately
 * because they aren't user-mutable values (they're triggers like "Refresh
 * catalog").
 */
class SettingsScreen(private val parent: Screen?) :
    Screen(Text.literal("IcoMod Settings")) {

    private companion object {
        const val HEADER_H = 44
        const val FOOTER_H = 40
        const val SIDEBAR_W = 150
        const val SIDEBAR_PAD = 8
        const val TAB_H = 24
        const val TAB_GAP = 2
        const val PANEL_MARGIN = 16
        const val ROW_H = 38
        const val ROW_PAD_X = 14
        const val SECTION_HEAD_H = 28
        const val CTRL_TOGGLE_W = 70
        const val CTRL_CYCLE_W = 140
        const val CTRL_ACTION_W = 140
        const val CTRL_TEXT_W = 240
        const val CTRL_H = 22
        const val SCROLLBAR_W = 4
        const val HELP_RIGHT_MARGIN = 12

        const val FOOTER_BTN_W = 130
        const val FOOTER_BTN_H = 22
    }

    private data class RowSlot(val item: SettingItem, val ctrl: WarmControl, val logicalY: Int)

    private var activeTab = 0
    private var rows: List<RowSlot> = emptyList()
    private var scrollY = 0
    private var contentHeight = 0

    private var doneBtn: ActionControl? = null
    private var discardBtn: GhostControl? = null

    /** Set by [requestRebuild] from action handlers; consumed in render. */
    @Volatile private var rebuildPending = false

    /** Pending edits keyed by item. Cleared on Apply or Discard. */
    private val pending = mutableMapOf<SettingItem, Any>()

    /** Exit-confirm dialog visibility + per-button hover. */
    private var showExitDialog = false
    private var dlgRedHover = 0f
    private var dlgBlueHover = 0f
    private var dlgLastNs = 0L

    private var tabHoverT: FloatArray = FloatArray(0)
    private var tabActiveT: FloatArray = FloatArray(0)
    private var tabLastNs: Long = 0L

    /** Right pane (content area) bounds: x, y, w, h. */
    private fun paneBounds(): IntArray {
        val x = SIDEBAR_W + PANEL_MARGIN
        val y = HEADER_H + 8
        val w = (width - x - PANEL_MARGIN).coerceAtLeast(120)
        val h = (height - FOOTER_H - 8 - y).coerceAtLeast(40)
        return intArrayOf(x, y, w, h)
    }

    override fun init() {
        super.init()
        val sections = SettingsRegistry.all()
        if (activeTab >= sections.size) activeTab = 0
        if (tabHoverT.size != sections.size) {
            tabHoverT = FloatArray(sections.size)
            tabActiveT = FloatArray(sections.size).also { if (it.isNotEmpty()) it[activeTab] = 1f }
        }
        buildPane()

        val btnY = height - FOOTER_H + (FOOTER_H - FOOTER_BTN_H) / 2
        doneBtn = ActionControl(
            x = width - FOOTER_BTN_W - 12,
            y = btnY, w = FOOTER_BTN_W, h = FOOTER_BTN_H,
            label = "Done",
            onClick = { applyAndClose() },
        )
        discardBtn = GhostControl(
            x = width - FOOTER_BTN_W * 2 - 22,
            y = btnY, w = FOOTER_BTN_W, h = FOOTER_BTN_H,
            label = "Discard Changes",
            onClick = { discardChanges() },
        )
    }

    /** Build right-pane row controls for the active section. */
    private fun buildPane() {
        clearChildren()
        val sections = SettingsRegistry.all()
        if (sections.isEmpty()) {
            rows = emptyList(); contentHeight = 0; return
        }
        val section = sections[activeTab]
        val sectionItems = section.resolveItems()
        val (paneX, paneY, paneW, _) = paneBounds()
        val list = mutableListOf<RowSlot>()
        var rowY = paneY + SECTION_HEAD_H
        for (item in sectionItems) {
            val ctrl = buildControl(item, paneX, paneW, rowY)
            list += RowSlot(item, ctrl, rowY)
            if (ctrl is TextFieldControl) addDrawableChild(ctrl.widget)
            rowY += ROW_H
        }
        rows = list
        contentHeight = SECTION_HEAD_H + sectionItems.size * ROW_H
        scrollY = 0
        applyScroll()
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    private fun applyScroll() {
        for (row in rows) {
            val rowYActual = row.logicalY - scrollY
            row.ctrl.y = rowYActual + (ROW_H - CTRL_H) / 2
            if (row.ctrl is TextFieldControl) row.ctrl.syncWidgetPosition()
        }
    }

    // --- Staging helpers ----------------------------------------------------

    private fun stagedGet(item: SettingItem.BoolItem): Boolean =
        pending[item] as? Boolean ?: item.get()

    private fun stagedSet(item: SettingItem.BoolItem, v: Boolean) {
        if (v == item.get()) pending.remove(item) else pending[item] = v
    }

    private fun stagedGet(item: SettingItem.EnumItem): String =
        pending[item] as? String ?: item.get()

    private fun stagedSet(item: SettingItem.EnumItem, v: String) {
        if (v == item.get()) pending.remove(item) else pending[item] = v
    }

    private fun stagedGet(item: SettingItem.StringItem): String =
        pending[item] as? String ?: item.get()

    private fun stagedSet(item: SettingItem.StringItem, v: String) {
        if (v == item.get()) pending.remove(item) else pending[item] = v
    }

    private fun isDirty(): Boolean = pending.isNotEmpty()

    /** Write pending values to real config + persist. */
    private fun applyPending() {
        for ((item, value) in pending) {
            when (item) {
                is SettingItem.BoolItem   -> item.set(value as Boolean)
                is SettingItem.EnumItem   -> item.set(value as String)
                is SettingItem.StringItem -> item.set(value as String)
                is SettingItem.ActionItem -> {}  // never staged
            }
        }
        pending.clear()
        ConfigManager.save()
    }

    private fun applyAndClose() {
        applyPending()
        MinecraftClient.getInstance().setScreen(parent)
    }

    private fun discardChanges() {
        if (!isDirty()) return
        pending.clear()
        buildPane()  // rebuild controls so widgets show original config values
    }

    // --- Control construction ----------------------------------------------

    private fun buildControl(item: SettingItem, paneX: Int, paneW: Int, rowY: Int): WarmControl {
        val rowRight = paneX + paneW - ROW_PAD_X
        val ctrlY = rowY + (ROW_H - CTRL_H) / 2
        return when (item) {
            is SettingItem.BoolItem -> ToggleControl(
                rowRight - CTRL_TOGGLE_W, ctrlY, CTRL_TOGGLE_W, CTRL_H,
                getOn = { stagedGet(item) },
                onChange = { stagedSet(item, it) },
            )
            is SettingItem.EnumItem -> {
                // Defensive normalization: if real config holds an option no
                // longer in the list, fix it immediately (and persist) — this
                // is config-corruption recovery, not a user edit.
                val safe = item.get().takeIf { it in item.options } ?: item.options.first()
                if (item.get() != safe) { item.set(safe); ConfigManager.save() }
                CycleControl(
                    rowRight - CTRL_CYCLE_W, ctrlY, CTRL_CYCLE_W, CTRL_H,
                    opts = item.options,
                    getCurrent = { stagedGet(item) },
                    onChange = { stagedSet(item, it) },
                )
            }
            is SettingItem.StringItem -> TextFieldControl(
                rowRight - CTRL_TEXT_W, ctrlY, CTRL_TEXT_W, CTRL_H,
                initial = stagedGet(item),
                maxLen = item.maxLen,
                onChange = { stagedSet(item, it) },
            )
            is SettingItem.ActionItem -> ActionControl(
                rowRight - CTRL_ACTION_W, ctrlY, CTRL_ACTION_W, CTRL_H,
                label = item.buttonText,
                onClick = item.onClick,
            )
        }
    }

    // --- Render -------------------------------------------------------------

    /**
     * Async-safe request to rebuild the active pane (e.g. after an action
     * handler mutates a dynamic section). Deferred so we don't recurse into
     * widget lifecycle from inside a click handler.
     */
    fun requestRebuild() { rebuildPending = true }

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        if (rebuildPending) {
            rebuildPending = false
            val savedScroll = scrollY
            buildPane()
            scrollY = savedScroll.coerceAtLeast(0)
            applyScroll()
        }
        ctx.fill(0, 0, width, height, WarmPalette.BG)

        // Header
        ctx.fill(0, 0, width, HEADER_H, WarmPalette.SURFACE)
        ctx.fill(0, HEADER_H - 2, width, HEADER_H - 1, WarmPalette.BORDER)
        ctx.fill(0, HEADER_H - 1, width, HEADER_H, WarmPalette.ACCENT)
        ctx.drawTextWithShadow(textRenderer, Text.literal("IcoMod"),
            12, 16, WarmPalette.ACCENT)
        val brandW = textRenderer.getWidth("IcoMod ")
        ctx.drawTextWithShadow(textRenderer, Text.literal("Settings"),
            12 + brandW, 16, WarmPalette.TEXT)
        ctx.drawTextWithShadow(textRenderer, Text.literal("press [${SettingsKeybind.boundKeyName()}] to toggle"),
            12, 28, WarmPalette.DIM)

        // Footer band
        val footerTop = height - FOOTER_H
        ctx.fill(0, footerTop, width, footerTop + 1, WarmPalette.ACCENT)
        ctx.fill(0, footerTop + 1, width, footerTop + 2, WarmPalette.BORDER)
        ctx.fill(0, footerTop + 2, width, height, WarmPalette.SURFACE)

        renderSidebar(ctx, mouseX, mouseY)
        renderPane(ctx, mouseX, mouseY, delta)

        // Footer status text (left side) — "N unsaved change(s)" when dirty
        if (isDirty()) {
            val n = pending.size
            val msg = if (n == 1) "1 unsaved change" else "$n unsaved changes"
            ctx.drawTextWithShadow(textRenderer, Text.literal(msg),
                12, footerTop + (FOOTER_H - 8) / 2, WarmPalette.ACCENT_BRIGHT)
        }

        discardBtn?.render(ctx, mouseX, mouseY)
        doneBtn?.render(ctx, mouseX, mouseY)

        if (showExitDialog) renderExitDialog(ctx, mouseX, mouseY)
    }

    private fun renderSidebar(ctx: DrawContext, mx: Int, my: Int) {
        val sbTop = HEADER_H
        val sbBottom = height - FOOTER_H
        ctx.fill(0, sbTop, SIDEBAR_W, sbBottom, WarmPalette.SURFACE)
        ctx.fill(SIDEBAR_W - 1, sbTop, SIDEBAR_W, sbBottom, WarmPalette.BORDER)

        val now = System.nanoTime()
        val dt = if (tabLastNs == 0L) 0f else ((now - tabLastNs) / 1_000_000_000.0).toFloat()
        tabLastNs = now
        val k = 1f - exp(-dt * 14f)

        val sections = SettingsRegistry.all()
        var ty = sbTop + SIDEBAR_PAD
        for ((i, sec) in sections.withIndex()) {
            val hovered = mx in SIDEBAR_PAD..(SIDEBAR_W - SIDEBAR_PAD) && my in ty..(ty + TAB_H)
            val hoverTarget = if (hovered) 1f else 0f
            val activeTarget = if (i == activeTab) 1f else 0f
            tabHoverT[i]  += (hoverTarget  - tabHoverT[i])  * k
            tabActiveT[i] += (activeTarget - tabActiveT[i]) * k

            val hovT = tabHoverT[i]
            val actT = tabActiveT[i]

            val bgIdle  = WarmPalette.SURFACE and 0x00FFFFFF
            val bgHover = WarmPalette.RAISED
            val bgActive = WarmPalette.ACCENT_FAINT
            val bg = lerpColor(lerpColor(bgIdle, bgHover, hovT), bgActive, actT)
            fillRounded(ctx, SIDEBAR_PAD, ty, SIDEBAR_W - SIDEBAR_PAD * 2, TAB_H, bg)

            if (actT > 0.01f) {
                val railH = (TAB_H * actT).toInt().coerceAtLeast(2)
                val railY = ty + (TAB_H - railH) / 2
                ctx.fill(SIDEBAR_PAD, railY, SIDEBAR_PAD + 3, railY + railH, WarmPalette.ACCENT)
            }

            val color = lerpColor(
                lerpColor(WarmPalette.MUTED, WarmPalette.TEXT, hovT),
                WarmPalette.ACCENT_BRIGHT,
                actT,
            )
            val textX = SIDEBAR_PAD + 10 + (actT * 2).toInt()
            ctx.drawTextWithShadow(textRenderer, Text.literal(sec.title),
                textX, ty + (TAB_H - 8) / 2, color)
            ty += TAB_H + TAB_GAP
        }
    }

    private fun renderPane(ctx: DrawContext, mx: Int, my: Int, delta: Float) {
        val (paneX, paneY, paneW, paneH) = paneBounds()
        val paneBottom = paneY + paneH

        val sections = SettingsRegistry.all()
        if (sections.isEmpty()) return
        val section = sections[activeTab]

        fillRounded(ctx, paneX, paneY, paneW, paneH, WarmPalette.CARD)

        ctx.fill(paneX + 2, paneY + 2, paneX + paneW - 2, paneY + SECTION_HEAD_H, WarmPalette.SURFACE)
        ctx.fill(paneX + 2, paneY + SECTION_HEAD_H - 1, paneX + paneW - 2,
            paneY + SECTION_HEAD_H, WarmPalette.BORDER)
        ctx.drawTextWithShadow(textRenderer, Text.literal(section.title.uppercase()),
            paneX + ROW_PAD_X, paneY + (SECTION_HEAD_H - 8) / 2, WarmPalette.ACCENT_BRIGHT)

        drawOrnamentalBorder(ctx, paneX, paneY, paneW, paneH, WarmPalette.ACCENT)

        val rowsTop = paneY + SECTION_HEAD_H
        ctx.enableScissor(paneX, rowsTop, paneX + paneW, paneBottom)
        try {
            for ((i, row) in rows.withIndex()) {
                val rowY = row.logicalY - scrollY
                if (rowY + ROW_H < rowsTop || rowY > paneBottom) continue
                if (i > 0) {
                    ctx.fill(paneX + ROW_PAD_X, rowY, paneX + paneW - ROW_PAD_X,
                        rowY + 1, WarmPalette.BORDER_HAIRLINE)
                }
                val textRight = row.ctrl.x - HELP_RIGHT_MARGIN
                val textLeft = paneX + ROW_PAD_X
                val textMaxW = (textRight - textLeft).coerceAtLeast(40)
                val labelColor = if (row.item in pending) WarmPalette.ACCENT_BRIGHT
                                 else WarmPalette.TEXT
                drawClipped(ctx, row.item.label, textLeft, rowY + 8, textMaxW, labelColor)
                row.item.help?.takeIf { it.isNotBlank() }?.let { help ->
                    drawClipped(ctx, help, textLeft, rowY + 22, textMaxW, WarmPalette.DIM)
                }
                row.ctrl.render(ctx, mx, my)
            }
            super.render(ctx, mx, my, delta)
        } finally {
            ctx.disableScissor()
        }

        drawScrollbar(ctx, paneX + paneW - SCROLLBAR_W - 2, rowsTop, paneBottom)
    }

    // --- Exit confirm dialog -----------------------------------------------

    private fun dialogBounds(): IntArray {
        val w = 340
        val h = 110
        return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
    }

    private data class DlgBtnRect(val x: Int, val y: Int, val w: Int, val h: Int) {
        fun hit(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
    }

    private fun exitBtnRect(): DlgBtnRect {
        val (dx, dy, dw, dh) = dialogBounds()
        val bw = 130
        val bh = 22
        return DlgBtnRect(dx + dw / 2 - bw - 6, dy + dh - bh - 12, bw, bh)
    }

    private fun applyBtnRect(): DlgBtnRect {
        val (dx, dy, dw, dh) = dialogBounds()
        val bw = 130
        val bh = 22
        return DlgBtnRect(dx + dw / 2 + 6, dy + dh - bh - 12, bw, bh)
    }

    private fun renderExitDialog(ctx: DrawContext, mx: Int, my: Int) {
        // Scrim
        ctx.fill(0, 0, width, height, 0xC0000000.toInt())

        // Anim
        val now = System.nanoTime()
        val dt = if (dlgLastNs == 0L) 0f else ((now - dlgLastNs) / 1_000_000_000.0).toFloat()
        dlgLastNs = now
        val k = 1f - exp(-dt * 14f)

        val (dx, dy, dw, dh) = dialogBounds()
        fillRounded(ctx, dx, dy, dw, dh, WarmPalette.CARD)
        drawOrnamentalBorder(ctx, dx, dy, dw, dh, WarmPalette.ACCENT)

        // Title
        val title = "Unapplied Changes"
        val titleW = textRenderer.getWidth(title)
        ctx.drawTextWithShadow(textRenderer, Text.literal(title),
            dx + (dw - titleW) / 2, dy + 14, WarmPalette.ACCENT_BRIGHT)

        // Body
        val body = "You have ${pending.size} unsaved setting${if (pending.size == 1) "" else "s"}."
        val bodyW = textRenderer.getWidth(body)
        ctx.drawTextWithShadow(textRenderer, Text.literal(body),
            dx + (dw - bodyW) / 2, dy + 36, WarmPalette.TEXT)
        val sub = "Exit without applying, or apply and exit?"
        val subW = textRenderer.getWidth(sub)
        ctx.drawTextWithShadow(textRenderer, Text.literal(sub),
            dx + (dw - subW) / 2, dy + 50, WarmPalette.DIM)

        // Buttons
        val ex = exitBtnRect()
        val ap = applyBtnRect()
        val exHover = ex.hit(mx.toDouble(), my.toDouble())
        val apHover = ap.hit(mx.toDouble(), my.toDouble())
        dlgRedHover  += ((if (exHover) 1f else 0f) - dlgRedHover) * k
        dlgBlueHover += ((if (apHover) 1f else 0f) - dlgBlueHover) * k

        drawDlgBtn(ctx, ex, "Exit Anyway", dlgRedHover,
            WarmPalette.DANGER_FAINT,
            (WarmPalette.DANGER and 0x00FFFFFF) or (0x66 shl 24),
            WarmPalette.DANGER, WarmPalette.DANGER_BRIGHT,
            WarmPalette.DANGER_BRIGHT, 0xFFFFFFFF.toInt())

        drawDlgBtn(ctx, ap, "Apply & Save", dlgBlueHover,
            0x335A8FE0,
            (0xFF5A8FE0.toInt() and 0x00FFFFFF) or (0x66 shl 24),
            0xFF5A8FE0.toInt(), 0xFF85AFFA.toInt(),
            0xFF85AFFA.toInt(), 0xFFFFFFFF.toInt())
    }

    private fun drawDlgBtn(
        ctx: DrawContext, r: DlgBtnRect, label: String, t: Float,
        bgIdle: Int, bgHover: Int,
        borderIdle: Int, borderHover: Int,
        textIdle: Int, textHover: Int,
    ) {
        val bg = lerpColor(bgIdle, bgHover, t)
        val border = lerpColor(borderIdle, borderHover, t)
        val text = lerpColor(textIdle, textHover, t)
        fillRounded(ctx, r.x, r.y, r.w, r.h, bg)
        drawRoundedBorder(ctx, r.x, r.y, r.w, r.h, border)
        val tw = textRenderer.getWidth(label)
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
            r.x + (r.w - tw) / 2, r.y + (r.h - 8) / 2, text)
    }

    // --- Helpers ------------------------------------------------------------

    private fun drawClipped(ctx: DrawContext, text: String, x: Int, y: Int, maxW: Int, color: Int) {
        if (textRenderer.getWidth(text) <= maxW) {
            ctx.drawTextWithShadow(textRenderer, Text.literal(text), x, y, color); return
        }
        val trimmed = textRenderer.trimToWidth(text, maxW - textRenderer.getWidth("..."))
        ctx.drawTextWithShadow(textRenderer, Text.literal("$trimmed..."), x, y, color)
    }

    private fun drawScrollbar(ctx: DrawContext, x: Int, vpTop: Int, vpBottom: Int) {
        val viewport = vpBottom - vpTop
        if (contentHeight <= viewport) return
        ctx.fill(x, vpTop, x + SCROLLBAR_W, vpBottom, WarmPalette.BORDER_HAIRLINE)
        val thumbH = (viewport.toDouble() / contentHeight * viewport).toInt().coerceAtLeast(20)
        val maxScroll = contentHeight - viewport
        val thumbY = vpTop + ((scrollY.toDouble() / maxScroll) * (viewport - thumbH)).toInt()
        ctx.fill(x, thumbY, x + SCROLLBAR_W, thumbY + thumbH, WarmPalette.ACCENT)
    }

    // --- Input --------------------------------------------------------------

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        val mx = click.x()
        val my = click.y()
        val button = click.button()

        // Dialog is modal — eat all other input while open
        if (showExitDialog) {
            if (button == 0) {
                if (exitBtnRect().hit(mx, my)) {
                    pending.clear()
                    showExitDialog = false
                    MinecraftClient.getInstance().setScreen(parent)
                    return true
                }
                if (applyBtnRect().hit(mx, my)) {
                    applyPending()
                    showExitDialog = false
                    MinecraftClient.getInstance().setScreen(parent)
                    return true
                }
            }
            return true
        }

        // Footer buttons
        doneBtn?.let { if (it.hit(mx, my) && button == 0) { it.onLeft(); return true } }
        discardBtn?.let { if (it.hit(mx, my) && button == 0) { it.onLeft(); return true } }

        // Sidebar tabs
        if (mx >= SIDEBAR_PAD && mx <= SIDEBAR_W - SIDEBAR_PAD && my >= HEADER_H) {
            val sections = SettingsRegistry.all()
            var ty = HEADER_H + SIDEBAR_PAD
            for ((i, _) in sections.withIndex()) {
                if (my >= ty && my <= ty + TAB_H && button == 0) {
                    if (i != activeTab) { activeTab = i; buildPane() }
                    return true
                }
                ty += TAB_H + TAB_GAP
            }
        }

        // Pane row controls
        val (paneX, paneY, paneW, paneH) = paneBounds()
        if (mx >= paneX && mx <= paneX + paneW && my >= paneY + SECTION_HEAD_H && my <= paneY + paneH) {
            for (row in rows) {
                val ctrl = row.ctrl
                if (ctrl is TextFieldControl) continue
                if (!ctrl.hit(mx, my)) continue
                when (button) {
                    0 -> { ctrl.onLeft(); return true }
                    1 -> { ctrl.onRight(); return true }
                }
            }
        }
        return super.mouseClicked(click, doubled)
    }

    override fun keyPressed(input: KeyInput): Boolean {
        if (showExitDialog) {
            if (input.key() == GLFW.GLFW_KEY_ESCAPE) { showExitDialog = false; return true }
            return true  // swallow other input while modal
        }
        return super.keyPressed(input)
    }

    override fun mouseScrolled(
        mouseX: Double, mouseY: Double, horizontal: Double, vertical: Double,
    ): Boolean {
        if (showExitDialog) return true
        val (paneX, paneY, paneW, paneH) = paneBounds()
        if (mouseX < paneX || mouseX > paneX + paneW ||
            mouseY < paneY || mouseY > paneY + paneH) return false
        val viewport = paneH - SECTION_HEAD_H
        val maxScroll = (contentHeight - SECTION_HEAD_H - viewport).coerceAtLeast(0)
        if (maxScroll == 0) return false
        scrollY = (scrollY - (vertical * 22).toInt()).coerceIn(0, maxScroll)
        applyScroll()
        return true
    }

    override fun renderBackground(
        ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float,
    ) {
        // No-op: render() paints the full bg.
    }

    /**
     * Called by vanilla ESC handler and by our own paths. If pending edits
     * exist, gate behind the exit dialog instead of closing immediately.
     * No save here — applying is explicit (Done button or dialog).
     */
    override fun close() {
        if (isDirty()) { showExitDialog = true; return }
        MinecraftClient.getInstance().setScreen(parent)
    }

    override fun shouldPause(): Boolean = false
}
