#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="external/sherpa-onnx-android"
VERSION="1.10.30"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${VERSION}/sherpa-onnx-v${VERSION}-android.tar.bz2"
ARCHIVE_NAME="sherpa-onnx-v${VERSION}-android.tar.bz2"

mkdir -p external

echo "=== Fetching prebuilt sherpa-onnx v${VERSION} for Android ==="
if [ ! -d "$DEST_DIR/jniLibs" ]; then
    echo "Downloading $URL..."
    curl -L --retry 3 -o "external/$ARCHIVE_NAME" "$URL"
    
    echo "Unpacking into $DEST_DIR..."
    mkdir -p "$DEST_DIR"
    tar -xjf "external/$ARCHIVE_NAME" -C "$DEST_DIR" --strip-components=1
    rm -f "external/$ARCHIVE_NAME"
    echo "Successfully installed sherpa-onnx prebuilt."
else
    echo "Prebuilt sherpa-onnx already present at $DEST_DIR."
fi

