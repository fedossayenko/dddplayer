package top.rootu.dddplayer.model

/**
 * Калибровка анаглиф-очков из визарда для группы очков (заменяет встроенную матрицу фильтра Dubois).
 * A и B - линзы базовых цветов группы (например, красная и голубая), независимо от того, какая слева.
 *
 * @param hueA сдвиг оттенка линзы A (-100..100)
 * @param hueB сдвиг оттенка линзы B (-100..100)
 * @param leakA утечка линзы A (0..0.5)
 * @param leakB утечка линзы B (0..0.5)
 */
data class AnaglyphCalibration(
    val hueA: Int = 0,
    val hueB: Int = 0,
    val leakA: Float = 0.2f,
    val leakB: Float = 0.2f
)
