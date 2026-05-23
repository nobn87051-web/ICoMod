package ahjd.icomod.features.overlay

import ahjd.icomod.config.ConfigManager
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen

/**
 * Mouse routing for overlay manipulation. Bound via [ScreenMouseEvents] to
 * every [Screen] that opens; handlers self-gate on [isInteractive] so the
 * overlay only responds when the user has authority — always for chat,
 * and for any other screen when `overlayShowOverGui` is enabled.
 *
 * Also registers a per-screen [ScreenEvents.afterRender] hook on non-chat
 * screens so overlays paint on top of inventory / chest / etc. GUIs when
 * `overlayShowOverGui` is on (the HUD layer is suspended while a Screen
 * is active, so it can't carry that case).
 *
 * Button mapping (LMB = 0, RMB = 1, MMB = 2):
 *  - **LMB press** inside any visible entry  -> bring to front, start drag
 *  - **LMB drag** while dragging              -> move via [tickDrag] poll
 *  - **LMB release**                          -> stop drag, persist
 *  - **RMB** inside any visible entry         -> hide
 *  - **MMB** inside any visible entry         -> reset scale to 1.0
 *  - **Scroll** inside any visible entry      -> zoom around cursor
 *
 * Hit-testing uses [MediaOverlayManager.allByStack] in reverse so the
 * visually top-most overlay is always grabbed first.
 *
 * Returning `false` from an `ALLOW_*` callback cancels the default screen
 * handling, so overlay clicks don't pass through to the underlying GUI.
 */
object MediaOverlayInput {

    /** Filename of the entry currently being LMB-dragged, or null. */
    @Volatile var draggingName: String? = null

    /** Cursor offset (in scaled GUI px) inside the dragged entry at press time. */
    private var dragOffX = 0.0
    private var dragOffY = 0.0

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            // Bind to every screen — handlers gate themselves on the live
            // `overlayShowOverGui` flag (chat always passes). Binding once
            // per screen via AFTER_INIT is correct: subscriptions die with
            // the screen, so we don't leak listeners.

