package top.rootu.dddplayer.logic

/**
 * Вырезает динамические метаданные HDR10+ (SMPTE ST 2094-40) из сэмпла видео, чтобы оно играло как HDR10.
 * Как в Kodi ("Разрешенные форматы динамических метаданных HDR").
 *
 * HDR10+ передается сообщением ITU-T T.35: страна 0xB5, провайдер 0x003C, код 0x0001, приложение 4.
 * - HEVC: в prefix SEI (NAL 39), сообщение user_data_registered_itu_t_t35 (payloadType 4);
 * - AV1: в metadata OBU (тип 5) с metadata_type ITU-T T.35 (4).
 * Метаданные Dolby Vision в T.35 (провайдер 0x003B) не трогаем.
 *
 * Сканируется только начало кадра: prefix SEI идут до слайсов, после первого слайса остаток копируется целиком.
 */
object Hdr10PlusStripper {

    private const val HEVC_NAL_PREFIX_SEI = 39
    private const val HEVC_NAL_FIRST_NON_VCL = 32
    private const val SEI_USER_DATA_REGISTERED_T35 = 4
    private const val AV1_OBU_METADATA = 5
    private const val AV1_METADATA_ITUT_T35 = 4L
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Растущий буфер результата (переиспользуется между сэмплами). */
    class Output(initialSize: Int = 64 * 1024) {
        var data = ByteArray(initialSize)
            private set
        var length = 0
            private set

        fun reset() {
            length = 0
        }

        /** Откат к ранее записанной длине. */
        fun truncate(newLength: Int) {
            length = newLength
        }

        fun write(src: ByteArray, offset: Int, len: Int) {
            ensure(len)
            System.arraycopy(src, offset, data, length, len)
            length += len
        }

        fun write(b: Int) {
            ensure(1)
            data[length++] = b.toByte()
        }

        fun writeInt32At(pos: Int, value: Int) {
            data[pos] = (value ushr 24).toByte()
            data[pos + 1] = (value ushr 16).toByte()
            data[pos + 2] = (value ushr 8).toByte()
            data[pos + 3] = value.toByte()
        }

        private fun ensure(extra: Int) {
            if (length + extra > data.size) data = data.copyOf(maxOf(length + extra, data.size * 2))
        }
    }

    /** Полезная нагрузка T.35 с HDR10+ (начиная с кода страны). */
    private fun isHdr10PlusT35(buf: ByteArray, start: Int, end: Int): Boolean =
        end - start >= 6 &&
            buf[start] == 0xB5.toByte() &&
            buf[start + 1] == 0x00.toByte() && buf[start + 2] == 0x3C.toByte() &&
            buf[start + 3] == 0x00.toByte() && buf[start + 4] == 0x01.toByte() &&
            buf[start + 5] == 0x04.toByte()

    // ===================== HEVC =====================

    /**
     * HEVC-сэмпл в формате Annex B (так его отдают экстракторы Media3) или с 4-байтовыми длинами NAL.
     * @return число вырезанных сообщений HDR10+ (0 - сэмпл скопирован как есть)
     */
    fun stripHevc(src: ByteArray, offset: Int, length: Int, out: Output): Int {
        out.reset()
        val end = offset + length
        return if (startCodeLength(src, offset, end) > 0) stripHevcAnnexB(src, offset, end, out)
        else stripHevcLengthPrefixed(src, offset, end, out)
    }

    private fun stripHevcAnnexB(src: ByteArray, offset: Int, end: Int, out: Output): Int {
        var removed = 0
        var pos = offset // начало стартового кода текущего NAL
        while (pos < end) {
            val nalStart = pos + startCodeLength(src, pos, end)
            if (nalStart >= end || isVcl(src[nalStart])) {
                // Дошли до слайсов: HDR10+ дальше не бывает, остаток копируем целиком
                out.write(src, pos, end - pos)
                return removed
            }
            val nalEnd = findStartCode(src, nalStart, end)
            val before = out.length
            out.write(START_CODE, 0, START_CODE.size)
            val result = writeHevcNal(src, nalStart, nalEnd, out)
            if (result < 0) out.truncate(before)
            removed += kotlin.math.abs(result)
            pos = nalEnd
        }
        return removed
    }

