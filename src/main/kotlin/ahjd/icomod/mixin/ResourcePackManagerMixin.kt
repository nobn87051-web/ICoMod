package ahjd.icomod.mixin

import ahjd.icomod.features.doggc.DoggcTexturePack
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Appends the DOGGC virtual pack to the TAIL of the built resource-pack list.
 *
 * Later packs in this list override earlier ones, so tail position = highest
 * client-resource priority — this is the only way to out-rank a server-forced
 * resource pack (Wynncraft pushes its own). [DoggcTexturePack] returns null for
 * every id except its targeted chest/anvil textures, so layering it on top is
 * non-destructive to everything else.
 *
 * `ResourcePackManager` is shared by the client-resource and server-data
 * managers; our pack contributes nothing to `SERVER_DATA` (empty namespaces /
 * null opens), so being appended to the data manager too is a harmless no-op.
 */
@Mixin(ResourcePackManager::class)
class ResourcePackManagerMixin {

    @Inject(method = ["createResourcePacks"], at = [At("RETURN")], cancellable = true)
    private fun icomod_appendDoggc(cir: CallbackInfoReturnable<List<ResourcePack>>) {
        val combined = ArrayList(cir.returnValue)
        combined.add(DoggcTexturePack())
        cir.returnValue = combined
    }
}
