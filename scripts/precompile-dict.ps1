#Requires -Version 7
<#
.SYNOPSIS
    预编译 Rime 词库：把 rime-frost（白霜拼音）的中文词库 + 英文词库编成
    table/prism bin，直接覆盖 ime-native\src\main\assets\rime\build\。

.DESCRIPTION
    宿主机（Windows）没有 MSVC，唯一工具链是 NDK —— 所以 rime_deployer 编成 Android
    x86_64 可执行（ime-native\native\scripts\build-deployer.ps1），推到模拟器原生
    执行，产物 pull 回来。61 万词条在模拟器上编一遍约 1~2 分钟，比真机首启编译（十分钟
    级 + 耗电）划算，也不进首启路径。

    产出（全部覆盖 assets\rime\build\）：
      - pinyin.table.bin / pinyin.prism.bin      中文主词库
      - t9_pinyin.prism.bin                      九键 prism（与 pinyin 共用词典，checksum 必须配套）
      - t14_pinyin.prism.bin                     14 键 prism（同上；拼写集合是 14 键键码）
      - english.table.bin / english.prism.bin    英文词库（拼音方案的 table_translator@english
                                                 与 english 方案共用）

    词库源不入库（约 600MB），默认从 docs\prototype\rime-frost 读取（gaboolic/rime-frost，
    白霜拼音），可用 -DictSource 指向别处。中文核心表 + 细胞词库走 import_tables，英文
    en/en_ext 汇成 name: english 后一并编译。

    大小写不敏感检索：码表词典的 prism 拼写就是 code 原文（speller.algebra 对
    table_translator 无效，实测加 xlit 不派生任何拼写），所以生成英文词典源时给每条
    「code 含大写」的条目补一条同文本的小写码（GPU 多一条 gpu，iPhone 多一条 iphone）。
    小写输入因此直接命中，候选文本仍是词库原文。

    为什么不从 rime-ice 换成 rime-frost（2026-11-07）：rime-ice 的 ext(33.9 万条) +
    tencent(98.1 万条) 权重全是占位值 100，占全库 68.8% 且跨表不可比；白霜用 7.45 亿字
    语料重算字频词频，weight==100 仅 0.1%（真实词频），规模约 61 万条。

    语法模型（zh-hans.gram）不在本脚本内：它由 docs\prototype\build-gram.ps1 从 196MB
    的 lm_sc.arpa 训练，语料同样不入库。

.PARAMETER DictSource
    rime-frost 词库根目录（其下有 cn_dicts / cn_dicts_cell / en_dicts）。
    默认 docs\prototype\rime-frost。

.PARAMETER WorkDir
    staging 与日志的落地目录，默认 build\dict-staging（gitignore）。脚本会清空其下 staging。

.PARAMETER Supplement
    漏词补录表 supplement.dict.yaml（可选）。默认取 docs\prototype\merged\supplement.dict.yaml，
    不存在则跳过该表 —— 补录表由本地对账脚本生成，生成逻辑未入库。

.PARAMETER SkipBuild
    deployer 二进制没变时跳过构建，直接跑模拟器侧流程。

.PARAMETER WithCells
    追加白霜 cn_dicts_cell\*（23 个垂直细胞词库）。默认开启：实测对日常词 top1/top5
    零影响，只补长尾；代价是 pinyin.table.bin 18.1MB -> 59.6MB。想瘦身用 -WithCells:$false。

.PARAMETER CellOnly
    只启用指定细胞词库（表名，不含 .dict.yaml），给定时覆盖 -WithCells 的全量行为。
    例：-CellOnly idiom place name2 geography history exthot industry_product

.EXAMPLE
    pwsh -File scripts\precompile-dict.ps1

.EXAMPLE
    pwsh -File scripts\precompile-dict.ps1 -SkipBuild -DictSource ..\rime-frost
#>
param(
  [string]$DictSource = '',
  [string]$WorkDir = '',
  [string]$Supplement = '',
  [switch]$SkipBuild,
  [switch]$WithCells = $true,
  [string[]]$CellOnly = @()
)

$ErrorActionPreference = 'Stop'

