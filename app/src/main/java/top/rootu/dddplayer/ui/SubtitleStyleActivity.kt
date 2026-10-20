package top.rootu.dddplayer.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.model.SubtitleStyle
import top.rootu.dddplayer.ui.SubtitleStyleOptions.Choice

/**
 * Настройка стиля субтитров с предпросмотром.
 * Пульт: Влево/Вправо меняют значение, OK открывает список значений. Изменения сохраняются сразу.
 */
class SubtitleStyleActivity : AppCompatActivity() {

    /**
     * Настройка стиля: варианты значений, текущий индекс в стиле и новый стиль по индексу.
     * @param cyclic перебор по кругу (цвета, шрифты) или с остановкой на краях (размеры, отступы)
     */
    private class StyleOption(
        val titleRes: Int,
        val choices: List<Choice<*>>,
        val cyclic: Boolean,
        val indexIn: (SubtitleStyle) -> Int,
        val applyTo: (SubtitleStyle, Int) -> SubtitleStyle
    ) {
        lateinit var valueView: TextView
    }

    private lateinit var repository: SettingsRepository
    private lateinit var preview: SubtitleView
    private var style = SubtitleStyle()

    private val options = listOf(
        StyleOption(R.string.sub_opt_font, SubtitleStyleOptions.FONTS, true,
            { it.font.coerceIn(SubtitleStyleOptions.FONTS.indices) },
            { s, i -> s.copy(font = i) }),
        StyleOption(R.string.sub_opt_size, SubtitleStyleOptions.SIZES, false,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.SIZES, it.sizePercent) },
            { s, i -> s.copy(sizePercent = SubtitleStyleOptions.SIZES[i].value) }),
        StyleOption(R.string.sub_opt_color, SubtitleStyleOptions.TEXT_COLORS, true,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.TEXT_COLORS, it.textColor) },
            { s, i -> s.copy(textColor = SubtitleStyleOptions.TEXT_COLORS[i].value) }),
        StyleOption(R.string.sub_opt_background, SubtitleStyleOptions.BACKGROUNDS, true,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.BACKGROUNDS, it.backgroundColor) },
            { s, i -> s.copy(backgroundColor = SubtitleStyleOptions.BACKGROUNDS[i].value) }),
        StyleOption(R.string.sub_opt_edge, SubtitleStyleOptions.EDGE_TYPES, true,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.EDGE_TYPES, it.edgeType) },
            { s, i -> s.copy(edgeType = SubtitleStyleOptions.EDGE_TYPES[i].value) }),
        StyleOption(R.string.sub_opt_edge_color, SubtitleStyleOptions.EDGE_COLORS, true,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.EDGE_COLORS, it.edgeColor) },
            { s, i -> s.copy(edgeColor = SubtitleStyleOptions.EDGE_COLORS[i].value) }),
        StyleOption(R.string.sub_opt_position, SubtitleStyleOptions.BOTTOM_OFFSETS, false,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.BOTTOM_OFFSETS, it.bottomPercent) },
            { s, i -> s.copy(bottomPercent = SubtitleStyleOptions.BOTTOM_OFFSETS[i].value) }),
        StyleOption(R.string.sub_opt_embedded, SubtitleStyleOptions.EMBEDDED, true,
            { SubtitleStyleOptions.indexOf(SubtitleStyleOptions.EMBEDDED, it.applyEmbedded) },
            { s, i -> s.copy(applyEmbedded = SubtitleStyleOptions.EMBEDDED[i].value) })
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subtitle_style)

        repository = SettingsRepository.getInstance(this)
        style = repository.getSubtitleStyle()

        preview = findViewById(R.id.subtitle_preview)
        preview.setCues(listOf(Cue.Builder().setText(getString(R.string.sub_preview_text)).build()))

        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }

        val container = findViewById<LinearLayout>(R.id.subtitle_options)
        options.forEach { option -> container.addView(createOptionRow(container, option)) }
        container.addView(createResetRow(container))

        updateAll()
        container.getChildAt(0).requestFocus()
    }

    private fun createOptionRow(container: LinearLayout, option: StyleOption): View {
        val row = layoutInflater.inflate(R.layout.item_subtitle_option, container, false)
        row.findViewById<TextView>(R.id.option_title).setText(option.titleRes)
        option.valueView = row.findViewById(R.id.option_value)

        row.setOnClickListener { showChoices(option) }
        row.findViewById<ImageView>(R.id.option_prev).setOnClickListener { step(option, -1) }
        row.findViewById<ImageView>(R.id.option_next).setOnClickListener { step(option, 1) }
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { step(option, -1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { step(option, 1); true }
                else -> false
            }
        }
        return row
    }

    private fun createResetRow(container: LinearLayout): View {
        val row = layoutInflater.inflate(R.layout.item_subtitle_option, container, false)
        row.findViewById<TextView>(R.id.option_title).setText(R.string.sub_opt_reset)
        listOf(R.id.option_prev, R.id.option_value, R.id.option_next).forEach { row.findViewById<View>(it).visibility = View.GONE }
        row.setOnClickListener { updateStyle(SubtitleStyle()) }
        return row
    }

    private fun step(option: StyleOption, direction: Int) {
        val size = option.choices.size
        val current = option.indexIn(style)
        val next = if (option.cyclic) (current + direction + size) % size else (current + direction).coerceIn(0, size - 1)
        if (next != current) updateStyle(option.applyTo(style, next))
    }

    private fun showChoices(option: StyleOption) {
        val labels = option.choices.map { it.label(this) }
        ChoiceDialog.show(this, getString(option.titleRes), labels, option.indexIn(style)) { index ->
            updateStyle(option.applyTo(style, index))
        }
    }

    private fun updateStyle(newStyle: SubtitleStyle) {
        style = newStyle
        repository.setSubtitleStyle(style)
        updateAll()
    }

    private fun updateAll() {
        options.forEach { option -> option.valueView.text = option.choices[option.indexIn(style)].label(this) }
        SubtitleStyleOptions.apply(preview, style)
    }
}
