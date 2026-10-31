#!/usr/bin/env bash
# Post-sign debug APKs with v1+v2 signatures.
# Samsung's One UI sideload installer can reject v2-only / uncompressed-libs
# packages with "There's a problem with the app file"; v1+v2 + useLegacyPackaging
# sidesteps it. Called automatically after `./gradlew assembleDebug` by hand:
#   ./tools/resign.sh
set -euo pipefail
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/34.0.0
KS="$HOME/.android/debug.keystore"

cd "$(dirname "$0")/../app/build/outputs/apk/debug"

for f in app-*-debug.apk; do
  rm -f "$f.s"
  "$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --v1-signing-enabled true --v2-signing-enabled true \
    --out "$f.s" "$f"
  mv "$f.s" "$f"
  echo "signed $f"
done
