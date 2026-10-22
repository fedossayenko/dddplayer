package top.rootu.dddplayer.logic

/** Открытые компоненты в составе приложения ("Приложение → Лицензии"). */
object OpenSourceLicenses {

    data class Component(val name: String, val license: String, val url: String)

    val components = listOf(
        Component("DDD Video Player", "GPL-3.0", "https://github.com/usmanec/dddplayer"),
        Component("AndroidX Media3 (ExoPlayer)", "Apache-2.0", "https://github.com/androidx/media"),
        Component("AndroidX (AppCompat, Leanback, Room, ConstraintLayout)", "Apache-2.0", "https://developer.android.com/jetpack/androidx"),
        Component("Material Components for Android", "Apache-2.0", "https://github.com/material-components/material-components-android"),
        Component("Kotlin, kotlinx.coroutines", "Apache-2.0", "https://kotlinlang.org"),
        Component("OkHttp, Okio", "Apache-2.0", "https://square.github.io/okhttp/"),
        Component("Coil", "Apache-2.0", "https://github.com/coil-kt/coil"),
        Component("SMBJ, ASN-ONE", "Apache-2.0", "https://github.com/hierynomus/smbj"),
        Component("Bouncy Castle", "MIT", "https://www.bouncycastle.org"),
        Component("SLF4J", "MIT", "https://www.slf4j.org"),
        // Нативные декодеры из app/libs
        Component("FFmpeg", "LGPL-2.1-or-later", "https://ffmpeg.org"),
        Component("dav1d", "BSD-2-Clause", "https://code.videolan.org/videolan/dav1d"),
        Component("libdovi (dovi_tool)", "MIT", "https://github.com/quietvoid/dovi_tool"),
        // Перенесенный код: IEC 61937 (DTS-HD, TrueHD) и конвертация Dolby Vision P7 -> 8.1
        Component("Plezy", "GPL-3.0", "https://github.com/edde746/plezy")
    )
}
