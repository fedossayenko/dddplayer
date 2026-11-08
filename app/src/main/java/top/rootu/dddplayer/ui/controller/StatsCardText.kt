package top.rootu.dddplayer.ui.controller

import android.content.res.Resources
import top.rootu.dddplayer.R
import top.rootu.dddplayer.player.PlaybackStats
import top.rootu.dddplayer.utils.MediaFormatHelper
import kotlin.math.roundToInt

/** Строки карточки статистики (BUFFER_DISPLAY_STATS): подписи и значения, по одной строке на пункт. */
object StatsCardText {
    private const val NONE = "—"

    fun labels(res: Resources): List<String> = listOf(
        R.string.stats_buffer, R.string.stats_network, R.string.stats_video, R.string.stats_audio, R.string.stats_dropped
    ).map(res::getString)

    /** [prev] - снимок секундой раньше: без него вместо скорости сети прочерк. [audioOut] - что идет на выход. */
    fun values(res: Resources, now: PlaybackStats, prev: PlaybackStats?, audioOut: String): List<String> {
        val buffer = res.getString(R.string.stats_buffer_value, now.bufferedMs / 1000, now.bufferBytes / (1024 * 1024))
        val ms = now.timeMs - (prev?.timeMs ?: now.timeMs)
        val network = if (prev == null || ms <= 0) NONE else res.getString(
            R.string.stats_network_value,
            (now.netBytes - prev.netBytes) * 8f / ms / 1000f, // байт за мс -> Мбит/с
            ((now.netRequests - prev.netRequests) * 1000f / ms).roundToInt()
        )
        val video = now.video?.let { f ->
            val fps = MediaFormatHelper.formatFrameRate(f.frameRate)
            listOf(
                MediaFormatHelper.formatResolution(f.width, f.height),
                "${MediaFormatHelper.getShortVideoCodecName(f)} ${MediaFormatHelper.getHdrInfo(f)}".trim(),
                if (fps.isEmpty()) "" else res.getString(R.string.stats_fps, fps)
            ).filter { it.isNotEmpty() }.joinToString(" · ")
        }.orEmpty()
        // Входной кодек и выход: "E-AC-3 → PCM 16-bit 5.1" - декодируем, "E-AC-3 → E-AC-3 5.1" - passthrough
        val audio = listOfNotNull(
            now.audio?.sampleMimeType?.let(MediaFormatHelper::getCodecName),
            audioOut.ifEmpty { null }
        ).joinToString(" → ")
        return listOf(buffer, network, video.ifEmpty { NONE }, audio.ifEmpty { NONE }, now.droppedFrames.toString())
    }
}
