package top.rootu.dddplayer.model

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Доступные режимы масштабирования видео.
 */
enum class ResizeMode {
    FIT,            // Вписать (по умолчанию, RESIZE_MODE_FIT)
    ZOOM,           // Заполнить (Обрезать, RESIZE_MODE_ZOOM)
    SCALE,          // Пользовательское увеличение (Ручное масштабирование поверх FIT)
    FILL            // Растянуть (Игнорирует пропорции, RESIZE_MODE_FILL)
}

/**
 * Соотношение сторон кадра. AUTO - как в видео, остальные - принудительно (для 2D).
 */
enum class AspectRatioMode(val ratio: Float?, val label: String?) {
    AUTO(null, null),
    R4_3(4f / 3f, "4:3"),
    R16_9(16f / 9f, "16:9"),
    R1_85(1.85f, "1.85:1"),
    R2_35(2.35f, "2.35:1"),
    R2_39(2.39f, "2.39:1")
}

/**
 * Доступные скорости воспроизведения.
 */
enum class PlaybackSpeed(val value: Float, val label: String) {
    X0_50(0.50f, "0.50x"),
    X0_75(0.75f, "0.75x"),
    X1_00(1.00f, "1.00x"),
    X1_25(1.25f, "1.25x"),
    X1_50(1.50f, "1.50x"),
    X1_75(1.75f, "1.75x"),
    X2_00(2.00f, "2.00x"),
    X2_25(2.25f, "2.25x"),
    X2_50(2.50f, "2.50x"),
    X2_75(2.75f, "2.75x"),
    X3_00(3.00f, "3.00x");

    companion object {
        // Своя скорость: диапазон и шаг ползунка
        const val CUSTOM_MIN = 0.25f
        const val CUSTOM_MAX = 4.00f
        const val CUSTOM_STEP = 0.05f

        /** Предустановка с этой скоростью или null (своя скорость). */
        fun preset(value: Float): PlaybackSpeed? = entries.firstOrNull { abs(it.value - value) < 0.001f }

        /** Подпись скорости: "1.25x". */
        fun label(value: Float): String = String.format(Locale.US, "%.2fx", value)

        /** Скорость в пределах ползунка, округленная до шага. */
        fun normalize(value: Float): Float =
            ((value / CUSTOM_STEP).roundToInt() * CUSTOM_STEP).coerceIn(CUSTOM_MIN, CUSTOM_MAX)
    }
}