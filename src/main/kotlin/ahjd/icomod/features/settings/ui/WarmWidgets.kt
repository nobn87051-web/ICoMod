package ahjd.icomod.features.settings.ui

import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import kotlin.math.exp

/**
 * Warm-skinned [ClickableWidget] for use on vanilla [net.minecraft.client.gui.screen.Screen]s
 * (where the [WarmControl] family — driven by SettingsScreen's own mouse
 * routing — can't be used). Subclassing ClickableWidget keeps vanilla
 * focus/child plumbing for free; we repaint and route the click ourselves.
 *
 * Three semantic styles so positive / destructive / dismissive actions read at
 * a glance. Inactive buttons drop to a flat dimmed look with no hover.
 */
class WarmButton(
    x: Int, y: Int, w: Int, h: Int,
    message: Text,
    var style: Style,
    private var onPress: () -> Unit,
) : ClickableWidget(x, y, w, h, message) {

    enum class Style { PRIMARY, DANGER, GHOST }

    /** Repoint the click action (used when a button's role changes by state). */
    fun setAction(action: () -> Unit) { onPress = action }

    private var hoverT = 0f
    private var lastNs = 0L

    override fun mouseClicked(click: Click, doubled: Boolean): Boolean {
        if (click.button() == 0 && active && visible && isMouseOver(click.x(), click.y())) {
            onPress.invoke()
            return true
        }
        return false
    }

    override fun appendClickableNarrations(builder: NarrationMessageBuilder) =
        appendDefaultNarrations(builder)

    override fun renderWidget(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1_000_000_000.0).toFloat()
        lastNs = now
        val target = if (active && isHovered) 1f else 0f
        hoverT += (target - hoverT) * (1f - exp(-dt * 14f))
        val hv = hoverT

        val (bg, border, text) = when {
            !active -> Triple(WarmPalette.SURFACE, WarmPalette.BORDER, WarmPalette.DIM)
            style == Style.PRIMARY -> Triple(
                lerpColor(WarmPalette.ACCENT_FAINT, WarmPalette.ACCENT_HOVER, hv),
                lerpColor(WarmPalette.ACCENT, WarmPalette.ACCENT_BRIGHT, hv),
                lerpColor(WarmPalette.ACCENT_BRIGHT, 0xFFFFFFFF.toInt(), hv),
            )
            style == Style.DANGER -> Triple(
                lerpColor(WarmPalette.DANGER_FAINT, (WarmPalette.DANGER and 0x00FFFFFF) or (0x66 shl 24), hv),
                lerpColor(WarmPalette.DANGER, WarmPalette.DANGER_BRIGHT, hv),
                lerpColor(WarmPalette.DANGER_BRIGHT, 0xFFFFFFFF.toInt(), hv),
            )
            else -> Triple(
                lerpColor(WarmPalette.SURFACE, WarmPalette.RAISED, hv),
                lerpColor(WarmPalette.BORDER, WarmPalette.ACCENT_EDGE, hv),
                lerpColor(WarmPalette.MUTED, WarmPalette.ACCENT_BRIGHT, hv),
            )
        }

        val bx = this.x
        val by = this.y
        fillRounded(ctx, bx, by, width, height, bg)
        drawRoundedBorder(ctx, bx, by, width, height, border)
        val tr = MinecraftClient.getInstance().textRenderer
        val label = message.string
        ctx.drawTextWithShadow(tr, label, bx + (width - tr.getWidth(label)) / 2, by + (height - 8) / 2, text)
    }
}

/**
 * Standalone controls owned by [ahjd.icomod.features.settings.SettingsScreen].
 *
 * Each control runs an internal hover animation: [hoverT] eases toward 1 while
 * the cursor is inside, toward 0 while outside. Rendering interpolates colors
 * via [lerpColor] so transitions feel smooth instead of binary. Toggle also
 * animates an `onT` to slide the knob between off/on positions.
 *
 * Painted with [fillRounded] / [drawRoundedBorder] for 2-px corner softening
 * without any texture lookup.
 */
sealed class WarmControl {
    abstract var x: Int
    abstract var y: Int
    abstract val w: Int
    abstract val h: Int

    protected var hoverT: Float = 0f
    private var lastNs: Long = 0L

    fun hit(mx: Double, my: Double): Boolean =
        mx >= x && mx < x + w && my >= y && my < y + h

    open fun onLeft() {}
    open fun onRight() {}

    abstract fun render(ctx: DrawContext, mx: Int, my: Int)

