package top.rootu.dddplayer.utils

import android.net.Uri
import top.rootu.dddplayer.model.SubtitleItem
import java.io.File

/**
 * Поиск внешних субтитров рядом с видео: movie.mkv -> movie.srt, movie.ru.srt, movie.eng.forced.ass.
 * Работает только для file:// ссылок. На Android 11+ система может не показывать приложению
 * не-медиа файлы общих папок, тогда список будет пустым.
 */
object SubtitleFinder {
    private val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

    fun findSidecarSubtitles(videoUri: Uri): List<SubtitleItem> {
        if (videoUri.scheme != "file") return emptyList()
        val path = videoUri.path ?: return emptyList()
        return try {
            val video = File(path)
            val dir = video.parentFile ?: return emptyList()
            val baseName = video.name.substringBeforeLast('.')
            val files = dir.listFiles() ?: return emptyList()
            files.filter { file ->
                file.isFile && file.extension.lowercase() in EXTENSIONS &&
                        file.name.startsWith("$baseName.", ignoreCase = true)
            }
                .sortedBy { it.name.lowercase() }
                .map { file ->
                    // Средняя часть имени - название дорожки: movie.ru.srt -> "ru", movie.srt -> нет
                    val tag = file.name
                        .substring(baseName.length + 1, file.name.length - file.extension.length)
                        .trimEnd('.')
                    val uri = Uri.fromFile(file)
                    SubtitleItem(uri, tag.ifEmpty { null }, file.name, MediaFormatHelper.getSubtitleMimeType(uri))
                }
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
