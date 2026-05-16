package ahjd.icomod.mixin

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.gifpicker.ChatGifRenderer
import ahjd.icomod.features.gifpicker.GifSize
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.hud.ChatHud
import net.minecraft.client.gui.hud.ChatHudLine
import org.spongepowered.asm.mixin.Final
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Inline-renders catalog-matched gifs in the chat HUD. Each gif sits below the
 * chat line whose text contains its filename, growing into the blank rows inserted
 * by [ahjd.icomod.features.gifpicker.GifChatPadding].
 */
@Mixin(ChatHud::class)
abstract class ChatHudMixin {

    @Shadow @Final private lateinit var visibleMessages: MutableList<ChatHudLine.Visible>
    @Shadow private var scrolledLines: Int = 0

    @Shadow abstract fun getChatScale(): Double
    @Shadow abstract fun getVisibleLineCount(): Int
    @Shadow abstract fun isChatFocused(): Boolean

    @Inject(method = ["render"], at = [At("TAIL")])
    private fun icomod_drawGifs(
        ctx: DrawContext,
        textRenderer: TextRenderer,
        currentTick: Int,
        mouseX: Int,
        mouseY: Int,
        focused: Boolean,
        extra: Boolean,
        ci: CallbackInfo
    ) {
        if (!ConfigManager.config.gifsEnabled) return
        if (visibleMessages.isEmpty()) return

        val firstIdx = scrolledLines.coerceAtLeast(0)
        val visibleLineCount = getVisibleLineCount()
        val lastIdx = (firstIdx + visibleLineCount - 1).coerceAtMost(visibleMessages.size - 1)
        if (firstIdx > lastIdx) return

        val scale = getChatScale().toFloat().coerceAtLeast(0.1f)
        val scaledHeight = ctx.scaledWindowHeight
        val bottomPx = if (isChatFocused()) scaledHeight - 40 else scaledHeight - 28
        val leftPx = 4
        val lineHeight = 9

        val matrices = ctx.matrices
        matrices.pushMatrix()
        matrices.scale(scale, scale)

        val baseXCs = (leftPx / scale).toInt()
        val bottomCs = (bottomPx / scale).toInt()

        val scanLastIdx = (lastIdx + 8).coerceAtMost(visibleMessages.size - 1)
        for (visIdx in firstIdx..scanLastIdx) {
            val m = visIdx - firstIdx
            val visible = visibleMessages[visIdx]
            val match = ChatGifRenderer.findGif(visible) ?: continue
            if (!isChatFocused() && lineOpacity(currentTick, visible) < 1.0f) continue

            val lineBottomCs = bottomCs - m * lineHeight
            val lineTopCs = lineBottomCs - lineHeight
            val chatTopCs = bottomCs - visibleLineCount * lineHeight

            val closedChatLift = when {
                isChatFocused() -> 0
                match.size == GifSize.XS -> 14
                else -> 12
            }
            val gifTopCs = lineBottomCs - closedChatLift
            val gifBottomCs = gifTopCs + match.size.height
            val clipTopCs = maxOf(chatTopCs, gifTopCs)
            val clipBottomLimitCs = if (isChatFocused()) bottomCs else bottomCs - 2
            val clipBottomCs = minOf(clipBottomLimitCs, gifBottomCs)
            if (clipBottomCs <= clipTopCs || lineTopCs >= bottomCs) continue

            ChatGifRenderer.drawAtClipped(ctx, match.entry, match.size, baseXCs, gifTopCs, clipTopCs, clipBottomCs)
        }

        matrices.popMatrix()
    }

    private fun lineOpacity(currentTick: Int, line: ChatHudLine.Visible): Float {
        var opacity = 1.0 - (currentTick - line.addedTime()).toDouble() / 200.0
        opacity = (opacity * 10.0).coerceIn(0.0, 1.0)
        return (opacity * opacity).toFloat()
    }
}
