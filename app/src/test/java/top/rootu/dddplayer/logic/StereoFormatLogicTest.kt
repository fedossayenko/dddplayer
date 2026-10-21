package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode

class StereoFormatLogicTest {

    @Test
    fun `half and full frames are told apart`() {
        assertTrue(StereoFormatLogic.isHalfFrame(StereoInputType.SIDE_BY_SIDE, 1920f, 1080f))
        assertFalse(StereoFormatLogic.isHalfFrame(StereoInputType.SIDE_BY_SIDE, 3840f, 1080f))
        assertTrue(StereoFormatLogic.isHalfFrame(StereoInputType.TOP_BOTTOM, 1920f, 1080f))
        assertFalse(StereoFormatLogic.isHalfFrame(StereoInputType.TOP_BOTTOM, 1920f, 2160f))
        // Размер еще неизвестен
        assertTrue(StereoFormatLogic.isHalfFrame(StereoInputType.SIDE_BY_SIDE, 0f, 0f))
    }

    @Test
    fun `format codes`() {
        assertNull(StereoFormatLogic.formatCode(StereoInputType.NONE, 1920f, 1080f))
        assertEquals("HSBS", StereoFormatLogic.formatCode(StereoInputType.SIDE_BY_SIDE, 1920f, 1080f))
        assertEquals("SBS", StereoFormatLogic.formatCode(StereoInputType.SIDE_BY_SIDE, 3840f, 1080f))
        assertEquals("HOU", StereoFormatLogic.formatCode(StereoInputType.TOP_BOTTOM, 1920f, 1080f))
        assertEquals("OU", StereoFormatLogic.formatCode(StereoInputType.TOP_BOTTOM, 1920f, 2160f))
        assertEquals("Interlaced", StereoFormatLogic.formatCode(StereoInputType.INTERLACED, 1920f, 1080f))
        assertEquals("3D(z)Tiled", StereoFormatLogic.formatCode(StereoInputType.TILED_1080P, 1920f, 1080f))
    }

    @Test
    fun `badge for 3D output`() {
        assertEquals("3D (HSBS)", StereoFormatLogic.badgeLabel(StereoInputType.SIDE_BY_SIDE, 1920f, 1080f, StereoOutputMode.ANAGLYPH, false))
        assertEquals("3D (HOU) RL", StereoFormatLogic.badgeLabel(StereoInputType.TOP_BOTTOM, 1920f, 1080f, StereoOutputMode.CARDBOARD_VR, true))
        assertNull(StereoFormatLogic.badgeLabel(StereoInputType.NONE, 1920f, 1080f, StereoOutputMode.ANAGLYPH, true))
    }

    @Test
    fun `badge for 2D output names the eye on screen`() {
        assertEquals("2D (OU) L", StereoFormatLogic.badgeLabel(StereoInputType.TOP_BOTTOM, 1920f, 2160f, StereoOutputMode.LEFT_ONLY, false))
        assertEquals("2D (HSBS) R", StereoFormatLogic.badgeLabel(StereoInputType.SIDE_BY_SIDE, 1920f, 1080f, StereoOutputMode.RIGHT_ONLY, false))
        // Смена глаз при выводе левого показывает правый глаз исходника
        assertEquals("2D (HSBS) R", StereoFormatLogic.badgeLabel(StereoInputType.SIDE_BY_SIDE, 1920f, 1080f, StereoOutputMode.LEFT_ONLY, true))
    }
}
