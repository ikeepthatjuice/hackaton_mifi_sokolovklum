#!/usr/bin/env bash
# Падает, если в git отслеживаются аудио, веса, ключи или каталоги данных кейса.
# Запускать перед каждым PR; то же самое делает CI.
set -euo pipefail

pattern='\.(wav|mp3|m4a|flac|ogg|opus|aac|bin|onnx|gguf|pt|tflite|jks|keystore)$|(^|/)(case_data|local_models|local_outputs|local_recordings|local_reports|secrets)/|(^|/)\.env($|\.)|(^|/)local\.properties$'

found=$(git ls-files | grep -E "$pattern" | grep -v -E '(^|/)\.env\.example$' || true)

if [ -n "$found" ]; then
  echo "Запрещённые файлы в git:" >&2
  echo "$found" >&2
  exit 1
fi
echo "OK: запрещённых файлов нет"
