import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

// Функция для безопасного выполнения команд через Providers API (совместимо с Gradle 8+)
fun getGitOutput(vararg command: String): String {
    return try {
        providers.exec {
            commandLine(*command)
            isIgnoreExitValue = true // Не ломать сборку, если git вернет ошибку (например, нет тегов)
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        ""
    }
}

val gitVersionCode: Int by lazy {
    // Используем HEAD, так как origin/main может быть не обновлен локально
    val output = getGitOutput("git", "rev-list", "--count", "HEAD")
    output.toIntOrNull() ?: 1
}

val gitVersionName: String by lazy {
    val output = getGitOutput("git", "describe", "--tags", "--dirty")

    // Если тегов нет (ошибка fatal или пусто), берем короткий хеш коммита
    if (output.isEmpty() || output.contains("fatal") || output.contains("не найдены")) {
        val commitHash = getGitOutput("git", "rev-parse", "--short", "HEAD")
        // Возвращаем временную версию, пока вы не создадите тег
        if (commitHash.isNotEmpty()) "0.0.1-dev-$commitHash" else "0.0.1"
    } else {
        output.replaceFirst("^v".toRegex(), "")
    }
}

// Ключ подписи релиза из переменных окружения (CI, см. .github/workflows/release.yml).
// Путь KEYSTORE_FILE - относительно модуля app (например, keystore/release.jks).
// Без переменных релиз собирается неподписанным, как раньше.
fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseKeystorePath: String? = envOrNull("KEYSTORE_FILE")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "top.rootu.dddplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "top.rootu.dddplayer"
        minSdk = 23
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName
        // Выводим в консоль при сборке
        println("Building Version: $versionName ($versionCode)")

        vectorDrawables.useSupportLibrary = true
    }

    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    signingConfigs {
        releaseKeystorePath?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = envOrNull("KEYSTORE_PASSWORD")
                keyAlias = envOrNull("RELEASE_SIGN_KEY_ALIAS")
                keyPassword = envOrNull("RELEASE_SIGN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    testOptions {
        unitTests {
            // Robolectric: Android API (Uri, SharedPreferences, org.json) в JVM тестах
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            // BouncyCastle (зависимость smbj) - multi-release jar с манифестами OSGi
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    lint {
        disable.add("MissingTranslation")
        disable.add("UnsafeOptInUsageError")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

// Готовые сборки libdovi 2.3.1 (edde746/libdovi-builds), sha256 как в plezy
val libdoviAbis = mapOf(
    "armeabi-v7a" to ("armv7-linux-androideabi" to "ed6fec8bf744e41c661b97f5fc4bf1197ebe9b09a140cbde369728e790ee3a68"),
    "arm64-v8a" to ("aarch64-linux-android" to "9d2983fc86f2f9e6da54c3c84ba8ea3a528690619f312ff4620198071b84e9ae"),
    "x86" to ("i686-linux-android" to "50f0a5606e617dff8976b9e7930a23272f4804882a35a6f0f2b2f2d3f8ed7135"),
    "x86_64" to ("x86_64-linux-android" to "eba59678f89b792f5c6f802962e237542fe8328f6aa03a0a90ee77353dac3194"),
)
val libdoviDir = file("src/main/cpp/libdovi")
val downloadLibdovi by tasks.registering {
    // Только локальные значения: ссылки на объект скрипта ломают configuration cache
    val abis = libdoviAbis
    val outRoot = libdoviDir
    val execOps = providers
    // Смена версии или sha256 должна перекачивать библиотеку
    inputs.property("libdovi", abis.toString())
    outputs.files(abis.keys.map { File(outRoot, "$it/libdovi.a") })
    doLast {
        abis.forEach { (abi, target) ->
            val (triple, sha256) = target
            val archive = File(temporaryDir, "libdovi-$triple.tar.gz")
            URI("https://github.com/edde746/libdovi-builds/releases/download/v2.3.1/libdovi-$triple.tar.gz")
                .toURL().openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(archive.readBytes())
                .joinToString("") { "%02x".format(it) }
            check(actual == sha256) { "libdovi $abi: sha256 $actual != $sha256" }
            val outDir = File(outRoot, abi).apply { mkdirs() }
            execOps.exec { commandLine("tar", "-xzf", archive.path, "-C", outDir.path) }.result.get()
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadLibdovi) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.okhttp)
    implementation(libs.smbj)

    // Media3 dependencies
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.decoder)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    implementation(libs.media3.extractor)
    // Media3 Extensions (HLS, DASH, RTSP)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    // Локальные AAR (Декодеры audio и AV1)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ui.graphics)
    ksp(libs.androidx.room.compiler)

    // Для загрузки изображений
    implementation(libs.coil)

    // поиск утечек
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
