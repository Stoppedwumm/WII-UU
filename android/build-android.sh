#!/bin/sh
# Builds WII-UU for Android (android/build/WII-UU-Android.apk) straight from the Android SDK's own
# tools, no Gradle: the native Choccy Milk library with the NDK (arm64-v8a, armeabi-v7a, x86_64),
# the resources with aapt2, the Java with javac and d8, then zipalign and apksigner.
#
#   ANDROID_HOME   the SDK (default /opt/android-sdk): platforms/android-35, build-tools/<any>
#   ANDROID_NDK    the NDK (default: the newest android-ndk-* or ndk/* in ANDROID_HOME)
#   WIIUU_KEYSTORE the signing key (default ~/.wiiuu-android.keystore, made the first time;
#                  WIIUU_KEYSTORE_PASS its password, default "wiiuu-android")
#   VERSION_NAME / VERSION_CODE  (default 1.0 / 1)
set -eu
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-/opt/android-sdk}
PLATFORM=$SDK/platforms/android-35/android.jar
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
NDK=${ANDROID_NDK:-$(ls -d "$SDK"/android-ndk-* "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
KS=${WIIUU_KEYSTORE:-$HOME/.wiiuu-android.keystore}
PASS=${WIIUU_KEYSTORE_PASS:-wiiuu-android}
VN=${VERSION_NAME:-1.0}
VC=${VERSION_CODE:-1}
OUT=build
[ -f "$PLATFORM" ] || { echo "no $PLATFORM (set ANDROID_HOME)"; exit 1; }
[ -x "$TC/clang" ] || { echo "no NDK in $NDK (set ANDROID_NDK)"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/apk" "$OUT/classes" "$OUT/dex"

echo "== native: Choccy Milk"
for abi in arm64-v8a:aarch64-linux-android26 armeabi-v7a:armv7a-linux-androideabi26 x86_64:x86_64-linux-android26; do
    name=${abi%%:*}
    target=${abi##*:}
    mkdir -p "$OUT/apk/lib/$name"
    "$TC/$target-clang" -O2 -fPIC -shared -Wall -Wextra -std=c11 -o "$OUT/apk/lib/$name/libchoccy.so" \
        jni/choccy_android.c jni/choccy_core.c -laaudio -landroid -llog -ldl -Wl,-z,max-page-size=16384 -s
done

echo "== resources"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -I "$PLATFORM" --manifest AndroidManifest.xml -o "$OUT/base.apk" "$OUT/res.zip" \
    --version-name "$VN" --version-code "$VC" --min-sdk-version 26 --target-sdk-version 34

echo "== java"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" -encoding UTF-8 -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== apk"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cp "$OUT/dex/classes.dex" "$OUT/apk/"
(cd "$OUT/apk" && zip -qr ../unsigned.apk classes.dex lib)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$KS" ]; then
    echo "== a new signing key: $KS (keep it: updates must be signed with the same one)"
    keytool -genkeypair -keystore "$KS" -storepass "$PASS" -keypass "$PASS" -alias wiiuu -keyalg RSA -keysize 3072 \
        -validity 20000 -dname "CN=WII-UU, O=WII-UU" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$PASS" --ks-key-alias wiiuu --out "$OUT/WII-UU-Android.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$OUT/WII-UU-Android.apk"
ls -la "$OUT/WII-UU-Android.apk"
