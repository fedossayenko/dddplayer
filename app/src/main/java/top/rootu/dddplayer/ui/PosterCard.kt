package top.rootu.dddplayer.ui

import android.net.Uri
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import coil.dispose
import coil.load
import top.rootu.dddplayer.R

/**
 * Карточка видео: постер, заглушка с номером и бейдж с номером в углу.
 * Общая логика для инфопанели, плейлиста и шторки свайпа.
 *
 * - есть постер: постер, бейдж с номером (если видео в плейлисте больше одного и включен номер);
 * - нет постера (или не загрузился) в плейлисте: заглушка с номером;
 * - нет постера у одиночного видео: баннер приложения.
 */
object PosterCard {

    fun bind(
        poster: ImageView,
        placeholder: TextView,
        badge: TextView,
        posterUri: Uri?,
        index: Int,
        isSingleVideo: Boolean,
        showIndexBadge: Boolean
    ) {
        val numberText = (index + 1).toString()
        placeholder.text = numberText
        badge.text = numberText

        // Очищаем слушатели предыдущего запроса (вьюхи переиспользуются)
        poster.dispose()
        showFallback(poster, placeholder, badge, isSingleVideo)
        if (posterUri == null) return

        poster.load(posterUri) {
            crossfade(true)
            listener(
                onSuccess = { _, _ ->
                    placeholder.isVisible = false
                    badge.isVisible = showIndexBadge && !isSingleVideo
                },
                onError = { _, _ -> showFallback(poster, placeholder, badge, isSingleVideo) }
            )
        }
    }

    private fun showFallback(poster: ImageView, placeholder: TextView, badge: TextView, isSingleVideo: Boolean) {
        badge.isVisible = false
        // Постер оставляем видимым: Coil грузит только в видимую вьюху
        poster.isVisible = true
        if (isSingleVideo) {
            poster.setImageResource(R.drawable.tv_banner)
            placeholder.isVisible = false
        } else {
            poster.setImageDrawable(null)
            placeholder.isVisible = true
        }
    }
}
