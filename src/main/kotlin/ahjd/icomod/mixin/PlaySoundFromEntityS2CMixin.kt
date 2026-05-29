package ahjd.icomod.mixin

import ahjd.icomod.features.sounds.SpellSoundManager
import net.minecraft.client.network.ClientPlayNetworkHandler
import net.minecraft.network.packet.s2c.play.PlaySoundFromEntityS2CPacket
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * Counterpart to [PlaySoundS2CMixin] for sounds emitted from a specific
 * entity (the server sends a separate packet type for those:
 * `PlaySoundFromEntityS2CPacket`). The three muteable cast-input sounds
 * (plate clicks / combo ping / fail clunk) can arrive on either packet
 * type, so both routes must feed [SpellSoundManager.onSoundIncoming].
 *
 * Same contract as [PlaySoundS2CMixin]: cancel the packet only when a
 * global mute toggle matches. No per-spell suppression.
 */
@Mixin(ClientPlayNetworkHandler::class)
abstract class PlaySoundFromEntityS2CMixin {

    @Inject(method = ["onPlaySoundFromEntity"], at = [At("HEAD")], cancellable = true)
    private fun icomod_interceptSpellEntitySounds(packet: PlaySoundFromEntityS2CPacket, ci: CallbackInfo) {
        val soundId = packet.sound.value().id ?: return
        if (SpellSoundManager.onSoundIncoming(soundId)) {
            ci.cancel()
        }
    }
}
