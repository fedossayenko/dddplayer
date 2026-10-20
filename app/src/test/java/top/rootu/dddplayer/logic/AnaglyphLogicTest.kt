package top.rootu.dddplayer.logic

import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.model.AnaglyphCalibration
import top.rootu.dddplayer.renderer.StereoRenderer.AnaglyphType
import top.rootu.dddplayer.utils.DuboisMath
import top.rootu.dddplayer.viewmodel.GlassesGroup

// Color.colorToHSV и DuboisMath используют Android API
@RunWith(RobolectricTestRunner::class)
class AnaglyphLogicTest {

    @Test
    fun `calibrated filter is listed first only when the glasses are calibrated`() {
        assertEquals(AnaglyphType.RC_CALIBRATED, AnaglyphLogic.calibratedFilter(GlassesGroup.RED_CYAN))
        assertEquals(null, AnaglyphLogic.calibratedFilter(GlassesGroup.RED_BLUE))
        assertFalse(AnaglyphType.RC_CALIBRATED in AnaglyphLogic.getFiltersForGroup(GlassesGroup.RED_CYAN))
        assertEquals(
            AnaglyphType.RC_CALIBRATED,
            AnaglyphLogic.getFiltersForGroup(GlassesGroup.RED_CYAN, includeCalibrated = true).first()
        )
        assertEquals(GlassesGroup.YELLOW_BLUE, AnaglyphLogic.getGlassesGroup(AnaglyphType.YB_CALIBRATED))
        assertTrue(AnaglyphLogic.isCalibratedType(AnaglyphType.GM_CALIBRATED))
    }

    @Test
    fun `built-in default filter of each group`() {
        assertEquals(AnaglyphType.RC_DUBOIS, AnaglyphLogic.defaultFilter(GlassesGroup.RED_CYAN))
        assertEquals(AnaglyphType.YB_DUBOIS, AnaglyphLogic.defaultFilter(GlassesGroup.YELLOW_BLUE))
        assertEquals(AnaglyphType.GM_DUBOIS, AnaglyphLogic.defaultFilter(GlassesGroup.GREEN_MAGENTA))
        assertEquals(AnaglyphType.RB_MONO, AnaglyphLogic.defaultFilter(GlassesGroup.RED_BLUE))
        assertTrue(AnaglyphLogic.isCalibratable(GlassesGroup.RED_CYAN))
        assertFalse(AnaglyphLogic.isCalibratable(GlassesGroup.RED_BLUE))
    }

    @Test
    fun `calibration uses the group lens colors with hue offsets and leaks`() {
        val calibration = AnaglyphCalibration(hueA = 10, hueB = -10, leakA = 0.1f, leakB = 0.25f)
        val result = AnaglyphLogic.calculateCalibrated(GlassesGroup.RED_CYAN, calibration)
        val expected = DuboisMath.calculate(
            DuboisMath.DuboisParams(
                AnaglyphLogic.applyHueOffset(Color.RED, 10),
                AnaglyphLogic.applyHueOffset(Color.CYAN, -10),
                0.1f, 0.25f, false
            )
        )
        assertArrayEquals(expected.left, result.left, 1e-6f)
        assertArrayEquals(expected.right, result.right, 1e-6f)
    }

    @Test
    fun `zero hue offset keeps the base color`() {
        assertEquals(Color.RED, AnaglyphLogic.applyHueOffset(Color.RED, 0))
    }
}
