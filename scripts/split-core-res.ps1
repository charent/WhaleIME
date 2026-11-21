#Requires -Version 7
<#
.SYNOPSIS
    按「谁引用谁持有」的原则裁剪 :ime-core 的 res 副本。
.DESCRIPTION
    Phase 1b 为了让 code 能编译，把 ime-ui 的整份 res 复制进了 ime-core。本脚本把
    core 代码（含 core 自己保留的 res XML）实际引用不到的资源删掉，只留下：

      values/strings.xml   —— core 代码引用的 string 条目（含被这些条目 @string/ 引用的条目）
      values/colors.xml    —— 保留（矢量图 drawable 会 @color/ 引用）
      values/ids.xml       —— 保留（R.id 引用）
      drawable/**          —— core 代码或保留的 XML 引用到的矢量图
      raw/privacypolicy.json —— 若任一模块代码引用 R.raw.privacypolicy

    values 里的 arrays / styles / attrs / themes 与 values-night 属于 UI 层的排版与主题，
    core 代码不引用（core 无 Activity、无主题声明），一律删除以免与 ime-ui 的副本并存。

    截断是机械的、可验证的：跑完必须 `./gradlew :app:assembleDebug` 通过，
    否则说明漏了某个被间接引用的资源，按报错把条目加回即可。
#>
[CmdletBinding()]
param(
    [string]$CoreJava = '',
    [string]$CoreRes = ''
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot
if (-not $CoreJava) { $CoreJava = Join-Path $Repo 'ime-core\src\main\java' }
if (-not $CoreRes) { $CoreRes = Join-Path $Repo 'ime-core\src\main\res' }

$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$path) { [System.IO.File]::ReadAllText($path) }
function Write-Text([string]$path, [string]$text) { [System.IO.File]::WriteAllText($path, $text, $utf8) }

# ---------------------------------------------------------------- 1. core 代码的 R.* 引用
$sources = Get-ChildItem $CoreJava -Recurse -Include *.kt, *.java
$refRe = [regex]'R\.(string|drawable|array|color|dimen|style|raw|xml|id|plurals)\.([A-Za-z0-9_]+)'
$codeRefs = @{}
foreach ($f in $sources) {
    # 先把 android.R.*（框架资源）剔掉，否则 android.R.string.ok 会被当成自身资源
    $text = (Read-Text $f.FullName) -replace 'android\.R\.', 'android_R_'
    foreach ($m in $refRe.Matches($text)) {
        $type = $m.Groups[1].Value
        $name = $m.Groups[2].Value
        if (-not $codeRefs.ContainsKey($type)) { $codeRefs[$type] = New-Object System.Collections.Generic.HashSet[string] }
        [void]$codeRefs[$type].Add($name)
    }
}

function Get-Keep([string]$type) {
    if ($codeRefs.ContainsKey($type)) { return $codeRefs[$type] } else { return New-Object System.Collections.Generic.HashSet[string] }
}

# ---------------------------------------------------------------- 2. 裁剪 values/strings.xml
$stringsPath = Join-Path $CoreRes 'values\strings.xml'
$original = Read-Text $stringsPath
$blockRe = [regex]'(?s)<string\s+name="([A-Za-z0-9_]+)"(.*?)</string>'
$blocks = @{}
foreach ($m in $blockRe.Matches($original)) { $blocks[$m.Groups[1].Value] = $m.Value }

$keepStrings = Get-Keep 'string'
# 条目之间可能有 @string/xxx 互相引用（例如带品牌名的提示语），迭代到稳定
do {
    $before = $keepStrings.Count
    foreach ($name in @($keepStrings)) {
        if (-not $blocks.ContainsKey($name)) { continue }
        foreach ($m in [regex]::Matches($blocks[$name], '@string/([A-Za-z0-9_]+)')) {
            [void]$keepStrings.Add($m.Groups[1].Value)
        }
    }
} while ($keepStrings.Count -ne $before)

$missing = @($keepStrings | Where-Object { -not $blocks.ContainsKey($_) })
if ($missing.Count -gt 0) { throw "core 引用了 strings.xml 里不存在的条目: $($missing -join ', ')" }

$sb = [System.Text.StringBuilder]::new()
[void]$sb.AppendLine('<?xml version="1.0" encoding="UTF-8"?>')
[void]$sb.AppendLine('<resources>')
[void]$sb.AppendLine('    <!--')
[void]$sb.AppendLine('        本文件由 scripts/split-core-res.ps1 生成：只保留 :ime-core 代码引用到的条目。')
[void]$sb.AppendLine('        其余文案归 :ime-ui 所有（ime-ui/src/main/res/values/strings.xml）。')
[void]$sb.AppendLine('        新增/修改文案请在 ime-ui 侧改；core 若需要新的条目，把该条目复制到这里。')
[void]$sb.AppendLine('    -->')
foreach ($m in $blockRe.Matches($original)) {
    if ($keepStrings.Contains($m.Groups[1].Value)) { [void]$sb.AppendLine('    ' + $m.Value) }
}
[void]$sb.AppendLine('</resources>')
Write-Text $stringsPath $sb.ToString()
Write-Host ("strings.xml: {0} -> {1} 条" -f $blocks.Count, $keepStrings.Count) -ForegroundColor Green

# ---------------------------------------------------------------- 3. 删掉 UI 专属的 values
foreach ($f in @('arrays.xml', 'attrs.xml', 'themes.xml')) {
    $p = Join-Path $CoreRes "values\$f"
    if (Test-Path $p) { Remove-Item $p -Force; Write-Host "删除 values/$f" }
}
$night = Join-Path $CoreRes 'values-night'
if (Test-Path $night) { Remove-Item $night -Recurse -Force; Write-Host '删除 values-night/' }

# ---------------------------------------------------------------- 4. 裁剪 drawable
$keepDrawables = Get-Keep 'drawable'
$drawableDir = Join-Path $CoreRes 'drawable'
$drawableFiles = @{}
foreach ($f in Get-ChildItem $drawableDir -File) {
    $drawableFiles[[System.IO.Path]::GetFileNameWithoutExtension($f.Name)] = $f
}
# 保留的 drawable 之间可能有 @drawable/ 引用（selector / layer-list），迭代到稳定
do {
    $before = $keepDrawables.Count
    foreach ($name in @($keepDrawables)) {
        if (-not $drawableFiles.ContainsKey($name)) { continue }
        $text = Read-Text $drawableFiles[$name].FullName
        foreach ($m in [regex]::Matches($text, '@drawable/([A-Za-z0-9_]+)')) {
            if ($drawableFiles.ContainsKey($m.Groups[1].Value)) { [void]$keepDrawables.Add($m.Groups[1].Value) }
        }
        # 保留的矢量图若引用 @color/，colors.xml 是全量保留的，不需要额外处理
    }
} while ($keepDrawables.Count -ne $before)

$removed = 0
foreach ($name in $drawableFiles.Keys) {
    if (-not $keepDrawables.Contains($name)) { Remove-Item $drawableFiles[$name].FullName -Force; $removed++ }
}
Write-Host ("drawable: {0} -> {1} 个（删除 {2}）" -f $drawableFiles.Count, $keepDrawables.Count, $removed) -ForegroundColor Green

# ---------------------------------------------------------------- 5. raw / anim
if ((Get-Keep 'raw').Count -eq 0) {
    $raw = Join-Path $CoreRes 'raw'
    if (Test-Path $raw) { Remove-Item $raw -Recurse -Force; Write-Host '删除 raw/（core 无 R.raw 引用）' }
}
$anim = Join-Path $CoreRes 'anim'
if ((Test-Path $anim) -and (Get-ChildItem $anim -File).Count -eq 0) { Remove-Item $anim -Force }

Write-Host "`n完成。请务必跑 ./gradlew :app:assembleDebug 验证没有漏掉被间接引用的资源。" -ForegroundColor Yellow
