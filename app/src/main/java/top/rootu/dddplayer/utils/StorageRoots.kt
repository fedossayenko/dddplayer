package top.rootu.dddplayer.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.annotation.RequiresApi
import top.rootu.dddplayer.R
import java.io.File

/** Корень хранилища для файлового браузера: внутренняя память, SD-карта, USB-диск. */
data class StorageRoot(val title: String, val path: String)

object StorageRoots {

    /**
     * Доступные хранилища: тома из StorageManager плюс содержимое /storage для томов, которые система
     * не отдала (часть USB-дисков на ТВ). getExternalFilesDirs не используем: он создает папку приложения
     * на каждом томе, то есть пишет на USB-диск пользователя.
     */
    fun list(context: Context): List<StorageRoot> {
        val primary = Environment.getExternalStorageDirectory()
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        val paths = linkedSetOf(primary.absolutePath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageManager?.storageVolumes
                ?.filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                ?.mapNotNull { volumePath(it) }
                ?.forEach { paths.add(it) }
        }
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead() }
            ?.forEach { paths.add(it.absolutePath) }

        return paths.map { path ->
            val file = File(path)
            val isPrimary = path == primary.absolutePath
            val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                storageManager?.getStorageVolume(file)?.getDescription(context)
            } else {
                null
            }
            StorageRoot(
                title = when {
                    isPrimary -> context.getString(R.string.files_internal_storage)
                    !description.isNullOrBlank() -> description
                    else -> file.name
                },
                path = path
            )
        }
    }

    // Путь тома: на Android 11+ есть API, раньше - скрытый метод getPath (есть на всех версиях 7-10)
    @RequiresApi(Build.VERSION_CODES.N)
    @Suppress("DiscouragedPrivateApi")
    private fun volumePath(volume: StorageVolume): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            try {
                StorageVolume::class.java.getMethod("getPath").invoke(volume) as? String
            } catch (_: Exception) {
                null
            }
        }
}
