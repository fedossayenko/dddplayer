package top.rootu.dddplayer.player

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.rootu.dddplayer.logic.DlnaEntry
import top.rootu.dddplayer.logic.DlnaLogic
import top.rootu.dddplayer.logic.DlnaServer
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Сетевая часть DLNA: поиск медиасерверов в локальной сети (SSDP) и чтение каталога (ContentDirectory Browse).
 */
class DlnaClient(context: Context) {

    private val appContext = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Медиасерверы, ответившие за [timeoutMs]. */
    suspend fun discover(timeoutMs: Int = 3000): List<DlnaServer> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        // Без блокировки многие Wi-Fi адаптеры отбрасывают multicast-ответы ради экономии энергии
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("dddplayer-dlna")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val request = DlnaLogic.searchRequest().toByteArray()
                val group = InetAddress.getByName(DlnaLogic.SSDP_ADDRESS)
                // Запрос дважды: UDP может потеряться
                repeat(2) { socket.send(DatagramPacket(request, request.size, group, DlnaLogic.SSDP_PORT)) }
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        DlnaLogic.parseLocation(String(packet.data, 0, packet.length))?.let { locations.add(it) }
                    } catch (_: SocketTimeoutException) {
                        // Ждем следующие ответы до конца времени поиска
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP search failed: ${e.message}")
        } finally {
            if (lock?.isHeld == true) lock.release()
        }

        coroutineScope {
            locations.map { location -> async { describe(location) } }.awaitAll().filterNotNull()
        }.distinctBy { it.controlUrl }.sortedBy { it.name.lowercase() }
    }

    /** Сервер по адресу описания устройства (для "Продолжить просмотр" без нового поиска). */
    suspend fun describe(location: String): DlnaServer? = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(location).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                DlnaLogic.parseDeviceDescription(response.body.string(), location)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device description failed for $location: ${e.message}")
            null
        }
    }

    /** Содержимое папки сервера (все страницы) или null при ошибке. */
    suspend fun browse(server: DlnaServer, objectId: String): List<DlnaEntry>? = withContext(Dispatchers.IO) {
        val result = mutableListOf<DlnaEntry>()
        try {
            var start = 0
            while (true) {
                val body = DlnaLogic.browseRequest(objectId, start, PAGE_SIZE)
                    .toRequestBody("text/xml; charset=\"utf-8\"".toMediaType())
                val request = Request.Builder()
                    .url(server.controlUrl)
                    .header("SOAPACTION", DlnaLogic.BROWSE_ACTION)
                    .post(body)
                    .build()
                val page = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    DlnaLogic.parseBrowseResponse(response.body.string())
                } ?: return@withContext null
                result.addAll(page.entries)
                start += PAGE_SIZE
                // Сервер отдал все или перестал отдавать элементы (аудио и прочее в page.entries уже отброшены)
                if (page.numberReturned == 0 || start >= page.totalMatches || result.size >= MAX_ENTRIES) break
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Browse failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "DlnaClient"
        private const val PAGE_SIZE = 200
        private const val MAX_ENTRIES = 5000
    }
}
