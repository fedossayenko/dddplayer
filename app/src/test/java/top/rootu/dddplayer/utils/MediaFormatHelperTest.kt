package top.rootu.dddplayer.utils

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormatHelperTest {

    @Test
    fun `file extension is taken after the last dot in lower case`() {
        assertEquals("srt", MediaFormatHelper.getFileExtension("/movies/Movie.Name.SRT"))
        assertNull(MediaFormatHelper.getFileExtension("/movies/noext"))
        assertNull(MediaFormatHelper.getFileExtension("/movies/dot."))
    }

    @Test
    fun `subtitle mime type by file name`() {
        assertEquals(MimeTypes.APPLICATION_SUBRIP, MediaFormatHelper.getSubtitleMimeTypeByName("movie.ru.srt"))
        assertEquals(MimeTypes.TEXT_VTT, MediaFormatHelper.getSubtitleMimeTypeByName("movie.VTT"))
        assertEquals(MimeTypes.TEXT_SSA, MediaFormatHelper.getSubtitleMimeTypeByName("movie.ass"))
        assertEquals(MimeTypes.APPLICATION_TTML, MediaFormatHelper.getSubtitleMimeTypeByName("movie.dfxp"))
    }

    @Test
    fun `unsupported and unknown subtitle files`() {
        // Бинарные форматы, которые плеер не умеет
        assertNull(MediaFormatHelper.getSubtitleMimeTypeByName("movie.idx"))
        assertEquals(MimeTypes.TEXT_UNKNOWN, MediaFormatHelper.getSubtitleMimeTypeByName("movie.xyz"))
        assertEquals(MimeTypes.TEXT_UNKNOWN, MediaFormatHelper.getSubtitleMimeTypeByName("subtitles"))
    }
}
