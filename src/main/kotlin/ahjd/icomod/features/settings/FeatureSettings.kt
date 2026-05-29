package ahjd.icomod.features.settings

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.doggc.DoggcManager
import ahjd.icomod.features.emote.EmoteCatalog
import ahjd.icomod.features.emote.EmoteScanner
import ahjd.icomod.features.update.UpdateInstaller
import ahjd.icomod.features.update.UpdateManager
import ahjd.icomod.features.chatmode.ChatMode
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifCatalog
import ahjd.icomod.features.gifpicker.GifSize
import ahjd.icomod.features.overlay.MediaOverlayManager
import ahjd.icomod.features.settings.ui.WarmPalette
import ahjd.icomod.features.sounds.SoundLibrary
import ahjd.icomod.features.sounds.SpellCatalog
import net.minecraft.client.MinecraftClient
import net.minecraft.util.Util

/**
 * Registers built-in feature sections with [SettingsRegistry]. Called once
 * from [ahjd.icomod.client.IcomodClient] after [ConfigManager.load]; each
 * feature's settings live here rather than scattered through their packages
 * so a new section is a single edit, not three.
 *
 * Setter lambdas write to the live [ConfigManager.config] object. The
 * settings screen stages edits in-memory and only flushes them through the
 * setters (followed by [ConfigManager.save]) when the user presses Done.
 * [SettingItem.ActionItem] handlers, on the other hand, fire immediately
 * — they're triggers, not values.
 */
object FeatureSettings {

    fun registerAll() {
        registerChatModes()
        registerGifs()
        registerMediaOverlay()
        registerSpellSounds()
        registerDoggc()
        registerEmoteWheel()
        registerBackend()
        registerUpdates()
    }

    /**
     * Updates (§18). Live status line + auto-check toggle + manual Check, and
     * an Update Now action that appears only when a newer release exists.
     * Dynamic so the status reflects the latest check / install state on each
     * rebuild (Check triggers a rebuild when its async result lands).
     */
    private fun registerUpdates() {
        SettingsRegistry.register(
            FeatureSection(
                key = "updates",
                title = "Updates",
                itemsProvider = {
                    buildList {
                        val r = UpdateManager.latest()
                        val st = UpdateInstaller.state
                        val (statusText, statusColor) = when {
                            st == UpdateInstaller.State.STAGED ->
                                "Update ready — restart to apply" to WarmPalette.SUCCESS
                            st == UpdateInstaller.State.DOWNLOADING -> {
                                val p = UpdateInstaller.progress
                                val pct = if (p >= 0f) "  ${(p * 100).toInt()}%" else ""
                                "Downloading...$pct" to WarmPalette.ACCENT
                            }
                            st == UpdateInstaller.State.FAILED ->
                                "Update failed — check the log" to WarmPalette.DANGER
                            r == null ->
                                "Press Check to look for updates" to WarmPalette.MUTED
                            r.updateAvailable ->
                                "v${r.current}  ->  v${r.latest}  (${r.behind} behind)" to WarmPalette.ACCENT
                            else ->
                                "Up to date (v${r.current})" to WarmPalette.SUCCESS
                        }
                        add(SettingItem.SectionHeaderItem(label = statusText, accent = statusColor))

                        add(SettingItem.BoolItem(
                            label = "Check on launch",
                            help = "Look for a new version when the game starts.",
                            get = { ConfigManager.config.updateCheckEnabled },
                            set = { ConfigManager.config.updateCheckEnabled = it },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Check for updates",
                            help = "Look for a new version now.",
                            buttonText = "Check",
                            onClick = { UpdateManager.recheck { activeSettingsScreen()?.requestRebuild() } },
                        ))
                        if (st == UpdateInstaller.State.STAGED) {
                            add(SettingItem.ActionItem(
                                label = "Restart now",
                                help = "Quit the game to finish the update now.",
                                buttonText = "Restart",
                                onClick = { MinecraftClient.getInstance().scheduleStop() },
                            ))
                            add(SettingItem.ActionItem(
                                label = "Cancel update",
                                help = "Drop the downloaded update.",
                                buttonText = "Cancel",
                                onClick = {
                                    UpdateInstaller.cancel()
                                    activeSettingsScreen()?.requestRebuild()
                                },
                            ))
                        } else if (r != null && r.updateAvailable && st != UpdateInstaller.State.DOWNLOADING) {
                            add(SettingItem.ActionItem(
                                label = "Update now",
                                help = "Download the new version, then restart now or later.",
                                buttonText = "Update",
                                onClick = {
                                    r.jarUrl?.let { UpdateInstaller.install(it, r.sha256) }
                                    activeSettingsScreen()?.requestRebuild()
                                },
                            ))
                        }
                    }
                },
            )
        )
    }

