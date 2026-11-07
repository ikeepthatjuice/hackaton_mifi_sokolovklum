param (
    [string]$DestDir = "local_models"
)

# Downloads model weights listed in models/manifest.json (the single source of url/size/sha256)
# into $DestDir and verifies size and SHA-256. Idempotent.
$ErrorActionPreference = "Stop"

$manifest = Join-Path $PSScriptRoot "..\models\manifest.json"
$models = (Get-Content -Raw $manifest | ConvertFrom-Json).models

if (!(Test-Path $DestDir)) {
    New-Item -ItemType Directory -Path $DestDir | Out-Null
}

Write-Host "=== Fetching ML models into $DestDir ===" -ForegroundColor Cyan

function Test-Model($path, $m) {
    (Test-Path $path) -and
        ((Get-Item $path).Length -eq $m.size_bytes) -and
        ((Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower() -eq $m.sha256.ToLower())
}

foreach ($m in $models) {
    $targetPath = Join-Path $DestDir $m.target_file
    Write-Host "--- Checking $($m.target_file) ---"

    if (Test-Model $targetPath $m) {
        Write-Host "Already downloaded and verified: $($m.target_file)" -ForegroundColor Green
        continue
    }

    Write-Host "Downloading $($m.target_file) from $($m.source_url)"
    $tmp = "$targetPath.part"
    Invoke-WebRequest -Uri $m.source_url -OutFile $tmp -MaximumRedirection 5
    if (!(Test-Model $tmp $m)) {
        $size = (Get-Item $tmp).Length
        $sha = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash.ToLower()
        Remove-Item $tmp
        Write-Error "ERROR: $($m.target_file) mismatch. Expected size=$($m.size_bytes) sha256=$($m.sha256); actual size=$size sha256=$sha"
    }
    Move-Item -Force $tmp $targetPath
    Write-Host "Verified size and SHA-256: $($m.target_file)" -ForegroundColor Green
}

Write-Host "=== All models downloaded and verified ===" -ForegroundColor Cyan
