#!/bin/bash
set -e
N="$1"
if [ -z "$N" ]; then echo "Использование: ./release.sh 1305"; exit 1; fi

NAME="5.5.499.$N"
CODE="55499$N"

# 1. Меняем версию в build.gradle.kts (строки versionCode и versionName)
sed -i '' -E "s/(overrideVersionCode \?: )[0-9]+/\1$CODE/; s/(overrideVersionName \?: \")[0-9.]+\"/\1$NAME\"/" app/build.gradle.kts
grep -nE "overrideVersion(Code|Name) \?:" app/build.gradle.kts

# 2. Сборка
./gradlew assembleGithubRelease 2>&1 | tee ~/Desktop/build.log | tail -5

# 3. Проверка версии внутри APK
APK=$(ls -t app/build/outputs/apk/github/release/*arm64-v8a.apk | head -1)
AAPT=$(ls -d ~/Library/Android/sdk/build-tools/*/ | tail -1)aapt2
INFO=$("$AAPT" dump badging "$APK" | head -1)
echo "$INFO"
if ! echo "$INFO" | grep -q "versionCode='$CODE'"; then
  echo "СТОП: внутри APK не та версия"; exit 1
fi

# 4. Размер и sha256 для update.json
echo "APK:    $APK"
echo "size:   $(stat -f%z "$APK")"
echo "sha256: $(shasum -a 256 "$APK" | cut -d' ' -f1)"
