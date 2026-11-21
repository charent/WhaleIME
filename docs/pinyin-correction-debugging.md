# 拼音纠错 badcase 排查与回归手册

遇到「打错一个键，期望的词没出现 / 排太后 / 排太前」这类问题时，照这份文档走一遍即可。
它记录的是**链路结构、判定规则、排查顺序、历史 case 与回归基线**，不是某个具体问题的补丁说明。

> 注：本文件是唯一入库的 `docs/` 文档（`.gitignore` 整条忽略 `docs`，只对它开了例外），
> 其余 `docs/**`（语料、探针脚本、基线输出）是本地资料库，不随仓库分发。

---

## 1. 链路一览

```
按键 → Compose KeyGrid → KeyDispatcher → InputDispatcher → RimeEngine
     → librime（syllabifier → script_translator → 候选）→ DictDecoder.CandidateState
     → CandidateBar / CandidatesPanel
```

纠错相关的代码只有五处，改之前先确认问题落在哪一层：

| 位置 | 文件（**改这里，不要改 native-build 下的副本**） | 负责 |
|---|---|---|
| 邻键表 / 编辑距离 | `ime-native/native/scripts/rime-corrector-whale.cc` | 哪些键算「邻键」，替换/插入/删除/换位的代价 |
| 纠错结果容器 | `ime-native/native/scripts/rime-corrector-whale.h` | `Corrections` 以「音节 + 消耗跨度」为键（同一音节可有多条跨度的解释） |
| 音节图 | `ime-native/native/scripts/rime-syllabifier-whale.cc` | 怎么把输入切成音节、纠错边怎么生成、容错阈值/惩罚 |
| 候选排序 | `ime-native/native/scripts/rime-script-translator-whale.cc` | 桶内次序、跨桶次序、纠错限流、`?` 角标 |
| 英文/词表候选 | `ime-native/native/scripts/rime-table-translator-whale.cc` | 原样命中的 quality 档位、大小写兜底（见 C11、C14） |
| 展示/交互 | `ime-core/.../engine/RimeEngine.kt`、`ime-ui/.../compose/KeyDispatcher.kt`、`ime-ui/.../compose/keyboard/CandidatesPanel.kt` | 读音栏、分段选择、联想、面板进出 |

补丁由 `ime-native/native/scripts/apply-rime-patches.ps1` 覆盖进源码树
（`ime-native/native/native-build/src/librime-1.17.0`），该目录是 gitignore 的临时树，
**直接改它会在下次构建时被覆盖**。

---

## 2. 关键概念

看懂这几条，大部分 badcase 能直接对号入座。

1. **桶（slot）**：候选按「覆盖到输入的哪个位置（`end_pos`）」分桶。`b'si` 有 `end_pos=1`（只覆盖 b）、`end_pos=3`（覆盖整串）等桶。
2. **cover**：跨桶排序主键 = **解释到的字节数 − 误按键数**。cover 大者先出；cover 相同比 `end_pos`（解释得更远的优先），再比词条权重。
3. **edits（误按键数）**：纠错边的编辑距离之和（`SpellingProperties::correction_distance`，由音节图带入），不是「被改写了几个字节」——`yiu→you` 只错一个键，却改写整个 3 字节音节。
4. **faithful**：这条候选是否**没改动用户敲的键**（`MeasureExactBytes == end_pos`，等价于 `edits == 0`）。注意简拼边、补全边也算「原样」。
5. **exact_full_coverage_**：整段输入能否被「非纠错边」切完。为 true 时启用**桶内原样优先**（`TakeExactEntry`：原样词条先出、改键词条扣进 `deferred`）。
6. **abbrev_input_**（2026-11-09，C9）：整段能被精确切完、但**完整拼写只覆盖不到一半按键**的「简拼串」（`shyzi` = `sh|y|zi`，`MaxFullSpellingEnd < consumed/2`）。这类输入里「原样」条目本身就是缩写猜测，桶内次序改由词频裁决：编码长度 == 输入长度的改键条目不再进 `deferred`，且这类桶的 cover 固定为 `end_pos`（`Slot::fixed_cover`），不随桶头跳动。
7. **纠错限流**：`correction_limit()`（默认 4，短输入提前时 8）—— 全程最多放行这么多改键候选，超出的直接跳过。
8. **`?` 角标**：comment 末尾加 `?` 表示这条改了键，UI 据此渲染角标。
9. **尾部悬空（`hanging_tail_`，2026-11-14）**：整段能被精确边切完（`exact_full_coverage_` 为 true），但**完整拼写**只覆盖到 `consumed` 之前（`MaxFullSpellingEnd < consumed`，且不是简拼串）—— 末键只能当某个音节的缩写（`xianf` 的 `f` → fa/fan/feng…）。这类输入的满覆盖桶里，「原样」条目本身就是缩写猜测，不享受「原样优先」，纠错与它按词典次序（词频 + 类型先验）竞争，见 C12。
10. **单音节输入（`single_syllable_`，2026-11-14）**：整段就是一个音节（`ke` / `beng` / `xi`，起点就有一条非纠错边跨到末尾）。这类输入的满覆盖桶里没有词，全是同音字表，「原样优先」会退化成「同音字表隔离」（`ke` 的 了 排到 50 名外），所以那个桶改用**有限偏置归并**（`kFaithfulFirstMargin = 0.5`，改键条目词频高出 1.65 倍才插队），见 C13。
11. **跨度（span / `Correction::length`，2026-11-19）**：一条纠错解释「吃掉了多少按键」。同一个音节在同一处可以有多条跨度的解释：`hoafe` 的 `hao` 既能是「`ho` 后插一个 a」（错 1 键、吃 2 字节），也能是「`hoa` 换位成 hao」（错 2 键、吃 3 字节）。它们是**不同长度的边**，都要留在图上（C15）。
12. **拼写长度偏差（deviation，2026-11-19）**：`Σ 音节拼写字母数 − 这段输入的按键数` 的绝对值。差 0 = 每个字母都对应一个按键（用户确实在按字母拼这个词，只是有几个键敲歪了）；差 2 = 少打了两个字母（缩写或漏打）。简拼串的桶内重排用它取代「原样优先」，见 C15。
13. **纠错衔接点（`correction_end_positions`，2026-11-19）**：只靠纠错边才能到达的顶点。上游的顶点裁剪按**拼写类型**判定，而纠错边不参与类型计算，于是这些顶点带着 `kInvalidSpelling` 被删、出边一起消失 —— 纠错因此只能发生在最后一个音节。这条豁免是「第一个音节就错」的路径（`shgyici` → 上一次）能存在的前提，见 C16。

> 词条 `weight` 的**可比性取决于词典**：旧的 100 占位档（ext/tencent 表）跨编码不可比，
> 那时只能靠 cover / faithful / 编码长度这类**结构信息**排序；换成 rime-frost（2026-11-07）
> 之后 ≥99.9% 的词条带真实词频（`weight = ln(freq)`），同一 `end_pos` 桶内按权重比较才有意义
> —— C9 就是在这个前提下把简拼串的桶内次序交还给词频。
> 词典里仍存在占位条目的边角情形：拿不准时直接查
> `ime-native/src/main/assets/rime/build/pinyin.table.bin` 里的
> 真实 `weight`（`Table::Metadata` 之后是 syllabary/index，词条 `{StringType text; float weight}`；
> 1.17 的 string_table 在 metadata 第 60 字节、syllabary 在第 44、index 在第 48）。

---

## 3. 排查顺序（固定四步）

### ① 复现（设备测试台）

模拟器需已推送 `share` / `user` 与 `librime.so` 到 `/data/local/tmp/rime-q`：

```powershell
$adb = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'
& $adb shell "cd /data/local/tmp/rime-q && printf 'bsi\n' | LD_LIBRARY_PATH=. ./qc_check share user 80"
```

`qc_check` 是 `ime-native/native/scripts/query-console.cc` 编出来的查询器，第 3 个参数是打印条数
（第 4 个可选参数是 `prebuilt_data_dir`，指到 `rime-v*/build` 可直接用随包部署产物，免现编）。
重新编译（改完 console 源码后）：

```powershell
cd ime-native\native
$cc = Join-Path $env:ANDROID_SDK_ROOT 'ndk\28.0.13004108\toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe'
& $cc --target=x86_64-linux-android23 -O2 -static-libstdc++ `
  -I native-build\install\x86_64\include `
  -L native-build\build\x86_64\librime-hosttools\lib -lrime `
  -o $env:TEMP\qc_check scripts\query-console.cc
& $adb push $env:TEMP\qc_check /data/local/tmp/rime-q/qc_check
& $adb shell "chmod +x /data/local/tmp/rime-q/qc_check"
```

支持的命令：每行一个输入串；空行清空组合；`#schema pinyin|t9_pinyin` 切方案；`#setinput xxx` 整串替换输入。

### ② 判断「候选在不在」

```powershell
& $adb shell "cd /data/local/tmp/rime-q && printf 'bsi\n' | LD_LIBRARY_PATH=. ./qc_check share user 200 > bsi200.txt"
& $adb pull /data/local/tmp/rime-q/bsi200.txt $env:TEMP\bsi200.txt
python ime-native\native\scripts\inspect-candidates.py $env:TEMP\bsi200.txt bsi
```

