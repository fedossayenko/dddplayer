package top.rootu.dddplayer

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import top.rootu.dddplayer.data.SettingsRepository
import top.rootu.dddplayer.utils.CrashHandler
import java.util.concurrent.TimeUnit

class App : Application(), ImageLoaderFactory {
    companion object {
        const val USER_AGENT = "DDDPlayer/${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        instance = this
        CrashHandler.init(this)
        applyAppLanguage()
        // Режим Dolby Vision по умолчанию выбирается при первом запуске (новая установка или обновление)
        SettingsRepository.getInstance(this).getDvMode()
        super.onCreate()
    }

    private fun applyAppLanguage() {
        val repository = SettingsRepository.getInstance(this)
        val langCode = repository.getAppLanguage()

        val appLocale = if (langCode == SettingsRepository.LANG_SYSTEM_DEFAULT) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }

        AppCompatDelegate.setApplicationLocales(appLocale)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    // Разрешаем редиректы
                    .followRedirects(true)
                    .followSslRedirects(true)
                    // Увеличиваем таймауты для медленных серверов
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("User-Agent", USER_AGENT)
                            .build()
                        chain.proceed(request)
                     }
                     .build()
            }
            // Кэш в памяти небольшой, чтобы не отнимать память у буфера видео (largeHeap)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.03)
                    .build()
            }
            // Логотипы каналов M3U кэшируются на диске
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}