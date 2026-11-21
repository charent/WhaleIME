# :ime-native —— 原生引擎与引擎数据

本模块是「必须进 APK 的二进制」与「唯一走 Gradle 的原生构建」的家，**没有一行 Kotlin/Java 业务代码**：

```
ime-native/
├── build.gradle                 # 唯一声明 ndkVersion + externalNativeBuild 的模块
├── src/main/assets/rime/        # Rime schema / 词典（约 200 MB，随 APK 分发）
├── src/main/assets/hccr/        # 手写识别模型（MobileNetV2 → NCNN INT8）
├── src/main/assets/pinyindb/    # 拼音表
├── src/main/assets/fonts/       # 表情字体（10 MB，app 侧声明 noCompress）
├── src/main/jniLibs/<abi>/      # libwhaleime.so（脚本构建产物，arm64-v8a + x86_64）
├── src/main/cpp/hccr/           # 手写 JNI 的 CMake 工程 → libhccr_jni.so
├── third_party/ncnn-android/    # hccr 的 CMake 用相对路径引用，必须与 cpp/hccr 同模块
└── native/                      # 不走 Gradle 的引擎构建资产
    ├── src/                     # JNI 桥（whale_jni.cpp、associate_rear.cpp）
    ├── CMakeLists.txt           # 引擎（脚本驱动），见 native/README.md
    ├── scripts/                 # build-engine.ps1 / build-deps.ps1 / fetch-sources.ps1 …
    ├── prebuilt/  native-build/ # 依赖静态库与构建中间产物（均 gitignore）
    └── README.md
```

两条原生构建路径**不能合并**：`src/main/cpp/hccr` 走 Gradle/CMake 产出 `libhccr_jni.so`，
`native/scripts/build-engine.ps1` 走脚本产出 `libwhaleime.so`；两者都落在 `jniLibs` 里，
同名产物会互相覆盖。

引擎数据的完整性由 `CustomConstant.CURRENT_RIME_DICT_DATA_VERSIOM` 控制：改动
`src/main/assets/rime` 后必须 bump 该版本号，否则已装设备不会重新拷贝。

## 原生引擎（libwhaleime.so）构建流程与踩坑

引擎**不是闭源预编译库**，可从公开源码完整重建。完整技术说明见
[`native/README.md`](native/README.md)（架构、JNI 分工、依赖版本表），
这里记录实际操作流程和真实踩过的坑。

### 环境要求

| 工具 | 版本 | 说明 |
|---|---|---|
| Android SDK | 任意，路径写在 `local.properties` 的 `sdk.dir` | |
| NDK | **28.0.13004108**（路径写死在脚本里） | `$SDK/ndk/28.0.13004108` |
| CMake / Ninja | **3.31.6**（Android SDK 自带版本） | `$SDK/cmake/3.31.6/bin/` |
| PowerShell | pwsh 7+ | 脚本不兼容 Windows PowerShell 5 |
| 网络 | 能访问 GitHub / archives.boost.io | 支持代理，见下 |

### 一次完整构建

```powershell
# 首次（下载 6 个源码包 + 编译依赖 + 2 个 64 位 ABI）：
$env:HTTPS_PROXY = 'http://127.0.0.1:10808'   # 按需设置代理
pwsh -File scripts\build-engine.ps1 -FetchSources

# 只改了 native/src/**（JNI 层）时的快速循环（~20s/ABI，两个 ABI 一次编完）：
pwsh -File scripts\build-engine.ps1 -SkipDeps
```

### ABI 策略

只保留 64 位：`arm64-v8a`（真机 / 发布包）与 `x86_64`（模拟器原生加载验证）。
32 位（`armeabi-v7a` / `x86`）的 so、依赖、脚本参数均已移除，向脚本传 32 位 ABI
会直接报错。默认一次命令按 **x86_64 → arm64-v8a** 的顺序编两个库：
先把 `x86_64` 的 debug 包装到模拟器上验证（走原生，不经 ARM 转译），
通过后同一次编译产出的 `arm64` 直接上真机，不必再单独编译一遍。

