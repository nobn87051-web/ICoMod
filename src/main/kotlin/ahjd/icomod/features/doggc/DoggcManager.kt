package ahjd.icomod.features.doggc

import ahjd.icomod.util.AhjLog
import net.minecraft.client.MinecraftClient

/**
 * Coordinates the resource reload that makes a DOGGC toggle change visible.
 *
 * Textures bake into atlases at resource-load time, so flipping a toggle can't
 * take effect per-frame — it needs a full [MinecraftClient.reloadResources]
 * (~1-3s hitch). Settings setters call [requestReload]; the client tick drains
 * the request via [tick], collapsing several toggles flipped in one Settings
 * session into a single reload, and never overlapping reloads.
 */
object DoggcManager {

    private const val TAG = "Doggc"

    @Volatile private var reloadRequested = false
    @Volatile private var reloading = false

    /** Mark that a DOGGC toggle changed; the next [tick] triggers one reload. */
    fun requestReload() { reloadRequested = true }

    fun tick(client: MinecraftClient) {
        if (!reloadRequested || reloading) return
        reloadRequested = false
        reloading = true
        AhjLog.info(TAG, "reloading client resources for DOGGC texture toggle")
        client.reloadResources().whenComplete { _, t ->
            reloading = false
            if (t != null) AhjLog.error(TAG, "DOGGC resource reload failed", t)
            else AhjLog.info(TAG, "DOGGC resource reload complete")
        }
    }
}
