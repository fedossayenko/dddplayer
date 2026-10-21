package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogUploadCacheTest {

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `same text gives the same hash`() {
        assertEquals(LogUploadCache.hash("crash log"), LogUploadCache.hash("crash log"))
        assertNotEquals(LogUploadCache.hash("crash log"), LogUploadCache.hash("crash log 2"))
        assertEquals(64, LogUploadCache.hash("").length)
    }

    @Test
    fun `cached url is used only for the same text within the ttl`() {
        val hash = LogUploadCache.hash("log")
        val entry = LogUploadCache.Entry(hash, "https://termbin.com/abcd", uploadedAt = 100 * day)
        assertEquals("https://termbin.com/abcd", LogUploadCache.cachedUrl(entry, hash, now = 100 * day + 14 * day))
        assertNull(LogUploadCache.cachedUrl(entry, hash, now = 100 * day + 15 * day))
        assertNull(LogUploadCache.cachedUrl(entry, LogUploadCache.hash("other"), now = 100 * day + day))
        assertNull(LogUploadCache.cachedUrl(null, hash, now = 100 * day))
        // Часы ушли назад - ссылке не верим
        assertNull(LogUploadCache.cachedUrl(entry, hash, now = 99 * day))
    }
}
