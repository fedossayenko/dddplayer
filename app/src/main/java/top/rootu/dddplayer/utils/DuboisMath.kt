package top.rootu.dddplayer.utils

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.pow

object DuboisMath {

    // Базисные матрицы
    private val mXYZ = arrayOf(
        floatArrayOf(0.4124f, 0.3576f, 0.1805f),
        floatArrayOf(0.2126f, 0.7152f, 0.0722f),
        floatArrayOf(0.0193f, 0.1192f, 0.9505f)
    )

    private val mLMS = arrayOf(
        floatArrayOf(0.3811f, 0.5783f, 0.0402f),
        floatArrayOf(0.1967f, 0.7244f, 0.0782f),
        floatArrayOf(0.0241f, 0.1288f, 0.8444f)
    )

    data class DuboisParams(
        val colorLeft: Int,
        val colorRight: Int,
        val leakL: Float, // 0.0 to 0.5 (0% - 50%)
        val leakR: Float,
        val useLms: Boolean
    )

    data class ResultMatrices(
        val left: FloatArray,
        val right: FloatArray,
        val isValid: Boolean = true
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ResultMatrices

            if (isValid != other.isValid) return false
            if (!left.contentEquals(other.left)) return false
            if (!right.contentEquals(other.right)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = isValid.hashCode()
            result = 31 * result + left.contentHashCode()
            result = 31 * result + right.contentHashCode()
            return result
        }
    }

    fun calculate(params: DuboisParams): ResultMatrices {
        val cL = colorToLinear(params.colorLeft)
        val cR = colorToLinear(params.colorRight)

        val mBasis = if (params.useLms) mLMS else mXYZ

        // Моделирование фильтров с раздельной утечкой
        val gray = floatArrayOf(0.299f, 0.587f, 0.114f)
        val effL = FloatArray(3) { i -> cL[i] + cR[i] * params.leakL * gray[i] }
        val effR = FloatArray(3) { i -> cR[i] + cL[i] * params.leakR * gray[i] }

        // Составляем матрицу Q (6x3)
        // Q = [ Basis * effL ]
        //     [ Basis * effR ]
        val qL = Array(3) { row -> FloatArray(3) { c -> mBasis[row][c] * effL[c] } }
        val qR = Array(3) { row -> FloatArray(3) { c -> mBasis[row][c] * effR[c] } }

        return solve(qL, qR, mBasis) ?: ResultMatrices(
            // Если инверсия не удалась, возвращаем цвета и помечаем как Invalid
            floatArrayOf(cL[0], 0f, 0f, 0f, cL[1], 0f, 0f, 0f, cL[2]),
            floatArrayOf(cR[0], 0f, 0f, 0f, cR[1], 0f, 0f, 0f, cR[2]),
            isValid = false
        )
    }

    /**
     * Матрицы фильтра "Калибровка": классический Dubois для идеальных фильтров плюс подавление двоения.
     *
     * Свои цвета фильтров - основные цвета экрана (ownA/ownB: индексы 0=R, 1=G, 2=B).
     * Утечка leakA - доля чужого света, прошедшего через фильтр A: полный дополнительный цвет через фильтр
     * выглядит как свой цвет фильтра с линейной яркостью leakA. Изображение второго глаза, просочившееся
     * через фильтр, вычитается из каналов своего цвета ("антитень"). Подавление работает, пока каналы
     * не уходят в 0: на средних и светлых тонах тень исчезает, в темных остается.
     * Без утечек получается классический Dubois.
     */
    fun calculateWithCrosstalk(ownA: Set<Int>, ownB: Set<Int>, leakA: Float, leakB: Float): ResultMatrices {
        val ideal = solveMatrices(filterResponse(ownA), filterResponse(ownB), mXYZ)
            ?: return ResultMatrices(
                FloatArray(9) { if (it % 4 == 0 && it / 4 in ownA) 1f else 0f },
                FloatArray(9) { if (it % 4 == 0 && it / 4 in ownB) 1f else 0f },
                isValid = false
            )
        val (mA, mB) = ideal
        // Остаток самого Dubois (без утечек) сохраняем, убираем только то, что добавляет утечка
        val targetA = FloatArray(3) { c -> weighted(mB, ownA, c) }
        val targetB = FloatArray(3) { c -> weighted(mA, ownB, c) }
        // Вычитание сдвигает баланс белого, а нормализация - вычитание: несколько итераций сходятся
        repeat(CROSSTALK_ITERATIONS) {
            val errorA = FloatArray(3) { c -> ghostThroughFilter(mB, ownA, leakA, c) - targetA[c] }
            val errorB = FloatArray(3) { c -> ghostThroughFilter(mA, ownB, leakB, c) - targetB[c] }
            for (row in ownA) for (c in 0..2) mB[row][c] -= errorA[c]
            for (row in ownB) for (c in 0..2) mA[row][c] -= errorB[c]
            normalize(mA, mB)
        }
        return ResultMatrices(flatten(mA), flatten(mB), true)
    }

    private const val CROSSTALK_ITERATIONS = 20

    /**
     * Яркость, которую входной канал c изображения (матрица m) дает глазу за фильтром own:
     * свои основные цвета целиком плюс остальные с долей leak (своя яркость фильтра = 1).
     */
    private fun ghostThroughFilter(m: Array<FloatArray>, own: Set<Int>, leak: Float, c: Int): Float =
        weighted(m, own, c) + leak * weighted(m, (0..2).filter { it !in own }.toSet(), c)

