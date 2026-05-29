package ahjd.icomod.features.settings

/**
 * Typed knob a feature exposes to the settings screen. Each variant maps to
 * a specific widget kind so [SettingsScreen] can render it without knowing
 * anything about the feature.
 *
 * Getters / setters wrap the underlying [ahjd.icomod.config.ConfigManager]
 * field. The screen stages edits in a `pending` map and only calls the
 * setter (and `ConfigManager.save()`) when the user presses Done; pressing
 * Discard drops the pending edits. [ActionItem.onClick] is the exception
 * — actions fire immediately because they aren't reversible values.
 */
sealed class SettingItem {
    abstract val label: String
    abstract val help: String?

    data class BoolItem(
        override val label: String,
        override val help: String? = null,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
    ) : SettingItem()

    data class EnumItem(
        override val label: String,
        override val help: String? = null,
        val options: List<String>,
        val get: () -> String,
        val set: (String) -> Unit,
    ) : SettingItem()

    data class StringItem(
        override val label: String,
        override val help: String? = null,
        val get: () -> String,
        val set: (String) -> Unit,
        val maxLen: Int = 128,
    ) : SettingItem()

    data class ActionItem(
        override val label: String,
        override val help: String? = null,
        val buttonText: String = "Run",
        val onClick: () -> Unit,
    ) : SettingItem()

    /**
     * Decorative subsection divider rendered inside a section. Useful when one
     * section groups several semantically distinct rows (e.g. Custom Spell
     * Sounds: "Setup" actions vs. "Mage Spells" cards).
     */
    data class SectionHeaderItem(
        override val label: String,
        override val help: String? = null,
        /** 0 = use [ahjd.icomod.features.settings.ui.WarmPalette.ACCENT]. */
        val accent: Int = 0,
    ) : SettingItem()

    /**
     * Composite card row for a single spell. Renders as a tall, accent-striped
     * card with the spell name on the left and three controls on the right:
     * enable toggle, sound-file dropdown, and a Preview button. Class is shown
     * as a small pill badge; classifier sound IDs are surfaced in [help].
     *
     * Staging works field-by-field: enable and file each stage independently
     * through [SettingsScreen]'s pending map.
     */
    data class SpellCardItem(
        override val label: String,
        override val help: String? = null,
        val classKind: String,
        /** Class accent color, used for the left stripe + pill. */
        val accentColor: Int,
        val getEnabled: () -> Boolean,
        val setEnabled: (Boolean) -> Unit,
        val fileOptions: List<String>,
        val getFile: () -> String,
        val setFile: (String) -> Unit,
        val getVolume: () -> Float,
        val setVolume: (Float) -> Unit,
        val onPreview: () -> Unit,
    ) : SettingItem()

    /**
     * Searchable picker over a dynamic option list (type-to-filter popup).
     * Rendered as a button showing the current value; clicking opens a picker
     * screen. Applies live (no staging). Used by the emote-wheel slot binds.
     */
    data class PickItem(
        override val label: String,
        override val help: String? = null,
        val get: () -> String,
        val set: (String) -> Unit,
        val options: () -> List<String>,
        val placeholder: String = "(none)",
    ) : SettingItem()

    /** 0..1 float slider with optional label formatter. */
    data class SliderItem(
        override val label: String,
        override val help: String? = null,
        val get: () -> Float,
        val set: (Float) -> Unit,
        val format: (Float) -> String = { "${(it * 100).toInt()}%" },
    ) : SettingItem()
}

/**
 * One feature's group of settings. Registered with [SettingsRegistry] so the
 * screen builds itself from whatever's been added — no central list to keep
 * in sync as features come and go.
 *
 * Sections with a static set of knobs pass [items] directly. Sections whose
 * row list changes at runtime (e.g. Media Overlay: one row per file in the
 * media folder) pass [itemsProvider] instead, and the screen invokes it each
 * time it builds the pane so additions/removals show up on the next open
 * (or after a [SettingsScreen.buildPane] call from an action handler).
 */
data class FeatureSection(
    val key: String,
    val title: String,
    val items: List<SettingItem> = emptyList(),
    val itemsProvider: (() -> List<SettingItem>)? = null,
) {
    fun resolveItems(): List<SettingItem> = itemsProvider?.invoke() ?: items
}
