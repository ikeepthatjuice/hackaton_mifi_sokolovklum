#!/usr/bin/env bash
# Downloads model weights listed in models/manifest.json (the single source of url/size/sha256)
# into DEST_DIR (default local_models) and verifies size and SHA-256. Idempotent.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
DEST_DIR="${1:-local_models}"
mkdir -p "$DEST_DIR"

echo "=== Fetching ML models into $DEST_DIR ==="

python3 -I - "$ROOT/models/manifest.json" "$DEST_DIR" <<'PY'
import hashlib, json, os, subprocess, sys

manifest, dest = sys.argv[1], sys.argv[2]

def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def ok(path, size, sha):
    return os.path.isfile(path) and os.path.getsize(path) == size and digest(path) == sha

for m in json.load(open(manifest))["models"]:
    name, url, size, sha = m["target_file"], m["source_url"], m["size_bytes"], m["sha256"].lower()
    path = os.path.join(dest, name)
    print(f"--- Checking {name} ---", flush=True)
    if ok(path, size, sha):
        print(f"Already downloaded and verified: {name}")
        continue
    print(f"Downloading {name} from {url}", flush=True)
    tmp = path + ".part"
    # curl, not urllib: uses system CA store and retries.
    subprocess.run(["curl", "-fL", "--retry", "3", "-o", tmp, url], check=True)
    actual_size, actual_sha = os.path.getsize(tmp), digest(tmp)
    if actual_size != size or actual_sha != sha:
        os.remove(tmp)
        sys.exit(f"ERROR: {name} mismatch\n  expected size={size} sha256={sha}\n  actual   size={actual_size} sha256={actual_sha}")
    os.replace(tmp, path)
    print(f"Verified size and SHA-256: {name}")
PY

echo "=== All models downloaded and verified ==="
