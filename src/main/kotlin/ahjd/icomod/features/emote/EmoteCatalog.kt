package ahjd.icomod.features.emote

import ahjd.icomod.config.ConfigManager

/**
 * Thin accessor over the emote-wheel config: the scanned emote list, the
 * per-slot bindings, and the wheel size. All reads hit
 * [ConfigManager.config] live; writes persist immediately.
 */
object EmoteCatalog {

    val SLOT_OPTIONS = listOf(4, 8, 12, 16)

    /** Scanned emote args (e.g. "clap", "dance"), in GUI order. */
    fun list(): List<String> = ConfigManager.config.emoteList

    fun setList(emotes: List<String>) {
        ConfigManager.config.emoteList = emotes.toMutableList()
        ConfigManager.save()
    }

    fun slots(): Int = ConfigManager.config.emoteWheelSlots.let {
        if (it in SLOT_OPTIONS) it else 8
    }

    fun setSlots(n: Int) {
        ConfigManager.config.emoteWheelSlots = if (n in SLOT_OPTIONS) n else 8
        ConfigManager.save()
    }

    /** Emote arg bound to [slot], or null if unbound/blank. */
    fun bind(slot: Int): String? =
        ConfigManager.config.emoteWheelBinds[slot.toString()]?.takeIf { it.isNotBlank() }

    fun setBind(slot: Int, arg: String?) {
        val m = ConfigManager.config.emoteWheelBinds
        if (arg.isNullOrBlank()) m.remove(slot.toString()) else m[slot.toString()] = arg
        ConfigManager.save()
    }
}
