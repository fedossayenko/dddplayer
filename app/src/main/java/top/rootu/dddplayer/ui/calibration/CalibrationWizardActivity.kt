package top.rootu.dddplayer.ui.calibration

import android.app.ActivityManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import top.rootu.dddplayer.R
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.logic.AnaglyphLogic
import top.rootu.dddplayer.logic.SettingsMutator
import top.rootu.dddplayer.model.AnaglyphCalibration
import top.rootu.dddplayer.model.StereoInputType
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.renderer.OnSurfaceReadyListener
import top.rootu.dddplayer.renderer.StereoGLSurfaceView
import top.rootu.dddplayer.renderer.StereoRenderer
import top.rootu.dddplayer.ui.calibration.CalibrationPatterns.Pattern
import top.rootu.dddplayer.utils.DuboisMath
import top.rootu.dddplayer.viewmodel.GlassesGroup
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Визард калибровки Cardboard VR или анаглиф-очков.
 *
 * Тестовые картинки рисуются в кадр Side-by-Side и проходят через StereoRenderer, как видео.
 * Каждый шаг начинается с инструкции (в VR - для каждого глаза, со сдвигом на разнос экранов).
 * Инструкция: кнопки Назад / Настроить (или Просмотр) / Далее. Режим настройки или просмотра:
 * Влево/Вправо меняют значение, Вверх/Вниз выбирают параметр, OK или Назад возвращают инструкцию.
 * Касание: края панели - изменить значение, центр - инструкция, свайп вверх/вниз - другой параметр.
 */
class CalibrationWizardActivity : AppCompatActivity(), OnSurfaceReadyListener {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_VR = "vr"
        const val MODE_ANAGLYPH = "anaglyph"
        private const val TAG = "CalibrationWizard"

