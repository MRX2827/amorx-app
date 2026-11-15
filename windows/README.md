# AmorX для Windows

EXE запускает подписанный Android-релиз AmorX 5.5.499.1318 GithubRelease x86_64 с R8 в отдельной среде Android 16. На ПК интерфейс и анимации отрисовывает оригинальный Android-код приложения.

## Запуск

Нужны Windows 10 или 11 x64, процессор Intel или AMD с включённой виртуализацией, компонент Windows Hypervisor Platform и минимум 8 ГБ свободного места. На первом запуске EXE открывает условия Android SDK и после согласия скачивает около 1,3 ГБ напрямую с серверов Google. Среда и данные AmorX хранятся в `%LOCALAPPDATA%\AmorX`. Данные на телефоне хранятся отдельно.

- Открывайте AmorX через EXE. Для выхода закройте окно Android.
- Перетащите фото, видео или музыку из Проводника в окно Android. В AmorX откройте папку «Загрузки» в диалоге выбора файла.
- Свайпы выполняются нажатием и перетаскиванием курсора.
- При проблемах с графикой закройте окно Android и запустите EXE с параметром `--software`.
- Журнал запуска находится в `%LOCALAPPDATA%\AmorX\launcher.log`.

[Настройка аппаратного ускорения Android Emulator](https://developer.android.com/studio/run/emulator-acceleration#vm-windows)

## Обновления

При запуске EXE проверяет [версию для Windows](https://mrx2827.github.io/amorxsyte/windows-update.json). Если вышла новая, AmorX предлагает скачать её и запускает проверенный EXE. Обычный ярлык или исходный EXE открывает установленную новую версию. Вход, библиотека и настройки остаются в том же виртуальном устройстве.

При проблемах с новой версией можно запустить исходный EXE с параметром `--no-launcher-update`.

## Сборка

Нужны Go 1.25+ и Python 3.11+. Скрипт собирает Windows x64 EXE с macOS, Linux или Windows. Он проверяет SHA-256 APK и встраивает его в EXE. APK берётся из релиза AmorX 5.5.499.1318 для x86_64.

```sh
python3 windows/build.py /path/to/AmorX-5.5.499.1318-githubRelease-x86_64.apk /path/to/AmorX-5.5.499.1318-Windows-x64.exe
```

Для следующих выпусков передайте `--android-manifest` с подготовленным `update.json` и `--windows-manifest` с путём к `windows-update.json`. Если APK скопирован из каталога Gradle и рядом нет `output-metadata.json`, укажите его Android `--version-code`. Имя файла для релиза должно иметь вид `AmorX-<версия>-Windows-x64.exe`.

Сначала прикрепите EXE к релизу GitHub с тегом версии, затем опубликуйте подготовленный `windows-update.json` в GitHub Pages. При необходимости передайте `--notes-file`, чтобы показать список изменений в диалоге обновления.
