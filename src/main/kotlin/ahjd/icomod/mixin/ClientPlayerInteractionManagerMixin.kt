package ahjd.icomod.mixin

import ahjd.icomod.features.doggc.DoggcSound
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.client.network.ClientPlayerInteractionManager
import net.minecraft.util.ActionResult
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Fires the DOGGC open sound at the source of a block right-click. Observe-only
 * (no cancel) so the interaction itself is untouched. Main-hand only to avoid a
 * double trigger from the off-hand pass.
 */
@Mixin(ClientPlayerInteractionManager::class)
class ClientPlayerInteractionManagerMixin {

    @Inject(method = ["interactBlock"], at = [At("HEAD")])
    private fun icomod_doggcOpenSound(
        player: ClientPlayerEntity,
        hand: Hand,
        hit: BlockHitResult,
        cir: CallbackInfoReturnable<ActionResult>,
    ) {
        if (hand == Hand.MAIN_HAND) DoggcSound.onRightClickBlock(hit)
    }
}
