package ahjd.icomod.features.update

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.features.settings.SettingsScreen
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.TitleScreen

/**
 * Drives the launch update check (Sec 18). Fetches GitHub Releases async on
 * init; when the title screen first appears and a newer version exists, shows
 * an optional [UpdateScreen] (Update Now / Later) once per session. The user
 * is free to stay on any version — the prompt never blocks play.
 */
object UpdateManager {

    @Volatile private var result: UpdateChecker.Result? = null
    @Volatile private var shown = false

    fun latest(): UpdateChecker.Result? = result

    fun register() {
        // Wipe stale staged jars / swap scripts left by a prior session.
        UpdateInstaller.cleanStaleStaging()

        if (ConfigManager.config.updateCheckEnabled) {
            UpdateChecker.checkAsync().thenAccept { result = it }
        }

        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            // Show once per session when the title screen first appears.
            if (screen is TitleScreen) maybePrompt(client)
        }

        // Settings rows are built once; while a download runs, nudge an open
        // SettingsScreen to rebuild so the progress percent + state advance
        // live instead of freezing on "Downloading...". Throttled to state
        // changes and whole-percent steps.
        ClientTickEvents.END_CLIENT_TICK.register {
            val st = UpdateInstaller.state
            val pct = (UpdateInstaller.progress * 100).toInt()
            if (st != lastTickState || (st == UpdateInstaller.State.DOWNLOADING && pct != lastTickPct)) {
                lastTickState = st
                lastTickPct = pct
                (MinecraftClient.getInstance().currentScreen as? SettingsScreen)?.requestRebuild()
            }
        }
    }

    @Volatile private var lastTickState = UpdateInstaller.State.IDLE
    @Volatile private var lastTickPct = -1

    /** Manual re-check (settings "Check for updates"). [onDone] runs on the
     *  client thread once the result lands (e.g. to rebuild the settings pane). */
    fun recheck(onDone: () -> Unit = {}) {
        shown = false
        UpdateChecker.checkAsync().thenAccept {
            result = it
            MinecraftClient.getInstance().execute {
                onDone()
                // Surface a freshly-found update immediately, even from the
                // settings screen, rather than waiting for the next title screen.
                maybePrompt(MinecraftClient.getInstance())
            }
        }
    }

    private fun maybePrompt(client: MinecraftClient) {
        val r = result ?: return
        if (!r.updateAvailable) return
        if (shown) return
        if (client.currentScreen is UpdateScreen) return
        shown = true
        val parent = client.currentScreen
        client.setScreen(UpdateScreen(r, parent))
    }
}