        // Глубина сцены проверки в VR: у каждого глаза свой экран, параллакс можно больше, чем в анаглифе
        private const val VR_DEPTH_SCALE = 1.5f
    }

    /** Настраиваемый параметр шага: название, текущее значение и изменение на шаг. */
    private class Param(val label: () -> String, val value: () -> String, val change: (Int) -> Unit)

    /**
     * @param filter для MATCH: измеряемый светофильтр (0 - первого цвета группы, 1 - второго)
     */
    private class Step(
        val titleRes: Int,
        val text: () -> String,
        val pattern: Pattern,
        val params: List<Param> = emptyList(),
        val filter: Int? = null,
        val isAvailable: () -> Boolean = { true }
    )

    /** Панель для одного глаза (в VR - половина экрана, обрезает содержимое по своей границе). */
    private class Panel(val root: View) {
        val card: View = root.findViewById(R.id.wizard_card)
        val step: TextView = root.findViewById(R.id.wizard_step)
        val title: TextView = root.findViewById(R.id.wizard_title)
        val text: TextView = root.findViewById(R.id.wizard_text)
        val controls: TextView = root.findViewById(R.id.wizard_controls)
        val back: Button = root.findViewById(R.id.btn_wizard_back)
        val adjust: Button = root.findViewById(R.id.btn_wizard_adjust)
        val next: Button = root.findViewById(R.id.btn_wizard_next)
        val value: TextView = root.findViewById(R.id.wizard_value)
    }

    /**
     * Очки анаглиф: группа цветов и сторона светофильтра первого цвета группы (A).
     * filterALeft = true: например, для Red-Cyan красный фильтр слева, голубой справа.
     */
    private class Glasses(val group: GlassesGroup, val filterALeft: Boolean)

    private lateinit var repository: SettingsRepository
    private lateinit var glView: StereoGLSurfaceView
    private lateinit var renderer: StereoRenderer
    private lateinit var overlay: LinearLayout
    @Volatile
    private var inputSurface: Surface? = null

    private val isVr by lazy { intent.getStringExtra(EXTRA_MODE) == MODE_VR }
    private val panels = mutableListOf<Panel>()
    private lateinit var steps: List<Step>
    private var stepIndex = 0
    private var paramIndex = 0
    private var cardVisible = true

    // Параметры VR
    private var separation = 0f
    private var k1 = 0.34f
    private var k2 = 0.10f
    private var scale = 1.2f

    // Параметры анаглифа: A и B - светофильтры первого и второго цвета группы (например, красный и голубой).
    // Утечка измеряется яркостью своего цвета фильтра (%, значение sRGB), при которой он выглядит
    // через фильтр так же, как полный дополнительный цвет
    private val glassesOptions = GlassesGroup.entries.flatMap { listOf(Glasses(it, true), Glasses(it, false)) }
    private var glassesIndex = 0
    private var levelA = 0
    private var levelB = 0

    private val glasses get() = glassesOptions[glassesIndex]

    private fun levelToLeak(level: Int) = DuboisMath.srgbToLinear(level / 100f)
    private fun leakToLevel(leak: Float) = (DuboisMath.linearToSrgb(leak) * 100).roundToInt().coerceIn(0, 100)
    private fun calibration() = AnaglyphCalibration(levelToLeak(levelA), levelToLeak(levelB))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration_wizard)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()

        repository = SettingsRepository.getInstance(this)
        if (isVr) loadVrParams() else loadAnaglyphParams()
        steps = if (isVr) buildVrSteps() else buildAnaglyphSteps()

        setupGl()
        setupPanels()
        setupTouch()

        onBackPressedDispatcher.addCallback(this) {
            if (!cardVisible) showCard() else previousStep()
        }

        showStep(0)
    }

    // ==================== НАСТРОЙКА ВЫВОДА ====================

    private fun setupGl() {
        glView = findViewById(R.id.wizard_gl)
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val supportsEs3 = activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        val preferEs3 = repository.getRenderApiMode() != SettingsRepository.RENDER_API_GLES2
        val contextIsEs3 = supportsEs3 && preferEs3
        glView.setEGLContextClientVersion(if (contextIsEs3) 3 else 2)
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        renderer = StereoRenderer(glView, this, contextIsEs3, preferEs3)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        applyRenderer()
    }

    // Вызывается в GL потоке, когда входная поверхность создана (и после пересоздания контекста)
    override fun onSurfaceReady(surface: Surface) {
        renderer.setInputBufferSize(CalibrationPatterns.FRAME_WIDTH, CalibrationPatterns.FRAME_HEIGHT)
        inputSurface = surface
        runOnUiThread { drawPattern() }
    }

    private fun applyRenderer() {
        renderer.setInputType(StereoInputType.SIDE_BY_SIDE)
        renderer.setVideoDimensions(CalibrationPatterns.FRAME_WIDTH, CalibrationPatterns.FRAME_HEIGHT)
        // Full SBS: каждый глаз растягивается на весь кадр 16:9
        renderer.setSingleFrameDimensions(CalibrationPatterns.FRAME_WIDTH.toFloat(), CalibrationPatterns.FRAME_HEIGHT.toFloat())
        renderer.setDepth(0)
        renderer.setSwapEyes(false)
        if (isVr) {
            renderer.setOutputMode(StereoOutputMode.CARDBOARD_VR)
            renderer.setDistortion(k1, k2, scale)
            renderer.setScreenSeparation(separation)
        } else if (steps[stepIndex].pattern == Pattern.MATCH) {
            // Измерение очков: чистые цвета экрана без анаглиф-обработки
            renderer.setOutputMode(StereoOutputMode.LEFT_ONLY)
            renderer.setScreenSeparation(0f)
        } else {
            val group = glasses.group
            val calibrated = AnaglyphLogic.calibratedFilter(group)
            val type = calibrated ?: AnaglyphLogic.defaultFilter(group)
            val matrices = if (calibrated != null) {
                AnaglyphLogic.calculateCalibrated(group, calibration())
            } else {
                AnaglyphLogic.calculateMatrix(type, 0, 0, 0f, 0f, false)
            }
            renderer.setOutputMode(StereoOutputMode.ANAGLYPH)
            renderer.setAnaglyphType(type)
            renderer.setScreenSeparation(0f)
            // Светофильтр пропускает свой цвет: изображение глаза кодируется цветом фильтра перед этим глазом
            if (glasses.filterALeft) {
                renderer.setAnaglyphMatrices(matrices.left, matrices.right)
            } else {
                renderer.setAnaglyphMatrices(matrices.right, matrices.left)
            }
        }
    }

    private fun drawPattern() {
        val surface = inputSurface ?: return
        if (!surface.isValid) return
        val step = steps[stepIndex]
        try {
            val canvas = surface.lockCanvas(null)
            try {
                val primaries = AnaglyphLogic.filterPrimaries(glasses.group)
                CalibrationPatterns.draw(
                    canvas, step.pattern,
                    depthScale = if (isVr) VR_DEPTH_SCALE else 1f,
                    ownPrimaries = if (step.filter == 1) primaries.second else primaries.first,
                    ownLevel = (if (step.filter == 1) levelB else levelA) / 100f
                )
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to draw calibration pattern", e)
        }
    }

    // ==================== ШАГИ ====================

    /** Названия светофильтров группы (A, B) в форме "красный", "голубой". */
    private fun filterNames(group: GlassesGroup): Pair<String, String> {
        val (a, b) = when (group) {
            GlassesGroup.RED_CYAN -> R.string.filter_red to R.string.filter_cyan
            GlassesGroup.YELLOW_BLUE -> R.string.filter_yellow to R.string.filter_blue
            GlassesGroup.GREEN_MAGENTA -> R.string.filter_green to R.string.filter_magenta
            GlassesGroup.RED_BLUE -> R.string.filter_red to R.string.filter_blue
        }
        return getString(a) to getString(b)
    }

    private fun groupName(group: GlassesGroup): String = getString(
        when (group) {
            GlassesGroup.RED_CYAN -> R.string.glasses_rc
            GlassesGroup.YELLOW_BLUE -> R.string.glasses_yb
            GlassesGroup.GREEN_MAGENTA -> R.string.glasses_gm
            GlassesGroup.RED_BLUE -> R.string.glasses_rb
        }
    )

    // Например: "Red - Cyan: красный фильтр слева"
    private fun glassesLabel(option: Glasses): String {
        val filterA = filterNames(option.group).first
        val side = getString(if (option.filterALeft) R.string.wizard_filter_left else R.string.wizard_filter_right, filterA)
        return getString(R.string.wizard_glasses_label, groupName(option.group), side)
    }

    private fun glassesStepText(): String {
        val text = getString(R.string.wizard_ana_glasses_text)
        return if (AnaglyphLogic.isCalibratable(glasses.group)) text else text + "\n\n" + getString(R.string.wizard_ana_rb_note)
    }

    private fun isCalibratable() = AnaglyphLogic.isCalibratable(glasses.group)

    private fun buildVrSteps(): List<Step> = listOf(
        Step(R.string.wizard_vr_intro_title, { getString(R.string.wizard_vr_intro_text) }, Pattern.INTRO),
        Step(
            R.string.wizard_vr_ipd_title, { getString(R.string.wizard_vr_ipd_text) }, Pattern.CROSSHAIR,
            listOf(Param({ getString(R.string.wizard_param_separation) },
                { String.format(Locale.US, "%+.1f%%", separation * 100) },
                { d -> separation = SettingsMutator.modifyFloat(separation, d, 0.005f, -0.15f, 0.15f) }))
        ),
        Step(
            R.string.wizard_vr_distortion_title, { getString(R.string.wizard_vr_distortion_text) }, Pattern.GRID,
            listOf(
                Param({ "K1" }, { String.format(Locale.US, "%.2f", k1) },
                    { d -> k1 = SettingsMutator.modifyFloat(k1, d, 0.02f, 0f, 2f) }),
                Param({ "K2" }, { String.format(Locale.US, "%.2f", k2) },
                    { d -> k2 = SettingsMutator.modifyFloat(k2, d, 0.01f, 0f, 1f) })
            )
        ),
        Step(
            R.string.wizard_vr_scale_title, { getString(R.string.wizard_vr_scale_text) }, Pattern.FRAME,
            listOf(Param({ getString(R.string.wizard_param_scale) }, { String.format(Locale.US, "%.2f", scale) },
                { d -> scale = SettingsMutator.modifyFloat(scale, d, 0.05f, 0.5f, 3f) }))
        ),
        Step(R.string.wizard_vr_check_title, { getString(R.string.wizard_vr_check_text) }, Pattern.DEPTH)
    )

    private fun buildAnaglyphSteps(): List<Step> {
        val glassesParam = Param({ getString(R.string.wizard_param_glasses) }, { glassesLabel(glasses) }, { d ->
            val group = glasses.group
            glassesIndex = (glassesIndex + d + glassesOptions.size) % glassesOptions.size
            if (glasses.group != group) loadCalibration()
        })
        return listOf(
            Step(R.string.wizard_ana_glasses_title, { glassesStepText() }, Pattern.DEPTH, listOf(glassesParam)),
            // Светофильтр A: яркость его цвета, при которой он выглядит как полный дополнительный цвет
            Step(
                R.string.wizard_ana_filter_title,
                { filterNames(glasses.group).let { (a, b) -> getString(R.string.wizard_ana_match_text, a, b) } },
                Pattern.MATCH,
                listOf(Param({ getString(R.string.wizard_param_level) }, { "$levelA%" },
                    { d -> levelA = SettingsMutator.modifyInt(levelA, d, 1, 0, 100) })),
                filter = 0,
                isAvailable = { isCalibratable() }
            ),
            Step(
                R.string.wizard_ana_filter_title,
                { filterNames(glasses.group).let { (a, b) -> getString(R.string.wizard_ana_match_text, b, a) } },
                Pattern.MATCH,
                listOf(Param({ getString(R.string.wizard_param_level) }, { "$levelB%" },
                    { d -> levelB = SettingsMutator.modifyInt(levelB, d, 1, 0, 100) })),
                filter = 1,
                isAvailable = { isCalibratable() }
            ),
            Step(
                R.string.wizard_ana_check_title, { getString(R.string.wizard_ana_check_text) }, Pattern.DEPTH,
                listOf(Param({ getString(R.string.wizard_param_glasses) }, { glassesLabel(glasses) }, {
                    // Поменять стороны светофильтров в той же группе очков
                    glassesIndex = glassesOptions.indexOfFirst { it.group == glasses.group && it.filterALeft != glasses.filterALeft }
                }))
            )
        )
    }

    private fun availableSteps() = steps.indices.filter { steps[it].isAvailable() }

    private fun showStep(index: Int) {
        stepIndex = index
        paramIndex = 0
        showCard()
        applyRenderer()
        drawPattern()
    }

    private fun nextStep() {
        val next = availableSteps().firstOrNull { it > stepIndex }
        if (next == null) save() else showStep(next)
    }

    private fun previousStep() {
        val previous = availableSteps().lastOrNull { it < stepIndex }
        if (previous == null) finish() else showStep(previous)
    }

    private fun changeValue(direction: Int) {
        val param = steps[stepIndex].params.getOrNull(paramIndex) ?: return
        param.change(direction)
        applyRenderer()
        drawPattern()
        updatePanels()
    }

    private fun selectParam(direction: Int) {
        val count = steps[stepIndex].params.size
        if (count < 2) return
        paramIndex = (paramIndex + direction + count) % count
        updatePanels()
    }

    // ==================== ПАНЕЛИ ====================

    private fun setupPanels() {
        overlay = findViewById(R.id.wizard_overlay)
        // В VR инструкция дублируется для каждого глаза
        repeat(if (isVr) 2 else 1) { i ->
            val view = layoutInflater.inflate(R.layout.item_wizard_panel, overlay, false)
            overlay.addView(view)
            val panel = Panel(view)
            panels.add(panel)
            panel.back.setOnClickListener { previousStep() }
            panel.adjust.setOnClickListener { hideCard() }
            panel.next.setOnClickListener { nextStep() }
            if (i > 0) {
                // Кнопки второй панели только для касания; фокус показывается копией выделения первой
                listOf(panel.back, panel.adjust, panel.next).forEach { it.isFocusable = false }
            }
        }
        val main = panels[0]
        val mirrors = panels.drop(1)
        listOf(
            main.back to { p: Panel -> p.back },
            main.adjust to { p: Panel -> p.adjust },
            main.next to { p: Panel -> p.next }
        ).forEach { (button, counterpart) ->
            button.setOnFocusChangeListener { _, hasFocus -> mirrors.forEach { counterpart(it).isSelected = hasFocus } }
        }
        // Сдвиг на разнос экранов зависит от ширины экрана - пересчитываем после разметки
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyEyeShift() }
    }

    /**
     * VR: панели каждого глаза сдвигаются вместе с центром линзы (как изображение в шейдере)
     * и обрезаются по своей половине экрана, не залезая на соседний глаз.
     */
    private fun applyEyeShift() {
        if (!isVr) return
        val shiftPx = separation * overlay.width
        panels.forEachIndexed { i, panel ->
            val shift = if (i == 0) -shiftPx else shiftPx
            panel.card.translationX = shift
            panel.value.translationX = shift
        }
    }

    private fun updatePanels() {
        val step = steps[stepIndex]
        val available = availableSteps()
        val position = available.indexOf(stepIndex) + 1
        val isFirst = position == 1
        val isLast = position == available.size
        val hasParams = step.params.isNotEmpty()

        val controls = buildString {
            append(getString(if (hasParams) R.string.wizard_controls_adjust else R.string.wizard_controls_view))
            if (step.params.size > 1) append('\n').append(getString(R.string.wizard_controls_params))
            append('\n').append(getString(if (hasParams) R.string.wizard_controls_touch else R.string.wizard_controls_touch_view))
        }
        val param = step.params.getOrNull(paramIndex)
        val valueText = param?.let {
            val counter = if (step.params.size > 1) "  (${paramIndex + 1}/${step.params.size} ▲▼)" else ""
            "◀  ${it.label()}: ${it.value()}  ▶$counter"
        }

        panels.forEach { panel ->
            panel.step.text = getString(R.string.wizard_step, position, available.size)
            panel.title.text = if (step.filter != null) {
                // Шаг светофильтра: в заголовке его цвет
                val (a, b) = filterNames(glasses.group)
                getString(R.string.wizard_ana_filter_title_format, if (step.filter == 0) a else b)
            } else {
                getString(step.titleRes)
            }
            panel.text.text = step.text()
            panel.controls.text = controls
            panel.back.setText(if (isFirst) R.string.wizard_cancel else R.string.wizard_back)
            // Шаг без параметров: кнопка просмотра результата без инструкции
            panel.adjust.setText(if (hasParams) R.string.wizard_adjust else R.string.wizard_view)
            panel.next.setText(if (isLast) R.string.wizard_save else R.string.wizard_next)
            panel.card.isVisible = cardVisible
            panel.value.isVisible = !cardVisible && valueText != null
            panel.value.text = valueText
        }
        applyEyeShift()
    }

    private fun showCard() {
        cardVisible = true
        updatePanels()
        val target = panels[0].adjust
        target.post { target.requestFocus() }
    }

    private fun hideCard() {
        cardVisible = false
        updatePanels()
    }

    // ==================== УПРАВЛЕНИЕ ====================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cardVisible && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { changeValue(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { changeValue(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { selectParam(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selectParam(1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    showCard()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupTouch() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (cardVisible) return false
                if (steps[stepIndex].params.isEmpty()) {
                    showCard()
                    return true
                }
                // Зоны касания внутри панели глаза: слева - меньше, справа - больше, центр - инструкция
                val panelWidth = overlay.width / panels.size.toFloat()
                val position = (e.x % panelWidth) / panelWidth
                when {
                    position < 0.33f -> changeValue(-1)
                    position > 0.67f -> changeValue(1)
                    else -> showCard()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (cardVisible || abs(velocityY) < abs(velocityX)) return false
                selectParam(if (velocityY < 0) -1 else 1)
                return true
            }
        })
        overlay.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    // ==================== СОХРАНЕНИЕ ====================

    private fun loadVrParams() {
        separation = repository.getGlobalFloat("global_screen_separation_pct", 0f)
        k1 = repository.getGlobalFloat("vr_k1", 0.34f)
        k2 = repository.getGlobalFloat("vr_k2", 0.10f)
        scale = repository.getGlobalFloat("vr_scale", 1.2f)
    }

    private fun loadAnaglyphParams() {
        val savedType = StereoRenderer.AnaglyphType.entries
            .getOrNull(repository.getGlobalInt("def_anaglyph_type", StereoRenderer.AnaglyphType.RC_DUBOIS.ordinal))
            ?: StereoRenderer.AnaglyphType.RC_DUBOIS
        val group = AnaglyphLogic.getGlassesGroup(savedType)
        // Переставленные линзы: фильтр первого цвета справа
        val filterALeft = !repository.isAnaglyphLensSwapped()
        glassesIndex = glassesOptions.indexOfFirst { it.group == group && it.filterALeft == filterALeft }.coerceAtLeast(0)
        loadCalibration()
    }

    // Сохраненная калибровка выбранной группы очков (или начальные значения группы)
    private fun loadCalibration() {
        val calibration = repository.getAnaglyphCalibration(glasses.group) ?: AnaglyphLogic.defaultCalibration(glasses.group)
        levelA = leakToLevel(calibration.leakA)
        levelB = leakToLevel(calibration.leakB)
    }

    private fun save() {
        if (isVr) {
            repository.putGlobalFloat("global_screen_separation_pct", separation)
            repository.putGlobalFloat("vr_k1", k1)
            repository.putGlobalFloat("vr_k2", k2)
            repository.putGlobalFloat("vr_scale", scale)
        } else {
            // Калибровка - отдельный фильтр "Калибровка" группы очков; встроенные фильтры и Custom не меняются
            val type = AnaglyphLogic.calibratedFilter(glasses.group) ?: AnaglyphLogic.defaultFilter(glasses.group)
            if (isCalibratable()) {
                repository.setAnaglyphCalibration(glasses.group, calibration())
            }
            // Фильтр по умолчанию - для новых видео и для этой группы очков
            repository.putGlobalInt("pref_filter_${glasses.group.name}", type.ordinal)
            repository.putGlobalInt("def_anaglyph_type", type.ordinal)
            repository.setAnaglyphLensSwapped(!glasses.filterALeft)
        }
        repository.bumpCalibrationVersion()
        Toast.makeText(this, R.string.wizard_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    // ==================== ЖИЗНЕННЫЙ ЦИКЛ ====================

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    override fun onPause() {
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        renderer.release()
        super.onDestroy()
    }
}