输出会告诉你候选里**单段 / 多段**各多少条、单段具体是哪些。
若期望词 200 条内都没有 → 属于「没生成」；若在但排 60+ → 属于「排序」；两者修法完全不同。

### ③ 归类（决定改哪一层）

| 现象 | 大概率原因 | 去改 |
|---|---|---|
| 期望词的音节组合根本不在图里（如 `s→a` 没产生 `bai`） | 邻键表缺键 / 容错阈值 / BFS 未覆盖该编辑操作 | `rime-corrector-whale.cc` |
| 期望词的**第一个音节**要纠错，整条路径却不存在 | 纠错边的终点顶点被「按拼写类型裁剪」删掉了 | `rime-syllabifier-whale.cc`（`correction_end_positions`，见 C16） |
| 期望词的某个音节在图里「换个跨度」才有用（`hoa` 要 3 字节的 `hao`） | `Corrections` 只按音节去重，长跨度解释被丢掉 | `rime-corrector-whale.h` / `.cc`（见 C15） |
| 少打了两个字母的音节（`shg`→`shang`）走不到 | 插入只允许一个字母 | `rime-corrector-whale.cc`（`kMaxInsertRun`，见 C16） |
| 音节边存在，但「该音节 + 该音节组」的条目不见了 | 每段纠错音节限流按音节 id 截断 | `rime-syllabifier-whale.cc`（`kMaxCorrectionsPerDistance`，见 C17） |
| 音节边有，但该编码的词条不在候选里 | 桶内被 `deferred` 丢弃、或被限流跳过 | `rime-script-translator-whale.cc` |
| 候选在，但排在几十名开外 | 桶内是「编码顺序」，被同桶的简拼/预测词压住 | 同上（`HoistCorrections` / `RerankAbbrevSlot` / `TakeExactEntry`） |
| 简拼串里「罕见词的原样解释」压过「高频词的纠错」 | 简拼串的桶内次序仍按「原样优先 + 误按惩罚」 | 同上（`RerankAbbrevSlot`，见 C15） |
| 期望词是「整串只差末键」的纠错，却被一串「缩写把末键补全」的词压住（`xianf` → 想） | 末键拼不出音节（尾部悬空）却仍走了「原样优先」 | 同上（`hanging_tail_` / `TakeExactEntry`，见 C12） |
| 单音节输入里高频纠错词排在整张同音字表之后（`ke` → 了 第 50+） | 「原样优先」是无限惩罚，把同音字表当成了不可逾越的墙 | 同上（`single_syllable_` / `kFaithfulFirstMargin`，见 C13） |
| 候选在，但被另一条更短的候选挤掉 | 跨桶 cover 比较 | 同上（cover 计算） |
| 整段只差一个键的词，被「少解释一两个字节但全对」的候选挤掉 | 扣分口径不是「误按次数」（或 tie-break 方向） | 同上（`Coverage` / `IsBetterKey`，见 C6） |
| 候选明明有，界面上却看不到 | UI 过滤、分段选择、联想覆盖 | Kotlin 侧 |

### ④ 改 + 回归（见第 5 节）

---

## 4. 历史 case（同类问题先看这里）

### C1 `beng` 首选是本/能，把「崩」压在后面

- **根因**：`beng` 能被精确切完（`exact_full_coverage_`），但桶内次序由词典给，
  `本`（code `ben`，改 1 键）靠词频压过 `崩`（code `beng`，原样）。
- **修法**：`TakeExactEntry` —— 原样词条先出，改键词条扣进 `deferred`，本桶原样出完再放。
- **注意**：改键候选要标 `type=correction`，否则会被 `ContextualTranslation` 的
  同 `end_pos` n-gram 重排洗回去。

### C2 `lsi` 完全没有「来」

- **根因**：`lsi` 能靠 `l`（简拼）+ `si` 切完 → `exact_full_coverage_ = true`，
  于是桶里 60+ 条 `lX'si` 简拼双字词（类似/罗斯/螺丝…）先出，`lai`（来）被 defer 到第 68 位。
- **修法**：新增**全拼口径**判据 `HasFullSpellingCoverage`（音节拼写长度 == 边覆盖长度），
  为 false（说明用户敲的键不是合法拼音）时启用 `HoistCorrections`，把「编码长度正好等于输入长度」的改键条目提到桶头。
- **坑**：`l` 这类简拼边在 prism 里仍是 `kNormalSpelling`（`SpellingProperties.type` 全是 0），
  只能靠 `Table::GetSyllableById()` 反查音节拼写长度来识别。

### C3 `bsi` 有「被」但没有「白 / 百」

- **根因**：`bai` 组的词条排在 `bei` 组之后，而纠错限流只放 4 条，`bei` 组占满配额。
- **修法**：`HoistCorrections` 改为**按音节分组**提前（每组 2 条、最多 4 组），
  限流同步放宽到 8（`correction_limit()`）。
- **未解**：`百` 是 `bai` 组第 5 条，仍在组内配额之外；要放开就调 `kPerGroup`，
  代价是短输入语料里 2 条正确候选被挤出前 20（数据见第 5 节）。

### C4 九键「福寿年高」这类满覆盖整词凭空消失

- **根因**：`TakeRawEntry` 已经把桶头取出（游标前移），但 `SlotHasEntry` 只看底层迭代器是否耗尽，
  于是"已取出还没吐出"的 head 被跳过，永远发不出来。
- **修法**：`SlotHasEntry` 先判 `head_valid && head`。

### C5 短输入纠错召回暴跌（改排序时最容易踩）

- **现象**：任选其一 —— 提太多改键条目、把 `kCorrectionCoverCredit` 从 0.5 调到 0.85、
  放宽限流到 10。
- **实测数据**（319 条单键误按语料，top5）：

  | 方案 | top1 | top5 | top10 |
  |---|---|---|---|
  | 基线 | 11 | 148 | 165 |
  | 提 4 条到桶头 + 限流 10 | 9 | 70 | 97 |
  | 提 4 条 + cover 信用度 0.85 | 2 | 58 | 76 |
  | 仅 ≤4 字节输入、每组 2 条、提 1 组 | 11 | 148 | 165 |

- **结论**：误按输入的最优解释往往仍走「简拼/补全」，激进提前改键条目会挤掉正确答案。
  任何排序改动**必须**跑第 5 节的误按语料，不要把 top5 打下去。

### C6 `zhiyiu` 纠错不出「只有」（2026-11-08，扣分口径按字节 → 按误按键数）

- **现象**：`zhiyiu` 前 40 条全是 `zhi'yi` 的同码词，`只有 [zhi'you?]` 排第 41；而同样
  只错一个键的 `yiuxi → 游戏` 是第一名，把 `zhi` 段单独选中后 `yiu` 也能出「有」。
- **根因**：旧 cover = `精确字节 + 纠错字节 × 0.5`，**按被改写的字节数**打折。一次误按
  改写多少字节取决于该音节的拼写长度：`yiu→you` 只错一个键，整个 3 字节音节都被记成
  纠错，于是「zhi + you（错 1 键，解释满 6 字节）」只有 `3 + 3×0.5 = 4.5`，输给
  「zhi + yi（0 键，剩一个 `u` 没解释）」的 `5.0`。而 `zhi'yi` 在词典里有 41 条同码词，
  40 名之内根本翻不到。`niyiu`/`yiuxi` 只是同码词少（6 条 / 2 条），同样的缺陷被掩盖。
- **修法**：扣分口径改为**误按键数**：`cover = 解释到的字节数 − edits`。`只有` 与 `之一`
  同为 5.0，由次键 `end_pos` 裁决 —— 解释到输入末尾的 `只有` 在前。旧 badcase 不受影响：
  `呵护宝宝`（4 键）仍是 `11 − 4 = 7 < 这不好 8`。
- **实现**：`corrector` 只记 `is_correction`，没有距离字段，所以 `SpellingProperties` 增
  加 `correction_distance`（`scripts/rime-spelling-whale.h`），音节图生成纠错边时写入，
  `probe_path` 改为「误按最少、其次原样字节最多」选路径并回传 edits，`Coverage()` 统一
  出 cover（整词与组句同一把尺子）。
- **唯一例外**：`HoistCorrections` 提前到桶头的条目仍只扣半字节 —— 它们已按「编码长度 ==
  输入长度 + 音节组」筛过，若再按误按次数扣分，`bsi` 里错 2 键的 是/白/摆 会整组沉掉。
- **实测**（模拟器 + 真实词库）：`zhiyiu` 1 只有? 2 知友? 3 之一…；`shiyiu` 1 是有?；
  `zhongyiu` 1 中有?；`niyiu` 1 你有?（原第 7）；`yiuxi` 1 游戏（不变）。
  合法语料 320 条期望词名次 0 变化，九键 318 条 0 变化；误按语料 top1 11→20、
  top5 148→147、top10 165、missing 139（掉出 top5 的 4 条里有 1 条是「十六块」被
  **纠错限流 4 条**截掉，属限流策略而非扣分口径）。

### C7 `beng` 首选是「本格」，`bang`→半个、`lin`→离你（2026-11-08，简拼边被纠错边洗白）

