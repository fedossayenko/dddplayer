package top.rootu.dddplayer.player

import android.text.TextUtils
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * LoadControl с буфером, заданным пользователем в мегабайтах, только для сетевого видео.
 *
 * Локальные файлы (file://, content:// и т.п.) и режим "Авто" (targetBufferBytes == C.LENGTH_UNSET)
 * используют прежние параметры буферизации плеера.
 *
 * При заданном объеме для сети лимит по времени снимается: плеер грузит данные,
 * пока не заполнит указанный объем памяти.
 */
class NetworkBufferLoadControl private constructor(
    val bufferAllocator: DefaultAllocator,
    private val networkTargetBufferBytes: Int,
    streamingMinBufferMs: Int,
    streamingMaxBufferMs: Int,
    streamingPrioritizeTime: Boolean
) : DefaultLoadControl(
    bufferAllocator,
    streamingMinBufferMs,
    MIN_BUFFER_MS,
    streamingMaxBufferMs,
    MAX_BUFFER_MS,
    BUFFER_FOR_PLAYBACK_MS,
    BUFFER_FOR_PLAYBACK_MS,
    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
    C.LENGTH_UNSET,
    streamingPrioritizeTime,
    true,
    DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
    DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
    emptyMap()
) {
    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        if (networkTargetBufferBytes != C.LENGTH_UNSET && !isLocalPlayback(parameters)) {
            return networkTargetBufferBytes
        }
        return super.calculateTargetBufferBytes(parameters, trackSelectionArray)
    }

    // Повторяет логику DefaultLoadControl.isLocalPlayback (она private)
    private fun isLocalPlayback(parameters: LoadControl.Parameters): Boolean {
        val windowIndex = parameters.timeline
            .getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex
        val uri = parameters.timeline.getWindow(windowIndex, window).mediaItem
            .localConfiguration?.uri ?: return false
        val scheme = uri.scheme
        return TextUtils.isEmpty(scheme) || DefaultLoadControl.LOCAL_PLAYBACK_SCHEMES.contains(scheme)
    }

    companion object {
        // Текущие параметры буферизации плеера
        const val MIN_BUFFER_MS = 15_000
        const val MAX_BUFFER_MS = 50_000
        const val BUFFER_FOR_PLAYBACK_MS = 500
        const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

        // При заданном объеме буфера лимит по времени фактически снимается (~13 часов)
        private const val UNLIMITED_BUFFER_MS = 50_000_000

        /**
         * @param networkBufferMB объем буфера для сетевого видео в МБ, или -1 для режима "Авто".
         */
        fun create(networkBufferMB: Int): NetworkBufferLoadControl {
            val allocator = DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
            return if (networkBufferMB > 0) {
                NetworkBufferLoadControl(
                    allocator,
                    networkBufferMB * 1024 * 1024,
                    UNLIMITED_BUFFER_MS,
                    UNLIMITED_BUFFER_MS,
                    streamingPrioritizeTime = false
                )
            } else {
                NetworkBufferLoadControl(
                    allocator,
                    C.LENGTH_UNSET,
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    streamingPrioritizeTime = true
                )
            }
        }
    }
}
