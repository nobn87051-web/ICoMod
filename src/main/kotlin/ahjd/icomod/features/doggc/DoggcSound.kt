package ahjd.icomod.features.doggc

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.sounds.SoundLibrary
import net.minecraft.block.Block
import net.minecraft.block.Blocks
import net.minecraft.client.MinecraftClient
import net.minecraft.util.hit.BlockHitResult

/**
 * Plays the bundled DOGGC open sound when the player right-clicks an enabled
 * DOGGC-textured block.
 *
 * Driven by [ahjd.icomod.mixin.ClientPlayerInteractionManagerMixin] hooking
 * `interactBlock` at HEAD — the client-side entry point for every block
 * right-click. This fires once per real click regardless of fast-clicking
 * (a 20Hz tick poll missed quick press+release) and regardless of whether the
 * server later cancels the interaction (Wynncraft virtual chests), which is
 * exactly the flakiness the old [useKey] poll suffered.
 */
object DoggcSound {

    private const val SOUND_RES = "/assets/icomod/sounds/doggcsound.mp3"

    fun onRightClickBlock(hit: BlockHitResult) {
        val world = MinecraftClient.getInstance().world ?: return
        val block = world.getBlockState(hit.blockPos).block
        if (!shouldPlay(block)) return
        val c = ConfigManager.config
        SoundLibrary.playResourceAsync(SOUND_RES, c.doggcSoundVolume, c.doggcSoundSpeed)
    }

    private fun shouldPlay(block: Block): Boolean {
        val c = ConfigManager.config
        if (!c.doggcEnabled || !c.doggcSound) return false
        return when (block) {
            Blocks.CHEST -> c.doggcChest
            Blocks.ENDER_CHEST -> c.doggcEnderChest
            Blocks.TRAPPED_CHEST -> c.doggcTrappedChest
            Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL -> c.doggcAnvil
            else -> false
        }
    }
}
