package top.rootu.dddplayer.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Сетевой источник, который не открывает новое соединение на каждый прыжок экстрактора.
 * Каждое соединение с облаком (TorBox) - это 0.3-0.8 с ожидания первого байта, а экстракторы прыгают часто:
 * MKV и MP4 с индексом в конце читают начало, индекс в конце и возвращаются к началу; MP4 с десятками дорожек
 * субтитров читает их пустые 2-байтовые сэмплы по времени, а лежат они в файле в обратном порядке - каждый шаг
 * назад на 2 байта был новым запросом (East of Eden: 32 запроса подряд, 26 с без видео).
 * Здесь соединение после close() остается открытым (паркуется) на IDLE_MS: возврат к нему, шаг назад в пределах
 * последних WINDOW_MAX байт или вперед до SKIP_AHEAD байт обходятся без запроса. Новое соединение открывается
 * на BACK байт раньше запрошенного, чтобы шаги назад сразу попадали в окно.
 * Только GET без длины запроса (так читает файлы ProgressiveMediaPeriod) и только файлы с известным размером;
 * остальное (HLS с диапазонами, POST, эфир без Content-Length) - как раньше.
 * Припаркованные соединения общие для всех экземпляров (Media3 создает источник на каждый элемент плейлиста):
 * другой адрес закрывает их сразу, а если сервер не дал новое соединение (у многих IPTV одно на аккаунт, а наше
 * еще на парковке), они закрываются и запрос повторяется один раз.
 * Параллельные запросы не помогают: TorBox отдает 15 МБ/с в одно соединение, а 2-8 частей ждут самый медленный ответ.
 */