$Repo = Split-Path -Parent $PSScriptRoot
$CppScripts = Join-Path $Repo 'ime-native\native\scripts'
$AssetsRime = Join-Path $Repo 'ime-native\src\main\assets\rime'
if (-not $DictSource) { $DictSource = Join-Path $Repo 'docs\prototype\rime-frost' }
if (-not $WorkDir) { $WorkDir = Join-Path $Repo 'build\dict-staging' }
$FrostCnDicts = Join-Path $DictSource 'cn_dicts'
$FrostEnDicts = Join-Path $DictSource 'en_dicts'
$Staging = Join-Path $WorkDir 'staging'
$LogFile = Join-Path $WorkDir 'precompile.log'

# 核心表：real 词频的 5 张。白霜的 tencent（大词库，权重不可比）、41448（无权重，永远殿后）、
# GB18030-2022（全字符集）不引入 —— 只增加体积不提升排序。
# cn_dicts_cell\* 由 -WithCells（默认开）单独控制，见下方 staging 组装。
$DictTables = '8105', 'base', 'ext', 'others', 'corrections'

if (-not (Test-Path (Join-Path $FrostCnDicts 'base.dict.yaml'))) {
  throw "缺少词库源 $FrostCnDicts（先 clone gaboolic/rime-frost，或用 -DictSource 指定根目录）"
}
if (-not (Test-Path (Join-Path $FrostEnDicts 'en.dict.yaml'))) {
  throw "缺少英文词库 $FrostEnDicts（rime-frost 的 en_dicts 目录）"
}

# 模拟器侧工作目录（app 二进制无权访问 /data/local/tmp 之外路径，用 shell 域）
$DeviceBase = '/data/local/tmp/rime-precompile'

# ---------------------------------------------------------------- 0. 定位 adb / 设备
$adb = $null
$localProps = Join-Path $Repo 'local.properties'
if (Test-Path $localProps) {
  $line = (Get-Content $localProps -Encoding utf8 | Select-String '^\s*sdk\.dir').ToString()
  if ($line) {
    $sdk = $line.Substring($line.IndexOf('=') + 1).Trim() -replace '\\:', ':' -replace '\\\\', '\'
    $candidate = Join-Path $sdk 'platform-tools\adb.exe'
    if (Test-Path $candidate) { $adb = $candidate }
  }
}
if (-not $adb -and $env:ANDROID_SDK_ROOT) {
  $candidate = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
  if (Test-Path $candidate) { $adb = $candidate }
}
if (-not $adb) {
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) { throw '未找到 adb（检查 local.properties 的 sdk.dir / ANDROID_SDK_ROOT / PATH）' }

$devices = & $adb devices | Select-String 'device\s*$'
if (-not $devices) { throw '没有已连接的设备/模拟器（先跑 scripts\start-emulator.ps1）' }

# ---------------------------------------------------------------- 1. 构建 deployer
if (-not $SkipBuild) {
  & pwsh -NoProfile -File (Join-Path $CppScripts 'build-deployer.ps1')
  if ($LASTEXITCODE -ne 0) { throw 'rime_deployer 构建失败' }
}
$HostTools = Join-Path $Repo 'ime-native\native\native-build\build\x86_64\librime-hosttools'
if (-not (Test-Path (Join-Path $HostTools 'bin\rime_deployer'))) {
  throw "缺少 $HostTools\bin\rime_deployer（去掉 -SkipBuild 先构建 deployer）"
}

# ---------------------------------------------------------------- 2. 组装 staging
Write-Host "`n=== 组装 staging ===" -ForegroundColor Cyan
Remove-Item -Recurse -Force $Staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Staging, "$Staging\build", "$Staging\opencc", $WorkDir | Out-Null

# 漏词补录表（可选）：白霜增频表里列出、但核心五表缺失的词。由本地对账脚本生成，未入库。
$SupplementPath = $Supplement
if (-not $SupplementPath) {
  $SupplementPath = Join-Path $Repo 'docs\prototype\merged\supplement.dict.yaml'
}
$HasSupplement = Test-Path $SupplementPath
if ($Supplement -and -not $HasSupplement) { throw "缺少补录表 $SupplementPath" }
if ($HasSupplement) {
  Write-Host "  补录表: $SupplementPath" -ForegroundColor DarkGray
} else {
  Write-Host "  补录表: 跳过（未找到 $SupplementPath）" -ForegroundColor DarkGray
}

