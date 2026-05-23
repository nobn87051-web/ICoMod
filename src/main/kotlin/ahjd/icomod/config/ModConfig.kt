package ahjd.icomod.config

import ahjd.icomod.features.overlay.OverlayState
import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import java.io.File

data class ModConfig(
    var chatMode: String = "NORMAL",
    var chatModeEnabled: Boolean = true,
    var serverUrl: String = "https://icomod.xyz",
    var gifsEnabled: Boolean = true,
    var gifDefaultSize: String = "S",
    var gifStretch: Boolean = false,
    // Media Overlay
    var overlayEnabled: Boolean = true,
    /**
     * Render overlays above ALL other HUD layers — including chat, scoreboard,
     * subtitles, and most other mods' HUDs. Default false (overlays sit just
     * below the chat box). When true, the renderer attaches its layer after
     * `VanillaHudElements.SUBTITLES`, the last vanilla layer.
     */
    var overlayRenderOnTop: Boolean = false,
    /**
     * Render overlays on top of any open GUI (inventory, chest, etc.) and
     * allow drag/scroll/right-click while that GUI is open. Clicks that land
     * on an overlay are consumed before the underlying GUI sees them.
     */
    var overlayShowOverGui: Boolean = false,
    /** Per-file overlay state. Key = filename incl. extension. */
    var overlayStates: MutableMap<String, OverlayState> = mutableMapOf(),
    /**
     * Names of default overlay files that have already been extracted from
     * the mod jar to `<gameDir>/icomod/overlay-media/`. Tracked so a user-
     * deleted default does not auto-restore on the next launch — once the
     * name is in this set, the deployer never re-copies it.
     */
    var overlayDefaultsDeployed: MutableSet<String> = mutableSetOf(),
)

object ConfigManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configFile: File = FabricLoader.getInstance()
        .gameDir.resolve("icomod/config.json").toFile()

    var config = ModConfig()
        private set

    fun load() {
        configFile.parentFile.mkdirs()
        if (configFile.exists()) {
            config = runCatching {
                gson.fromJson(configFile.readText(), ModConfig::class.java)
            }.getOrDefault(ModConfig())
        }
        // Gson can leave non-primitive fields as null when they're absent from
        // an older config JSON (data-class defaults are skipped by reflection).
        // Normalize so the rest of the code never has to null-check.
        @Suppress("SENSELESS_COMPARISON")
        if (config.overlayStates == null) config.overlayStates = mutableMapOf()
        @Suppress("SENSELESS_COMPARISON")
        if (config.overlayDefaultsDeployed == null) config.overlayDefaultsDeployed = mutableSetOf()

        // Migration: OverlayState.placed was added after first ship. If a
        // saved state has a non-zero position it was definitely placed by the
        // user; mark it so the renderer doesn't re-center on load.
        for ((_, st) in config.overlayStates) {
            if (!st.placed && (st.x != 0 || st.y != 0)) st.placed = true
        }
        save()
    }

    fun save() {
        configFile.parentFile.mkdirs()
        configFile.writeText(gson.toJson(config))
    }
}
