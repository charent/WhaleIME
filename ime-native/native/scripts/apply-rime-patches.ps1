# 把受版本管理的 librime 源码补丁与插件覆盖进 fetch 出来的源码树。
# librime 源码树在 native-build/（gitignore、每次 fetch 重建），所以任何源码改动
# 必须以补丁文件形式放在 scripts/ 里、由构建脚本覆盖（与 no_logging.h 同一机制）。
#
# 当前补丁清单（cpp/README.md「librime 源码树改动」要与这里同步）：
#   1. rime-no-logging-android.h       -> src/rime/no_logging.h        （日志宏改写 logcat）
#   2. rime-corrector-whale.cc         -> src/rime/dict/corrector.cc   （纠错邻键表补全 +
#        四操作编辑自动机：替换(1)/插入(漏打,1)/删除(多打,1)/换位(2)，代价=误按次数；
#        插入允许**一段连写漏字**（最多 2 个，且必须同位置连写）—— shg→shang 这类
#        少打两个字母的音节原先完全走不到；另有「拼写长度/按键数比例」闸门，
#        3 个按键被 1~2 个字母的音节吃掉（hoa→a）或补出 7 个字母（hoa→asakawa）
#        一律不算误按）
#   3. rime-syllabifier-whale.cc       -> src/rime/algo/syllabifier.cc （纠错五连修：
#        阈值 5→2 / 惩罚按编辑距离缩放 / 补全改从精确前沿生长，不再被纠错边禁用 /
#        同段同音节的拼写属性整条取优，纠错边不再被简拼边洗成精确边；撞车时输掉的
#        纠错解释在裁剪后以纠错边补回，简拼与纠错都留得住 /
#        每段纠错音节限流到 6 条（按误按距离分档，每档 4 条）—— 纠错音节数量直接决定
#        组句路径数，不限流时 yiuxiwanjia 第 11 键 213ms（11 键累计 1.23s），限流后
#        12ms/键、累计 61ms，误按召回还小幅提升。CORR_MAX_SYLLABLES 覆盖默认值，
#        CORR_STATS=1 打点。
#        另有：纠错边的终点顶点不参与「按拼写类型裁剪」—— 否则纠错只能发生在最后一个
#        音节（shgyici 的上一次要求第一个音节就是纠错，顶点被删，整条路径组不出来））
#   4. rime-script-translator-whale.cc -> src/rime/gear/script_translator.cc
#        （纠错候选 comment 末尾追加 "?"，客户端渲染角标；候选排序主键从「覆盖长度」
#         改为「可信覆盖」= 解释到的字节数 − 误按键数，句子的组句门槛回到上游；
#         另有桶内「原样优先」：输入能被精确音节切完时，同一 end_pos 桶里原样解释的
#         词条先吐、改键词条扣到 deferred 之后再放（改键候选标 type=correction，
#         免得被 ContextualTranslation 的同 end_pos 重排洗回去）。
#         另有组句 vs 整词在 cover 打平时整词优先（九键 segmentation_prior 方案除外）。
#         另有 preedit：分词按用户敲的键自己的精确切分呈现（纠错候选不再显示
#         ke'ji'a 这类用户没敲过的音节），供候选面板的分段选择使用。
#         另含九键「切分先验重排」：句首 + 满覆盖 + 多切分时，先按 n-gram 分定切分、
#         再在同一切分内按词条权重排，用 schema 选项 translator/segmentation_prior 控制。
#         另有短输入「纠错条目提前」：≤4 字节但拼不出完整拼写（lsi）时，把编码长度
#         正好等于输入长度的改键条目提到桶内第 5 条（HoistCorrections），否则它们会被
#         成片的简拼双字词压到几十名开外。
#         另有简拼串（abbrev_input_，如 shyzi）的桶内词频次序：完整拼写只覆盖不到一半
#         按键时，「原样优先」失效 —— 编码长度 == 输入长度的改键条目（数字 shu'zi）按
#         词频直接出，且这类桶的 cover 固定为 end_pos（Slot::fixed_cover），免得桶头
#         一跳就把整桶的简拼词冲散。
#         2026-11-19：简拼串的满覆盖桶改为按「词条权重 − 拼写长度偏差」整桶重排
#         （RerankAbbrevSlot，偏差 = 词条字母数 − 按键数），并撤掉该桶的「原样优先」
#         —— 缩写猜测（临死时 = l|si|shi、试过一次 = sh|g|yi|ci）与改过一两键的真词
#         （老师、上一次）由此按词频 + 按键依据竞争，见 C15。
#         见 docs/pinyin-candidate-ranking.md、docs/pinyin-segmentation-prior.md）
#   5. rime-spelling-whale.h -> src/rime/algo/spelling.h
#        （SpellingProperties 增加运行时字段 correction_distance = 纠错边的误按键数，
#         供候选排序按「改了几个键」而不是「改写了几个字节」扣分；不参与 prism 序列化）
#   5a. rime-corrector-whale.h -> src/rime/dict/corrector.h
#        （Corrections 的键从「音节」改成「音节 + 消耗跨度」：同一音节在同一处可能有
#         多条跨度的解释，只按音节去重会把长跨度那条丢掉，多音节纠错因此组不出来；
#         Correction 另带 spelling_length 供「长度偏差」排序）
#   5b. rime-table-translator-whale.cc -> src/rime/gear/table_translator.cc
#        （LazyTableTranslation 大小写兜底：原码整词查不到时依次用全大写、全小写再查一遍，
#         GPU/USB/PDF/HTTP 这类按大写存的缩写词才能被 gpu/usb 敲出来，反向也成立；单键
#         输入不走兜底，且中文方案单键只解释成拼音前缀 —— 编码就是该字母的整词命中词条
#         （A/B/K、c#/c++）整条丢掉，只留补全，否则从/才/吃 会被挤到后面）
#   6. rime-dict-compiler-whale.cc -> src/rime/dict/dict_compiler.cc
#        （部署链修复：本项目的 assets 只有预编译 *.table.bin / *.prism.bin，没有词库源，
#         上游在这里有两处致命行为 ——
#         a) reverse.bin 缺失/过期就无条件置 rebuild_table，BuildTable 拿空词表跑一遍，
#            在部署目标目录写出空表（62MB -> 3.5KB），运行时优先加载它 → 候选全空；
#            改为「没有词库源时不重建词表，沿用预编译 table」；
#         b) 只在词典带 import_tables 时才从旧表读音节表，单表词典取不到音节表，
#            prism 重建必然失败 → speller/algebra 的 patch（模糊音/纠错）永远不生效；
#            改为无条件从 primary_table 的 syllabary chunk 兜底。
#         修好后设备端 redeploy 能真正重建 prism，pinyin.custom.yaml 才有效；
#         反向 db 依然不重建（无源可依），反查功能本来也没发布）
# 插件：
#   7. native-build/src/librime-octagram -> plugins/librime-octagram（八股文语法模型，
#      源码由 fetch-sources.ps1 下载；plugins/ 目录为空时 CMake 不收，这里装进去后
#      BUILD_MERGED_PLUGINS 会把它并进 librime，模块自动进 kDefaultModules）