- **现象**：输入整串能用一个完整音节解释（`beng`/`bang`/`lin`），首候选却是拆成两段的词
  （本格 `ben'g`、半个 `ban'g`、离你 `li'n`），候选上方的分词显示也跟着变成 `ben'g` /
  `ban'g` / `li'n`。
- **根因**：音节图合并拼写属性时（`syllabifier.cc` 里 `spellings[syllable_id]` 的
  else 分支）上游只写 `type = min(type)`，`credibility` 与 `is_correction` 仍保留**先
  插入**的那条。同一段（`[current_pos, end_pos)`）上的同一音节会被两条拼写同时命中：
  - 简拼：`ge` 的缩写键 `g`（`type=kAbbreviation`，credibility≈-0.69）；
  - 纠错：corrector 的插入纠错 `g`→`ge`（`is_correction`，credibility≈-4.6）。
  合并后得到「type=Normal、is_correction=false、cred≈-0.69」的**四不像**精确边，于是
  1) 躲过「`last_type` 判定 → 整串已有更优路径就裁掉简拼边」的裁剪；
  2) 被 `script_translator` 当成原样解释，连 `?` 角标都不打，直接与 崩/帮/林 同桶比词频。
  词频高的双字词（本格/半个/离你）就这样压在正确的单音节字前面。
- **修法**：合并改为**整条属性取更优**（`PreferSpelling`：非纠错 > 纠错，其次 type 小、
  最后 credibility 高）。简拼边保持 `kAbbreviation` 被裁掉，纠错边保持 `is_correction`
  被打 `?` 并排在精确候选之后。**不要**只改 `type` 而保留先插入的 credibility/标记，
  那正是本 bug。
- **撞车的那份纠错不能丢**：一个音节在同一段上只能存一份属性，简拼赢了就会顶掉纠错解释，
  而简拼边在「整串能被完整拼写切完」时又被整条裁掉 —— 本格/半个/离你会彻底消失。所以
  输掉的纠错属性先收进 `discarded_corrections`，**裁剪之后**再以纠错边补回图上（顶点一并
  恢复）。只补「简拼解释已被删掉」的位置，因此纯简拼输入完全不受影响；补回来的边带
  `is_correction`+`correction_distance`，cover 扣分、打 `?`、排在精确候选之后按词频竞争。
- **不要**简单地把撞车一律判给纠错：简拼输入（`b`→被/吧/把、`sm`→什么）里同样存在
  「简拼 + corrector 插入」撞车，判给纠错等于把整个简拼标成纠错（全打 `?`、再被纠错限流
  截到 4 条），简拼会直接废掉。
- **实测**（模拟器 + 真实词库）：`beng` → 崩/蹦/泵/甭/绷…（本格退到纠错段）；`bang` →
  帮… 之后 `半个[ban'ge?]`；`lin` → 林…；分词显示回到 `beng`/`bang`/`lin`。合法语料
  320 条：316 不变、2 变好（降价折扣、正心泰片 由 2 → 1）、2 条罕见同码词互换（期望词
  1→2，新 1 名与它同码同切分）；九键 318 条 0 变化；误按语料 **`top1=19 top5=154
  top10=172 missing=132`**（旧 20/147/165/139，比只修合并的 19/145/163/141 还好 ——
  补回纠错边的意义就在这里）；
  `lsi`/`bsi`/`jifang`/`zhonguo`/`zhiyiu`/`shiyiu`/`niyiu`/`yiuxi` smoke 全部不变。

### C8 `yulejia` 分词显示成 `yu'le'ji'a`，分段选 jia 只剩「加」（2026-11-08，preedit 不再按候选编码切）

- **现象**：输入 `yulejia`，分词栏（preedit）与候选面板的分段都是 `yu'le'ji'a`。
  先选 娱乐，再点「ji」段/点读音栏的 `jia?`，`jia` 只剩「加」一个字，
  家/假/甲/价 全部不出现。
- **根因**：`ScriptSyllabifier::GetPreeditString` 是**按候选编码**做 DFS 打印输入字节：
  首候选 与科技啊 的编码是 `yu'ke'ji'a`（le→ke 改一个键），打印出来就是
  `yu'le'ji'a`。而 Kotlin 侧把 preedit 当分段依据（`RimeEngine.segments()` →
  面板分段选择，`focusSegment` 会 `setinput("ji")` 那个音节），于是：
  1. 分词栏显示了一个用户从没敲过的切分；
  2. `jia` 只作为「ji + a」的纠错读音存在，聚焦 `ji` 后 `jia` 的候选属于**纠错候选**，
     而纠错候选全局限流 4 条（还要按误按方向分给 `jiu?`/`ni?` 等组），所以只漏出「加」。
  注意：这不是「音节切分判断错了」—— 音节图里 `yu'le'jia` 的精确边一直都在
  （`HasExactFullCoverage` 为 true），错的只是 preedit 的取词来源。
- **修法**：
  1. `GetPreeditString`：候选是纠错来的（`MeasureExactBytes` 的 probe 成功但没走满）
     且 `[start, end)` 这段输入能被**精确音节**切完时，改用精确切分（`FaithfulSplit`，
     长音节优先，jia 优先于 ji'a）。原样候选的编码本来就对应精确切分，行为不变；
     纠错仍由 `?` 角标和 comment 表达（comment 里是候选真实读音）。
  2. `ScriptTranslation::PrepareCandidate`：组句与整词 **cover 打平**时整词优先
     （`parts` 小的先出）。yulejia 里 与科技啊（组句，cover 6）盖住了 娱乐机
     （整词，cover 6），旧次序靠 end_pos（7 > 6）把纠错组句排到了最前。只在
     cover 相等时生效，且九键（`segmentation_prior`）不启用 —— 那里同一串按键能
     切出不同音节，词数不可比（实测启用后一定会得到/去前线 两条退化）。
- **实测**（模拟器 + 真实词库）：`yulejia` → preedit `yu'le'jia`、1 娱乐机 2 与科技啊；
  面板分段 `yu / le / jia`，聚焦 jia 得到 加/家/假/甲/价…（不再走纠错限流）。
  26 键合法语料 316 不变 / 4 变好（福寿年高、裁并、天克地冲、徐柏龄 由 2→1）/ 0 变坏；
  九键 318 条 0 变化；误按语料 **top1=33 top5=154 top10=173 missing=132**
  （旧 19/154/172/132，top1 明显变好）。smoke：`zhiyiu`/`shiyiu`/`jifang`/`beng`/
  `lsi`/`bsi`/`zhebuhaoba`/`zhonguo` 全部符合预期。
- **教训**：改「分词/分段」相关行为时，先确认 preedit 的来源（候选编码 DFS），
  不要把它当成音节图的裁决结果；音节图对不对要用 `qc_check` 的 preedit + 候选一起看。
- **试过但不要重复的路**：
  1. 「整段能被精确音节切完时，组句词图只用精确边」（`PrepareForMakingSentence` 里滤掉
     `is_correction` 的条目）。它确实让 yulejia 出 娱乐加、分词正确，但误按语料
     top1 从 19 掉到 5：`shuyouguandai`（本意 输油管道，dai→dao 手滑）里，滤掉纠错边后
     该音节的最高频同音字变成「带」，于是「输油管+带」以 cover 满覆盖压过
     「输油管道[?]」。**误按输入里「按字面拼出来的组句」与「改一键的真实词条」结构上
     完全一样，只能靠语义区分**，所以不要用图过滤解决排序问题。
  2. 「多取几条组句再按 cover 排序」（`max_sentences` 调大）。无效：poet 的候选流里
     纠错组句的权重远高于精确组句，`娱乐加` 连前 24 都进不去；不滤图就得靠放宽
     `sentence_cutoff_threshold` 才看得到，噪声同时进来。

---

### C9 `shyzi` 里 数字 被 啥样子/树叶子/十一子 压到 24 名（2026-11-09，简拼串不再「原样优先」）

- **现象**：输入 `shyzi`，候选是 `1 啥样子 2 树叶子 3 十一子 4 收银子 … 24 数字?`
  —— 一个只错 1 个键的高频词（数字 shu'zi，freq 23100）排在「十一子」
  （freq 9）这种简拼凑出来的低频词后面。
- **根因**：`shyzi` 能被精确边切完（`sh|y|zi`），`exact_full_coverage_` 为 true，
  于是 `TakeExactEntry` 启用「原样优先」：桶 `end_pos=5` 里所有 faithful 条目先出，
  改键的 数字 被扣进 `deferred`。而桶内词典次序（含 abbreviation/纠错惩罚）其实
  把 数字 排在第 2 位 —— 顺序本身没问题，是「原样优先」把一个简拼猜测捧成了硬前提：
  `啥样子`(sha'yang'zi) 用 11 个字母的编码去解释 5 个按键，`十 一子`(shi'yi'zi) 也是，
  它们和 数字 一样只是「解释之一」（实测 weight：数字 10.05 / 啥样子 4.65 / 树叶子 3.09 /
  十一子 2.20）。
