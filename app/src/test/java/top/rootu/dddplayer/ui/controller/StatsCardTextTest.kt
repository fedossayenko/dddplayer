package top.rootu.dddplayer.ui.controller

import androidx.media3.common.ColorInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import top.rootu.dddplayer.player.PlaybackStats

@RunWith(RobolectricTestRunner::class)
class StatsCardTextTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun stats(timeMs: Long, netBytes: Long, netRequests: Long) = PlaybackStats(
        timeMs = timeMs,
        bufferedMs = 42_900,
        bufferBytes = 310L * 1024 * 1024,
        netBytes = netBytes,
        netRequests = netRequests,
        video = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160)
            .setFrameRate(23.976f)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build(),
        audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build(),
        droppedFrames = 3
    )

    @Test
    fun `a second between snapshots gives the speed and requests, the first one a dash`() {
        val first = stats(10_000, 0, 5)
        assertEquals("—", StatsCardText.values(res, first, null, "")[1])
        // 6 025 000 байт за секунду = 48.2 Мбит/с, 2 новых запроса
        val values = StatsCardText.values(res, stats(11_000, 6_025_000, 7), first, "PCM 16-bit 5.1")
        assertEquals(
            listOf("42 s · 310 MB", "48.2 Mbps · 2 req/s", "3840x2160 · HEVC HDR · 23.976 fps", "E-AC-3 → PCM 16-bit 5.1", "3"),
            values
        )
        assertEquals(5, StatsCardText.labels(res).size)
    }

    @Test
    fun `no video and no audio yet are dashes`() {
        val empty = stats(0, 0, 0).copy(video = null, audio = null)
        assertEquals(listOf("—", "—"), StatsCardText.values(res, empty, null, "").subList(2, 4))
    }
}
