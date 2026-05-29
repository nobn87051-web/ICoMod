package ahjd.icomod.features.sounds

import ahjd.icomod.config.ConfigManager
import ahjd.icomod.util.AhjLog
import net.minecraft.util.Identifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Spell-cast event handler + custom playback.
 *
 * Two network-thread inputs from mixins:
 *
 *   - [ahjd.icomod.mixin.GameMessageS2CMixin] -> [onSpellCast] when an
 *     action-bar packet carries the `"<Spell> Cast! -<cost>"` line.
 *     Single source of truth for which spell fired.
 *   - [ahjd.icomod.mixin.PlaySoundS2CMixin] (+ the from-entity variant)
 *     -> [onSoundIncoming] on every inbound sound packet. Used for two
 *     things: feeding the plate-trio repaint guard, and the three fixed
 *     global mute toggles. Returns true only when a mute toggle matches.
 *
 * The mod does NOT suppress Wynn's own spell SFX -- custom audio layers
 * over the top. (Node / gear / aspect variants reshuffle the sound bed
 * too freely to catalog, so per-spell suppression was dropped.)
 *
 * On a cast: stop the previous instance of the same spell (unless overlap
 * is on) and start the user's file from t=0.
 *
 * All on the network thread; playback handles' .stop() is safe from here
 * (audio thread polls a volatile flag).
 */
object SpellSoundManager {

    private const val PLATE_WINDOW_MS: Long = 1200

    // Plate-trio arming. Wynn repaints the same action-bar text ~60×/s for
    // ~1.5s after a cast, so onSpellCast() would fire playback repeatedly
    // if it trusted the packet blindly. Each real cast consumes exactly 3
    // plate-click sounds (the R/L combo input). Arm on the 3rd plate inside
    // PLATE_WINDOW_MS; the next onSpellCast() consumes the arm. Repaints
    // with no fresh combo are ignored.
    private val plateTimes = ArrayDeque<Long>()
    @Volatile private var comboArmed: Boolean = false

    // Per-spell-id in-flight playback handle so consecutive casts can
    // cleanly interrupt without leaking SourceDataLines.
    private val active = ConcurrentHashMap<String, SoundLibrary.PlaybackHandle>()

    /**
     * Called from [ahjd.icomod.mixin.GameMessageS2CMixin] when the action-bar
     * parser extracts a Wynncraft cast line. [name] is the raw display name
     * ("Heal", "Ice Snake", ...); [cost] is the mana number after the dash
     * (kept for logging only).
     */
    fun onSpellCast(name: String, cost: Int) {
        if (!ConfigManager.config.spellSoundsEnabled) return
        // Repaint guard -- only emit on a fresh armed combo.
        if (!comboArmed) return
        comboArmed = false

        val spell = SpellCatalog.byDisplayName(name)
        if (spell == null) {
            AhjLog.info("Spell", "[cast] '{}' (-{}) -- unknown spell, no catalog entry", name, cost)
            return
        }
        if (!isSpellEnabled(spell.id)) {
            AhjLog.info("Spell", "[cast] {} (-{}) disabled in config -- skipping playback", spell.id, cost)
            return
        }

        AhjLog.info("Spell", "[cast] {} (-{} mana)", spell.id, cost)
        firePlayback(spell)
    }

    /**
     * Called from the sound-packet mixins for every inbound sound. Feeds
     * the plate-trio guard and the global mute toggles. Returns true to
     * cancel the packet (mute toggles only).
     */
    fun onSoundIncoming(soundId: Identifier): Boolean {
        if (!ConfigManager.config.spellSoundsEnabled) return false

        // Plate-trio arm tracking. Note BEFORE the mute check so muting
        // plates doesn't stop the combo from arming.
        if (soundId == SpellCatalog.PLATE) notePlate(System.currentTimeMillis())

        val cfg = ConfigManager.config
        if (cfg.spellMutePlates && soundId == SpellCatalog.PLATE) return true
        if (cfg.spellMuteComboPing && soundId == SpellCatalog.COMBO_PING) return true
        if (cfg.spellMuteFail && soundId == SpellCatalog.FAIL) return true

        return false
    }

    // ---- internals -------------------------------------------------------

    private fun notePlate(now: Long) {
        plateTimes.addLast(now)
        while (plateTimes.size > 3) plateTimes.removeFirst()
        if (plateTimes.size == 3 &&
            (plateTimes.last() - plateTimes.first()) <= PLATE_WINDOW_MS) {
            comboArmed = true
            plateTimes.clear()
        }
    }

    private fun firePlayback(spell: SpellCatalog.Spell) {
        val cfg = ConfigManager.config
        val file = cfg.spellPairings[spell.id]
        if (file.isNullOrBlank()) {
            AhjLog.info("Spell", "[play] {} skipped -- no file paired", spell.id)
            return
        }

        val perSpell = cfg.spellVolumes[spell.id] ?: 1.0f
        val gain = perSpell * cfg.spellMasterVolume

        // Consecutive-cast behaviour: stop the previous instance of THIS
        // spell unless overlap is enabled. Other spells' playbacks are
        // untouched (a Heal mid-Totem doesn't kill the totem loop).
        if (!cfg.spellOverlapEnabled) {
            active.remove(spell.id)?.stop()
        }
        val handle = SoundLibrary.playAsync(file, gain)
        active[spell.id] = handle
        AhjLog.info("Spell", "[play] {} file='{}' gain={} overlap={}",
            spell.id, file, "%.2f".format(gain), cfg.spellOverlapEnabled)
    }

    private fun isSpellEnabled(spellId: String): Boolean =
        ConfigManager.config.spellEnabled[spellId] ?: true
}
