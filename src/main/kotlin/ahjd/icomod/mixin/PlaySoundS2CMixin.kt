package ahjd.icomod.mixin

import ahjd.icomod.features.sounds.SpellSoundManager
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.PlaySoundS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Routes every incoming sound packet through [SpellSoundManager.onSoundIncoming]
 * for two reasons: feed the plate-trio combo-arming detector, and apply the
 * three opt-in global mute toggles (plate clicks / combo ping / fail clunk).
 * The packet is cancelled ONLY when one of those mute toggles matches — there
 * is no per-spell sound suppression (custom audio layers over Wynn's SFX).
 */
@Mixin(ClientPlayNetworkHandler::class)
abstract class PlaySoundS2CMixin {

    @Inject(method = ["onPlaySound"], at = [At("HEAD")], cancellable = true)
    private fun icomod_interceptSpellSounds(packet: PlaySoundS2CPacket, ci: CallbackInfo) {
        val soundId = packet.sound.value().id ?: return
        if (SpellSoundManager.onSoundIncoming(soundId)) {
            ci.cancel()
        }
    }
}
