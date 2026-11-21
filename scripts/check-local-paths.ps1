#Requires -Version 7
<#
.SYNOPSIS
    检查已纳入版本控制的文件里是否残留本机绝对路径。
.DESCRIPTION
    仓库里不应出现具体机器上的路径（盘符、用户名主目录），否则既泄露开发者环境，
    也让其他用户无法直接复用脚本。本脚本只扫描 `git ls-files` 的结果，未入库的
    docs/、local.properties、keystore/ 天然不在范围内；命中时逐条打印
    「文件:行号  规则  内容」并以退出码 1 结束，可直接挂到 CI 与 pre-commit。

    修复方式：改用环境变量（$env:ANDROID_SDK_ROOT）、仓库相对路径或盘符占位符
    （`<盘符>:\path\to\...` 这类不含真实盘符的写法）。

.PARAMETER Exclude
    额外排除的路径通配符。默认排除第三方语料目录 ime-native/src/main/assets/*。

.PARAMETER NoFail
    只报告、不返回失败退出码（本地试跑用）。
.EXAMPLE
    pwsh -File scripts/check-local-paths.ps1
.EXAMPLE
    pwsh -File scripts/check-local-paths.ps1 -NoFail
#>
param(
    [string[]]$Exclude = @('ime-native/src/main/assets/*'),
    [switch]$NoFail
)

$ErrorActionPreference = 'Stop'
$Repo = Split-Path -Parent $PSScriptRoot

# 只扫描文本文件，避免把二进制资源读进来
$TextExtensions = @(
    '.ps1', '.psm1', '.psd1', '.kt', '.kts', '.java', '.gradle', '.xml', '.md',
    '.json', '.yml', '.yaml', '.txt', '.properties', '.cfg', '.bat', '.cmd', '.sh',
    '.cc', '.cpp', '.h', '.hpp', '.py', '.toml', '.pro', '.rs', '.js', '.ts', '.html', '.css'
)
$TextNames = @('.gitignore', '.gitattributes', '.editorconfig', 'gradlew')

# 盘符路径前面紧邻字母/数字/反斜杠的不算（避免把正则里的 `speller:\n` 当成路径）
$Rules = @(
    @{ Name = 'Windows 盘符路径'; Pattern = '(?<![A-Za-z0-9_\\])([A-Za-z]):[\\/]{1,2}[A-Za-z0-9_.]' }
    @{ Name = 'Unix 用户主目录'; Pattern = '(^|[\s"''(=])(/Users|/home)/[A-Za-z0-9._-]+' }
)
# 行内 URL 先剔除，避免 https:// / content:// 里的冒号误报
$UrlPattern = '[A-Za-z][A-Za-z0-9+.-]*://\S+'

Push-Location $Repo
try {
    $files = & git ls-files
    if ($LASTEXITCODE -ne 0) { throw 'git ls-files 执行失败，请在仓库根目录运行' }
} finally {
    Pop-Location
}

$hits = [System.Collections.Generic.List[object]]::new()
foreach ($rel in $files) {
    $name = [IO.Path]::GetFileName($rel)
    $ext = [IO.Path]::GetExtension($rel)
    if ($TextNames -notcontains $name -and $TextExtensions -notcontains $ext) { continue }

    $skip = $false
    foreach ($p in $Exclude) { if ($rel -like $p) { $skip = $true; break } }
    if ($skip) { continue }

    $full = Join-Path $Repo $rel
    if (-not (Test-Path -LiteralPath $full)) { continue }

    $lineNo = 0
    foreach ($line in [IO.File]::ReadLines($full)) {
        $lineNo++
        $probe = $line -replace $UrlPattern, ''
        foreach ($rule in $Rules) {
            if ($probe -match $rule.Pattern) {
                $hits.Add([pscustomobject]@{
                        File = $rel
                        Line = $lineNo
                        Rule = $rule.Name
                        Text = $line.Trim()
                    })
                break
            }
        }
    }
}

if ($hits.Count -eq 0) {
    Write-Host '[ok]   未发现本机绝对路径' -f Green
    exit 0
}

Write-Host "[FAIL] 发现 $($hits.Count) 处本机绝对路径：" -f Red
foreach ($h in $hits) {
    Write-Host ("  {0}:{1}  [{2}]  {3}" -f $h.File, $h.Line, $h.Rule, $h.Text)
}
Write-Host ''
Write-Host '处理：改用环境变量（$env:ANDROID_SDK_ROOT）、仓库相对路径或盘符占位符（<盘符>:\path\to\...）。' -f Yellow

if ($NoFail) { exit 0 }
exit 1
