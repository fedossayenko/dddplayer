package top.rootu.dddplayer.ui

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import top.rootu.dddplayer.R
import top.rootu.dddplayer.utils.DeviceInfoCollector
import kotlin.system.exitProcess

class CrashActivity : AppCompatActivity() {

    private var errorDialog: Dialog? = null
    private var errorDetails: String = ""
    private var uploadedLogUrl: String? = null

    // Отчет об устройстве (экран, звук, декодеры) собирается здесь, в процессе :crash, а не в упавшем
    private lateinit var fullReport: Deferred<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        errorDetails = intent.getStringExtra("errorDetails") ?: "No details available"
        fullReport = lifecycleScope.async(Dispatchers.Default) {
            val diagnostics = runCatching { DeviceInfoCollector.toText(DeviceInfoCollector.collect(this@CrashActivity)) }
                .getOrElse { "Diagnostics failed: ${it.message}" }
            "$errorDetails\n\n--- Diagnostics ---\n$diagnostics"
        }

        findViewById<Button>(R.id.btRestartApp).setOnClickListener {
            restartApp()
        }

        findViewById<Button>(R.id.btShowErrorLogs).setOnClickListener {
            showErrorLogDialog()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                exitProcess(0)
            }
        })
    }

    private fun restartApp() {
        val packageManager = packageManager
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val componentName = intent?.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun showErrorLogDialog() {
        errorDialog?.dismiss()

        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_error_log)
        val view = LogReportView(
            activity = this,
            root = dialog.window!!.decorView,
            title = getString(R.string.error_logs),
            textProvider = { fullReport.await() },
            onClose = { dialog.dismiss() }
        )
        view.onUploaded = { uploadedLogUrl = it }
        // Сначала сама ошибка, отчет об устройстве дописывается, когда соберется
        view.setText(errorDetails)
        lifecycleScope.launch { view.setText(fullReport.await()) }
        // Если уже загружали - показываем результат сразу
        uploadedLogUrl?.let { view.showUploaded(it) }

        errorDialog = dialog
        dialog.show()
        view.focusDefault()
    }

    override fun onDestroy() {
        super.onDestroy()
        errorDialog?.dismiss()
        errorDialog = null
    }
}
