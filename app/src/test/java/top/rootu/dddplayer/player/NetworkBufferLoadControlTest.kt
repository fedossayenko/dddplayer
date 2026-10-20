package top.rootu.dddplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkBufferLoadControlTest {

    private fun mb(value: Long) = value * 1024 * 1024

    @Test
    fun `buffer leaves a reserve of half the heap within 64-160 MB`() {
        assertEquals(mb(36).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(100)))
        assertEquals(mb(96).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(192)))
        assertEquals(mb(352).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(512)))
        assertEquals(mb(864).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(1024)))
    }

    @Test
    fun `tiny heap still gets the minimum buffer`() {
        assertEquals(mb(16).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(32)))
        assertEquals(mb(16).toInt(), NetworkBufferLoadControl.maxBufferBytesForHeap(mb(70)))
    }

    @Test
    fun `huge heap is limited to Int range`() {
        assertEquals(Int.MAX_VALUE, NetworkBufferLoadControl.maxBufferBytesForHeap(mb(8192)))
    }
}
