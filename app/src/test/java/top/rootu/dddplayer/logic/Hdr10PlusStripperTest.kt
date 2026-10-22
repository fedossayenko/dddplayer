package top.rootu.dddplayer.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Hdr10PlusStripperTest {

    private val sc = bytes(0, 0, 0, 1)

    // Заголовки NAL HEVC: тип << 1, второй байт nuh_temporal_id_plus1 = 1
    private val vpsHeader = bytes(0x40, 0x01)
    private val seiHeader = bytes(39 shl 1, 0x01)
    private val sliceHeader = bytes(0x26, 0x01) // IDR_W_RADL (19)

    // Сообщение SEI user_data_registered_itu_t_t35 с HDR10+ (тип 4, размер 8)
    private val hdr10PlusMsg = bytes(4, 8, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01, 0x40)
    // То же для Dolby (провайдер 0x003B) - не трогаем
    private val dolbyT35Msg = bytes(4, 7, 0xB5, 0x00, 0x3B, 0x00, 0x00, 0x08, 0x01)
    // Mastering display (тип 137) - короткая заглушка
    private val masteringMsg = bytes(137, 3, 0x11, 0x22, 0x33)
    private val trailing = bytes(0x80)

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun hevc(vararg parts: ByteArray): ByteArray = parts.reduce { a, b -> a + b }

    private fun stripHevc(sample: ByteArray): Pair<ByteArray, Int> {
        val out = Hdr10PlusStripper.Output(16)
        val removed = Hdr10PlusStripper.stripHevc(sample, 0, sample.size, out)
        return out.data.copyOf(out.length) to removed
    }

    private fun stripAv1(sample: ByteArray): Pair<ByteArray, Int> {
        val out = Hdr10PlusStripper.Output(16)
        val removed = Hdr10PlusStripper.stripAv1(sample, 0, sample.size, out)
        return out.data.copyOf(out.length) to removed
    }

    @Test
    fun `hevc sei with only hdr10plus is dropped`() {
        val slice = sliceHeader + bytes(0xAF, 0x00, 0x00, 0x01, 0x55) // внутри слайса "00 00 01" не трогаем
        val sample = hevc(sc, vpsHeader, bytes(0x0C), sc, seiHeader, hdr10PlusMsg, trailing, sc, slice)
        val (result, removed) = stripHevc(sample)
        assertEquals(1, removed)
        assertArrayEquals(hevc(sc, vpsHeader, bytes(0x0C), sc, slice), result)
    }

    @Test
    fun `hevc mixed sei keeps other messages`() {
        val sample = hevc(sc, seiHeader, masteringMsg, hdr10PlusMsg, trailing, sc, sliceHeader, bytes(0x01))
        val (result, removed) = stripHevc(sample)
        assertEquals(1, removed)
        assertArrayEquals(hevc(sc, seiHeader, masteringMsg, trailing, sc, sliceHeader, bytes(0x01)), result)
    }

    @Test
    fun `hevc without hdr10plus is unchanged`() {
        val sample = hevc(sc, seiHeader, masteringMsg, dolbyT35Msg, trailing, sc, sliceHeader, bytes(0x01, 0x02))
        val (result, removed) = stripHevc(sample)
        assertEquals(0, removed)
        assertArrayEquals(sample, result)
    }

    @Test
    fun `hevc sei with emulation prevention is rebuilt correctly`() {
        // Сообщение с нулями в payload: в NAL они экранированы байтом 03
        val zerosMsgRbsp = bytes(5, 4, 0x00, 0x00, 0x01, 0x07)
        val zerosMsgEscaped = bytes(5, 4, 0x00, 0x00, 0x03, 0x01, 0x07)
        val sample = hevc(sc, seiHeader, zerosMsgEscaped, hdr10PlusMsg, trailing, sc, sliceHeader)
        val (result, removed) = stripHevc(sample)
        assertEquals(1, removed)
        assertArrayEquals(hevc(sc, seiHeader, zerosMsgEscaped, trailing, sc, sliceHeader), result)
        // RBSP без экранирования совпадает с исходным сообщением
        assertEquals(zerosMsgRbsp.size + 1, zerosMsgEscaped.size)
    }

    @Test
    fun `hevc length prefixed nals`() {
        fun nal(body: ByteArray) = bytes(0, 0, 0, body.size) + body
        val sei = seiHeader + hdr10PlusMsg + trailing
        val slice = sliceHeader + bytes(0x10, 0x20)
        val sample = nal(vpsHeader + bytes(0x0C)) + nal(sei) + nal(slice)
        val (result, removed) = stripHevc(sample)
        assertEquals(1, removed)
        assertArrayEquals(nal(vpsHeader + bytes(0x0C)) + nal(slice), result)
    }

    @Test
    fun `av1 hdr10plus metadata obu is dropped`() {
        val temporalDelimiter = bytes(0x12, 0x00)
        // metadata OBU: заголовок (тип 5, есть размер), размер, metadata_type 4, T.35 HDR10+
        val hdr10PlusObu = bytes(0x2A, 8, 0x04, 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, 0x01)
        // metadata OBU с HDR CLL (metadata_type 1) остается
        val cllObu = bytes(0x2A, 5, 0x01, 0x03, 0xE8, 0x01, 0x90)
        val frameObu = bytes(0x32, 3, 0x10, 0x00, 0x00)
        val sample = temporalDelimiter + cllObu + hdr10PlusObu + frameObu
        val (result, removed) = stripAv1(sample)
        assertEquals(1, removed)
        assertArrayEquals(temporalDelimiter + cllObu + frameObu, result)
    }

    @Test
    fun `av1 without hdr10plus is unchanged`() {
        val sample = bytes(0x12, 0x00, 0x32, 3, 0x10, 0x00, 0x00)
        val (result, removed) = stripAv1(sample)
        assertEquals(0, removed)
        assertArrayEquals(sample, result)
    }
}
