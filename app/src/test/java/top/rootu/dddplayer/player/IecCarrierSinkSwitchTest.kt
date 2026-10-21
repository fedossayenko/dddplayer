package top.rootu.dddplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Выбор между несущей IEC 61937 и обычным Sink, смена дорожки во время воспроизведения. */
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

    /** Заглушка обычного Sink с заданной поддержкой форматов; configure может бросать ошибку. */
    private fun normalSink(support: Int, failConfigure: Boolean = false, calls: MutableList<String> = mutableListOf()): AudioSink =
        Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            calls += method.name
            when (method.name) {
                "getFormatSupport" -> support
                "supportsFormat" -> support != AudioSink.SINK_FORMAT_UNSUPPORTED
                "handleBuffer" -> true
                "configure" -> if (failConfigure) throw AudioSink.ConfigurationException("test", args!![0] as Format) else null
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink

    @Test
    fun trueHdWithoutCarrierRouteKeepsTheOldPath() {
        // Выход без IEC 61937 (Android < 10 и т.п.): как с выключенной опцией, решает обычный Sink
        val sink = IecCarrierSink(normalSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY), recordingSink(mutableListOf()), { false }, { false })
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format(MimeTypes.AUDIO_TRUEHD, 8)))
        assertTrue(sink.supportsFormat(format(MimeTypes.AUDIO_TRUEHD, 8)))
    }

    @Test
    fun trueHdOnCarrierRouteDecodesWhenTheCarrierIsUnavailable() {
        // Выход с несущей, но туннелирование: сырой TrueHD там молчит, поэтому декодер
        val sink = IecCarrierSink(normalSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY), recordingSink(mutableListOf()), { true }, { false })
        sink.enableTunnelingV21()
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format(MimeTypes.AUDIO_TRUEHD, 8)))
    }

    @Test
    fun plainDtsSkipsTheCarrierWhenTheNormalSinkCannotTakeTheCore() {
        val carrierCalls = mutableListOf<String>()
        val sink = IecCarrierSink(normalSink(AudioSink.SINK_FORMAT_UNSUPPORTED), recordingSink(carrierCalls), { true }, { false })
        assertEquals(AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format(MimeTypes.AUDIO_DTS, 6)))
        sink.configure(format(MimeTypes.AUDIO_DTS, 6), 0, null)
        assertTrue("carrier must not be configured", "configure" !in carrierCalls)
    }

    @Test
    fun failedDtsCoreFallbackIsAnInitializationError() {
        // A_DTS без HD-части уходит в обычный Sink; его ошибка configure должна прийти как ошибка инициализации
        val sink = IecCarrierSink(
            normalSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, failConfigure = true),
            normalSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY),
            { true },
            { false }
        )
        sink.configure(format(MimeTypes.AUDIO_DTS, 6), 0, null)
        assertEquals("DTS-HD", sink.carrierCodecLabel)
        assertThrows(AudioSink.InitializationException::class.java) {
            sink.handleBuffer(ByteBuffer.wrap(dtsCoreFrame()), 0, 1)
        }
    }

    @Test
    fun carrierCodecLabelNamesTheCodecOnTheCarrier() {
        val sink = IecCarrierSink(normalSink(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY), recordingSink(mutableListOf()), { true }, { false })
        assertNull(sink.carrierCodecLabel)
        sink.configure(format(MimeTypes.AUDIO_TRUEHD, 8), 0, null)
        assertEquals("TrueHD", sink.carrierCodecLabel)
        sink.configure(format(MimeTypes.AUDIO_DTS_HD, 6), 0, null)
        assertEquals("DTS-HD", sink.carrierCodecLabel)
        sink.configure(format(MimeTypes.AUDIO_AC3, 6), 0, null)
        assertNull(sink.carrierCodecLabel)
    }

    /** Первый кадр ядра DTS из фикстуры DTS-HD MA, без HD-части (EXSS) */
    private fun dtsCoreFrame(): ByteArray {
        val units = checkNotNull(javaClass.classLoader?.getResourceAsStream("dtshd_ma_access_units.bin")).use { it.readBytes() }
        val exss = (4 until units.size - 4).first {
            units[it] == 0x64.toByte() && units[it + 1] == 0x58.toByte() && units[it + 2] == 0x20.toByte() && units[it + 3] == 0x25.toByte()
        }
        return units.copyOfRange(0, exss)
    }
}
