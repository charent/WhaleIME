# 原生层构建说明（librime 1.17.0）

联想词、九宫格点选等原先在作者私有 fork 里的能力，这里全部用上游 1.17 的公开接口重建：

- **librime 源码树改动由 `apply-rime-patches.ps1` 统一覆盖**（源码树本身 gitignore，每次 fetch 重建）：
  - `src/rime/no_logging.h` → Android 版 logcat 实现（`scripts/rime-no-logging-android.h`，只动日志宏）；
  - `src/rime/dict/corrector.cc` → `keyboard_map` 补全上下排斜向邻键 + 四操作编辑
    自动机（`scripts/rime-corrector-whale.cc`，见 `docs/pinyin-correction-refactor-plan.md` P1
    与「插入型纠错」节）：替换（邻键，代价 1）/ 插入（漏打字母，代价 1，含输入末尾）/
    删除（多打字母，代价 1）/ 换位（相邻颠倒，代价 2），代价按误按次数计，
    纯替换模型覆盖不了的 "zhonguo→中国"、"niha→你好"、"shng→生" 由此可达；
    插入允许**一段连写漏字**（最多 2 个、必须同位置连写），"shg→shang" 这类少打两个
    字母的音节才走得到 —— 散开补字仍禁止（26×26 分支会让组句路径爆炸）；
    另有「拼写长度 / 按键数」比例闸门：3 个按键被 1~2 个字母的音节吃掉（hoa→a）、
    或被补成 7 个字母的罗马字（hoa→asakawa）都不算误按；
  - `src/rime/dict/corrector.h` → `Corrections` 的键由「音节」改成「音节 + 消耗跨度」
    （`scripts/rime-corrector-whale.h`）：同一音节在同一处可能有多条跨度的解释
    （hoafe 的 hao：吃 2 字节错 1 键 / 吃 3 字节错 2 键），只按音节去重会把长跨度那条
    丢掉，**需要该音节覆盖更多按键的多音节纠错**（好的 = hao'de）因此组不出来；
  - `src/rime/algo/syllabifier.cc` → 纠错五连修（`scripts/rime-syllabifier-whale.cc`）：
    容错阈值 5→2（上游允许整段输入 5 键替换，"ndj"→"mei" 也能匹配）；纠错惩罚按编辑
    距离缩放（上游固定 log 0.01，错 3 键与错 1 键同价）；补全改从精确匹配前沿生长
    （上游 farthest 被纠错边推到输入末尾后补全被整体跳过，"nih" 出不来"你好"）；同一
    段上同一音节的拼写属性改为**整条取更优**（非纠错 > 纠错，其次 type 小、credibility
    高）。上游合并只 `type = min(type)`、credibility 与 `is_correction` 仍留着先插入那
    条，纠错边因此被简拼边洗成「精确边」，既躲过「整串能被精确音节切完就裁掉简拼边」
    的裁剪，又被当成原样解释参与桶内词频竞争 —— beng → 本格（ben+g）、bang → 半个、
    lin → 离你 就是这么跑到 崩/帮/林 前面的；撞车时输掉的那份纠错解释不丢弃，裁剪之后
    以纠错边补回图上（简拼仍作主解释，纯简拼输入不受影响）；
    **第五修是性能**：每段（同 consumed 长度）最多 6 条纠错音节进图。纠错音节的
    数量不节制时，组句（`Poet::MakeSentence` 的 BeamSearch：7 条候选行 × 每条出边 ×
    每条边的词条，逐项调 `Grammar::Evaluate`）会随按键数平方增长 —— 实测
    `yiuxiwanjia` 第 11 键 213 ms、11 键累计 1.23 s，而组音节图本身（含纠错 BFS）只占
    1 ms。限流后降到 12 ms/键、累计 61 ms，误按语料召回还略有提升（top1 132→142、
    top5 197→210），因为被丢掉的都是低分纠错音节，它们本来只会在组句里制造垃圾。
    `CORR_MAX_SYLLABLES` 可覆盖 6 这个默认值，`CORR_STATS=1` 让引擎把每张图的规模与
    分段耗时打到 stderr（`scripts/query-console.cc` 的 `@`/`~` 模式配合使用）。
    **限流的截断规则本身会决定哪些纠错能被组出来**：上限按「误按距离档」分（每档 4 条），
    否则距离档内按音节 id 截断会把 lao（lsishi → 老师）挤掉；同一音节在同一距离档只
    保留一条跨度。另外，纠错边的终点顶点不参与「按拼写类型裁剪」的顶点清理 ——
    否则纠错只能发生在最后一个音节（shgyici → 上一次 要求第一个音节就是纠错）；
  - `src/rime/gear/script_translator.cc` → 五部分（`scripts/rime-script-translator-whale.cc`，
    背景与实测见 `docs/pinyin-candidate-ranking.md`）：
    1. 纠错候选 comment 末尾追加 `"?"`，Kotlin 候选栏据此渲染右上角角标；
    2. 候选排序主键由「覆盖到的输入长度」换成「可信覆盖」`cover = 解释到的字节数 −
       误按键数`，次键覆盖位置、末键概率。上游按覆盖分桶 + 桶内比词频，而纠错路径
       带来的覆盖同样成立，四个音节全改的「呵护宝宝」会无条件压在精确的「这不好」
       之前；换主键后一次误按只扣 1 分（不是按被改写的字节数打折），句子与整词在
       同一把尺子上竞争（原先"整段已有纠错词覆盖就跳过组句"的布尔开关随之删除）。
       扣分口径必须按**误按次数**：yiu→you 只错一个键，却会把整个 3 字节音节记成
       纠错，按字节打折会让「zhi+you」输给只解释 5 字节的「zhi+yi」（zhiyiu 出不来
       只有，见 `docs/pinyin-candidate-ranking.md` 末节）。
       注意：**不要再把「词数少优先」加回主键** —— 九键里同一串按键能切出音节数相同、
       词数不同的候选（94328426 → yi'da'tiao 与 zhe'bu'hao），按词数排会让凑巧成词的
       长词无条件压过常用字词组合。唯一例外是下面第 4 条的「cover 打平、组句 vs 整词」；
    3. 同一个 `end_pos` 桶内「原样敲的键」优先（2026-11-07）：cover 只在桶与桶之间
       生效，桶内次序仍由词典按「词条权重 + 一次 −log(0.01)」给出，于是单音节输入
       （beng / xi）里「ben / geng / neng 改一键」靠词频（本 10.4 / 更 11.7 / 能 12.4）
       就压过了「beng 原样」（崩 7.7）；jifang 里 你放（ji→ni）也压过 己方 / 机房。
       修法**只调整桶内次序**：整段输入能被精确音节切完时（`exact_full_coverage_`），
       桶里「原样解释」的词条先吐，改键词条扣到 `Slot::deferred`、等本桶精确词条
       出完再放（`TakeExactEntry`）；跨桶比较仍只看 cover/end_pos/score。
       判定要求 `MeasureExactBytes` 的 probe **成功**才算原样 —— 编码比输入长的联想词
       probe 走不到终点，退回「整段算原样」会把「做你该做的事」这类简拼凑满整串的
       长词顶到桶头。改键候选另外标 `type = "correction"`，避免被
       `ContextualTranslation` 的同 `end_pos` 重排重新洗回精确候选前面；
    4. 组句候选与整词候选 **cover 打平**时整词优先（2026-11-08，yulejia →
       与科技啊 压在 娱乐机 前面）：组句是「多个词的猜测」，整词是一条词条。
       只在 cover 相等时生效（跨 cover 仍按 cover，所以 呵护宝宝 7 < 这不好 9，
       zhebuhaoba 的 这不好吧 cover 11 照样第一），且九键（`segmentation_prior`
       开启的方案）不启用 —— 那里同一串按键能切出不同音节，词数不可比。
       实测：26 键合法语料 4 条变好 0 条变坏，九键 0 变化，单键误按语料
       top1 19→33、top5 154 持平、top10 172→173；
    5. `ScriptSyllabifier::GetPreeditString`：**分词（preedit）按用户敲的键自己的精确
       切分呈现**，不再按候选的编码做 DFS。上游用候选编码打印输入字节，纠错候选
       （与科技啊 = yu'ke'ji'a）于是显示成 yu'le'ji'a 这种用户没敲过的切分；Kotlin 侧
       拿 preedit 当分段依据（`RimeEngine.segments()` → 候选面板分段选择、
       `focusSegment` 会 `setinput()` 那个音节），于是「jia」这段只在纠错读音里存在，
       聚焦后只剩纠错限流放行的零星单字。原样候选的编码本来就对应精确切分，不受影响；
       纠错仍由候选的 `?` 角标与 comment（候选真实读音）表达；
       - `src/rime/algo/spelling.h` → `SpellingProperties` 增加运行时字段 `correction_distance`
    （`scripts/rime-spelling-whale.h`）：纠错边的误按键数随音节图带出，供上面 cover 的扣分
    使用。只是加字段、不改 prism 序列化，词库产物无需重建；
  - 简拼串（`abbrev_input_`：整段只能靠缩写边切完，完整拼写覆盖不到一半按键）的满覆盖
    桶另有整桶重排 `RerankAbbrevSlot`（2026-11-19）：词条权重 − 1.5 × 拼写长度偏差，
    并撤掉该桶的「原样优先」。因为这类输入里「原样」条目本身只是缩写猜测
    （lsishi 的 临死时 = l|si|shi、shgyici 的 试过一次 = sh|g|yi|ci），拿「0 误按」
    整批压过改过一两键的高频真词（老师、上一次、好的）没有道理。实测 26 键合法语料
    319 条只有 1 条变好、0 条变坏，九键 0 变化；详细实测见
    `docs/pinyin-correction-debugging.md` 的 C15；
  - `src/rime/dict/dict_compiler.cc` → 部署链修复（`scripts/rime-dict-compiler-whale.cc`）：
    本项目的 assets 只有预编译 `*.table.bin` / `*.prism.bin`（没有 `*.dict.yaml` 词库源），
    上游在这里有两处致命行为 ——
    1. reverse.bin 缺失/过期就无条件置 `rebuild_table`，于是 `BuildTable` 拿空词表跑一遍，
       在部署目标目录写出**空表**（实测 62MB → 3.5KB）；运行时用户目录优先，空表盖掉共享
       目录的预编译表，现象是「拼音串正常显示、候选一个都没有」（2026-11-14 实测）。
       改为：没有词库源时不重建词表，沿用预编译 table；
    2. 只在词典带 `import_tables`（`packs_` 非空）时才从旧表读音节表，单表词典取不到音节表，
       `BuildPrism` 直接失败 → `pinyin.custom.yaml` 里的 `speller/algebra`（模糊音/纠错）
       永远落不了地。改为：无条件从 `primary_table` 的 syllabary chunk 兜底。
    修好后设备端 `Rime.redeploy()` 只重建 prism（词表不动），模糊音/纠错开关才真正生效；
    reverse db 依旧不重建（无源可依，反查功能本来也没发布）。
  - `plugins/librime-octagram` → 八股文语法模型插件（源码由 `fetch-sources.ps1` 下载 master 快照，
    merged 进 librime 后模块自动进 `kDefaultModules`；同时产出 build_grammar 工具，见 P3）。

