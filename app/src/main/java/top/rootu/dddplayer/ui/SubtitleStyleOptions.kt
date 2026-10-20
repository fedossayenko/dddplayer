package top.rootu.dddplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.model.SubtitleStyle

/**
 * Варианты настроек стиля субтитров и применение стиля к SubtitleView.
 */
object SubtitleStyleOptions {

    /** Вариант значения: само значение и его название. */
    class Choice<T>(val value: T, val label: (Context) -> String)

    private fun res(id: Int): (Context) -> String = { it.getString(id) }

    // Индекс в этом списке хранится в SubtitleStyle.font
    val FONTS = listOf(
        Choice<Typeface?>(null, res(R.string.sub_font_default)),
        Choice<Typeface?>(Typeface.SANS_SERIF, res(R.string.sub_font_sans)),
        Choice<Typeface?>(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), res(R.string.sub_font_sans_bold)),
        Choice<Typeface?>(Typeface.create("sans-serif-condensed", Typeface.NORMAL), res(R.string.sub_font_condensed)),
        Choice<Typeface?>(Typeface.SERIF, res(R.string.sub_font_serif)),
        Choice<Typeface?>(Typeface.MONOSPACE, res(R.string.sub_font_mono))
    )

    val SIZES = listOf(50, 60, 70, 80, 90, 100, 110, 120, 130, 150, 175, 200)
        .map { percent -> Choice(percent) { _: Context -> "$percent%" } }

    val TEXT_COLORS = listOf(
        Choice(Color.WHITE, res(R.string.color_white)),
        Choice(0xFFFFEB3B.toInt(), res(R.string.color_yellow)),
        Choice(0xFF76FF03.toInt(), res(R.string.color_green)),
        Choice(0xFF18FFFF.toInt(), res(R.string.color_cyan)),
        Choice(0xFFFF5252.toInt(), res(R.string.color_red)),
        Choice(0xFFBDBDBD.toInt(), res(R.string.color_gray)),
        Choice(Color.BLACK, res(R.string.color_black))
    )

    val BACKGROUNDS = listOf(
        Choice(Color.TRANSPARENT, res(R.string.sub_bg_none)),
        Choice(0x40000000, { it.getString(R.string.sub_bg_black, 25) }),
        Choice(0x80000000.toInt(), { it.getString(R.string.sub_bg_black, 50) }),
        Choice(0xC0000000.toInt(), { it.getString(R.string.sub_bg_black, 75) }),
        Choice(0xFF000000.toInt(), { it.getString(R.string.sub_bg_black, 100) })
    )

    val EDGE_TYPES = listOf(
        Choice(CaptionStyleCompat.EDGE_TYPE_NONE, res(R.string.sub_edge_none)),
        Choice(CaptionStyleCompat.EDGE_TYPE_OUTLINE, res(R.string.sub_edge_outline)),
        Choice(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, res(R.string.sub_edge_shadow)),
        Choice(CaptionStyleCompat.EDGE_TYPE_RAISED, res(R.string.sub_edge_raised)),
        Choice(CaptionStyleCompat.EDGE_TYPE_DEPRESSED, res(R.string.sub_edge_depressed))
    )

    val EDGE_COLORS = listOf(
        Choice(Color.BLACK, res(R.string.color_black)),
        Choice(0xFF424242.toInt(), res(R.string.color_dark_gray)),
        Choice(Color.WHITE, res(R.string.color_white))
    )

    val BOTTOM_OFFSETS = listOf(0, 2, 5, 8, 10, 15, 20, 25)
        .map { percent -> Choice(percent) { _: Context -> "$percent%" } }

    val EMBEDDED = listOf(
        Choice(true, res(R.string.sub_embedded_on)),
        Choice(false, res(R.string.sub_embedded_off))
    )

    /** Индекс значения в списке вариантов (или ближайший по умолчанию - первый). */
    fun <T> indexOf(choices: List<Choice<T>>, value: T): Int =
        choices.indexOfFirst { it.value == value }.coerceAtLeast(0)

    // edgeType хранится как Int, значения - из EDGE_TYPES (CaptionStyleCompat.EDGE_TYPE_*)
    @SuppressLint("WrongConstant")
    fun apply(view: SubtitleView, style: SubtitleStyle) {
        val typeface = FONTS.getOrNull(style.font)?.value
        view.setStyle(
            CaptionStyleCompat(
                style.textColor,
                style.backgroundColor,
                Color.TRANSPARENT,
                style.edgeType,
                style.edgeColor,
                typeface
            )
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.sizePercent / 100f)
        view.setBottomPaddingFraction(style.bottomPercent / 100f)
        view.setApplyEmbeddedStyles(style.applyEmbedded)
        view.setApplyEmbeddedFontSizes(style.applyEmbedded)
    }
}
