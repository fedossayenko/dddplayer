package top.rootu.dddplayer.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import top.rootu.dddplayer.logic.Hdr10PlusStripper

/**
 * Видеодорожка без динамических метаданных HDR10+: видео играет как HDR10 (опция "HDR10+" выключена).
 * HEVC и AV1 чистятся в самом сэмпле ([Hdr10PlusStripper]), у VP9 HDR10+ приходит из MKV как
 * дополнительные данные сэмпла - их отбрасываем.
 *
 * Буферизуем все видеосэмплы: в TS данные первого кадра приходят раньше формата дорожки.
 */
class Hdr10PlusStrippingTrackOutput(delegate: TrackOutput) :
    BufferedTransformingTrackOutput(delegate, INITIAL_BUFFER_SIZE) {

    private enum class Codec { OTHER, HEVC, AV1, VP9 }

    private var codec = Codec.OTHER
    private val out = Hdr10PlusStripper.Output(INITIAL_BUFFER_SIZE)
    private var useOutput = false
    private var removedTotal = 0L

    override fun format(format: Format) {
        codec = detectCodec(format)
        super.format(format)
    }

    override val transformEnabled: Boolean = true

    override val transformedBuffer: ByteArray
        get() = if (useOutput) out.data else inputBuffer

    override fun transformSample(inputLength: Int, flags: Int): Int {
        useOutput = false
        if (codec == Codec.OTHER || inputLength == 0 || (flags and C.BUFFER_FLAG_ENCRYPTED) != 0) {
            return if (inputLength == 0) -1 else inputLength
        }
        return try {
            if ((flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) != 0) transformWithSupplemental(inputLength)
            else transformMain(inputLength)
        } catch (e: Exception) {
            Log.w(TAG, "HDR10+ strip failed, passing raw sample", e)
            useOutput = false
            inputLength
        }
    }

    /** Обычный сэмпл: вырезаем HDR10+ из данных кадра. */
    private fun transformMain(inputLength: Int): Int {
        val removed = strip(0, inputLength)
        if (removed == 0) return inputLength
        onRemoved(removed)
        useOutput = true
        return out.length
    }

    /**
     * Сэмпл с дополнительными данными: [4 байта размер кадра][кадр][доп. данные].
     * У VP9 доп. данные - это HDR10+ (ITU-T T.35 из BlockAdditional MKV): оставляем их пустыми.
     */
    private fun transformWithSupplemental(inputLength: Int): Int {
        if (inputLength < 4) return inputLength
        val mainLength = ((inputBuffer[0].toInt() and 0xFF) shl 24) or ((inputBuffer[1].toInt() and 0xFF) shl 16) or
            ((inputBuffer[2].toInt() and 0xFF) shl 8) or (inputBuffer[3].toInt() and 0xFF)
        if (mainLength < 0 || mainLength > inputLength - 4) return inputLength
        val supplementalLength = inputLength - 4 - mainLength

        if (codec == Codec.VP9) {
            if (supplementalLength == 0) return inputLength
            onRemoved(1)
            // Кадр остается на месте, отрезаем только хвост с метаданными
            return 4 + mainLength
        }

        val removed = strip(4, mainLength)
        if (removed == 0) return inputLength
        onRemoved(removed)
        // Пересобираем: размер кадра, очищенный кадр, доп. данные как были
        val stripped = out.data.copyOf(out.length)
        out.reset()
        out.write(ByteArray(4), 0, 4)
        out.writeInt32At(0, stripped.size)
        out.write(stripped, 0, stripped.size)
        out.write(inputBuffer, 4 + mainLength, supplementalLength)
        useOutput = true
        return out.length
    }

    private fun strip(offset: Int, length: Int): Int = when (codec) {
        Codec.HEVC -> Hdr10PlusStripper.stripHevc(inputBuffer, offset, length, out)
        Codec.AV1 -> Hdr10PlusStripper.stripAv1(inputBuffer, offset, length, out)
        else -> 0
    }

    private fun onRemoved(count: Int) {
        if (removedTotal == 0L) Log.i(TAG, "HDR10+ metadata found ($codec), stripping: playing as HDR10")
        removedTotal += count
    }

    private fun detectCodec(format: Format): Codec {
        val codecs = format.codecs.orEmpty()
        return when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> Codec.HEVC
            MimeTypes.VIDEO_AV1 -> Codec.AV1
            MimeTypes.VIDEO_VP9 -> Codec.VP9
            // Dolby Vision поверх HEVC (dvhe/dvh1) или AV1 (dav1): базовый слой может нести HDR10+
            MimeTypes.VIDEO_DOLBY_VISION -> when {
                codecs.startsWith("dvhe") || codecs.startsWith("dvh1") -> Codec.HEVC
                codecs.startsWith("dav1") -> Codec.AV1
                else -> Codec.OTHER
            }
            else -> Codec.OTHER
        }
    }

    private companion object {
        const val TAG = "Hdr10PlusStrip"
        const val INITIAL_BUFFER_SIZE = 256 * 1024
    }
}

/** Обертка экстрактора: видеодорожки идут через [Hdr10PlusStrippingTrackOutput]. */
class Hdr10PlusExtractorWrapper(private val delegate: Extractor) : Extractor {

    private val trackOutputs = mutableListOf<Hdr10PlusStrippingTrackOutput>()

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) {
        delegate.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val original = output.track(id, type)
                if (type != C.TRACK_TYPE_VIDEO) return original
                return Hdr10PlusStrippingTrackOutput(original).also { trackOutputs.add(it) }
            }

            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)

    override fun seek(position: Long, timeUs: Long) {
        // Недописанные сэмплы до перемотки больше не нужны
        trackOutputs.forEach { it.resetBufferedData() }
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getSniffFailureDetails() = delegate.sniffFailureDetails

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}
