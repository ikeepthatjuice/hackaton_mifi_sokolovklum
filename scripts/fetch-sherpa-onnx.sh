#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="external/sherpa-onnx-android"
VERSION="1.10.30"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-v${VERSION}-android.tar.bz2"
ARCHIVE_PATH="external/sherpa-onnx-v${VERSION}-android.tar.bz2"
EXPECTED_SHA="ebca29196a638938d3178fc43f90580486cd22813ab1eb0b269c6b7d7547ff16"

mkdir -p external

echo "=== Fetching prebuilt sherpa-onnx v${VERSION} for Android ==="
if [ ! -d "$DEST_DIR/jniLibs" ]; then
    echo "Downloading $URL..."
    curl -L --retry 3 -o "$ARCHIVE_PATH" "$URL"

    echo "Verifying SHA-256 checksum..."
    ACTUAL_SHA=$(sha256sum "$ARCHIVE_PATH" | awk '{print $1}')
    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        echo "ERROR: SHA-256 mismatch for sherpa-onnx prebuilt archive!"
        echo "Expected: $EXPECTED_SHA"
        echo "Actual:   $ACTUAL_SHA"
        rm -f "$ARCHIVE_PATH"
        exit 1
    fi
    echo "Checksum verified: $ACTUAL_SHA"

    echo "Unpacking into $DEST_DIR..."
    mkdir -p "$DEST_DIR"
    tar -xjf "$ARCHIVE_PATH" -C "$DEST_DIR" --strip-components=1
    rm -f "$ARCHIVE_PATH"
    echo "Successfully installed sherpa-onnx prebuilt."
else
    echo "Prebuilt sherpa-onnx already present at $DEST_DIR."
fi
