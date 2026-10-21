package top.rootu.dddplayer.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.av1.Dav1dLibrary
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import top.rootu.dddplayer.BuildConfig
import top.rootu.dddplayer.R
import top.rootu.dddplayer.logic.DeviceInfoLogic

/**
 * Сбор данных для экрана диагностики: устройство, экран и HDR, аудиовыходы и проброс, декодеры.
 * Каждый раздел собирается отдельно: ошибка в одном (кривая прошивка) не ломает остальные.
 */
object DeviceInfoCollector {

    data class Section(val title: String, val lines: List<String>)

    private const val TAG = "DeviceInfo"

    private val VIDEO_MIMES = listOf(
        MimeTypes.VIDEO_H264 to "H.264",
        MimeTypes.VIDEO_H265 to "HEVC",
        MimeTypes.VIDEO_DOLBY_VISION to "Dolby Vision",
        MimeTypes.VIDEO_AV1 to "AV1",
        MimeTypes.VIDEO_VP9 to "VP9",
        MimeTypes.VIDEO_VP8 to "VP8",
        MimeTypes.VIDEO_MPEG2 to "MPEG-2",
        MimeTypes.VIDEO_MP4V to "MPEG-4",
        MimeTypes.VIDEO_VC1 to "VC-1"
    )

    private val AUDIO_MIMES = listOf(
        MimeTypes.AUDIO_AC3 to "AC3",
        MimeTypes.AUDIO_E_AC3 to "E-AC3",
        MimeTypes.AUDIO_E_AC3_JOC to "E-AC3 JOC (Atmos)",
        MimeTypes.AUDIO_AC4 to "AC-4",
        MimeTypes.AUDIO_DTS to "DTS",
        MimeTypes.AUDIO_DTS_HD to "DTS-HD",
        MimeTypes.AUDIO_TRUEHD to "TrueHD",
        MimeTypes.AUDIO_AAC to "AAC",
        MimeTypes.AUDIO_MPEG to "MP3",
        MimeTypes.AUDIO_OPUS to "Opus",
        MimeTypes.AUDIO_VORBIS to "Vorbis",
        MimeTypes.AUDIO_FLAC to "FLAC"
    )

    // Форматы, которые можно отдать на выход без декодирования (проброс)
    private val PASSTHROUGH_ENCODINGS = listOf(
        C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_AC4,
        C.ENCODING_DTS, C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2, C.ENCODING_DOLBY_TRUEHD
    )

