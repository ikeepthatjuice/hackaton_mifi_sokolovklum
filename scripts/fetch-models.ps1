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
        Name = "silero_vad.onnx"
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/silero_vad.onnx"
        Sha256 = "7a3563969bcbe63cd3187b8f04746f34316279f6e6aa45550dfb0b92d6e4b854"
    },
    @{
        Name = "3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx"
        Url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_ru_voxceleb_16k.onnx"
        Sha256 = "3cb40ff8d1796d11f6c4d7ecfef0f74577881c1626f2f01fbf9f42d2a45a31a9"
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

