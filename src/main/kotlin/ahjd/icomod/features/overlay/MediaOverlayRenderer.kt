package ahjd.icomod.features.overlay

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.gifpicker.GifDraw
import ahjd.icomod.features.gifpicker.GifThumbnail
import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.text.Text
import net.minecraft.util.Identifier

/**
 * Paints all overlay entries every frame.
 *
 * Render paths (only one is active per frame, gated by config):
 *  - HUD layer **before** [VanillaHudElements.CHAT] — default; chat box and
 *    player list draw on top of overlays.
 *  - HUD layer **after** [VanillaHudElements.SUBTITLES] — when
 *    `overlayRenderOnTop` is true; overlays sit above every vanilla HUD layer.
 *  - [renderManual] called from [MediaOverlayInput]'s per-screen render hook
 *    when `overlayShowOverGui` is true and a non-chat screen is active.
 *
 * Entries are drawn in [MediaOverlayManager.allByStack] order (oldest touch
 * first), so the most recently dragged ends up on top. The current drag
 * target gets a brighter outline.
 *
 * Out-of-bounds clamp: at least 25% of width AND 25% of height of any entry
 * must stay on screen (75% can hang off any edge). Re-applied every frame
 * so a screen resize automatically pulls strays back in.
 */
object MediaOverlayRenderer {

    private val LAYER_ID_NORMAL = Identifier.of("icomod", "media-overlay")
    private val LAYER_ID_TOP    = Identifier.of("icomod", "media-overlay-top")

    /**
     * Register both layers up front; only the one matching the current
     * `overlayRenderOnTop` flag actually paints each frame. Re-attaching at
     * runtime isn't supported by [HudElementRegistry], so the dual-layer
     * design is the lowest-friction way to honor a live toggle.
     *
     *  - NORMAL: before CHAT  -> sits under chat box and player list
     *  - TOP:    after SUBTITLES -> sits above every vanilla HUD layer
     */
    fun register() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            LAYER_ID_NORMAL,
            HudElement { ctx, _ -> if (!ConfigManager.config.overlayRenderOnTop) render(ctx) },
        )
        HudElementRegistry.attachElementAfter(
            VanillaHudElements.SUBTITLES,
            LAYER_ID_TOP,
            HudElement { ctx, _ -> if (ConfigManager.config.overlayRenderOnTop) render(ctx) },
        )
    }

    /**
     * Public entrypoint for paths outside the HUD layer (e.g. the over-GUI
     * render hook bound per-screen). Identical to the layer render — gated
     * by the same `overlayEnabled` config check.
     */
    fun renderManual(ctx: DrawContext) = render(ctx)

    private fun render(ctx: DrawContext) {
        if (!ConfigManager.config.overlayEnabled) return
        val entries = MediaOverlayManager.allByStack()
        if (entries.isEmpty()) return

        val mc = MinecraftClient.getInstance()
        val sw = ctx.scaledWindowWidth
        val sh = ctx.scaledWindowHeight
        val cfg = ConfigManager.config
        val screen = mc.currentScreen
        // "Editing mode" — show draggable outlines whenever the user has
        // input authority over overlays: chat always, any other screen only
        // when the over-GUI toggle is on.
        val editing = (screen is ChatScreen) ||
                      (screen != null && cfg.overlayShowOverGui)

        for (entry in entries) {
            val st = MediaOverlayManager.state(entry.name)
            if (st.hidden) continue

            val w = (entry.origW * st.scale).toInt().coerceAtLeast(8)
            val h = (entry.origH * st.scale).toInt().coerceAtLeast(8)

            // First-time placement: center on screen. Tracked by an explicit
            // [OverlayState.placed] flag, NOT by `x < 0`, because negative x
            // is a valid runtime value (image can hang 75% off the left edge).
            if (!st.placed) {
                st.x = (sw - w) / 2
                st.y = (sh - h) / 2
                st.placed = true
            }

            val (cx, cy) = clampPos(st.x, st.y, w, h, sw, sh)
            if (cx != st.x || cy != st.y) {
                st.x = cx; st.y = cy
                // No save here — clamp also runs in input paths, which persist.
                // This branch only fires on screen resize.
            }

            paintEntry(ctx, entry, st.x, st.y, w, h)

            if (editing) {
                val isDragging = MediaOverlayInput.draggingName == entry.name
                val outline = if (isDragging) WarmPalette.ACCENT_BRIGHT else WarmPalette.ACCENT
                drawRoundedBorder(ctx, st.x, st.y, w, h, outline)
            }
        }
    }

    /**
     * Clamp [x],[y] so at least 25% of width and 25% of height stays inside
     * the screen rect `[0, sw) x [0, sh)`. Returns the clamped pair.
     */
    fun clampPos(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): Pair<Int, Int> {
        val maxOffX = (w * 0.75).toInt()
        val maxOffY = (h * 0.75).toInt()
        val minVisX = (w * 0.25).toInt().coerceAtLeast(1)
        val minVisY = (h * 0.25).toInt().coerceAtLeast(1)
        val nx = x.coerceIn(-maxOffX, sw - minVisX)
        val ny = y.coerceIn(-maxOffY, sh - minVisY)
        return nx to ny
    }

    private fun paintEntry(
        ctx: DrawContext,
        entry: MediaOverlayManager.Entry,
        x: Int, y: Int, w: Int, h: Int,
    ) {
        when (entry.kind) {
            MediaOverlayManager.Kind.IMAGE,
            MediaOverlayManager.Kind.GIF -> {
                val cached = GifThumbnail.get(entry.file, entry.name) ?: run {
                    paintLoadingCard(ctx, x, y, w, h)
                    return
                }
                val frame = cached.frameAt(System.currentTimeMillis())
                GifDraw.drawScaled(ctx, frame.id, x, y, w, h, frame.width, frame.height)
            }
            MediaOverlayManager.Kind.VIDEO_PLACEHOLDER -> {
                paintVideoPlaceholder(ctx, entry.name, x, y, w, h)
            }
        }
    }

    private fun paintLoadingCard(ctx: DrawContext, x: Int, y: Int, w: Int, h: Int) {
        ctx.fill(x, y, x + w, y + h, 0xC0181818.toInt())
        drawRoundedBorder(ctx, x, y, w, h, WarmPalette.BORDER)
        val tr = MinecraftClient.getInstance().textRenderer
        val label = "Loading..."
        val tw = tr.getWidth(label)
        ctx.drawTextWithShadow(tr, Text.literal(label),
            x + (w - tw) / 2, y + h / 2 - 4, WarmPalette.MUTED)
    }

    private fun paintVideoPlaceholder(ctx: DrawContext, name: String, x: Int, y: Int, w: Int, h: Int) {
        ctx.fill(x, y, x + w, y + h, 0xD0141414.toInt())
        drawRoundedBorder(ctx, x, y, w, h, WarmPalette.ACCENT)
        val tr = MinecraftClient.getInstance().textRenderer
        val title = "MP4 preview not supported"
        val titleW = tr.getWidth(title)
        ctx.drawTextWithShadow(tr, Text.literal(title),
            x + (w - titleW) / 2, y + h / 2 - 10, WarmPalette.ACCENT_BRIGHT)
        val sub = if (tr.getWidth(name) > w - 12) {
            tr.trimToWidth(name, w - 12 - tr.getWidth("…")) + "…"
        } else name
        val subW = tr.getWidth(sub)
        ctx.drawTextWithShadow(tr, Text.literal(sub),
            x + (w - subW) / 2, y + h / 2 + 2, WarmPalette.MUTED)
    }
}
