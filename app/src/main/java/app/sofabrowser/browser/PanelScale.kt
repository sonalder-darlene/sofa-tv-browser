package app.sofabrowser.browser

import kotlin.math.max

/**
 * How many panel pixels each app pixel covers: Android draws apps at 1080p on a 4K panel, so 2 there. Pages in
 * fullscreen get their pixel ratio multiplied by it (see VideoRelay). Plain Kotlin, so it is unit-tested.
 */
object PanelScale {
    fun of(panelWidth: Int, panelHeight: Int, windowWidth: Int, windowHeight: Int): Double {
        val panel = max(panelWidth, panelHeight)
        val window = max(windowWidth, windowHeight)
        if (panel <= 0 || window <= 0 || panel <= window) return 1.0
        return Math.round(panel * 100.0 / window) / 100.0
    }
}
