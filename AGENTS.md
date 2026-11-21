# AGENTS.md

面向在本仓库工作的编码智能体。面向用户的介绍见 `README.md`，原生引擎的构建细节见
`ime-native/README.md` 与 `ime-native/native/README.md`。

## 项目速览

- 小鲸鱼输入法（Whale IME）：完全离线的安卓中文输入法，Rime（librime 1.17.0）引擎 + Kotlin +
  全量 Jetpack Compose UI。
- 5 个 Gradle 模块：`app` / `ime-ui` / `ime-core` / `ime-vendor` / `ime-native`。克隆即可构建，无 git 子模块。
- `libwhaleime.so` 不是黑盒：源码在 `ime-native/native`（librime + JNI 桥 + 自研插件），可用
  `scripts/build-engine.ps1` 完整重建。
- 没有单元/仪器测试（`src/test`、`src/androidTest` 均不存在）。验证方式只有一个：构建后装到真机或模拟器实际操作。

## 构建与运行

```sh
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # R8 压缩 + release 签名
./gradlew :app:installDebug
./gradlew clean
```

```powershell
pwsh -File scripts/deploy-apk.ps1                  # 编最新 debug 并安装到已连接设备；无设备时自动拉起模拟器
pwsh -File scripts/start-emulator.ps1 -InstallApk -EnableIme   # 后台启动模拟器 + 装包 + 设为默认输入法
pwsh -File scripts/build-release.ps1 -Clean -Install           # 打包校验一体化（签名检查、apksigner 验签）
pwsh -File scripts/build-engine.ps1 -SkipDeps      # 只重编 JNI 层，约 20 秒/ABI
pwsh -File scripts/precompile-dict.ps1             # 在模拟器上预编译 Rime 词库 bin
pwsh -File scripts/verify-emoji.ps1               # 校验表情/颜文字数据与随包字体的字形覆盖
```

要点：

- 构建基线只在根 `build.gradle` 的 `ext` 里定义：`whaleCompileSdk`(36) / `whaleMinSdk`(33) /
  `whaleJavaVersion`(21) / `whaleJvmTarget`，另有 `whaleGitHead` / `whaleBuildTime` 闭包（供设置页「关于」）。
  模块里只引用变量，不要再硬编码 `compileSdk` / `minSdk` / Java 版本。
- 没有 product flavor，只有 `debug` / `release` 两种构建类型，任务名就是 `assembleDebug` / `assembleRelease`。
- `versionCode` / `versionName` 由打包时刻生成，不要手改：`versionCode` = 2026-01-01T00:00(GMT+8) 起的分钟数，
  `versionName` = `yyMMdd.HHmm`，APK 文件名 `whaleIme_<versionName>_<buildType>.apk`（见 `app/build.gradle`）。
- `applicationId` = `com.whale.inputmethod` + `.debug` / `.release`；`targetSdk` app 36、库模块不声明。
- 签名读 `keystore/keystore.properties`（gitignore）或环境变量 `RELEASE_STORE_FILE` /
  `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`。`debug` 也用这份 release 配置，
  **缺 keystore 时 Gradle 不报错、直接产出未签名 APK**——打包前用 `scripts/build-release.ps1`（会先拦截）。
- ABI 只有 64 位：`arm64-v8a`（真机/发布）+ `x86_64`（模拟器原生加载）。32 位（`armeabi-v7a` / `x86`）已移除，
  引擎脚本会拒绝其它 ABI。
- 两条互不干扰的原生构建路径：`scripts/build-engine.ps1` 产出 `libwhaleime.so`（内部转发到
  `ime-native/native/scripts/build-engine.ps1`，直接覆盖 `ime-native/src/main/jniLibs/<abi>/`）；
  Gradle 的 `externalNativeBuild` 只编手写引擎 `ime-native/src/main/cpp/hccr` → `libhccr_jni.so`
  （默认只编 `arm64-v8a`，本地存在 ncnn 的 `x86_64` 预编译库时才加编，所以模拟器上手写入口不可用，
  拼音引擎不受影响）。不要让 `libwhaleime.so` 走 Gradle。
