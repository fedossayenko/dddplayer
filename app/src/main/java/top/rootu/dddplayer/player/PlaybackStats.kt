package top.rootu.dddplayer.player

import androidx.media3.common.Format

/** Снимок для карточки статистики. Сеть - счетчики с запуска, скорость дает разница двух снимков. */
data class PlaybackStats(
    val timeMs: Long,
    val bufferedMs: Long, // загружено вперед от текущей позиции
    val bufferBytes: Long, // занято в памяти буфера
    val netBytes: Long,
    val netRequests: Long,
    val video: Format?,
    val audio: Format?,
    val droppedFrames: Int
)
