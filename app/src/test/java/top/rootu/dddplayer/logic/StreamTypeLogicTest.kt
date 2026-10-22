package top.rootu.dddplayer.logic

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamTypeLogicTest {

    @Test
    fun `content type with parameters and variants is hls`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamTypeLogic.fromResponse("application/vnd.apple.mpegurl; charset=utf-8", "/v"))
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamTypeLogic.fromResponse("Application/X-MpegURL", null))
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamTypeLogic.fromResponse("audio/mpegurl", null))
        assertEquals(MimeTypes.APPLICATION_MPD, StreamTypeLogic.fromResponse("application/dash+xml;charset=UTF-8", null))
    }

    @Test
    fun `extension of final url is the fallback`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, StreamTypeLogic.fromResponse("application/octet-stream", "/hls/index.M3U8"))
        assertNull(StreamTypeLogic.fromResponse("video/mp4", "/movie.mp4"))
        assertNull(StreamTypeLogic.fromResponse(null, null))
    }

    @Test
    fun `hls fallback only for links that do not look like video files`() {
        assertTrue(StreamTypeLogic.shouldTryHls("/play/12345"))
        assertTrue(StreamTypeLogic.shouldTryHls("/stream.php"))
        assertFalse(StreamTypeLogic.shouldTryHls("/stream/Starring.mkv"))
        assertFalse(StreamTypeLogic.shouldTryHls("/films/movie.MP4"))
    }
}
