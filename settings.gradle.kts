pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DDD Video Player"
include(":app")

// Нативная конвертация Dolby Vision (AAR в app/libs) собирается отдельно и только по запросу:
// ./gradlew -Pdovi=true :dovi:assembleRelease. Обычной сборке и CI NDK и Rust не нужны
if (providers.gradleProperty("dovi").orNull == "true") {
    include(":dovi")
}
 
