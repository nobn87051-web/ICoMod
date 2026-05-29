package ahjd.icomod.client

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.chatmode.ChatModeFeature
import ahjd.icomod.features.chatmode.ChatModeManager
import ahjd.icomod.features.doggc.DoggcManager
import ahjd.icomod.features.emote.EmoteWheel
import ahjd.icomod.features.update.UpdateManager
import ahjd.icomod.features.gifpicker.GifCatalog
import ahjd.icomod.features.gifpicker.GifChatPadding
import ahjd.icomod.features.overlay.MediaOverlayInput
import ahjd.icomod.features.overlay.MediaOverlayManager
import ahjd.icomod.features.overlay.MediaOverlayRenderer
import ahjd.icomod.features.settings.FeatureSettings
import ahjd.icomod.features.settings.SettingsKeybind
import ahjd.icomod.features.settings.SettingsMenuIntegration
import ahjd.icomod.features.sounds.SoundLibrary
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class IcomodClient : ClientModInitializer {

    override fun onInitializeClient() {
        ConfigManager.load()
        ChatModeManager.init()
        ChatModeFeature.register()
        GifCatalog.refreshAsync()
        GifChatPadding.register()

        // Settings menu: register built-in feature sections + bind the
        // keybind that opens [SettingsScreen]. Must run AFTER
        // ConfigManager.load so the section item getters resolve against
        // the on-disk config rather than defaults.
        // Custom Spell Sounds (§5): scan user folder so the settings
        // dropdowns have file options; cast detection itself is driven
        // by PlaySoundS2CMixin once the mixin is loaded.
        SoundLibrary.refresh()

        FeatureSettings.registerAll()
        SettingsKeybind.register()
        SettingsMenuIntegration.register()

        // Media Overlay: scan folder, install HUD layers + per-screen input
        // hooks. Drag polling lives on the client tick because Mojang's
        // mouseDragged dispatch through screens isn't reliable.
        MediaOverlayManager.init()
        MediaOverlayRenderer.register()
        MediaOverlayInput.register()
        ClientTickEvents.END_CLIENT_TICK.register { MediaOverlayInput.tickDrag() }

        // DOGGC Textures (§6): drain debounced resource-reload requests raised
        // when the user toggles a block type in Settings. Pack itself is
        // tail-injected via ResourcePackManagerMixin (no init needed here).
        ClientTickEvents.END_CLIENT_TICK.register { DoggcManager.tick(it) }

        // DOGGC open sound is driven by ClientPlayerInteractionManagerMixin
        // (interactBlock HEAD) -- no tick registration needed here.

        // Emote Wheel (§25): keybind (IcoMod category) + radial hold/release.
        // register() also installs the client-tick that drives the emote scan.
        EmoteWheel.register()

        // Auto Update (§18): async GitHub Releases check + title-screen prompt.
        UpdateManager.register()

        // Belt-and-braces: persist on clean shutdown so any in-memory state
        // (transient drag positions, last-touched stack order) survives if
        // the player quits mid-drag or alt-F4s out without releasing.
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            ConfigManager.save()
        }
    }
}
