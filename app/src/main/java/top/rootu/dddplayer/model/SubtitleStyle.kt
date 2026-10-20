package top.rootu.dddplayer.model

/**
 * Пользовательский стиль субтитров. Значения по умолчанию совпадают со стандартным видом
 * SubtitleView (белый текст на черной подложке), чтобы без настройки ничего не менялось.
 *
 * @param font индекс шрифта (SubtitleStyleOptions.FONTS)
 * @param sizePercent размер относительно стандартного, %
 * @param textColor цвет текста (ARGB)
 * @param backgroundColor цвет подложки под текстом (ARGB, прозрачность задает плотность)
 * @param edgeType тип обводки (CaptionStyleCompat.EDGE_TYPE_*)
 * @param edgeColor цвет обводки/тени (ARGB)
 * @param bottomPercent отступ снизу, % высоты кадра
 * @param applyEmbedded применять стили и размеры из файла субтитров (ASS/SSA, TTML)
 */
data class SubtitleStyle(
    val font: Int = 0,
    val sizePercent: Int = 100,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val backgroundColor: Int = 0xFF000000.toInt(),
    val edgeType: Int = 0,
    val edgeColor: Int = 0xFF000000.toInt(),
    val bottomPercent: Int = 8,
    val applyEmbedded: Boolean = true
)
