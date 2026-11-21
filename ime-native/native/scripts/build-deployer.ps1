# 构建宿主工具：把 librime 的 rime_deployer 编成 Android x86_64 可执行文件。
#
# 为什么不是 Windows 原生 exe：本机没有 MSVC，唯一的工具链是 NDK(cmake/ninja)；
# 而预编译词典只需要 rime_deployer 在任何能跑的地方执行一次——x86_64 模拟器
# 原生执行（docs/build-and-run.md 的 AVD 就是 x86_64），adb push 上去跑完拉回
# 产物即可，不引入新工具链依赖。
#
# 用法：
#   pwsh -File scripts\build-deployer.ps1          # 产出 native-build\build\x86_64\librime\bin\rime_deployer
#   pwsh -File scripts\build-deployer.ps1 -RunTest # 顺手在模拟器上跑一次 --version 自检
#
# 前置：
#   - 已跑过 build-deps.ps1（x86_64 依赖在 native-build\install\x86_64）
#   - 模拟器在跑（-RunTest 时）

param(
  [switch]$RunTest
)

$ErrorActionPreference = 'Stop'

$CppRoot = Split-Path -Parent $PSScriptRoot          # ...\ime-native\native
$ImeNative = Resolve-Path (Join-Path $CppRoot '..')
$NativeRoot = Join-Path $CppRoot 'native-build'
$SrcRoot = Join-Path $NativeRoot 'src'

$localProps = Join-Path (Resolve-Path (Join-Path $CppRoot '..\..')) 'local.properties'
$Sdk = $null
if (Test-Path $localProps) {
  $line = (Get-Content $localProps | Select-String '^\s*sdk\.dir').ToString()
  $Sdk = $line.Substring($line.IndexOf('=') + 1).Trim() -replace '\\:', ':' -replace '\\\\', '\'
}
if (-not $Sdk -or -not (Test-Path $Sdk)) { $Sdk = $env:ANDROID_SDK_ROOT }
if (-not $Sdk) { $Sdk = $env:ANDROID_HOME }
if (-not $Sdk -or -not (Test-Path $Sdk)) { throw '未找到 Android SDK' }

$Ndk = Join-Path $Sdk 'ndk\28.0.13004108'
$Cmake = Join-Path $Sdk 'cmake\3.31.6\bin\cmake.exe'
$Ninja = Join-Path $Sdk 'cmake\3.31.6\bin\ninja.exe'
$Toolchain = Join-Path $Ndk 'build\cmake\android.toolchain.cmake'

$Abi = 'x86_64'
$ApiLevel = 23
$install = Join-Path $NativeRoot "install\$Abi"
if (-not (Test-Path (Join-Path $install 'lib\librime.a'))) {
  throw "缺少 $install\lib\librime.a，请先跑 build-deps.ps1 -Abi x86_64"
}
$posixInstall = $install -replace '\\', '/'
$boostInclude = (Join-Path $SrcRoot 'boost_1_86_0') -replace '\\', '/'
$rimeBuild = Join-Path $NativeRoot "build\$Abi\librime-hosttools"

# build-deps.ps1 里的源码树补丁在这里同样要打（no_logging / corrector / spelling…）。
# 必须带上 -InstallDir：编译期 include 路径里 install/ 在源码树之前，头文件补丁
# 不同步到 install 就会被旧副本吃掉。
& (Join-Path $PSScriptRoot 'apply-rime-patches.ps1') -SrcRoot $SrcRoot -InstallDir $install

# librime 的 tools(rime_deployer) 只在 BUILD_SHARED_LIBS=ON 时编（CMakeLists 门控），
# 预编译场景没关系：产物是 exe + librime.so 一起 push 到模拟器，跑完即弃
Write-Host "=== [$Abi] librime host-tools : configure（rime_deployer）===" -ForegroundColor Cyan
& $Cmake -S (Join-Path $SrcRoot 'librime-1.17.0') -B $rimeBuild -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$Ninja" `
  "-DCMAKE_TOOLCHAIN_FILE=$Toolchain" `
  "-DANDROID_ABI=$Abi" `
  "-DANDROID_PLATFORM=android-$ApiLevel" `
  '-DCMAKE_BUILD_TYPE=Release' `
  "-DCMAKE_INSTALL_PREFIX=$posixInstall" `
  '-DBUILD_SHARED_LIBS=ON' `
  '-DBUILD_STATIC=ON' `
  '-DBUILD_TEST=OFF' `
  '-DBUILD_SAMPLE=OFF' `
  '-DBUILD_DATA=OFF' `
  '-DBUILD_SEPARATE_LIBS=OFF' `
  '-DENABLE_LOGGING=OFF' `
  '-DENABLE_EXTERNAL_PLUGINS=OFF' `
  '-DENABLE_THREADING=ON' `
  "-DCMAKE_PREFIX_PATH=$posixInstall" `
  "-DMarisa_LIBRARY=$posixInstall/lib/libmarisa.a" `
  "-DMarisa_INCLUDE_PATH=$posixInstall/include" `
  "-DYamlCpp_LIBRARY=$posixInstall/lib/libyaml-cpp.a" `
  "-DYamlCpp_INCLUDE_PATH=$posixInstall/include" `
  "-DYamlCpp_NEW_API=$posixInstall/include" `
  "-DLevelDb_LIBRARY=$posixInstall/lib/libleveldb.a" `
  "-DLevelDb_INCLUDE_PATH=$posixInstall/include" `
  "-DOpencc_LIBRARY=$posixInstall/lib/libopencc.a" `
  "-DOpencc_INCLUDE_PATH=$posixInstall/include" `
  "-DBOOST_INCLUDEDIR=$boostInclude" `
  "-DBOOST_LIBRARYDIR=$posixInstall/lib" `
  "-DCMAKE_CXX_FLAGS=-O2 -I$boostInclude" `
  '-DCMAKE_SHARED_LINKER_FLAGS=-llog' `
  '-DCMAKE_EXE_LINKER_FLAGS=-llog'
if ($LASTEXITCODE -ne 0) { throw 'configure 失败' }

Write-Host "=== [$Abi] librime host-tools : build rime_deployer + build_grammar ===" -ForegroundColor Cyan
# build_grammar（八股文 .gram 编译工具）来自 octagram 插件（apply-rime-patches 安装）
& $Cmake --build $rimeBuild --target rime_deployer --target build_grammar --parallel
if ($LASTEXITCODE -ne 0) { throw '构建失败' }

$deployer = Join-Path $rimeBuild 'bin\rime_deployer'
if (-not (Test-Path $deployer)) { throw "未生成 $deployer" }
Write-Host ("完成：{0}  {1} KB" -f $deployer, [math]::Round((Get-Item $deployer).Length/1KB)) -ForegroundColor Green

if ($RunTest) {
  $adb = Join-Path $Sdk 'platform-tools\adb.exe'
  & $adb push $deployer /data/local/tmp/rime_deployer | Out-Host
  & $adb push (Join-Path $rimeBuild 'lib\librime.so') /data/local/tmp/ | Out-Host
  & $adb shell chmod +x /data/local/tmp/rime_deployer
  & $adb shell "LD_LIBRARY_PATH=/data/local/tmp /data/local/tmp/rime_deployer --help" 2>&1 |
    Select-Object -First 6 | Out-Host
}