    /** Advance hover animation. Call at the top of each subclass render(). */
    protected fun tickHover(mx: Int, my: Int): Float {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1_000_000_000.0).toFloat()
        lastNs = now
        val target = if (hit(mx.toDouble(), my.toDouble())) 1f else 0f
        // Exponential easing — time-constant ~80ms feels snappy but not instant.
        val k = 1f - exp(-dt * 14f)
        hoverT += (target - hoverT) * k
        return hoverT
    }

    protected fun textRenderer() = MinecraftClient.getInstance().textRenderer

    protected fun playClick() {
        // Sound API churn skipped — visual state change is enough feedback.
    }

    protected fun centerText(ctx: DrawContext, label: String, color: Int) {
        val tr = textRenderer()
        val tw = tr.getWidth(label)
        ctx.drawTextWithShadow(tr, label, x + (w - tw) / 2, y + (h - 8) / 2, color)
    }
}

/** ON/OFF pill: green when on, red when off. Knob slides on toggle. */
class ToggleControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    val getOn: () -> Boolean,
    val onChange: (Boolean) -> Unit,
) : WarmControl() {

    private var onT: Float = if (getOn()) 1f else 0f
    private var lastOnNs: Long = 0L

    override fun onLeft() { playClick(); onChange(!getOn()) }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val on = getOn()

        // Animate onT toward current state
        val now = System.nanoTime()
        val dt = if (lastOnNs == 0L) 0f else ((now - lastOnNs) / 1_000_000_000.0).toFloat()
        lastOnNs = now
        val target = if (on) 1f else 0f
        val k = 1f - exp(-dt * 16f)
        onT += (target - onT) * k

        val baseBg = lerpColor(WarmPalette.DANGER_FAINT, WarmPalette.SUCCESS_FAINT, onT)
        val hoverBg = lerpColor(WarmPalette.DANGER, WarmPalette.SUCCESS, onT)
        val bg = lerpColor(baseBg, hoverBg and 0x66FFFFFF.toInt() or (0x66 shl 24), hover * 0.5f)
        val border = lerpColor(WarmPalette.DANGER, WarmPalette.SUCCESS, onT)

        fillRounded(ctx, x, y, w, h, bg)
        drawRoundedBorder(ctx, x, y, w, h, border)

        // Sliding knob — 2-px inset from edges
        val knobW = (h - 4)
        val travel = w - knobW - 4
        val knobX = x + 2 + (travel * onT).toInt()
        val knobY = y + 2
        val knobColor = lerpColor(WarmPalette.DANGER_BRIGHT, WarmPalette.SUCCESS_BRIGHT, onT)
        fillRounded(ctx, knobX, knobY, knobW, knobW, knobColor)

        // Label opposite the knob
        val tr = textRenderer()
        val label = if (on) "ON" else "OFF"
        val labelColor = lerpColor(WarmPalette.DANGER_BRIGHT, WarmPalette.SUCCESS_BRIGHT, onT)
        val labelX = if (on) x + 8 else x + w - 8 - tr.getWidth(label)
        ctx.drawTextWithShadow(tr, label, labelX, y + (h - 8) / 2, labelColor)
    }
}

/**
 * Discrete enum cycler. Left click advances, right click goes back.
 * Hover lifts background + brightens arrows.
 */
class CycleControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    val opts: List<String>,
    val getCurrent: () -> String,
    val onChange: (String) -> Unit,
) : WarmControl() {

    override fun onLeft() { playClick(); cycle(forward = true) }
    override fun onRight() { playClick(); cycle(forward = false) }

    private fun cycle(forward: Boolean) {
        if (opts.isEmpty()) return
        val idx = opts.indexOf(getCurrent()).coerceAtLeast(0)
        val next = if (forward) (idx + 1) % opts.size else (idx - 1 + opts.size) % opts.size
        onChange(opts[next])
    }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val bg = lerpColor(WarmPalette.INPUT, WarmPalette.RAISED, hover)
        val border = lerpColor(WarmPalette.BORDER_SOFT, WarmPalette.ACCENT, hover)
        fillRounded(ctx, x, y, w, h, bg)
        drawRoundedBorder(ctx, x, y, w, h, border)
        centerText(ctx, getCurrent(), WarmPalette.ACCENT_BRIGHT)
        val tr = textRenderer()
        val arrowColor = lerpColor(WarmPalette.DIM, WarmPalette.ACCENT_BRIGHT, hover)
        ctx.drawTextWithShadow(tr, "<", x + 6, y + (h - 8) / 2, arrowColor)
        ctx.drawTextWithShadow(tr, ">", x + w - 11, y + (h - 8) / 2, arrowColor)
    }
}

/** Yellow action button. Fills brighten yellow on hover. */
class ActionControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    val label: String,
    val onClick: () -> Unit,
) : WarmControl() {

    override fun onLeft() { playClick(); onClick() }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val bg = lerpColor(WarmPalette.ACCENT_FAINT, WarmPalette.ACCENT_HOVER, hover)
        val border = lerpColor(WarmPalette.ACCENT, WarmPalette.ACCENT_BRIGHT, hover)
        val text = lerpColor(WarmPalette.ACCENT_BRIGHT, 0xFFFFFFFF.toInt(), hover)
        fillRounded(ctx, x, y, w, h, bg)
        drawRoundedBorder(ctx, x, y, w, h, border)
        centerText(ctx, label, text)
    }
}

