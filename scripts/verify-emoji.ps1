<#
.SYNOPSIS
    校验 EmojiconData.kt 里的表情/符号数据：重复项、与官方 RGI 清单的差异、随包 emoji 字体的字形覆盖。

.DESCRIPTION
    表情面板现在直接信任 EmojiconData 的数据（不再按系统字体裁剪），
    所以「数据里有的，字体必须画得出来；字体画得出来的，数据不该漏」这两条要有东西兜住。
    这个脚本就是那道兜底，改完数据跑一遍。

    三项检查：
      1. 数据内部重复（同一个 emoji 出现在多个分类 / 同一分类出现两次）
      2. 与 Unicode 官方 emoji-test.txt 对比：数据里有没有非 RGI 条目、RGI 基础 emoji 有没有漏
      3. 与随包字体（assets/fonts/NotoColorEmojiCompat.ttf）的 cmap 对比：每条数据是否都有字形

.PARAMETER EmojiTest
    emoji-test.txt 路径。不传则联网抓 latest（-Offline 时跳过这项检查）。

.PARAMETER Font
    字体路径，默认 ime-native/src/main/assets/fonts/NotoColorEmojiCompat.ttf；不存在则跳过字形检查。

.PARAMETER Offline
    不联网，跳过 RGI 对比。

.EXAMPLE
    pwsh -File scripts/verify-emoji.ps1
#>
[CmdletBinding()]
param(
    [string]$DataFile = (Join-Path $PSScriptRoot '..\ime-core\src\main\java\com\whale\ime\data\emojicon\EmojiconData.kt'),
    [string]$Font = (Join-Path $PSScriptRoot '..\ime-native\src\main\assets\fonts\NotoColorEmojiCompat.ttf'),
    [string]$EmojiTest,
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$problems = 0

function Write-Problem([string]$message) {
    $script:problems++
    Write-Host "  [问题] $message" -ForegroundColor Red
}

function Write-Ok([string]$message) {
    Write-Host "  [通过] $message" -ForegroundColor Green
}

# ---------------------------------------------------------------- 读取数据

if (-not (Test-Path $DataFile)) { throw "找不到数据文件：$DataFile" }
$src = Get-Content -Raw -Encoding UTF8 $DataFile

$emojiStart = $src.IndexOf('val emojiconData')
$skinStart = $src.IndexOf('val skinToneVariants')
if ($emojiStart -lt 0 -or $skinStart -lt 0) { throw 'EmojiconData.kt 结构变了：找不到 emojiconData / skinToneVariants' }
$emojiBlock = $src.Substring($emojiStart, $skinStart - $emojiStart)
$skinBlock = $src.Substring($skinStart)

$quoted = [regex]'"([^"\\]*(?:\\.[^"\\]*)*)"'

$categories = [ordered]@{}
foreach ($m in [regex]::Matches($emojiBlock, 'R\.drawable\.(icon_emojibar_\w+)\s+to\s+listOf\(([^)]*)\)')) {
    $name = $m.Groups[1].Value
    $items = @($quoted.Matches($m.Groups[2].Value) | ForEach-Object { $_.Groups[1].Value } | Where-Object { $_ -ne '' })
    $categories[$name] = $items
}

if ($categories.Count -eq 0) { throw '没有解析到任何表情分类，正则可能失效' }

$all = New-Object System.Collections.Generic.List[string]
foreach ($kv in $categories.GetEnumerator()) { foreach ($e in $kv.Value) { $all.Add($e) } }

Write-Host "表情分类：$($categories.Count) 个，条目 $($all.Count) 条" -ForegroundColor Cyan

# 肤色表
$skin = [ordered]@{}
foreach ($m in [regex]::Matches($skinBlock, '"([^"]+)"\s+to\s+listOf\(([^)]*)\)')) {
    $skin[$m.Groups[1].Value] = @($quoted.Matches($m.Groups[2].Value) | ForEach-Object { $_.Groups[1].Value })
}

# ---------------------------------------------------------------- 检查 1：重复

# 必须用 Ordinal 比较：PowerShell 默认的文化敏感比较会把 tag 字符（U+E00xx）当可忽略字符，
# 🏴󠁧󠁢󠁥󠁮󠁧󠁿 / 🏴󠁧󠁢󠁳󠁣󠁴󠁿 这类子区域旗帜会被误判成同一个 🏴
$seen = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)
$dups = New-Object System.Collections.Generic.List[string]
foreach ($e in $all) { if (-not $seen.Add($e)) { $dups.Add($e) } }
if ($dups.Count -gt 0) {
    foreach ($d in $dups) { Write-Problem "重复条目：$d" }
} else {
    Write-Ok '数据内无重复条目'
}