param(
  [Parameter(Mandatory = $true)][string]$SrcRoot,
  # install 目录里的头文件副本也要覆盖，否则 include 优先级会吃掉源码树的改动
  [string]$InstallDir
)

$ErrorActionPreference = 'Stop'

# ENABLE_LOGGING=OFF 时 librime 用 no_logging.h，上游实现是全部编译成空语句，
# Android 上等于完全静默。换成写 logcat 的版本（tag: librime），便于排查部署/加载问题。
$androidNoLogging = Join-Path $PSScriptRoot 'rime-no-logging-android.h'
Copy-Item $androidNoLogging (Join-Path $SrcRoot 'librime-1.17.0\src\rime\no_logging.h') -Force
if ($InstallDir) {
  $installedNoLogging = Join-Path $InstallDir 'include\rime\no_logging.h'
  if (Test-Path $installedNoLogging) {
    Copy-Item $androidNoLogging $installedNoLogging -Force
  }
}

# corrector.cc 的 keyboard_map 上游只有同行左右邻键，移动端竖向误按（t↔f、a↔z）
# 覆盖不到；换成补全上下排斜向邻键的版本（对齐原 Kotlin 版邻键模型）。
Copy-Item (Join-Path $PSScriptRoot 'rime-corrector-whale.cc') `
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\corrector.cc') -Force

# syllabifier.cc：见文件头注释（纠错阈值/惩罚/补全三处修复）
Copy-Item (Join-Path $PSScriptRoot 'rime-syllabifier-whale.cc') `
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\algo\syllabifier.cc') -Force

