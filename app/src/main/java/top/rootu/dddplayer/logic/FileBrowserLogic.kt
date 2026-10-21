package top.rootu.dddplayer.logic

import java.text.Collator
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
    fun visibleEntries(entries: List<FileEntry>): List<FileEntry> {
        val collator = newCollator()
        return entries
            .filter { !it.name.startsWith(".") && (it.isDirectory || it.isVideo || it.isPlaylist) }
            // Ключ сравнения считаем один раз на файл, а не в каждом сравнении
            .map { it to collator.getCollationKey(sortKey(it.name)) }
            .sortedWith(compareBy<Pair<FileEntry, java.text.CollationKey>> { !it.first.isDirectory }.thenBy { it.second })
            .map { it.first }
    }

    /**
     * Сравнение имен с учетом чисел: s1e2 < s1e10, "Серия 2" < "Серия 10".
     * Числа дополняются нулями до одной длины, а строки сравниваются по правилам языка (Collator)
     * без учета регистра.
     */
    fun naturalCompare(a: String, b: String): Int {
        val collator = newCollator()
        return collator.compare(sortKey(a), sortKey(b))
    }

    private const val NUMBER_WIDTH = 32
    private val NUMBER = Regex("\\d+")

    // s1e2 -> s000...1e000...2: числа одной длины сравниваются как строки так же, как по значению.
    // Нижний регистр - для надежности: Collator из JDK (тесты) не знает регистров кириллицы, в отличие от ICU в Android
    private fun sortKey(name: String): String =
        NUMBER.replace(name.lowercase(Locale.ROOT)) { it.value.trimStart('0').padStart(NUMBER_WIDTH, '0') }

    // Collator не потокобезопасен: новый на каждую сортировку. SECONDARY - без учета регистра
    private fun newCollator(): Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

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
