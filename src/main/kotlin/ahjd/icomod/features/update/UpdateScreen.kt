package ahjd.icomod.features.update

import ahjd.icomod.features.settings.ui.WarmButton
import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.settings.ui.drawOrnamentalBorder
import ahjd.icomod.features.settings.ui.drawRoundedBorder
import ahjd.icomod.features.settings.ui.fillRounded
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

/**
 * Optional update prompt shown once per session when a newer version exists.
 * Always dismissible — the user may stay on any version.
 *
 * Update Now streams the download with a live progress bar, then offers
 * **Restart Now** (quits so the swap applies immediately) or **On Next Launch**
 * (keep playing; the staged swap completes whenever Minecraft next exits).
 */
class UpdateScreen(
    private val result: UpdateChecker.Result,
    private val parent: Screen?,
) : Screen(Text.literal("IcoMod Update")) {

    private val panelW = 300
    private val panelH = 172
    private var px = 0
    private var py = 0
    private var bw = 0
    private var cx = 0
    private var rowY = 0

    private lateinit var btnLeft: WarmButton    // Update Now / Retry / Restart Now / Downloading
    private lateinit var btnRight: WarmButton    // Later / On Next Launch

    override fun init() {
        super.init()
        px = (width - panelW) / 2
        py = (height - panelH) / 2
        cx = px + panelW / 2
        val pad = 16
        val gap = 8
        bw = (panelW - pad * 2 - gap) / 2
        val bh = 20
        rowY = py + panelH - 40

        btnLeft = addDrawableChild(
            WarmButton(cx - bw - gap / 2, rowY, bw, bh, Text.literal("Update Now"), WarmButton.Style.PRIMARY) { onUpdate() }
        )
        btnRight = addDrawableChild(
            WarmButton(cx + gap / 2, rowY, bw, bh, Text.literal("Later"), WarmButton.Style.GHOST) { close() }
        )
    }

    private fun onUpdate() {
        val url = result.jarUrl ?: return
        UpdateInstaller.install(url, result.sha256)
    }

    /** Quit the client so the JVM exits and the staged swap runs immediately. */
    private fun restartNow() {
        MinecraftClient.getInstance().scheduleStop()
    }

    /** Keep playing — the armed swap completes whenever Minecraft next exits. */
    private fun finalizeLater() {
        MinecraftClient.getInstance().setScreen(parent)
    }

    override fun renderBackground(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // No-op: render() paints scrim + panel; vanilla dirt/blur suppressed.
    }

    /** Reflect the installer's live state in the two buttons each frame. */
    private fun refreshButtons() {
        when (UpdateInstaller.state) {
            UpdateInstaller.State.STAGED -> {
                btnLeft.active = true
                btnLeft.message = Text.literal("Restart Now")
                btnLeft.setAction { restartNow() }

                btnRight.visible = true
                btnRight.active = true
                btnRight.message = Text.literal("On Next Launch")
                btnRight.setAction { finalizeLater() }
            }
            UpdateInstaller.State.DOWNLOADING -> {
                btnLeft.active = false
                btnLeft.message = Text.literal("Downloading...")
                btnRight.visible = false
            }
            else -> { // IDLE or FAILED
                btnLeft.active = true
                btnLeft.message = Text.literal(if (UpdateInstaller.state == UpdateInstaller.State.FAILED) "Retry" else "Update Now")
                btnLeft.setAction { onUpdate() }

                btnRight.visible = true
                btnRight.active = true
                btnRight.message = Text.literal("Later")
                btnRight.setAction { close() }
            }
        }
        // Center the left button when it stands alone (download in progress).
        btnLeft.x = if (btnRight.visible) cx - bw - 4 else cx - bw / 2
    }

    override fun render(ctx: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        refreshButtons()
        ctx.fill(0, 0, width, height, WarmPalette.SCRIM)
        fillRounded(ctx, px, py, panelW, panelH, WarmPalette.CARD)
        drawOrnamentalBorder(ctx, px, py, panelW, panelH, WarmPalette.ACCENT, WarmPalette.ACCENT_BRIGHT)

        // Title
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Update Available"), cx, py + 20, WarmPalette.ACCENT_BRIGHT)

        // Version transition + behind badge
        ctx.drawCenteredTextWithShadow(
            textRenderer, Text.literal("§7v${result.current}  §8->  §fv${result.latest}"),
            cx, py + 40, WarmPalette.TEXT,
        )
        val behind = "${result.behind} version${if (result.behind == 1) "" else "s"} behind"
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(behind), cx, py + 54, WarmPalette.DIM)

        // Hairline divider
        ctx.fill(px + 24, py + 70, px + panelW - 24, py + 71, WarmPalette.BORDER_HAIRLINE)

        // Status zone: progress bar while downloading, else a status line.
        if (UpdateInstaller.state == UpdateInstaller.State.DOWNLOADING) {
            drawProgressBar(ctx, py + 86)
        } else {
            val (status, color) = when (UpdateInstaller.state) {
                UpdateInstaller.State.STAGED -> "Update ready — restart to apply." to WarmPalette.SUCCESS_BRIGHT
                UpdateInstaller.State.FAILED -> "Update failed — check the log." to WarmPalette.DANGER_BRIGHT
                else -> "A new version is available." to WarmPalette.MUTED
            }
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(status), cx, py + 86, color)
        }

        super.render(ctx, mouseX, mouseY, delta)
    }

    /** Rounded track with an accent fill + percent label (or a marquee when
     *  the server didn't report a content length). */
    private fun drawProgressBar(ctx: DrawContext, y: Int) {
        val barX = px + 40
        val barW = panelW - 80
        val barH = 8
        fillRounded(ctx, barX, y, barW, barH, WarmPalette.INPUT)
        drawRoundedBorder(ctx, barX, y, barW, barH, WarmPalette.BORDER_SOFT)

        val p = UpdateInstaller.progress
        val label: String
        if (p >= 0f) {
            val fillW = (barW * p).toInt().coerceIn(0, barW)
            if (fillW > 2) fillRounded(ctx, barX, y, fillW, barH, WarmPalette.ACCENT)
            label = "Downloading...  ${(p * 100).toInt()}%"
        } else {
            // Indeterminate marquee — a small block sweeping the track.
            val blockW = barW / 4
            val t = (System.nanoTime() / 4_000_000L % (barW + blockW)).toInt() - blockW
            val sx = (barX + t).coerceIn(barX, barX + barW - 1)
            val ex = (barX + t + blockW).coerceIn(barX, barX + barW)
            if (ex > sx) fillRounded(ctx, sx, y, ex - sx, barH, WarmPalette.ACCENT)
            label = "Downloading..."
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label), cx, y + barH + 4, WarmPalette.ACCENT_BRIGHT)
    }

    override fun shouldPause(): Boolean = false

    override fun close() {
        MinecraftClient.getInstance().setScreen(parent)
    }
}
