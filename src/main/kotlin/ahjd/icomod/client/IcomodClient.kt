package ahjd.icomod.client

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.chatmode.ChatModeFeature
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifCatalog
import ahjd.icomod.features.gifpicker.GifChatPadding
import ahjd.icomod.features.settings.SettingsScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil

class IcomodClient : ClientModInitializer {

    override fun onInitializeClient() {
        ConfigManager.load()
        ChatModeManager.init()
        ChatModeFeature.register()
        GifCatalog.refreshAsync()
        GifChatPadding.register()
        registerKeybinds()
    }

    private fun registerKeybinds() {
        val settingsKey = KeyBindingHelper.registerKeyBinding(
            KeyBinding("key.icomod.settings", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.code, KeyBinding.Category.MISC)
        )
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (settingsKey.wasPressed()) {
                mc.setScreen(SettingsScreen(mc.currentScreen))
            }
        }
    }
}
