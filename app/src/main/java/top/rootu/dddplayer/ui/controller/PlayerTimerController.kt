package top.rootu.dddplayer.ui.controller

import android.text.format.DateFormat
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Контроллер таймеров плеера на Coroutines.
 */
class PlayerTimerController(
    private val scope: CoroutineScope,
    private val onHideControls: () -> Unit,
    private val onHideSettings: () -> Unit,
    private val clockViews: List<TextView>
) {
    private var controlsTimerJob: Job? = null
    private var settingsTimerJob: Job? = null
    private var clockJob: Job? = null

    private var lastTimeStr: String = ""

    fun startClock() {
        clockJob?.cancel()
        clockJob = scope.launch {
            while (isActive) {
                // Формат 12/24 ч берем из системных настроек, как это делал TextClock
                val context = clockViews.firstOrNull()?.context
                lastTimeStr = if (context != null) {
                    DateFormat.getTimeFormat(context).format(Date())
                } else {
                    val calendar = Calendar.getInstance()
                    String.format(
                        Locale.getDefault(), "%02d:%02d",
                        calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
                    )
                }
                updateClockViews()
                delay(10000) // Обновляем каждые 10 секунд
            }
        }
    }

    private fun updateClockViews() {
        if (lastTimeStr.isEmpty()) return
        clockViews.forEach { it.text = lastTimeStr }
    }

    fun resetControlsTimer() {
        controlsTimerJob?.cancel()
        controlsTimerJob = scope.launch {
            delay(5000) // 5 sec auto-hide
            onHideControls()
        }
    }

    fun stopControlsTimer() {
        controlsTimerJob?.cancel()
    }

    fun resetSettingsTimer() {
        settingsTimerJob?.cancel()
        settingsTimerJob = scope.launch {
            delay(60000) // 60 sec auto-hide
            onHideSettings()
        }
    }

    fun stopSettingsTimer() {
        settingsTimerJob?.cancel()
    }

    fun cleanup() {
        controlsTimerJob?.cancel()
        settingsTimerJob?.cancel()
        clockJob?.cancel()
    }
}