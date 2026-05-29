package ahjd.icomod.features.doggc

import ahjd.icomod.config.ConfigManager
import net.minecraft.util.Identifier

/**
 * The fixed set of vanilla texture ids DOGGC overrides, mapped to the bundled
 * replacement bytes in the jar (`assets/icomod/doggc/`). Each target carries a
 * [Group] so the per-block-type toggles in [ahjd.icomod.config.ModConfig] can
 * gate it independently.
 *
 * Chest entity textures (`entity/chest/...`) are loaded by explicit id through
 * the `minecraft:chests` sprite atlas; anvil block textures (`block/...`) are
 * picked up by the block atlas's directory scan. [DoggcTexturePack] serves
 * both paths (explicit `open` + `findResources` enumeration), so both atlas
 * styles see the override.
 */
object DoggcTextures {

    enum class Group { CHEST, ENDER, TRAPPED, ANVIL }

    /** Texture-set folders under `assets/icomod/`, selectable via [config mode]. */
    val MODES = listOf("DOGGC", "DGTAL")

    data class Target(val id: Identifier, val file: String, val group: Group) {
        /** Live gate: master toggle AND this group's per-block toggle. */
        fun enabled(): Boolean {
            val c = ConfigManager.config
            if (!c.doggcEnabled) return false
            return when (group) {
                Group.CHEST -> c.doggcChest
                Group.ENDER -> c.doggcEnderChest
                Group.TRAPPED -> c.doggcTrappedChest
                Group.ANVIL -> c.doggcAnvil
            }
        }

        /** Jar resource path for the currently-selected texture set (mode). */
        fun resourcePath(): String {
            val mode = ConfigManager.config.doggcMode.takeIf { it in MODES } ?: MODES.first()
            return "/assets/icomod/${mode.lowercase()}/$file"
        }
    }

    private fun t(path: String, file: String, g: Group) =
        Target(Identifier.of("minecraft", path), file, g)

    val ALL: List<Target> = listOf(
        // Chest (single + double halves share the single-chest UV layout).
        t("textures/entity/chest/normal.png",        "normal.png",        Group.CHEST),
        t("textures/entity/chest/normal_left.png",   "normal_left.png",   Group.CHEST),
        t("textures/entity/chest/normal_right.png",  "normal_right.png",  Group.CHEST),
        // Ender chest (own texture, same model/UV as single chest).
        t("textures/entity/chest/ender.png",         "ender.png",         Group.ENDER),
        // Trapped chest (own texture set, same model/UV as normal chest).
        t("textures/entity/chest/trapped.png",        "trapped.png",        Group.TRAPPED),
        t("textures/entity/chest/trapped_left.png",   "trapped_left.png",   Group.TRAPPED),
        t("textures/entity/chest/trapped_right.png",  "trapped_right.png",  Group.TRAPPED),
        // Anvil: shared body + the three damage-state tops (flat per-face).
        t("textures/block/anvil.png",                "anvil.png",                Group.ANVIL),
        t("textures/block/anvil_top.png",            "anvil_top.png",            Group.ANVIL),
        t("textures/block/chipped_anvil_top.png",    "chipped_anvil_top.png",    Group.ANVIL),
        t("textures/block/damaged_anvil_top.png",    "damaged_anvil_top.png",    Group.ANVIL),
    )

    private val byId: Map<Identifier, Target> = ALL.associateBy { it.id }

    fun byId(id: Identifier): Target? = byId[id]
}
