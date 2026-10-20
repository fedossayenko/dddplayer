package top.rootu.dddplayer.utils

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.model.StereoInputType

@RunWith(RobolectricTestRunner::class)
class StereoTypeDetectorTest {

    private fun detect(name: String) =
        StereoTypeDetector.detect(null, Uri.parse("http://example.com/video/$name"))

    @Test
    fun `stereo type from file name markers`() {
        assertEquals(StereoInputType.SIDE_BY_SIDE, detect("Avatar.2009.3D.HSBS.1080p.mkv"))
        assertEquals(StereoInputType.SIDE_BY_SIDE, detect("movie_half-sbs.mkv"))
        assertEquals(StereoInputType.TOP_BOTTOM, detect("Movie (OU).mkv"))
        assertEquals(StereoInputType.TOP_BOTTOM, detect("movie.top-bottom.mp4"))
        assertEquals(StereoInputType.INTERLACED, detect("movie.interlace.mkv"))
        assertEquals(StereoInputType.TILED_1080P, detect("movie.3dz-tiled.mkv"))
    }

    @Test
    fun `markers inside words are ignored`() {
        assertEquals(StereoInputType.NONE, detect("sbsmovie.mkv"))
        assertEquals(StereoInputType.NONE, detect("Ballad.of.the.Road.mkv"))
        assertEquals(StereoInputType.NONE, detect("Regular.Movie.1080p.mkv"))
    }

    @Test
    fun `container metadata has priority over the file name`() {
        val format = Format.Builder().setStereoMode(C.STEREO_MODE_TOP_BOTTOM).build()
        val type = StereoTypeDetector.detect(format, Uri.parse("http://example.com/movie.sbs.mkv"))
        assertEquals(StereoInputType.TOP_BOTTOM, type)
    }
}
