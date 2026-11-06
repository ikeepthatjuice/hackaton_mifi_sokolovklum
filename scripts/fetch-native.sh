#!/usr/bin/env bash
# Fetches the pinned sherpa-onnx Android prebuilt into external/sherpa-onnx-android/:
#   include/sherpa-onnx/c-api/c-api.h
#   jniLibs/{arm64-v8a,x86_64}/{libsherpa-onnx-c-api.so,libonnxruntime.so}
# The release archive has only .so files, so the header is taken from the same tag.
# Both files are checked by SHA-256; a mismatch fails the script. Idempotent.
set -euo pipefail

VERSION=v2.0.1
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/$VERSION/sherpa-onnx-$VERSION-android.tar.bz2"
ARCHIVE_SHA256=39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330
HEADER_URL="https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/$VERSION/sherpa-onnx/c-api/c-api.h"
HEADER_SHA256=5698f38ba09ad2bdf085c56a6cbbd7741acf15e25f4ce5c7097b9702cadf437f
ABIS=(arm64-v8a x86_64) # must match abiFilters in the Gradle modules
# Only what the C API needs; jni/cxx-api .so would add APK weight and risk duplicates.
LIBS=(libsherpa-onnx-c-api.so libonnxruntime.so)

ROOT=$(git rev-parse --show-toplevel)
DEST="$ROOT/external/sherpa-onnx-android"
STAMP="$DEST/.version"

if [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$VERSION" ]; then
  echo "sherpa-onnx $VERSION already in $DEST"
  exit 0
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

fetch() { # url sha256 out
  curl -fsSL --retry 3 -o "$3" "$1"
  echo "$2  $3" | shasum -a 256 -c - >/dev/null || { echo "SHA-256 mismatch: $1" >&2; exit 1; }
}

fetch "$ARCHIVE_URL" "$ARCHIVE_SHA256" "$tmp/sherpa.tar.bz2"
fetch "$HEADER_URL" "$HEADER_SHA256" "$tmp/c-api.h"

tar -xjf "$tmp/sherpa.tar.bz2" -C "$tmp"

rm -rf "$DEST"
mkdir -p "$DEST/include/sherpa-onnx/c-api"
cp "$tmp/c-api.h" "$DEST/include/sherpa-onnx/c-api/"
for abi in "${ABIS[@]}"; do
  mkdir -p "$DEST/jniLibs/$abi"
  for lib in "${LIBS[@]}"; do
    cp "$tmp/jniLibs/$abi/$lib" "$DEST/jniLibs/$abi/"
  done
done
echo "$VERSION" > "$STAMP"
echo "sherpa-onnx $VERSION -> $DEST"
