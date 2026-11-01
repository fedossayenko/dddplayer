package top.rootu.dddplayer.player

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Скорость не 1x и звук идет битстримом (passthrough AC3/E-AC3/DTS): DefaultAudioSink меняет темп только
 * у PCM (shouldApplyAudioProcessorPlaybackParameters), битстрим играет как есть, а за его часами и видео,
 * то есть 1.5x/2x молча не работают. Пока скорость не 1x, Sink отказывается от битстрима и сообщает
 * о смене возможностей - тот же путь, что у Media3 при смене HDMI (и у IecCarrierSink): плеер заново
 * включает рендереры на текущей позиции, звук декодируется. На 1x - обратно в битстрим тем же путем.
 */
@UnstableApi
class SpeedDecodeSink(sink: AudioSink) : ForwardingAudioSink(sink) {

    private var listener: AudioSink.Listener? = null
    private var input: Format? = null // формат, с которым Sink настроен сейчас
    private var sped = false // скорость не 1x
    private var refused = false // при скорости не 1x отказали битстриму, который иначе ушел бы как есть

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        input = inputFormat
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun reset() {
        input = null
        super.reset()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        val now = playbackParameters.speed != 1f
        if (now == sped) return
        sped = now
        // Сигнал только когда звук на самом деле переедет: уже декодированный PCM ускоряется и так,
        // а лишнее переключение - пауза на перезапуск рендереров
        val move = if (sped) input?.let(::isBitstream) == true else refused
        refused = false
        if (move) {
            Log.i(TAG, "Speed ${playbackParameters.speed}x: audio moves ${if (sped) "to the decoder" else "back to passthrough"}")
            listener?.onAudioCapabilitiesChanged()
        }
    }

    override fun getFormatSupport(format: Format): Int {
        val support = super.getFormatSupport(format)
        if (!sped || !isBitstream(format) || support == AudioSink.SINK_FORMAT_UNSUPPORTED) return support
        refused = true
        return AudioSink.SINK_FORMAT_UNSUPPORTED
    }

    @Deprecated("AudioSink.supportsFormat", ReplaceWith("getFormatSupport(format)"))
    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    private companion object {
        const val TAG = "SpeedDecodeSink"

        fun isBitstream(format: Format): Boolean = format.sampleMimeType.let { it != null && it != MimeTypes.AUDIO_RAW }
    }
}
