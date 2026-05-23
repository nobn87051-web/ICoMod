package ahjd.icomod.features.settings

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.chatmode.ChatMode
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.gifpicker.GifCatalog
import ahjd.icomod.features.gifpicker.GifSize
import ahjd.icomod.features.overlay.MediaOverlayManager
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
        registerBackend()
    }

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
                            help = "Master toggle for the on-screen media overlay.",
                            get = { cfg.overlayEnabled },
                            set = { cfg.overlayEnabled = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Render above everything",
                            help = "Draw overlays above chat, scoreboard, and other mods' HUDs.",
                            get = { cfg.overlayRenderOnTop },
                            set = { cfg.overlayRenderOnTop = it },
                        ))
                        add(SettingItem.BoolItem(
                            label = "Show over GUIs",
                            help = "Render and drag overlays while inventory / chests / any screen is open. Overlay clicks won't pass through to the GUI.",
                            get = { cfg.overlayShowOverGui },
                            set = { cfg.overlayShowOverGui = it },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Open media folder",
                            help = "Drop PNG/JPG/GIF here. MP4 shows a placeholder card.",
                            buttonText = "Open",
                            onClick = {
                                val f = MediaOverlayManager.folder
                                f.mkdirs()
                                Util.getOperatingSystem().open(f.toURI())
                            },
                        ))
                        add(SettingItem.ActionItem(
                            label = "Rescan folder",
                            help = "Pick up newly added or removed files now.",
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
                                help = "Toggle visibility. Right-click in chat to hide quickly.",
                                get = { !MediaOverlayManager.state(name).hidden },
                                set = { v -> MediaOverlayManager.setHidden(name, !v) },
                            ))
                            add(SettingItem.ActionItem(
                                label = "  Delete \"$name\"",
                                help = "Moves the file into overlay-media/.trash/.",
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
                        help = "Master toggle for the outgoing chat rewriter.",
                        get = { cfg.chatModeEnabled },
                        set = { cfg.chatModeEnabled = it },
                    ),
                    SettingItem.EnumItem(
                        label = "Active mode",
                        help = "Word-map used to rewrite outgoing messages.",
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
                        help = "Render image filenames typed in chat as inline images.",
                        get = { cfg.gifsEnabled },
                        set = { cfg.gifsEnabled = it },
                    ),
                    SettingItem.EnumItem(
                        label = "Default size",
                        help = "Used when a typed filename has no XS/S/M/L suffix.",
                        options = sizeOptions,
                        get = { cfg.gifDefaultSize },
                        set = { cfg.gifDefaultSize = it },
                    ),
                    SettingItem.BoolItem(
                        label = "Stretch to fit",
                        help = "Distort to fill the slot. Off: contain (letterboxed).",
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
                title = "Backend",
                items = listOf(
                    SettingItem.StringItem(
                        label = "Server URL",
                        help = "Base URL of the IcoMod API. Leave default unless testing.",
                        get = { cfg.serverUrl },
                        set = { cfg.serverUrl = it.trim().trimEnd('/') },
                        maxLen = 200,
                    ),
                    SettingItem.ActionItem(
                        label = "Refresh GIF catalog",
                        help = "Force the picker to re-pull the live catalog now.",
                        buttonText = "Refresh",
                        onClick = { GifCatalog.refreshAsync() },
                    ),
                ),
            )
        )
    }
}
