# AmorX Desktop

Нативная сборка Compose Desktop для Windows x64 и macOS Intel. JVM и VLC входят в пакет. Окно открывается развёрнутым, клавиша F11 переключает полноэкранный режим.

## Сборка

Используйте JDK 21 и Python 3.11+. Для Windows также нужен Go 1.25+.

- Подготовьте встроенный аудиодвижок командой `python packaging/media-runtime.py`. На Intel Mac скрипт берёт VLC 3.0.24 из `/Applications/VLC.app`. Путь можно передать через `--vlc-app`.
- Запустите `./gradlew createDistributable` или `gradlew.bat createDistributable` на Windows.
- На macOS упакуйте `build/compose/binaries/main/app/AmorX.app` командой `ditto -c -k --keepParent`.
- На Windows передайте `build/compose/binaries/main/app/AmorX` и путь EXE в `python packaging/portable.py`.

Чтобы проверить встроенный звук, запустите готовый пакет с аргументами `--verify-audio путь-к-файлу`. Исходники VLC и ссылки на лицензии перечислены в `vlc/SOURCE.txt` внутри пакета.

## Данные и обновления

На macOS профиль находится в `~/Library/Application Support/AmorX`, на Windows в `%LOCALAPPDATA%\AmorX Desktop`. Пакет приложения хранится отдельно.

Нативный клиент читает `desktop-update.json` на сайте AmorX. В записи нужны `version`, `notes` и `assets` с ключами `windows-x64` и `macos-intel`. Каждый файл описывается полями `url`, `size` и `sha256` и прикрепляется к релизу `desktop-<версия>` в GitHub. Сборки именуются `AmorX-Desktop-<версия>-Windows-x64.exe` и `AmorX-Desktop-<версия>-macOS-Intel.zip`. Версию выпуска надо указать в `DesktopUpdate.kt` перед сборкой.
