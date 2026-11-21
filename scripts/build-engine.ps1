#Requires -Version 7
<#
.SYNOPSIS
    Root-level entry point for building the native Rime engine (libwhaleime.so).
.DESCRIPTION
    Forwards to ime-native\native\scripts\build-engine.ps1, so the engine can be
    built from the repo root. The output .so files are written directly into
    ime-native\src\main\jniLibs\<abi>\ and picked up by Gradle automatically
    (that is AGP's default jniLibs dir, no sourceSets override).

    First run on a clean checkout:  pwsh -File scripts\build-engine.ps1 -FetchSources
    JNI-only fast loop (~20s/ABI):  pwsh -File scripts\build-engine.ps1 -SkipDeps

    ABI: 只保留 64 位（arm64-v8a + x86_64，默认两个一起编，先 x86_64 后 arm64-v8a）。
    32 位（armeabi-v7a / x86）支持已移除，传其它 ABI 会直接报错。

    Environment: NDK 28.0.13004108, CMake/Ninja 3.31.6 (under the Android SDK dir),
    SDK location resolved from local.properties / ANDROID_SDK_ROOT / ANDROID_HOME.
    Full reference: ime-native\native\README.md and docs\engine-upgrade-implementation.md
#>
param(
    # Target ABIs, comma-separated (arm64-v8a,x86_64)
    [string]$Abi = 'arm64-v8a,x86_64',
    # Android API level (minSdk)
    [int]$ApiLevel = 23,
    # Skip dependency build (librime + third-party static libs already in prebuilt\)
    [switch]$SkipDeps,
    # Delete native-build\build\<abi>\jni first: full recompile, verifies the whole toolchain
    [switch]$Clean,
    # Run fetch-sources.ps1 first (downloads 6 source tarballs, boost is 141 MB)
    [switch]$FetchSources
)

$ErrorActionPreference = 'Stop'

$EngineScripts = Join-Path (Split-Path -Parent $PSScriptRoot) 'ime-native\native\scripts'
$BuildEngine = Join-Path $EngineScripts 'build-engine.ps1'
if (-not (Test-Path $BuildEngine)) {
    throw "engine build script not found: $BuildEngine"
}

if ($FetchSources) {
    $Fetch = Join-Path $EngineScripts 'fetch-sources.ps1'
    if (-not (Test-Path $Fetch)) { throw "fetch script not found: $Fetch" }
    Write-Host '=== fetching engine sources ===' -ForegroundColor Cyan
    & pwsh -NoProfile -File $Fetch
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$engineArgs = @('-NoProfile', '-File', $BuildEngine, '-Abi', $Abi, '-ApiLevel', $ApiLevel)
if ($SkipDeps) { $engineArgs += '-SkipDeps' }
if ($Clean) { $engineArgs += '-Clean' }
& pwsh @engineArgs
exit $LASTEXITCODE
