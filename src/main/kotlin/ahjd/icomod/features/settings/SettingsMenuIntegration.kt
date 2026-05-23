package ahjd.icomod.features.settings

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.option.OptionsScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.text.Text

/**
 * Adds an "IcoMod" entry point to the vanilla pause and options screens so
 * users who don't know the `K` keybind can still find the settings panel.
 *
 * Injection is done via Fabric's [ScreenEvents.AFTER_INIT] — no mixin needed.
 * The button is positioned in a corner so it doesn't fight with vanilla
 * layout reflows across versions.
 */
object SettingsMenuIntegration {

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, scaledWidth, scaledHeight ->
            // GameMenuScreen (Esc menu) injection removed at user request —
            // the keybind alone is the supported entry point now. Only the
            // Options screen still gets the button as a discovery aid.
            if (screen is OptionsScreen) injectButton(screen, scaledWidth, scaledHeight, topRight = true)
        }
    }

    private fun injectButton(
        screen: net.minecraft.client.gui.screen.Screen,
        scaledWidth: Int,
        @Suppress("UNUSED_PARAMETER") scaledHeight: Int,
        topRight: Boolean,
    ) {
        val width = 90
        val height = 20
        val x = if (topRight) scaledWidth - width - 8 else 8
        val y = 6
        val button = ButtonWidget.builder(Text.literal("IcoMod")) {
            MinecraftClient.getInstance().setScreen(SettingsScreen(screen))
        }.dimensions(x, y, width, height).build()
        Screens.getButtons(screen).add(button)
    }
}
