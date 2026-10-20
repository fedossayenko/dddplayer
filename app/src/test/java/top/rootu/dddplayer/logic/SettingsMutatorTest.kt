package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import top.rootu.dddplayer.model.StereoOutputMode

class SettingsMutatorTest {

    @Test
    fun `cycleEnum wraps around in both directions`() {
        assertEquals(StereoOutputMode.LEFT_ONLY, SettingsMutator.cycleEnum(StereoOutputMode.ANAGLYPH, 1))
        assertEquals(StereoOutputMode.CARDBOARD_VR, SettingsMutator.cycleEnum(StereoOutputMode.ANAGLYPH, -1))
        assertEquals(StereoOutputMode.ANAGLYPH, SettingsMutator.cycleEnum(StereoOutputMode.CARDBOARD_VR, 1))
    }

    @Test
    fun `cycleList wraps around and falls back to the first item`() {
        val list = listOf(10, 20, 30)
        assertEquals(20, SettingsMutator.cycleList(10, list, 1))
        assertEquals(30, SettingsMutator.cycleList(10, list, -1))
        assertEquals(10, SettingsMutator.cycleList(99, list, 1))
        assertEquals(5, SettingsMutator.cycleList(5, emptyList(), 1))
    }

    @Test
    fun `modify values stay within bounds`() {
        assertEquals(0.15f, SettingsMutator.modifyFloat(0.149f, 1, 0.005f, -0.15f, 0.15f), 1e-6f)
        assertEquals(-0.15f, SettingsMutator.modifyFloat(-0.15f, -1, 0.005f, -0.15f, 0.15f), 1e-6f)
        assertEquals(100, SettingsMutator.modifyInt(100, 1, 5, 0, 100))
        assertEquals(15, SettingsMutator.modifyInt(10, 1, 5, 0, 100))
    }
}
