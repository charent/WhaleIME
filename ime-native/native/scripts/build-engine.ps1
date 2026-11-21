# 一键构建原生引擎：依赖 → librime → JNI → 覆盖 src/main/jniLibs/<abi>/libwhaleime.so
#
# 用法：
#   pwsh -File scripts\build-engine.ps1                                   # 默认 arm64-v8a + x86_64
#   pwsh -File scripts\build-engine.ps1 -Abi x86_64                       # 只编模拟器用的那份
#   pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a -SkipDeps          # 依赖已编好时
#   pwsh -File scripts\build-engine.ps1 -SkipDeps -Clean                 # 强制从头编 JNI（验证工具链）
#
# 说明：
#   - 32 位 ABI（armeabi-v7a / x86）已移除，脚本只接受 arm64-v8a 与 x86_64
#   - 编译顺序固定为先 x86_64、后 arm64-v8a：把 x86_64 装到模拟器上验证通过后，
#     同一次编译产出的 arm64 包不必再单独编译/验证
#   - 首次执行会先跑 build-deps.ps1（需源码，见 fetch-sources.ps1），耗时较长
#   - 产物直接覆盖 ime-native/src/main/jniLibs/<abi>/libwhaleime.so（AGP 约定的默认目录），
#     Gradle 侧无需任何 sourceSets 覆盖
#   - 同时保留未 strip 的版本在 native-build/build/<abi>/jni/
#   - -Clean 删除 native-build/build/<abi>/jni 后重新 configure + 编译，
#     用来确认 NDK/CMake/Ninja/静态库链接整条工具链正常（不做增量跳过）

param(
  [string]$Abi = 'arm64-v8a,x86_64',
  [int]$ApiLevel = 23,
  [switch]$SkipDeps,
  [switch]$Clean
)

$ErrorActionPreference = 'Stop'

# 32 位支持已移除；顺序即编译顺序（x86_64 先编，供模拟器验证）
$BuildOrder = @('x86_64', 'arm64-v8a')

$CppRoot = Split-Path -Parent $PSScriptRoot          # ...\ime-native\native
$ImeNative = Resolve-Path (Join-Path $CppRoot '..')  # ...\ime-native
$NativeRoot = Join-Path $CppRoot 'native-build'

$localProps = Join-Path (Resolve-Path (Join-Path $CppRoot '..\..')) 'local.properties'
$Sdk = $null
if (Test-Path $localProps) {
  $line = (Get-Content $localProps | Select-String '^\s*sdk\.dir').ToString()
  $Sdk = $line.Substring($line.IndexOf('=') + 1).Trim() -replace '\\:', ':' -replace '\\\\', '\'
}
if (-not $Sdk -or -not (Test-Path $Sdk)) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw '未找到 Android SDK' }

$Ndk = Join-Path $Sdk 'ndk\28.0.13004108'
$Cmake = Join-Path $Sdk 'cmake\3.31.6\bin\cmake.exe'
$Ninja = Join-Path $Sdk 'cmake\3.31.6\bin\ninja.exe'
$Strip = Join-Path $Ndk 'toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe'
$Toolchain = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'

$Requested = $Abi -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ }
$Unsupported = $Requested | Where-Object { $BuildOrder -notcontains $_ }
if ($Unsupported) {
  throw "不支持的 ABI: $($Unsupported -join ', ')。32 位（armeabi-v7a / x86）支持已移除，只允许: $($BuildOrder -join ', ')"
}

# 按固定顺序执行，与用户传入顺序无关
$Abis = $BuildOrder | Where-Object { $Requested -contains $_ }

if (-not $SkipDeps) {
  Write-Host "=== 构建依赖（$($Abis -join ', ')）===" -ForegroundColor Cyan
  & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'build-deps.ps1') -Abi ($Abis -join ',') -ApiLevel $ApiLevel
  if ($LASTEXITCODE -ne 0) { throw '依赖构建失败' }
}

foreach ($a in $Abis) {
  $buildDir = Join-Path $NativeRoot "build\$a\jni"
  if ($Clean -and (Test-Path $buildDir)) {
    Write-Host "[$a] -Clean：删除 $buildDir" -ForegroundColor DarkGray
    Remove-Item $buildDir -Recurse -Force
  }
  New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

  Write-Host "`n=== [$a] 编译 JNI ===" -ForegroundColor Cyan
  & $Cmake -S $CppRoot -B $buildDir -G Ninja `
    "-DCMAKE_MAKE_PROGRAM=$Ninja" `
    "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
    "-DANDROID_ABI=$a" `
    "-DANDROID_PLATFORM=android-$ApiLevel" `
    '-DANDROID_STL=c++_static' `
    '-DCMAKE_BUILD_TYPE=Release'
  if ($LASTEXITCODE -ne 0) { throw "[$a] configure 失败" }

  & $Cmake --build $buildDir --parallel
  if ($LASTEXITCODE -ne 0) { throw "[$a] 编译失败" }

  $built = Join-Path $buildDir 'libwhaleime.so'
  if (-not (Test-Path $built)) { throw "[$a] 未生成 libwhaleime.so" }

  $dest = Join-Path $ImeNative "src\main\jniLibs\$a"
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  $stripped = Join-Path $buildDir 'libwhaleime.stripped.so'
  Copy-Item $built $stripped -Force
  & $Strip --strip-unneeded $stripped

  Copy-Item $stripped (Join-Path $dest 'libwhaleime.so') -Force
  Write-Host ("[{0}] -> {1}  {2} MB（strip 后）" -f $a, (Join-Path $dest 'libwhaleime.so'), [math]::Round((Get-Item $stripped).Length/1MB,2)) -ForegroundColor Green
}

Write-Host "`n完成：先装 x86_64 的 debug 包到模拟器验证，通过后可直接用同一次编译的 arm64 包上真机。" -ForegroundColor Green