# schema 用 assets 的源文件（带 enable_correction 等改动）。
# t9_pinyin / t14_pinyin 必须一起编：它们的 translator 分别用 `prism: t9_pinyin` /
# `prism: t14_pinyin` + `dictionary: pinyin`，prism 由词典拼写集合生成并写死 dict
# checksum；换了词库却沿用旧 prism，对应方案会立刻失配（候选只剩全拼/echo）。
# 三个 schema 共用同一份 pinyin.table.bin。
Copy-Item (Join-Path $AssetsRime 'pinyin.schema.yaml') $Staging -Force
Copy-Item (Join-Path $AssetsRime 't9_pinyin.schema.yaml') $Staging -Force
Copy-Item (Join-Path $AssetsRime 't14_pinyin.schema.yaml') $Staging -Force

# 白霜中文词库：核心表 + （默认）细胞词库，import_tables 走 rime 原生编译。未拷入的表
# （tencent / 41448 / GB18030-2022）既不参与编译也不占推送体积。
New-Item -ItemType Directory -Force -Path "$Staging\cn_dicts" | Out-Null
foreach ($t in $DictTables) {
  $src = Join-Path $FrostCnDicts "$t.dict.yaml"
  if (-not (Test-Path $src)) { throw "缺少词库表 $src" }
  Copy-Item $src "$Staging\cn_dicts\" -Force
}
if ($HasSupplement) {
  Copy-Item $SupplementPath "$Staging\cn_dicts\supplement.dict.yaml" -Force
}

# 可选：追加细胞词库（-WithCells 全量 / -CellOnly 指定子集）
$CellNames = @()
$UseCells = $WithCells -or ($CellOnly.Count -gt 0)
if ($UseCells) {
  $CellSrc = Join-Path $DictSource 'cn_dicts_cell'
  if (-not (Test-Path $CellSrc)) { throw "缺少 $CellSrc（rime-frost 的细胞词库目录）" }
  New-Item -ItemType Directory -Force -Path "$Staging\cn_dicts_cell" | Out-Null
  if ($CellOnly.Count -gt 0) {
    foreach ($n in $CellOnly) {
      $src = Join-Path $CellSrc "$n.dict.yaml"
      if (-not (Test-Path $src)) { throw "缺少细胞词库表 $src" }
      Copy-Item $src "$Staging\cn_dicts_cell\" -Force
      $CellNames += $n
    }
  } else {
    Get-ChildItem "$CellSrc\*.dict.yaml" | ForEach-Object {
      Copy-Item $_.FullName "$Staging\cn_dicts_cell\" -Force
      $CellNames += ($_.Name -replace '\.dict\.yaml$', '')
    }
  }
  $cellMB = [math]::Round(((Get-ChildItem "$Staging\cn_dicts_cell\*.dict.yaml" | Measure-Object Length -Sum).Sum) / 1MB, 1)
  Write-Host ("  细胞词库: {0} 个表 / {1} MB 源数据" -f $CellNames.Count, $cellMB)
}

# 英文词库：english.table.bin / english.prism.bin 以前是「无源的预编译产物」，改一次
# 大小写只能手工动二进制。这里把 rime-frost 的 en_dicts（en 常见两万词 + en_ext 缩写/
# 互联网词）汇成 name: english 的汇总表，走同一条 rime 原生编译管线产出。
# pinyin.schema.yaml 的 table_translator@english 与 english.schema.yaml 都读这份表。
New-Item -ItemType Directory -Force -Path "$Staging\en_dicts" | Out-Null

