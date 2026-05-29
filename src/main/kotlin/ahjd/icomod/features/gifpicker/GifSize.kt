package ahjd.icomod.features.gifpicker

/**
 * Per-message gif size, encoded as a suffix on the filename in chat:
 *   `text.gifXS` `text.gifS` (default) `text.gifM` `text.gifL`
 *
 * Heights are in chat-space pixels (9 = one chat line). Widths are upper bounds —
 * actual gif aspect-fits inside (height × maxWidth).
 */
enum class GifSize(val height: Int, val maxWidth: Int) {
    /** Emoji-sized, ~1 chat line tall. */
    XS(9, 36),
    /** Default. A little under 3 chat lines tall. */
    S(24, 110),
    /** A little under 5 chat lines tall. */
    M(40, 200),
    /** Full chat width, ~6 chat lines tall. */
    L(56, 340);

    companion object {
        /** Compile-time fallback used when config is unreadable. */
        val DEFAULT = S

        /**
         * User-configured default. Read from
         * [ahjd.icomod.config.ConfigManager.config.gifDefaultSize] each call so
         * a settings-screen change applies to the next typed-without-suffix
         * GIF without a restart.
         */
        fun configuredDefault(): GifSize = runCatching {
            valueOf(ahjd.icomod.config.ConfigManager.config.gifDefaultSize.uppercase())
        }.getOrDefault(DEFAULT)

        fun parse(suffix: String?): GifSize = when (suffix?.uppercase()) {
            "XS" -> XS
            "S" -> S
            "M" -> M
            "L" -> L
            else -> configuredDefault()
        }
    }
}