- 工具链：JDK 21、NDK `28.0.13004108`、CMake `3.31.6`、PowerShell 7+（脚本不兼容 Windows PowerShell 5）。
- SDK 路径不入库：本地写在 `local.properties`（模板 `local.properties.example`），或用 `ANDROID_SDK_ROOT` /
  `ANDROID_HOME` 覆盖；统一由 `scripts/lib/android-sdk.ps1` 解析。**仓库内禁止出现盘符绝对路径**，
  `scripts/check-local-paths.ps1`（CI 已挂）会拦截。
- CI：`.github/workflows/build.yml` 在 `v*` tag 上跑 `./gradlew assemble` 并上传 release 资产。

## 模块布局

依赖只允许向下：`app → ime-ui → ime-core → ime-vendor`。

- `app/`（namespace `com.whale`）— 应用外壳：`BaseApplication`（`CrashHandler.init` + `Launcher.initData` +
  `AutoBackupScheduler.reschedule`）、签名/版本/`abiFilters`/`noCompress`，以及 `ImeService`（+ `@xml/method`）、
  `LauncherActivity` 的 manifest 声明。库模块刻意不声明这些组件，宿主接入时必须自己补。
- `ime-native/`（namespace `com.whale.ime.nativepack`）— 载荷模块，没有任何 Kotlin/Java 业务代码：
  `assets/{rime,hccr,pinyindb,fonts}`、`jniLibs/<abi>/libwhaleime.so`、手写 CMake 工程、`native/`（脚本驱动的引擎源码）。
- `ime-core/`（`com.whale.ime.core`，无 Compose、无 Activity）— 运行时与引擎，包按领域划分：
  `boot`（`Launcher`/`CrashHandler`/`rime/{RimeDataInstaller,RimePaths}`，唯一组装点）、
  `config`（`SchemaIds` / `DictDataVersion` / `AppLinks`，纯常量）、
  `preference`（`AppPrefs` + `ManagedPreference*`，`preference/model` 放值类型）、
  `storage`（`WhaleDatabase` + `dao`/`entity`/`migration`）、
  `keyboard`（`model`/`layout`/`builder`/`menu`/`state`/`metrics`）、`dispatch`（`InputDispatcher`/`DictDecoder`）、
  `engine`（+ `engine/schema`）、`rime`（JNI）、`handwriting`（HCCR JNI）、
  `editor`/`theme`/`emoji`/`textstyle`/`userword`/`backup`/`clipboard`/`callback`/`util`。
- `ime-ui/`（`com.whale.ime.ui`，唯一启用 Compose 的模块）— `ui/{activity,setup,theme,service,backup,util}`
  放 Android 组件（`SettingsActivity`/`LauncherActivity`/`SetupActivity`/`CustomThemeActivity`、`ImeService`、
  自动备份 JobService），`compose/**` 放 Compose 键盘与设置界面，外加全部 `res/`。
- `ime-vendor/`（`com.whale.ime.vendor`，无 Compose、无 BuildConfig、零项目依赖）— vendored 源码：
  `pinyin4j`（GPL-2.0-or-later，本地 fork）与 `expression`（EvalEx 转写，Apache-2.0）。见「包约定」。

## 启动流程

`BaseApplication.onCreate` → `Launcher.initData`（`ime-core/.../boot/Launcher.kt`）：

1. 同步：`PinyinHelper.init(context.assets)`（必须最先——拼音表由 `assets/pinyindb` 注入，
   后续 `UserWordManager.load()` 与所有设置页都要用）、`AppPrefs.init`、清理已下线的乱序17 偏好
   （`migrateRemovedLx17InputMode`）、`ThemeManager.init`、预热 Room（查一次侧边符号表）、`ClipboardHelper.init`。
2. 单线程池：`RimeDataInstaller.install()`（把共享词库拷到版本化目录，必要时从旧 `rime/` 迁出用户数据）、
   `InputDispatcher.resetIme()`（启动原生引擎）、`UserWordManager.load()`（用户词库内存索引）、
   `CorrectionSchema.syncFromPrefs()`（纠错/模糊音落成 `pinyin.custom.yaml`）、`WhaleEmojiCompat.init`、
   `EmojiFont.prewarm`（10MB 表情字体，首次解析有几十毫秒）。
