package top.rootu.dddplayer.logic

import java.util.Locale

/** Адрес сетевой папки SMB: сервер, общий ресурс и путь внутри него (через "/"). */
data class SmbAddress(val host: String, val port: Int?, val share: String, val path: String)

/**
 * Чистая логика SMB: разбор адреса, пути внутри ресурса, субтитры рядом с видео.
 */
object SmbLogic {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "ttml", "dfxp")

    /**
     * Адрес из ввода пользователя: smb://host[:port]/share/folder, \\host\share\folder или host/share/folder.
     * Без общего ресурса - null: smbj не умеет получать список ресурсов сервера.
     */
    fun parseAddress(input: String): SmbAddress? {
        var text = input.trim().replace('\\', '/')
        if (text.startsWith("smb://", ignoreCase = true)) text = text.substring(6)
        text = text.trimStart('/')
        val parts = text.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val hostPart = parts[0]
        val port = hostPart.substringAfterLast(':', "").toIntOrNull()
        val host = if (port != null) hostPart.substringBeforeLast(':') else hostPart
        if (host.isEmpty()) return null
        return SmbAddress(host, port, parts[1], parts.drop(2).joinToString("/"))
    }

    /** Адрес для отображения: smb://host/share/folder. */
    fun format(address: SmbAddress): String {
        val port = address.port?.let { ":$it" }.orEmpty()
        val path = if (address.path.isEmpty()) "" else "/${address.path}"
        return "smb://${address.host}$port/${address.share}$path"
    }

    fun childPath(path: String, name: String): String = if (path.isEmpty()) name else "$path/$name"

    fun parentPath(path: String): String = path.substringBeforeLast('/', "")

    /** Субтитры для видео среди имен файлов папки: movie.mkv -> movie.srt, movie.ru.srt. */
    fun subtitlesFor(videoName: String, fileNames: List<String>): List<String> {
        val base = videoName.substringBeforeLast('.')
        return fileNames.filter { name ->
            name.substringAfterLast('.', "").lowercase(Locale.ROOT) in SUBTITLE_EXTENSIONS &&
                    name.startsWith("$base.", ignoreCase = true)
        }.sortedBy { it.lowercase(Locale.ROOT) }
    }
}