产物直接覆盖到 `ime-native/src/main/jniLibs/<abi>/libwhaleime.so`（AGP 约定的默认目录），
之后 `./gradlew :app:assembleDebug` 重新打包即可，Gradle 侧无需任何改动
（**不接 externalNativeBuild**：那是手写引擎 `libhccr_jni.so` 的构建路径，
两条路径都产出到 `jniLibs`，同名库会互相覆盖）。

源码包缓存在 `native/native-build/dl/`，解压在 `native/native-build/src/`，依赖静态库在
`native/prebuilt/<abi>/`（均已 gitignore）。重跑 `-FetchSources` 时已解压的包会跳过，
只有损坏/缺失的包会重新下载。

### 踩坑记录（2026-10 首次全量重建）

1. **GNU tar 把 Windows 盘符当远程主机**。pwsh 里直接调 `tar.exe` 在装有 Git Bash
   的机器上会解析到 Git Bash 的 GNU tar，报
   `tar (child): Cannot connect to X: resolve failed`——它把绝对路径开头的盘符当成
   `host:path`。已修复：`fetch-sources.ps1` 显式调用 `$env:SystemRoot\System32\tar.exe`
   （Windows 自带 bsdtar）。
2. **下载必须走代理**（国内网络环境）。直连 codeload.github.com / archives.boost.io
   很慢且容易下载不完整——曾出现 librime 压缩包只有 2.6 MB（完整约 2.6 MB 是正常的，
   但解压报 `unexpected end of file` 时先怀疑下载截断）。两个下载脚本现已支持
   `HTTPS_PROXY`/`HTTP_PROXY` 环境变量，自动透传给 `Invoke-WebRequest`。
   下载失败/损坏后，删掉 `native-build/dl/` 下对应压缩包重跑即可。
3. **部分下载的压缩包不会被自动重新下载**（脚本看到文件存在就跳过），排查解压失败时
   先用 `tar -t` 或 `file` 验证压缩包完整性。
4. **NDK/CMake 版本写死**：脚本里是 `ndk\28.0.13004108` 和 `cmake\3.31.6` 的绝对路径。
   升级 SDK 组件后要同步改 `native/scripts/build-engine.ps1`。
5. **依赖编译本身耗时较长**（librime + 5 个第三方库 × 2 个 ABI），但只在首次或升级
   librime 版本时需要；日常 JNI 改动一律用 `-SkipDeps`。
6. **librime 只改 1 处**：`src/rime/no_logging.h` 被替换为 Android 静默日志实现
   （`scripts/rime-no-logging-android.h`）。升级 librime 版本时需要重新套这个补丁，
   其余 JNI 层只依赖公开 `rime_api.h`；唯一绑内部 C++ 的是联想词
   `associate_rear.cpp`，升级时复核其文件头列出的三处签名。
7. **目录搬迁后 CMake 缓存失效**：`native/native-build/build/<abi>/jni` 里的
   `CMakeCache.txt` 记录的是绝对路径，目录一搬就报
   `The current CMakeCache.txt directory ... is different than the directory ...`。
   删掉该 `jni` 目录重跑即可（`-Clean` 等价于这一步）。

### 引擎升级 checklist

1. 改 `native/scripts/fetch-sources.ps1` 里的版本号 / URL，删除 `native-build/dl`、
   `native-build/src`、`prebuilt/` 后重跑 `-FetchSources`；
2. 按新版本复核 `rime-no-logging-android.h` 补丁是否仍然适用、`associate_rear.cpp`
   的内部签名是否变化；
3. 跑完整构建（不带 `-SkipDeps`），确认 x86_64 与 arm64-v8a 都产出并覆盖到
   `src/main/jniLibs/<abi>/`；
4. 重新打包 APK，真机验证拼音/九宫格/联想词/手写。
