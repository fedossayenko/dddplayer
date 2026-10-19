package top.rootu.dddplayer.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Ответ, уже скачанный при проверке ссылки (например, HLS плейлист, который проверяли на M3U).
 * Плеер получает эти байты вместо повторного запроса: так не теряются одноразовые токены в ссылках.
 */
class PrefetchedResponse(
    val data: ByteArray,
    val finalUri: Uri, // URI после редиректов: относительные пути в HLS разрешаются от него
    val headers: Map<String, List<String>>
) {
    val createdAt: Long = SystemClock.elapsedRealtime()
}

class PrefetchStore {
    private val responses = ConcurrentHashMap<String, PrefetchedResponse>()

    fun put(uri: String, response: PrefetchedResponse) {
        responses[uri] = response
    }

    /** Забирает ответ (одноразово). Устаревшие ответы не отдаются. */
    fun take(uri: String): PrefetchedResponse? {
        val response = responses.remove(uri) ?: return null
        return if (SystemClock.elapsedRealtime() - response.createdAt <= MAX_AGE_MS) response else null
    }

    fun clear() = responses.clear()

    private companion object {
        const val MAX_AGE_MS = 30_000L
    }
}

class PrefetchingDataSource(
    private val upstream: DataSource,
    private val store: PrefetchStore
) : DataSource {

    private var prefetched: PrefetchedResponse? = null
    private var readPosition = 0
    private var bytesRemaining = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val response = store.take(dataSpec.uri.toString())
        if (response != null && dataSpec.position <= response.data.size) {
            prefetched = response
            readPosition = dataSpec.position.toInt()
            val available = response.data.size - readPosition
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                min(dataSpec.length, available.toLong()).toInt()
            } else {
                available
            }
            return bytesRemaining.toLong()
        }
        prefetched = null
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val response = prefetched ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT
        val count = min(length, bytesRemaining)
        System.arraycopy(response.data, readPosition, buffer, offset, count)
        readPosition += count
        bytesRemaining -= count
        return count
    }

    override fun getUri(): Uri? = prefetched?.finalUri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        prefetched?.headers ?: upstream.responseHeaders

    override fun close() {
        if (prefetched != null) {
            prefetched = null
        } else {
            upstream.close()
        }
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val store: PrefetchStore
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PrefetchingDataSource(upstreamFactory.createDataSource(), store)
    }
}