# 大小写不敏感检索的落点：给每条「code 含大写」的条目补一条同文本的小写码条目
# （GPU 多一条 gpu，iPhone 多一条 iphone）。小写输入因此直接命中，候选文本仍是词库原文。
function Copy-EnglishDictWithLowerCode {
  param([string]$Source, [string]$Destination)
  $lines = [System.IO.File]::ReadAllLines($Source, [System.Text.Encoding]::UTF8)
  $out = New-Object 'System.Collections.Generic.List[string]'
  $inBody = $false
  $added = 0
  foreach ($line in $lines) {
    $out.Add($line)
    if (-not $inBody) {
      if ($line.Trim() -eq '...') { $inBody = $true }
      continue
    }
    if ($line.Length -eq 0 -or $line.TrimStart().StartsWith('#')) { continue }
    $cols = $line.Split("`t")
    if ($cols.Count -lt 2) { continue }
    $code = $cols[1].Trim()
    if ($code.Length -eq 0) { continue }
    # 解释型条目不派生：en_ext 里有 700+ 条「全称 → 缩写」（Graphics Processing Unit → GPU、
    # iPhone 16 Pro Max → iPhone）。派生小写码会让敲 gpu 的首选变成那串全称；不派生时小写
    # 输入只命中 text 与 code 同形的缩写条目（GPU / iPhone 本身）。
    if ($cols[0].Trim().Contains(' ')) { continue }
    $lower = $code.ToLowerInvariant()
    if ($lower -ceq $code) { continue }
    $cols[1] = $lower
    $out.Add(($cols -join "`t"))
    $added++
  }
  [System.IO.File]::WriteAllLines($Destination, $out, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host ("  {0}: 派生 {1} 条小写码" -f (Split-Path -Leaf $Source), $added) -ForegroundColor DarkGray
}

Copy-EnglishDictWithLowerCode (Join-Path $FrostEnDicts 'en.dict.yaml') "$Staging\en_dicts\en.dict.yaml"
Copy-EnglishDictWithLowerCode (Join-Path $FrostEnDicts 'en_ext.dict.yaml') "$Staging\en_dicts\en_ext.dict.yaml"

# 英文高频缩写补充表（内置，不依赖词库源）：rime-frost 的 en.dict.yaml 把「单个字母、绝大
# 部分两字母缩写」整段注释掉了（原文就是 `# vs	vs`），于是 vs 既没有候选，也没有
# simplifier@emoji 的宿主 —— emoji 联想只改写「已有候选」的文本，而 echo_translator 的兜底
# 候选（文本 = 输入串）在纯字母输入被纠错候选占满时会被丢弃（EchoTranslation::Compare
# 一见候选非空即自毁），所以 `vs` 永远联想不到 🆚。这里只补实测缺失的高频词，
# 不要把注释掉的两字母表整段搬回来（会让英文表混入大量无意义缩写候选）。
@(
  '# 由 scripts/precompile-dict.ps1 生成：英文高频缩写补充（rime-frost 注释掉了两字母缩写）'
  '---'
  'name: whale_supplement'
  'version: "2026.11.14"'
  'sort: by_weight'
  '...'
  "vs`tvs`t5000"
  "VS`tVS`t5000"
) | Set-Content -Path (Join-Path $Staging 'en_dicts\whale_supplement.dict.yaml') -Encoding utf8

# schema 用 assets 的源文件（与词典一起部署，拼音方案的英文候选也共用这份表）
Copy-Item (Join-Path $AssetsRime 'english.schema.yaml') $Staging -Force

@(
  '# 由 scripts/precompile-dict.ps1 生成：rime-frost 英文词库汇总表'
  '---'
  'name: english'
  'version: "2026.11.14"'
  'sort: by_weight'
  'use_preset_vocabulary: false'
  'import_tables:'
  '  - en_dicts/en       # 常见两万词（小写为主，专有名词保留原大小写）'
  '  - en_dicts/en_ext   # 缩写与互联网词（GPU / USB / OK / okey-dokey …）'
  '  - en_dicts/whale_supplement  # 高频缩写补充（vs / VS），见上方说明'
  '...'
) | Set-Content -Path (Join-Path $Staging 'english.dict.yaml') -Encoding utf8

$importLines = @(
  '  - cn_dicts/8105        # 通用规范汉字表（字频真实）'
  '  - cn_dicts/base        # 基础词库（36.6 万条，真实词频）'
  '  - cn_dicts/ext         # 扩展词库（23.6 万条，真实词频，白霜重算过）'
  '  - cn_dicts/others      # 杂项'
  '  - cn_dicts/corrections # 错音错字提示（weight=0，仅兜底，不抢排序）'
)
if ($HasSupplement) {
  $importLines += '  - cn_dicts/supplement  # 白霜增频表落空的漏词（是的），权重经同码校准'
}
if ($UseCells) {
  $CellNames | ForEach-Object { $importLines += "  - cn_dicts_cell/$_   # 细胞词库" }
}

@(
  '# 由 scripts/precompile-dict.ps1 生成：rime-frost（白霜拼音）汇总表'
  '---'
  'name: pinyin'
  "version: `"2026.11.07`""
  'sort: by_weight'
  'use_preset_vocabulary: false'
  'import_tables:'
) + $importLines + @('...') | Set-Content -Path (Join-Path $Staging 'pinyin.dict.yaml') -Encoding utf8

# 精简 default.yaml：只编 pinyin + t9_pinyin + english。stroke 等 schema 没有词典源
# （assets 只有预编译 bin），编它们必然报错，这里不需要它们的产物。
@'
# 预编译 staging 专用（scripts/precompile-dict.ps1 生成）
config_version: "1"
schema_list:
  - schema: pinyin
  - schema: t9_pinyin
  - schema: t14_pinyin
  - schema: english
'@ | Set-Content -Path (Join-Path $Staging 'default.yaml') -Encoding utf8

# installation.yaml 让部署任务认为已安装过，跳过安装相关逻辑
@'
installation_id: "whaleime-precompile"
'@ | Set-Content -Path (Join-Path $Staging 'installation.yaml') -Encoding utf8

# ---------------------------------------------------------------- 3. 推送并执行
Write-Host "`n=== 推送 staging 到模拟器 ===" -ForegroundColor Cyan
& $adb shell "rm -rf $DeviceBase && mkdir -p $DeviceBase/user $DeviceBase/shared $DeviceBase/staging/opencc"
if ($LASTEXITCODE -ne 0) { throw '清理设备目录失败' }

# 67MB dict yaml 用 sync push 快很多
& $adb push "$Staging\." "$DeviceBase/shared/" | Out-Host
if ($LASTEXITCODE -ne 0) { throw '推送 staging 失败' }
& $adb push "$HostTools\bin\rime_deployer" "$HostTools\lib\librime.so" "$DeviceBase/" | Out-Host
if ($LASTEXITCODE -ne 0) { throw '推送 deployer 失败' }

Write-Host "`n=== 模拟器上编译词典（1~2 分钟，deployer 静默，日志走 logcat）===" -ForegroundColor Cyan
$out = & $adb shell "cd $DeviceBase && chmod +x rime_deployer && LD_LIBRARY_PATH=$DeviceBase ./rime_deployer --build $DeviceBase/user $DeviceBase/shared $DeviceBase/shared; echo EXIT_CODE=`$?" 2>&1
$out | ForEach-Object { "$_" } | Tee-Object -FilePath $LogFile | Out-Host
if (-not ($out -match 'EXIT_CODE=0')) { throw "编译失败（见 $LogFile；adb logcat -s librime 可看细节）" }

# ---------------------------------------------------------------- 4. 拉回产物
Write-Host "`n=== 拉回产物 ===" -ForegroundColor Cyan
# --build 的产物直接落在 staging 根目录（shared/），不在 build/ 子目录
$built = "$DeviceBase/shared"
# default.yaml 不拉：staging 里是精简版，assets 的全量 schema_list 必须保留
$BuiltFiles = 'pinyin.table.bin', 'pinyin.prism.bin', 'pinyin.schema.yaml',
              't9_pinyin.prism.bin', 't14_pinyin.prism.bin', 't14_pinyin.schema.yaml',
              'english.table.bin', 'english.prism.bin'
foreach ($f in $BuiltFiles) {
  & $adb shell "test -f $built/$f"
  if ($LASTEXITCODE -ne 0) { throw "缺少编译产物 $f（见 $LogFile）" }
  & $adb pull "$built/$f" "$Staging\build\$f" | Out-Null
}

# ---------------------------------------------------------------- 5. 覆盖 assets + 提示升版
Write-Host "`n=== 覆盖 assets/rime/build ===" -ForegroundColor Cyan
foreach ($f in $BuiltFiles) {
  Copy-Item "$Staging\build\$f" (Join-Path $AssetsRime "build\$f") -Force
  $sz = [math]::Round((Get-Item (Join-Path $AssetsRime "build\$f")).Length/1MB, 2)
  Write-Host ("  {0}  {1} MB" -f $f, $sz)
}

Write-Host @"

完成。别忘了：
  1. 词库有变化时递增 DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM（否则已装设备不重拷）
  2. 重新打包：./gradlew :app:assembleDebug
"@ -ForegroundColor Green
