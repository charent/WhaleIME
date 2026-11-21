#Requires -Version 7
<#
.SYNOPSIS
    统一解析 Android SDK 根目录，供 scripts/ 下的构建、打包、部署脚本共用。
.DESCRIPTION
    解析顺序（命中即返回）：

      1) 显式传入的 -Explicit（脚本自己的 -SdkRoot 之类参数）
      2) 环境变量 ANDROID_SDK_ROOT / ANDROID_HOME
      3) 仓库根目录 local.properties 的 sdk.dir（properties 转义格式）
      4) 各平台通用安装位置（不含任何写死的盘符）

    都找不到时返回 $null，由调用方决定报错文案。

    仓库里不写任何具体机器的路径：开发者把自己的 sdk.dir 写进 local.properties
    （已 gitignore，模板见 local.properties.example），CI 或多 SDK 环境用
    ANDROID_SDK_ROOT 覆盖。
#>

function Resolve-SdkRoot {
    [CmdletBinding()]
    param(
        # 仓库根目录（即 local.properties 所在目录）
        [Parameter(Mandatory)]
        [string]$Repo,
        # 显式指定的 SDK 路径，优先级最高
        [string]$Explicit = ''
    )

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "Android SDK 不存在: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }

    foreach ($name in @('ANDROID_SDK_ROOT', 'ANDROID_HOME')) {
        $val = [Environment]::GetEnvironmentVariable($name)
        if ($val -and (Test-Path -LiteralPath $val)) { return (Resolve-Path -LiteralPath $val).Path }
    }

    # local.properties 里的 sdk.dir 是 properties 转义格式：X\:\\path\\to\\Android\\SDK
    $localProps = Join-Path $Repo 'local.properties'
    if (Test-Path -LiteralPath $localProps) {
        $line = Get-Content -LiteralPath $localProps |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $dir = ($line -split '=', 2)[1].Trim() -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path -LiteralPath $dir) { return (Resolve-Path -LiteralPath $dir).Path }
        }
    }

    $candidates = @()
    if ($env:LOCALAPPDATA) { $candidates += Join-Path $env:LOCALAPPDATA 'Android\Sdk' }  # Windows 默认安装
    if ($HOME) {
        $candidates += Join-Path $HOME 'Library/Android/sdk'  # macOS（含 Android Studio 默认）
        $candidates += Join-Path $HOME 'Android/Sdk'          # Linux
    }
    foreach ($dir in $candidates) {
        if (Test-Path -LiteralPath $dir) { return (Resolve-Path -LiteralPath $dir).Path }
    }

    return $null
}
