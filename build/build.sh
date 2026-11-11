#!/usr/bin/env bash
# Build the module APK without Gradle: javac -> dex -> aapt2 -> jar -> zipalign -> apksigner
#
#   ANDROID_HOME=~/Android/Sdk ./build/build.sh
#
# Optional environment:
#   KEYSTORE   path to a keystore  (skip signing when unset -> *-unsigned.apk)
#   KS_PASS    keystore password
#   KEY_ALIAS  key alias (default: xiaobu)
#   DX_JAR     path to dx.jar, only used when d8 cannot run (old JDK)
#
# Requirements: JDK (8+ for javac; 11+ recommended so d8 works),
#               Android SDK with build-tools + one platform, unzip-free.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$ROOT/module"
OUT="$ROOT/out"
CLS="$OUT/classes"
DEX="$OUT/dex"
APK="$OUT/apk"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "error: set ANDROID_HOME (or ANDROID_SDK_ROOT) to your Android SDK" >&2; exit 1; }
[ -d "$SDK" ] || { echo "error: SDK not found: $SDK" >&2; exit 1; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe"; BAT=".bat" ;;
  *) EXE=""; BAT="" ;;
esac

BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
[ -n "$BT" ] || { echo "error: no build-tools under $SDK/build-tools" >&2; exit 1; }
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"
APKSIGNER="$BT/apksigner$BAT"
for t in "$AAPT2" "$ZIPALIGN" "$APKSIGNER"; do
  [ -f "$t" ] || { echo "error: missing tool: $t" >&2; exit 1; }
done

ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
[ -n "$ANDROID_JAR" ] || { echo "error: no platforms/android-*/android.jar in $SDK" >&2; exit 1; }

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
JAR="${JAR:-jar}"
command -v "$JAVAC" >/dev/null || { echo "error: javac not on PATH" >&2; exit 1; }

VERSION_NAME="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$MODULE/AndroidManifest.xml")"
VERSION_CODE="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$MODULE/AndroidManifest.xml")"
[ -n "$VERSION_NAME" ] || { echo "error: cannot read versionName" >&2; exit 1; }

echo "== module ${VERSION_NAME} (code ${VERSION_CODE})"
echo "== SDK    $SDK"
echo "== tools  $BT"
echo "== jar    $ANDROID_JAR"

rm -rf "$OUT"
mkdir -p "$CLS" "$DEX" "$APK"

echo "== javac (libxposed API stub + module)"
# The real API jar is Java 17 bytecode (JDK 8 javac cannot read it), so the
# signatures are mirrored in build/stub-libxposed. Those classes are compiled
# separately and never enter the dex.
mkdir -p "$OUT/libxposed-stub"
find "$ROOT/build/stub-libxposed" -name '*.java' > "$OUT/stub-sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$OUT/libxposed-stub" @"$OUT/stub-sources.txt"

find "$MODULE/src" -name '*.java' > "$OUT/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -classpath "$OUT/libxposed-stub" -d "$CLS" @"$OUT/sources.txt"

echo "== dex"
JAVA_VER="$("$JAVA" -version 2>&1 | head -1 || true)"
JAVA_MAJOR="$(printf '%s' "$JAVA_VER" | sed -n 's/.*version "\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\1/p')"
JAVA_MINOR="$(printf '%s' "$JAVA_VER" | sed -n 's/.*version "\([0-9]*\)\.\{0,1\}\([0-9]*\).*/\2/p')"
[ "${JAVA_MAJOR:-0}" -eq 1 ] && JAVA_MAJOR="${JAVA_MINOR:-8}"
echo "   java ${JAVA_MAJOR} ($JAVA_VER)"

D8="$BT/lib/d8.jar"
DEXED=0
if [ "${JAVA_MAJOR:-0}" -ge 11 ] && [ -f "$D8" ]; then
  find "$CLS" -name '*.class' > "$OUT/classes.txt"
  if "$JAVA" -cp "$D8" com.android.tools.r8.D8 --min-api 29 \
      --lib "$ANDROID_JAR" --output "$DEX" @"$OUT/classes.txt"; then
    DEXED=1
  else
    echo "   d8 failed; falling back to dx"
  fi
fi
if [ "$DEXED" -eq 0 ]; then
  if [ -n "${DX_JAR:-}" ] && [ -f "$DX_JAR" ]; then
    "$JAVA" -cp "$DX_JAR" com.android.dx.command.Main --dex --output="$DEX/classes.dex" "$CLS"
  else
    echo "error: no dexer. Use JDK 11+ (d8) or set DX_JAR to a dx.jar (dex-tools 2.4)" >&2
    exit 1
  fi
fi

echo "== aapt2"
"$AAPT2" compile --dir "$MODULE/res" -o "$OUT/res.zip"
"$AAPT2" link -o "$APK/base.apk" --manifest "$MODULE/AndroidManifest.xml" \
  -I "$ANDROID_JAR" -R "$OUT/res.zip" --auto-add-overlay

echo "== pack (jar uf)"
cp "$APK/base.apk" "$APK/unaligned.apk"
"$JAR" uf "$APK/unaligned.apk" -C "$DEX" classes.dex -C "$MODULE/meta" META-INF/xposed

echo "== align"
"$ZIPALIGN" -f 4 "$APK/unaligned.apk" "$APK/aligned.apk"

NAME="xiaobu-unlocker-${VERSION_NAME}-vc${VERSION_CODE}"
if [ -n "${KEYSTORE:-}" ]; then
  echo "== sign"
  "$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:${KS_PASS:?set KS_PASS}" \
    --ks-key-alias "${KEY_ALIAS:-xiaobu}" \
    --out "$APK/$NAME.apk" "$APK/aligned.apk"
  "$APKSIGNER" verify "$APK/$NAME.apk" >/dev/null
else
  echo "== unsigned (set KEYSTORE/KS_PASS/KEY_ALIAS to sign)"
  cp "$APK/aligned.apk" "$APK/$NAME-unsigned.apk"
fi

echo "== done"
ls -l "$APK"/*.apk
