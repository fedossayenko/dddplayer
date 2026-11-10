package top.rootu.dddplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fold71Test {
    private val matrices = Fold71.matrices()

    @Test
    fun coversEveryChannelCountUpToEightAndOnlyEightChangesTheCount() {
        assertEquals((1..12).toList(), matrices.map { it.inputChannelCount }.sorted())
        matrices.filter { it.inputChannelCount != 8 }.forEach { assertTrue(it.isIdentity) }
        assertEquals(6, matrices.single { it.inputChannelCount == 8 }.outputChannelCount)
    }

    @Test
    fun sevenOneFoldsSidesIntoTheSurroundsAndKeepsTheFront() {
        val m = matrices.single { it.inputChannelCount == 8 }
        for (c in 0 until 4) assertEquals(1f, m.getMixingCoefficient(c, c), 0f)
        assertEquals(0.7071f, m.getMixingCoefficient(6, 4), 1e-4f) // SL -> BL
        assertEquals(0.7071f, m.getMixingCoefficient(7, 5), 1e-4f) // SR -> BR
        assertEquals(0.7071f, m.getMixingCoefficient(4, 4), 1e-4f)
        assertEquals(0f, m.getMixingCoefficient(6, 5), 0f) // стороны не смешиваются
    }
}
