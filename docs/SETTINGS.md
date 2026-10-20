# Настройки

## Где что хранится

| Хранилище | Что | Код |
|:---|:---|:---|
| SharedPreferences `global_prefs` | Глобальные настройки приложения | `data/SettingsRepository` |
| Room `ddd_player_db`, таблица `video_settings` | Настройки конкретного видео по URI: стерео-режим, вывод, анаглиф, swap, глубина, позиция, длительность, ID аудио- и субтитровой дорожек | `data/VideoSettings`, `data/AppDatabase` |

Записи Room старше 30 дней удаляются при загрузке плейлиста (`cleanupOldSettings`).

`SettingsRepository` — синглтон (`getInstance(context)`). На каждую настройку в нем есть пара
`get/is...` + `set...`, и ключи строками объявлены прямо в этих методах.

## Глобальные настройки (основное)

| Ключ | По умолчанию | Значения / смысл |
|:---|:---|:---|
| `resume_mode` | `RESUME_ASK` (0) | 0 спрашивать, 1 всегда, 2 никогда |
| `external_position_mode` | `EXTERNAL_POSITION_APPLY` (0) | Позиция из Intent: 0 применять сразу, 1 спрашивать |
| `up_button_action` | `UP_ACTION_MENU` (2) | 0 ничего, 1 OSD-настройки, 2 боковое меню, 3 инфопанель |
| `ok_button_action` | `OK_ACTION_PAUSE_PANEL` (1) | 0 пауза, 1 пауза + панель, 2 панель |
| `horizontal_swipe_action` | 2 | Действие горизонтального свайпа (телефон) |
| `sub_font`, `sub_size`, `sub_text_color`, `sub_bg_color`, `sub_edge_type`, `sub_edge_color`, `sub_bottom`, `sub_embedded` | как у стандартного `SubtitleView` | Стиль субтитров (`SubtitleStyle`, экран `SubtitleStyleActivity`, применение `SubtitleStyleOptions.apply`) |
| `gesture_volume_brightness` | true | Вертикальные свайпы: громкость справа, яркость слева |
| `confirm_exit` | true | Выход по второму нажатию «Назад» в течение 2 с |
| `auto_pip` | false | Авто-PiP при уходе с экрана |
| `live_mode_control` | false | Эфирный режим (см. [PLAYLISTS.md](PLAYLISTS.md)) |
| `show_info_on_change` | false | Инфопанель при смене элемента плейлиста |
| `show_playlist_index` | true | Номера в плейлисте |
| `startup_playlist_uri` | null | Стартовый плейлист (M3U/ссылка) |
| `last_channel_uri` | null | Последний канал M3U (служебное) |
| `clock_transparency` | `CLOCK_OFF` (-1) | -1 выкл, 0..80 % прозрачности. Мигрирует из старого `show_clock` |
| `buffer_display_mode` | `BUFFER_DISPLAY_OFF` (0) | 0 выкл, 1 в панели, 2 рядом с часами (и в инфопанели) |
| `target_buffer_mb` | `BUFFER_SIZE_AUTO` (-1) | Буфер сетевого видео в МБ, варианты ограничены кучей |
| `render_api_mode` | `RENDER_API_AUTO` (0) | 0 авто (ES 3.0 если есть), 1 принудительно GLES 2.0 |
| `pause_dim_level` | 60 | Затемнение на паузе, % |
| `global_resize_mode`, `video_zoom_scale`, `remember_zoom` | 0 (FIT), 115, false | Масштаб |
| `decoder_priority` | `EXTENSION_RENDERER_MODE_PREFER` | Приоритет декодеров ffmpeg и AV1 |
| `tunneling_enabled` | false | Туннелирование |
| `dv_mode` | `DV_MODE_ALWAYS` (2) | Dolby Vision → HEVC: 0 выкл (DV декодер), 1 авто (если экран или декодер не поддерживают DV), 2 всегда. Мигрирует из старого `map_dv_to_hevc` |
| `stereo_downmix`, `mix_*` | true, пресет 0 | Даунмикс многоканального звука в стерео и коэффициенты микшера |
| `skip_silence`, `loudness_boost` | false, 0 | Пропуск тишины, усиление громкости |
| `audio_track_memory`, `audio_track_stats` | `AUDIO_MEMORY_OFF` (0), — | Запоминание аудиодорожки: 0 выкл, 1 в пределах плейлиста (дорожка, выбранная в этом плейлисте), 2 + между видео (экспериментально): до 20 наиболее используемых дорожек (подпись: название, язык, кодек, каналы), порядок пользователь меняет в настройках |
| `pref_audio_lang`, `pref_sub_lang` | `""` | `""` — как в файле, `device` — язык системы, иначе код языка |
| `frame_rate_matching`, `afr_*` | false, ... | AFR |
| `app_language` | `system` | Язык интерфейса |
| `def_output_mode`, `def_anaglyph_type` | ANAGLYPH, RC_DUBOIS | Вывод по умолчанию для нового стерео-видео |
| `pref_filter_<GROUP>` | — | Последний фильтр в группе очков |
| `calib_<group>_set/hue_a/hue_b/leak_a/leak_b`, `anaglyph_lens_swap`, `calibration_version` | — | Калибровка анаглиф-очков из визарда (фильтр «Калибровка» группы), порядок линз, версия для перечитывания в плеере |
| `vr_k1`, `vr_k2`, `vr_scale`, `global_screen_separation_pct` | 0.34, 0.10, 1.2, 0 | Параметры VR |

