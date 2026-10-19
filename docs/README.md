# Документация по коду

Документация для разработчиков и AI-агентов. С чего начинать: [../AGENTS.md](../AGENTS.md).

| Документ | О чем |
|:---|:---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Слои, жизненный цикл экрана плеера, потоки данных, где что лежит |
| [PLAYBACK.md](PLAYBACK.md) | `PlayerManager`: ExoPlayer, OkHttp, источники данных, буфер, восстановление после ошибок, аудио |
| [PLAYLISTS.md](PLAYLISTS.md) | Плейлисты, M3U/IPTV, группы, эфирный режим, последний канал, продолжение просмотра |
| [RENDERING.md](RENDERING.md) | 2D/HDR и стерео вывод, `StereoRenderer`, шейдеры ES 2.0/3.0, анаглиф, VR |
| [SETTINGS.md](SETTINGS.md) | Глобальные настройки (ключи и значения по умолчанию), настройки видео в Room, как добавить настройку |
| [INTENT_API.md](INTENT_API.md) | Как Intent превращается в плейлист, результат для вызывающего приложения |

Пользовательская документация (возможности, интеграция через Intent) лежит в [README.md](../README.md)
и [README_RU.md](../README_RU.md). Сборка AAR-декодеров описана в [app/libs/README.md](../app/libs/README.md).
