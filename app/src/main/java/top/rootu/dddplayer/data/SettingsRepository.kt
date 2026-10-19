package top.rootu.dddplayer.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.exoplayer.DefaultRenderersFactory
import top.rootu.dddplayer.model.StereoOutputMode
import top.rootu.dddplayer.player.NetworkBufferLoadControl
import top.rootu.dddplayer.renderer.StereoRenderer

class SettingsRepository private constructor(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences =
        context.getSharedPreferences("global_prefs", Context.MODE_PRIVATE)

    companion object {
        const val TRACK_DEFAULT = ""
        const val TRACK_DEVICE = "device"
        const val LANG_SYSTEM_DEFAULT = "system"
        const val RESUME_ASK = 0
        const val RESUME_ALWAYS = 1
        const val RESUME_NEVER = 2

        // Позиция, переданная внешним приложением (например, Лампой)
        const val EXTERNAL_POSITION_APPLY = 0
        const val EXTERNAL_POSITION_ASK = 1

        // Действие кнопки "Вверх"
        const val UP_ACTION_NONE = 0
        const val UP_ACTION_OSD = 1
        const val UP_ACTION_MENU = 2
        const val UP_ACTION_INFO = 3

        // Действие кнопки "OK" при скрытой панели
        const val OK_ACTION_PAUSE = 0
        const val OK_ACTION_PAUSE_PANEL = 1
        const val OK_ACTION_PANEL = 2

        // Прозрачность часов: -1 = часы выключены, 0..80 = прозрачность в %
        const val CLOCK_OFF = -1

        // Отображение процента буфера
        const val BUFFER_DISPLAY_OFF = 0
        const val BUFFER_DISPLAY_PANEL = 1
        const val BUFFER_DISPLAY_CLOCK = 2 // Рядом с часами (одиночные часы и инфопанель)

        const val BUFFER_SIZE_AUTO = -1
        const val MAX_RECENT_AUDIO_TRACKS = 10

        // Совместимость с Dolby Vision: подмена декодера DV на HEVC (базовый слой HDR10/HLG)
        const val DV_MODE_OFF = 0 // Системный DV декодер
        const val DV_MODE_AUTO = 1 // HEVC, только если экран или декодер не поддерживают Dolby Vision
        const val DV_MODE_ALWAYS = 2 // Всегда HEVC
        const val RENDER_API_AUTO = 0
        const val RENDER_API_GLES2 = 1

        @Volatile
        private var instance: SettingsRepository? = null

        /**
         * Получение единственного экземпляра репозитория (Singleton).
         * Гарантирует потокобезопасность при инициализации.
         */
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun getVideoSettings(uri: String): VideoSettings? {
        return db.videoSettingsDao().getSettings(uri)
    }

    suspend fun saveVideoSettings(settings: VideoSettings) {
        db.videoSettingsDao().saveSettings(settings)
    }

    suspend fun cleanupOldSettings() {
        db.videoSettingsDao().deleteOldSettings(System.currentTimeMillis() - 2592000000L)
    }

    // RENDER_API_AUTO = GLES 3.0 (если доступно), RENDER_API_GLES2 = принудительно GLES 2.0
    fun getRenderApiMode(): Int = prefs.getInt("render_api_mode", RENDER_API_AUTO)
    fun setRenderApiMode(mode: Int) = prefs.edit { putInt("render_api_mode", mode) }

    // --- Zoom Settings ---
    fun isRememberZoomEnabled(): Boolean = prefs.getBoolean("remember_zoom", false)
    fun setRememberZoomEnabled(enabled: Boolean) = prefs.edit { putBoolean("remember_zoom", enabled) }

    // Сохраняем сам режим (Fit, Fill, Zoom, Scale)
    fun getGlobalResizeMode(): Int = prefs.getInt("global_resize_mode", 0) // 0 = FIT
    fun setGlobalResizeMode(modeOrdinal: Int) = prefs.edit { putInt("global_resize_mode", modeOrdinal) }

    // Custom Video Zoom Scale
    fun getZoomScalePercent(): Int = prefs.getInt("video_zoom_scale", 115)
    fun setZoomScalePercent(percent: Int) = prefs.edit { putInt("video_zoom_scale", percent) }

    // --- Playlist Settings ---
    // Плейлист, который открывается при запуске из лаунчера
    fun getStartupPlaylistUri(): String? = prefs.getString("startup_playlist_uri", null)
    fun setStartupPlaylistUri(uri: String?) = prefs.edit { putString("startup_playlist_uri", uri) }

    // Последний канал M3U плейлиста (восстанавливается только по точному URI)
    fun getLastPlayedChannelUri(): String? = prefs.getString("last_channel_uri", null)
    fun setLastPlayedChannelUri(uri: String?) = prefs.edit { putString("last_channel_uri", uri) }

    // Эфирный режим (OK - плейлист, Влево/Вправо - переключение каналов) для плейлистов с live-потоком.
    // По умолчанию выключен
    fun isLiveModeEnabled(): Boolean = prefs.getBoolean("live_mode_control", false)
    fun setLiveModeEnabled(enabled: Boolean) = prefs.edit { putBoolean("live_mode_control", enabled) }

    // Показывать инфопанель при смене элемента плейлиста. По умолчанию выключено
    fun isShowInfoOnTrackChange(): Boolean = prefs.getBoolean("show_info_on_change", false)
    fun setShowInfoOnTrackChange(enabled: Boolean) = prefs.edit { putBoolean("show_info_on_change", enabled) }

    // --- Buffer Settings ---
    // Отображение процента буфера (BUFFER_DISPLAY_*)
    fun getBufferDisplayMode(): Int = prefs.getInt("buffer_display_mode", BUFFER_DISPLAY_OFF)
    fun setBufferDisplayMode(mode: Int) = prefs.edit { putInt("buffer_display_mode", mode) }

    // Размер буфера сетевого видео в мегабайтах (BUFFER_SIZE_AUTO = текущие параметры плеера)
    fun getTargetBufferOptions(): List<Int> {
        val bufferSizeOptions = listOf(BUFFER_SIZE_AUTO, 16, 32, 48, 64, 96, 128, 160, 192, 224, 256, 320, 352, 384, 448, 512, 768, 1024)

        // Разрешаем выбирать буфер в пределах кучи с учетом резерва под UI (тот же лимит, что у плеера)
        val safeLimit = NetworkBufferLoadControl.maxBufferBytesForHeap() / 1024 / 1024

        return bufferSizeOptions.filter { it == BUFFER_SIZE_AUTO || it <= safeLimit }
    }

    // Значение из настроек, если оно допустимо для текущего устройства, иначе "Авто"
    fun getTargetBufferCorrectMB(): Int {
        val currentVal = getTargetBufferMB()
        return if (currentVal in getTargetBufferOptions()) currentVal else BUFFER_SIZE_AUTO
    }
    fun getTargetBufferMB(): Int = prefs.getInt("target_buffer_mb", BUFFER_SIZE_AUTO)
    fun setTargetBufferMB(sizeInMB: Int) = prefs.edit { putInt("target_buffer_mb", sizeInMB) }

    // --- UI Settings ---
    fun getPauseDimLevel(): Int = prefs.getInt("pause_dim_level", 60)
    fun setPauseDimLevel(level: Int) = prefs.edit { putInt("pause_dim_level", level) }
    fun isShowPlaylistIndexEnabled(): Boolean = prefs.getBoolean("show_playlist_index", true)
    fun setShowPlaylistIndexEnabled(enabled: Boolean) = prefs.edit { putBoolean("show_playlist_index", enabled) }

    // --- Global Player Preferences ---
    // Автоматический переход в PiP при сворачивании. По умолчанию выключен
    fun isAutoPipEnabled(): Boolean = prefs.getBoolean("auto_pip", false)
    fun setAutoPipEnabled(enabled: Boolean) = prefs.edit { putBoolean("auto_pip", enabled) }

    fun getDecoderPriority(): Int = prefs.getInt("decoder_priority", DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    fun setDecoderPriority(mode: Int) = prefs.edit { putInt("decoder_priority", mode) }

    fun isTunnelingEnabled(): Boolean = prefs.getBoolean("tunneling_enabled", false)
    fun setTunnelingEnabled(enabled: Boolean) = prefs.edit { putBoolean("tunneling_enabled", enabled) }

    // Принудительный Downmix в стерео
    fun isStereoDownmixEnabled(): Boolean = prefs.getBoolean("stereo_downmix", true)
    fun setStereoDownmixEnabled(enabled: Boolean) = prefs.edit { putBoolean("stereo_downmix", enabled) }

    fun getMixPreset(): Int = prefs.getInt("mix_preset", 0)
    fun setMixPreset(id: Int) = prefs.edit { putInt("mix_preset", id) }

    fun getMixFront(): Float = prefs.getFloat("mix_front", 1.0f)
    fun setMixFront(value: Float) = prefs.edit { putFloat("mix_front", value) }

    fun getMixCenter(): Float = prefs.getFloat("mix_center", 1.0f)
    fun setMixCenter(value: Float) = prefs.edit { putFloat("mix_center", value) }

    fun getMixLfe(): Float = prefs.getFloat("mix_lfe", 0.0f)
    fun setMixLfe(value: Float) = prefs.edit { putFloat("mix_lfe", value) }

    fun getMixRear(): Float = prefs.getFloat("mix_rear", 1.0f)
    fun setMixRear(value: Float) = prefs.edit { putFloat("mix_rear", value) }

    fun getMixMiddle(): Float = prefs.getFloat("mix_middle", 1.0f)
    fun setMixMiddle(value: Float) = prefs.edit { putFloat("mix_middle", value) }

    // Совместимость с Dolby Vision (DV_MODE_*).
    // Миграция со старого переключателя "map_dv_to_hevc" (вкл по умолчанию): вкл -> "Всегда", выкл -> "Выкл".
    fun getDvMode(): Int {
        if (!prefs.contains("dv_mode")) {
            val migrated = if (prefs.getBoolean("map_dv_to_hevc", true)) DV_MODE_ALWAYS else DV_MODE_OFF
            prefs.edit {
                putInt("dv_mode", migrated)
                remove("map_dv_to_hevc")
            }
            return migrated
        }
        return prefs.getInt("dv_mode", DV_MODE_ALWAYS)
    }
    fun setDvMode(mode: Int) = prefs.edit { putInt("dv_mode", mode) }

    fun isFrameRateMatchingEnabled(): Boolean = prefs.getBoolean("frame_rate_matching", false)
    fun setFrameRateMatchingEnabled(enabled: Boolean) = prefs.edit { putBoolean("frame_rate_matching", enabled) }

    fun isAfrResolutionSwitchEnabled(): Boolean = prefs.getBoolean("afr_resolution", false)
    fun setAfrResolutionSwitchEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_resolution", enabled) }

    fun isAfrFpsCorrectionEnabled(): Boolean = prefs.getBoolean("afr_fps_correction", false)
    fun setAfrFpsCorrectionEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_fps_correction", enabled) }

    fun isAfrDoubleRefreshRateEnabled(): Boolean = prefs.getBoolean("afr_double_refresh", true)
    fun setAfrDoubleRefreshRateEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_double_refresh", enabled) }

    fun isAfrSkip24RateEnabled(): Boolean = prefs.getBoolean("afr_skip_24", false)
    fun setAfrSkip24RateEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_skip_24", enabled) }

    fun getAfrPauseMs(): Int = prefs.getInt("afr_pause_ms", 2000)
    fun setAfrPauseMs(ms: Int) = prefs.edit { putInt("afr_pause_ms", ms) }

    fun isAfrSkipShortsEnabled(): Boolean = prefs.getBoolean("afr_skip_shorts", true)
    fun setAfrSkipShortsEnabled(enabled: Boolean) = prefs.edit { putBoolean("afr_skip_shorts", enabled) }

    fun isSkipSilenceEnabled(): Boolean = prefs.getBoolean("skip_silence", false)
    fun setSkipSilenceEnabled(enabled: Boolean) = prefs.edit { putBoolean("skip_silence", enabled) }

    fun getPreferredAudioLang(): String = prefs.getString("pref_audio_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredAudioLang(lang: String) = prefs.edit { putString("pref_audio_lang", lang) }

    fun getPreferredSubLang(): String = prefs.getString("pref_sub_lang", TRACK_DEFAULT) ?: TRACK_DEFAULT
    fun setPreferredSubLang(lang: String) = prefs.edit { putString("pref_sub_lang", lang) }

    fun getLoudnessBoost(): Int = prefs.getInt("loudness_boost", 0)
    fun setLoudnessBoost(boost: Int) = prefs.edit { putInt("loudness_boost", boost) }

    fun getAppLanguage(): String = prefs.getString("app_language", LANG_SYSTEM_DEFAULT) ?: LANG_SYSTEM_DEFAULT
    fun setAppLanguage(langCode: String) = prefs.edit { putString("app_language", langCode) }

    // Запоминание аудиодорожки между видео (по названию, языку и кодеку)
    fun isRememberAudioTrackEnabled(): Boolean = prefs.getBoolean("remember_audio_track", false)
    fun setRememberAudioTrackEnabled(enabled: Boolean) = prefs.edit { putBoolean("remember_audio_track", enabled) }

    // Последние выбранные вручную аудиодорожки (подписи из TrackLogic.audioTrackSignature), новые - первыми
    fun getRecentAudioTracks(): List<String> =
        prefs.getString("recent_audio_tracks", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    fun addRecentAudioTrack(signature: String) {
        val list = listOf(signature) + getRecentAudioTracks().filter { it != signature }
        prefs.edit { putString("recent_audio_tracks", list.take(MAX_RECENT_AUDIO_TRACKS).joinToString("\n")) }
    }

    fun getResumeMode(): Int = prefs.getInt("resume_mode", RESUME_ASK)
    fun setResumeMode(mode: Int) = prefs.edit { putInt("resume_mode", mode) }

    // Позиция из внешнего приложения: применять сразу (по умолчанию) или спрашивать
    fun getExternalPositionMode(): Int = prefs.getInt("external_position_mode", EXTERNAL_POSITION_APPLY)
    fun setExternalPositionMode(mode: Int) = prefs.edit { putInt("external_position_mode", mode) }

    // Действие кнопки "Вверх" ( 0 = Nothing, 1 = OSD, 2 = Side Menu)
    // Действие кнопки "Вверх" (UP_ACTION_*). По умолчанию - боковое меню
    fun getUpButtonAction(): Int = prefs.getInt("up_button_action", UP_ACTION_MENU)
    fun setUpButtonAction(action: Int) = prefs.edit { putInt("up_button_action", action) }

    // Подтверждение выхода из плеера повторным нажатием "Назад"
    fun isConfirmExitEnabled(): Boolean = prefs.getBoolean("confirm_exit", true)
    fun setConfirmExitEnabled(enabled: Boolean) = prefs.edit { putBoolean("confirm_exit", enabled) }

    // Действие кнопки "OK" (OK_ACTION_*). По умолчанию - пауза + панель (текущее поведение)
    fun getOkButtonAction(): Int = prefs.getInt("ok_button_action", OK_ACTION_PAUSE_PANEL)
    fun setOkButtonAction(action: Int) = prefs.edit { putInt("ok_button_action", action) }
    // Горизонтальный свайп ( 0 = None, 1 = Seek, 2 = Playlist)
    fun getHorizontalSwipeAction(): Int = prefs.getInt("horizontal_swipe_action", 2)
    fun setHorizontalSwipeAction(action: Int) = prefs.edit { putInt("horizontal_swipe_action", action) }

    // Прозрачность часов (CLOCK_OFF или 0..80 %).
    // Миграция со старого переключателя "show_clock": выкл -> часы выключены, вкл -> без прозрачности.
    fun getClockTransparency(): Int {
        if (!prefs.contains("clock_transparency")) {
            val migrated = if (prefs.getBoolean("show_clock", false)) 0 else CLOCK_OFF
            prefs.edit {
                putInt("clock_transparency", migrated)
                remove("show_clock")
            }
            return migrated
        }
        return prefs.getInt("clock_transparency", CLOCK_OFF)
    }
    fun setClockTransparency(transparency: Int) = prefs.edit { putInt("clock_transparency", transparency) }

    // Сигнатура настроек, требующих полного перезапуска плеера
    fun getHardSettingsSignature(): String {
        val videoParams = "${getDecoderPriority()}_${isTunnelingEnabled()}_${getDvMode()}"
        val audioDownmix = "${isStereoDownmixEnabled()}_${getMixPreset()}_${getMixFront()}_${getMixCenter()}_${getMixRear()}_${getMixMiddle()}_${getMixLfe()}"
        return "${videoParams}_${audioDownmix}_${getTargetBufferCorrectMB()}"
    }

    // --- Global Preferences Helpers ---
    fun getGlobalFloat(key: String, def: Float) = prefs.getFloat(key, def)
    fun putGlobalFloat(key: String, value: Float) = prefs.edit { putFloat(key, value) }

    fun getGlobalInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putGlobalInt(key: String, value: Int) = prefs.edit { putInt(key, value) }

    fun getGlobalBoolean(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun putGlobalBoolean(key: String, value: Boolean) = prefs.edit { putBoolean(key, value) }

    fun getLastUpdateTime(): Long = prefs.getLong("last_update_check", 0)
    fun setLastUpdateTime(time: Long) = prefs.edit { putLong("last_update_check", time) }

    fun getLastUpdateInfo(): String? = prefs.getString("last_update_info_json", null)
    fun saveUpdateInfo(json: String?) {
        prefs.edit {
            putString("last_update_info_json", json)
            putLong("last_update_check", System.currentTimeMillis())
        }
    }

    fun saveGlobalDefaults(
        outputMode: StereoOutputMode,
        anaglyphType: StereoRenderer.AnaglyphType
    ) {
        prefs.edit {
            putInt("def_output_mode", outputMode.ordinal)
            putInt("def_anaglyph_type", anaglyphType.ordinal)
        }
    }
}