    /**
     * Emote Wheel (§25). Slot count + scan action + per-slot searchable emote
     * pickers. Dynamic items so the slot rows track the chosen wheel size and
     * the scanned emote list without reopening.
     */
    private fun registerEmoteWheel() {
        SettingsRegistry.register(
            FeatureSection(
                key = "emote-wheel",
                title = "Emote Wheel",
                itemsProvider = {
                    buildList {
                        add(SettingItem.PickItem(
                            label = "Wheel slots",
                            help = "Number of emotes on the wheel.",
                            get = { EmoteCatalog.slots().toString() },
                            set = { EmoteCatalog.setSlots(it.toIntOrNull() ?: 8) },
                            options = { EmoteCatalog.SLOT_OPTIONS.map { n -> n.toString() } },
                            placeholder = "8",
                        ))
                        add(SettingItem.ActionItem(
                            label = "Scan emotes",
                            help = "Open the /emote menu and read your emotes.",
                            buttonText = "Scan",
                            onClick = {
                                MinecraftClient.getInstance().setScreen(null)
                                EmoteScanner.startScan()
                            },
                        ))
                        add(SettingItem.SectionHeaderItem(
                            label = "Slots (${EmoteCatalog.list().size} emotes found)",
                            accent = WarmPalette.ACCENT,
                        ))
                        for (i in 0 until EmoteCatalog.slots()) {
                            val slot = i
                            add(SettingItem.PickItem(
                                label = "Slot ${i + 1}",
                                get = { EmoteCatalog.bind(slot) ?: "" },
                                set = { EmoteCatalog.setBind(slot, it) },
                                options = { EmoteCatalog.list() },
                            ))
                        }
                    }
                },
            )
        )
    }

