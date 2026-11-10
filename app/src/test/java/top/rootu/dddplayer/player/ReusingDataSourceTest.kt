package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.io.InterruptedIOException

@RunWith(RobolectricTestRunner::class)
class ReusingDataSourceTest {

    // Больше SKIP_AHEAD: индекс в конце - дальний прыжок
    private fun fileOf(size: Int) = ByteArray(size) { (it * 31 + it / 7).toByte() }
    private var file = fileOf(6 * ReusingDataSource.SKIP_AHEAD.toInt())
    private val uri = Uri.parse("https://store.example/dld/film.mp4")
    private val opens = mutableListOf<Long>() // позиции запросов в "сеть"
    private var failReads = false
    private var limit = Int.MAX_VALUE // соединений сразу, как у IPTV с одним на аккаунт
    private var live = 0
    private var pulled = 0L // байт, отданных "сетью"

    /** "Сеть": каждый open - запрос с Range, чтение отдает файл с этой позиции. */
    private inner class Network : DataSource {
        private var position = 0
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            if (live == limit) throw HttpDataSource.InvalidResponseCodeException(458, "Too many connections", null,
                emptyMap(), dataSpec, ByteArray(0))
            live++
            opens += dataSpec.position
            position = dataSpec.position.toInt()
            return file.size - dataSpec.position
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (failReads) throw IOException("connection reset")
            if (position == file.size) return C.RESULT_END_OF_INPUT
            val count = minOf(length, file.size - position)
            System.arraycopy(file, position, buffer, offset, count)
            position += count
            pulled += count
            return count
        }
        override fun getUri(): Uri = uri
        override fun close() {
            closes++
            live--
        }
    }

    private var closes = 0

    private val source = ReusingDataSource { Network() }

    @After
    fun closeParked() {
        ReusingDataSource.closeParked() // общие для всех экземпляров
    }

    private fun readAt(position: Long, count: Int): ByteArray {
        assertEquals(file.size - position, source.open(DataSpec.Builder().setUri(uri).setPosition(position).build()))
        val out = ByteArray(count)
        var done = 0
        while (done < count) done += source.read(out, done, count - done)
        source.close()
        return out
    }

    private fun assertReads(position: Long, count: Int) =
        assertArrayEquals(file.copyOfRange(position.toInt(), position.toInt() + count), readAt(position, count))

    @Test
    fun `steps back by 2 bytes, as the MP4 extractor reads out-of-order subtitles, need one request`() {
        for (position in 1_215_000L downTo 1_214_960L step 2) assertReads(position, 2)
        assertEquals(listOf(1_215_000L - ReusingDataSource.BACK), opens)
    }

    @Test
    fun `head, index at the end, back to the head reuses the head connection`() {
        val index = file.size - 300_000L
        assertReads(0, 40)
        assertReads(index, 300_000) // через границу кольца окна
        assertReads(117, 1000)
        assertReads(file.size - 5000L, 1000) // назад в окно индекса
        assertEquals(listOf(0L, index - ReusingDataSource.BACK), opens)
    }

    @Test
    fun `another address closes the parked connection at once, also from the next item's source`() {
        assertReads(0, 10)
        assertEquals(0, closes)
        val next = ReusingDataSource { Network() } // Media3: свой источник у каждого элемента плейлиста
        next.open(DataSpec(Uri.parse("https://iptv.example/channel/2.ts")))
        assertEquals(1, closes)
        next.close()
    }

    @Test
    fun `a server allowing one connection gets the parked one closed and the request again`() {
        limit = 1
        assertReads(0, 10)
        assertReads(file.size - 1000L, 1000) // индекс в конце: голова еще на парковке
        assertEquals(listOf(0L, file.size - 1000L - ReusingDataSource.BACK), opens)
        assertEquals(1, live)
    }

    @Test
    fun `off by the setting, and manifests or playlists (gzip allowed) are never served from memory`() {
        val off = ReusingDataSource({ false }) { Network() }
        repeat(2) {
            off.open(DataSpec.Builder().setUri(uri).build())
            off.close()
        }
        val manifest = DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build()
        repeat(2) {
            source.open(manifest)
            source.close()
        }
        assertEquals(listOf(0L, 0L, 0L, 0L), opens)
        assertEquals(0, live)
    }

    @Test
    fun `a cancelled or timed-out open is not repeated and keeps the parked connection`() {
        assertReads(0, 10)
        val cancelled = HttpDataSource.HttpDataSourceException(InterruptedIOException(), DataSpec(uri),
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        val failing = ReusingDataSource { object : DataSource by Network() {
            override fun open(dataSpec: DataSpec): Long = throw cancelled
        } }
        try {
            failing.open(DataSpec.Builder().setUri(uri).setPosition(file.size - 1000L).build())
            fail("expected the cancellation")
        } catch (e: HttpDataSource.HttpDataSourceException) {
            assertEquals(cancelled, e)
        }
        assertEquals(0, closes) // голова на парковке осталась
        assertReads(5, 5)
        assertEquals(listOf(0L), opens)
    }

    @Test
    fun `a far jump is a new request, a broken parked connection is replaced`() {
        val far = ReusingDataSource.SKIP_AHEAD + 100_000
        assertReads(0, 10)
        assertReads(far, 10)
        failReads = true
        try {
            readAt(500, 10)
            fail("expected the reset to reach the player")
        } catch (_: IOException) {
            source.close() // так делает Media3 после ошибки open
        }
        failReads = false
        assertReads(500, 10)
        // 0: голова; far: прыжок; 0: попытка после сброса припаркованного; 0: повтор плеера
        assertEquals(listOf(0L, far - ReusingDataSource.BACK, 0L, 0L), opens)
    }

    @Test
    fun `MP4 with tracks in five far places read in turn keeps a connection for each`() {
        // Фильм 28 ГБ: субтитры к 25-й минуте лежат на 0.56-0.72 ГБ, видео и звук - на 4.3 ГБ
        val places = (0L until 5L).map { it * (ReusingDataSource.SKIP_AHEAD + 100_000) }
        for (round in 0 until 3) places.forEach { assertReads(it + round * 300_000L, 10) }
        assertEquals(places.map { maxOf(0L, it - ReusingDataSource.BACK) }, opens)
    }

    @Test
    fun `MP4 audio tracks read in turn step back up to the window from memory, one request for the film`() {
        // Mp4Extractor с 7 дорожками звука: вперед через куски других дорожек, потом назад за ними (до ~10 МБ)
        val forward = ReusingDataSource.SKIP_AHEAD - 500_000
        val back = ReusingDataSource.WINDOW_MAX - 300_000L // шаг назад почти на все окно, какой бы ни была куча
        assertReads(0, 100_000)
        var far = 0L
        while (far + forward + 100_000 < file.size) {
            far += forward
            assertReads(far, 100_000)
            if (far >= back) assertReads(far - back, 100_000)
        }
        assertEquals(listOf(0L), opens)
    }

    @Test
    fun `the window grows from an unaligned start with a wrapped ring and a step back across the old edge is served from memory`() {
        val far = 3_000_001L
        assertReads(far, 10)
        // По 200 КБ: окно 256 КБ растет, когда кольцо уже обернулось (начало не кратно размеру окна)
        var at = far
        while (at < far + 1_500_000) { assertReads(at, 200_000); at += 200_000 }
        assertReads(far + 1_400_000 - 1_000_000, 100_000) // назад на 1 МБ: за прежней границей окна
        assertReads(far - 30_000, 1000) // и ниже начала запроса, но в пределах BACK
        assertEquals(listOf(far - ReusingDataSource.BACK), opens)
    }

    @Test
    fun `more far places than MAX_PARKED close the oldest connections`() {
        val step = ReusingDataSource.SKIP_AHEAD + 100_000
        file = fileOf(((ReusingDataSource.MAX_PARKED + 2) * step + step).toInt())
        val places = (0L until ReusingDataSource.MAX_PARKED + 2L).map { it * step }
        places.forEach { assertReads(it, 10) }
        assertEquals(places.size, opens.size)
        assertEquals(2, closes)
    }

    @Test
    fun `over the windows budget old windows shrink and the sockets stay`() {
        val saved = ReusingDataSource.parkedBudget
        try {
            ReusingDataSource.parkedBudget = 600_000
            val step = ReusingDataSource.SKIP_AHEAD + 1_500_000
            file = fileOf((5 * step).toInt())
            val places = (0L until 4L).map { it * step }
            // Каждое соединение читает 700 КБ: окно растет до 1 МБ
            places.forEach { assertReads(it, 700_000) }
            assertEquals(0, closes)
            assertEquals(4L * ReusingDataSource.WINDOW_MIN, ReusingDataSource.parkedWindowBytes()) // бюджет меньше минимума: все сжаты
            // Старое соединение по-прежнему принимает возврат без нового запроса
            assertReads(places[0] + 700_000, 10)
            // Окно снова выросло, но настоящих байт в нем только последние 256 КБ: ниже - новый запрос, не нули
            assertReads(places[0] + 100_000, 10)
            assertReads(places[0] + 650_000, 10)
            assertEquals(5, opens.size)
        } finally {
            ReusingDataSource.parkedBudget = saved
        }
    }

    @Test
    fun `of two parked connections the one needing less skipping is taken`() {
        val p = 1_000_000L
        val b = p + 2_000_000
        assertReads(b, 10) // B
        assertReads(p, 10) // A: ниже окна B, свое соединение, припаркована позже B
        val before = pulled
        assertReads(b + 5_000, 10) // подходят оба: у B до цели ~5 КБ, у A ~2 МБ
        assertTrue("took the far connection: ${pulled - before} bytes pulled", pulled - before < 100_000)
        assertEquals(2, opens.size)
    }
}
