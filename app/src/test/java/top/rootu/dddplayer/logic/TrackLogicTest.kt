package top.rootu.dddplayer.logic

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.rootu.dddplayer.viewmodel.TrackOption

// Format.Builder нормализует код языка через Android API - нужен Robolectric
@RunWith(RobolectricTestRunner::class)
class TrackLogicTest {

    private fun audio(label: String?, language: String?, mime: String, channels: Int) =
        Format.Builder()
            .setLabel(label)
            .setLanguage(language)
            .setSampleMimeType(mime)
            .setChannelCount(channels)
            .build()

    private fun option(format: Format?, nameFromMeta: String? = null, isOff: Boolean = false) =
        TrackOption(format, nameFromMeta, 1, null, 0, isOff)

    @Test
    fun `signature contains name, language, codec and channels`() {
        val format = audio("DUB Mosfilm", "ru", MimeTypes.AUDIO_AC3, 6)
        assertEquals("DUB Mosfilm|ru|${MimeTypes.AUDIO_AC3}|6", TrackLogic.audioTrackSignature(option(format)))
    }

    @Test
    fun `name from file metadata has priority over the player label`() {
        val format = audio("Label", "en", MimeTypes.AUDIO_AAC, 2)
        assertEquals(
            "Meta name|en|${MimeTypes.AUDIO_AAC}|2",
            TrackLogic.audioTrackSignature(option(format, nameFromMeta = "  Meta name "))
        )
    }

    @Test
    fun `same track in different videos has the same signature`() {
        val a = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 6)
        val b = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 6)
        assertEquals(TrackLogic.audioTrackSignature(option(a)), TrackLogic.audioTrackSignature(option(b)))
        val stereo = audio("Original", "en", MimeTypes.AUDIO_E_AC3, 2)
        assertNotEquals(TrackLogic.audioTrackSignature(option(a)), TrackLogic.audioTrackSignature(option(stereo)))
    }

    @Test
    fun `missing language becomes und and newlines are removed`() {
        val format = audio("Two\nlines", null, MimeTypes.AUDIO_AAC, 2)
        assertEquals("Two lines|und|${MimeTypes.AUDIO_AAC}|2", TrackLogic.audioTrackSignature(option(format)))
    }

    @Test
    fun `off item has no signature`() {
        assertNull(TrackLogic.audioTrackSignature(option(null, isOff = true)))
        assertNull(TrackLogic.audioTrackSignature(option(null)))
    }
}
