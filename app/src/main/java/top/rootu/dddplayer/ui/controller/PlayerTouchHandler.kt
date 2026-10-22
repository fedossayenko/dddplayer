package top.rootu.dddplayer.ui.controller

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs

class PlayerTouchHandler(
    private val context: Context,
    private val screenWidth: Int,
    private val onSingleTap: () -> Unit,
    private val onDoubleTapSeek: (forward: Boolean) -> Unit,
    private val onVolumeChange: (deltaPercent: Float) -> Unit,
    private val onBrightnessChange: (deltaPercent: Float) -> Unit,
    private val onHorizontalScroll: (deltaX: Float) -> Unit,
    private val onGestureEnd: () -> Unit,
    // Жесты громкости и яркости (вертикальные свайпы) включены в настройках
    private val isVerticalGesturesEnabled: () -> Boolean = { true }
) : GestureDetector.SimpleOnGestureListener() {

    // IGNORED - жест распознан, но ничего не делает (выключенные жесты громкости/яркости)
    private enum class ScrollMode { NONE, VOLUME, BRIGHTNESS, HORIZONTAL, IGNORED }
    private var scrollMode = ScrollMode.NONE

    // В текущем касании был второй палец (масштабирование): тапы и свайпы одним пальцем не обрабатываем
    private var multiTouch = false

    /** Идет свайп одним пальцем (громкость, яркость или горизонтальный). */
    val isScrolling: Boolean
        get() = scrollMode != ScrollMode.NONE && scrollMode != ScrollMode.IGNORED

    fun onMultiTouch() {
        multiTouch = true
    }

    // Порог срабатывания (в пикселях).
    // Должен быть достаточно большим, чтобы отличить скролл от тапа,
    // но достаточно маленьким, чтобы интерфейс был отзывчивым.
    private val scrollThreshold = 30f

    // Полоса у левого и правого края: там системный жест "Назад", горизонтальный свайп оттуда не начинаем
    private val edgeExclusionPx = EDGE_EXCLUSION_DP * context.resources.displayMetrics.density

    override fun onDown(e: MotionEvent): Boolean {
        scrollMode = ScrollMode.NONE
        multiTouch = false
        return true
    }

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
        if (multiTouch) return true
        onSingleTap()
        return true
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        if (multiTouch) return true
        val isForward = e.x > screenWidth / 2
        onDoubleTapSeek(isForward)
        return true
    }

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean {
        if (e1 == null) return false
        if (multiTouch && scrollMode == ScrollMode.NONE) return true

        // 1. Определяем режим, если он еще не выбран
        if (scrollMode == ScrollMode.NONE) {
            // Считаем ОБЩЕЕ смещение от точки нажатия
            val totalDx = e2.x - e1.x
            val totalDy = e2.y - e1.y

            // Проверяем, превышен ли порог по любой из осей
            if (abs(totalDx) > scrollThreshold || abs(totalDy) > scrollThreshold) {

                // Определяем доминирующее направление
                scrollMode = if (abs(totalDx) > abs(totalDy)) {
                    // Горизонтальный свайп, кроме начатого от края экрана (системный "Назад")
                    if (isNearSideEdge(e1.x)) ScrollMode.IGNORED else ScrollMode.HORIZONTAL
                } else {
                    // Вертикальный свайп
                    // Определяем сторону экрана (Лево/Право) по точке НАЧАЛА жеста (e1)
                    if (!isVerticalGesturesEnabled()) {
                        ScrollMode.IGNORED
                    } else if (e1.x > screenWidth / 2) {
                        ScrollMode.VOLUME
                    } else {
                        ScrollMode.BRIGHTNESS
                    }
                }
            }
        }

        // 2. Выполняем действие в зависимости от выбранного режима
        val screenHeight = context.resources.displayMetrics.heightPixels

        // distanceY > 0 при свайпе вверх (GestureDetector инвертирует Y для скролла)
        // Нам нужно: вверх -> плюс, вниз -> минус.
        val deltaYPercent = (distanceY / screenHeight) * 1.5f

        when (scrollMode) {
            ScrollMode.VOLUME -> onVolumeChange(deltaYPercent)
            ScrollMode.BRIGHTNESS -> onBrightnessChange(deltaYPercent)
            ScrollMode.HORIZONTAL -> {
                // distanceX > 0 когда палец движется влево.
                // Нам удобнее: палец влево -> минус, палец вправо -> плюс.
                onHorizontalScroll(-distanceX)
            }
            else -> {
                // Режим еще не определен (палец сдвинулся меньше чем на threshold)
                // Ничего не делаем, ждем следующего события
            }
        }

        return true
    }

    private fun isNearSideEdge(x: Float): Boolean {
        val width = context.resources.displayMetrics.widthPixels
        return x < edgeExclusionPx || x > width - edgeExclusionPx
    }

    fun onActionUp() {
        if (isScrolling) onGestureEnd()
        scrollMode = ScrollMode.NONE
    }

    companion object {
        // Ширина зоны системного жеста "Назад" (в Android по умолчанию 24dp) с запасом
        private const val EDGE_EXCLUSION_DP = 32f
    }
}
