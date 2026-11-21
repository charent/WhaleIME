#Requires -Version 7
<#
.SYNOPSIS
    一键打包 release APK（R8 混淆 + release 签名 + 签名校验 + 可选安装）。
.DESCRIPTION
    把这个仓库里打包 release 需要人工确认的几件事串成一条命令：

    1) JDK：Gradle 用 Java toolchain 21，低于 21 的 java 会直接编译失败，
       脚本在动手前先检查，避免跑到一半才报错。
    2) 签名：app/build.gradle 的 release config 读 keystore/keystore.properties
       或 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
       RELEASE_KEY_PASSWORD 四个环境变量。两者都缺时 Gradle **不会报错**，
       只会静默产出未签名 APK —— 这是最容易踩的坑，所以脚本先拦截。
    3) 校验：打完用 build-tools 的 apksigner 验签并打印证书指纹，再输出
       文件大小与 SHA256，方便分发前贴到 release notes。
    4) 可选安装：-Install 直接复用 scripts\deploy-apk.ps1 装到当前设备。

    versionCode / versionName 由 app/build.gradle 按打包时间（GMT+8，分钟粒度）
    自动生成，脚本不需要也不应该改版本号。
.PARAMETER Clean
    先执行 gradlew clean 再打包（用到 shrinkResources 时建议带上，避免残留资源）。
.PARAMETER SkipCheck
    跳过签名配置检查，强制打包。产物可能是未签名 APK，仅用于确认构建链路。
.PARAMETER Install
    打包完成后安装到当前设备（没有设备时由 deploy-apk.ps1 自动拉起模拟器）。
.PARAMETER OutDir
    额外把 APK 复制一份到指定目录，例如发布包统一放到 dist（相对路径）。
.EXAMPLE
    pwsh -File scripts\build-release.ps1
.EXAMPLE
    pwsh -File scripts\build-release.ps1 -Clean -Install
.EXAMPLE
    pwsh -File scripts\build-release.ps1 -OutDir dist
#>
param(
    [switch]$Clean,
    [switch]$SkipCheck,
    [switch]$Install,
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
function Info([string]$m) { Write-Host "  $m" }
function Ok([string]$m)   { Write-Host "  [ok]   $m" -f Green }
function Warn([string]$m) { Write-Host "  [warn] $m" -f Yellow }
function Die([string]$m)  { Write-Host "  [FAIL] $m" -f Red; exit 1 }

$Repo = Split-Path -Parent $PSScriptRoot
$Gradlew = if ($IsWindows) { Join-Path $Repo 'gradlew.bat' } else { Join-Path $Repo 'gradlew' }
$ReleaseDir = Join-Path $Repo 'app\build\outputs\apk\release'

if (-not (Test-Path -LiteralPath $Gradlew)) { Die "找不到 Gradle wrapper: $Gradlew" }

# ---------------------------------------------------------------- 1. JDK 检查
Write-Host "`n=== 检查 JDK ===" -ForegroundColor Cyan

function Resolve-Java {
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME 'bin\java.exe'
        if (Test-Path -LiteralPath $p) { return $p }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$java = Resolve-Java
if (-not $java) { Die '找不到 java。请安装 JDK 21 并设置 JAVA_HOME。' }

$javaOut = (& $java -version 2>&1 | Out-String)
if ($javaOut -match 'version "(\d+)') {
    $javaMajor = [int]$Matches[1]
    if ($javaMajor -lt 21) {
        Die "当前 JDK $javaMajor ($java) 低于项目要求（Java 21）。请切换 JAVA_HOME 后重试。"
    }
    Ok "JDK $javaMajor  ($java)"
} else {
    Warn "无法解析 java 版本，继续构建：`n$javaOut"
}

# ---------------------------------------------------------------- 2. 签名配置检查
Write-Host "`n=== 检查 release 签名 ===" -ForegroundColor Cyan

$ksProps = Join-Path $Repo 'keystore\keystore.properties'
$envNames = @('RELEASE_STORE_FILE', 'RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD')
$hasEnv = ($envNames | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }).Count -eq 0
$hasProps = Test-Path -LiteralPath $ksProps

$signingProblem = $null
if (-not $hasProps -and -not $hasEnv) {
    $signingProblem = @"
    keystore/keystore.properties 不存在，且 $($envNames -join ' / ') 环境变量不完整。
    Gradle 不会报错，只会产出**未签名** APK（无法安装/分发）。
    任选其一：
      a) 新建 keystore/keystore.properties（storeFile / storePassword / keyAlias / keyPassword）
      b) 设置上面四个 RELEASE_* 环境变量
      c) 加 -SkipCheck 仅验证构建流程