    fun collect(activity: Activity): List<Section> {
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }
            .onFailure { Log.w(TAG, "Codec list failed", it) }
            .getOrDefault(emptyList())
        return listOf(
            section(activity.getString(R.string.diag_sec_device)) { device(activity) },
            section(activity.getString(R.string.diag_sec_display)) { display(activity) },
            section(activity.getString(R.string.diag_sec_audio_out)) { audioOutput(activity) },
            section(activity.getString(R.string.diag_sec_video_dec)) { videoDecoders(activity, decoders) },
            section(activity.getString(R.string.diag_sec_audio_dec)) { audioDecoders(activity, decoders) }
        )
    }

    /** Отчет текстом для копирования в сообщение об ошибке */
    fun toText(sections: List<Section>): String = sections.joinToString("\n\n") { section ->
        "== ${section.title} ==\n" + section.lines.joinToString("\n")
    }

    private fun section(title: String, lines: () -> List<String>): Section {
        val content = try {
            lines()
        } catch (e: Exception) {
            Log.w(TAG, "Section $title failed", e)
            listOf("Error: ${e.javaClass.simpleName}: ${e.message}")
        }
        return Section(title, content)
    }

    private fun Context.line(labelRes: Int, value: String) = "${getString(labelRes)}: $value"

    private fun device(activity: Activity): List<String> {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val heapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return listOf(
            activity.line(R.string.diag_model, "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"),
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            activity.line(R.string.diag_app_version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
            activity.line(R.string.diag_abi, Build.SUPPORTED_ABIS.joinToString(", ")),
            activity.line(R.string.diag_heap, "$heapMb MB"),
            "OpenGL ES: ${am.deviceConfigurationInfo.glEsVersion}"
        )
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): List<String> {
        val display: Display = activity.windowManager.defaultDisplay
        val lines = mutableListOf<String>()
        val mode = display.mode
        lines += activity.line(R.string.diag_mode, DeviceInfoLogic.formatMode(mode.physicalWidth, mode.physicalHeight, mode.refreshRate))
        val modes = display.supportedModes
            .sortedWith(compareByDescending<Display.Mode> { it.physicalWidth * it.physicalHeight }.thenByDescending { it.refreshRate })
            .map { DeviceInfoLogic.formatMode(it.physicalWidth, it.physicalHeight, it.refreshRate) }
            .distinct()
        lines += activity.line(R.string.diag_modes, modes.joinToString(", "))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val hdr = display.hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
            lines += activity.line(R.string.diag_hdr, DeviceInfoLogic.hdrTypeNames(hdr).joinToString(", ").ifEmpty { activity.getString(R.string.diag_none) })
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lines += activity.line(R.string.diag_wide_gamut, activity.getString(if (display.isWideColorGamut) R.string.diag_yes else R.string.diag_no))
        }
        return lines
    }

    private fun audioOutput(activity: Activity): List<String> {
        val lines = mutableListOf<String>()
        val am = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Выходы с форматами, которые они принимают (для HDMI - из EDID телевизора или ресивера)
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE && it.type != AudioDeviceInfo.TYPE_TELEPHONY }
            .forEach { device ->
                val encodings = device.encodings.toList().distinct()
                    .map { MediaFormatHelper.getAudioCodecName(it) }.distinct()
                val channels = device.channelCounts.maxOrNull()
                val details = buildList {
                    if (encodings.isNotEmpty()) add(encodings.joinToString(", "))
                    if (channels != null && channels > 0) add(activity.line(R.string.diag_max_channels, channels.toString()))
                }
                val name = device.productName?.toString()?.trim().orEmpty()
                val title = deviceTypeName(device.type) + if (name.isNotEmpty()) " ($name)" else ""
                lines += if (details.isEmpty()) title else "$title: ${details.joinToString("; ")}"
            }
        // Что Media3 считает доступным для проброса на текущем выходе
        val caps = AudioCapabilities.getCapabilities(activity, AudioAttributes.DEFAULT, null)
        val passthrough = PASSTHROUGH_ENCODINGS.filter { caps.supportsEncoding(it) }.map { MediaFormatHelper.getAudioCodecName(it) }
        lines += activity.line(R.string.diag_passthrough, passthrough.joinToString(", ").ifEmpty { activity.getString(R.string.diag_none) })
        lines += activity.line(R.string.diag_max_channels, caps.maxChannelCount.toString())
        return lines
    }

    private fun deviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
        29 -> "HDMI eARC" // AudioDeviceInfo.TYPE_HDMI_EARC (API 31)
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        22 -> "USB headset" // AudioDeviceInfo.TYPE_USB_HEADSET (API 26)
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Line (analog)"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line (digital)"
        AudioDeviceInfo.TYPE_AUX_LINE -> "AUX"
        AudioDeviceInfo.TYPE_IP -> "IP"
        else -> "Type $type"
    }

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !DeviceInfoLogic.isSoftwareCodecName(info.name)

    // Псевдонимы (API 29) и защищенные копии декодеров дублируют основной декодер
    private fun isDuplicate(info: MediaCodecInfo): Boolean =
        info.name.endsWith(".secure") || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias)

    private fun videoDecoders(activity: Activity, decoders: List<MediaCodecInfo>): List<String> =
        VIDEO_MIMES.map { (mime, label) ->
            val items = decoders.filter { !isDuplicate(it) && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .map { info ->
                    val caps = info.getCapabilitiesForType(mime)
                    val video = caps.videoCapabilities
                    val parts = mutableListOf(if (isHardware(info)) "HW" else "SW")
                    if (video != null) parts += "${video.supportedWidths.upper}×${video.supportedHeights.upper}"
                    parts += DeviceInfoLogic.profileFeatures(mime, caps.profileLevels.map { it.profile })
                    "${info.name} (${parts.joinToString(", ")})"
                }.toMutableList()
            if (mime == MimeTypes.VIDEO_AV1 && runCatching { Dav1dLibrary.isAvailable() }.getOrDefault(false)) {
                items += "dav1d (${activity.getString(R.string.diag_builtin)}, SW)"
            }
            "$label: " + items.joinToString("; ").ifEmpty { activity.getString(R.string.diag_none) }
        }

    private fun audioDecoders(activity: Activity, decoders: List<MediaCodecInfo>): List<String> {
        val ffmpeg = runCatching { FfmpegLibrary.isAvailable() }.getOrDefault(false)
        return AUDIO_MIMES.map { (mime, label) ->
            val items = decoders.filter { !isDuplicate(it) && it.supportedTypes.any { t -> t.equals(mime, ignoreCase = true) } }
                .map { "${it.name} (${if (isHardware(it)) "HW" else "SW"})" }
                .toMutableList()
            if (ffmpeg && runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)) {
                items += "FFmpeg (${activity.getString(R.string.diag_builtin)})"
            }
            "$label: " + items.joinToString("; ").ifEmpty { activity.getString(R.string.diag_none) }
        }
    }
}
