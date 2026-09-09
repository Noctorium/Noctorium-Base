package app.spiceity.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeSliderTest {
    @Test
    fun `boost slider preserves precision through one hundred percent`() {
        assertEquals(.25f, volumeToSliderPosition(.5f, boosted = true), .001f)
        assertEquals(.5f, volumeToSliderPosition(1f, boosted = true), .001f)
        assertEquals(.5f, sliderPositionToVolume(.25f, boosted = true), .001f)
        assertEquals(1f, sliderPositionToVolume(.5f, boosted = true), .001f)
    }

    @Test
    fun `boost slider reaches one thousand percent`() {
        assertEquals(10f, sliderPositionToVolume(1f, boosted = true), .001f)
        assertEquals(1f, volumeToSliderPosition(10f, boosted = true), .001f)
    }
}
