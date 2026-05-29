package ahjd.icomod.mixin

import ahjd.icomod.features.sounds.SpellCastParser
import ahjd.icomod.features.sounds.SpellSoundManager
import ahjd.icomod.util.AhjLog
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Read-only sniffer on the inbound action-bar / system-message packet.
 *
 * Wynncraft appends a plain-ASCII `"<Spell Name> Cast! -<mana>"` to the
 * action-bar text component on every completed spell cast. By the time
 * vanilla dispatches that packet to the chat listener, Wynntils'
 * `ChatListenerMixin` may have already cancelled the dispatch (its
 * "Spell Cast Vignette" feature wraps `handleSystemMessage` and aborts
 * the original call so it can render its own overlay instead). That's
 * why a JsMacros Title-event listener -- which hooks the chat-listener
 * level -- sees zero action-bar events when Wynntils is loaded.
 *
 * Hooking here, on the packet itself, fires before any downstream
 * cancellation. We do NOT cancel or mutate the packet; the chat
 * listener still gets the original component so Wynntils' renderer is
 * undisturbed.
 *
 * Detection is gated on `packet.overlay == true` so chat / system
 * messages don't trigger a regex pass per line.
 *
 * Helper logic (regex, ASCII strip) lives in [SpellCastParser] -- a
 * Kotlin `companion object` on the mixin class itself would emit a
 * static `Companion` field that Mixin rejects at apply time.
 */
@Mixin(ClientPlayNetworkHandler::class)
abstract class GameMessageS2CMixin {

    @Inject(method = ["onGameMessage"], at = [At("HEAD")])
    private fun icomod_sniffSpellCast(packet: GameMessageS2CPacket, ci: CallbackInfo) {
        if (!packet.overlay) return
        try {
            val parsed = SpellCastParser.parse(packet.content.string) ?: return
            SpellSoundManager.onSpellCast(parsed.name, parsed.cost)
        } catch (e: Throwable) {
            AhjLog.error("GameMessageS2CMixin", "actionbar parse failed", e)
        }
    }
}