> 改到头文件（如 `spelling.h`）时注意：编译期 include 路径里 `native-build/install/<abi>/include`
> 排在源码树之前，`install/` 里的副本不同步会把改动吃掉（build-deps 会重装，build-deployer 已
> 带 `-InstallDir` 一并覆盖）。
- **JNI 桥只依赖公开 C API**：`whale_jni.cpp` 只 include `rime_api.h`，不引用任何内部 C++ 类。
- **唯一仍绑内部 C++ 的地方**：`associate_rear.cpp`（联想词，上游没有 predict 接口），
  文件头列了升级时要复核的三处签名。

移植细节见 `docs/engine-upgrade-implementation.md`，方案取舍与迁移记录见
`docs/engine-stock-vs-custom.md`。

## 目录

```
cpp/
├── CMakeLists.txt          JNI 层构建（只有一个目标：libwhaleime.so）
├── src/
│   ├── whale_jni.cpp       16 个 JNI 方法，全部直通 rime_api.h
│   ├── associate_rear.cpp  联想词（Table + StringTable::Predict）——唯一内部依赖
│   ├── associate_rear.h
│   └── utf8.h              UTF-8 ↔ jstring（规避 NewStringUTF 的 4 字节坑）
├── scripts/
│   ├── fetch-sources.ps1      下载 6 个源码包
│   ├── parallel-download.ps1  HTTP Range 多线程下载
│   ├── build-deps.ps1         交叉编译依赖 + librime → prebuilt/<abi>
│   ├── build-engine.ps1       依赖 + JNI → libs/<abi>/libwhaleime.so
│   ├── rime-no-logging-android.h  补丁：覆盖到 librime 源码树
│   ├── rime-*-whale.cc        其余补丁文件（corrector / syllabifier / script_translator）
│   ├── rime-spelling-whale.h  补丁：SpellingProperties 增加 correction_distance
│   ├── rime-corrector-whale.h 补丁：Corrections 以「音节 + 消耗跨度」为键
│   ├── gen-t9-algebra.py      生成 t9_pinyin.schema.yaml 的九键键码 algebra
│   │                          （音节表变动后重跑；详见下方「九键方案」）
│   └── boost-regex/           boost 1.86 没有 CMake 工程，这里单独编 libboost_regex.a
├── prebuilt/<abi>/         依赖静态库与头文件（gitignore，由脚本生成）
└── native-build/           源码、构建目录、安装目录（gitignore）
```

