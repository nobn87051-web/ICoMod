package ahjd.icomod.features.settings

/**
 * Central directory of [FeatureSection]s. Features call [register] at mod
 * init; [SettingsScreen] reads [all] each time it opens, so newly-registered
 * sections appear without restart.
 *
 * Order is insertion order — first registered, first rendered. Re-registering
 * with the same [FeatureSection.key] replaces the prior entry in-place so
 * order is stable across hot reloads.
 */
object SettingsRegistry {
    private val sections = mutableListOf<FeatureSection>()

    @Synchronized
    fun register(section: FeatureSection) {
        val existing = sections.indexOfFirst { it.key == section.key }
        if (existing >= 0) sections[existing] = section
        else sections.add(section)
    }

    @Synchronized
    fun all(): List<FeatureSection> = sections.toList()
}