"@
} elseif ($hasProps) {
    # properties 里的 storeFile 是相对仓库根解析的，提前确认 .jks 真的在
    $prop = @{}
    Get-Content -LiteralPath $ksProps | Where-Object { $_ -match '^\s*[^#\s][^=]*=' } | ForEach-Object {
        $kv = $_ -split '=', 2
        $prop[$kv[0].Trim()] = $kv[1].Trim()
    }
    $storeRel = if ($env:RELEASE_STORE_FILE) { $env:RELEASE_STORE_FILE } else { $prop['storeFile'] }
    $storePath = if ($storeRel -and [IO.Path]::IsPathRooted($storeRel)) { $storeRel }
                 elseif ($storeRel) { Join-Path $Repo $storeRel }
                 else { $null }
    if (-not $storePath -or -not (Test-Path -LiteralPath $storePath)) {
        $signingProblem = "keystore.properties 里的 storeFile 指向的密钥库不存在: $storePath"
    } else {
        Ok "密钥库: $storePath"
    }
}

if ($signingProblem) {
    if ($SkipCheck) {
        Warn $signingProblem
        Warn '已指定 -SkipCheck，继续构建（产物可能未签名）。'
    } else {
        Write-Host $signingProblem -f Red
        Die '签名配置不完整，已中止。'
    }
} elseif ($hasEnv) {
    Ok "签名: 使用 RELEASE_* 环境变量"
} else {
    Ok '签名: 使用 keystore/keystore.properties'
}

# ---------------------------------------------------------------- 3. 构建
Write-Host "`n=== 打包 release ===" -ForegroundColor Cyan

$gradleArgs = @('--console=plain')
if ($Clean) {
    Info 'gradlew clean ...'
    & $Gradlew @gradleArgs 'clean'
    if ($LASTEXITCODE -ne 0) { Die 'clean 失败' }
}
Info 'gradlew :app:assembleRelease ...'
& $Gradlew @gradleArgs ':app:assembleRelease'
if ($LASTEXITCODE -ne 0) { Die 'release 构建失败（上方为 Gradle 输出）' }

# ---------------------------------------------------------------- 4. 产物与签名校验
Write-Host "`n=== 产物 ===" -ForegroundColor Cyan

$apk = Get-ChildItem -LiteralPath $ReleaseDir -Filter *.apk -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { Die "构建成功但未找到 APK: $ReleaseDir" }

$sizeMB = [math]::Round($apk.Length / 1MB, 2)
Ok "APK: $($apk.FullName)"
Info "大小: $sizeMB MB"
Info "时间: $($apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"

# apksigner：从环境变量 / local.properties / 平台默认位置定位 SDK，取最高版本 build-tools
. (Join-Path $PSScriptRoot 'lib\android-sdk.ps1')

$sdk = Resolve-SdkRoot -Repo $Repo
$apksigner = $null
if ($sdk) {
    $btRoot = Join-Path $sdk 'build-tools'
    if (Test-Path -LiteralPath $btRoot) {
        $bt = Get-ChildItem -LiteralPath $btRoot -Directory |
            Where-Object { Test-Path (Join-Path $_.FullName 'apksigner.bat') } |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0.0' } } -Descending |
            Select-Object -First 1
        if ($bt) { $apksigner = Join-Path $bt.FullName 'apksigner.bat' }
    }
}

if ($apksigner) {
    $verify = (& $apksigner verify --print-certs $apk.FullName 2>&1 | Out-String)
    if ($LASTEXITCODE -eq 0) {
        Ok '签名校验通过'
        # 只挑证书 DN 与 SHA-256 两行（不同 build-tools 版本前缀可能是
        # "Signer #1 ..." 或 "V2 Signer: ..."，按关键字匹配）
        $verify -split "`r?`n" |
            Where-Object { $_ -match 'Signer' -and $_ -match '(certificate DN|certificate SHA-256 digest)' } |
            ForEach-Object { Info $_.Trim() }
    } else {
        Warn 'apksigner 校验未通过（APK 未签名或签名无效）:'
        Info $verify.Trim()
    }
} else {
    Warn '未找到 apksigner（缺 Android SDK build-tools），跳过签名校验。'
}

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
Info "SHA256: $hash"

# ---------------------------------------------------------------- 5. 可选：复制 / 安装
if ($OutDir) {
    if (-not (Test-Path -LiteralPath $OutDir)) {
        New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    }
    $dest = Join-Path $OutDir $apk.Name
    Copy-Item -LiteralPath $apk.FullName -Destination $dest -Force
    Ok "已复制到 $dest"
}

if ($Install) {
    Write-Host "`n=== 安装 ===" -ForegroundColor Cyan
    & (Join-Path $PSScriptRoot 'deploy-apk.ps1') -ApkPath $apk.FullName
    if ($LASTEXITCODE -ne 0) { Die '安装失败' }
}

Write-Host "`n完成。" -f Green
Write-Host "  $($apk.FullName)" -f Green
