package top.rootu.dddplayer.logic

import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONObject
import top.rootu.dddplayer.model.MediaItem

/**
 * Парсер M3U плейлистов (IPTV и списки файлов).
 * HLS плейлисты (любой тег #EXT-X-) плейлистом каналов не считаются.
 */
object M3uParser {
    private const val EXT_INF = "#EXTINF"
    private const val EXT_GRP = "#EXTGRP"
    private const val EXT_VLC_OPT = "#EXTVLCOPT"
    private const val EXT_HTTP = "#EXTHTTP"
    private const val HLS_TAG_PREFIX = "#EXT-X-"

    private val attrRegex = Regex("""([a-zA-Z0-9_-]+)=((?:"[^"]*")|(?:[^\s,]+))""")

    /**
     * Ответ сервера по ссылке может быть самим видео или звуком: тогда проверять его на плейлист не нужно.
     * Но плейлисты M3U часто отдаются как audio/x-mpegurl (TorrServer, Icecast) или application/vnd.apple.mpegurl.
     */
    fun isMediaContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.contains("mpegurl")) return false
        return type.startsWith("video/") || type.startsWith("audio/")
    }

    /**
     * true, если это M3U плейлист с элементами (#EXTINF), а не HLS.
     */
    fun isPlaylist(content: String): Boolean {
        var hasExtInf = false
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(HLS_TAG_PREFIX, ignoreCase = true)) return false
            if (trimmed.startsWith(EXT_INF, ignoreCase = true)) hasExtInf = true
        }
        return hasExtInf
    }

    fun parse(content: String, baseUri: Uri, parentHeaders: Map<String, String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()

        var currentTitle: String? = null
        var currentPoster: Uri? = null
        var currentGroup: String? = null
        var defGroup: String? = null
        val currentItemHeaders = mutableMapOf<String, String>()

        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith(EXT_GRP, true) -> {
                    defGroup = trimmed.substringAfter(":").trim().ifEmpty { null }
                }
                trimmed.startsWith(EXT_INF, true) -> {
                    val (attrs, title) = splitExtInf(trimmed.substringAfter(":"))
                    currentTitle = title
                    attrRegex.findAll(attrs).forEach { match ->
                        val key = match.groupValues[1].lowercase()
                        val value = match.groupValues[2].removeSurrounding("\"")
                        when (key) {
                            "tvg-logo", "logo" -> if (value.isNotBlank()) currentPoster = value.toUri()
                            "group-title" -> currentGroup = value.ifBlank { null }
                        }
                    }
                }
                trimmed.startsWith(EXT_VLC_OPT, true) -> parseVlcOpt(trimmed, currentItemHeaders)
                trimmed.startsWith(EXT_HTTP, true) -> parseExtHttp(trimmed, currentItemHeaders)
                !trimmed.startsWith("#") -> {
                    val parsed = trimmed.toUri()
                    val uri = if (parsed.scheme != null) parsed else resolveRelative(baseUri, trimmed)

                    items.add(
                        MediaItem(
                            uri = uri,
                            title = currentTitle?.ifBlank { null },
                            posterUri = currentPoster,
                            group = currentGroup ?: defGroup, // Наследование группы из #EXTGRP
                            headers = parentHeaders + currentItemHeaders
                        )
                    )
                    currentTitle = null
                    currentPoster = null
                    currentGroup = null
                    currentItemHeaders.clear()
                }
            }
        }
        return items
    }

    /**
     * Делит строку после "#EXTINF:" на атрибуты и название.
     * Название - все после первой запятой вне кавычек (в названии могут быть запятые).
     */
    private fun splitExtInf(value: String): Pair<String, String> {
        var inQuotes = false
        for (i in value.indices) {
            when (value[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return value.substring(0, i) to value.substring(i + 1).trim()
            }
        }
        return value to ""
    }

    private fun parseVlcOpt(line: String, outHeaders: MutableMap<String, String>) {
        val opt = line.substringAfter(":").trim()
        val key = opt.substringBefore("=").lowercase()
        val value = opt.substringAfter("=", "")
        if (value.isEmpty()) return
        when (key) {
            "http-user-agent" -> outHeaders["User-Agent"] = value
            "http-referrer", "http-referer" -> outHeaders["Referer"] = value
            "http-cookie" -> outHeaders["Cookie"] = value
        }
    }

    private fun parseExtHttp(line: String, outHeaders: MutableMap<String, String>) {
        try {
            val json = JSONObject(line.substringAfter(":").trim())
            json.keys().forEach { outHeaders[it] = json.getString(it) }
        } catch (_: Exception) {
        }
    }

    private fun resolveRelative(baseUri: Uri, relativePath: String): Uri {
        val baseStr = baseUri.toString()
        val lastSlash = baseStr.lastIndexOf('/')
        return if (lastSlash != -1) (baseStr.substring(0, lastSlash + 1) + relativePath).toUri() else relativePath.toUri()
    }
}
