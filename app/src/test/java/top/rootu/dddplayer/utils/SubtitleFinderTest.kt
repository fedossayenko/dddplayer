package top.rootu.dddplayer.utils

import android.net.Uri
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SubtitleFinderTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `finds subtitles with the same base name next to the video`() {
        val video = folder.newFile("Movie.mkv")
        listOf("Movie.srt", "Movie.ru.srt", "movie.eng.forced.ass", "Movie2.srt", "Other.srt", "Movie.txt")
            .forEach { folder.newFile(it) }

        val subs = SubtitleFinder.findSidecarSubtitles(Uri.fromFile(video))

        assertEquals(listOf("movie.eng.forced.ass", "Movie.ru.srt", "Movie.srt"), subs.map { it.filename })
        // Название дорожки - часть имени между названием видео и расширением
        assertEquals(listOf("eng.forced", "ru", null), subs.map { it.name })
        assertEquals(
            listOf(MimeTypes.TEXT_SSA, MimeTypes.APPLICATION_SUBRIP, MimeTypes.APPLICATION_SUBRIP),
            subs.map { it.mimeType }
        )
    }

    @Test
    fun `network and content links are not scanned`() {
        assertTrue(SubtitleFinder.findSidecarSubtitles(Uri.parse("http://example.com/Movie.mkv")).isEmpty())
        assertTrue(SubtitleFinder.findSidecarSubtitles(Uri.parse("content://media/external/video/1")).isEmpty())
    }
}
