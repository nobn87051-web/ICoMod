package ahjd.icomod.client

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.chatmode.ChatModeFeature
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifCatalog
import ahjd.icomod.features.gifpicker.GifChatPadding
import net.fabricmc.api.ClientModInitializer

class IcomodClient : ClientModInitializer {

    override fun onInitializeClient() {
        ConfigManager.load()
        ChatModeManager.init()
        ChatModeFeature.register()
        GifCatalog.refreshAsync()
        GifChatPadding.register()
    }
}
