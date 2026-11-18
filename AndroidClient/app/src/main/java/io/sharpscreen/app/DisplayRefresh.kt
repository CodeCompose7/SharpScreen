package io.sharpscreen.app

import android.view.Display

/**
 * Keeps the panel at its top refresh rate while streaming.
 *
 * Samsung's adaptive refresh dropped the Tab S11 Ultra to 96 Hz under a
 * 120 fps stream because nothing asked for more: every fifth frame was then
 * dropped unevenly, and dragged windows and the cursor stuttered.
 */
object DisplayRefresh {
    /** The bits of [Display.Mode] the choice depends on (testable without a device). */
    data class Mode(
        val id: Int,
        val width: Int,
        val height: Int,
        val refreshRate: Float,
    )

    /**
     * The mode with the highest refresh rate at the current resolution, or
     * null when there is nothing better than [current].
     */
    fun highestRefreshMode(
        modes: List<Mode>,
        current: Mode,
    ): Mode? =
        modes
            .filter { it.width == current.width && it.height == current.height }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate || it.id != current.id }

    /** Highest refresh rate any mode at the current resolution offers. */
    fun peakRefreshRate(display: Display?): Float {
        display ?: return 60f
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: display.refreshRate
    }

    fun Display.Mode.toMode() = Mode(modeId, physicalWidth, physicalHeight, refreshRate)
}