- **修法**（都不碰主键，只动桶内次序与桶的 cover）：
  1. `abbrev_input_`：`exact_full_coverage_ && MaxFullSpellingEnd < consumed/2`
     —— 「整串不是拼音，只是缩写」才算简拼串。门槛卡在「不到一半」而不是「完全不完整」：
     末尾误按（`dapiyinf` = da'pi'yin + f，完整拼写覆盖 7/8）必须继续走原样优先，
     否则误按语料 top5 154 → 124。
  2. `TakeExactEntry`：简拼串里**编码拼写长度 == 覆盖按键数**的改键条目（数字 shu'zi，
     5 == 5）不进 `deferred`，按桶内词频直接出（`CodeSpellingLength`）。
  3. `Slot::fixed_cover`：简拼串里「桶里还有原样条目」的桶，cover 固定为 `end_pos`
     （`MarkExactReachable` 一次 DP 求出）。否则桶头一跳（数字 cover 4）整桶就被
     `end_pos=4` 的补全词（是影子/是一种/使用者…）插队，简拼词全被冲散到 30 名开外。
- **实测**：`shyzi` → `1 数字? 2 啥样子 3 傻子? 4 狮子? 5 师资? 6 树叶子 … 8 十一子`
  （傻子/狮子/师资/沙子 是 sha'zi / shi'zi 的 1 键纠错，词频高于简拼词，符合预期）；
  附带 `lsi` 的 来? 从 6→5、`bsi` 的 被 从 6→5、`yiuxi` 少一条「有系」多一条「有戏」。
  回归：26 键合法语料 **320 条 0 变化**；九键 **318 条 0 变化**；
  误按语料 `top1=33 top5=154 top10=172 missing=132`（top10 −1，仍在 ≥172 的闸门内）。
- **教训**：「原样优先」的前提是**用户真的在拼拼音**。简拼串里「原样」条目只是缩写
  猜测，跟改键条目地位相同，该交回词频；判断「是不是在拼拼音」要看 `MaxFullSpellingEnd`
  这类**覆盖比例**，不能只看「能不能被精确边切完」（缩写边也算精确边）。
- **试过但不要重复的路**：
  1. 直接把 `TakeExactEntry` 的原样优先整个关掉（简拼串里所有改键条目都按权重混排）：
     `shyzi` 能出 数字，但桶头变改键条目后 cover 从 5 掉到 4，整桶被 `end_pos=4` 的
     补全词淹没；误按语料 top1 33→15、top5 154→79，`dapiyinf` 之类末尾误按全崩。
  2. 只锁 cover、不开 `covers_input` 放行：数字 仍然是 deferred，问题原样存在。
  3. 门槛写成「完整拼写覆盖 == 0」：`shyzi` 的 `zi` 是全拼（2/5 > 0），条件不成立。

---

### C10 `code` 的第一屏全是 `ci'de?` 这类纠错，英文词本身连影子都没有（2026-11-09，跨译法器比较 + UI 两处推翻）

- **现象**：输入 `code`（整串就是词库里的英文词 code），首屏是
  `词的? 粗的? 测的? 测得? 佛的? …`，英文那一批只有 `codec? coded? codes` 补全，
  **`code` 自己根本不出现**。
- **根因（两层，缺一不可）**：
  1. **跨译法器比较**：`Candidate::compare` 先比 `end`（越长越前）、再比 `quality`。
     pinyin schema 的 `translator/initial_quality` 是 1.2，`english/initial_quality` 是 1.1
     （`assets/rime/pinyin.schema.yaml`），所以**任何**满覆盖的拼音候选都排在
     整串原样命中的英文词前面 —— 哪怕是 `ci'de?` 这种只靠纠错边成立的词。
  2. **UI 层两处推翻引擎**（`RimeEngine.kt`）：
     - `arrangeForDisplay` 把中文模式下所有纯字母词条降到 `TIER_ENGLISH`（词库里混进来的
       ged/gear 不该抢中文的位置），于是即使引擎排第一也会被挪到中文之后；
     - `findEchoIndex` 把「第一条与编码同文本的候选」当回声删掉。回声的 quality 是 −100
       （`echo_translator.cc`），恒在列表末尾，`code` 命中英文词排在第 1 却正好与编码同文本，
       就被整条删掉了 —— 这也是「候选栏里压根没有 code」的唯一解释。
- **修法**：
  1. `script_translator`：改键候选（`!slot->faithful`，含 `HoistCorrections` 提前的条目）
     的 quality 一律压到 `initial_quality − 0.15`（1.05）。原样拼音 ≥ 1.2 不受影响，
     纠错仍然压过英文补全（1.1 − 1）与 cn_en（0.5），只是不再压过原样命中的英文词。
     quality 只用于跨译法器比较，同一译法器内部的桶内次序不读它。
  2. `arrangeForDisplay`：`text == Rime.getInput()`（忽略大小写）的英文词条不降档，
     档内次级键也用 `ORDER_KEY_DEFAULT`，保持引擎位置。
     （2026-11-14 又加了前置条件「整串未被中文完整拼写解释」，见 C14 —— `nu` 就是它加上去的。）
  3. `findEchoIndex`/`shouldDropEcho`：只有「列表已取完 + 命中项是最后一条」才算回声。
- **实测**：`code → code, 词的?, 粗的?…`；`hello/good/world/music/time/love` 也直接第一；
  `name → 那么, name`（原样拼音仍然优先）；`test` 当时是 托尔斯泰…之后第 4（C11 把它推到第 1）；
  `he → 和`、`an → 按`、`me → 么, me`、`wo → 我`、`shenme → 什么`、`shijian → 时间`
  全部不受影响。回归：26 键合法语料 **320 条 0 变化**、九键 **318 条 0 变化**、
  误按语料 **33/154/172/132 逐字节不变**。
- **教训**：引擎排好了不等于用户看到的就是那个顺序。UI 侧只要动过顺序（分档、剔回声、
  去重、拼音重拼），排查「引擎里明明第一」时都要先把这几步在脑子里跑一遍 ——
  先 `qc_check` 确认引擎输出，再看 `RimeEngine.updateCandidatesOrCommitText` 的加工链。
- **试过但不要重复的路**：
  1. 只改引擎（`quality` 上限）不改 UI：`qc_check` 显示 `code` 第一，App 里仍是纠错在前
     （降档 + 回声删除），现象像是"没生效"。
  2. 只改 UI 不改引擎：`code` 在引擎里排第 10（在 9 条纠错之后），不降档也只是第 10。
  3. 把 `english/initial_quality` 调到 1.25 以上（改 schema）：原样拼音 ≥1.2+拼写覆盖比，
     短输入还能勉强分开，但 `name` 这种全拼输入会和英文词挤在一起，且改数据要 bump
     `CURRENT_RIME_DICT_DATA_VERSIOM` 重拷 200 MB 词库，不如在 code 里按 faithful 区分。

---

### C11 `ok` / `gpu` / `usb` 这类英文简写排在后面（2026-11-09，缩写词条的三层修复）

- **现象**：`ok` 排第 8（前 7 名是 欧克/欧卡/欧柯… 这些 `ou'ke` 缩写猜测）；`gpu` / `cpu` /
  `usb` / `tv` 在候选里**完全没有**英文词，只有 光谱 / 菜谱 / 去上班 / 铁律? 这类拼音解释。
- **根因（三个独立的坑）**：
  1. **纯缩写解释压过原样英文**：`quality = exp(权重) + initial_quality + 全拼覆盖率`。
     `ok` 的拼音解释 欧克 是 `o|k` 两个缩写边拼出来的（全拼覆盖率 0），却仍拿 1.2；
     英文 `ok` 只有 1.1，于是永远排在后面。
  2. **部分全拼也算高分**：`gpu → 光谱（guang'pu）`里 `pu` 是全拼，覆盖率 2/3 → 1.87；
     `cpu → 菜谱` 同理。原样命中的英文词 1.1 连这个都压不过。
  3. **词表里缩写词只有大写形态**：`english.table.bin` 里有 `GPU`/`USB`/`PDF`/`HTTP`，
     没有 `gpu`/`usb`；敲小写就查不到（`grep` marisa 词表可验证）。`ok`/`app`/`id`
     这类恰好有小写词条，所以只有它们"看起来还行"。
- **修法**：
  1. `script_translator`：`entry->quality_len == 0`（整条编码没有任何全拼成分）的候选，
     quality 也压到 `initial_quality − 0.15`（1.05），与改键候选同档 —— 纯缩写猜测不该
     压过原样命中的英文词；有全拼成分的（`ni → 你`、`name → 那么`）不受影响。
  2. `table_translator`（新补丁 `rime-table-translator-whale.cc`）：
     - **整串原样命中**的词条 quality 固定为 `kExactWholeWordQuality = 1.95`：
       高于「缩写 + 部分全拼」（实测最高 1.87），低于「整串全拼」（2.2）。同一编码的
       多条词条（`id`/`ID`/`I'd`）quality 相同，稳定次序仍由词库权重（词频）决定。
     - **大小写兜底**：原码没有整词命中（什么都没有，或只命中 `tvOS` 这种补全）时，依次
       用全大写、全小写再查一遍并采用（`gpu → GPU`、`tv → TV`）。文本保持词库原样，
       `RimeEngine` 默认档会统一小写显示。
     - 兜底的边界（后续补的）：**编码长度 > 1 才兜底**，否则单键会把词表里的单字母词条
       `M`/`B`/`C`、`c#`/`c++` 当整词命中，压住 吗/们/被/从；变体只试一轮，命中后后续
       增量补全都沿用它。派生小写码的范围后来也收窄了（只给 `text == code` 的词条），
       见 C14。
