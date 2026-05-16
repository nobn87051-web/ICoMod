package ahjd.icomod.features.gifpicker

/**
 * Per-message gif size, encoded as a suffix on the filename in chat:
 *   `text.gifXS` `text.gifS` (default) `text.gifM` `text.gifL`
 *
 * Heights are in chat-space pixels (9 = one chat line). Widths are upper bounds â€”
 * actual gif aspect-fits inside (height Ã— maxWidth).
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
        val DEFAULT = S

        fun parse(suffix: String?): GifSize = when (suffix?.uppercase()) {
            "XS" -> XS
            "S" -> S
            "M" -> M
            "L" -> L
            else -> DEFAULT
        }
    }
}
