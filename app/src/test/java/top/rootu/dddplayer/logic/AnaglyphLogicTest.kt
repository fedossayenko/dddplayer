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
    fun `calibration uses the filter primaries of the group and the leaks`() {
        val calibration = AnaglyphCalibration(leakA = 0.1f, leakB = 0.02f)
        val result = AnaglyphLogic.calculateCalibrated(GlassesGroup.RED_CYAN, calibration)
        val expected = DuboisMath.calculateWithCrosstalk(setOf(0), setOf(1, 2), 0.1f, 0.02f)
        assertArrayEquals(expected.left, result.left, 1e-6f)
        assertArrayEquals(expected.right, result.right, 1e-6f)
    }

    @Test
    fun `crosstalk matrices keep white and cancel the leaked image`() {
        val leakA = 0.1f
        val leakB = 0.02f
        val ideal = DuboisMath.calculateWithCrosstalk(setOf(0), setOf(1, 2), 0f, 0f)
        val m = DuboisMath.calculateWithCrosstalk(setOf(0), setOf(1, 2), leakA, leakB)
        assertTrue(ideal.isValid && m.isValid)
        // Белый в обоих глазах дает белый на экране (сумма строк L и R = 1)
        for (row in 0..2) {
            val sum = (0..2).sumOf { (m.left[row * 3 + it] + m.right[row * 3 + it]).toDouble() }
            assertEquals(1.0, sum, 1e-4)
        }
        // Яркость через фильтр: свой канал целиком, остальные (с весами по яркости) - с утечкой
        val yG = 0.7152f
        val yB = 0.0722f
        fun throughRed(mat: FloatArray, c: Int, leak: Float) =
            mat[c] + leak * (yG * mat[3 + c] + yB * mat[6 + c]) / (yG + yB)
        fun throughCyan(mat: FloatArray, c: Int, leak: Float) = (yG * mat[3 + c] + yB * mat[6 + c]) / (yG + yB) + leak * mat[c]
        for (c in 0..2) {
            // Остается только собственный остаток Dubois, утечка вычтена
            assertEquals(throughRed(ideal.right, c, 0f), throughRed(m.right, c, leakA), 1e-4f)
            assertEquals(throughCyan(ideal.left, c, 0f), throughCyan(m.left, c, leakB), 1e-4f)
        }
        assertFalse(ideal.right.contentEquals(m.right))
    }

    @Test
    fun `srgb and linear conversions are inverse`() {
        for (v in listOf(0f, 0.02f, 0.15f, 0.35f, 0.5f, 1f)) {
            assertEquals(v, DuboisMath.linearToSrgb(DuboisMath.srgbToLinear(v)), 1e-4f)
        }
    }

    @Test
    fun `red filter leaks more than cyan by default`() {
        val rc = AnaglyphLogic.defaultCalibration(GlassesGroup.RED_CYAN)
        assertTrue(rc.leakA > rc.leakB)
        assertEquals(AnaglyphCalibration(), AnaglyphLogic.defaultCalibration(GlassesGroup.YELLOW_BLUE))
    }

    @Test
    fun `zero hue offset keeps the base color`() {
        assertEquals(Color.RED, AnaglyphLogic.applyHueOffset(Color.RED, 0))
    }
}