## JNI 方法与 Kotlin 的分工

`whale_jni.cpp` 里只有两类东西：结构体编组（`RimeContext` / `RimeStatus` / 候选数组）
和 `rime_get_api()` 的直通。所有能表达成普通字符串/数组操作的逻辑都在 Kotlin：

| 能力 | Kotlin 位置 | 用到的公开 API |
|---|---|---|
| 组合串局部替换（九宫格点选 / 拼音点选） | `Rime.replaceKey` | `get_input` + `set_input` |
| 键名 → 键码 | `RimeKeycode` 常量 | —（X11 keysym） |
| 候选分页 / 全量列表 | `Rime.getCandidates` + `RimeEngine.appendNextPage` | `candidate_list_from_index` / `candidate_list_next` |

候选下标统一是**全局下标**（`candidate_list_*` 的顺序），可以直接喂给 `select_candidate`，
不再有「页内偏移」与「全局下标」两套语义。

## 一键构建

```powershell
# 首次：下载源码（boost 141 MB，走多线程）
pwsh -File scripts\fetch-sources.ps1

# 默认一次编两个 64 位 ABI（固定顺序 x86_64 → arm64-v8a），覆盖到 ime-native/src/main/jniLibs/<abi>/
pwsh -File scripts\build-engine.ps1

# 只编其中一个（32 位已移除，传 armeabi-v7a / x86 会直接报错）
pwsh -File scripts\build-engine.ps1 -Abi x86_64
```

