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

    // Custom Spell Sounds (§5). Cast detection runs through
    // [ahjd.icomod.mixin.GameMessageS2CMixin] (action-bar text) into
    // [ahjd.icomod.features.sounds.SpellSoundManager]. Per-spell pairing is a
    // filename inside `<gameDir>/icomod/sounds/`; an empty/missing entry means
    // "play nothing for this spell" (Wynn's own SFX are never suppressed).
    var spellSoundsEnabled: Boolean = true,
    /** Spell id (see [ahjd.icomod.features.sounds.SpellCatalog]) -> sound filename. */
    var spellPairings: MutableMap<String, String> = mutableMapOf(),
    /** Spell id -> per-spell enable flag. Absent entry = enabled. */
    var spellEnabled: MutableMap<String, Boolean> = mutableMapOf(),
    /** Master multiplier applied on top of every per-spell volume. */
    var spellMasterVolume: Float = 1.0f,
    /** Spell id -> 0..1 per-spell volume. Absent entry = 1.0. */
    var spellVolumes: MutableMap<String, Float> = mutableMapOf(),
    /**
     * Consecutive-cast behavior. When true, repeated casts of the same
     * spell layer their custom playback. When false (default), each new
     * cast stops the previous instance and restarts the sound from t=0.
     */
    var spellOverlapEnabled: Boolean = false,
    /** Mute the 3 plate-click sounds the combo input produces. */
    var spellMutePlates: Boolean = false,
    /** Mute the `entity.arrow.hit_player` ping that fires on combo completion. */
    var spellMuteComboPing: Boolean = false,
    /** Mute the `block.anvil.land` fail-cast feedback. */
    var spellMuteFail: Boolean = false,

    // DOGGC Textures (§6). Replaces chest / ender chest / anvil textures via a
    // tail-injected virtual resource pack ([ahjd.icomod.features.doggc]). Each
    // block type toggles independently; a change triggers a resource reload.
    var doggcEnabled: Boolean = true,
    /** Active texture set: "DOGGC" (dog) or "DGTAL" (greek flag). */
    var doggcMode: String = "DOGGC",
    var doggcChest: Boolean = true,
    var doggcEnderChest: Boolean = true,
    var doggcTrappedChest: Boolean = true,
    var doggcAnvil: Boolean = true,
    /** Play the bundled DOGGC open sound when right-clicking an enabled DOGGC block. */
    var doggcSound: Boolean = true,
    /** 0..1 volume for the DOGGC open sound. */
    var doggcSoundVolume: Float = 1.0f,
    /** Playback speed multiplier for the DOGGC open sound, 1.0..10.0. */
    var doggcSoundSpeed: Float = 1.0f,

    // Emote Wheel (§25). Radial hold-to-open menu firing `/emote <name>`.
    /** Slot count on the wheel: 4 / 8 / 12 / 16. */
    var emoteWheelSlots: Int = 8,
    /** Slot index (as string) -> emote arg (e.g. "clap"). */
    var emoteWheelBinds: MutableMap<String, String> = mutableMapOf(),
    /** Scanned emote args from the `/emote` GUI. */
    var emoteList: MutableList<String> = mutableListOf(),

    // Auto Update (§18). Checks GitHub Releases on launch.
    var updateCheckEnabled: Boolean = true,
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
                // Gson builds Kotlin data classes via Unsafe, so fields ABSENT
                // from the on-disk JSON ignore the constructor defaults and come
                // back as false / 0 / null. That silently disabled every newly
                // added field on existing configs (e.g. doggcTrappedChest -> false).
                // Merge the saved JSON over a default tree so missing keys keep
                // their data-class defaults; present keys still win.
                val saved = gson.fromJson(configFile.readText(), com.google.gson.JsonObject::class.java)
                val merged = gson.toJsonTree(ModConfig()).asJsonObject
                if (saved != null) for ((k, v) in saved.entrySet()) merged.add(k, v)
                gson.fromJson(merged, ModConfig::class.java)
            }.getOrDefault(ModConfig())
        }
        // Gson can leave non-primitive fields as null when they're absent from
        // an older config JSON (data-class defaults are skipped by reflection).
        // Normalize so the rest of the code never has to null-check.
        @Suppress("SENSELESS_COMPARISON")
        if (config.overlayStates == null) config.overlayStates = mutableMapOf()
        @Suppress("SENSELESS_COMPARISON")
        if (config.overlayDefaultsDeployed == null) config.overlayDefaultsDeployed = mutableSetOf()
        @Suppress("SENSELESS_COMPARISON")
        if (config.spellPairings == null) config.spellPairings = mutableMapOf()
        @Suppress("SENSELESS_COMPARISON")
        if (config.spellEnabled == null) config.spellEnabled = mutableMapOf()
        @Suppress("SENSELESS_COMPARISON")
        if (config.spellVolumes == null) config.spellVolumes = mutableMapOf()

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
