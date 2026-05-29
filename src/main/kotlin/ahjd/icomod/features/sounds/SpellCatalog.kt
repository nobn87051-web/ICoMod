package ahjd.icomod.features.sounds

import net.minecraft.util.Identifier

/**
 * Static catalog of Wynncraft spells we can detect.
 *
 * Detection model (see ICoMod/tools/jsmacros/spell-sound-collector.js for
 * the data-collection script that established this signal):
 *
 * Wynncraft appends a plain-ASCII line `"<Spell Name> Cast! -<mana>"` to
 * the action-bar packet text on every completed cast. [GameMessageS2CMixin]
 * pulls that text out of the inbound `GameMessageS2CPacket` (without
 * cancelling or mutating it) and feeds the parsed name to
 * [SpellSoundManager.onSpellCast].
 *
 * The mod no longer tries to *suppress* Wynn's own spell SFX -- node /
 * gear / aspect variants change the sound bed too freely to catalog
 * reliably, so custom playback simply layers over the top. The only
 * cancellation left is the three fixed-id global mute toggles
 * ([PLATE], [COMBO_PING], [FAIL]) the user can opt into.
 */
object SpellCatalog {

    /**
     * Cast-input chatter the user may opt to mute via global toggles in
     * [ahjd.icomod.config.ModConfig]. Fixed vanilla ids, independent of
     * any per-spell data:
     *   - [PLATE]      the 3 wooden-plate clicks of the R/L combo input
     *   - [COMBO_PING] the arrow-hit ding that fires when a cast lands
     *   - [FAIL]       the anvil-land thunk on an invalid / no-mana combo
     */
    val PLATE: Identifier = id("minecraft", "block.wooden_pressure_plate.click_on")
    val COMBO_PING: Identifier = id("minecraft", "entity.arrow.hit_player")
    val FAIL: Identifier = id("minecraft", "block.anvil.land")

    enum class Kind {
        /** Short, self-contained cast (Heal, Teleport, Meteor, Bash...). */
        INSTANT,

        /** Long / world-persistent effect (Ice Snake, Totem, Arrow Shield...). */
        ACTIVE,
    }

    data class Spell(
        val id: String,
        val displayName: String,
        val classKind: String,
        val kind: Kind,
    )

    // ---- Mage ------------------------------------------------------------
    val MAGE_HEAL      = Spell("mage.heal", "Heal", "Mage", Kind.INSTANT)
    val MAGE_TELEPORT  = Spell("mage.teleport", "Teleport", "Mage", Kind.INSTANT)
    val MAGE_METEOR    = Spell("mage.meteor", "Meteor", "Mage", Kind.INSTANT)
    val MAGE_ICE_SNAKE = Spell("mage.ice_snake", "Ice Snake", "Mage", Kind.ACTIVE)

    // ---- Archer ----------------------------------------------------------
    val ARCHER_ARROW_STORM  = Spell("archer.arrow_storm", "Arrow Storm", "Archer", Kind.INSTANT)
    val ARCHER_ESCAPE       = Spell("archer.escape", "Escape", "Archer", Kind.INSTANT)
    val ARCHER_ARROW_BOMB   = Spell("archer.arrow_bomb", "Arrow Bomb", "Archer", Kind.INSTANT)
    val ARCHER_ARROW_SHIELD = Spell("archer.arrow_shield", "Arrow Shield", "Archer", Kind.ACTIVE)

    // ---- Warrior ---------------------------------------------------------
    val WARRIOR_BASH       = Spell("warrior.bash", "Bash", "Warrior", Kind.INSTANT)
    val WARRIOR_CHARGE     = Spell("warrior.charge", "Charge", "Warrior", Kind.INSTANT)
    val WARRIOR_UPPERCUT   = Spell("warrior.uppercut", "Uppercut", "Warrior", Kind.INSTANT)
    val WARRIOR_WAR_SCREAM = Spell("warrior.war_scream", "War Scream", "Warrior", Kind.INSTANT)

    // ---- Assassin --------------------------------------------------------
    val ASSASSIN_SPIN_ATTACK = Spell("assassin.spin_attack", "Spin Attack", "Assassin", Kind.INSTANT)
    val ASSASSIN_DASH        = Spell("assassin.dash", "Dash", "Assassin", Kind.INSTANT)
    val ASSASSIN_MULTIHIT    = Spell("assassin.multihit", "Multihit", "Assassin", Kind.INSTANT)
    val ASSASSIN_SMOKE_BOMB  = Spell("assassin.smoke_bomb", "Smoke Bomb", "Assassin", Kind.INSTANT)

    // ---- Shaman ----------------------------------------------------------
    val SHAMAN_TOTEM  = Spell("shaman.totem", "Totem", "Shaman", Kind.ACTIVE)
    val SHAMAN_HAUL   = Spell("shaman.haul", "Haul", "Shaman", Kind.INSTANT)
    val SHAMAN_AURA   = Spell("shaman.aura", "Aura", "Shaman", Kind.INSTANT)
    val SHAMAN_UPROOT = Spell("shaman.uproot", "Uproot", "Shaman", Kind.INSTANT)

    val ALL: List<Spell> = listOf(
        MAGE_HEAL, MAGE_TELEPORT, MAGE_METEOR, MAGE_ICE_SNAKE,
        ARCHER_ARROW_STORM, ARCHER_ESCAPE, ARCHER_ARROW_BOMB, ARCHER_ARROW_SHIELD,
        WARRIOR_BASH, WARRIOR_CHARGE, WARRIOR_UPPERCUT, WARRIOR_WAR_SCREAM,
        ASSASSIN_SPIN_ATTACK, ASSASSIN_DASH, ASSASSIN_MULTIHIT, ASSASSIN_SMOKE_BOMB,
        SHAMAN_TOTEM, SHAMAN_HAUL, SHAMAN_AURA, SHAMAN_UPROOT,
    )

    // Case-insensitive lookup by server-sent display name ("Heal", "Ice Snake", ...).
    private val byDisplayName: Map<String, Spell> =
        ALL.associateBy { it.displayName.lowercase() }

    fun byDisplayName(name: String): Spell? = byDisplayName[name.trim().lowercase()]

    fun byId(id: String): Spell? = ALL.firstOrNull { it.id == id }

    private fun id(ns: String, path: String): Identifier = Identifier.of(ns, path)
}
