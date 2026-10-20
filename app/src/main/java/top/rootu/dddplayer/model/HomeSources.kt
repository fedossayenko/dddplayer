package top.rootu.dddplayer.model

/** Откуда запущено видео со стартового экрана: по этому источнику ведется история просмотра. */
data class HistorySource(val kind: String, val source: String, val name: String)

/** Плейлист по ссылке на стартовом экране (M3U/IPTV). Имя задает пользователь. */
data class SavedPlaylist(val id: String, val name: String, val url: String)

/**
 * Запись истории "Продолжить просмотр". Одна запись на источник: при переключении серии в папке
 * запись обновляется (новое видео и позиция), а не добавляется новая.
 *
 * @param kind тип источника: [KIND_FOLDER] - папка (видео папки открываются плейлистом), [KIND_PLAYLIST] - плейлист по ссылке
 * @param source путь к папке или ссылка на плейлист
 * @param sourceName название источника для подписи (имя папки или плейлиста)
 * @param title название текущего видео
 * @param uri ссылка на текущее видео
 */
data class HistoryEntry(
    val kind: String,
    val source: String,
    val sourceName: String,
    val title: String,
    val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
) {
    val key: String get() = "$kind:$source"

    companion object {
        const val KIND_FOLDER = "folder"
        const val KIND_PLAYLIST = "playlist"
    }
}
