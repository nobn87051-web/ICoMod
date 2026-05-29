package ahjd.icomod.features.sounds

/**
 * Pulls Wynncraft's `"<Spell Name> Cast! -<mana>"` line out of an
 * action-bar string.
 *
 * The action-bar text Wynn sends is mostly PUA bitmap-font glyphs (HUD
 * bars, mana digits, etc.) with the cast notice appended as plain ASCII
 * when a cast lands. Stripping non-printable-ASCII leaves just that
 * notice for the regex to match.
 *
 * Lives outside [ahjd.icomod.mixin.GameMessageS2CMixin] because Kotlin
 * `companion object` synthesises a static `Companion` field on the host
 * class, which Mixin rejects at apply time ("contains non-private static
 * field Companion").
 */
object SpellCastParser {

    data class Parsed(val name: String, val cost: Int)

    /**
     * Matches "Heal Cast! -19", "Ice Snake Cast! -25", etc.
     * Name = 1-4 capitalized words separated by spaces.
     */
    private val CAST_RE = Regex(
        "([A-Z][A-Za-z]+(?:\\s+[A-Z][A-Za-z]+){0,3})\\s+Cast!\\s*-?(\\d+)"
    )

    fun parse(raw: String?): Parsed? {
        if (raw.isNullOrEmpty()) return null
        val ascii = asciiOnly(raw)
        val m = CAST_RE.find(ascii) ?: return null
        val cost = m.groupValues[2].toIntOrNull() ?: return null
        return Parsed(name = m.groupValues[1].trim(), cost = cost)
    }

    private fun asciiOnly(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices) {
            val c = s[i].code
            if (c in 0x20..0x7E) sb.append(s[i])
        }
        return sb.toString()
    }
}
