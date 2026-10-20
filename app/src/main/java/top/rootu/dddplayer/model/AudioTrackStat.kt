package top.rootu.dddplayer.model

/**
 * Аудиодорожка, которую пользователь выбирал вручную (для выбора такой же дорожки в других видео).
 * @param signature подпись дорожки (TrackLogic.audioTrackSignature)
 * @param label название для списка приоритетов в настройках
 * @param count сколько раз выбиралась (по одному разу на видео)
 * @param pinned порядок задан пользователем вручную (такие дорожки идут первыми, в заданном порядке)
 */
data class AudioTrackStat(
    val signature: String,
    val label: String,
    val count: Int,
    val pinned: Boolean = false
)
