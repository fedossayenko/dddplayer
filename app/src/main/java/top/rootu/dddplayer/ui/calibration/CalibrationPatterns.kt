package top.rootu.dddplayer.ui.calibration

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Тестовые стереокартинки для визардов калибровки. Рисуются программно (без файлов и лицензий)
 * в кадр Full Side-by-Side: каждый глаз - половина кадра, сжатая по горизонтали вдвое,
 * дальше кадр проходит через тот же StereoRenderer, что и видео.
 */
object CalibrationPatterns {

    const val FRAME_WIDTH = 1920
    const val FRAME_HEIGHT = 1080

    // Логический размер кадра одного глаза (до сжатия в половину кадра)
    private const val EYE_W = FRAME_WIDTH.toFloat()
    private const val EYE_H = FRAME_HEIGHT.toFloat()

    enum class Pattern { INTRO, CROSSHAIR, GRID, FRAME, DEPTH, MATCH }

    /**
     * @param depthScale DEPTH: множитель параллакса (для анаглифа 1, в VR можно больше)
     * @param ownPrimaries MATCH: основные цвета, которые пропускает проверяемый светофильтр (0=R, 1=G, 2=B)
     * @param ownLevel MATCH: яркость своего цвета фильтра (значение sRGB 0..1)
     */
    fun draw(
        canvas: Canvas,
        pattern: Pattern,
        depthScale: Float = 1f,
        ownPrimaries: Set<Int> = emptySet(),
        ownLevel: Float = 0f
    ) {
        canvas.drawColor(Color.BLACK)
        for (eye in 0..1) {
            canvas.save()
            canvas.translate(eye * FRAME_WIDTH / 2f, 0f)
            canvas.clipRect(0f, 0f, FRAME_WIDTH / 2f, EYE_H)
            canvas.scale(0.5f, 1f)
            when (pattern) {
                Pattern.INTRO -> drawIntro(canvas)
                Pattern.CROSSHAIR -> drawCrosshair(canvas)
                Pattern.GRID -> drawGrid(canvas)
                Pattern.FRAME -> drawFrame(canvas)
                Pattern.DEPTH -> drawDepth(canvas, eye, depthScale)
                Pattern.MATCH -> drawMatch(canvas, ownPrimaries, ownLevel)
            }
            canvas.restore()
        }
    }

