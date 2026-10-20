package top.rootu.dddplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PrefetchingDataSourceTest {

    private val url = "http://example.com/live/index.m3u8"
    private val finalUri = Uri.parse("http://cdn.example.com/live/index.m3u8")
    private val data = "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()

    private fun response() = PrefetchedResponse(data, finalUri, mapOf("Content-Type" to listOf("application/x-mpegurl")))

    /** Источник сети, к которому обращаться нельзя: ответ должен прийти из кэша пробы. */
    private class FailingUpstream : DataSource {
        var opened = false
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long {
            opened = true
            return 0
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun close() {}
    }

    private fun readAll(source: DataSource): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4)
        while (true) {
            val count = source.read(buffer, 0, buffer.size)
            if (count == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    @Test
    fun `prefetched response is served once without a network request`() {
        val store = PrefetchStore().apply { put(url, response()) }
        val upstream = FailingUpstream()
        val source = PrefetchingDataSource(upstream, store)

        assertEquals(data.size.toLong(), source.open(DataSpec(Uri.parse(url))))
        assertArrayEquals(data, readAll(source))
        // Относительные пути HLS разрешаются от адреса после редиректа
        assertEquals(finalUri, source.uri)
        assertEquals(listOf("application/x-mpegurl"), source.responseHeaders["Content-Type"])
        source.close()
        assertEquals(false, upstream.opened)

        // Повторное открытие идет в сеть (одноразовые токены уже использованы)
        source.open(DataSpec(Uri.parse(url)))
        assertEquals(true, upstream.opened)
    }

    @Test
    fun `reading from a position`() {
        val store = PrefetchStore().apply { put(url, response()) }
        val source = PrefetchingDataSource(FailingUpstream(), store)

        val spec = DataSpec.Builder().setUri(Uri.parse(url)).setPosition(8).build()
        assertEquals((data.size - 8).toLong(), source.open(spec))
        assertArrayEquals(data.copyOfRange(8, data.size), readAll(source))
    }

    @Test
    fun `stale responses are not served`() {
        val store = PrefetchStore().apply { put(url, response()) }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31))
        assertNull(store.take(url))
    }

    @Test
    fun `fresh response is taken only once`() {
        val store = PrefetchStore().apply { put(url, response()) }
        assertNotNull(store.take(url))
        assertNull(store.take(url))
    }
}