- **实测**：`ok / app / code / test / gpu / cpu / usb / url / pdf / api / tv / pc / ui`
  全部第一（`gpu` 第二候选还是 `Graphics Processing Unit` 的全称）；`name → 那么, name`、
  `he → 和`、`an → 按`、`ni → 你`、`me → 么, me`、`shenme → 什么`、`shijian → 时间`、
  `shyzi → 数字?` 全部不变。回归：26 键语料 **320 条 0 变化**、九键 **318 条 0 变化**、
  误按语料 **33/154/172/132 逐字节不变**。
- **教训**：`english` 词表的键是**大小写敏感**的整词，缩写词只存大写；「词库里有」不等于
  「敲得出来」。排查「明明在词表里却没有」先用 marisa 查词表（见 §2 的 `pinyin.table.bin`
  解析方法，string_table 偏移换成 60 即可）。
- **不要重复的路**：往英文 `initial_quality` 上动手（见 C10），或者按「长度/大小写」在
  UI 里硬排 —— 后者会把 `he → 和` 一起翻掉。

---

### C12 `xianf` / `xianh` 里 想 掉到第 53 位（2026-11-14，尾部悬空的缩写猜测不再优先于纠错）

- **现象**：`xianf`（本意 `xiang`，g 的左右邻键是 f / h）前 52 条全是 `先发 xian'fa` / `宪法` /
  `掀翻 xian'fan` / `先富 xian'fu`… 这类「先 + f 开头音节」的词，`想 [xiang?]` 排第 53
  （`xianh` 同样）。UI 上就是「翻半天才看到想」。
- **根因（两层，缺一不可）**：
  1. **图判定**：末键 `f` 在音节图上能当**缩写边**（`f` → fa/fan/fang/fei/fen/feng/fo/fou/fu…），
     于是 `HasExactFullCoverage` 为 true、`exact_full_coverage_` 生效；但完整拼写只到 4/5
     （`MaxFullSpellingEnd = 4`）——末键本身拼不出任何音节。
  2. **桶内规则**：`TakeExactEntry` 按「原样优先」把改键条目扣进 `deferred`，等本桶原样条目
     出完再放。可这个桶里的「原样」条目**恰恰全是靠缩写把末键补全出来的猜测**，它们和
     「把 f 打成 g、整串读作 xiang」的纠错猜的是同一个未知键。规则在这里失去前提，于是
     52 条缩写猜测整批排在 想 前面（实测词频 raw：想 11.60、先锋 7.92，差约 40 倍）。
- **修法**：新增 `hanging_tail_ = exact_full_coverage_ && !abbrev_input_ &&
  MaxFullSpellingEnd < consumed`：尾部悬空时**满覆盖桶**不再扣纠错（`TakeExactEntry` 直接走
  `TakeRawEntry`），两边回到词典次序 —— 也就是「词频 + 类型先验（缩写 ≈ −0.69、每误按键
  −4.6）」的公平竞争。范围严格限死：
  - 只动 `end_pos == consumed_` 这一个桶。`end_pos < consumed_` 的桶没覆盖到悬空的末键，
    那里「原样」真的把用户敲的键解释掉了，原样优先照旧（放开全部桶实测把 `huizhuanhus`
    的 `会转化?`、`junshiguojiq` 的 `军事国家?` 挤到 20 名开外）。
  - `beng` / `xi` / `jifang` 这类整串全拼输入 `MaxFullSpellingEnd == consumed`，不触发；
    C1 的「本/能 压 崩」仍由原样优先兜住。
  - 简拼串（`shyzi`）走 `abbrev_input_` 那条既有路径，行为不变。
- **实测**（模拟器 + 真实词库，干净 `user` 目录）：
  `xianf` → `1 先锋 2 想? 3 宪法 4 像? 5 先放 6 向? 7 先发 8 掀翻`；
  `xianh` → `1 先后 2 想? 3 像? 4 鲜花 5 鲜活 6 现货 7 向?`；
  `beng`/`bang`/`lin`/`jifang`/`zhiyiu`/`shiyiu`/`niyiu`/`yiuxi`/`shyzi`/`lsi`/`bsi` 顺序不变
  （`zhiyiu` 只多挤进一条 `之于?`，见下）；
  合法语料 **320 条 0 变化**；九键 **318 条 0 变化**；
  误按语料 **`top1=125 top5=211 top10=220 missing=89`**（改前同一份二进制 124/208/218/93，
  三项都略好）。
- **不要重复的路**：
  1. **「拿到候选（含纠错）后整体按词频重排」**：跨编码的词频不可比 —— `beng` 里 本 的词频
     远高于 崩，纯词频排序会把 C1 打回去；候选是流式 iterator，全量收集再排还要多一次缓存
     + O(n log n)，热路径不划算。撤掉「原样优先」之后桶内顺序本来就是词频序（词典已按
     `credibility + weight` 排好），不需要再排一遍。
  2. **给悬空桶锁 `fixed_cover = end_pos`**（照搬 `abbrev_input_` 的做法）：实测反而更差 ——
     悬空桶里常混着「整串被纠错」的长词（`骨质疏松症`、`近时郭家狮子花`），锁 cover 等于把
     整桶抬到它们头上，`huizhuanhus` 的 `会转化?` 从 1 掉到 20 名外、`junshiguojiq` 的
     `军事国家?` 整条消失（被纠错限流截掉）。悬空桶的桶头在原样/改键之间跳是**正常**的，
     它的 cover 就该跟着桶头走。
  3. **动 `kCorrectionCredibility`（−4.6）**：它同时是「纠错 vs 缩写」「纠错 vs 补全」的唯一
     先验，语料里 1 键误按的噪声全靠它压住；本次只需要在悬空桶里撤掉结构性的「原样优先」，
     不必动先验。
- **教训**：`exact_full_coverage_` 只回答「能不能用非纠错边切完」，**不回答**「这些边是不是
  真把用户敲的键读成了完整音节」。缩写边/补全边也是非纠错边，所以「能切完」经常只是
  「末键被缩写吞掉了」。判断「用户到底在不在拼拼音」要看 `MaxFullSpellingEnd` 与 `consumed`
  的关系：C9 用它识别**整串都是缩写**（shyzi），C12 用它识别**只有尾巴悬空**（xianf）。

---

### C13 `ke` 里 了 排在 50 名开外（2026-11-14，单音节输入的「原样优先」改有限偏置）

- **现象**：`ke`（本意 `le`，k/l 是邻键）第一屏 20 条全是 可/课/克/科/氪/颗… 的 ke 同音字，
  `了 [le?]` 在第 50 位之后（`了` 的词频 raw 14.38，是同音节除 可 之外所有字的数倍）。
- **根因**：`ke` 是**单音节输入**，过 `exact_full_coverage_`，满覆盖桶里没有词、只有这个音节的
  **整份同音字表**（30+ 条）。桶内「原样优先」把改键条目整批扣到 `deferred`，等原样条目
  **全部**吐完才放 —— 于是纠错候选被隔离在同音字表之后，而词频信号（`了` 9.78 vs `克` 9.50 /
  `氪` 8.11）根本没机会参与比较。这是单一结构规则把词频整段盖掉，不是先验不准。
- **修法**：把「无限惩罚」换成**有限偏置**，并且只对单音节输入生效：
  - `single_syllable_`：起点有一条非纠错边直接跨到 `consumed`（`ke` / `beng` / `xi`）。
  - 该桶的 `TakeExactEntry` 改成**带偏置归并**：原样流与暂存纠错流各自按词频降序，改键条目
    只要满足 `weight_correction − kFaithfulFirstMargin > weight_faithful` 就插队
    （`kFaithfulFirstMargin = 0.5`，≈ 词频高 1.65 倍）；其余桶裕度取 `1e9`（等价于原来的
    「原样全出完再放」）。
  - 被插队的那条原样条目挂在 `Slot::pending` 上、下一轮先吐（**不能丢**：实测漏了这一步
    `huozong` 的 霍总/火鬃 会整条消失 —— 它已经从底层流里取走了）。
- **为什么不一刀切放开全部桶**：多音节桶里的「原样」是**整词精确匹配**，词典里没有词频的
  占位词条（`霍总` @ −54、`卷住`、`遮人`、`裁并`）全靠原样优先才排得上来。一刀切实测 320 条
  合法语料 **26 条变坏**（`huozong` 霍总 #1→#5、`caibing` 裁并 #3→MISS、`zheren` 遮人 #5→MISS…），
  换来的就是常见词纠错（汇总/活动、建筑、责任、带兵）往上顶。
- **实测**：`ke` → `1 可 2 课 3 克 4 了? 5 科 6 氪 7 而? 8 颗`（`了` 第 4）；
  `beng` → `1 崩 2 蹦 3 能? 4 泵 5 更? 6 甭`（`崩` 仍第一，`能` 的词频只高 0.04，被裕度挡在崩/蹦之后）；
  `bang` / `lin` / `xi` 完全不变；`xianf` / `xianh`（C12）不变；
  合法语料 **320 条 0 变化**、九键 **318 条 0 变化**、误按语料 **125/211/220/89**（与 C12 持平）。
