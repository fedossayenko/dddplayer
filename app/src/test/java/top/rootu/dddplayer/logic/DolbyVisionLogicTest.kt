package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DolbyVisionLogicTest {

    @Test
    fun `profile is read from the codec string`() {
        assertEquals(8, DolbyVisionLogic.profile("dvhe.08.06"))
        assertEquals(5, DolbyVisionLogic.profile("dvh1.05.06"))
        assertEquals(10, DolbyVisionLogic.profile("dav1.10.09"))
        assertEquals(7, DolbyVisionLogic.profile("hvc1.2.4.L153.B0, dvhe.07.06"))
        assertNull(DolbyVisionLogic.profile("hvc1.2.4.L153.B0"))
        assertNull(DolbyVisionLogic.profile(null))
    }

    @Test
    fun `only profiles with an hevc base layer can be played as hevc`() {
        assertTrue(DolbyVisionLogic.canPlayAsHevc("dvhe.08.06"))
        assertTrue(DolbyVisionLogic.canPlayAsHevc("dvhe.07.06"))
        assertTrue(DolbyVisionLogic.canPlayAsHevc("dvhe.04.06"))
        assertFalse(DolbyVisionLogic.canPlayAsHevc("dvhe.05.06"))
        assertFalse(DolbyVisionLogic.canPlayAsHevc("dvav.09.05"))
        assertFalse(DolbyVisionLogic.canPlayAsHevc("dav1.10.09"))
        // Неизвестный профиль - как раньше, подменяем
        assertTrue(DolbyVisionLogic.canPlayAsHevc(null))
    }
}
