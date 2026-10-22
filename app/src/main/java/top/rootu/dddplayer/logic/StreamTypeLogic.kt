package top.rootu.dddplayer.logic

import androidx.media3.common.MimeTypes

/** Тип адаптивного потока (HLS/DASH/SmoothStreaming) по ответу сервера. */
object StreamTypeLogic {

    /**
     * @param contentType заголовок Content-Type (может быть с параметрами: "; charset=utf-8")
     * @param urlPath путь финального URL (после редиректов) - запасной вариант, если Content-Type не помог
     * @return MIME-тип Media3 или null (обычный файл)
     */
    fun fromResponse(contentType: String?, urlPath: String?): String? {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase()
        when (type) {
            "application/x-mpegurl", "application/vnd.apple.mpegurl",
            "audio/mpegurl", "audio/x-mpegurl" -> return MimeTypes.APPLICATION_M3U8
            "application/dash+xml" -> return MimeTypes.APPLICATION_MPD
            "application/vnd.ms-sstr+xml" -> return MimeTypes.APPLICATION_SS
        }
        return when (urlPath?.substringAfterLast('.', "")?.lowercase()) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            "ism", "isml" -> MimeTypes.APPLICATION_SS
            else -> null
        }
    }

    // Расширения обычных видеофайлов: такой файл не пробуем открыть как HLS
    private val FILE_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "ts", "m2ts", "mts", "mov", "webm", "flv", "wmv", "rmvb", "rm",
        "3gp", "mpg", "mpeg", "vob", "iso", "ogv", "mp3", "aac", "flac", "mka"
    )

    /**
     * Ни один экстрактор не узнал файл, а сервер тип не сообщил: стоит один раз попробовать HLS
     * (ссылки балансеров без расширения или с чужим, например .php).
     */
    fun shouldTryHls(urlPath: String?): Boolean =
        urlPath?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase() !in FILE_EXTENSIONS
}
