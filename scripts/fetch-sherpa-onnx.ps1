$ErrorActionPreference = "Stop"

$destDir = "external/sherpa-onnx-android"
$version = "1.10.30"
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-v$version-android.tar.bz2"
$archiveName = "external/sherpa-onnx-v$version-android.tar.bz2"

if (!(Test-Path "external")) {
    New-Item -ItemType Directory -Path "external" | Out-Null
}

Write-Host "=== Fetching prebuilt sherpa-onnx v$version for Android ===" -ForegroundColor Cyan
if (!(Test-Path "$destDir/jniLibs")) {
    Write-Host "Downloading $url..."
    Invoke-WebRequest -Uri $url -OutFile $archiveName -MaximumRedirection 5
    
    Write-Host "Unpacking into $destDir..."
    if (!(Test-Path $destDir)) {
        New-Item -ItemType Directory -Path $destDir | Out-Null
    }
    tar -xjf $archiveName -C $destDir --strip-components=1
    Remove-Item -Force $archiveName
    Write-Host "Successfully installed sherpa-onnx prebuilt." -ForegroundColor Green
} else {
    Write-Host "Prebuilt sherpa-onnx already present at $destDir." -ForegroundColor Green
}

