package top.rootu.dddplayer.logic

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class M3uParserTest {

    private val base = Uri.parse("http://example.com/lists/tv.m3u")

    @Test
    fun `channel list is a playlist, HLS is not`() {
        assertTrue(M3uParser.isPlaylist("#EXTM3U\n#EXTINF:-1,Channel\nhttp://example.com/1.ts\n"))
        assertFalse(
            M3uParser.isPlaylist(
                "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nsegment1.ts\n"
            )
        )
        assertFalse(M3uParser.isPlaylist("#EXTM3U\nhttp://example.com/1.ts\n"))
    }

    @Test
    fun `title may contain commas, attributes are parsed`() {
        val content = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="http://example.com/logo.png" group-title="News, World",Channel, One
            http://example.com/one.ts
        """.trimIndent()

        val item = M3uParser.parse(content, base, emptyMap()).single()
        assertEquals("Channel, One", item.title)
        assertEquals("News, World", item.group)
        assertEquals("http://example.com/logo.png", item.posterUri.toString())
        assertEquals("http://example.com/one.ts", item.uri.toString())
    }

    @Test
    fun `EXTGRP sets the default group, group-title overrides it`() {
        val content = """
            #EXTM3U
            #EXTGRP:Movies
            #EXTINF:-1,First
            http://example.com/1.ts
            #EXTINF:-1 group-title="Sport",Second
            http://example.com/2.ts
            #EXTINF:-1,Third
            http://example.com/3.ts
        """.trimIndent()

        val items = M3uParser.parse(content, base, emptyMap())
        assertEquals(listOf("Movies", "Sport", "Movies"), items.map { it.group })
    }

    @Test
    fun `item headers come from EXTVLCOPT and EXTHTTP and apply to one item`() {
        val content = """
            #EXTM3U
            #EXTINF:-1,With headers
            #EXTVLCOPT:http-user-agent=TestAgent
            #EXTVLCOPT:http-referrer=http://example.com/
            #EXTHTTP:{"Cookie":"a=1"}
            http://example.com/1.ts
            #EXTINF:-1,Plain
            http://example.com/2.ts
        """.trimIndent()

        val parent = mapOf("X-Token" to "abc")
        val items = M3uParser.parse(content, base, parent)
        assertEquals(
            mapOf("X-Token" to "abc", "User-Agent" to "TestAgent", "Referer" to "http://example.com/", "Cookie" to "a=1"),
            items[0].headers
        )
        assertEquals(parent, items[1].headers)
    }

    @Test
    fun `relative links are resolved against the playlist url`() {
        val content = "#EXTM3U\n#EXTINF:-1,Relative\nvideo/1.mp4\n#EXTINF:-1,\nhttp://other.com/2.mp4\n"
        val items = M3uParser.parse(content, base, emptyMap())
        assertEquals("http://example.com/lists/video/1.mp4", items[0].uri.toString())
        assertEquals("http://other.com/2.mp4", items[1].uri.toString())
        // Пустое название - null (UI покажет имя из ссылки)
        assertNull(items[1].title)
    }

    @Test
    fun `playlist content types are not treated as media`() {
        // TorrServer отдает плейлист "Играть все" как audio/x-mpegurl
        assertFalse(M3uParser.isMediaContentType("audio/x-mpegurl"))
        assertFalse(M3uParser.isMediaContentType("application/vnd.apple.mpegurl; charset=utf-8"))
        assertFalse(M3uParser.isMediaContentType("Audio/MPEGURL"))
        assertFalse(M3uParser.isMediaContentType("text/plain"))
        assertFalse(M3uParser.isMediaContentType(null))
        assertTrue(M3uParser.isMediaContentType("video/mp4"))
        assertTrue(M3uParser.isMediaContentType("audio/mpeg"))
    }
}