    private fun stripHevcLengthPrefixed(src: ByteArray, offset: Int, end: Int, out: Output): Int {
        var removed = 0
        var pos = offset
        while (pos + 4 < end) {
            val nalLen = readInt32(src, pos)
            val nalStart = pos + 4
            if (nalLen <= 0 || nalLen > end - nalStart || isVcl(src[nalStart])) break
            val nalEnd = nalStart + nalLen
            val before = out.length
            out.write(src, pos, 4)
            val result = writeHevcNal(src, nalStart, nalEnd, out)
            if (result < 0) out.truncate(before) else out.writeInt32At(before, out.length - before - 4)
            removed += kotlin.math.abs(result)
            pos = nalEnd
        }
        // Слайсы или нераспознанные данные - как есть
        if (pos < end) out.write(src, pos, end - pos)
        return removed
    }

    private fun isVcl(nalHeader: Byte): Boolean = ((nalHeader.toInt() shr 1) and 0x3F) < HEVC_NAL_FIRST_NON_VCL

    /**
     * Пишет NAL (без стартового кода) в [out], убирая из SEI сообщения HDR10+.
     * @return число убранных сообщений; отрицательное - NAL состоял только из HDR10+ и не записан
     */
    private fun writeHevcNal(src: ByteArray, start: Int, end: Int, out: Output): Int {
        val len = end - start
        if (len < 3 || ((src[start].toInt() shr 1) and 0x3F) != HEVC_NAL_PREFIX_SEI) {
            out.write(src, start, len)
            return 0
        }
        val rbsp = unescape(src, start + 2, end)
        val messages = parseSeiMessages(rbsp)
        val hdr10Plus = messages?.count { it.isHdr10Plus } ?: 0
        if (messages == null || hdr10Plus == 0) {
            out.write(src, start, len)
            return 0
        }
        if (hdr10Plus == messages.size) return -hdr10Plus

        // В SEI есть и другие сообщения: собираем NAL заново без HDR10+
        val newRbsp = Output(rbsp.size + 1)
        messages.filterNot { it.isHdr10Plus }.forEach { newRbsp.write(rbsp, it.start, it.end - it.start) }
        newRbsp.write(0x80) // rbsp_trailing_bits
        out.write(src, start, 2)
        escapeInto(newRbsp.data, newRbsp.length, out)
        return hdr10Plus
    }

    private class SeiMessage(val start: Int, val end: Int, val isHdr10Plus: Boolean)

    /** Границы сообщений SEI в RBSP (вместе с заголовками типа и размера), null - разобрать не удалось. */
    private fun parseSeiMessages(rbsp: ByteArray): List<SeiMessage>? {
        // Конец данных - последний ненулевой байт (rbsp_trailing_bits, обычно 0x80)
        var dataEnd = rbsp.size
        while (dataEnd > 0 && rbsp[dataEnd - 1] == 0.toByte()) dataEnd--
        if (dataEnd == 0) return null
        dataEnd-- // байт со stop bit

        val messages = ArrayList<SeiMessage>(2)
        var pos = 0
        while (pos < dataEnd) {
            val msgStart = pos
            var type = 0
            while (pos < dataEnd && rbsp[pos] == 0xFF.toByte()) { type += 255; pos++ }
            if (pos >= dataEnd) return null
            type += rbsp[pos++].toInt() and 0xFF
            var size = 0
            while (pos < dataEnd && rbsp[pos] == 0xFF.toByte()) { size += 255; pos++ }
            if (pos >= dataEnd) return null
            size += rbsp[pos++].toInt() and 0xFF
            if (size > dataEnd - pos) return null
            val payloadStart = pos
            pos += size
            val isHdr10Plus = type == SEI_USER_DATA_REGISTERED_T35 && isHdr10PlusT35(rbsp, payloadStart, pos)
            messages.add(SeiMessage(msgStart, pos, isHdr10Plus))
        }
        return messages
    }