class ReusingDataSource(
    // Настройка reuse_connections (по умолчанию выключена): иначе каждый open - свой запрос, как раньше
    private val enabled: () -> Boolean = { true },
    private val factory: DataSource.Factory
) : DataSource {

    private data class Key(val uri: String, val headers: Map<String, String>)

    private class Connection(val key: Key, val upstream: DataSource, start: Long, val end: Long) {
        // Кольцо последних прочитанных байт: растет вместе с прочитанным до WINDOW_MAX, так что соединение
        // субтитров (несколько КБ) остается маленьким, а соединение видео держит последние WINDOW_MAX
        private var window = ByteArray(WINDOW_MIN)
        private val first = start
        var position = start // следующий байт из сети
        var lastUsed = 0L
        var reusable = true // false: ошибка чтения или конец файла

        fun windowStart() = max(first, position - window.size)

        fun pull(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                reusable = false
                throw e
            }
            if (count == C.RESULT_END_OF_INPUT) {
                reusable = false
                return count
            }
            grow(position + count - first)
            // Последние байты - в кольцо окна
            val from = offset + max(0, count - window.size)
            put(position + (from - offset), buffer, from, offset + count - from)
            position += count
            return count
        }

        /** Байт в окне сейчас (для общего лимита припаркованных). */
        val windowBytes get() = window.size

        /** Окно до [needed] байт (не больше WINDOW_MAX), прочитанное раньше остается на своих местах. */
        private fun grow(needed: Long) {
            if (needed <= window.size || window.size >= WINDOW_MAX) return
            var size = window.size.toLong()
            while (size < needed && size < WINDOW_MAX) size *= 2
            val old = window
            val next = ByteArray(min(size, WINDOW_MAX.toLong()).toInt())
            // Кольцо в кольцо, без промежуточной копии (на 8 -> 12 МБ она давала бы лишние 8 МБ кучи)
            val start = windowStart()
            var at = start
            while (at < position) {
                val from = (at % old.size).toInt()
                val to = (at % next.size).toInt()
                val chunk = minOf(position - at, (old.size - from).toLong(), (next.size - to).toLong()).toInt()
                System.arraycopy(old, from, next, to, chunk)
                at += chunk
            }
            window = next
        }

        private fun put(at: Long, source: ByteArray, from: Int, count: Int) {
            var done = 0
            while (done < count) {
                val index = ((at + done) % window.size).toInt()
                val chunk = min(count - done, window.size - index)
                System.arraycopy(source, from + done, window, index, chunk)
                done += chunk
            }
        }

        /** Байты из окна начиная с [at] (windowStart() <= at < position). */
        fun copy(at: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val index = (at % window.size).toInt()
            val count = minOf(length.toLong(), position - at, (window.size - index).toLong()).toInt()
            System.arraycopy(window, index, buffer, offset, count)
            return count
        }

        /** Дочитывает до [target], выбрасывая байты. false - соединение не дошло (ошибка или конец). */
        fun skipTo(target: Long): Boolean {
            if (position >= target) return true
            val scratch = ByteArray(64 * 1024)
            while (position < target) {
                if (pull(scratch, 0, min(scratch.size.toLong(), target - position).toInt()) == C.RESULT_END_OF_INPUT) return false
            }
            return true
        }
    }

    private val listeners = mutableListOf<TransferListener>()
    private var current: Connection? = null
    private var direct: DataSource? = null
    private var readAt = 0L

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme?.lowercase()
        // FLAG_ALLOW_GZIP - манифесты и плейлисты (DASH, HLS): они меняются, и копия из памяти отдала бы старый
        // (эфирный MPD с Content-Length, который парсер не дочитывает до конца). Файлы видео идут без него
        if (!enabled() || dataSpec.isFlagSet(DataSpec.FLAG_ALLOW_GZIP) || dataSpec.length != C.LENGTH_UNSET.toLong() ||
            dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || (scheme != "http" && scheme != "https")
        ) {
            val source = newUpstream()
            direct = source
            return source.open(dataSpec)
        }
        val key = Key(dataSpec.uri.toString(), dataSpec.httpRequestHeaders)
        val target = dataSpec.position
        val now = SystemClock.elapsedRealtime()
        // Другой адрес (следующий канал IPTV, следующая серия, сегмент HLS) - старые соединения сразу закрываются:
        // у многих IPTV одно соединение на аккаунт
        val (found, other) = synchronized(lock) {
            val other = parked.filter { it.key != key }.also { parked.removeAll(it) }
            // Самое дешевое: то, до которого читать меньше всего (цель в окне - ноль)
            parked.filter {
                target >= it.windowStart() && target <= it.position + SKIP_AHEAD && now - it.lastUsed < IDLE_MS
            }.minByOrNull { max(0L, target - it.position) }?.also { parked.remove(it) } to other
        }
        other.forEach { DataSourceUtil.closeQuietly(it.upstream) }
        val connection = found?.takeIf { reached(it, target) } ?: try {
            connect(dataSpec, key, target)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // Сервер не дал еще одно соединение (ответил кодом ошибки), а наше (голова файла) ждет на парковке:
            // закрываем и еще раз. Отмена или таймаут - не отказ сервера, их не повторяем
            if (!closeParked()) throw e
            connect(dataSpec, key, target)
        }
        current = connection
        readAt = target
        return if (connection.end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else connection.end - target
    }

    /** Припаркованное соединение дошло до [target]; иначе оно закрыто и нужен новый запрос. */
    private fun reached(connection: Connection, target: Long): Boolean {
        try {
            if (connection.skipTo(target)) return true
        } catch (e: IOException) {
            DataSourceUtil.closeQuietly(connection.upstream)
            // OkHttp заворачивает прерывание в HttpDataSourceException. Таймаут чтения (тоже InterruptedIOException) -
            // не отмена: такое соединение заменяем новым
            val cause = e.cause
            if (e is InterruptedIOException && e !is SocketTimeoutException ||
                cause is InterruptedIOException && cause !is SocketTimeoutException) throw e
            return false
        }
        DataSourceUtil.closeQuietly(connection.upstream)
        return false
    }

    private fun connect(dataSpec: DataSpec, key: Key, target: Long): Connection {
        val start = max(0L, target - BACK)
        val upstream = newUpstream()
        val length = upstream.open(dataSpec.buildUpon().setPosition(start).build())
        val end = if (length == C.LENGTH_UNSET.toLong()) length else start + length
        val connection = Connection(key, upstream, start, end)
        try {
            if (!connection.skipTo(target)) connection.reusable = false
        } catch (e: IOException) {
            DataSourceUtil.closeQuietly(upstream)
            throw e
        }
        return connection
    }

    private fun newUpstream(): DataSource = factory.createDataSource().also { s -> listeners.forEach(s::addTransferListener) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        direct?.let { return it.read(buffer, offset, length) }
        val connection = checkNotNull(current)
        if (length == 0) return 0
        val count = if (readAt < connection.position) connection.copy(readAt, buffer, offset, length)
        else connection.pull(buffer, offset, length)
        if (count != C.RESULT_END_OF_INPUT) readAt += count
        return count
    }

    override fun getUri(): Uri? = direct?.uri ?: current?.upstream?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        direct?.responseHeaders ?: current?.upstream?.responseHeaders ?: emptyMap()

    override fun close() {
        direct?.let {
            direct = null
            it.close()
            return
        }
        val connection = current ?: return
        current = null
        // Без длины - эфир (IPTV): его не держим, данные идут и в паузе
        if (!connection.reusable || connection.end == C.LENGTH_UNSET.toLong()) {
            connection.upstream.close()
            return
        }
        connection.lastUsed = SystemClock.elapsedRealtime()
        val evicted = synchronized(lock) {
            parked.add(0, connection)
            val out = mutableListOf<Connection>()
            // Лимит числа и общий лимит окон: перемотка пультом открывает по соединению на каждый шаг
            while (parked.size > 1 && (parked.size > MAX_PARKED || parked.sumOf { it.windowBytes.toLong() } > PARKED_BUDGET)) {
                out += parked.removeAt(parked.lastIndex)
            }
            out
        }
        evicted.forEach { DataSourceUtil.closeQuietly(it.upstream) }
        // Source не освобождают явно: соединения, к которым не вернулись, закрывает таймер
        closer.schedule({ closeIdle() }, IDLE_MS + 100, TimeUnit.MILLISECONDS)
    }

    class Factory(
        private val upstreamFactory: DataSource.Factory,
        private val enabled: () -> Boolean = { true }
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = ReusingDataSource(enabled, upstreamFactory)
    }

    companion object {
        const val WINDOW_MIN = 256 * 1024
        // MP4 с 7 дорожками звука: Mp4Extractor читает куски дорожек по очереди и возвращается назад до ~10 МБ
        // (MAXIMUM_READ_AHEAD_BYTES_STREAM). С окном 256 КБ каждый такой шаг был новым соединением, которое заново
        // качало те же байты: 4-6x трафика, и фильм 25 Мбит/с не успевал буферизоваться на линии 57 Мбит/с.
        val WINDOW_MAX = (Runtime.getRuntime().maxMemory() / 32).coerceIn(WINDOW_MIN.toLong(), 12L * 1024 * 1024).toInt()
        // Окна припаркованных вместе: резерв кучи под остальное приложение (NetworkBufferLoadControl) не съедается
        val PARKED_BUDGET = 3L * WINDOW_MAX
        const val BACK = 64 * 1024L
        // ~0.27 с чтения на 15 МБ/с против 0.3-0.8 с нового запроса, а часть уже лежит в сокете с парковки.
        // На медленной линии (2 МБ/с) это ~2 с, дороже нового запроса: цена сознательная, MP4 с дорожками вразброс
        // без нее открывает запрос на каждый прыжок
        // MP4 с дорожками вразброс прыгает вперед на 1-3 МБ (звук 7 языков, субтитры через 1.6 МБ)
        const val SKIP_AHEAD = 4L * 1024 * 1024
        const val IDLE_MS = 10_000L // индекс в конце скачивается и разбирается ~3 с, потом возврат к началу
        const val MAX_PARKED = 8 // видео со звуком и субтитры в 4-6 местах файла: у каждого места свое соединение

        private val closer = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ReusingDataSource").apply { isDaemon = true }
        }

        // Все экземпляры, под lock: лимит соединений у сервера общий, а источник у каждого элемента свой
        private val parked = ArrayList<Connection>()
        private val lock = Any()

        /** Закрывает все припаркованные соединения; false - их не было. */
        internal fun closeParked(): Boolean {
            val all = synchronized(lock) { parked.toList().also { parked.clear() } }
            all.forEach { DataSourceUtil.closeQuietly(it.upstream) }
            return all.isNotEmpty()
        }

        private fun closeIdle() {
            val now = SystemClock.elapsedRealtime()
            val idle = synchronized(lock) {
                parked.filter { now - it.lastUsed >= IDLE_MS }.also { parked.removeAll(it) }
            }
            idle.forEach { DataSourceUtil.closeQuietly(it.upstream) }
        }
    }
}
