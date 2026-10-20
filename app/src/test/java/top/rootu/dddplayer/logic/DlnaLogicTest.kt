package top.rootu.dddplayer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaLogicTest {

    @Test
    fun `location is read from an ssdp response`() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.10:8200/rootDesc.xml\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"
        assertEquals("http://192.168.1.10:8200/rootDesc.xml", DlnaLogic.parseLocation(response))
        assertNull(DlnaLogic.parseLocation("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun `device description gives the name and the content directory control url`() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <friendlyName>NAS: minidlna</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <controlURL>/ctl/ConnectionMgr</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                    <controlURL>/ctl/ContentDir</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>"""
        val server = DlnaLogic.parseDeviceDescription(xml, "http://192.168.1.10:8200/rootDesc.xml")!!
        assertEquals("NAS: minidlna", server.name)
        assertEquals("http://192.168.1.10:8200/ctl/ContentDir", server.controlUrl)
    }

    @Test
    fun `device without content directory is skipped`() {
        val xml = """<root xmlns="urn:schemas-upnp-org:device-1-0"><device><friendlyName>TV</friendlyName>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/av</controlURL></service></serviceList></device></root>"""
        assertNull(DlnaLogic.parseDeviceDescription(xml, "http://10.0.0.2:9000/desc.xml"))
    }

    @Test
    fun `browse response gives folders and videos with subtitles`() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <container id="64$1" parentID="64" childCount="3"><dc:title>Series &amp; Shows</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>
            <item id="64$2" parentID="64"><dc:title>Movie</dc:title><upnp:class>object.item.videoItem</upnp:class>
              <res size="1048576" protocolInfo="http-get:*:video/x-matroska:*">http://192.168.1.10:8200/MediaItems/22.mkv</res>
              <res protocolInfo="http-get:*:text/srt:*">http://192.168.1.10:8200/Captions/22.srt</res>
            </item>
            <item id="64$3" parentID="64"><dc:title>Song</dc:title><res protocolInfo="http-get:*:audio/mpeg:*">http://x/1.mp3</res></item>
            </DIDL-Lite>"""
        val escaped = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
            <Result>$escaped</Result><NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches></u:BrowseResponse>
            </s:Body></s:Envelope>"""
        val result = DlnaLogic.parseBrowseResponse(soap)!!
        assertEquals(3, result.totalMatches)
        assertEquals(3, result.numberReturned)
        assertEquals(2, result.entries.size)
        val folder = result.entries[0]
        assertTrue(folder.isContainer)
        assertEquals("64$1", folder.id)
        assertEquals("Series & Shows", folder.title)
        val movie = result.entries[1]
        assertEquals("http://192.168.1.10:8200/MediaItems/22.mkv", movie.url)
        assertEquals("video/x-matroska", movie.mimeType)
        assertEquals(1048576L, movie.size)
        assertEquals(listOf("http://192.168.1.10:8200/Captions/22.srt"), movie.subtitleUrls)
    }

    @Test
    fun `browse request escapes the object id`() {
        val request = DlnaLogic.browseRequest("a<b&c", start = 100, count = 50)
        assertTrue(request.contains("<ObjectID>a&lt;b&amp;c</ObjectID>"))
        assertTrue(request.contains("<StartingIndex>100</StartingIndex>"))
        assertTrue(request.contains("<RequestedCount>50</RequestedCount>"))
    }
}
