package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBrowserLogicTest {

    private fun file(name: String, size: Long = 0) = FileEntry(name, "/x/$name", isDirectory = false, size = size)
    private fun dir(name: String) = FileEntry(name, "/x/$name", isDirectory = true)

    @Test
    fun `videos and playlists are shown, other files and hidden entries are not`() {
        val result = FileBrowserLogic.visibleEntries(
            listOf(file("movie.MKV"), file("movie.srt"), file("list.m3u"), file("notes.txt"), dir(".thumbnails"), dir("Series"))
        ).map { it.name }
        assertEquals(listOf("Series", "list.m3u", "movie.MKV"), result)
    }

    @Test
    fun `numbers are sorted by value`() {
        val result = FileBrowserLogic.visibleEntries(
            listOf(file("Серия 10.mkv"), file("Серия 2.mkv"), file("серия 1.mkv"), file("Серия 02.mkv"))
        ).map { it.name }
        assertEquals(listOf("серия 1.mkv", "Серия 2.mkv", "Серия 02.mkv", "Серия 10.mkv"), result)
    }

    @Test
    fun `directories come first`() {
        val result = FileBrowserLogic.visibleEntries(listOf(file("a.mp4"), dir("z"), dir("B"))).map { it.name }
        assertEquals(listOf("B", "z", "a.mp4"), result)
    }

    @Test
    fun `natural compare handles prefixes and long numbers`() {
        assertTrue(FileBrowserLogic.naturalCompare("abc", "abcd") < 0)
        assertTrue(FileBrowserLogic.naturalCompare("S01E09", "S01E10") < 0)
        assertTrue(FileBrowserLogic.naturalCompare("x99999999999999999999", "x100000000000000000000") < 0)
        assertEquals(0, FileBrowserLogic.naturalCompare("Movie", "movie"))
    }

    @Test
    fun `file types and sizes`() {
        assertTrue(file("a.ts").isVideo)
        assertFalse(dir("a.mkv").isVideo)
        assertTrue(file("b.M3U8").isPlaylist)
        val units = listOf("B", "KB", "MB", "GB", "TB")
        assertEquals("512 B", FileBrowserLogic.formatSize(512, units))
        assertEquals("1.5 KB", FileBrowserLogic.formatSize(1536, units))
        assertEquals("3.0 GB", FileBrowserLogic.formatSize(3L * 1024 * 1024 * 1024, units))
    }
}
