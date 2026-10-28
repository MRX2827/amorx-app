<div align="center">
  <img src="assets/icon_full.png" alt="AmorX" width="160">

  # AmorX
  **Твоя музыка. Твой плеер.**

  [![Android](https://img.shields.io/badge/Android-34A853?style=flat-square&logo=android&logoColor=white)](https://android.com)
  [![API](https://img.shields.io/badge/API-26%2B-4285f4?style=flat-square&logo=android&logoColor=white)](https://android-arsenal.com/api?level=26)
  [![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7c4dff?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![License](https://img.shields.io/badge/License-GPL%20v3-4285f4?style=flat-square)](LICENSE)

  [🌐 Сайт](https://mrx2827.github.io/amorxsyte/) • [📥 Скачать](https://github.com/MRX2827/amorxsyte/releases/latest)
</div>

---

## О приложении

AmorX — музыкальный плеер для Android в стиле Material 3.

- Локальная музыка и онлайн-поиск песен
- Плейлисты AmorX, импорт из Яндекс Музыки и Mimose по ссылке
- Скачивание песен для прослушивания без интернета
- «Моя волна» с рекомендациями

## Скачать

Последняя версия лежит в [релизах](https://github.com/MRX2827/amorxsyte/releases/latest).
Для большинства телефонов подходит файл `arm64-v8a`, если не уверены — берите `universal`.

## Сборка

```bash
./gradlew assembleGithubRelease
```

Для подписи нужен свой `.config/keystore.properties` (в репозиторий не входит).

## Лицензия

Проект распространяется под лицензией [GPL v3](LICENSE). AmorX основан на открытом проекте Rhythm (автор cromaguy), код которого также открыт по GPL v3.
