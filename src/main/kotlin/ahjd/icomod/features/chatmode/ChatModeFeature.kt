package ahjd.icomod.features.chatmode

import ahjd.icomod.config.ConfigManager
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.MinecraftClient

object ChatModeFeature {

    // Wynncraft chat-channel commands whose message portion should be rewritten
    private val CHAT_COMMANDS = setOf("p", "g")

    @Volatile private var resending = false

    fun register() {
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (resending) return@register true
            if (!shouldRewrite()) return@register true
            sendChatUnhooked(transform(message))
            false
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (resending) return@register true
            if (!shouldRewrite()) return@register true

            val spaceIdx = command.indexOf(' ')
            if (spaceIdx == -1) return@register true

            val cmdName = command.substring(0, spaceIdx)
            if (cmdName !in CHAT_COMMANDS) return@register true

            val originalMsg = command.substring(spaceIdx + 1)
            if (originalMsg.isBlank()) return@register true

            sendCommandUnhooked("$cmdName ${transform(originalMsg)}")
            false
        }
    }

    private fun shouldRewrite(): Boolean =
        ConfigManager.config.chatModeEnabled && ChatModeManager.currentMode != ChatMode.NORMAL

    private fun transform(message: String): String {
        // Pipeline: WordMap (vocabulary) â†’ GrammarFixer (caps/contractions/period)
        return GrammarFixer.fix(ChatModeManager.applyWordMap(message))
    }

    /** Send a chat message verbatim, bypassing the chat-mode rewrite. */
    fun sendRaw(message: String) = sendChatUnhooked(message)

    private fun sendChatUnhooked(message: String) {
        resending = true
        try {
            MinecraftClient.getInstance().player?.networkHandler?.sendChatMessage(message)
        } finally {
            resending = false
        }
    }

    private fun sendCommandUnhooked(command: String) {
        resending = true
        try {
            MinecraftClient.getInstance().player?.networkHandler?.sendChatCommand(command)
        } finally {
            resending = false
        }
    }
}