    private fun paint(color: Int, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        if (stroke > 0f) {
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
    }

    private fun drawIntro(canvas: Canvas) {
        canvas.drawColor(0xFF101820.toInt())
        canvas.drawCircle(EYE_W / 2, EYE_H / 2, 260f, paint(0xFF2E4A62.toInt()))
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 200f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("3D", EYE_W / 2, EYE_H / 2 + 70f, text)
    }

    // Перекрестие и кольца по центру: при верном разносе экранов два перекрестия сливаются в одно
    private fun drawCrosshair(canvas: Canvas) {
        val cx = EYE_W / 2
        val cy = EYE_H / 2
        val line = paint(Color.WHITE, 3f)
        canvas.drawLine(0f, cy, EYE_W, cy, line)
        canvas.drawLine(cx, 0f, cx, EYE_H, line)
        val ring = paint(0xFF4FC3F7.toInt(), 4f)
        for (r in 100..500 step 100) canvas.drawCircle(cx, cy, r.toFloat(), ring)
        canvas.drawCircle(cx, cy, 14f, paint(0xFF76FF03.toInt()))
    }

    // Сетка прямых линий: при верной дисторсии линии у краев остаются прямыми
    private fun drawGrid(canvas: Canvas) {
        val thin = paint(0xFFB0BEC5.toInt(), 3f)
        val step = 120f
        var x = EYE_W / 2 % step
        while (x <= EYE_W) { canvas.drawLine(x, 0f, x, EYE_H, thin); x += step }
        var y = EYE_H / 2 % step
        while (y <= EYE_H) { canvas.drawLine(0f, y, EYE_W, y, thin); y += step }
        val center = paint(0xFFFFEB3B.toInt(), 6f)
        canvas.drawLine(EYE_W / 2, 0f, EYE_W / 2, EYE_H, center)
        canvas.drawLine(0f, EYE_H / 2, EYE_W, EYE_H / 2, center)
    }

    // Рамка по краю кадра с угловыми метками: масштаб подбирают так, чтобы край был виден целиком
    private fun drawFrame(canvas: Canvas) {
        canvas.drawColor(0xFF0D1B2A.toInt())
        val border = paint(0xFFFFEB3B.toInt(), 16f)
        canvas.drawRect(RectF(8f, 8f, EYE_W - 8f, EYE_H - 8f), border)
        val inner = paint(0xFF4FC3F7.toInt(), 4f)
        val inset = 0.1f
        canvas.drawRect(RectF(EYE_W * inset, EYE_H * inset, EYE_W * (1 - inset), EYE_H * (1 - inset)), inner)
        val corner = paint(0xFFFF5252.toInt(), 12f)
        val len = 120f
        listOf(0f to 0f, EYE_W to 0f, 0f to EYE_H, EYE_W to EYE_H).forEach { (x, y) ->
            val dx = if (x == 0f) len else -len
            val dy = if (y == 0f) len else -len
            val px = if (x == 0f) 24f else x - 24f
            val py = if (y == 0f) 24f else y - 24f
            canvas.drawLine(px, py, px + dx, py, corner)
            canvas.drawLine(px, py, px, py + dy, corner)
        }
        canvas.drawCircle(EYE_W / 2, EYE_H / 2, 10f, paint(Color.WHITE))
    }

    /**
     * Стереопара с небольшим параллаксом (как в обычном 3D видео, не больше ~1% ширины):
     * сетка фона за экраном, квадрат на уровне экрана и ряд кругов от "за экраном" к "перед экраном".
     * Параллакс > 0 - объект за экраном (изображение правого глаза смещено вправо).
     */
    private fun drawDepth(canvas: Canvas, eye: Int, scale: Float) {
        fun shift(parallax: Float) = (if (eye == 0) -parallax / 2 else parallax / 2) * scale

        canvas.drawColor(0xFF3A3A3A.toInt())
        // Фон за экраном
        val behind = shift(12f)
        val grid = paint(0xFF5A5A5A.toInt(), 4f)
        var x = -120f
        while (x <= EYE_W + 120f) { canvas.drawLine(x + behind, 0f, x + behind, EYE_H, grid); x += 120f }
        var y = 60f
        while (y <= EYE_H) { canvas.drawLine(0f, y, EYE_W, y, grid); y += 120f }

        // Квадрат на уровне экрана (шахматная текстура помогает глазам совместить изображения)
        val square = RectF(EYE_W * 0.42f, EYE_H * 0.12f, EYE_W * 0.58f, EYE_H * 0.12f + EYE_W * 0.16f)
        drawChecker(canvas, square, 0xFF1E88E5.toInt(), 0xFF90CAF9.toInt())

        // Ряд кругов: слева за экраном, справа перед экраном
        val parallaxes = listOf(12f, 6f, 0f, -8f, -16f)
        val colors = listOf(0xFF43A047, 0xFF00ACC1, 0xFFFDD835, 0xFFFB8C00, 0xFFE53935).map { it.toInt() }
        parallaxes.forEachIndexed { i, p ->
            val cx = EYE_W * (0.14f + 0.18f * i) + shift(p)
            val cy = EYE_H * 0.72f
            val r = 80f + 12f * i
            canvas.drawCircle(cx, cy, r, paint(colors[i]))
            canvas.drawCircle(cx, cy, r, paint(Color.WHITE, 6f))
            canvas.drawCircle(cx, cy, r * 0.35f, paint(Color.WHITE))
        }
    }

    private fun drawChecker(canvas: Canvas, rect: RectF, dark: Int, light: Int) {
        val cells = 6
        val w = rect.width() / cells
        val h = rect.height() / cells
        for (i in 0 until cells) for (j in 0 until cells) {
            val c = if ((i + j) % 2 == 0) dark else light
            canvas.drawRect(rect.left + i * w, rect.top + j * h, rect.left + (i + 1) * w, rect.top + (j + 1) * h, paint(c))
        }
        canvas.drawRect(rect, paint(Color.WHITE, 6f))
    }

    /**
     * Измерение утечки светофильтра (показывается без анаглиф-обработки): в каждом прямоугольнике
     * дополнительный цвет на полной яркости и свой цвет фильтра с регулируемой яркостью.
     * Через фильтр обе половины одного оттенка; при равной яркости граница между ними исчезает.
     * В нижнем прямоугольнике половины поменяны местами, чтобы не мешало положение на экране.
     */
    private fun drawMatch(canvas: Canvas, own: Set<Int>, ownLevel: Float) {
        fun color(primaries: Set<Int>, level: Float): Int {
            val v = (level.coerceIn(0f, 1f) * 255).toInt()
            return Color.rgb(if (0 in primaries) v else 0, if (1 in primaries) v else 0, if (2 in primaries) v else 0)
        }
        val foreign = (0..2).filter { it !in own }.toSet()
        val complement = paint(color(foreign, 1f))
        val ownPaint = paint(color(own, ownLevel))

        val w = EYE_W * 0.6f
        val h = EYE_H * 0.3f
        val left = (EYE_W - w) / 2
        listOf(EYE_H * 0.14f to true, EYE_H * 0.56f to false).forEach { (top, complementFirst) ->
            val half = RectF(left, top, left + w / 2, top + h)
            val other = RectF(left + w / 2, top, left + w, top + h)
            canvas.drawRect(half, if (complementFirst) complement else ownPaint)
            canvas.drawRect(other, if (complementFirst) ownPaint else complement)
        }
    }
}
