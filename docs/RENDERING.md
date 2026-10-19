# Вывод видео (`renderer/`)

## Две поверхности

`player_fragment.xml` содержит обе поверхности. Видимую выбирает `PlayerUiController.setSurfaceMode(isStereo)`.

| Режим | Поверхность | Зачем |
|:---|:---|:---|
| 2D (`StereoInputType.NONE`) | `SurfaceView` внутри `AspectRatioFrameLayout` | Прямой вывод: HDR/Dolby Vision, туннелирование, минимум нагрузки |
| Стерео | `StereoGLSurfaceView` → `StereoRenderer` | Шейдер разбирает стереопару и выводит анаглиф, VR или один глаз |

При смене режима `PlayerFragment` заново подключает нужную поверхность к плееру
(`attachSurfaceToPlayer`, `onSurfaceReady`). В 2D масштаб (`ResizeMode`, зум) и принудительное
соотношение сторон (`AspectRatioMode`: AUTO, 4:3, 16:9, 1.85, 2.35, 2.39) применяются
к `AspectRatioFrameLayout`.

## StereoRenderer

- Работает в GL-потоке, режим `RENDERMODE_WHEN_DIRTY`. Кадры поступают через `SurfaceTexture.onFrameAvailable`
  и обновляются в `onDrawFrame` (`updateTexImage` и матрица текстуры).
- Сеттеры (`setInputType`, `setOutputMode`, `setAnaglyphMatrices`, `setDepth`, `setDistortion`, ...)
  вызываются из UI. Они только меняют поля и делают `requestRender()`, а uniform-ы применяются
  в `updateShaderUniforms()` внутри `onDrawFrame`.
- `u_inputType` и `u_outputMode` передаются как `ordinal` enum-ов `StereoInputType` и `StereoOutputMode`.
  **Порядок констант в enum должен совпадать с константами `INPUT_*` и `OUTPUT_*` в шейдерах.**
  Если добавляете значение, добавьте его в конец enum и в оба шейдера.
- Глубина (`u_depth`) — это сдвиг в пикселях, нормированный на ширину одного кадра (`setSingleFrameDimensions`).
  Размер кадра считает `PlayerViewModel.calculateFrameSize` по типу стереопары.

### ES 3.0 и ES 2.0

- `PlayerFragment` создает контекст ES 3, если устройство поддерживает ES 3.0 (`reqGlEsVersion >= 0x30000`),
  а в настройках не выбран режим «GLES 2.0» (`render_api_mode`). Иначе создается контекст ES 2.
- `ShaderSource` содержит две версии шейдеров: `VERTEX_SHADER`/`FRAGMENT_SHADER` (ES 2.0) и
  `VERTEX_SHADER_300`/`FRAGMENT_SHADER_300` (ES 3.0). Геометрия у них одинаковая. В ES 3.0 используется `highp`
  и, для матриц, рассчитанных в линейном RGB, смешивание в линейном пространстве (`u_linearBlend`, точные
  формулы sRGB через `pow`: аппроксимации давали ошибку до 10 ступеней 8 бит).
- Откат: если нет `GL_OES_EGL_image_external_essl3` или шейдер ES 3.0 не собрался, рендер переключается на
  ES 2.0 и вызывает `onEs3ShadersUnavailable`. После этого пункт выбора API рендера в OSD скрывается.
- `setRenderApi(forceLegacy)` переключает шейдеры на лету (`shouldReloadShaders`). Из контекста ES 2
  включить ES 3.0 на лету нельзя: метод возвращает false, и пользователь видит тост о перезапуске.
- **Любую правку логики шейдера вносите в обе версии.**
- HDR в стерео-режиме не поддерживается: GL-вывод идет в SDR. Это сознательное решение,
  потому что выигрыш не окупает сложность.

## Анаглиф

- Типы (`StereoRenderer.AnaglyphType`) делятся на группы очков: Red-Cyan, Yellow-Blue, Green-Magenta и Red-Blue
  (только Mono). В каждой группе есть варианты Dubois, Half Color, Color, Mono, Custom (у RC есть еще Optimized).
- Матрицы 3×3 для левого и правого глаза берутся из `AnaglyphLogic.calculateMatrix`: это пресеты либо
  `DuboisMath.calculate` для Custom (оттенок и утечка цветов очков, расчет в линейном RGB).
- Линейное смешивание (`usesLinearMatrix`) применяется для Dubois, Optimized и Custom. Mono и Half Color
  построены на весах яркости Rec.601 для гамма-значений, а Color копирует каналы, поэтому их смешивание
  остается в гамме.
- Последний выбранный фильтр в каждой группе очков хранится в `pref_filter_<GROUP>`.

## Cardboard VR

- `OUTPUT_CARDBOARD` выводит side-by-side с бочкообразной коррекцией дисторсии: `u_k1`, `u_k2` и масштаб
  `u_distortScale`. Межлинзовое расстояние задается через `u_screenSeparation`.
- Параметры VR хранятся в `vr_k1`, `vr_k2`, `vr_scale` и `global_screen_separation_pct` (`AnaglyphDelegate`).
  Импорт профиля из QR-кода Cardboard (`google.com/cardboard/cfg?...`) делает `VrConfigActivity`
  с помощью `CardboardParamsParser`.

## Автоопределение стереопары

`StereoTypeDetector.detect(format, uri)` сначала смотрит `Format.stereoMode` из контейнера, затем
ищет в имени файла маркеры (`sbs`, `half-sbs`, `lr`, `side-by-side`, `ou`, `tb`, `over-under`, `interlace`,
`3dz-tiled`, ...). Автоопределение срабатывает, только если стерео-режим еще не задан
(`inputType == NONE` после загрузки сохраненных настроек), и показывает тост.