# script_translator.cc：纠错候选 comment 追加 "?"，候选排序按「误按键数」扣分
Copy-Item (Join-Path $PSScriptRoot 'rime-script-translator-whale.cc') `
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\gear\script_translator.cc') -Force

# table_translator.cc：原码整词查不到时，依次用全大写、全小写再查一遍（词表的缩写词按
# 大写存：GPU / USB / PDF / HTTP …，不加这一步 gpu / usb 这类简写在候选里完全看不见；
# 反向的大写输入命中全小写词条同一处解决）
Copy-Item (Join-Path $PSScriptRoot 'rime-table-translator-whale.cc') `
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\gear\table_translator.cc') -Force

# spelling.h：SpellingProperties 增加运行时字段 correction_distance（纠错边的误按键数）。
# 只加字段、不动序列化，prism 产物无需重建。
$spellingPatch = Join-Path $PSScriptRoot 'rime-spelling-whale.h'
Copy-Item $spellingPatch (Join-Path $SrcRoot 'librime-1.17.0\src\rime\algo\spelling.h') -Force
if ($InstallDir) {
  $installedSpelling = Join-Path $InstallDir 'include\rime\algo\spelling.h'
  if (Test-Path $installedSpelling) {
    Copy-Item $spellingPatch $installedSpelling -Force
  }
}

# corrector.h：Corrections 的键从「音节」改成「音节 + 消耗跨度」。
# 一个音节在同一处可能有多条跨度的解释（hoafe 的 hao：吃掉 2 字节错 1 键 /
# 吃掉 3 字节错 2 键），只按音节去重会把长跨度那条丢掉，多音节纠错就组不出来。
$correctorHeaderPatch = Join-Path $PSScriptRoot 'rime-corrector-whale.h'
Copy-Item $correctorHeaderPatch (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\corrector.h') -Force
if ($InstallDir) {
  $installedCorrector = Join-Path $InstallDir 'include\rime\dict\corrector.h'
  if (Test-Path $installedCorrector) {
    Copy-Item $correctorHeaderPatch $installedCorrector -Force
  }
}

# dict_compiler.cc：部署链修复（无词库源时不重建空表 + prism 音节表兜底），
# 没有这处修复设备端 redeploy 会写出空表/空 prism，直接导致零候选（见文件头）
Copy-Item (Join-Path $PSScriptRoot 'rime-dict-compiler-whale.cc') `
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\dict_compiler.cc') -Force

# 刷新被覆盖文件的 mtime：Copy-Item 会保留源文件的修改时间，补丁文件比上次的
# 目标文件时间戳更早时 ninja 会判定「无需重编」，改了半天等于白改（踩过）。
$patched = @(
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\no_logging.h'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\corrector.h'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\corrector.cc'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\algo\syllabifier.cc'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\gear\script_translator.cc'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\gear\table_translator.cc'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\algo\spelling.h'),
  (Join-Path $SrcRoot 'librime-1.17.0\src\rime\dict\dict_compiler.cc')
)
foreach ($f in $patched) {
  if (Test-Path $f) { (Get-Item $f).LastWriteTime = Get-Date }
}

# ---------------------------------------------------------------- octagram 插件
$octagramSrc = Join-Path $SrcRoot 'librime-octagram'

$octagramDst = Join-Path $SrcRoot 'librime-1.17.0\plugins\librime-octagram'
if (Test-Path $octagramSrc) {
  if (Test-Path $octagramDst) { Remove-Item -Recurse -Force $octagramDst }
  Copy-Item -Recurse $octagramSrc $octagramDst
  # 工具（build_grammar）一并编出来，供 .gram 预编译用
} else {
  Write-Warning 'librime-octagram 源码不存在（fetch-sources.ps1），跳过语法插件'
}
