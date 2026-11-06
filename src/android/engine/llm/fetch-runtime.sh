#!/usr/bin/env bash
# Explicit preparation step. No downloads occur during inference or CMake configuration.
set -euo pipefail
module_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
dest="$module_dir/external/llama.cpp"
revision=954de248ac6907dd05dc19f0f511ab18f2807a54
if [ -e "$dest" ]; then
  test "$(git -C "$dest" rev-parse HEAD)" = "$revision"
  test -z "$(git -C "$dest" status --porcelain --untracked-files=no)"
else
  mkdir -p "$dest"
  git -C "$dest" init -q
  git -C "$dest" remote add origin https://github.com/ggml-org/llama.cpp.git
  git -C "$dest" fetch --depth 1 origin "$revision"
  git -C "$dest" checkout --detach FETCH_HEAD
fi
echo "llama.cpp revision verified: $revision"