            ScreenMouseEvents.allowMouseClick(screen).register { _, click: Click ->
                if (!isInteractive(screen)) return@register true
                !onClick(click.x(), click.y(), click.button())
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { _, click: Click ->
                if (!isInteractive(screen)) return@register true
                onRelease(click.button())
                true
            }
            ScreenMouseEvents.allowMouseScroll(screen).register { _, mx, my, _, vert ->
                if (!isInteractive(screen)) return@register true
                !onScroll(mx, my, vert)
            }

            // ChatScreen renders via the HUD layer (chat doesn't fully cover
            // the screen). Every OTHER screen needs an explicit paint pass
            // because the HUD layer is suspended while a Screen is active.
            if (screen !is ChatScreen) {
                ScreenEvents.afterRender(screen).register { _, ctx, _, _, _ ->
                    val cfg = ConfigManager.config
                    if (cfg.overlayEnabled && cfg.overlayShowOverGui) {
                        MediaOverlayRenderer.renderManual(ctx)
                    }
                }
            }
        }
    }

    /**
     * Whether overlay input should be live for this screen RIGHT NOW.
     * Re-evaluated each event so toggling [ConfigManager.config.overlayShowOverGui]
     * takes effect mid-screen without re-opening.
     */
    private fun isInteractive(screen: Screen): Boolean {
        val cfg = ConfigManager.config
        if (!cfg.overlayEnabled) return false
        return screen is ChatScreen || cfg.overlayShowOverGui
    }

    /** Returns true if consumed (cancel default screen handling). */
    private fun onClick(mx: Double, my: Double, button: Int): Boolean {
        if (!ConfigManager.config.overlayEnabled) return false
        val hit = hitTopMost(mx, my) ?: return false
        when (button) {
            0 -> {
                // Bring to front. Drag-start ordering is what users intuit as
                // "the one I'm working with comes on top".
                MediaOverlayManager.touch(hit.name)
                draggingName = hit.name
                dragOffX = mx - MediaOverlayManager.state(hit.name).x
                dragOffY = my - MediaOverlayManager.state(hit.name).y
                return true
            }
            1 -> { MediaOverlayManager.setHidden(hit.name, true); return true }
            2 -> { MediaOverlayManager.resetScale(hit.name); return true }
        }
        return false
    }

    private fun onRelease(button: Int) {
        if (button == 0 && draggingName != null) {
            // Drag loop used setPosTransient — write final position to disk now.
            MediaOverlayManager.persist()
            draggingName = null
        }
    }

    /**
     * Bound to a per-frame poll in [tickDrag], not a separate ALLOW callback,
     * because Fabric's mouseDragged event isn't always fired by ChatScreen
     * (depends on Mojang's input dispatch). We instead poll cursor position
     * from the main tick while [draggingName] is set.
     */
    fun tickDrag() {
        val name = draggingName ?: return
        val cfg = ConfigManager.config
        if (!cfg.overlayEnabled) {
            // Lost authority — commit whatever the drag tick wrote in memory
            // so a screen-close mid-drag doesn't lose the position.
            MediaOverlayManager.persist()
            draggingName = null
            return
        }
        val mc = MinecraftClient.getInstance()
        val screen = mc.currentScreen
        // End drag if we lost the screen authority that started it. Chat
        // always counts; other screens only when over-GUI is enabled.
        val canDrag = screen is ChatScreen ||
                      (screen != null && cfg.overlayShowOverGui)
        if (!canDrag) {
            MediaOverlayManager.persist()
            draggingName = null
            return
        }
        val win = mc.window
        val mouse = mc.mouse
        val sf = win.scaleFactor.toDouble().coerceAtLeast(1.0)
        val mx = mouse.x / sf
        val my = mouse.y / sf

        val entry = MediaOverlayManager.all().find { it.name == name } ?: run {
            draggingName = null; return
        }
        val st = MediaOverlayManager.state(name)
        val w = (entry.origW * st.scale).toInt().coerceAtLeast(8)
        val h = (entry.origH * st.scale).toInt().coerceAtLeast(8)
        val sw = win.scaledWidth
        val sh = win.scaledHeight
        val (nx, ny) = MediaOverlayRenderer.clampPos(
            (mx - dragOffX).toInt(),
            (my - dragOffY).toInt(),
            w, h, sw, sh
        )
        // Transient: avoid hitting disk 20x/s during drag. onRelease persists.
        MediaOverlayManager.setPosTransient(name, nx, ny)
    }

    private fun onScroll(mx: Double, my: Double, vertical: Double): Boolean {
        if (!ConfigManager.config.overlayEnabled) return false
        val hit = hitTopMost(mx, my) ?: return false
        val st = MediaOverlayManager.state(hit.name)
        val oldScale = st.scale
        val factor = if (vertical > 0) 1.1 else 1.0 / 1.1
        val newScale = (oldScale * factor).coerceIn(0.1, 5.0)
        if (newScale == oldScale) return true  // hit clamp; still consume

        // Anchor zoom around cursor: keep the pixel under the cursor stationary.
        val oldW = hit.origW * oldScale
        val oldH = hit.origH * oldScale
        val anchorU = (mx - st.x) / oldW
        val anchorV = (my - st.y) / oldH
        val newW = hit.origW * newScale
        val newH = hit.origH * newScale
        val newX = (mx - anchorU * newW).toInt()
        val newY = (my - anchorV * newH).toInt()

        MediaOverlayManager.setScale(hit.name, newScale)
        // Re-clamp under new dimensions
        val mc = MinecraftClient.getInstance()
        val (cx, cy) = MediaOverlayRenderer.clampPos(
            newX, newY,
            newW.toInt().coerceAtLeast(8),
            newH.toInt().coerceAtLeast(8),
            mc.window.scaledWidth, mc.window.scaledHeight,
        )
        MediaOverlayManager.setPos(hit.name, cx, cy)
        return true
    }

    /**
     * Walk the stack from top to bottom (reversed render order) and return
     * the first entry whose bounds contain the cursor. Uses [allByStack] to
     * match the renderer's z-order — without this, clicking on a dragged
     * entry that's visually on top could grab the one underneath.
     */
    private fun hitTopMost(mx: Double, my: Double): MediaOverlayManager.Entry? {
        val list = MediaOverlayManager.allByStack()
        for (i in list.indices.reversed()) {
            val e = list[i]
            val st = MediaOverlayManager.state(e.name)
            if (st.hidden) continue
            val w = (e.origW * st.scale).toInt().coerceAtLeast(8)
            val h = (e.origH * st.scale).toInt().coerceAtLeast(8)
            if (mx >= st.x && mx < st.x + w && my >= st.y && my < st.y + h) return e
        }
        return null
    }
}
