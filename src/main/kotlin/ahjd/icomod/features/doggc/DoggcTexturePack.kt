package ahjd.icomod.features.doggc

import net.minecraft.resource.AbstractFileResourcePack
import net.minecraft.resource.InputSupplier
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourcePackInfo
import net.minecraft.resource.ResourcePackSource
import net.minecraft.resource.ResourceType
import net.minecraft.text.Text
import net.minecraft.util.Identifier
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Optional

/**
 * Virtual resource pack that serves the bundled DOGGC textures for the exact
 * vanilla ids listed in [DoggcTextures], gated live by the per-block config
 * toggles. Returns null for everything else so it never shadows unrelated
 * resources — it only ever overrides the chest / ender / anvil textures.
 *
 * Injected at the TAIL of [net.minecraft.resource.ResourcePackManager.createResourcePacks]
 * by [ahjd.icomod.mixin.ResourcePackManagerMixin], i.e. highest client-resource
 * priority, so it wins over the base game, user resourcepacks, AND the server
 * (Wynncraft) resource pack.
 *
 * Extends [AbstractFileResourcePack] purely to inherit `parseMetadata` /
 * `getInfo`; metadata is parsed from the in-memory [MCMETA] via [openRoot].
 */
class DoggcTexturePack : AbstractFileResourcePack(INFO) {

    override fun openRoot(vararg segments: String): InputSupplier<InputStream>? {
        if (segments.size == 1 && segments[0] == "pack.mcmeta") {
            return InputSupplier { ByteArrayInputStream(MCMETA) }
        }
        return null
    }

    override fun open(type: ResourceType, id: Identifier): InputSupplier<InputStream>? {
        if (type != ResourceType.CLIENT_RESOURCES) return null
        val target = DoggcTextures.byId(id) ?: return null
        if (!target.enabled()) return null
        return supplier(target.resourcePath())
    }

    override fun findResources(
        type: ResourceType,
        namespace: String,
        prefix: String,
        consumer: ResourcePack.ResultConsumer,
    ) {
        if (type != ResourceType.CLIENT_RESOURCES || namespace != "minecraft") return
        for (target in DoggcTextures.ALL) {
            if (!target.enabled()) continue
            if (target.id.namespace != namespace) continue
            if (!target.id.path.startsWith(prefix)) continue
            consumer.accept(target.id, supplier(target.resourcePath()))
        }
    }

    override fun getNamespaces(type: ResourceType): Set<String> =
        if (type == ResourceType.CLIENT_RESOURCES) NAMESPACES else emptySet()

    override fun close() {}

    private fun supplier(jarPath: String): InputSupplier<InputStream> =
        InputSupplier {
            DoggcTexturePack::class.java.getResourceAsStream(jarPath)
                ?: throw FileNotFoundException("missing bundled DOGGC asset: $jarPath")
        }

    companion object {
        const val PACK_ID = "icomod_doggc"
        private val NAMESPACES = setOf("minecraft")

        private val INFO = ResourcePackInfo(
            PACK_ID,
            Text.literal("IcoMod DOGGC"),
            ResourcePackSource.BUILTIN,
            Optional.empty(),
        )

        // pack_format is only validated when a pack is added through a profile;
        // we bypass profiles (raw tail-inject), so the value isn't checked at
        // reload. Kept plausible for 1.21.x anyway.
        private val MCMETA: ByteArray =
            """{"pack":{"description":"IcoMod DOGGC textures","pack_format":64}}"""
                .toByteArray(Charsets.UTF_8)
    }
}