`build-engine.ps1` 默认会先跑 `build-deps.ps1`；依赖已编好时加 `-SkipDeps`
（只改 `cpp/src/**` 时用这个，几秒钟出结果）。

模拟器验证流程：先编 `x86_64`（原生加载，不经 ARM 转译）装到模拟器上跑通，
同一次编译产出的 `arm64-v8a` 即可直接上真机，不必再单独编译与验证。

## 九键方案（t9_pinyin）的两处硬约束

九键键盘送进引擎的不是拼音，而是按键代表字母（94328426 → `WGDATGAM`），
而 prism 里存的是音节拼写。两者能对上，全靠 `assets/rime/t9_pinyin.schema.yaml`
的 `speller/algebra`：

- **必须用 `derive`（派生一条新的、保留原拼写），不能用 `xlit`（就地改写）**。
  `xlit` 是双拼的用法，改完只剩键码拼写；九键还要留全拼 —— 候选栏点选具体读音后，
  客户端会用 `Rime.replaceKey()` 把键码段换成小写拼音（`zhe'bu`），prism 里没有全拼
  就查不到音节图，点完拼音直接没候选。
- 单键/前缀靠 `abbrev`（只匹配大写键码），敲 9 才看得到 w/x/y/z 开头的字。
- 规则由 `scripts/gen-t9-algebra.py` 从 `build/pinyin.prism.bin` 的 414 个音节生成
  （221 条 derive 覆盖 221 个键码串）。**词库音节表变动后必须重跑**
  `python scripts/gen-t9-algebra.py --write`，否则新音节没有键码拼写。

