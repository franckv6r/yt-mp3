#!/usr/bin/env bash
# Reconstruit l'extracteur auto-updatable : plugin (NewPipe) -> classes.dex
# Usage: ./build-plugin-dex.sh
set -euo pipefail
cd "$(dirname "$0")"

: "${JAVA_HOME:?definir JAVA_HOME (JDK 17)}"
: "${ANDROID_HOME:?definir ANDROID_HOME (SDK Android)}"
GRADLE="${GRADLE:-$HOME/.local/opt/gradle-8.7/bin/gradle}"

"$GRADLE" --no-daemon :plugin:fatJar

rm -rf build/dex && mkdir -p build/dex
"$ANDROID_HOME/build-tools/35.0.0/d8" \
  --min-api 26 \
  --lib "$ANDROID_HOME/platforms/android-34/android.jar" \
  --output build/dex \
  plugin/build/libs/extractor-fat.jar

cp build/dex/classes.dex app/src/main/assets/extractor.dex
mkdir -p dist
cp build/dex/classes.dex dist/extractor.dex
echo "OK: extractor.dex ($(stat -c%s extractor.dex) octets)"
echo "Heberge ce fichier quelque part et colle son URL dans l'appli (Mettre a jour l'extracteur)."
