package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import okhttp3.OkHttpClient
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.EOFException

// Не юнит-тест (без DDD_URL пропускается): повторяет цикл чтения ProgressiveMediaPeriod (Mp4Extractor, RESULT_SEEK =
// новый open) по настоящему файлу через ReusingDataSource и печатает запросы. DDD_URL=... DDD_FROM_S=1497 ./gradlew testDebugUnitTest --tests '*Mp4Jumps*'
@RunWith(RobolectricTestRunner::class)
class Mp4JumpsDiagnostic {
    @Test
    fun replay() {
        val url = System.getenv("DDD_URL")
        Assume.assumeTrue("DDD_URL не задан", url != null)
        url!!
        val fromUs = (System.getenv("DDD_FROM_S") ?: "1497").toLong() * 1_000_000
        val endUs = fromUs + 60_000_000
        var opens = 0
        var bytes = 0L
        val http = OkHttpDataSource.Factory(OkHttpClient())
        val counting = DataSource.Factory {
            val d = http.createDataSource()
            object : DataSource {
                var at = 0L; var got = 0L
                override fun addTransferListener(transferListener: TransferListener) = d.addTransferListener(transferListener)
                override fun open(dataSpec: DataSpec): Long { opens++; at = dataSpec.position; got = 0; return d.open(dataSpec) }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    d.read(buffer, offset, length).also { if (it > 0) { bytes += it; got += it } }
                override fun getUri(): Uri? = d.uri
                override fun close() { if (System.getenv("DDD_LOG") != null) println("conn at %.4f GB read %.2f MB".format(at / 1e9, got / 1e6)); d.close() }
            }
        }
        val source = ReusingDataSource({ true }, counting)
        var seekMap: SeekMap? = null
        var videoUs = 0L
        val scratch = ByteArray(64 * 1024)
        val output = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) {}
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                    val n = input.read(scratch, 0, minOf(length, scratch.size))
                    if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
                    return n
                }
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    if (type == C.TRACK_TYPE_VIDEO) videoUs = maxOf(videoUs, timeUs)
                }
            }
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) { seekMap.also { m -> seekMapSet(m) } }
            fun seekMapSet(m: SeekMap) { seekMap = m }
        }
        val extractor = Mp4Extractor(DefaultSubtitleParserFactory())
        extractor.init(output)
        val holder = PositionHolder()
        val uri = Uri.parse(url)
        var position = 0L
        var seeked = false
        val started = System.nanoTime()
        var startedFrom = 0L
        while (videoUs < endUs) {
            val length = source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
            val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) length else position + length)
            var result = Extractor.RESULT_CONTINUE
            while (result == Extractor.RESULT_CONTINUE && videoUs < endUs) {
                result = extractor.read(input, holder)
                val map = seekMap
                if (!seeked && map != null) { // moov read: jump to the resume point, as a resumed play does
                    seeked = true
                    val point = map.getSeekPoints(fromUs).first
                    extractor.seek(point.position, point.timeUs)
                    holder.position = point.position
                    result = Extractor.RESULT_SEEK
                    println("moov: $opens opens, ${bytes / 1_000_000} MB; seek to ${point.timeUs / 1_000_000} s at ${point.position}")
                    opens = 0; bytes = 0; startedFrom = System.nanoTime()
                }
            }
            val next = if (result == Extractor.RESULT_SEEK) holder.position else input.position
            if (System.getenv("DDD_LOG") != null && seeked) println("visit %.4f-%.4f GB, then %s %.4f".format(position / 1e9, input.position / 1e9, if (result == Extractor.RESULT_SEEK) "seek" else "cont", next / 1e9))
            position = next
            source.close()
        }
        val s = (System.nanoTime() - startedFrom) / 1e9
        println("60 s of film: $opens requests, ${bytes / 1_000_000} MB read, %.1f s on the Mac (total %.1f s)".format(s, (System.nanoTime() - started) / 1e9))
        ReusingDataSource.closeParked()
    }
}