    /**
     * DOGGC Textures (§6). Master + per-block-type toggles. Each setter writes
     * config then asks [DoggcManager] to reload resources so the change shows
     * without restarting — the reload is debounced to one call per Settings
     * session (see [ahjd.icomod.features.doggc.DoggcManager]).
     */
    private fun registerDoggc() {
        val cfg = ConfigManager.config
        SettingsRegistry.register(
            FeatureSection(
                key = "doggc",
                title = "Extra Textures",
                items = listOf(
                    SettingItem.BoolItem(
                        label = "Enabled",
                        help = "Replace some block textures with custom art.",
                        get = { cfg.doggcEnabled },
                        set = { cfg.doggcEnabled = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.EnumItem(
                        label = "Texture set",
                        help = "Art style: DOGGC (dog) or DGTAL (Greek flag).",
                        options = ahjd.icomod.features.doggc.DoggcTextures.MODES,
                        get = { cfg.doggcMode },
                        set = { cfg.doggcMode = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.BoolItem(
                        label = "Chest",
                        help = "Re-texture chests.",
                        get = { cfg.doggcChest },
                        set = { cfg.doggcChest = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.BoolItem(
                        label = "Ender Chest",
                        help = "Re-texture ender chests.",
                        get = { cfg.doggcEnderChest },
                        set = { cfg.doggcEnderChest = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.BoolItem(
                        label = "Trapped Chest",
                        help = "Re-texture trapped chests.",
                        get = { cfg.doggcTrappedChest },
                        set = { cfg.doggcTrappedChest = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.BoolItem(
                        label = "Anvil",
                        help = "Re-texture anvils.",
                        get = { cfg.doggcAnvil },
                        set = { cfg.doggcAnvil = it; DoggcManager.requestReload() },
                    ),
                    SettingItem.BoolItem(
                        label = "Open sound",
                        help = "Play a sound when you open a re-textured block.",
                        get = { cfg.doggcSound },
                        set = { cfg.doggcSound = it },
                    ),
                    SettingItem.SliderItem(
                        label = "Open sound volume",
                        help = "Loudness of the open sound.",
                        get = { cfg.doggcSoundVolume },
                        set = { cfg.doggcSoundVolume = it },
                    ),
                    SettingItem.SliderItem(
                        label = "Open sound speed",
                        help = "Speed of the open sound (1x-4.5x).",
                        // Slider works in 0..1; map to/from the 1..4.5 multiplier.
                        // Speed is applied by resampling (SoundLibrary keeps the
                        // audio line at the source rate), so the cap is just a
                        // taste limit, not an audio-device constraint.
                        get = { (cfg.doggcSoundSpeed - 1f) / 3.5f },
                        set = { cfg.doggcSoundSpeed = 1f + it * 3.5f },
                        format = { "%.1fx".format(1f + it * 3.5f) },
                    ),
                ),
            )
        )
    }

    /**
     * Custom Spell Sounds (§5). All five classes are detected — cast detection
     * is action-bar-text driven (see [ahjd.icomod.features.sounds.SpellCastParser])
     * so it's class-agnostic, and [ahjd.icomod.features.sounds.SpellCatalog.ALL]
     * lists every class's spells. Rows are dynamic so newly dropped .ogg/.mp3
     * files appear in the dropdowns on the next pane rebuild.
     */
    private fun registerSpellSounds() {
        // NOTE: do NOT capture `val cfg = ConfigManager.config` here.
        // ConfigManager.config is a `var` reassigned on load() (which only
        // runs once today, but anything that triggers a config reload would
        // strand stale references in every closure below). Reading
        // ConfigManager.config inline on each invocation always sees the
        // current instance.

        SettingsRegistry.register(
            FeatureSection(
                key = "spell-sounds",
                title = "Custom Spell Sounds",
                itemsProvider = {
                    buildList {
                        add(SettingItem.SectionHeaderItem(
                            label = "Setup",
                            accent = WarmPalette.ACCENT,
                        ))
                        add(SettingItem.BoolItem(
                            label = "Enabled",
                            help = "Play custom sounds when you cast spells.",
                            get = { ConfigManager.config.spellSoundsEnabled },
                            set = { ConfigManager.config.spellSoundsEnabled = it },
                        ))
                        add(SettingItem.SliderItem(
                            label = "Master volume",
                            help = "Overall loudness of all spell sounds.",
                            get = { ConfigManager.config.spellMasterVolume },
                            set = { ConfigManager.config.spellMasterVolume = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Overlap repeat casts",
                            help = "On: repeat casts stack. Off: each cast restarts the sound.",
                            get = { ConfigManager.config.spellOverlapEnabled },
                            set = { ConfigManager.config.spellOverlapEnabled = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Mute plate clicks",
                            help = "Silence the spell-input clicks while casting.",
                            get = { ConfigManager.config.spellMutePlates },
                            set = { ConfigManager.config.spellMutePlates = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Mute combo ping",
                            help = "Silence the ping when a spell lands.",
                            get = { ConfigManager.config.spellMuteComboPing },
                            set = { ConfigManager.config.spellMuteComboPing = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Mute failed cast",
                            help = "Silence the clunk when a cast fails.",
                            get = { ConfigManager.config.spellMuteFail },
                            set = { ConfigManager.config.spellMuteFail = it },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Open sounds folder",
                            help = "Add your sound files here (OGG or MP3).",
                            buttonText = "Open",
                            onClick = {
                                val f = SoundLibrary.folder
                                f.mkdirs()
                                Util.getOperatingSystem().open(f.toURI())
                            },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Rescan sounds",
                            help = "Reload your sound files now.",
                            buttonText = "Rescan",
                            onClick = {
                                SoundLibrary.refresh()
                                activeSettingsScreen()?.requestRebuild()
                            },
                        ))

                        // "(None)" sentinel lets the user clear a pairing
                        // without deleting the file from disk.
                        val fileOptions = listOf(NONE_FILE) + SoundLibrary.list()

                        // Group by class so users only scroll through one
                        // class's spell cards at a time. Section headers are
                        // collapsible (SettingsScreen tracks collapsed state +
                        // toggles on click) so a class's cards fold away.
                        val classOrder = listOf("Mage", "Archer", "Warrior", "Assassin", "Shaman")
                        val byClass = SpellCatalog.ALL.groupBy { it.classKind }
                        for (className in classOrder) {
                            val spells = byClass[className] ?: continue
                            add(SettingItem.SectionHeaderItem(
                                label = "$className Spells",
                                accent = classColor(className),
                            ))
                            for (spell in spells) {
                                add(spellCard(spell, fileOptions))
                            }
                        }
                    }
                },
            )
        )
    }

    /**
     * Build the per-spell card row. Pulled out of the inline loop so the
     * class grouping above stays readable. All getters/setters read
     * `ConfigManager.config` inline so a future config reload doesn't
     * strand them on a stale instance.
     */
    private fun spellCard(
        spell: SpellCatalog.Spell,
        fileOptions: List<String>,
    ): SettingItem.SpellCardItem = SettingItem.SpellCardItem(
        label = spell.displayName,
        help = classifierHint(spell),
        classKind = spell.classKind,
        accentColor = classColor(spell.classKind),
        getEnabled = { ConfigManager.config.spellEnabled[spell.id] ?: true },
        setEnabled = { ConfigManager.config.spellEnabled[spell.id] = it },
        fileOptions = fileOptions,
        getFile = {
            ConfigManager.config.spellPairings[spell.id]?.takeIf { it.isNotBlank() } ?: NONE_FILE
        },
        setFile = { name ->
            val c = ConfigManager.config
            if (name == NONE_FILE) c.spellPairings.remove(spell.id)
            else c.spellPairings[spell.id] = name
        },
        getVolume = { ConfigManager.config.spellVolumes[spell.id] ?: 1.0f },
        setVolume = { v -> ConfigManager.config.spellVolumes[spell.id] = v },
        onPreview = {
            val c = ConfigManager.config
            val file = c.spellPairings[spell.id]
            if (!file.isNullOrBlank()) {
                val perSpell = c.spellVolumes[spell.id] ?: 1.0f
                SoundLibrary.playAsync(file, perSpell * c.spellMasterVolume)
            }
        },
    )

    private fun classifierHint(spell: SpellCatalog.Spell): String {
        // Detection is action-bar-text driven; custom audio layers over
        // Wynn's own SFX (no suppression). Surface spell type so the user
        // can reason about overlap behaviour vs the global setting.
        return when (spell.kind) {
            SpellCatalog.Kind.INSTANT -> "Instant"
            SpellCatalog.Kind.ACTIVE  -> "Sustained"
        }
    }

    private fun classColor(kind: String): Int = when (kind) {
        "Mage"     -> CLASS_COLOR_MAGE
        "Archer"   -> CLASS_COLOR_ARCHER
        "Warrior"  -> CLASS_COLOR_WARRIOR
        "Assassin" -> CLASS_COLOR_ASSASSIN
        "Shaman"   -> CLASS_COLOR_SHAMAN
        else       -> WarmPalette.ACCENT
    }

    private const val NONE_FILE = "(None)"
    private const val CLASS_COLOR_MAGE     = 0xFF6FAFFF.toInt()
    private const val CLASS_COLOR_ARCHER   = 0xFF4ECC5C.toInt()
    private const val CLASS_COLOR_WARRIOR  = 0xFFE07050.toInt()
    private const val CLASS_COLOR_ASSASSIN = 0xFFB070FF.toInt()
    private const val CLASS_COLOR_SHAMAN   = 0xFFE0C040.toInt()

    /**
     * Media Overlay section. Uses [FeatureSection.itemsProvider] because the
     * rows are dynamic — one toggle + one delete-action row per file in
     * `<gameDir>/icomod/overlay-media/`. Action handlers call
     * [SettingsScreen.requestRebuild] so the panel reflects the change
     * without the user reopening Settings.
     */
    private fun registerMediaOverlay() {
        val cfg = ConfigManager.config

        SettingsRegistry.register(
            FeatureSection(
                key = "media-overlay",
                title = "Media Overlay",
                itemsProvider = {
                    buildList {
                        add(SettingItem.BoolItem(
                            label = "Enabled",
                            help = "Show your images on screen.",
                            get = { cfg.overlayEnabled },
                            set = { cfg.overlayEnabled = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Draw above everything",
                            help = "Draw images over chat and other HUDs.",
                            get = { cfg.overlayRenderOnTop },
                            set = { cfg.overlayRenderOnTop = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Show over menus",
                            help = "Keep images visible while inventories are open.",
                            get = { cfg.overlayShowOverGui },
                            set = { cfg.overlayShowOverGui = it },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Open media folder",
                            help = "Add your images here (PNG, JPG, or GIF).",
                            buttonText = "Open",
                            onClick = {
                                val f = MediaOverlayManager.folder
                                f.mkdirs()
                                Util.getOperatingSystem().open(f.toURI())
                            },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Rescan folder",
                            help = "Reload your image files now.",
                            buttonText = "Rescan",
                            onClick = {
                                MediaOverlayManager.rescan()
                                activeSettingsScreen()?.requestRebuild()
                            },
                        ))

                        for (entry in MediaOverlayManager.all()) {
                            val name = entry.name
                            add(SettingItem.BoolItem(
                                label = name,
                                help = "Show or hide this image.",
                                get = { !MediaOverlayManager.state(name).hidden },
                                set = { v -> MediaOverlayManager.setHidden(name, !v) },
                            ))
                            add(SettingItem.ActionItem(
                                label = "  Delete \"$name\"",
                                help = "Move this image to the trash folder.",
                                buttonText = "Delete",
                                onClick = {
                                    MediaOverlayManager.delete(name)
                                    activeSettingsScreen()?.requestRebuild()
                                },
                            ))
                        }
                    }
                }
            )
        )
    }

    private fun activeSettingsScreen(): SettingsScreen? =
        MinecraftClient.getInstance().currentScreen as? SettingsScreen

    private fun registerChatModes() {
        val cfg = ConfigManager.config
        val chatModeOptions = ChatMode.values().map { it.name }

        SettingsRegistry.register(
            FeatureSection(
                key = "chat-modes",
                title = "Chat Modes",
                items = listOf(
                    SettingItem.BoolItem(
                        label = "Enabled",
                        help = "Rewrite your chat messages in a fun style.",
                        get = { cfg.chatModeEnabled },
                        set = { cfg.chatModeEnabled = it },
                    ),
                    SettingItem.EnumItem(
                        label = "Active mode",
                        help = "Choose the style your messages are rewritten in.",
                        options = chatModeOptions,
                        get = { cfg.chatMode },
                        // Route through the manager so its live `currentMode`
                        // stays in sync with config -- writing the config
                        // alone wouldn't take effect until the next restart.
                        set = { name ->
                            runCatching { ChatModeManager.setMode(ChatMode.valueOf(name)) }
                        },
                    ),
                ),
            )
        )
    }

    private fun registerGifs() {
        val cfg = ConfigManager.config
        val sizeOptions = GifSize.values().map { it.name }

        SettingsRegistry.register(
            FeatureSection(
                key = "gifs",
                title = "GIFs in Chat",
                items = listOf(
                    SettingItem.BoolItem(
                        label = "Enabled",
                        help = "Show GIF names typed in chat as images.",
                        get = { cfg.gifsEnabled },
                        set = { cfg.gifsEnabled = it },
                    ),
                    SettingItem.EnumItem(
                        label = "Default size",
                        help = "Size used when you don't add XS, S, M, or L.",
                        options = sizeOptions,
                        get = { cfg.gifDefaultSize },
                        set = { cfg.gifDefaultSize = it },
                    ),
                    SettingItem.BoolItem(
                        label = "Stretch to fit",
                        help = "On: fill the box. Off: keep the image's shape.",
                        get = { cfg.gifStretch },
                        set = { cfg.gifStretch = it },
                    ),
                ),
            )
        )
    }

    private fun registerBackend() {
        val cfg = ConfigManager.config

        SettingsRegistry.register(
            FeatureSection(
                key = "backend",
                title = "Server",
                items = listOf(
                    SettingItem.StringItem(
                        label = "Server URL",
                        help = "Address the mod loads GIFs from.",
                        get = { cfg.serverUrl },
                        set = { cfg.serverUrl = it.trim().trimEnd('/') },
                        maxLen = 200,
                    ),
                    SettingItem.ActionItem(
                        label = "Refresh GIFs",
                        help = "Reload the online GIF list now.",
                        buttonText = "Refresh",
                        onClick = { GifCatalog.refreshAsync() },
                    ),
                ),
            )
        )
    }
}