3. 自动备份的 `JobScheduler` 重建不属于 core（`AutoBackupJobService` 在 UI 层），由宿主 app 在
   `Launcher.initData` 之后调用 `AutoBackupScheduler.reschedule`。

`AppPrefs.getInstance()` 依赖 `AppPrefs.init`，不要在 `Launcher` 之前使用。深色模式跟随系统由 Compose `values-night` 承担。

## 输入数据流

```
按键（Compose KeyGrid / 物理键 ImeService.onKeyDown）
  → KeyDispatcher.processKey                  ime-ui/compose/KeyDispatcher.kt
  → InputDispatcher                           ime-core/dispatch/InputDispatcher.kt
  → RimeEngine / CustomEngine / HWEngine      ime-core/engine/
  → Rime JNI → libwhaleime.so                 ime-core/rime/Rime.kt
  → DictDecoder.candidateState (StateFlow)    ime-core/dispatch/DictDecoder.kt
  → Compose CandidateBar / CandidatesPanel
  → ImeService.commitText / setComposingText  ime-ui/ui/service/ImeService.kt
```

- `KeyDispatcher` 是 UI 与引擎之间唯一的咽喉点；`InputDispatcher` 是引擎侧唯一入口（按键、删除、选词、
  换方案、联想查询都走它）。
- `DictDecoder` 持有唯一的候选状态：不可变快照 `CandidateState` 的 `StateFlow`，`generation` 区分整表替换与
  增量追加。不要再引入第二个状态通道（历史 LiveData 已删除）。
- `RimeEngine` 承担 Rime 策略：组合串渲染（九键→拼音、双拼补全）、大小写、`📋`/`CUSTOM_TAG` 用户词注入、
  翻页、选词学习（`flushLearning`）、分段选词（`focusSegment` / `pickSegmentCandidate` / `learnComposedWord`）。
- 方案 ID 是 `config/SchemaIds` 里的字符串常量：`t9_pinyin` / `pinyin` / `english` / `stroke` / `handwriting` /
  `double_pinyin_`（前缀，后接小鹤等方案名）。当前方案持久化在 `AppPrefs.internal.pinyinModeRime`，由
  `InputDispatcher.initImeSchema` / `resetIme` 应用。
- 邻键纠错与模糊音由原生插件实现，开关在 `AppPrefs.input`，经 `engine/schema/CorrectionSchema.syncFromPrefs()`
  写成 `pinyin.custom.yaml` 补丁。
- `HWEngine` 驱动开源手写识别（MobileNetV2 → NCNN INT8，GB2312 一级 3755 字，数据在 `assets/hccr`），候选
  汇入同一条 `DictDecoder` 管线；`isAvailable` 是「模型懒加载成功即可用」，失败不缓存
  （模拟器缺 `x86_64` 版 `libhccr_jni.so` 时不可用，`KeyboardManager.switchKeyboard` 会回落到全键）。
- `CustomEngine` 提供非 Rime 候选：用户词注入、中文联想、日期/时间快捷键、数字键盘的表达式计算。
  常用语（`phrase` 表）默认**不**注入候选——那些整句模板只从键盘「常用语」面板上屏，避免内置短句抢 `zm` 这类编码；
  只有用户打开「快捷码上屏常用语」（`InputPrefs.phraseQuickCodeCandidate`，默认关闭）时，编码精确命中
  快捷码才会把整句置顶（索引在 `phrase/PhraseQuickCodeManager`，增删改后需 `reload()`）。

## 键盘 UI（Compose）

- `ImeService.onCreateInputView` 返回缓存的 `ImeComposeRoot`：旋屏、切换输入框都复用同一份组合，隐藏时生命周期只降到
  CREATED（`onFinishInputView`）而非销毁。`onCreateCandidatesView` 返回 `HardwareCandidateView`，仅物理键盘且
  不显示软键盘时使用，与软键盘自带候选栏互斥。
- `ImeController` 持有全部 UI 状态（`ImeUiState`：设置页、符号模式/页码、剪贴板、常用语编辑…），并提供
  `bumpKeyboardRevision()` / `bumpClipboardRevision()` 让 UI 缓存失效。