- **教训 / 边界**：
  1. 「原样优先」是**结构性偏好**，落地时必须是有限力度。凡是用「整批扣到末尾 / 整批提桶头」
     实现的偏好，遇到「桶里条目数量极多且词频差距极大」的桶（同音字表）都会失效。
  2. 判断「该不该给纠错开门」看**桶里原样条目的性质**：单音节桶＝同音字表（可以按词频混排）；
     多音节桶＝整词精确匹配（必须保原样，占位词条没有词频可比）。
  3. 归并必然要**暂存**被插队的条目，流式 iterator 上一旦漏存就是「候选凭空消失」，
     排查时表现为某条候选 MISS/掉到 20 名外，而不是名次小幅波动。

---

### C14 `nu` 被英文词条 / 希腊字母抢首位，`gpu` 一度被全称顶掉（2026-11-14，英文候选的「原样命中」判据收紧）

- **现象**：`nu` 本身是合法音节，但首选是英文词条 `nu`（以及希腊字母 `ν`），怒/弩 排在后面；
  同一批改动之前还出现过反例 —— `gpu` 的首选是 `Graphics Processing Unit` 这条「全称」词条
  而不是缩写 `GPU`；而 `ok` 曾被莫名后移（欧克是 `o|k` 简拼，并不是完整拼写）。
- **根因（三处，都是「整串原样命中」判据取太宽）**：
  1. **UI 降档只看文本相等**（`RimeEngine.arrangeForDisplay`）：`text == Rime.getInput()`
     的英文词条一律不降档，于是 `nu` 这种「拼音音节恰好也是英文词」的码永远让英文领先。
  2. **词表派生小写码过宽**（生成 `english.table.bin` 的 `precompile-dict.ps1`）：原本给
     每条「code 含大写」的词条补一条小写码，`en_ext` 里 700+ 条「全称 → 缩写」也被派生，
     敲 `gpu` 直接命中一串全称。
  3. **大小写兜底不设边界**（`rime-table-translator-whale.cc`）：单键输入也会兜底查到大写
     字母词条（`M` / `B` / `C`、`c#` / `c++`），把 吗/们/被/从 挤到后面。
- **修法**：
  1. `arrangeForDisplay`：把「不降档」的前置条件收紧为「整串被**完整拼写**解释时不享有豁免」
     —— 存在 `comment`（去掉隔音符号后）等于输入、且不带 `?` 的汉字候选时，原样英文词条照样
     降档。只看「有没有汉字候选」不行：`ok` 会命中简拼的「欧克」，`ok` 就被误后移。
  2. 派生小写码只给 `text` 与 `code` 同形的词条（`GPU → gpu` 仍需，全称不再派生）。
  3. 大小写兜底只在编码长度 > 1 时做，且变体只试一轮（`boost::to_upper` / `to_lower` 两路，
     命中哪一路后续补全都沿用它）。
- **教训**：凡是拿「文本 == 输入」当「用户在打这个词」的证据，都要再问一句**这条输入能不能
  按用户的方案被完整拼写解释**。中文方案里 `nu` / `he` / `an` 都是合法音节，英文词条只是
  词库的副产品；而 `code` / `gpu` 拼不出完整音节，才真的是在打英文。这条判据同时决定了
  UI 降档（本节）和引擎 quality（C10 修法 1），两边必须用同一把尺子，否则会出现「引擎里第一、
  界面上不是」的 ghost。
- **边界**：
  - 豁免判据要排除简拼：`ok` 的 comment 是 `ou ke`，与输入不同，所以仍走豁免。
  - 纠错候选（comment 带 `?`）不能当作「完整拼写解释」，否则 `code` 又会掉回纠错之后。
  - 这条判据只在**候选已经拿到之后**做文本判断，不碰音节图；改动它不需要重编 `librime`，
    但要连同 §7 的「UI 会改顺序的两处」一起改。

---

### C15 `hoafe` 出不来 好的、`lsishi` 的老师排 91（2026-11-19，「多音节纠错」三处断链之一：跨度被去重 + 简拼桶按结构排序）

- **现象**：`hoafe`（本意 好的 `hao'de`，a/o 换位 + f→d）前 300 条里**根本没有 好的**，
  首位是 差的 `cha'de`；`lsishi`（本意 老师 `lao'shi`）首位 临死时 `lin'si'shi`，
  老师 排 **91**（而且是「l 的单字母预测词」，不是满覆盖候选）。
- **根因（两层）**：
  1. **跨度被去重**：`corrector` 的 `Corrections` 以**音节**为键，一个音节在同一处只留
     一条解释，取优口径是「误按最少、其次吃掉输入最多」。`hoa` 上的 `hao` 有两条解释：
     `ho` 后插一个 a（错 1 键、吃 2 字节）与 `hoa` 换位（错 2 键、吃 3 字节）。
     去重只留前一条 → 图上只有 `hao` 的 2 字节边，而 好的 需要它覆盖 3 字节，
     整条路径组不出来。同理 `cha`（插 c + 删 o，2 键 3 字节）就成了唯一能覆盖满的
     2 字词，独占首位。
  2. **简拼桶的结构性排序**：`lsishi` 的完整拼写覆盖为 0（起点只能是 `l` 这样的缩写边），
     命中 `abbrev_input_`。此时桶里的「原样」条目（临死时 = `l|si|shi`，l 是 lin 的缩写）
     只是缩写猜测，但桶内次序用的是**词条权重**（含 `ln(0.01) × 误按` 惩罚），
     于是 0 误按的 临死时（词频 ≈ 68）压过 2 误按的 老师（词频 ≈ 13 万）。
     老师 连满覆盖桶都进不去 —— `lao` 这条音节被「每段 6 条 + 音节 id 截断」挤掉了
     （见 C17）。
- **修法**：
  1. `Corrections` 改以 **`(音节, 跨度)`** 为键（`rime-corrector-whale.h`），同一音节的
     不同跨度各自保留。**这是多音节纠错的前提**：光有「每个音节都能纠错」不够，
     还要「每个音节能以这次的跨度纠错」。
  2. 每段纠错音节的取优次序加一层「**拼写长度偏差**」（`|音节字母数 − 吃掉按键数|`）：
     `hoa` → `hao`（换位，偏差 0）排在 `cha`/`ba`/`ga`（删掉 h、o 才凑出来，偏差 1~2）前面。
  3. 简拼串的满覆盖桶改用 `RerankAbbrevSlot` 整桶按
     **`词条权重 − 1.5 × 拼写长度偏差`** 重排，并撤掉该桶的「原样优先」
     （`TakeExactEntry`）。权重里的误按惩罚保留（只罚一遍，不再被 cover/原样优先罚第二遍），
     偏差项负责「这条解释有没有按键依据」。
- **实测**（模拟器 + 真实词库，干净 `user`）：
  `hoafe` → `1 好的? 2 喝咖啡? 3 差的?`（改前前 300 条里没有 好的）；
  `lsishi` → `1 历史? 2 开始? 3 老师? 4 来时?`（临死时 退到第 6 之后；历史 = `li'shi`，
  是把输入里多出来的 `s` 删掉，1 键，结构上确实最近）；
  `shgyici` → `1 是一次? 2 上一次? 3 试一次?`；
  `shyzi` 仍是 `1 数字?`（C9 的门槛没被破坏）。
  回归：26 键合法语料 **319 不变 / 1 变好 / 0 变坏**；九键 **318 不变**；
  误按语料 **`top1=121 top5=211 top10=219 missing=89`**（改前 125/211/220/89：
  top1 −4、top5/top10/missing 基本持平，15 条变差 / 14 条变好，其中多条是
  「从 MISS 到第 2」）。
- **不要重复的路**：
  1. 把简拼桶改成「纯词频」（把误按惩罚全部加回去）：`shyzi` 的 所以 会顶掉 数字
     （所以 词频只比 数字 高 0.4 倍，但当时它有 3 个误按键被整批免罚），
     `shgyici` 的 说过一句 这类「四个音节全改」的长词也会冒到第一。
     必须保留「每个误按键一份 ln(0.01)」，只把**结构性的第二遍惩罚**去掉。
  2. 只改排序不改图：`hoafe` 的 好的 压根不在图上（根因 1 在 corrector 层），
     怎么调排序都是 差的 第一。

---

### C16 `shgyici` 里 上一次 完全不存在（2026-11-19，纠错衔接点被裁 + 漏两个字走不到）

- **现象**：`shgyici`（本意 上一次 `shang'yi'ci`，漏打 `an`）前 300 条里没有 上一次，
  首位是 `sh|g|yi|ci` 这类**每个音节都是缩写**的解释（试过一次 / 上过一次）。
- **根因（两层）**：
  1. **图里没有 `shang` 这条边**：`shg` → `shang` 要补两个字母，而 2026-11-10 起
     「一条路径最多插一个字母」（`rec.inserts == 0`），补第二个字母的分支根本不存在。
     那条限制的动机是「2 字节输入靠双插入能拼出任何音节」（yiuxi 646ms），
     但它把「连写漏字」（`shg` 是少打了 `an` 两个字母）一并封死了。
  2. **就算边存在，路径也会被裁掉**：上游的顶点裁剪按**拼写类型**判定
     （`graph->vertices[i] > last_type` 就删顶点和它的出边），而纠错边不参与类型计算
     （`end_vertex_type` 只在 `!props.is_correction` 时更新）。于是「只靠纠错边到达的
     顶点」带着 `kInvalidSpelling` 被删 —— 纠错只能发生在**最后一个音节**（或精确前缀
     之后）。上一次 要求第一个音节就是纠错（`0→3` 是纠错边），顶点 3 一删，整条路径没了。
