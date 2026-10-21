package top.rootu.dddplayer.logic

import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode

/**
 * Названия стереоформатов: SBS/HSBS (горизонтальная полная/анаморфная), OU/HOU (вертикальная),
 * Interlaced, 3D(z)Tiled. Те же сокращения используются в меню и в документации.
 */
object StereoFormatLogic {

    /**
     * Анаморфная стереопара (Half SBS/OU): каждый глаз сжат вдвое и при выводе растягивается на весь кадр.
     * SBS: половина кадра почти квадратная или уже (1920x1080 -> 960x1080) - Half, иначе Full (3840x1080).
     * OU: половина кадра очень широкая (1920x1080 -> 1920x540) - Half, иначе Full (1920x2160).
     * Пока размер кадра неизвестен, стереопара считается анаморфной (самый частый случай).
     */
    fun isHalfFrame(type: StereoInputType, width: Float, height: Float): Boolean {
        if (width <= 0f || height <= 0f) return true
        return when (type) {
            StereoInputType.SIDE_BY_SIDE -> (width / 2) / height < 1.2f
            StereoInputType.TOP_BOTTOM -> width / (height / 2) > 2.5f
            else -> false
        }
    }

    /** Сокращение формата; null для 2D */
    fun formatCode(type: StereoInputType, width: Float, height: Float): String? = when (type) {
        StereoInputType.NONE -> null
        StereoInputType.SIDE_BY_SIDE -> if (isHalfFrame(type, width, height)) "HSBS" else "SBS"
        StereoInputType.TOP_BOTTOM -> if (isHalfFrame(type, width, height)) "HOU" else "OU"
        StereoInputType.INTERLACED -> "Interlaced"
        StereoInputType.TILED_1080P -> "3D(z)Tiled"
    }

    /**
     * Бейдж инфопанели: "3D (HSBS)", "3D (OU) RL" при смене глаз, "2D (OU) L" при выводе одного глаза.
     * В 2D буква - глаз исходника, который реально на экране (смена глаз меняет его на другой).
     * null - видео не стерео, бейдж скрывается.
     */
    fun badgeLabel(
        type: StereoInputType,
        width: Float,
        height: Float,
        output: StereoOutputMode?,
        swapEyes: Boolean
    ): String? {
        val code = formatCode(type, width, height) ?: return null
        return when (output) {
            StereoOutputMode.LEFT_ONLY, StereoOutputMode.RIGHT_ONLY -> {
                val leftShown = (output == StereoOutputMode.LEFT_ONLY) != swapEyes
                "2D ($code) ${if (leftShown) "L" else "R"}"
            }
            else -> "3D ($code)" + if (swapEyes) " RL" else ""
        }
    }
}
