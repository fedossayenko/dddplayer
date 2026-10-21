package top.rootu.dddplayer.logic

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

/** Медиасервер DLNA: имя и адрес службы каталога (ContentDirectory). */
data class DlnaServer(val name: String, val location: String, val controlUrl: String)

/** Папка (container) или видео (item) из каталога медиасервера. */
data class DlnaEntry(
    val id: String,
    val title: String,
    val isContainer: Boolean,
    val url: String? = null,
    val mimeType: String? = null,
    val size: Long = 0L,
    val subtitleUrls: List<String> = emptyList()
)

/**
 * Протокол DLNA/UPnP без сторонних библиотек: поиск серверов (SSDP), описание устройства,
 * запрос Browse службы ContentDirectory и разбор ответа DIDL-Lite.
 */
object DlnaLogic {

    const val SSDP_ADDRESS = "239.255.255.250"
    const val SSDP_PORT = 1900
    const val MEDIA_SERVER = "urn:schemas-upnp-org:device:MediaServer:1"

    /**
     * Что искать: часть серверов отвечает только на свой тип устройства, часть - только на службу каталога
     * или на корневое устройство. Лишние устройства отсеет parseDeviceDescription.
     */
    val SEARCH_TARGETS = listOf(
        MEDIA_SERVER,
        "urn:schemas-upnp-org:service:ContentDirectory:1",
        "upnp:rootdevice"
    )
    private const val CONTENT_DIRECTORY = "urn:schemas-upnp-org:service:ContentDirectory"
    const val BROWSE_ACTION = "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
    const val ROOT_ID = "0"

    /** Запрос поиска медиасерверов в сети. */
    fun searchRequest(target: String = MEDIA_SERVER, mx: Int = 2): String =
        "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: $mx\r\n" +
                "ST: $target\r\n\r\n"

    /** Адрес описания устройства (LOCATION) из ответа SSDP. */
    fun parseLocation(response: String): String? = response.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
        ?.takeIf { it.isNotEmpty() }

    /** Медиасервер из описания устройства или null, если у устройства нет службы каталога. */
    fun parseDeviceDescription(xml: String, location: String): DlnaServer? {
        val doc = parse(xml) ?: return null
        val name = doc.getElementsByTagNameLocal("friendlyName").firstOrNull()?.textContent?.trim().orEmpty()
        val base = doc.getElementsByTagNameLocal("URLBase").firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            ?: location
        val service = doc.getElementsByTagNameLocal("service").firstOrNull { service ->
            service.childText("serviceType")?.startsWith(CONTENT_DIRECTORY) == true
        } ?: return null
        val control = service.childText("controlURL") ?: return null
        return DlnaServer(name.ifEmpty { URI(location).host ?: location }, location, resolve(base, control))
    }

    /** Тело SOAP-запроса Browse: содержимое папки [objectId] с позиции [start]. */
    fun browseRequest(objectId: String, start: Int = 0, count: Int = 0): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
                "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                "<ObjectID>${escape(objectId)}</ObjectID>" +
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter>" +
                "<StartingIndex>$start</StartingIndex>" +
                "<RequestedCount>$count</RequestedCount>" +
                "<SortCriteria></SortCriteria>" +
                "</u:Browse></s:Body></s:Envelope>"

    /**
     * Ответ Browse: папки и видео (остальное отброшено), сколько элементов вернул сервер
     * и сколько всего в папке (для постраничной загрузки).
     */
    data class BrowseResult(val entries: List<DlnaEntry>, val numberReturned: Int, val totalMatches: Int)

    fun parseBrowseResponse(soap: String): BrowseResult? {
        val envelope = parse(soap) ?: return null
        val didl = envelope.getElementsByTagNameLocal("Result").firstOrNull()?.textContent ?: return null
        val total = envelope.getElementsByTagNameLocal("TotalMatches").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
        val returned = envelope.getElementsByTagNameLocal("NumberReturned").firstOrNull()?.textContent?.trim()?.toIntOrNull() ?: 0
        val doc = parse(didl) ?: return BrowseResult(emptyList(), returned, total)
        val entries = mutableListOf<DlnaEntry>()
        doc.getElementsByTagNameLocal("container").forEach { container ->
            val id = container.getAttribute("id")
            if (id.isNotEmpty()) entries.add(DlnaEntry(id, container.childText("title").orEmpty(), isContainer = true))
        }
        doc.getElementsByTagNameLocal("item").forEach { item ->
            val resources = item.childElements("res")
            // Видео: первый ресурс с video/* в protocolInfo (http-get:*:video/x-matroska:*)
            val video = resources.firstOrNull { mimeOf(it)?.startsWith("video/") == true } ?: return@forEach
            val subtitles = resources.filter { res ->
                val mime = mimeOf(res).orEmpty()
                mime.contains("srt") || mime.contains("subrip") || mime.contains("vtt") || mime.contains("ssa") || mime.contains("ass")
            }.mapNotNull { it.textContent?.trim()?.takeIf { url -> url.isNotEmpty() } }
            val url = video.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            entries.add(
                DlnaEntry(
                    id = item.getAttribute("id"),
                    title = item.childText("title").orEmpty(),
                    isContainer = false,
                    url = url,
                    mimeType = mimeOf(video),
                    size = video.getAttribute("size").toLongOrNull() ?: 0L,
                    subtitleUrls = subtitles
                )
            )
        }
        return BrowseResult(entries, returned, total)
    }

    private fun mimeOf(res: Element): String? =
        res.getAttribute("protocolInfo").split(':').getOrNull(2)?.lowercase()?.takeIf { it.isNotEmpty() && it != "*" }

    private fun resolve(base: String, path: String): String = try {
        URI(base).resolve(path).toString()
    } catch (e: Exception) {
        path
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun parse(xml: String): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml.trim())))
    } catch (e: Exception) {
        null
    }

    // Поиск по локальному имени: у серверов разные префиксы пространств имен (dc:title, upnp:class)
    private fun org.w3c.dom.Document.getElementsByTagNameLocal(name: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(name: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && (node.localName ?: node.nodeName) == name) result.add(node)
        }
        return result
    }

    private fun Element.childText(name: String): String? = childElements(name).firstOrNull()?.textContent?.trim()
}
