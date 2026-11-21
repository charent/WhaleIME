#Requires -Version 7
<#
.SYNOPSIS
    一键编译并部署 debug APK，不重启模拟器。
.DESCRIPTION
    - 默认先执行 :app:assembleDebug 编译最新 debug APK，再安装；
    - 已有可用的 adb 设备（模拟器优先，其次真机）→ 直接安装编译产物；
    - 一个可用设备都没有 → 自动调用 scripts\start-emulator.ps1 拉起模拟器后再装；
    - 模拟器已在运行时绝不会重启它。
.PARAMETER NoBuild
    跳过 Gradle 编译，直接安装 app/build/outputs/apk/debug 下最新的 APK。
.PARAMETER ApkPath
    显式指定 APK 路径。指定后不再编译，直接安装该文件。
.EXAMPLE
    pwsh -File scripts\deploy-apk.ps1                # 编译 debug 并安装
    pwsh -File scripts\deploy-apk.ps1 -EnableIme     # 编译安装后顺带把小鲸鱼设为默认输入法
    pwsh -File scripts\deploy-apk.ps1 -NoBuild       # 不编译，直接装现有最新 debug APK
    pwsh -File scripts\deploy-apk.ps1 -ApkPath X.apk # 安装指定 APK
#>
param(
    # 装完把小鲸鱼输入法设为默认（测试输入法时建议开着）
    [switch]$EnableIme,
    # 显式指定 APK 路径；缺省先编译再取 app/build/outputs/apk/debug 下最新的
    [string]$ApkPath = '',
    # 跳过 Gradle 编译，直接安装现有 debug APK
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
function Info([string]$m) { Write-Host "  $m" }
function Ok([string]$m)   { Write-Host "  [ok]   $m" -f Green }
function Die([string]$m)  { Write-Host "  [FAIL] $m" -f Red; throw $m }

$Repo = Split-Path -Parent $PSScriptRoot
$ImeId = 'com.whale.inputmethod.debug/com.whale.ime.ui.service.ImeService'

# 从环境变量 / local.properties / 平台默认位置定位 Android SDK
. (Join-Path $PSScriptRoot 'lib\android-sdk.ps1')

$Sdk = Resolve-SdkRoot -Repo $Repo
if (-not $Sdk) {
    Die '找不到 Android SDK：请设置 ANDROID_SDK_ROOT，或在 local.properties 写 sdk.dir（模板见 local.properties.example）'
}
$AdbExe = Join-Path $Sdk 'platform-tools\adb.exe'

# 默认先编译 debug APK（-ApkPath / -NoBuild 时跳过）
if (-not $ApkPath -and -not $NoBuild) {
    $Gradlew = if ($IsWindows) { Join-Path $Repo 'gradlew.bat' } else { Join-Path $Repo 'gradlew' }
    if (-not (Test-Path -LiteralPath $Gradlew)) { Die "找不到 Gradle wrapper: $Gradlew" }
    Info '编译 debug APK (:app:assembleDebug) ...'
    Push-Location $Repo
    try {
        & $Gradlew '--console=plain' ':app:assembleDebug'
    } finally {
        Pop-Location
    }
    if ($LASTEXITCODE -ne 0) { Die 'Gradle 编译失败（上方为 Gradle 输出）' }
    Ok '编译完成'
}

# 找一个就绪的设备：优先模拟器，其次任意真机
function Get-ReadySerial {
    $lines = @(& $AdbExe devices 2>$null)
    foreach ($l in $lines) {
        if ($l -match '^(emulator-\d+)\s+device\s*$') { return $Matches[1] }
    }
    foreach ($l in $lines) {
        if ($l -match '^(\S+)\s+device\s*$') { return $Matches[1] }
    }
    return $null
}

$serial = Get-ReadySerial
if (-not $serial) {
    Info '没有可用设备，自动拉起模拟器（含装包）...'
    & (Join-Path $PSScriptRoot 'start-emulator.ps1') -InstallApk -EnableIme:$EnableIme
    if ($LASTEXITCODE -ne 0) { Die '模拟器启动失败' }
    exit 0
}

Info "目标设备: $serial"

# 选 APK：显式指定优先，否则取 debug 输出目录里最新的
if (-not $ApkPath) {
    $dir = Join-Path $Repo 'app\build\outputs\apk\debug'
    $apk = Get-ChildItem -LiteralPath $dir -Filter *.apk -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Die "未找到 debug APK: $dir（去掉 -NoBuild 让脚本先编译，或手动执行 :app:assembleDebug）" }
    $ApkPath = $apk.FullName
}
if (-not (Test-Path -LiteralPath $ApkPath)) { Die "APK 不存在: $ApkPath" }

Info "安装 $(Split-Path -Leaf $ApkPath)..."
& $AdbExe -s $serial install -r $ApkPath
if ($LASTEXITCODE -ne 0) { Die 'APK 安装失败' }
Ok "已安装到 $serial"

if ($EnableIme) {
    & $AdbExe -s $serial shell ime enable $ImeId | Out-Null
    & $AdbExe -s $serial shell ime set $ImeId | Out-Null
    # 模拟器会向 Android 暴露 PS/2 硬键盘，不加这条软键盘不显示
    & $AdbExe -s $serial shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
    Ok "输入法已激活: $ImeId"
}
