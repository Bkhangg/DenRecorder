#!/bin/bash
# Build a signed APK without Gradle.
#   ./build.sh            (package names / versions come from app.properties)
# Needs: javac, d8, aapt2, apksigner, keytool, python3 and an Android SDK
# with platforms/android-36/android.jar.
set -euo pipefail
P=$(cd "$(dirname "$0")" && pwd)

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
if [ -f "$SDK/android-36/android.jar" ]; then
  SDK_JAR=$SDK/android-36/android.jar
elif [ -f "$SDK/platforms/android-36/android.jar" ]; then
  SDK_JAR=$SDK/platforms/android-36/android.jar
else
  echo "android.jar (API 36) not found under $SDK" >&2
  echo "Set ANDROID_HOME to your SDK folder." >&2
  exit 1
fi

for t in javac d8 aapt2 apksigner keytool python3; do
  command -v "$t" >/dev/null || { echo "$t is missing" >&2; exit 1; }
done

KEYDIR=${KEYDIR:-$HOME/.denrecorder-keys}
OUT=$P/build
prop() { [ -f "$P/app.properties" ] && sed -n "s/^$1=//p" "$P/app.properties" | head -1 || true; }
VCODE=$(prop versionCode); VCODE=${VCODE:-1}
VNAME=$(prop versionName); VNAME=${VNAME:-1.0}
MINSDK=$(prop minSdk); MINSDK=${MINSDK:-22}

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "1/5 Resources"
RES=()
if [ -d "$P/res" ] && [ -n "$(ls -A "$P/res")" ]; then
  aapt2 compile --dir "$P/res" -o "$OUT/res.zip"
  RES=("$OUT/res.zip")
fi
ASSETS=()
[ -d "$P/assets" ] && ASSETS=(-A "$P/assets")
aapt2 link -I "$SDK_JAR" --manifest "$P/AndroidManifest.xml" \
  --min-sdk-version "$MINSDK" --target-sdk-version 36 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "${ASSETS[@]}" --java "$OUT/gen" -o "$OUT/base.apk" "${RES[@]}"

echo "2/5 Java"
javac --release 8 -classpath "$SDK_JAR" -Xlint:-options -encoding UTF-8 -nowarn \
  -d "$OUT/classes" $(find "$P/java" "$OUT/gen" -name '*.java')

echo "3/5 Dex"
d8 --release --min-api "$MINSDK" --lib "$SDK_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

echo "4/5 Package"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" "$OUT/aligned.apk" <<'EOF'
import sys, zipfile
src, dex, dst = sys.argv[1:]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    entries.append((zipfile.ZipInfo("classes.dex"), open(dex, "rb").read()))
    entries[-1][0].compress_type = zipfile.ZIP_DEFLATED
    for info, data in entries:
        out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        out.compress_type = info.compress_type
        out.external_attr = info.external_attr
        if out.compress_type == zipfile.ZIP_STORED:  # keep resources.arsc 4-byte aligned
            start = zout.fp.tell() + 30 + len(out.filename.encode())
            out.extra = b"\0" * ((-start) % 4)
        zout.writestr(out, data)
EOF

echo "5/5 Sign"
if [ ! -f "$KEYDIR/apps.jks" ]; then
  mkdir -p "$KEYDIR"; chmod 700 "$KEYDIR"
  head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24 > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
  keytool -genkeypair -keystore "$KEYDIR/apps.jks" -alias apps -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$(cat "$KEYDIR/password")" \
    -keypass "$(cat "$KEYDIR/password")" -dname "CN=DenRecorder" >/dev/null 2>&1
fi
apksigner sign --ks "$KEYDIR/apps.jks" --ks-key-alias apps \
  --ks-pass "file:$KEYDIR/password" --out "$OUT/app.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/app.apk"
cp "$OUT/app.apk" "$OUT/DenRecorder-$VNAME.apk"
echo "Built $OUT/DenRecorder-$VNAME.apk"
