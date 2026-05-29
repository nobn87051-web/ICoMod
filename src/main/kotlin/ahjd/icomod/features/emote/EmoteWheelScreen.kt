package ahjd.icomod.features.emote

import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import ahjd.icomod.features.settings.ui.fillRounded
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Radial emote wheel. Opened by holding [EmoteWheel]'s keybind; the slot under
 * the cursor (by angle from center, outside the dead-zone) is selected, and on
 * key RELEASE its bound emote fires via `/emote <arg>`.
 *
 * Slots are read live from [EmoteCatalog] (count + per-slot bind). Unbound
 * slots render dimmed and never fire.
 */
class EmoteWheelScreen : Screen(Text.literal("Emote Wheel")) {

    private val slots = EmoteCatalog.slots()
    private val deadZone = 28.0
    private val ringRadius = 95
    private val cellW = 70
    private val cellH = 22
    private var selected = -1
    private var openFrames = 0

    override fun shouldPause(): Boolean = false

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        super.render(ctx, mouseX, mouseY, delta)
        val mc = MinecraftClient.getInstance()

        // Scrim
        ctx.fill(0, 0, width, height, 0x88000000.toInt())

        val cx = width / 2
        val cy = height / 2
        selected = slotAt(mouseX.toDouble(), mouseY.toDouble(), cx, cy)

        // Center hint
        val hint = if (selected >= 0) (EmoteCatalog.bind(selected) ?: "Unbound") else "Release to cancel"
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(hint), cx, cy - 4,
            if (selected >= 0 && EmoteCatalog.bind(selected) != null) WarmPalette.ACCENT_BRIGHT else WarmPalette.DIM)

        for (i in 0 until slots) {
            val ang = slotAngleRad(i)
            val sx = cx + (ringRadius * cos(ang)).toInt()
            val sy = cy + (ringRadius * sin(ang)).toInt()
            val x = sx - cellW / 2
            val y = sy - cellH / 2

            val arg = EmoteCatalog.bind(i)
            val isSel = i == selected
            val bg = when {
                isSel -> WarmPalette.ACCENT_HOVER
                arg != null -> WarmPalette.INPUT
                else -> (WarmPalette.SURFACE and 0x00FFFFFF) or (0x99 shl 24)
            }
            fillRounded(ctx, x, y, cellW, cellH, bg)
            drawRoundedBorder(ctx, x, y, cellW, cellH,
                if (isSel) WarmPalette.ACCENT_BRIGHT else WarmPalette.BORDER)

            val label = arg ?: "-"
            val color = when {
                isSel -> 0xFFFFFFFF.toInt()
                arg != null -> WarmPalette.TEXT
                else -> WarmPalette.DIM
            }
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label), sx, y + (cellH - 8) / 2, color)
        }

        // Poll raw key; fire on release. Grace a couple frames so a poll race
        // at open-time doesn't instantly close.
        openFrames++
        if (openFrames > 2 && !EmoteWheel.keyHeld(mc)) {
            fireAndClose(mc)
        }
    }

    private fun fireAndClose(mc: MinecraftClient) {
        val arg = if (selected >= 0) EmoteCatalog.bind(selected) else null
        if (arg != null) mc.player?.networkHandler?.sendChatCommand("emote $arg")
        mc.setScreen(null)
    }

    /** Top-anchored, clockwise. Returns radians for drawing. */
    private fun slotAngleRad(i: Int): Double =
        Math.toRadians(-90.0 + i * (360.0 / slots))

    /** Slot index under the cursor, or -1 if inside the dead-zone. */
    private fun slotAt(mx: Double, my: Double, cx: Int, cy: Int): Int {
        val dx = mx - cx
        val dy = my - cy
        if (hypot(dx, dy) < deadZone) return -1
        var deg = Math.toDegrees(atan2(dy, dx)) + 90.0  // 0 = top, clockwise
        deg = ((deg % 360.0) + 360.0) % 360.0
        val per = 360.0 / slots
        return (Math.round(deg / per).toInt()) % slots
    }
}
