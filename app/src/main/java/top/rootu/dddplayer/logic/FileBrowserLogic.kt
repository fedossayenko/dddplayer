package top.rootu.dddplayer.logic

import java.util.Locale

/** Элемент списка файлового браузера. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L
) {
    val isVideo: Boolean get() = !isDirectory && FileBrowserLogic.isVideo(name)
    val isPlaylist: Boolean get() = !isDirectory && FileBrowserLogic.isPlaylist(name)
}

/**
 * Чистая логика встроенного файлового браузера: какие файлы показывать и в каком порядке.
 */
object FileBrowserLogic {

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "mov", "avi", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "vob",
        "wmv", "flv", "3gp", "3gpp", "ogv", "divx"
    )
    private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")

    private fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun isVideo(name: String): Boolean = extension(name) in VIDEO_EXTENSIONS

    fun isPlaylist(name: String): Boolean = extension(name) in PLAYLIST_EXTENSIONS

    /**
     * Что показываем: папки (кроме скрытых), видео и плейлисты. Папки сверху, дальше по имени
     * с учетом чисел ("Серия 2" раньше "Серия 10").
     */
    fun visibleEntries(entries: List<FileEntry>): List<FileEntry> = entries
        .filter { !it.name.startsWith(".") && (it.isDirectory || it.isVideo || it.isPlaylist) }
        .sortedWith(compareBy<FileEntry> { !it.isDirectory }.then { a, b -> naturalCompare(a.name, b.name) })

    /** Сравнение строк без учета регистра, числа сравниваются по значению. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val numA = a.substring(startA, i).trimStart('0')
                val numB = b.substring(startB, j).trimStart('0')
                if (numA.length != numB.length) return numA.length - numB.length
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    /** Размер файла: 734 Б, 12.4 МБ, 3.1 ГБ (единицы передаются из ресурсов). */
    fun formatSize(bytes: Long, units: List<String>): String {
        if (bytes < 1024) return "$bytes ${units[0]}"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
