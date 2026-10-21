package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Смена дорожки AC3 -> DTS-HD во время воспроизведения: play() приходит до configure. */
class IecCarrierSinkSwitchTest {

    /** AudioSink-заглушка, которая записывает имена вызванных методов. */
    private fun recordingSink(calls: MutableList<String>): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            calls += method.name
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        } as AudioSink

    private fun format(mime: String, channels: Int) = Format.Builder()
        .setSampleMimeType(mime).setChannelCount(channels).setSampleRate(48_000).build()

    @Test
    fun playBeforeConfigureReachesCarrierAndReleasesOldOutput() {
        val defaultCalls = mutableListOf<String>()
        val carrierCalls = mutableListOf<String>()
        val sink = IecCarrierSink(recordingSink(defaultCalls), recordingSink(carrierCalls), { true }, { false })

        sink.configure(format(MimeTypes.AUDIO_AC3, 6), 0, null)
        sink.play()
        // Пользователь переключил дорожку: рендерер снова стартует до прихода нового формата
        sink.pause()
        sink.play()
        defaultCalls.clear()
        sink.configure(format(MimeTypes.AUDIO_DTS_HD, 6), 0, null)

        assertTrue("carrier must be started", "play" in carrierCalls)
        assertTrue("carrier must be configured", "configure" in carrierCalls)
        assertTrue("old output must be released", "flush" in defaultCalls)
    }

    @Test
    fun pausedSwitchDoesNotStartCarrier() {
        val carrierCalls = mutableListOf<String>()
        val sink = IecCarrierSink(recordingSink(mutableListOf()), recordingSink(carrierCalls), { true }, { false })

        sink.configure(format(MimeTypes.AUDIO_AC3, 6), 0, null)
        sink.configure(format(MimeTypes.AUDIO_DTS_HD, 6), 0, null)

        assertTrue("paused player must not start the carrier", "play" !in carrierCalls)
    }

    @Test
    fun tunnelingKeepsHdAudioOffTheCarrier() {
        val carrierCalls = mutableListOf<String>()
        val sink = IecCarrierSink(recordingSink(mutableListOf()), recordingSink(carrierCalls), { true }, { false })

        sink.enableTunnelingV21()
        sink.configure(format(MimeTypes.AUDIO_DTS_HD, 6), 0, null)

        assertTrue("tunneled playback must not use the carrier", "configure" !in carrierCalls)
    }
}