    /** Убирает emulation prevention байты (00 00 03 -> 00 00). */
    private fun unescape(src: ByteArray, start: Int, end: Int): ByteArray {
        val result = ByteArray(end - start)
        var n = 0
        var zeros = 0
        for (i in start until end) {
            val b = src[i]
            if (zeros >= 2 && b == 3.toByte()) {
                zeros = 0
                continue
            }
            result[n++] = b
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return result.copyOf(n)
    }

    /** Вставляет emulation prevention байты обратно. */
    private fun escapeInto(src: ByteArray, length: Int, out: Output) {
        var zeros = 0
        for (i in 0 until length) {
            val v = src[i].toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
    }

    private fun startCodeLength(buf: ByteArray, pos: Int, end: Int): Int {
        if (pos + 3 <= end && buf[pos] == 0.toByte() && buf[pos + 1] == 0.toByte()) {
            if (buf[pos + 2] == 1.toByte()) return 3
            if (pos + 4 <= end && buf[pos + 2] == 0.toByte() && buf[pos + 3] == 1.toByte()) return 4
        }
        return 0
    }

    /** Позиция следующего стартового кода или [end]. */
    private fun findStartCode(buf: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i < end - 2) {
            if (buf[i] == 0.toByte() && buf[i + 1] == 0.toByte() && startCodeLength(buf, i, end) > 0) return i
            i++
        }
        return end
    }

    // ===================== AV1 =====================

    /**
     * AV1-сэмпл (временной блок из OBU в low overhead формате). OBU пропускаются по полю размера.
     * @return число вырезанных OBU с HDR10+ (0 - сэмпл скопирован как есть)
     */
    fun stripAv1(src: ByteArray, offset: Int, length: Int, out: Output): Int {
        out.reset()
        val end = offset + length
        var removed = 0
        var pos = offset
        while (pos < end) {
            val header = src[pos].toInt() and 0xFF
            val type = (header shr 3) and 0x0F
            val hasExtension = (header and 0x04) != 0
            val hasSize = (header and 0x02) != 0
            if (!hasSize) break // Последний OBU без размера идет до конца сэмпла
            var payloadStart = pos + 1 + if (hasExtension) 1 else 0
            val leb = readLeb128(src, payloadStart, end) ?: break
            payloadStart += leb.second
            if (leb.first > end - payloadStart) break
            val payloadEnd = payloadStart + leb.first.toInt()
            if (type == AV1_OBU_METADATA && isAv1Hdr10Plus(src, payloadStart, payloadEnd)) {
                removed++
            } else {
                out.write(src, pos, payloadEnd - pos)
            }
            pos = payloadEnd
        }
        // Остаток (OBU без размера или нераспознанные данные) отдаем как есть
        if (pos < end) out.write(src, pos, end - pos)
        return removed
    }

    private fun isAv1Hdr10Plus(src: ByteArray, start: Int, end: Int): Boolean {
        val (metadataType, bytes) = readLeb128(src, start, end) ?: return false
        return metadataType == AV1_METADATA_ITUT_T35 && isHdr10PlusT35(src, start + bytes, end)
    }

    private fun readLeb128(buf: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var value = 0L
        for (i in 0 until 8) {
            if (start + i >= end) return null
            val b = buf[start + i].toInt() and 0xFF
            value = value or ((b and 0x7F).toLong() shl (7 * i))
            if (b and 0x80 == 0) return value to (i + 1)
        }
        return null
    }

    private fun readInt32(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xFF) shl 24) or ((buf[pos + 1].toInt() and 0xFF) shl 16) or
            ((buf[pos + 2].toInt() and 0xFF) shl 8) or (buf[pos + 3].toInt() and 0xFF)
}
