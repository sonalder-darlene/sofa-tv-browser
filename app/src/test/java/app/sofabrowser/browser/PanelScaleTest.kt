package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelScaleTest {

    @Test
    fun aFourKPanelUnderA1080pWindowDoublesTheRatio() {
        assertEquals(2.0, PanelScale.of(panelWidth = 3840, panelHeight = 2160, windowWidth = 1920, windowHeight = 1080), 0.0)
    }

    @Test
    fun aPanelNoBiggerThanTheWindowChangesNothing() {
        assertEquals(1.0, PanelScale.of(1920, 1080, 1920, 1080), 0.0)
        assertEquals(1.0, PanelScale.of(1280, 720, 1920, 1080), 0.0)
        assertEquals(1.0, PanelScale.of(0, 0, 1920, 1080), 0.0)
        assertEquals(1.0, PanelScale.of(3840, 2160, 0, 0), 0.0)
    }

    @Test
    fun otherSizesAndOrientationsUseTheLongSide() {
        assertEquals(1.5, PanelScale.of(1080, 1920, 1280, 720), 0.0)
        assertEquals(3.0, PanelScale.of(3840, 2160, 1280, 720), 0.0)
    }
}