    // Вклад входного канала c в яркость основных цветов primaries (веса по яркости, сумма весов = 1)
    private fun weighted(m: Array<FloatArray>, primaries: Set<Int>, c: Int): Float {
        val total = primaries.sumOf { mXYZ[1][it].toDouble() }.toFloat()
        if (total <= 0f) return 0f
        return primaries.sumOf { (mXYZ[1][it] / total * m[it][c]).toDouble() }.toFloat()
    }

    // XYZ, видимый через идеальный фильтр, для каждого основного цвета экрана (столбцы)
    private fun filterResponse(own: Set<Int>): Array<FloatArray> =
        Array(3) { row -> FloatArray(3) { c -> if (c in own) mXYZ[row][c] else 0f } }

    /**
     * Метод наименьших квадратов Dubois: Q = [qL; qR] (6x3), P = (Q^T Q)^-1 Q^T, итог = P * Basis.
     * @return null, если матрица вырождена
     */
    private fun solve(qL: Array<FloatArray>, qR: Array<FloatArray>, mBasis: Array<FloatArray>): ResultMatrices? =
        solveMatrices(qL, qR, mBasis)?.let { (l, r) -> ResultMatrices(flatten(l), flatten(r), true) }

    private fun solveMatrices(
        qL: Array<FloatArray>,
        qR: Array<FloatArray>,
        mBasis: Array<FloatArray>
    ): Pair<Array<FloatArray>, Array<FloatArray>>? {
        val q = Array(6) { row -> if (row < 3) qL[row] else qR[row - 3] }

        val qt = transpose(q) // 3x6
        val qtq = multiply(qt, q) // 3x3
        val qtqInv = invert3x3(qtq) ?: return null

        val mPInv = multiply(qtqInv, qt) // 3x6

        // Extract pLeft (3x3) and pRight (3x3) from pseudo-inverse
        val pLeft = Array(3) { i -> floatArrayOf(mPInv[i][0], mPInv[i][1], mPInv[i][2]) }
        val pRight = Array(3) { i -> floatArrayOf(mPInv[i][3], mPInv[i][4], mPInv[i][5]) }

        // Final = P * Basis
        val finalL = multiply3x3(pLeft, mBasis)
        val finalR = multiply3x3(pRight, mBasis)

        // Normalize (White balance fix)
        normalize(finalL, finalR)

        return finalL to finalR
    }

    /** Значение sRGB (0..1) -> линейная яркость (0..1). */
    fun srgbToLinear(x: Float): Float = sRGBtoLinear(x)

    /** Линейная яркость (0..1) -> значение sRGB (0..1). */
    fun linearToSrgb(x: Float): Float =
        if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f

    private fun sRGBtoLinear(x: Float): Float {
        return if (x <= 0.04045f) x / 12.92f else ((x + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun colorToLinear(color: Int): FloatArray {
        return floatArrayOf(
            sRGBtoLinear(Color.red(color) / 255f),
            sRGBtoLinear(Color.green(color) / 255f),
            sRGBtoLinear(Color.blue(color) / 255f)
        )
    }

    // --- Linear Algebra Utils ---

    private fun transpose(m: Array<FloatArray>): Array<FloatArray> {
        val rows = m.size
        val cols = m[0].size
        val res = Array(cols) { FloatArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                res[j][i] = m[i][j]
            }
        }
        return res
    }

    // Multiply (m x n) by (n x p) -> (m x p)
    private fun multiply(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        val m = a.size
        val n = a[0].size
        val p = b[0].size
        val res = Array(m) { FloatArray(p) }
        for (i in 0 until m) {
            for (j in 0 until p) {
                var sum = 0f
                for (k in 0 until n) sum += a[i][k] * b[k][j]
                res[i][j] = sum
            }
        }
        return res
    }

    private fun multiply3x3(a: Array<FloatArray>, b: Array<FloatArray>): Array<FloatArray> {
        return multiply(a, b)
    }

    private fun invert3x3(m: Array<FloatArray>): Array<FloatArray>? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[2][1] * m[1][2]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

        if (abs(det) < 1e-9) return null
        val invDet = 1.0f / det

        val res = Array(3) { FloatArray(3) }
        res[0][0] = (m[1][1] * m[2][2] - m[2][1] * m[1][2]) * invDet
        res[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet
        res[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet
        res[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet
        res[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet
        res[1][2] = (m[1][0] * m[0][2] - m[0][0] * m[1][2]) * invDet
        res[2][0] = (m[1][0] * m[2][1] - m[2][0] * m[1][1]) * invDet
        res[2][1] = (m[2][0] * m[0][1] - m[0][0] * m[2][1]) * invDet
        res[2][2] = (m[0][0] * m[1][1] - m[1][0] * m[0][1]) * invDet
        return res
    }

    private fun normalize(mL: Array<FloatArray>, mR: Array<FloatArray>) {
        for (row in 0 until 3) {
            var sum = 0f
            for (col in 0 until 3) sum += mL[row][col]
            for (col in 0 until 3) sum += mR[row][col]
            if (abs(sum) > 0.0001f) {
                val scale = 1.0f / sum
                for (col in 0 until 3) {
                    mL[row][col] *= scale
                    mR[row][col] *= scale
                }
            }
        }
    }

    private fun flatten(m: Array<FloatArray>): FloatArray {
        return floatArrayOf(
            m[0][0], m[0][1], m[0][2],
            m[1][0], m[1][1], m[1][2],
            m[2][0], m[2][1], m[2][2]
        )
    }
}