- `ImeApp` 是键盘渲染入口：候选栏、单手/悬浮布局、工具栏，以及按 `KeyboardType` 切换的容器
  （`KeyGrid` / `CandidatesPanel` / `SymbolPanel` / `ClipboardPanel` / `SettingsMenuPanel` / `HandwritingSession` /
  `PhraseEditorBar` / `T9PrefixColumn`）。
- `KeyboardManager`（`keyboard/state`）不持有 View，只暴露两路 `StateFlow`：`keyboardType`
  （T9/QWERTY/QWERTYABC/NUMBER/SYMBOL/SETTINGS/HANDWRITING/CANDIDATES/ClipBoard/TEXTEDIT/STROKE）与
  `revision`（`clearKeyboard()` 自增，用于丢弃 `KeyboardBuilder` 缓存）。
- `KeyboardBuilder`（`keyboard/builder`）按布局掩码构建并缓存 `SoftKeyboard`/`SoftKey`；布局由
  `InputModeSwitcher` 的 `MASK_SKB_LAYOUT_*` 位掩码选择。
- `Environment`（`keyboard/metrics`，单例）由偏好与屏幕尺寸算出全部像素尺寸（键盘高度、候选区、按键边距、
  悬浮/横屏比例）。
- 改布局、尺寸、比例、单手或主题后的标准失效序列：`Environment.instance.initData()` +
  `KeyboardBuilder.instance.clearKeyboardMap()` + `KeyboardManager.instance.clearKeyboard()`；Compose 根还需要
  `applyPreferredHeight()`（`ImeService.onConfigurationChanged` 与 `SettingsEffects.rebuildKeyboard()` 是两处范例）。
- `ThemeManager` 管理内置/自定义主题并通知监听者，主题变更经 `controller.bumpKeyboardRevision()` 生效。
- 另有 `compose/crop`（自定义主题裁图，替代旧的 vendored cropper）、`KeyboardEditor`、`GestureTuning`、
  `LocalIme` 等支撑文件。

## 偏好与设置界面

偏好是声明式封装，不要直接读 `SharedPreferences`：

- `AppPrefs` 只负责装配与变更广播；各分类各自一个文件：`Internal`（本机运行期状态，不导出）、`Input`、
  `KeyboardSetting`、`Feedback`、`Clipboard`、`Handwriting`、`Voice`、`Other`。**新设置项加到对应分类文件，
  不要写进 `AppPrefs`**；分类类名即备份包里的分组 id，不要改名。
- 每个字段是 `ManagedPreference<T>`，声明时同时登记 `ManagedPreferenceUi`（标题/摘要/`advanced`/`enableUiOn`）。
- `SettingsGroups(providers)`（`compose/settings/SettingsList.kt`）把元数据渲染成控件：≤4 项渲染成分段控件，
  >4 项渲染成下拉，`SeekBarInt/Float` 渲染成行内展开滑杆（松手才写值），`advanced = true` 收进页面底部
  「高级选项」。行的原语只有 `SettingsGroup` / `SettingsGroupHeader` / `SettingRow` / `SettingSwitchRow` /
  `SettingCheckRow` / `SettingNavRow` / `SettingInfoRow` / `SettingSegmentedRow` / `SettingDropdownRow` /
  `SettingSliderRow` / `SettingSliderFloatRow` / `SettingTwinSliderRow`，不要在页面里手搓行布局。
- `SettingsScaffold` 是页面骨架（折叠大标题 + LazyColumn + snackbar + 复位入口），尺寸/间距/字号统一取
  `SettingsDims` 令牌（内容最大宽度 720dp）。
- 取值按行订阅 `rememberPreferenceValue(pref)`；`rememberPreferencesRevision` 只用于重算 `enableUiOn`，不要再拿它驱动取值。
- 副作用集中在 `SettingsEffects.onChanged(key)`（键盘重建、Rime option、双拼方案、纠错补丁、桌面图标）。
  新增会影响键盘或引擎的 key，必须同时加进对应集合，否则会出现「改了设置但键盘没刷新」。
