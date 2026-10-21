package top.rootu.dddplayer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.DeviceInfoCollector

/**
 * Диагностика устройства: экран и HDR, аудиовыходы и проброс, видео- и аудиодекодеры.
 * Отчет можно скопировать, чтобы приложить к сообщению об ошибке.
 */
class DeviceInfoActivity : AppCompatActivity() {

    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)

        val content = findViewById<LinearLayout>(R.id.diag_content)
        val progress = findViewById<ProgressBar>(R.id.diag_progress)
        val scroll = findViewById<ScrollView>(R.id.diag_scroll)
        val copy = findViewById<View>(R.id.btn_diag_copy)
        findViewById<View>(R.id.btn_diag_close).setOnClickListener { finish() }
        copy.setOnClickListener { copyReport() }
        copy.isEnabled = false

        // Список декодеров и звуковые возможности читаются медленно - не в главном потоке
        lifecycleScope.launch {
            val sections = withContext(Dispatchers.Default) { DeviceInfoCollector.collect(this@DeviceInfoActivity) }
            report = DeviceInfoCollector.toText(sections)
            sections.forEach { section ->
                content.addView(sectionTitle(section.title))
                content.addView(sectionBody(section.lines.joinToString("\n")))
            }
            progress.isVisible = false
            copy.isEnabled = true
            scroll.requestFocus()
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.tv_white))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun sectionBody(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setLineSpacing(dp(3).toFloat(), 1f)
        setTextIsSelectable(false)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun copyReport() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.pref_diagnostics), report))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }
}
