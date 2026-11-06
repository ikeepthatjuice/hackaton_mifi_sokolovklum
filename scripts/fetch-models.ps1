param (
    [string]$DestDir = "local_models"
)

$ErrorActionPreference = "Stop"

if (!(Test-Path $DestDir)) {
    New-Item -ItemType Directory -Path $DestDir | Out-Null
}

Write-Host "=== Fetching ML models into $DestDir ===" -ForegroundColor Cyan

$models = @(
    @{
        Name = "ggml-base.bin"
        Url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
        Sha256 = "60ed5bc3dd14eea856493d334349b405782ddcaf00eec29073ba81a4d4ea8aec"
    },
    @{
        Name = "sherpa-onnx-pyannote-segmentation-3-0.onnx"
        Url = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.onnx"
        Sha256 = "893c52e411b439c2c8f85f3ce010a300da58908ec8e0f6c278fb7785beab4aa7"
    },
    @{
        Name = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        Sha256 = "4b68079a4058f967ad7f5e8e3c54d398f51a44e5914fa6463994326588db2b90"
    }
)

foreach ($item in $models) {
    $targetPath = Join-Path $DestDir $item.Name
    Write-Host "--- Checking $($item.Name) ---"

    $needDownload = $true
    if (Test-Path $targetPath) {
        $actualSha = (Get-FileHash -Path $targetPath -Algorithm SHA256).Hash.ToLower()
        if ($actualSha -eq $item.Sha256.ToLower()) {
            Write-Host "Already downloaded and checksum verified: $($item.Name)" -ForegroundColor Green
            $needDownload = $false
        } else {
            Write-Host "Checksum mismatch for $($item.Name), re-downloading..." -ForegroundColor Yellow
        }
    }

    if ($needDownload) {
        Write-Host "Downloading $($item.Name)..."
        Invoke-WebRequest -Uri $item.Url -OutFile $targetPath -MaximumRedirection 5
        $actualSha = (Get-FileHash -Path $targetPath -Algorithm SHA256).Hash.ToLower()
        if ($actualSha -ne $item.Sha256.ToLower()) {
            Write-Error "ERROR: Checksum mismatch for $($item.Name)! Expected: $($item.Sha256), Actual: $actualSha"
        }
        Write-Host "Verified SHA-256 for $($item.Name)." -ForegroundColor Green
    }
}

Write-Host "=== All models downloaded and verified ===" -ForegroundColor Cyan