/** Ghost button — used for top-right "Done". Subtle hover lift. */
class GhostControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    val label: String,
    val onClick: () -> Unit,
) : WarmControl() {

    override fun onLeft() { playClick(); onClick() }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val bg = lerpColor(WarmPalette.SURFACE, WarmPalette.RAISED, hover)
        val border = lerpColor(WarmPalette.BORDER, WarmPalette.ACCENT, hover)
        val color = lerpColor(WarmPalette.MUTED, WarmPalette.ACCENT_BRIGHT, hover)
        fillRounded(ctx, x, y, w, h, bg)
        drawRoundedBorder(ctx, x, y, w, h, border)
        centerText(ctx, label, color)
    }
}

/**
 * Horizontal slider for 0..1 float values. Click anywhere on the track to
 * snap; drag updates while the mouse is held. Knob is a 6px-wide rounded
 * pill; track is a filled rounded rect with an accent-colored fill segment
 * for the current value.
 *
 * Dragging is driven externally by
 * [ahjd.icomod.features.settings.SettingsScreen] which tracks the active
 * slider between `mouseClicked` and `mouseReleased` and forwards mouse-move
 * events into [updateFromMouse].
 */
class SliderControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    val getValue: () -> Float,
    val onChange: (Float) -> Unit,
    /** Optional formatter for the inline label. Default: "NN%". */
    val format: (Float) -> String = { "${(it * 100).toInt()}%" },
) : WarmControl() {

    override fun onLeft() {}  // SettingsScreen calls updateFromMouse + starts drag

    fun updateFromMouse(mx: Double) {
        if (w <= 0) return
        val rel = ((mx - x) / w).coerceIn(0.0, 1.0)
        val newVal = rel.toFloat().coerceIn(0f, 1f)
        if (kotlin.math.abs(newVal - getValue()) > 0.001f) onChange(newVal)
    }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val value = getValue().coerceIn(0f, 1f)

        // Track
        val trackY = y + (h - 6) / 2
        fillRounded(ctx, x, trackY, w, 6, WarmPalette.INPUT)
        drawRoundedBorder(ctx, x, trackY, w, 6,
            lerpColor(WarmPalette.BORDER_SOFT, WarmPalette.ACCENT, hover))

        // Filled portion
        val fillW = (w * value).toInt().coerceAtLeast(0)
        if (fillW > 0) {
            val fillColor = lerpColor(WarmPalette.ACCENT_DEEP, WarmPalette.ACCENT, hover)
            fillRounded(ctx, x, trackY, fillW, 6, fillColor)
        }

        // Knob
        val knobW = 6
        val knobX = (x + (w - knobW) * value).toInt().coerceIn(x, x + w - knobW)
        val knobColor = lerpColor(WarmPalette.ACCENT, WarmPalette.ACCENT_BRIGHT, hover)
        fillRounded(ctx, knobX, y, knobW, h, knobColor)

        // Inline value label, right of slider
        val tr = textRenderer()
        val label = format(value)
        val labelColor = lerpColor(WarmPalette.MUTED, WarmPalette.ACCENT_BRIGHT, hover)
        ctx.drawTextWithShadow(tr, Text.literal(label),
            x + w + 6, y + (h - 8) / 2, labelColor)
    }
}

/**
 * Wraps a vanilla [TextFieldWidget] with our warm-input skin. The widget
 * itself is added as a Screen child for typing/cursor/IME; this class paints
 * the bg + rounded border around it from the screen's render pass.
 */
class TextFieldControl(
    override var x: Int, override var y: Int, override val w: Int, override val h: Int,
    initial: String,
    maxLen: Int,
    onChange: (String) -> Unit,
) : WarmControl() {

    val widget: TextFieldWidget = run {
        val tr = MinecraftClient.getInstance().textRenderer
        val field = TextFieldWidget(tr, x + 6, y + (h - 8) / 2, w - 12, 8, Text.empty())
        field.setMaxLength(maxLen)
        field.text = initial
        field.setEditableColor(WarmPalette.TEXT)
        field.setDrawsBackground(false)
        field.setChangedListener(onChange)
        field
    }

    fun syncWidgetPosition() {
        widget.x = x + 6
        widget.y = y + (h - 8) / 2
    }

    override fun render(ctx: DrawContext, mx: Int, my: Int) {
        val hover = tickHover(mx, my)
        val focused = widget.isFocused
        val border = when {
            focused -> WarmPalette.ACCENT
            else    -> lerpColor(WarmPalette.BORDER_SOFT, WarmPalette.ACCENT_EDGE, hover)
        }
        fillRounded(ctx, x, y, w, h, WarmPalette.INPUT)
        drawRoundedBorder(ctx, x, y, w, h, border)
    }
}