Полный список смотрите в `SettingsRepository.kt`.

## Экран глобальных настроек

`GlobalSettingsActivity`: слева список категорий (`categories`, пункты `item_settings_category`), справа
прокручиваемые настройки выбранной категории (контейнеры `section_*`, видим только один).

Управление с пульта: фокус на категории сразу показывает ее настройки; Вправо — в настройки (на последний
выбранный в категории пункт); Влево или Назад — обратно к категориям; CH+/CH- и PgUp/PgDn — соседняя категория
из любой колонки; Назад в списке категорий закрывает экран. Выбор из списка значений — `ChoiceDialog`.

## Перезапуск плеера

`getHardSettingsSignature()` включает приоритет декодеров, туннелирование, DV→HEVC, даунмикс с микшером
и буфер. Когда пользователь возвращается из настроек, `PlayerViewModel.checkSettingsAndRestart()` сравнивает
сигнатуру с прежней. Если она изменилась, плеер пересоздается с сохранением позиции, иначе вызывается
`applyLiveSettings()` (языки дорожек, skip silence, громкость).

## OSD-настройки плеера

Панель сверху (кнопка «Вверх» в режиме OSD или пункт меню) показывает настройки текущего видео
(`SettingType`): тип стереопары, вывод, очки, API рендера, фильтр, параметры Custom, swap, глубина,
разнос экранов, VR-дисторсия и зум, дорожки. Список доступных пунктов зависит от режима
(`updateAvailableSettings`). Влево/Вправо меняют значение (`changeSettingValue`), OK сохраняет
в Room (`saveCurrentSettings`), «Назад» откатывает изменения (`restoreSettings`).

## Как добавить глобальную настройку

1. `SettingsRepository`: добавьте геттер и сеттер (и константы значений, если они нужны). Значение по умолчанию
   **должно сохранять текущее поведение.**
2. Если старый ключ поменял смысл, напишите миграцию (пример — `getClockTransparency`).
3. `GlobalSettingsViewModel`: добавьте `MutableLiveData` с начальным значением из репозитория и метод изменения.
4. `activity_global_settings.xml`: добавьте пункт в контейнер нужной категории (`section_playback`, `section_screen`,
   `section_video`, `section_audio`, `section_subtitles`, `section_control`, `section_3d`, `section_app`) по образцу
   соседних. Пункты фокусируемые: навигация идет с пульта. Новая категория — это новый контейнер `section_*`
   плюс запись в `GlobalSettingsActivity.categories`.
   потому что навигация идет с пульта).
5. `GlobalSettingsActivity`: `lateinit` поля, `bindViews`, обработчик в `setupListeners` и подписка
   в `observeViewModel`. Для выбора из списка используйте `showSingleChoiceDialog`.
6. Строки (`title` и `summary`/значения) добавьте во все 5 локалей.
7. Если для настройки нужно пересоздать ExoPlayer, добавьте ее в `getHardSettingsSignature()`.
8. Используйте настройку там, где она нужна (обычно `PlayerViewModel`, `PlayerManager` или `PlayerInputHandler`).
