package ahjd.icomod.features.overlay

/**
 * Per-file persisted overlay state. Stored as a value in
 * [ahjd.icomod.config.ModConfig.overlayStates], keyed by filename
 * (with extension).
 *
 * [placed] flips from false to true on the first frame after this state
 * is created. While false, the renderer centers the entry on screen and
 * sets [placed] = true. After that point, [x]/[y] are authoritative even
 * when negative — the clamp rule allows up to 75% of the image to hang
 * off any edge, so negative coordinates are a legitimate runtime value
 * and CANNOT be used as a "not yet placed" sentinel (an earlier version
 * of this code used `x < 0` and re-centered any image dragged off the
 * left edge — fixed by this flag).
 *
 * [scale] is clamped at write-time in [MediaOverlayManager.setScale] to
 * `[0.1, 5.0]` so the render path doesn't have to defensive-clamp.
 */
data class OverlayState(
    var placed: Boolean = false,
    var x: Int = 0,
    var y: Int = 0,
    var scale: Double = 1.0,
    var hidden: Boolean = false,
    /**
     * Monotonic counter for stacking order. Bumped on each LMB drag start;
     * the renderer paints entries sorted ascending by this field so the
     * most recently touched overlay ends up on top. Tied-to `0L` for new
     * entries — they appear above unfocused old entries (older = lower
     * lastTouched) but below anything that's been interacted with since
     * the last restart, which is the intuitive default.
     */
    var lastTouched: Long = 0,
)
