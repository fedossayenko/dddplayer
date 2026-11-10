package top.rootu.dddplayer.player

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Декодированный 7.1 -> 5.1 (настройка fold_71_to_51). На Fire OS прямой PCM-порт принимает маски 0x3 и 0x3f,
 * а 8 каналов уходят в стерео-микшер: тыловые пропадают. Свернутые 6 каналов идут напрямую (потом MS12 DD+ 5.1).
 */
internal object Fold71 {
    /** Порядок Android: FL FR C LFE BL BR SL SR -> FL FR C LFE BL BR. */
    fun matrices(): List<ChannelMixingMatrix> {
        val identity = (1..7).map { ChannelMixingMatrix.create(it, it) }
        val matrix = FloatArray(8 * 6)
        fun put(input: Int, output: Int, gain: Float) { matrix[input * 6 + output] = gain }
        for (c in 0 until 4) put(c, c, 1f) // FL FR C LFE как есть
        put(4, 4, BACK_GAIN); put(5, 5, BACK_GAIN) // тыловые (BL BR) тише, чтобы сумма не клипала
        put(6, 4, 1f); put(7, 5, 1f) // боковые (SL SR) - основные окружающие в 7.1, идут в левый/правый окружающий 5.1
        return identity + ChannelMixingMatrix(8, 6, matrix)
    }

    private const val BACK_GAIN = 0.7071f
    const val MAX_CHANNELS = 6

    /** Аудио-рендерер, который просит у декодера устройства не больше 6 каналов (не 8 и не стерео по умолчанию). */
    @OptIn(UnstableApi::class)
    class Renderer(
        context: Context,
        factory: MediaCodecAdapter.Factory,
        selector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        handler: Handler?,
        listener: AudioRendererEventListener?,
        sink: AudioSink
    ) : MediaCodecAudioRenderer(context, factory, selector, enableDecoderFallback, handler, listener, sink) {
        override fun getMediaFormat(
            format: Format,
            codecMimeType: String,
            codecMaxInputSize: Int,
            codecOperatingRate: Float
        ): MediaFormat = super.getMediaFormat(format, codecMimeType, codecMaxInputSize, codecOperatingRate).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, MAX_CHANNELS)
        }
    }
}
