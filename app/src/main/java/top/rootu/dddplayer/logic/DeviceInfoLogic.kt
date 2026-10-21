package top.rootu.dddplayer.logic

import java.util.Locale

/**
 * Чистая логика экрана диагностики: подписи профилей декодеров, признак программного декодера,
 * форматирование режимов экрана. Константы профилей продублированы из MediaCodecInfo.CodecProfileLevel,
 * чтобы логика не зависела от Android.
 */
object DeviceInfoLogic {

    const val MIME_HEVC = "video/hevc"
    const val MIME_DOLBY_VISION = "video/dolby-vision"
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"

    // MediaCodecInfo.CodecProfileLevel
    private const val HEVC_MAIN10 = 0x2
    private const val HEVC_MAIN10_HDR10 = 0x1000
    private const val HEVC_MAIN10_HDR10_PLUS = 0x2000
    private const val VP9_PROFILE2 = 0x4
    private const val VP9_PROFILE3 = 0x8
    private const val VP9_PROFILE2_HDR = 0x1000
    private const val VP9_PROFILE3_HDR = 0x2000
    private const val VP9_PROFILE2_HDR10_PLUS = 0x4000
    private const val VP9_PROFILE3_HDR10_PLUS = 0x8000
    private const val AV1_MAIN10 = 0x2
    private const val AV1_MAIN10_HDR10 = 0x1000
    private const val AV1_MAIN10_HDR10_PLUS = 0x2000

    // Display.HdrCapabilities
    private val HDR_TYPE_NAMES = mapOf(1 to "Dolby Vision", 2 to "HDR10", 3 to "HLG", 4 to "HDR10+")

    /**
     * Что декодер умеет сверх 8 бит: "10-bit", "HDR10", "HDR10+"; для Dolby Vision - номера профилей ("P5", "P8").
     * Профили Dolby Vision идут битами по номеру профиля (DvavPer = 0x1 - профиль 0, DvheStn = 0x20 - профиль 5).
     */
    fun profileFeatures(mime: String, profiles: List<Int>): List<String> {
        fun has(mask: Int) = profiles.any { it and mask != 0 }
        return when (mime) {
            MIME_HEVC -> listOfNotNull(
                "10-bit".takeIf { has(HEVC_MAIN10 or HEVC_MAIN10_HDR10 or HEVC_MAIN10_HDR10_PLUS) },
                "HDR10".takeIf { has(HEVC_MAIN10_HDR10) },
                "HDR10+".takeIf { has(HEVC_MAIN10_HDR10_PLUS) }
            )
            MIME_VP9 -> listOfNotNull(
                "10-bit".takeIf { has(VP9_PROFILE2 or VP9_PROFILE3 or VP9_PROFILE2_HDR or VP9_PROFILE3_HDR) },
                "HDR10".takeIf { has(VP9_PROFILE2_HDR or VP9_PROFILE3_HDR) },
                "HDR10+".takeIf { has(VP9_PROFILE2_HDR10_PLUS or VP9_PROFILE3_HDR10_PLUS) }
            )
            MIME_AV1 -> listOfNotNull(
                "10-bit".takeIf { has(AV1_MAIN10 or AV1_MAIN10_HDR10 or AV1_MAIN10_HDR10_PLUS) },
                "HDR10".takeIf { has(AV1_MAIN10_HDR10) },
                "HDR10+".takeIf { has(AV1_MAIN10_HDR10_PLUS) }
            )
            MIME_DOLBY_VISION -> (0..30).filter { bit -> has(1 shl bit) }.map { "P$it" }
            else -> emptyList()
        }
    }

    /** Программный декодер по имени (до Android 10, где нет MediaCodecInfo.isHardwareAccelerated) */
    fun isSoftwareCodecName(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") ||
                n.startsWith("omx.ffmpeg.") || n.contains(".sw.") || n.endsWith(".sw")
    }

    /** Типы HDR экрана (Display.HdrCapabilities) */
    fun hdrTypeNames(types: List<Int>): List<String> = types.distinct().sorted().map { HDR_TYPE_NAMES[it] ?: "#$it" }

    /** Частота кадров: 60 -> "60", 59.94 -> "59.94", 23.976 -> "23.976" */
    fun formatRate(rate: Float): String {
        val rounded = String.format(Locale.ROOT, "%.3f", rate).trimEnd('0').trimEnd('.')
        return rounded.ifEmpty { "0" }
    }

    /** Режим экрана "3840×2160 @ 59.94" */
    fun formatMode(width: Int, height: Int, rate: Float) = "$width×$height @ ${formatRate(rate)}"
}
