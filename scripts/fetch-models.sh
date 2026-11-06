#!/usr/bin/env bash
set -euo pipefail

DEST_DIR="${1:-local_models}"
mkdir -p "$DEST_DIR"

echo "=== Fetching ML models into $DEST_DIR ==="

declare -A MODELS=(
    ["ggml-base.bin"]="https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin 60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec"
    ["silero_vad.onnx"]="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/silero_vad.onnx 7a3563969bcbe63cd3187b8f04746f34316279f6e6aa45550dfb0b92d6e4b854"
    ["3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx"]="https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx 3cb40ff8d1796d11f6c4d7ecfef0f74577881c1626f2f01fbf9f42d2a45a31a9"
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

