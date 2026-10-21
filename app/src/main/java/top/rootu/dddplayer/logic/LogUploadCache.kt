package top.rootu.dddplayer.logic

import java.security.MessageDigest

/**
 * Кеш выгрузки лога на termbin: тот же текст (по SHA-256) не выгружается повторно, а сразу
 * показывается прошлая ссылка. termbin хранит вставки месяц, ссылка считается живой половину срока.
 */
object LogUploadCache {

    const val TTL_MS = 15L * 24 * 60 * 60 * 1000

    data class Entry(val hash: String, val url: String, val uploadedAt: Long)

    fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Ссылка из кеша, если текст тот же и срок не вышел (время назад - часы перевели - тоже не верим) */
    fun cachedUrl(entry: Entry?, hash: String, now: Long): String? {
        if (entry == null || entry.hash != hash || entry.url.isBlank()) return null
        val age = now - entry.uploadedAt
        return entry.url.takeIf { age in 0 until TTL_MS }
    }
}
