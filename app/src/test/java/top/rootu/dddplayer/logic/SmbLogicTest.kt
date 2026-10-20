package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmbLogicTest {

    @Test
    fun `addresses in different forms`() {
        assertEquals(SmbAddress("192.168.1.5", null, "Video", "Series/Show"), SmbLogic.parseAddress("smb://192.168.1.5/Video/Series/Show/"))
        assertEquals(SmbAddress("nas", null, "Movies", ""), SmbLogic.parseAddress("\\\\nas\\Movies"))
        assertEquals(SmbAddress("nas", 4455, "Movies", "4K"), SmbLogic.parseAddress("nas:4455/Movies/4K"))
        assertEquals(SmbAddress("nas", null, "Movies", ""), SmbLogic.parseAddress("SMB://nas/Movies"))
        // Без общего ресурса адрес не годится
        assertNull(SmbLogic.parseAddress("smb://nas"))
        assertNull(SmbLogic.parseAddress(""))
    }

    @Test
    fun `address is formatted back`() {
        assertEquals("smb://nas:4455/Movies/4K", SmbLogic.format(SmbAddress("nas", 4455, "Movies", "4K")))
        assertEquals("smb://nas/Movies", SmbLogic.format(SmbAddress("nas", null, "Movies", "")))
    }

    @Test
    fun `paths inside a share`() {
        assertEquals("Series", SmbLogic.childPath("", "Series"))
        assertEquals("Series/S01", SmbLogic.childPath("Series", "S01"))
        assertEquals("Series", SmbLogic.parentPath("Series/S01"))
        assertEquals("", SmbLogic.parentPath("Series"))
    }

    @Test
    fun `subtitles next to the video`() {
        val names = listOf("Movie.mkv", "Movie.srt", "movie.ru.ass", "Movie 2.srt", "Movie.nfo", "Other.srt")
        assertEquals(listOf("movie.ru.ass", "Movie.srt"), SmbLogic.subtitlesFor("Movie.mkv", names))
    }
}
