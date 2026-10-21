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

    /**
     * @param listDir чтение содержимого папки. Для плейлиста из одной папки передайте кэширующий вариант
     * ([cachedLister]), чтобы не читать папку заново для каждого видео (на USB-дисках это заметно).
     */
    fun findSidecarSubtitles(videoUri: Uri, listDir: (File) -> Array<File>? = { it.listFiles() }): List<SubtitleItem> {
        if (videoUri.scheme != "file") return emptyList()
        val path = videoUri.path ?: return emptyList()
        return try {
            val video = File(path)
            val dir = video.parentFile ?: return emptyList()
            val baseName = video.name.substringBeforeLast('.')
            val files = listDir(dir) ?: return emptyList()
            files.filter { file ->
                // Сначала проверки по имени, isFile последним: на USB каждый запрос к файлу заметен
                file.extension.lowercase() in EXTENSIONS &&
                        file.name.startsWith("$baseName.", ignoreCase = true) && file.isFile
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

    /** Чтение папок с кэшем на время разбора одного плейлиста. */
    fun cachedLister(): (File) -> Array<File>? {
        val cache = HashMap<String, Array<File>?>()
        return { dir -> cache.getOrPut(dir.absolutePath) { dir.listFiles() } }
    }
}
