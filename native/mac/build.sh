#!/usr/bin/env bash
# Builds the macOS sound library (wiiuu_audio.m) for Apple Silicon and Intel in one file:
#   native/mac/build.sh [out.dylib]      (default: resources/mac/libwiiuu-audio.dylib)
# Runs on a Mac with the Xcode Command Line Tools and a JDK (for jni.h): GitHub Actions does it
# (.github/workflows/mac-native.yml), so people running WII-UU never need a compiler.
set -euo pipefail
cd "$(dirname "$0")/../.."
out="${1:-resources/mac/libwiiuu-audio.dylib}"
jdk="${JAVA_HOME:-$(/usr/libexec/java_home)}"
mkdir -p "$(dirname "$out")"
clang -dynamiclib -fobjc-arc -O2 -Wall -Werror=objc-method-access \
  -arch arm64 -arch x86_64 -mmacosx-version-min=13.0 \
  -I"$jdk/include" -I"$jdk/include/darwin" \
  -framework Foundation -framework CoreAudio -framework CoreMedia -framework ScreenCaptureKit \
  -install_name @rpath/libwiiuu-audio.dylib \
  -o "$out" native/mac/wiiuu_audio.m
# an ad-hoc signature, which Apple Silicon needs to load it
codesign --force --sign - "$out"
lipo -info "$out"
