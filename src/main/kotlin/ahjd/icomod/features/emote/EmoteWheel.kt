package ahjd.icomod.features.emote

import ahjd.icomod.features.settings.SettingsKeybind
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.minecraft.client.MinecraftClient
import net.minecraft.client.option.KeyBinding
import net.minecraft.client.util.InputUtil
import org.lwjgl.glfw.GLFW

/**
 * Emote wheel keybind + open logic. HOLD the bind to open the radial
 * [EmoteWheelScreen]; the screen fires the hovered slot's emote on release.
 *
 * Lives in the shared `IcoMod` controls category ([SettingsKeybind.CATEGORY]).
 * Default key: `B`.
 */
object EmoteWheel {

    private const val KEY_TRANSLATION = "key.icomod.emote_wheel"

    lateinit var key: KeyBinding
        private set

    fun register() {
        key = KeyBindingHelper.registerKeyBinding(
            KeyBinding(KEY_TRANSLATION, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, SettingsKeybind.CATEGORY)
        )

        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            EmoteScanner.tick(mc)
            // Open on press when nothing else is on screen. Release handling
            // lives in the screen (keybindings report unpressed while a screen
            // is open, so the screen polls the raw key instead).
            if (mc.currentScreen == null && key.isPressed) {
                mc.setScreen(EmoteWheelScreen())
            }
        }
    }

    /** Raw physical state of the bound key (works while a screen is open). */
    fun keyHeld(mc: MinecraftClient): Boolean {
        val k = InputUtil.fromTranslationKey(key.boundKeyTranslationKey) ?: return false
        if (k.code < 0) return false
        return InputUtil.isKeyPressed(mc.window, k.code)
    }
}
