package ahjd.icomod.features.chatmode

import ahjd.icomod.config.ConfigManager
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.MinecraftClient

object ChatModeFeature {

    // Wynncraft chat-channel commands whose message portion should be rewritten
    private val CHAT_COMMANDS = setOf("p", "g")

    // Bypass token: messages containing this verbatim are sent as-is regardless
    // of the active chat mode. Used by other Wynncraft mods (e.g. WynnMod) that
    // do version-handshake pings via in-channel messages — rewriting those
    // would break protocol-level matching.
    private const val BYPASS_TOKEN = "wynnmod ping"

    // GIF references must pass through verbatim so the server-side catalog
    // matcher can find them and the in-chat renderer can substitute the image.
    // Matches `foo.gif`, `foo.gifS`, `bar.png`, `baz.jpeg`, etc — same shape
    // as ChatGifRenderer.NAME_RE so the bypass set matches the renderer's set.
    private val IMAGE_TOKEN_RE = Regex(
        "[a-z0-9_-]+\\.(?:png|jpe?g|gif)(?:xs|s|m|l)?\\b",
        RegexOption.IGNORE_CASE,
    )

    @Volatile private var resending = false

    fun register() {
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (resending) return@register true
            if (!shouldRewrite(message)) return@register true
            sendChatUnhooked(transform(message))
            false
        }

        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (resending) return@register true

            val spaceIdx = command.indexOf(' ')
            if (spaceIdx == -1) return@register true

            val cmdName = command.substring(0, spaceIdx)
            if (cmdName !in CHAT_COMMANDS) return@register true

            val originalMsg = command.substring(spaceIdx + 1)
            if (originalMsg.isBlank()) return@register true
            if (!shouldRewrite(originalMsg)) return@register true

            sendCommandUnhooked("$cmdName ${transform(originalMsg)}")
            false
        }
    }

    private fun shouldRewrite(message: String): Boolean {
        if (!ConfigManager.config.chatModeEnabled) return false
        if (ChatModeManager.currentMode == ChatMode.NORMAL) return false
        if (message.contains(BYPASS_TOKEN, ignoreCase = true)) return false
        if (IMAGE_TOKEN_RE.containsMatchIn(message)) return false
        return true
    }

    private fun transform(message: String): String {
        // Chinese mode interleaves translations after every 2 English words
        // instead of replacing them — GrammarFixer would clobber the CJK output.
        if (ChatModeManager.currentMode == ChatMode.CHINESE) {
            return ChineseInjector.inject(message)
        }
        // Pipeline: WordMap (vocabulary) → GrammarFixer (caps/contractions/period)
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