- **修法**：
  1. 插入改成**一段连写漏字**（`kMaxInsertRun = 2`），且第二个及以后的插入必须紧跟前一次
     （同一个 `rec.idx`）：只允许「在这个位置一起漏了几个字母」，不允许散落各处补字。
     这样每个插入态只有 26 个分支（而不是 26×26），上限仍由 `threshold` 兜住。
  2. 顶点裁剪豁免纠错边的终点（`correction_end_positions`）—— 边这一层早就写着
     「Don't care correction edges」，顶点这一层必须同步，否则纠错链断在中间。
     （`DiscardedCorrection` 的补回逻辑其实一直在手工做同一件事，这次改成通用规则。）
- **实测**：`shgyici` → 上一次 第 2（改前不存在）；`xhongguo` / `zhobgguo` 的中国 仍第一；
  `zhebuhaoba` → 这部好吧 / 这不好 不变；26 键语料 319 不变 / 1 变好 / 0 变坏；九键 0 变化。
- **性能（必看）**：两处改动都让音节图变大，组句（`Poet`）耗时随「纠错音节数 × 顶点数」
  增长。模拟器上 17 键 `shangyiciwomeiyou` 由 69ms 涨到 ~135ms（最慢一键 17→24ms），
  11 键 `nihaoshijie` 24→42ms、`yiuxiwanjia` 61→83ms。收益与代价都记在这里，
  再往上加「允许更多纠错边」之前先看这组数。
- **不要重复的路**：
  1. 允许「两条路径各插一个字母」再靠 distance 兜（即 `inserts < 2` 但不限定连写）：
     分支数回到 26×26，`yi` 这类 2 字节输入会重新生成成片的纠错音节，
     组句路径爆炸（这条是 2026-11-10 那一版踩过的坑）。
  2. 只豁免顶点不限制插入：`shgyici` 能出 上一次，但 `shg` 依然只能读成 `sh|g`
     （`shen`/`shang` 都到不了），双拼音纠错等于没做。

---

### C17 `lsishi` 的老师在图里被换成 kai/kei（2026-11-19，每段纠错音节限流的截断规则）

- **现象**：`lsishi` 的 `l` 段（`0→3`）上，图里是 `lai / lei / li / si / kai / kei`，
  **没有 `lao`**；而 老师 只差 `lao` 这一条边。
- **根因**：每段（同 `consumed` 长度）最多 6 条纠错音节，超出时按
  `(误按次数, 跨度, 音节 id)` 排序截断。距离档内的最后一道 tie-break 是**音节 id** ——
  与「这个音节能不能组出词」毫无关系。`lai/lei/li/si`（错 1 键）先占 4 个名额，
  剩下 2 个给了 id 比 `lao` 小的 `kai/kei`，`lao` 被整条丢掉。
- **修法**：限流改成分档：总上限仍是 6（保证音节图规模不变、组句耗时不变），
  但**每个误按距离档各留 4 条**（`kMaxCorrectionsPerDistance`）。错 1 键的照旧先出，
  错 2 键的不再被 id 挤光。另外同一音节在同一距离档只保留一条跨度（去重，
  「多跨度」只需要每个音节留最长/偏差最小的那条）。
- **实测**：`lsishi` 的 老师 从「第 91 的单字母预测词」变成满覆盖纠错候选（第 3）；
  `hoafe` 的 好的 第 1；26 键语料 319 不变、九键 318 不变；误按语料见 C15。
- **边界**：不要把总上限也放大（6 → 8 实测 17 键输入 +50ms，误按语料 top5 还掉 3 条）；
  产物里 `CORR_MAX_SYLLABLES` 仍可覆盖总上限，便于 A/B。

---

## 5. 回归（改完必跑）

> **跑之前先把 `user` 目录还原成干净状态**（只留 `build/` + `installation.yaml`）。
> `/data/local/tmp/rime-q/user` 是持久化的，历史会话攒下的 `pinyin.userdb` 会让
> `zhonguo`（插入型纠错）/`niha`（补全）这类**靠输入末尾生成**的候选整类消失 ——
> 现象看着像代码坏了，其实是测试台脏了。A/B 对比时两侧都要从同一份快照恢复。

### 5.1 合法输入语料（防回归）

320 条合法拼音，输出应与改动前**逐条一致**（除目标 case）：

```powershell
# 需要 docs/probe/run_corpus.ps1 与 corpus_pinyin.tsv（本地资料）
pwsh -File docs\probe\run_corpus.ps1 -Kind pinyin -Out after.txt
python docs\probe\diff_runs.py base.txt after.txt pinyin
```

没有基线时，先**在改动前**跑一次并保存为 `base.txt`（九键同理 `base_t9.txt`）。
`docs/probe/` 现有的 `base_pinyin.txt` / `base_t9.txt` 已按 2026-11-14（C12 之前）的代码刷新，
可直接当基线；C12 那次改动前后的原始产物另存为 `base_pinyin_before.txt` / `base_t9_before.txt`
与 `after_pinyin_ht2.txt` / `after_t9_ht2.txt`（两侧逐条一致）。
`before_c6_*.txt` 是 C6 修复前的同批输出（对照用）；更早的旧引擎产物（preedit 切分都不同）已不可比，勿再用。

### 5.2 单键误按语料（防「纠错变吵」）

把每个输入最后一个字母换成邻键，统计期望词召回：

```powershell
cd ime-native\native\scripts
python gen-typo-corpus.py                     # 生成 in-typo.txt + .expect
& $adb push in-typo.txt /data/local/tmp/rime-q/in_typo.txt
& $adb shell "cd /data/local/tmp/rime-q && LD_LIBRARY_PATH=. ./query_console20 share user < in_typo.txt > out_typo.txt 2>/dev/null"
& $adb pull /data/local/tmp/rime-q/out_typo.txt out-typo.txt
python eval-typo-recall.py out-typo.txt in-typo.txt.expect
```

> `query_console20` 是本地探针脚本（`docs/probe/run_corpus.ps1`、旧版 console 源码）里写死的
> 名字，上限固定 20 条。仓库内的 `query-console.cc` 已把条数改成参数，按 3.1 编成 `qc_check` 后
> 命令写成 `./qc_check share user 20` 即可，两者输出格式一致，`eval-typo-recall.py` 都能吃。

**基线**：改 C6 之前 `cases=319 top1=11 top5=148 top10=165 missing=139`；
改 C6 之后 `top1=20 top5=147 top10=165 missing=139`（top1 +9，top5 掉 1 条）；
改 C7 之后 `top1=19 top5=154 top10=172 missing=132`（只修合并不给纠错补回时是
19/145/163/141，可见「补回纠错边」是加分项；**不要为了 top1 去调 `correction_limit()`**，
实测放到 6 反而更差：19/144/163/140）；
改 C8 之后 **`top1=33 top5=154 top10=173 missing=132`**（cover 打平时整词优先于组句
带来的收益，仍是 26 键合法语料 0 变坏、九键 0 变化）；
改 C9 之后 **`top1=33 top5=154 top10=172 missing=132`**（简拼串放开桶内词频的代价，
只有 `fufeo` 一条从 10 掉到 12）。
**2026-11-14 现状**（C6–C9 之后的几笔排序/限流提交把整体拉高过一轮，旧数字不要当闸门用）：
C12 之前 **`top1=124 top5=208 top10=218 missing=93`**，C12 之后
**`top1=125 top5=211 top10=220 missing=89`** —— 当前闸门：top1 ≥ 120、top5 ≥ 205、top10 ≥ 215。
C15–C17（2026-11-19，多音节纠错）之后 **`top1=121 top5=211 top10=219 missing=89`**：
top5/top10/missing 基本持平，top1 −4（15 条名次变差 / 14 条变好，变好的多是
「MISS → 第 2」的长输入，变差的都是尾键 f/b/h 的手误 + 罕见词）。闸门不变。

### 5.3 九键语料

九键不应该被 26 键的改动波及（`has_corrections_` 为 false 时整套逻辑空转）：

```powershell
pwsh -File docs\probe\run_corpus.ps1 -Kind t9 -Out after_t9.txt
python docs\probe\diff_runs.py base_t9.txt after_t9.txt t9
```

**基线**：318 条 0 变化。

### 5.4 定向 smoke（改排序后必看）

```powershell
& $adb shell "cd /data/local/tmp/rime-q && printf '#schema pinyin\nyulejia\nbeng\nbang\nlin\nlsi\nbsi\njifang\nxi\nzhonguo\nzhiyiu\nshiyiu\nniyiu\nyiuxi\nsm\nnh\nzmb\nshyzi\nxianf\nxianh\nke\nnu\nhoafe\nlsishi\nshgyici\n' | LD_LIBRARY_PATH=. ./qc_check share user 12"
```

> **每次都要先发 `#schema pinyin`**：`user/user.yaml` 会记住上一次用的方案
> （`previously_selected_schema`），跑完 5.3 的九键语料后直接开跑，整套 smoke 会在
> `t9_pinyin` 方案下执行 —— 表现是 `lsi`/`sm`/`code` 只剩一条回声（看着像引擎坏了）。

