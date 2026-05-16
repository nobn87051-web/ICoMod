package ahjd.icomod.mixin

import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifPickerScreen
import net.minecraft.client.gui.screen.ChatScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(ChatScreen::class)
abstract class ChatScreenMixin(title: Text) : Screen(title) {

    @Shadow protected lateinit var chatField: TextFieldWidget

    @Inject(method = ["init"], at = [At("TAIL")])
    private fun onInit(ci: CallbackInfo) {
        // Chat-mode cycle button (existing) â€” bottom row above chat input
        addDrawableChild(
            ButtonWidget.builder(modeLabel()) { btn ->
                ChatModeManager.cycleMode()
                btn.message = modeLabel()
                btn.setTooltip(Tooltip.of(modeTooltip()))
            }
                .dimensions(width - 73, height - 40, 44, 24)
                .tooltip(Tooltip.of(modeTooltip()))
                .build()
        )

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

    private fun modeLabel() = Text.literal("[${ChatModeManager.currentMode.icon}]")
    private fun modeTooltip() = Text.literal("Chat Mode: ${ChatModeManager.currentMode.displayName}")
}
