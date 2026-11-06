$ErrorActionPreference = "Stop"

$destDir = "external/sherpa-onnx-android"
$version = "1.10.30"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-v$version-android.tar.bz2"
$archivePath = "external/sherpa-onnx-v$version-android.tar.bz2"
$expectedSha = "ebca29196a638938d3178fc43f90580486cd22813ab1eb0b269c6b7d7547ff16"

if (!(Test-Path "external")) {
    New-Item -ItemType Directory -Path "external" | Out-Null
}

Write-Host "=== Fetching prebuilt sherpa-onnx v$version for Android ===" -ForegroundColor Cyan
if (!(Test-Path "$destDir/jniLibs")) {
    Write-Host "Downloading $url..."
    Invoke-WebRequest -Uri $url -OutFile $archivePath -MaximumRedirection 5

    Write-Host "Verifying SHA-256 checksum..."
    $actualSha = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash.ToLower()
    if ($actualSha -ne $expectedSha.ToLower()) {
        Remove-Item -Force $archivePath
        Write-Error "ERROR: SHA-256 mismatch! Expected: $expectedSha, Actual: $actualSha"
    }
    Write-Host "Checksum verified: $actualSha" -ForegroundColor Green

    Write-Host "Unpacking into $destDir..."
    if (!(Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir | Out-Null
    }
    tar -xjf $archivePath -C $destDir --strip-components=1
    Remove-Item -Force $archivePath
    Write-Host "Successfully installed sherpa-onnx prebuilt." -ForegroundColor Green
} else {
    Write-Host "Prebuilt sherpa-onnx already present at $destDir." -ForegroundColor Green
}