`assets/rime/build/t9_pinyin.prism.bin` 必须与当前 `pinyin.table.bin` 同源重建
（prism 头里的 dict checksum 要对得上）。项目里原先那份是 1.11.2 时代遗留产物，
checksum 与当前词库不符：平时能跑，但只要触发一次部署（改版本号/换词库），
librime 就会按 schema 重建 prism —— 而缺少 algebra 的历史 schema 重建出来的只有全拼拼写，
九键输入大写键码**一个候选都不会有**（只剩 echo）。这就是 2026-10-26 那次修复的对象。

另外九键必须开 `grammar: language: zh-hans` + `translator/contextual_suggestions`：
同一串按键能切出完全不同的音节（`94328426` → yi'da'tiao / zhe'bu'hao / xie'bu'hao），
覆盖长度又都是满的，没有 n-gram 时只能比「逐词词频之和」，凑巧成词的生僻长词会压过
常用字词组合。

## 依赖版本与关键参数

| 依赖 | 版本 | 说明 |
|---|---|---|
| librime | 1.17.0 | `BUILD_SHARED_LIBS=OFF` → `librime.a`；`ENABLE_LOGGING=OFF` 免 glog |
| marisa-trie | 0.3.1 | CMake 工程，`ENABLE_TOOLS=OFF` |
| yaml-cpp | 0.8.0 | 静态 |
| leveldb | 1.23 | 静态，关测试；snappy/crc32c 自动探测为「无」 |
| OpenCC | 1.1.9 | `USE_SYSTEM_MARISA=ON` 复用 0.3.1，避免重复符号 |
| boost | 1.86.0 | 只有 `boost::regex` 需要编译，其余 header-only |

踩过的坑（都已在脚本里处理）：

1. **boost 1.86 的 tarball 没有 CMakeLists**（官方 CMake 工程自 1.87 才有），
   改为用 `scripts/boost-regex` 直接编 `libs/regex/src/*.cpp`。
2. **CMake 3.31 已移除 FindBoost**，`find_package(Boost)` 只会失败；
   librime 里该调用非 REQUIRED，靠 `-I<boost>` + 最终链接 `libboost_regex.a` 兜底。
3. **librime 的 `FindYamlCpp` 用两次 `find_path` 判断 0.5 新 API**，
   交叉编译时必须把 `YamlCpp_LIBRARY` / `YamlCpp_INCLUDE_PATH` / `YamlCpp_NEW_API` 全部预设。
4. **OpenCC 的头是 `.hpp`**（不是 `.h`），且 `opencc_config.h` 在 build 目录里生成。
5. **`rime/key_event.h` 依赖 `<X11/keysym.h>`**，Android 没有；JNI 层只 include `rime_api.h`，
   不会碰到它（键码现在由 Kotlin 的 `RimeKeycode` 提供）。
6. **`$Args` 是 PowerShell 自动变量**，函数参数用这个名字会静默丢参数（早期构建全踩过）。
7. **`rime::make` 在 1.17 不存在**，构造 shared_ptr 用 `rime::New<T>`；
   `rime::Table` 的构造函数收 `rime::path` 而非 `std::string`。
8. **UTF-8 ↔ jstring 一律走 `utf8.h`**：`NewStringUTF` 期望 Modified UTF-8，
   4 字节序列（emoji、CJK 扩展 B）会截断。

## 与 Gradle 的关系

不接 `externalNativeBuild`：`libs/` 下同时存在手写等第三方 `.so`，
两条路径都会产出 `libwhaleime.so`，打包时冲突。
`build-engine.ps1` 直接把产物覆盖到 `libs/<abi>/`，Gradle 侧无需改动。