# ---------------------------------------------------------------- 检查 2：RGI 对比

$rgi = $null
if (-not $Offline) {
    if (-not $EmojiTest) {
        $EmojiTest = Join-Path $env:TEMP 'emoji-test.txt'
        try {
            Invoke-WebRequest -Uri 'https://www.unicode.org/Public/emoji/latest/emoji-test.txt' `
                -OutFile $EmojiTest -UseBasicParsing -TimeoutSec 120 | Out-Null
        } catch {
            Write-Host "  [跳过] 下载 emoji-test.txt 失败：$($_.Exception.Message)" -ForegroundColor Yellow
            $EmojiTest = $null
        }
    }
    if ($EmojiTest -and (Test-Path $EmojiTest)) {
        $rgi = @{}
        foreach ($line in Get-Content -Encoding UTF8 $EmojiTest) {
            if ($line.StartsWith('#') -or -not $line.Contains(';')) { continue }
            $parts = $line.Split(';', 2)
            $status = $parts[1].Split('#')[0].Trim()
            $sb = New-Object System.Text.StringBuilder
            foreach ($cp in $parts[0].Trim().Split(' ', [System.StringSplitOptions]::RemoveEmptyEntries)) {
                [void]$sb.Append([char]::ConvertFromUtf32([Convert]::ToInt32($cp, 16)))
            }
            $rgi[$sb.ToString()] = $status
        }
        Write-Host "RGI 清单：$($rgi.Count) 条（$EmojiTest）" -ForegroundColor Cyan

        $extra = @($all | Where-Object { -not $rgi.ContainsKey($_) })
        if ($extra) {
            foreach ($e in $extra) { Write-Problem "不在 RGI 清单里的条目：$e" }
        } else {
            Write-Ok '数据中的条目全部在 RGI 清单内'
        }

        $modifiers = @(0x1F3FB..0x1F3FF | ForEach-Object { [char]::ConvertFromUtf32($_) })
        $bases = New-Object System.Collections.Generic.HashSet[string]
        foreach ($kv in $rgi.GetEnumerator()) {
            if ($kv.Value -ne 'fully-qualified') { continue }
            $hasModifier = $false
            foreach ($m in $modifiers) { if ($kv.Key.Contains($m)) { $hasModifier = $true; break } }
            if (-not $hasModifier) { [void]$bases.Add($kv.Key) }
        }
        # 地区旗帜（纯 regional indicator 对）由人工维护、不自动补全：新增/撤并都涉及地区口径，
        # 交给编辑判断，脚本这里只做提示，不算失败。
        $isRegionFlag = {
            param([string]$text)
            if ($text.Length -ne 4) { return $false }
            for ($i = 0; $i -lt 2; $i++) {
                $cp = [char]::ConvertToUtf32($text, $i * 2)
                if ($cp -lt 0x1F1E6 -or $cp -gt 0x1F1FF) { return $false }
            }
            return $true
        }

        $missing = @($bases | Where-Object { -not $all.Contains($_) })
        $missingFlags = @($missing | Where-Object { & $isRegionFlag $_ })
        $missingOther = @($missing | Where-Object { -not (& $isRegionFlag $_) })

        if ($missingOther.Count -gt 0) {
            foreach ($e in $missingOther) { Write-Problem "RGI 基础 emoji 缺失：$e" }
        } else {
            Write-Ok 'RGI 基础 emoji 无遗漏（地区旗帜除外）'
        }
        if ($missingFlags.Count -gt 0) {
            Write-Host "  [提示] 有 $($missingFlags.Count) 个地区旗帜未收录，按人工口径维护，不计为失败" -ForegroundColor Yellow
        }
    }
}

# ---------------------------------------------------------------- 检查 3：字体字形

if (-not (Test-Path $Font)) {
    Write-Host "  [跳过] 找不到字体：$Font" -ForegroundColor Yellow
} else {
    $bytes = [System.IO.File]::ReadAllBytes($Font)
    function Read-U16([int]$o) { ($bytes[$o] -shl 8) -bor $bytes[$o + 1] }
    function Read-U32([int]$o) {
        (([int64]$bytes[$o] -shl 24) -bor ([int64]$bytes[$o + 1] -shl 16) -bor
         ([int64]$bytes[$o + 2] -shl 8) -bor [int64]$bytes[$o + 3])
    }

    $numTables = Read-U16 4
    $cmapOffset = -1
    for ($i = 0; $i -lt $numTables; $i++) {
        $rec = 12 + $i * 16
        $tag = [System.Text.Encoding]::ASCII.GetString($bytes, $rec, 4)
        if ($tag -eq 'cmap') { $cmapOffset = [int](Read-U32 ($rec + 8)) }
    }
    if ($cmapOffset -lt 0) { throw '字体没有 cmap 表' }

    $subCount = Read-U16 ($cmapOffset + 2)
    $bestFormat = -1; $bestOffset = -1
    for ($i = 0; $i -lt $subCount; $i++) {
        $rec = $cmapOffset + 4 + $i * 8
        $platformId = Read-U16 $rec
        $subOffset = $cmapOffset + [int](Read-U32 ($rec + 4))
        $format = Read-U16 $subOffset
        if ($platformId -eq 3 -and ($format -eq 4 -or $format -eq 12)) {
            $bestFormat = $format; $bestOffset = $subOffset
        }
    }

    $cmap = New-Object System.Collections.Generic.HashSet[int]
    if ($bestFormat -eq 4) {
        $segX2 = Read-U16 ($bestOffset + 6)
        $segCount = $segX2 / 2
        $endBase = $bestOffset + 14
        $startBase = $endBase + $segX2 + 2
        for ($s = 0; $s -lt $segCount; $s++) {
            $start = Read-U16 ($startBase + $s * 2)
            $end = Read-U16 ($endBase + $s * 2)
            if ($end -eq 0xFFFF -and $start -eq 0xFFFF) { continue }
            for ($c = $start; $c -le $end; $c++) { [void]$cmap.Add($c) }
        }
    } elseif ($bestFormat -eq 12) {
        $groups = [int](Read-U32 ($bestOffset + 12))
        for ($g = 0; $g -lt $groups; $g++) {
            $rec = $bestOffset + 16 + $g * 12
            $start = [int](Read-U32 $rec)
            $end = [int](Read-U32 ($rec + 4))
            for ($c = $start; $c -le $end; $c++) { [void]$cmap.Add($c) }
        }
    } else {
        throw '字体里没有可解析的 cmap 子表（format 4/12）'
    }

    # ZWJ（U+200D）与变体选择符（U+FE0F）没有独立字形，不参与检查
    $ignored = @(0x200D, 0xFE0F)
    $bad = New-Object System.Collections.Generic.List[string]
    $check = { param($text, $label)
        $codepoints = @()
        for ($i = 0; $i -lt $text.Length; $i++) {
            if ([char]::IsHighSurrogate($text[$i]) -and $i + 1 -lt $text.Length) {
                $codepoints += [char]::ConvertToUtf32($text[$i], $text[$i + 1]); $i++
            } else { $codepoints += [int]$text[$i] }
        }
        foreach ($cp in $codepoints) {
            if ($ignored -contains $cp) { continue }
            if (-not $cmap.Contains($cp)) { $bad.Add("$label $text (U+$($cp.ToString('X4')))"); return }
        }
    }
    foreach ($kv in $categories.GetEnumerator()) {
        foreach ($e in $kv.Value) { & $check $e $kv.Key }
    }
    foreach ($kv in $skin.GetEnumerator()) {
        foreach ($v in $kv.Value) { & $check $v 'skin' }
    }

    if ($bad.Count -gt 0) {
        foreach ($b in $bad) { Write-Problem "字体缺字形：$b" }
    } else {
        Write-Ok "字体覆盖全部 $($all.Count) 条数据（cmap $($cmap.Count) 个码位）"
    }
}

# ---------------------------------------------------------------- 结果

Write-Host ''
if ($problems -eq 0) {
    Write-Host '校验通过。' -ForegroundColor Green
    exit 0
} else {
    Write-Host "共 $problems 处问题。" -ForegroundColor Red
    exit 1
}
