package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.model.HistoryEntry

// defaultPlaylistName разбирает ссылку через android.net.Uri - нужен Robolectric
@RunWith(RobolectricTestRunner::class)
class HomeLogicTest {

    private fun entry(source: String, uri: String, time: Long) =
        HistoryEntry(HistoryEntry.KIND_FOLDER, source, source, uri, uri, 1000, 2000, time)

    @Test
    fun `next episode of the same folder replaces the entry and moves it up`() {
        val history = listOf(entry("/a", "/a/e1.mkv", 2), entry("/b", "/b/x.mkv", 1))
        val updated = HomeLogic.upsertHistory(history, entry("/b", "/b/y.mkv", 3))
        assertEquals(listOf("/b/y.mkv", "/a/e1.mkv"), updated.map { it.uri })
    }

    @Test
    fun `history is limited`() {
        val history = (1..5).map { entry("/d$it", "/d$it/v.mkv", it.toLong()) }
        assertEquals(3, HomeLogic.upsertHistory(history, entry("/new", "/new/v.mkv", 9), max = 3).size)
    }

    @Test
    fun `progress label`() {
        assertEquals("41:20 / 1:52:00", HomeLogic.progressLabel(2_480_000, 6_720_000))
        assertEquals("0:05", HomeLogic.progressLabel(5_000, 0))
        assertEquals("", HomeLogic.progressLabel(0, 6_720_000))
    }

    @Test
    fun `default playlist name is the host or the file name`() {
        assertEquals("iptv.example.com", HomeLogic.defaultPlaylistName("http://iptv.example.com/get.php?u=1"))
        assertEquals("tv.m3u", HomeLogic.defaultPlaylistName("file:///storage/emulated/0/tv.m3u"))
    }
}
