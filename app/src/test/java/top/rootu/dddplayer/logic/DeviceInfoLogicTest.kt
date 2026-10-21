package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceInfoLogicTest {

    @Test
    fun `hevc profiles give 10-bit and hdr`() {
        assertEquals(listOf("10-bit", "HDR10", "HDR10+"), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_HEVC, listOf(0x1, 0x2, 0x1000, 0x2000)))
        assertEquals(listOf("10-bit"), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_HEVC, listOf(0x1, 0x2)))
        assertEquals(emptyList<String>(), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_HEVC, listOf(0x1)))
    }

    @Test
    fun `dolby vision profiles are listed by number`() {
        // DvheDtr (0x10) - профиль 4, DvheStn (0x20) - 5, DvheSt (0x100) - 8
        assertEquals(listOf("P4", "P5", "P8"), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_DOLBY_VISION, listOf(0x10, 0x20, 0x100)))
    }

    @Test
    fun `vp9 and av1 hdr profiles`() {
        assertEquals(listOf("10-bit", "HDR10", "HDR10+"), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_VP9, listOf(0x1, 0x4, 0x1000, 0x4000)))
        assertEquals(listOf("10-bit", "HDR10"), DeviceInfoLogic.profileFeatures(DeviceInfoLogic.MIME_AV1, listOf(0x1, 0x2, 0x1000)))
        assertEquals(emptyList<String>(), DeviceInfoLogic.profileFeatures("video/avc", listOf(0x8)))
    }

    @Test
    fun `software codecs are told by name`() {
        assertTrue(DeviceInfoLogic.isSoftwareCodecName("OMX.google.h264.decoder"))
        assertTrue(DeviceInfoLogic.isSoftwareCodecName("c2.android.hevc.decoder"))
        assertFalse(DeviceInfoLogic.isSoftwareCodecName("OMX.amlogic.hevc.decoder.awesome2"))
        assertFalse(DeviceInfoLogic.isSoftwareCodecName("c2.mtk.avc.decoder"))
    }

    @Test
    fun `modes and hdr types are formatted`() {
        assertEquals("60", DeviceInfoLogic.formatRate(60f))
        assertEquals("59.94", DeviceInfoLogic.formatRate(59.94f))
        assertEquals("23.976", DeviceInfoLogic.formatRate(23.976f))
        assertEquals("3840×2160 @ 50", DeviceInfoLogic.formatMode(3840, 2160, 50f))
        assertEquals(listOf("Dolby Vision", "HDR10", "HLG", "HDR10+"), DeviceInfoLogic.hdrTypeNames(listOf(4, 2, 3, 1, 2)))
    }
}
