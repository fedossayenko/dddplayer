# Тестовые данные IEC 61937

Фикстуры упаковщиков `TrueHdMatPacker` и `DtsHdIecPacker` перенесены из
[edde746/plezy](https://github.com/edde746/plezy) (коммит `3faa1227`, `android/app/src/test/resources/`, GPL-3.0)
вместе с тестами и побайтно совпадают с оригиналом. Эталоны (`*_iec61937_golden.bin`) — вывод мультиплексора
`spdif` из FFmpeg, тесты сравнивают с ним упаковку байт в байт.

| Файл | Откуда |
| :--- | :--- |
| `truehd_access_units.bin`, `truehd_iec61937_golden.bin` | `surround_5_1_truehd.mka` (тестовые ассеты plezy, `android/app/src/androidTest/assets/ffmpeg/`): `ffmpeg -i surround_5_1_truehd.mka -c:a copy -f truehd …` и `-f spdif …` |
| `truehd_441_access_units.bin` | Блоки TrueHD того же размера, объявляющие частоту семейства 44.1 кГц: проверка, что упаковщик отказывается от несущей |
| `dtshd_ma_access_units.bin`, `dtshd_ma_iec61937_golden.bin` | Синтезированы `dtshd_fixture_generator.py` (ядро DTS из синусов FFmpeg `dca` + собранные вручную EXSS), команды в шапке скрипта |

FFmpeg не умеет кодировать DTS-HD MA, поэтому HD-часть собрана скриптом: упаковщик смотрит только синхрослова и
размеры EXSS, так что этого достаточно для проверки формата IEC 61937.
