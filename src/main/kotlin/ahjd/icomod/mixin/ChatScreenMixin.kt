package ahjd.icomod.mixin

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.chatmode.ChatMode
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifPickerScreen
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin(title: Text) : Screen(title) {

    @Shadow protected lateinit var chatField: TextFieldWidget

    @Unique private var icomod_gifX = 0
    @Unique private var icomod_gifY = 0
    @Unique private var icomod_modeX = 0
    @Unique private var icomod_modeY = 0
    @Unique private val BTN_W = 44
    @Unique private val BTN_H = 24

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun onInit(ci: CallbackInfo) {
        icomod_gifX  = width - 73;  icomod_gifY  = height - 68
        icomod_modeX = width - 73;  icomod_modeY = height - 40
    }

    @Inject(method = ["render"], at = [At("TAIL")])
    private fun icomod_renderButtons(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, ci: CallbackInfo) {
        val cfg = ConfigManager.config
        if (cfg.gifsEnabled) {
            icomod_drawBtn(context, icomod_gifX,  icomod_gifY,  BTN_W, BTN_H, "[GIF]", mouseX, mouseY)
            if (icomod_hovered(mouseX, mouseY, icomod_gifX,  icomod_gifY))
                context.drawTooltip(textRenderer, Text.literal("Open GIF picker"), mouseX, mouseY)
        }
        if (cfg.chatModeEnabled) {
            icomod_drawBtn(context, icomod_modeX, icomod_modeY, BTN_W, BTN_H, "[${ChatModeManager.currentMode.icon}]", mouseX, mouseY)
            if (icomod_hovered(mouseX, mouseY, icomod_modeX, icomod_modeY))
                context.drawTooltip(textRenderer, Text.literal("Chat Mode: ${ChatModeManager.currentMode.displayName}"), mouseX, mouseY)
        }
    }

    @Inject(method = ["mouseClicked"], at = [At("HEAD")], cancellable = true)
    private fun icomod_mouseClicked(click: Click, doubled: Boolean, cir: CallbackInfoReturnable<Boolean>) {
        val mx = click.x().toInt()
        val my = click.y().toInt()
        val cfg = ConfigManager.config

        when (click.button()) {
            0 -> {
                if (cfg.gifsEnabled && icomod_hovered(mx, my, icomod_gifX, icomod_gifY)) {
                    client?.setScreen(GifPickerScreen(chatField.text)); cir.returnValue = true; return
                }
                if (cfg.chatModeEnabled && icomod_hovered(mx, my, icomod_modeX, icomod_modeY)) {
                    ChatModeManager.cycleMode(); cir.returnValue = true; return
                }
            }
            2 -> {
                if (!cfg.chatModeEnabled) return
                if (ChatModeManager.currentMode == ChatMode.NORMAL) return
                ChatModeManager.resetToNormal(); cir.returnValue = true
            }
        }
    }

    @Unique
    private fun icomod_hovered(mx: Int, my: Int, bx: Int, by: Int) =
        mx in bx..(bx + BTN_W) && my in by..(by + BTN_H)

    @Unique
    private fun icomod_drawBtn(
        ctx: DrawContext, x: Int, y: Int, w: Int, h: Int,
        label: String, mouseX: Int, mouseY: Int,
    ) {
        val hovered = icomod_hovered(mouseX, mouseY, x, y)
        val bg     = if (hovered) 0xE03A3A3A.toInt() else 0xE01F1F1F.toInt()
        val border = if (hovered) 0xFFFFE066.toInt() else 0xFFFFCC33.toInt()
        val text   = if (hovered) 0xFFFFFFFF.toInt() else 0xFFFFE066.toInt()

        // Rounded-ish 2px-corner fill
        ctx.fill(x + 2, y, x + w - 2, y + 2, bg)
        ctx.fill(x, y + 2, x + w, y + h - 2, bg)
        ctx.fill(x + 2, y + h - 2, x + w - 2, y + h, bg)

        // Rounded border
        ctx.fill(x + 2, y,         x + w - 2, y + 1,     border)
        ctx.fill(x + 2, y + h - 1, x + w - 2, y + h,     border)
        ctx.fill(x,     y + 2,     x + 1,     y + h - 2, border)
        ctx.fill(x + w - 1, y + 2, x + w,     y + h - 2, border)
        ctx.fill(x + 1, y + 1, x + 2, y + 2, border)
        ctx.fill(x + w - 2, y + 1, x + w - 1, y + 2, border)
        ctx.fill(x + 1, y + h - 2, x + 2, y + h - 1, border)
        ctx.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, border)

        val tw = textRenderer.getWidth(label)
        ctx.drawTextWithShadow(textRenderer, Text.literal(label),
            x + (w - tw) / 2, y + (h - 8) / 2, text)
    }
}