| 输入 | 期望 |
|---|---|
| `yulejia` | 分词 `yu'le'jia`（不是 `yu'le'ji'a`）；面板分段为 yu/le/jia（C8）。首位 娱乐加（原样满覆盖），娱乐机 是改键候选 |
| `beng` / `bang` / `lin` | 崩/帮/林 第一，分词显示为整音节；本格/半个/离你 不出现（C7） |
| `lsi` | 类似/罗斯… 之后出现 来[?]（前 10 内，C9 后是第 5） |
| `bsi` | 被/背/是/白/摆 等纠错词可见（前 13 内，C9 后是第 5） |
| `jifang` | 己方 第一（不能是「你放?」） |
| `zhonguo` | 中国[?] 第一 |
| `zhiyiu` | 只有[?] 第一（旧口径排第 41；C12 后第 5 名起会多一条 之于?，属预期） |
| `shiyiu` / `zhongyiu` | 是有[?] / 中有[?] 第一（C12 后第 5 名起仍是 yiu→you 的纠错，属预期） |
| `niyiu` | 你有[?] 第一（旧口径第 7） |
| `yiuxi` | 游戏[?] 第一（不变） |
| `sm` / `nh` / `zmb` | 什么 / 你会 / 怎么办 第一（纯简拼串，顺序不应被 C9 改动；`nh` 的 你会 是 C12–C14 之后的现状） |
| `shyzi` | 数字[?] 第一，之后 是以?/收益?/所以?/树叶子/十一子（C9；C15 之后桶内按「权重 − 长度偏差」排） |
| `hoafe` | 好的[?] 第一（本意 好的 = hao'de：a/o 换位、f→d），其后才是 差的[?]（C15） |
| `lsishi` | 历史[?]/开始?/老师? 在前（临死时 退到第 6 之后；老师 原第 91，C15 + C17） |
| `shgyici` | 是一次?/上一次?/试一次? 在前（上一次 原图里根本没有，C16） |
| `xianf` / `xianh` | 尾键 f/h 是 g 的邻键：先锋 之后紧跟 想[?]（第 2），再往后 宪法/像?/先放/向?（C12） |
| `ke` | 首键 k 的邻键 l：可/课/克 之后紧跟 了[?]（第 4），再往后 科/氪/而?/颗（C13） |
| `code` / `hello` / `music` | 英文词本身第一（**且没有被当回声删掉**），其后才是 `ci'de?` 这类纠错（C10） |
| `name` / `he` / `an` / `ni` / `me` | 原样拼音仍然优先：那么 / 和 / 按 / 你 / 么（C10、C11） |
| `ok` / `gpu` / `cpu` / `usb` / `url` / `pdf` / `api` / `tv` / `pc` / `ui` | 该缩写词第一（`Graphics Processing Unit` 这类全称不再派生小写码后，`gpu` 仍是 `GPU` 第一，见 C14）|
| `nu` | 怒/弩 等汉字第一，英文词条 `nu` 与希腊字母 `ν` 不抢首位（C14） |

> 英文/希腊字母那几行（`code` / `ok` / `gpu` / `nu` …）在 console 测试台上**验不了**：
> `share/build` 里只有 `pinyin` / `t9_pinyin` 的 prism+table，没有 `english.table.bin`，
> 英文词条根本不会被查出来（现象是 `code` 第一位是 `词的?`）。这些行要在装了 APK 的设备上看。

---

## 6. 构建与生效

改完 `ime-native/native/scripts/rime-*-whale.cc` 后：

```powershell
cd ime-native\native
pwsh -File scripts\build-deployer.ps1          # 快速验证用：x86_64 共享库 + rime_deployer
# 产物：native-build\build\x86_64\librime-hosttools\lib\librime.so
& $adb push native-build\build\x86_64\librime-hosttools\lib\librime.so /data/local/tmp/rime-q/librime.so

pwsh -File scripts\build-engine.ps1 -Abi arm64-v8a   # 出货：静态库 + libwhaleime.so
pwsh -File scripts\build-engine.ps1 -Abi x86_64
```

根目录的 `scripts\build-engine.ps1` 只是转发到上面这份（默认两个 ABI 一起编），两者等价；
产物直接覆盖 `ime-native/src/main/jniLibs/<abi>/libwhaleime.so`，Gradle 侧不需要任何配置。

**踩坑**：`Copy-Item` 会保留源文件 mtime，补丁文件比上次的目标旧时 ninja 判定「无需重编」，
改了等于白改。`apply-rime-patches.ps1` 已统一刷新时间戳；手工同步时记得 `touch`。

**踩坑 2**：改到**头文件**（如 `rime-spelling-whale.h` → `src/rime/algo/spelling.h`）时，
编译期 include 路径里 `native-build/install/<abi>/include` 排在源码树之前，只覆盖源码树会被
`install/` 里的旧副本吃掉（报 `no member named 'correction_distance'`）。`build-deps.ps1` 会
重装头文件；`build-deployer.ps1` 已带 `-InstallDir` 一并覆盖。

打包 APK 之后才能验证 UI 侧行为：`scripts/deploy-apk.ps1`。

---

## 7. Kotlin 侧相关约定（排查 UI 现象时）

- **组合串为准**：判断「这次输入结束了没有」看 `RimeEngine.compositionText` 是否为空，
  **不要**看候选是否为空 —— 上屏后联想词会让候选非空。
- **联想只属于候选栏**：`KeyDispatcher.onUpdateSelection` 里若 `compositionText` 非空则**不联想**
  （否则会把分段/剩余段的候选覆盖掉），仅在输入真正结束后联想，并把候选页收回键盘。
  `ImeService.onUpdateSelection` 只负责把回调转发进去。
- **分段选择**：`b'si` 里 `b` 是简拼段，整串组合下不会产出段内单字，
  必须先 `KeyDispatcher.focusSegment(segments, index)` 收窄输入再取候选；分段下标与总段数
  **由 UI 传入**，不要从 preedit 反推（聚焦后组合串只剩一段，反推会得到错误段数）。
  引擎侧的分段态是 `RimeEngine.isSegmentFocused` / `focusedSegmentsSnapshot`：
  候选页收起再打开时 UI 的本地快照已随组合丢失，只能用后者恢复。
- **分组上屏（重构后已下沉到 KeyDispatcher）**：已确认的段存在
  `KeyDispatcher.pickedSegmentTexts`（段下标 → 字），最终串在
  `pickSegmentCandidate(candId, segmentIndex, total)` 里就地按段序拼好，
  一份存进 `DictDecoder.pickedSegmentText`（候选栏回显「已选 + 当前段」）后交给
  `RimeEngine.pickSegmentCandidate`，全部选完才一次性 `commitText`。
  `CandidatesPanel` 只负责显示与置灰（`pickedSegments`），**不要**再把「每段攒字并拼接」
  的活搬回 UI：中途的 `updateCandidate()/resetToIdleState()` 会清掉面板本地状态，
  上一版就是这么把「布丝」拼成空串的。
- **UI 会改顺序的两处**（引擎排好了也可能被 UI 推翻，排查「引擎里明明第一」必看）：
  1. `arrangeForDisplay`：中文模式下纯字母英文词条整体降到 `TIER_ENGLISH`。
     例外＝「整串原样命中的英文词条」（`text == Rime.getInput()`）**且整串没有被中文
     完整拼写解释**（没有 comment 等于输入的非纠错汉字候选），它是用户在打这个词，
     保持引擎位置（见 C10；后半条判据见 C14，`nu` / `ok` 就是它加上去的）。
  2. `findEchoIndex` / `shouldDropEcho`：回声候选（`echo_translator`，quality −100，
     恒在列表末尾）会从候选里剔掉；判据必须是「整份列表的最后一条」，否则会把
     与编码同文本的真实词条（`code`）当回声删掉，现象是候选栏里根本没有这条词。

---

## 8. 提交前检查单

- [ ] 改动只落在 `ime-native/native/scripts/rime-*-whale.{cc,h}`（不是 `native-build/src/` 副本）
- [ ] 5.1 合法语料期望词名次无变化（除目标 case）
- [ ] 5.2 误按语料不低于当前基线（C15–C17 后：top1 ≥ 120 / top5 ≥ 205 / top10 ≥ 215 / missing ≤ 93；
      当前 121/211/219/89）
- [ ] 5.3 九键无变化
- [ ] 5.4 定向 smoke 全部符合期望
- [ ] 动过音节图（纠错边数量、顶点裁剪）时，用 `@` 逐键模式量一遍长输入的耗时
      （C16 记录：17 键 130ms 量级、最慢一键 25ms 量级，明显更慢要先看组句）。
- [ ] `build-engine.ps1` 重编了 arm64-v8a 与 x86_64，
      `ime-native/src/main/jniLibs/<abi>/libwhaleime.so` 一并提交
- [ ] `ime-native/native/scripts/apply-rime-patches.ps1` 里的补丁清单注释同步更新
- [ ] 动过 `english` 词表派生（`precompile-dict.ps1`）或 `assets/rime` 时，
      `DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM` 已递增（否则已装设备不会重拷词库）
