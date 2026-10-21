package top.rootu.dddplayer.logic

import android.net.Uri
import top.rootu.dddplayer.model.HistoryEntry
import java.util.Locale

/**
 * Чистая логика стартового экрана: история просмотра и подписи.
 */
object HomeLogic {

    const val MAX_HISTORY = 30
    // Сколько записей истории видно сразу, остальные - по "Вся история"
    const val HISTORY_PREVIEW = 3

    /** Добавляет или обновляет запись источника и поднимает ее наверх. */
    fun upsertHistory(history: List<HistoryEntry>, entry: HistoryEntry, max: Int = MAX_HISTORY): List<HistoryEntry> =
        (listOf(entry) + history.filter { it.key != entry.key }).take(max)

    /** Время 41:20 или 1:52:00. */
    fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** Позиция для подписи истории: "41:20 / 1:52:00", пусто для эфира и начала видео. */
    fun progressLabel(positionMs: Long, durationMs: Long): String = when {
        positionMs <= 0 -> ""
        durationMs <= 0 -> formatTime(positionMs)
        else -> "${formatTime(positionMs)} / ${formatTime(durationMs)}"
    }

    /** Источник истории для папки медиасервера: адрес описания сервера и id папки. */
    fun dlnaSource(serverLocation: String, objectId: String): String = "$serverLocation\n$objectId"

    fun parseDlnaSource(source: String): Pair<String, String>? {
        val location = source.substringBefore('\n')
        val objectId = source.substringAfter('\n', "")
        return if (location.isNotEmpty() && objectId.isNotEmpty()) location to objectId else null
    }

    /** Имя по умолчанию для плейлиста по ссылке: хост или имя файла. */
    fun defaultPlaylistName(url: String): String {
        val uri = Uri.parse(url)
        return uri.host?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
            ?: url
    }
}