- 设置搜索 `SettingsSearchIndex.build/search` 由同一份元数据生成，新增设置自动可搜（支持拼音首字母）。
- 设置导航是 `SettingsDest` 枚举 + `SettingsNav` 的 Compose `NavHost`（没有 `res/navigation` 图）。route 字符串
  是深链协议的一部分：**只增不改名、不删除**；键盘端跳转走 `AppUtil.launchSettingsToXxx` 的显式 extra。
- `SettingsTheme` 由 `preference/model/SettingsAppearanceMode` + `ThemePrefs.appearanceMode` / `dynamicColor`
  驱动（跟随系统/浅色/深色 + Material You），只作用于设置等文字界面，与键盘主题无关。

## 持久化

- Room 数据库名 `ime_db`，实现在 `ime-core/.../storage/WhaleDatabase.kt`，当前 **schema 版本 1**（唯一基线），
  `exportSchema = false`，`allowMainThreadQueries()`，并启用 `fallbackToDestructiveMigration()`——磁盘版本不匹配
  时直接重建表而不是崩溃（设置项在 SharedPreferences 里不受影响）。
- 实体：`SideSymbol` / `Clipboard` / `UsedSymbol` / `Phrase` / `SkbFun` / `UserWord`，DAO 在 `storage/dao`。
  默认数据在 `onOpen → seedIfEmpty`：侧边栏符号（表空才写）、功能宫格与工具栏（整表为空才写，顺序来源是
  `WhaleDatabase.MENU_ORDER`）、常用语（按 `PHRASE_SEED_VERSION` 幂等补缺，不动用户改过的行）。
- 改表结构：在 `storage/migration/Migrations.kt` 增加 `Migration` → 按序追加到 `ALL` → 同步提升
  `@Database(version = ...)`。`ALL` 目前为空。
- Rime 数据分三处（`boot/rime/RimePaths`）：`rime-v<N>`（随包共享词库的只读副本，按
  `DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM` 隔离）、`rime-user`（userdb、`*.custom.yaml`、部署产物）、
  遗留的 `rime/`（仅升级时迁出用户数据后删除）。
- 剪贴板内容与常用语在 Room；其余行为设置走默认 `SharedPreferences`。

## 用户词库

- `user_word` 表（实体 `UserWord`，`word` + `code`）存用户新增/纠正的词，与 `phrase`（常用语）严格分开：
  常用语只在面板里用，用户词只在编码精确命中时作为置顶候选注入。
- `UserWordManager`（`userword/UserWordManager.kt`）是唯一读写入口，维护两套内存索引——按「当时敲的编码」和按
  「词本身的读音前缀」——因为候选注入在每次按键都会发生，不能查库。写入一律走它，索引才不脏。
  容量与注入上限在常量里：`MAX_WORDS`(20000)、`MAX_INJECT`(3)、`MAX_PER_CODE`(8)。
- 学习入口只有两处：`RimeEngine.flushLearning()`（一次输入结束时结算，选过非首位候选才记）与
  `RimeEngine.learnComposedWord()`（分段选词拼完整串才记，任一段是简拼则不记）。
- 注入复用 `📋`（`RimeEngine.CUSTOM_TAG`）候选通道，所以 `customPhraseSize` 的下标偏移逻辑不要改动。
- 管理界面是 `compose/settings/screens/UserDictScreen.kt`（设置 → 输入法 → 用户词库）；导入导出不单独实现，
  统一走「备份与恢复」的 zip 包（`ime-core/.../backup/UserDataBackup.kt`，用户词库是其中一个分类）；
  `UserWordCodec` 只做编码规范化与长度上限。

## 原生引擎与词库数据

- `Rime.kt` 用 `System.loadLibrary("whaleime")` 加载 `ime-native/src/main/jniLibs/<abi>/libwhaleime.so`。
  重建用 `scripts/build-engine.ps1`（`-FetchSources` 首次拉源码、`-SkipDeps` 只重编 JNI 层），产物直接覆盖
  `jniLibs/`，之后只要常规 Gradle 打包。
