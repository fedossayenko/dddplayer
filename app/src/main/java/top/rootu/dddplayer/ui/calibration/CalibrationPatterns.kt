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

    enum class Pattern { INTRO, CROSSHAIR, GRID, FRAME, DEPTH, GHOSTING, COLORS }

    /**
     * @param ghostEye глаз, для которого в GHOSTING рисуются круги (второму - квадраты)
     */
    fun draw(canvas: Canvas, pattern: Pattern, ghostEye: Int = 0) {
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
                Pattern.DEPTH -> drawDepth(canvas, eye)
                Pattern.GHOSTING -> drawGhosting(canvas, eye == ghostEye)
                Pattern.COLORS -> drawColors(canvas)
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

    private fun textPaint(color: Int, size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private fun drawIntro(canvas: Canvas) {
        canvas.drawColor(0xFF101820.toInt())
        canvas.drawCircle(EYE_W / 2, EYE_H / 2, 260f, paint(0xFF2E4A62.toInt()))
        canvas.drawText("3D", EYE_W / 2, EYE_H / 2 + 70f, textPaint(Color.WHITE, 200f))
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
     * Сцена с глубиной: сетка за экраном, круг на уровне экрана, круг перед экраном.
     * Параллакс > 0 - объект за экраном (изображение правого глаза смещено вправо).
     */
    private fun drawDepth(canvas: Canvas, eye: Int) {
        fun shift(parallax: Float) = if (eye == 0) -parallax / 2 else parallax / 2

        canvas.drawColor(0xFF101418.toInt())
        // Сетка за экраном
        val behind = shift(60f)
        val grid = paint(0xFF37474F.toInt(), 4f)
        var x = -120f
        while (x <= EYE_W + 120f) { canvas.drawLine(x + behind, 0f, x + behind, EYE_H, grid); x += 120f }
        var y = 0f
        while (y <= EYE_H) { canvas.drawLine(0f, y, EYE_W, y, grid); y += 120f }

        // Круг на уровне экрана
        canvas.drawCircle(EYE_W * 0.3f, EYE_H * 0.55f, 170f, paint(0xFF1E88E5.toInt()))
        // Круг перед экраном
        val front = shift(-70f)
        canvas.drawCircle(EYE_W * 0.68f + front, EYE_H * 0.5f, 210f, paint(0xFFE53935.toInt()))
        canvas.drawCircle(EYE_W * 0.68f + front, EYE_H * 0.5f, 210f, paint(Color.WHITE, 8f))
    }

    // Тест утечки (ghosting): для одного глаза круги, для другого квадраты
    private fun drawGhosting(canvas: Canvas, circles: Boolean) {
        val fill = paint(Color.WHITE)
        for (i in 0..2) {
            val cx = EYE_W * (0.25f + 0.25f * i)
            if (circles) {
                canvas.drawCircle(cx, EYE_H * 0.32f, 130f, fill)
            } else {
                canvas.drawRect(RectF(cx - 120f, EYE_H * 0.68f - 120f, cx + 120f, EYE_H * 0.68f + 120f), fill)
            }
        }
    }

    // Цвета и градации серого (одинаковые для обоих глаз): оценка баланса и яркости
    private fun drawColors(canvas: Canvas) {
        canvas.drawColor(0xFF202020.toInt())
        val colors = listOf(
            0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFE0AC69
        ).map { it.toInt() }
        val barW = EYE_W / colors.size
        colors.forEachIndexed { i, c -> canvas.drawRect(i * barW, EYE_H * 0.1f, (i + 1) * barW, EYE_H * 0.55f, paint(c)) }
        val steps = 10
        val stepW = EYE_W / steps
        for (i in 0 until steps) {
            val v = (255 * i / (steps - 1))
            canvas.drawRect(i * stepW, EYE_H * 0.62f, (i + 1) * stepW, EYE_H * 0.9f, paint(Color.rgb(v, v, v)))
        }
    }
}
