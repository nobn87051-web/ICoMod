package ahjd.icomod.features.settings

/**
 * Previously injected an "IcoMod" entry button into the vanilla pause and
 * options screens. Both injections were removed at user request — the
 * keybind ([SettingsKeybind], default `K`) is the supported entry point.
 *
 * Kept as a no-op shim so [ahjd.icomod.client.IcomodClient.onInitializeClient]
 * doesn't need a conditional `register()` call. Re-add screen hooks here
 * if a discovery affordance is ever wanted again.
 */
object SettingsMenuIntegration {
    fun register() {
        // Intentionally empty.
    }
}
