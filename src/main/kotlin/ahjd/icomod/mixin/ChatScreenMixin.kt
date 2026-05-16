package ahjd.icomod.mixin

import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifPickerScreen
import net.minecraft.client.gui.Click
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
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

    @Unique private var icomod_modeButton: ButtonWidget? = null

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun onInit(ci: CallbackInfo) {
        // Chat-mode cycle button (existing) — bottom row above chat input.
        // Middle-clicking anywhere in the chat screen resets to NORMAL.
        val modeBtn = ButtonWidget.builder(modeLabel()) { btn ->
            ChatModeManager.cycleMode()
            btn.message = modeLabel()
            btn.setTooltip(Tooltip.of(modeTooltip()))
        }
            .dimensions(width - 73, height - 40, 44, 24)
            .tooltip(Tooltip.of(modeTooltip()))
            .build()
        icomod_modeButton = modeBtn
        addDrawableChild(modeBtn)

        // GIF picker button â€” sits directly above the chat-mode button
        addDrawableChild(
            ButtonWidget.builder(Text.literal("[GIF]")) {
                client?.setScreen(GifPickerScreen(chatField.text))
            }
                .dimensions(width - 73, height - 68, 44, 24)
                .tooltip(Tooltip.of(Text.literal("Open GIF picker")))
                .build()
        )
    }

    @Inject(method = ["mouseClicked"], at = [At("HEAD")], cancellable = true)
    private fun icomod_chatMiddleClickReset(click: Click, doubled: Boolean, cir: CallbackInfoReturnable<Boolean>) {
        // GLFW middle button == 2. Reset chat mode to NORMAL and consume the click
        // so it doesn't bleed into widgets behind. The button label refreshes via
        // the stored reference so the user sees the change immediately.
        if (click.button() != 2) return
        if (ChatModeManager.currentMode == ahjd.icomod.features.chatmode.ChatMode.NORMAL) return
        ChatModeManager.resetToNormal()
        icomod_modeButton?.let {
            it.message = modeLabel()
            it.setTooltip(Tooltip.of(modeTooltip()))
        }
        cir.returnValue = true
    }

    private fun modeLabel() = Text.literal("[${ChatModeManager.currentMode.icon}]")
    private fun modeTooltip() = Text.literal("Chat Mode: ${ChatModeManager.currentMode.displayName}")
}
