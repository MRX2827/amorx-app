# Перенос AmorX на Linux

Состояние на 28 ноября 2026 года. Рабочая ветка приложения — **`codex/amorx-native`**.

## Что уже опубликовано

| Компонент | Версия | Где хранится |
| --- | --- | --- |
| Android, GithubRelease с R8 | 5.5.499.1341 / 554991341 | [APK и релиз](https://github.com/MRX2827/amorxsyte/releases/tag/5.5.499.1341) |
| Windows x64 / macOS Intel | 5.5.499.1330 | `desktop-update.json` в репозитории сайта |
| iOS 15+ | 5.5.499.1330 | `ios-update.json` в репозитории сайта |

[Сайт](https://mrx2827.github.io/amorxsyte/) и манифесты обновлений находятся в [amorxsyte](https://github.com/MRX2827/amorxsyte), ветка `main`. `windows-update.json` относится к старому клиенту 1318; современный настольный клиент использует `desktop-update.json`. Номера версий платформ отличаются намеренно.

## Получение исходников

```bash
git clone --branch codex/amorx-native https://github.com/MRX2827/amorx-app.git
git clone https://github.com/MRX2827/amorxsyte.git
cd amorx-app
git status
```

Каталог старого Mac был Git worktree с абсолютным путём к другой папке. Его файл `.git` нельзя переносить напрямую. Используйте clone или самостоятельные Git bundle из резервной копии. Старые локальные снимки `original-rhythm` и `workspace/work/windows-ci-smoke` сохранены отдельно: не копируйте их поверх актуального checkout.

## Android: подготовка Linux

Нужны Git, Python 3, JDK **21**, Android SDK command-line tools и доступ в интернет для первой сборки. Android Studio для сборки не обязателен. Установите SDK по [официальной инструкции](https://developer.android.com/tools/sdkmanager).

```bash
export JAVA_HOME=/путь/к/jdk-21
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
sdkmanager --licenses
sdkmanager 'platform-tools' 'platforms;android-37.0' 'build-tools;36.0.0'
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
java -version
```

Версия SDK, установленная на исходном Mac: API **37.0**, revision 2; build-tools **36.0.0**. Gradle **9.7.1** загружается wrapper автоматически. Не копируйте `local.properties` с macOS: там другой абсолютный путь.

### Сначала восстановите ключ подписи

Опубликованные APK используют существующий `debug.keystore`. Несмотря на имя, **это действующий ключ обновлений AmorX**. Новый автоматически созданный ключ несовместим с установленными приложениями. Не удаляйте приложение для обхода ошибки подписи.

Ключ сохранён только в зашифрованном миграционном архиве, путь после восстановления: `secrets/android/debug.keystore`. Скопируйте его перед первой сборкой:

```bash
mkdir -p "$HOME/.android"
# Если файл уже существует, сначала сохраните его отдельно.
install -m 600 /путь/к/восстановленной-копии/secrets/android/debug.keystore "$HOME/.android/debug.keystore"
keytool -list -v -keystore "$HOME/.android/debug.keystore" -alias androiddebugkey
```

Пароль этого существующего Android debug store — стандартный `android`. Ожидаемый SHA-256 сертификата:

```text
43:D5:3F:2E:8B:83:29:25:05:06:13:51:30:9D:94:45:9A:45:FF:34:38:F4:9B:A8:81:72:A2:84:EC:D7:BF:0C
```

В текущем проекте при отсутствии `.config/keystore.properties` Release использует этот debug store. При настройке отдельного signing config указывайте **тот же ключ**, не создавайте новый. Не коммитьте keystore, пароли, токены или ключ расшифровки резервной копии.

### Сборка GithubRelease с R8

Для машины с 8 ГБ RAM применялось ограничение процесса до 4 ГБ heap и двух workers. На машине с 4 ГБ нужен меньший heap, swap и больше времени; скорость и успешность R8 заранее не гарантируются. Настройки в `gradle.properties` рассчитаны на больший объём памяти, поэтому используйте явное ограничение:

```bash
./gradlew :app:assembleGithubRelease --no-daemon \
  --no-configuration-cache --no-parallel --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xms256m -Xmx4096m -XX:MaxMetaspaceSize=768m -XX:ReservedCodeCacheSize=256m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process --console=plain
```

Для первой сборки не используйте `--offline`. Результаты: `app/build/outputs/apk/github/release/`. Сборка Release включает `isMinifyEnabled=true` и `isShrinkResources=true`. Перед публикацией проверяйте package, versionCode, сертификат каждого APK и SHA-256:

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose --print-certs /путь/к/AmorX.apk
sha256sum /путь/к/AmorX.apk
adb install -r /путь/к/AmorX.apk
```

`adb install -r` сохраняет данные; используйте APK правильной архитектуры. Не запускайте debug-сборку вместо GithubRelease для выпуска.

## Windows, macOS и iPhone после переноса

Общий desktop-код: `desktop/`; iOS-код: `ios/`, включая модифицированный `ios/Vendor/YouTubeKit` и описание изменений `AMORX_CHANGES.md`. Отдельный старый checkout YouTubeKit не нужен для сборки iOS.

- Windows EXE собирается на Windows через `.github/workflows/amorx-native-windows.yml`: JDK 21, Python 3.13, Go 1.27, встроенные JVM и VLC. Workflow запускается при изменениях соответствующих desktop/playback файлов; у него нет ручного `workflow_dispatch`.
- iOS IPA и проверки симулятора выполняются на macOS через `.github/workflows/amorx-ios.yml`. Ручной запуск с Linux: `gh workflow run amorx-ios.yml --repo MRX2827/amorx-app --ref codex/amorx-native`. Это запускает сборку; проверяйте результат отдельно.
- Для упаковки macOS Intel нужен Intel Mac/подходящий macOS runner, JDK 21 и VLC; команды описаны в `desktop/README.md`.
- Linux не заменяет Xcode для сборки IPA или нативной упаковки macOS. Готового Linux-пакета AmorX этот перенос не создаёт.

Секреты GitHub Actions остаются в настройках репозитория. Их значения нельзя выгрузить через GitHub API; повторная авторизация `gh auth login` на Linux выполняется отдельно. Системные SDK, Gradle-кеши, установленные программы и учётные данные macOS не являются частью переносимых исходников.

## Выпуск и сайт

1. Увеличьте версию только изменяемой платформы, соберите Release и проверьте поведение. Для Android сохраните сертификат выше.
2. Закоммитьте и отправьте исходники в `MRX2827/amorx-app`, ветку `codex/amorx-native`.
3. Прикрепите APK/EXE/ZIP/IPA к релизу в `MRX2827/amorxsyte`. Для desktop тег имеет вид `desktop-<версия>`; для Android — `<версия>`.
4. В checkout сайта обновите соответствующий JSON манифест: версия, URL, размер и SHA-256 каждого файла; при необходимости ссылки/описание на странице. Отправьте `main`.
5. Проверьте успешный Pages deployment, публичный JSON и доступность файлов. Не объявляйте обновление готовым по одному факту запуска сборки.

В миграционной копии сохранены скрипты и отчёты предыдущих публикаций в `workspace/outputs/`, в том числе `amorx-1341/verify-apks.py`, `update.json`, отчёт GitHub digest и проверки публичного сайта. Старые скрипты содержат пути Mac; перед повторным запуском замените их на пути Linux.

## Последние изменения Android

До 1341 исправлены получение/кеширование текстов для онлайн и скачанных песен, выбор подходящей записи, плавность строк и показ длинных строк. Визуализатор получает семь диапазонов реального PCM; в 1341 убраны автономное движение в тишине и реакция при mute/pause. Код последнего исправления: `1d17a2b8`. Отчёты содержат 23 прошедших unit-теста и проверки компиляции UI; физический тест именно 1341 при публикации не проводился, телефон был отключён.

Предпочтение владельца: после старта длительной сборки сразу сообщить о запуске и вернуться в чат; проверять готовность по его следующему запросу. Не обещать идеальную синхронизацию текстов у каждой записи: качество временных меток зависит от источника.

## Зашифрованная резервная копия

Дополнительные локальные файлы, Git bundles и signing key хранятся в приватном [amorx-migration-backup](https://github.com/MRX2827/amorx-migration-backup). Инструкция и программа восстановления находятся в нём. Копия шифруется [age](https://github.com/FiloSottile/age); ключ расшифровки **не загружается на GitHub**. Перед стиранием Mac сохраните `AmorX-recovery-key.txt` на отдельный носитель или в свой менеджер паролей. Одного доступа к GitHub для расшифровки недостаточно.
