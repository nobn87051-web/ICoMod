package ahjd.icomod.features.settings

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import net.minecraft.util.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Registers the keybind that opens [SettingsScreen].
 *
 * Default key: `K`. Lives under a dedicated `IcoMod` category in the vanilla
 * Controls screen so all current and future mod keybinds group together.
 *
 * Wired through a tick listener so the press fires regardless of whether a
 * screen is open; vanilla's key dispatcher already filters out the in-screen
 * case for us, so the body only needs to guard against re-opening when one
 * is already up.
 */
object SettingsKeybind {

    private const val KEY_TRANSLATION = "key.icomod.open_settings"

    /**
     * Custom keybind category. Registering via [KeyBinding.Category.create]
     * adds an "IcoMod" header to the vanilla Controls list. Identifier
     * doesn't have to point anywhere; it's just the category key.
     */
    val CATEGORY: KeyBinding.Category =
        KeyBinding.Category.create(Identifier.of("icomod", "main"))

    private lateinit var openSettings: KeyBinding

    /** Localized name of the currently-bound key, e.g. "K" or "Unbound". */
    fun boundKeyName(): String =
        if (::openSettings.isInitialized) openSettings.boundKeyLocalizedText.string
        else "K"

    fun register() {
        openSettings = KeyBindingHelper.registerKeyBinding(
            KeyBinding(
                KEY_TRANSLATION,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Drain the queue -- if user holds the key down with auto-repeat,
            // wasPressed() returns true once per fresh press.
            while (openSettings.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(SettingsScreen(null))
                }
            }
        }
    }
}
