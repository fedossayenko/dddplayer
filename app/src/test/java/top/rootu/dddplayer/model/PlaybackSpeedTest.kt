package top.rootu.dddplayer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSpeedTest {

    @Test
    fun `label has two decimals`() {
        assertEquals("1.00x", PlaybackSpeed.label(1f))
        assertEquals("1.15x", PlaybackSpeed.label(PlaybackSpeed.normalize(1.149f)))
    }

    @Test
    fun `normalize rounds to step and clamps`() {
        assertEquals(1.10f, PlaybackSpeed.normalize(1.12f), 0.0001f)
        assertEquals(PlaybackSpeed.CUSTOM_MIN, PlaybackSpeed.normalize(0.01f), 0.0001f)
        assertEquals(PlaybackSpeed.CUSTOM_MAX, PlaybackSpeed.normalize(9f), 0.0001f)
    }

    @Test
    fun `preset matches only list speeds`() {
        assertEquals(PlaybackSpeed.X1_25, PlaybackSpeed.preset(1.25f))
        assertEquals(PlaybackSpeed.X1_50, PlaybackSpeed.preset(PlaybackSpeed.normalize(1.5f)))
        assertNull(PlaybackSpeed.preset(1.10f))
    }
}
