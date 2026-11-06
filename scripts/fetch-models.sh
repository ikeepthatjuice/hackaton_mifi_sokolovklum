#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="${1:-local_models}"
mkdir -p "$DEST_DIR"

echo "=== Fetching ML models into $DEST_DIR ==="

declare -A MODELS=(
    ["ggml-base.bin"]="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin 60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec"
    ["sherpa-onnx-pyannote-segmentation-3-0.onnx"]="https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx 893c52e411b439c2c8f85f3ce010a300da58908ec8e0f6c278fb7785beab4aa7"
    ["3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"]="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx 4b68079a4058f967ad7f5e8e3c54d398f51a44e5914fa6463994326588db2b90"
)

for FILE_NAME in "${!MODELS[@]}"; do
    INFO=(${MODELS[$FILE_NAME]})
    URL="${INFO[0]}"
    EXPECTED_SHA="${INFO[1]}"
    TARGET_PATH="$DEST_DIR/$FILE_NAME"

    echo "--- Checking $FILE_NAME ---"
    if [ -f "$TARGET_PATH" ]; then
        ACTUAL_SHA=$(sha256sum "$TARGET_PATH" | awk '{print $1}')
        if [ "$ACTUAL_SHA" == "$EXPECTED_SHA" ]; then
            echo "Already downloaded and checksum verified: $FILE_NAME"
            continue
        else
            echo "Checksum mismatch for $FILE_NAME, re-downloading..."
        fi
    fi

    echo "Downloading $FILE_NAME from $URL..."
    curl -L --retry 3 -o "$TARGET_PATH" "$URL"

    ACTUAL_SHA=$(sha256sum "$TARGET_PATH" | awk '{print $1}')
    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        echo "ERROR: Checksum mismatch for $FILE_NAME!"
        echo "Expected: $EXPECTED_SHA"
        echo "Actual:   $ACTUAL_SHA"
        exit 1
    fi
    echo "Verified SHA-256 for $FILE_NAME."
done

echo "=== All models successfully downloaded and verified ==="
