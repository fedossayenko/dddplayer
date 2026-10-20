package top.rootu.dddplayer.logic

/**
 * Профили Dolby Vision по строке кодека из контейнера (dvhe.08.06, dvh1.05.06, dav1.10.09).
 */
object DolbyVisionLogic {

    private val DV_PREFIXES = setOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    // Профили с совместимым базовым слоем HEVC: 4 (SDR), 7 (BL+EL), 8 (HDR10/HLG/SDR).
    // У 5 совместимого слоя нет (IPTPQc2), 9 построен на AVC, 10 - на AV1
    private val HEVC_COMPATIBLE_PROFILES = setOf(4, 7, 8)

    /** Профиль Dolby Vision или null, если кодек не Dolby Vision или профиль не разобрать. */
    fun profile(codecs: String?): Int? {
        val codec = codecs?.split(',')
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore('.').lowercase() in DV_PREFIXES }
            ?: return null
        return codec.split('.').getOrNull(1)?.toIntOrNull()
    }

    /**
     * Можно ли подменить декодер Dolby Vision на HEVC и играть базовый слой.
     * Неизвестный профиль подменяется, как раньше.
     */
    fun canPlayAsHevc(codecs: String?): Boolean =
        profile(codecs)?.let { it in HEVC_COMPATIBLE_PROFILES } ?: true
}
