package ahjd.icomod.features.gifpicker

import ahjd.icomod.config.ConfigManager
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.text.MutableText
import net.minecraft.text.Text

/**
 * Pads any incoming game message that contains a catalog gif filename with trailing
 * newlines, so vanilla wraps the message into multiple chat rows. The empty rows
 * below the actual text become canvas for [ChatGifRenderer] to draw the gif into.
 */
object GifChatPadding {
    /** Chat line height in chat-space pixels. Matches vanilla. */
    private const val LINE_HEIGHT = 9

    fun register() {
        // MODIFY_GAME covers all unsigned text from the server (which is what Wynncraft uses).
        // There is no MODIFY_CHAT in fabric-message-api-v1; signed player chat would need a
        // separate path if we ever leave Wynncraft.
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, _ ->
            if (!ConfigManager.config.gifsEnabled) return@register message
            maybePad(message)
        }
    }

    private fun maybePad(original: Text): Text {
        val match = findCatalogGif(original) ?: return original
        val padded: MutableText = Text.empty().append(original)
        repeat(padRows(match.size)) { padded.append("\n") }
        return padded
    }

    private fun padRows(size: GifSize): Int =
        (size.height + LINE_HEIGHT - 1) / LINE_HEIGHT

    private fun findCatalogGif(text: Text): ChatGifRenderer.Match? {
        val plain = StringBuilder()
        text.asOrderedText().accept { _, _, codePoint ->
            plain.appendCodePoint(codePoint)
            true
        }
        return ChatGifRenderer.findGif(plain.toString())
    }
}