- 引擎/词库的演进历史（每次换词库的原因、体积、影响）都记在 `ime-core/.../config/DictDataVersion.kt` 的注释里，
  动 schema / 词库前先读；**改动 `assets/rime` 后必须递增 `DictDataVersion.CURRENT_RIME_DICT_DATA_VERSIOM`**
  （格式 `yyyyMMddHHmm`），否则已安装设备不会重新拷贝。
- `ime-native/src/main/assets/rime/build/` 的 `table/prism bin` **已入库**（26 个文件约 102MB，最大
  `pinyin.table.bin` 约 60MB）。它由 `scripts/precompile-dict.ps1` 生成：把 `rime-frost`（白霜拼音）中文词库
  与英文词库在模拟器上经 `rime_deployer` 编成 bin 再拉回。CI 与干净 clone 都无法重建它（词库源不入库、
  需要模拟器），所以缺少这批文件时打出来的 APK 会在首启时现场编译词典——改动词库后必须重新生成并提交。
  `-WithCells`（默认开）控制是否编入 23 个细胞词库（约 +40MB APK）。
- 英文词典由 `en_dicts/en` + `en_dicts/en_ext` 汇总，并给每条「code 含大写」的词补一条小写码
  （`GPU` 也有 `gpu`），实现检索大小写不敏感而候选文本保持原样。
- 词库源（约 600MB）不入库，默认从本地 `docs/prototype/rime-frost` 读取，可用 `-DictSource` 指定别处。

## 包约定（会被机器检查的硬规则）

- 业务代码只有一个包根 `com.whale.ime`（`app` 是 `com.whale`，只放 `BaseApplication`）。`com.whale.inputmethod.*`
  只出现在 `applicationId` 里，不得再出现在源码包名中。
- 同一个包不得跨 Gradle 模块存在（no split package）；`ime-core` 不得 import `com.whale.ime.ui.*` 或
  `androidx.compose.*`；`ime-core` 的 `util` 包不得依赖领域包；`ime-vendor` 不得 import 任何宿主类型
  （只允许 `com.whale.ime.vendor.*`）。
- 资源 id 按模块划分（`com.whale.ime.core.R` / `com.whale.ime.ui.R`，`android.nonTransitiveRClass` 默认开启）：
  资源必须放在引用它的模块。`ime-core` 的 res 是 `ime-ui` 的子集，`scripts/split-core-res.ps1` 可按引用重算。
- 源码目录不得命名为 `build` / `out` / `dist`（与 AGP 产物同名，且会被 `.gitignore` 静默吞掉）。
- `com.whale.ime.rime`（`Rime` / `Structs` / `RimeKeycode`）的包名与类名就是 `libwhaleime.so` 的 JNI ABI：
  `Java_com_whale_ime_rime_Rime_*` 是入口，`whale_jni.cpp` 还会 `FindClass("com/whale/ime/rime/*")`。
  **不要重命名**；真要改必须同步改 `ime-native/native/src/whale_jni.cpp` 并用
  `scripts/build-engine.ps1 -SkipDeps` 重编两个 ABI（`app/proguard.cfg` 也为此保留了该包）。
- `com.whale.ime.handwriting` 的五个 `Java_com_whale_ime_handwriting_*` 符号声明在
  `ime-native/src/main/cpp/hccr/hccr_jni.cc`，改名同样要改那边并重新走 Gradle 原生构建。
- vendored 源码每个包都有 `package-info`（上游、许可证、本地改动），改过就补一条记录。

## 本地资料（未入库，不要当作仓库事实）

`.gitignore` 里整条忽略了 `docs`（唯一例外是已跟踪的 `docs/pinyin-correction-debugging.md`），所以下列内容在
干净 clone 里**不存在**：

- `docs/build-and-run.md`（内容早于 Compose 迁移与 minSdk 提升，已过时）、`docs/*plan*.md`、`docs/probe/`、
  `docs/prototype/rime-frost`（约 600MB 词库源）。
- `docs/check-package-rules.ps1`：重构期的临时校验脚本（自述「临时，故意不放 scripts/」）。它检查的规则已
  整理成上面「包约定」一节，按那里的条文自查即可；脚本只在本地存在时才可运行。

写代码或文档时不要新增对 `docs/` 下路径的依赖；需要长期保留的内容请放进 `scripts/` 或随代码一起入库。
