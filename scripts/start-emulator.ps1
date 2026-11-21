#Requires -Version 7
<#
.SYNOPSIS
    后台启动 Android 模拟器并等待其完成开机，可选安装最新的 debug APK。
.DESCRIPTION
    本脚本解决两个曾反复出现的"模拟器启动即崩溃"问题（2026-10-24 排查结论）：

    1) 残留实例标记：上次模拟器被强杀后，%TEMP%\avd\running 下的 pid 标记与
       ~\.android\avd\<avd>\ 下的锁文件不会自动清理，导致下次启动报
       "It seems too many emulator instances are running"。脚本会在启动前
       清理这些残留（只删 PID 已不存在的标记，绝不影响正在运行的实例）。

    2) 启动器控制台关闭连带杀掉模拟器：emulator.exe 是控制台子系统程序，
       用 Start-Process 直接拉起会共享父控制台，父 shell 退出时模拟器被一并
       终止。脚本改用 DETACHED_PROCESS（含 Job Object 突破尝试）创建独立
       进程组，模拟器因此能在启动 shell 退出后继续存活，真正实现后台运行。

    脚本幂等：若模拟器已在运行，直接报告现状并复用，不会启动第二个实例。
.EXAMPLE
    pwsh -File scripts\start-emulator.ps1                       # 仅启动并等开机
    pwsh -File scripts\start-emulator.ps1 -InstallApk           # 启动 + 装最新 debug APK
    pwsh -File scripts\start-emulator.ps1 -InstallApk -EnableIme # 装 APK 并切默认输入法
    pwsh -File scripts\start-emulator.ps1 -Status               # 查看状态
    pwsh -File scripts\start-emulator.ps1 -Stop                 # 停止模拟器
#>
param(
    [string]$Avd = 'Pixel_10',
    # 默认走 host 硬件加速（2026-10-25 实测 RTX 4070 Ti SUPER 渲染正常且明显更流畅）；
    # 若驱动问题复发，可临时改回 -Gpu swiftshader_indirect
    [string]$Gpu = 'host',
    [string]$SdkRoot = '',
    [switch]$NoWait,
    [int]$TimeoutSec = 240,
    [switch]$EnableIme,
    # 安装 app/build/outputs/apk/debug/ 下最新的 APK
    [switch]$InstallApk,
    [switch]$Status,
    [switch]$Stop,
    # 沙箱/IDE 可能用 Job Object 禁止 breakaway，此时模拟器会随脚本退出；
    # 加 -Foreground 让脚本驻留前台，Ctrl+C 一并停止模拟器
    [switch]$Foreground,
    [string]$ImeId = 'com.whale.inputmethod.debug/com.whale.ime.ui.service.ImeService'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Info([string]$m) { Write-Host "  $m" }
function Ok([string]$m)   { Write-Host "  [ok]   $m" -f Green }
function Warn([string]$m) { Write-Host "  [warn] $m" -f Yellow }
function Die([string]$m)  { Write-Host "  [FAIL] $m" -f Red; throw $m }

$Repo = Split-Path -Parent $PSScriptRoot
# 日志落到系统临时目录，仓库内不留本机产物
$LogDir = Join-Path ([IO.Path]::GetTempPath()) 'whaleime-logs\emulator'

# ---------------------------------------------------------------- 定位 Android SDK
. (Join-Path $PSScriptRoot 'lib\android-sdk.ps1')

if ($SdkRoot -and -not (Test-Path -LiteralPath $SdkRoot)) { Die "SDK 不存在: $SdkRoot" }
$Sdk = Resolve-SdkRoot -Repo $Repo -Explicit $SdkRoot
if (-not $Sdk) {
    Die '找不到 Android SDK：请设置 ANDROID_SDK_ROOT，或在 local.properties 写 sdk.dir（模板见 local.properties.example）'
}
$EmuExe = Join-Path $Sdk 'emulator\emulator.exe'
$AdbExe = Join-Path $Sdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $EmuExe)) { Die "找不到 emulator.exe: $EmuExe" }
if (-not (Test-Path -LiteralPath $AdbExe)) { Die "找不到 adb.exe: $AdbExe" }

# ---------------------------------------------------------------- 模拟器 / adb 状态
function Get-EmulatorState {
    $procs = @(Get-Process -Name 'emulator', 'qemu-system-x86_64', 'qemu-system-aarch64' -ErrorAction SilentlyContinue)
    $devices = @(& $AdbExe devices 2>$null | Where-Object { $_ -match '^(emulator-\d+)\s+(device|offline|unauthorized)' } | ForEach-Object { $_.Trim() })
    [pscustomobject]@{ Processes = $procs; Devices = $devices }
}

function Get-ReadySerial {
    foreach ($l in (& $AdbExe devices 2>$null)) {
        if ($l -match '^(emulator-\d+)\s+device\s*$') { return $Matches[1] }
    }
    return $null
}

# ---------------------------------------------------------------- 清理残留实例标记
# 只删除"持有 PID 已不存在"的标记/锁，运行中的实例绝不受影响。见 .DESCRIPTION。
function Remove-StaleInstanceState {
    $removed = 0
    $livePids = @(Get-EmulatorState).Processes.Id

    # %TEMP%\avd\running 下的 "<pid>\" 目录与 pid_<pid>.ini
    $runningDir = Join-Path ([System.IO.Path]::GetTempPath()) 'avd\running'
    if (Test-Path -LiteralPath $runningDir) {
        foreach ($item in @(Get-ChildItem -LiteralPath $runningDir -Force -ErrorAction SilentlyContinue)) {
            $ownerPid = $null
            if ($item.Name -match '^pid_(\d+)\.ini$' -or $item.Name -match '^(\d+)$') { $ownerPid = [int]$Matches[1] }
            if ($null -eq $ownerPid) { continue }
            if ($livePids -contains $ownerPid) { continue }      # 运行中的模拟器，绝不动
            # PID 存活但不是模拟器进程 => PID 已被系统复用，标记按残留处理
            $proc = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
            if ($proc -and $proc.ProcessName -match '^(emulator|qemu-system-)') { continue }
            if ($proc) { Info "标记 $($item.Name) 的 PID $ownerPid 已被无关进程 $($proc.ProcessName) 复用，按残留清理" }
            try {
                if ($item.PSIsContainer) { [System.IO.Directory]::Delete($item.FullName, $true) }
                else { [System.IO.File]::Delete($item.FullName) }
                Info "已删除残留实例标记: $($item.Name)"
                $removed++
            } catch { Warn "无法删除 $($item.Name): $($_.Exception.Message)" }
        }
    }

    # 无任何模拟器进程存活时，顺带清理 AVD 目录里的残留锁
    if ($livePids.Count -eq 0) {
        $avdDir = Join-Path $env:USERPROFILE ".android\avd\$Avd.avd"
        foreach ($lockName in @('multiinstance.lock', 'hardware-qemu.ini.lock')) {
            $lock = Join-Path $avdDir $lockName
            if (-not (Test-Path -LiteralPath $lock)) { continue }
            try {
                if ([System.IO.Directory]::Exists($lock)) { [System.IO.Directory]::Delete($lock, $true) }
                elseif ([System.IO.File]::Exists($lock)) { [System.IO.File]::Delete($lock) }
                else { continue }
                Info "已删除残留锁: $lockName"; $removed++
            } catch { Warn "无法删除锁 $lockName : $($_.Exception.Message)" }
        }
    }
    return $removed
}

# ---------------------------------------------------------------- 分离启动辅助（C#）
# emulator.exe 是控制台程序，Start-Process 拉起的子进程会随父控制台一起被杀；
# 这里用 DETACHED_PROCESS 让它拥有独立进程组（无控制台），并在允许时突破
# 调用方的 Job Object（沙箱/CI 会用 KILL_ON_JOB_CLOSE 连带杀子进程）。
if (-not ('WhaleIME.DetachedLauncher' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace WhaleIME
{
    public static class DetachedLauncher
    {
        const int DETACHED_PROCESS = 0x00000008;
        const int CREATE_BREAKAWAY_FROM_JOB = 0x01000000;
        const int STARTF_USESTDHANDLES = 0x00000100;
        const uint GENERIC_READ = 0x80000000, GENERIC_WRITE = 0x40000000;
        const uint FILE_SHARE_READ = 0x1, FILE_SHARE_WRITE = 0x2;
        const uint OPEN_EXISTING = 3, CREATE_ALWAYS = 2, FILE_ATTRIBUTE_NORMAL = 0x80;

        [StructLayout(LayoutKind.Sequential)]
        struct SECURITY_ATTRIBUTES { public int nLength; public IntPtr lpSecurityDescriptor; public bool bInheritHandle; }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct STARTUPINFO
        {
            public int cb; public string lpReserved; public string lpDesktop; public string lpTitle;
            public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
            public short wShowWindow, cbReserved2; public IntPtr lpReserved2;
            public IntPtr hStdInput, hStdOutput, hStdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct PROCESS_INFORMATION { public IntPtr hProcess, hThread; public int dwProcessId, dwThreadId; }

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern IntPtr CreateFile(string name, uint access, uint share, IntPtr sa, uint disp, uint flags, IntPtr tmpl);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        static extern bool CreateProcess(string app, string cmd, IntPtr pa, IntPtr ta, bool inherit,
            int flags, IntPtr env, string cwd, ref STARTUPINFO si, out PROCESS_INFORMATION pi);

        [DllImport("kernel32.dll", SetLastError = true)]
        static extern bool CloseHandle(IntPtr h);

        // 返回新进程 PID；stdout/stderr 重定向到指定日志文件
        public static int Launch(string exe, string args, string cwd, string outFile, string errFile)
        {
            var sb = new StringBuilder();
            sb.Append('"').Append(exe).Append('"');
            if (!string.IsNullOrEmpty(args)) sb.Append(' ').Append(args);

            IntPtr hIn = IntPtr.Zero, hOut = IntPtr.Zero, hErr = IntPtr.Zero;
            try
            {
                hIn = CreateFile("NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, IntPtr.Zero, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
                hOut = CreateFile(outFile, GENERIC_WRITE, FILE_SHARE_READ, IntPtr.Zero, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
                hErr = CreateFile(errFile, GENERIC_WRITE, FILE_SHARE_READ, IntPtr.Zero, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, IntPtr.Zero);
                if (hIn == IntPtr.Zero || hOut == IntPtr.Zero || hErr == IntPtr.Zero)
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "无法打开标准句柄");

                var sa = new SECURITY_ATTRIBUTES { nLength = Marshal.SizeOf(typeof(SECURITY_ATTRIBUTES)), bInheritHandle = true };
                IntPtr psa = Marshal.AllocHGlobal(Marshal.SizeOf(sa));
                Marshal.StructureToPtr(sa, psa, false);

                var si = new STARTUPINFO();
                si.cb = Marshal.SizeOf(typeof(STARTUPINFO));
                si.dwFlags = STARTF_USESTDHANDLES;
                si.hStdInput = hIn; si.hStdOutput = hOut; si.hStdError = hErr;

                PROCESS_INFORMATION pi;
                // 先尝试突破 Job Object；不允许 breakaway 时退回纯 DETACHED_PROCESS
                bool ok = CreateProcess(exe, sb.ToString(), psa, IntPtr.Zero, true,
                    DETACHED_PROCESS | CREATE_BREAKAWAY_FROM_JOB, IntPtr.Zero, cwd, ref si, out pi);
                if (!ok)
                {
                    si.hStdInput = hIn; si.hStdOutput = hOut; si.hStdError = hErr;
                    ok = CreateProcess(exe, sb.ToString(), psa, IntPtr.Zero, true,
                        DETACHED_PROCESS, IntPtr.Zero, cwd, ref si, out pi);
                    if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcess 失败");
                }
                Marshal.FreeHGlobal(psa);
                CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
                return pi.dwProcessId;
            }
            finally
            {
                if (hIn != IntPtr.Zero) CloseHandle(hIn);
                if (hOut != IntPtr.Zero) CloseHandle(hOut);
                if (hErr != IntPtr.Zero) CloseHandle(hErr);
            }
        }
    }
}
'@
}

# ---------------------------------------------------------------- 安装最新 debug APK
function Install-LatestDebugApk {
    param([string]$Serial)
    $dir = Join-Path $Repo 'app\build\outputs\apk\debug'
    $apk = Get-ChildItem -LiteralPath $dir -Filter *.apk -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $apk) { Die "未找到 debug APK: $dir" }
    Info "安装 $($apk.Name) ($([math]::Round($apk.Length/1MB)) MB)..."
    & $AdbExe -s $Serial install -r $apk.FullName
    if ($LASTEXITCODE -ne 0) { Die 'APK 安装失败' }
    Ok "已安装: $($apk.Name)"
}

# ---------------------------------------------------------------- 启用本项目的输入法
function Enable-Ime {
    param([string]$Serial)
    & $AdbExe -s $Serial shell ime enable $ImeId | Out-Null
    & $AdbExe -s $Serial shell ime set $ImeId | Out-Null
    # 模拟器会向 Android 暴露 PS/2 硬键盘，不加这条软键盘不显示
    & $AdbExe -s $Serial shell settings put secure show_ime_with_hard_keyboard 1 | Out-Null
    $cur = (& $AdbExe -s $Serial shell settings get secure default_input_method 2>$null | Out-String).Trim()
    if ($cur -eq $ImeId) { Ok "输入法已激活: $cur" } else { Warn "默认输入法为 '$cur'，预期 '$ImeId'" }
}

# ---------------------------------------------------------------- -Stop
if ($Stop) {
    $st = Get-EmulatorState
    if ($st.Processes.Count -eq 0) {
        Info '没有正在运行的模拟器'
        $removed = Remove-StaleInstanceState
        if ($removed -gt 0) { Ok "清理了 $removed 个残留标记" }
        exit 0
    }
    $st.Processes | ForEach-Object { Info "停止 $($_.ProcessName) (PID $($_.Id))" }
    $st.Processes | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    $removed = Remove-StaleInstanceState
    if ($removed -gt 0) { Ok "清理了 $removed 个残留标记" }
    Ok '模拟器已停止'
    exit 0
}

# ---------------------------------------------------------------- -Status
if ($Status) {
    $st = Get-EmulatorState
    if ($st.Processes.Count -eq 0) { Info '模拟器未运行' }
    else { $st.Processes | ForEach-Object { Info "$($_.ProcessName) PID $($_.Id)  内存 $([math]::Round($_.WorkingSet64/1MB))MB" } }
    if ($st.Devices.Count -eq 0) { Info '无 adb 设备' }
    else {
        $st.Devices | ForEach-Object { Info "adb: $_" }
        $serial = Get-ReadySerial
        if ($serial) {
            Info "boot_completed : $((& $AdbExe -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim())"
            Info "默认输入法     : $((& $AdbExe -s $serial shell settings get secure default_input_method 2>$null | Out-String).Trim())"
        }
    }
    exit 0
}

# ---------------------------------------------------------------- 幂等检查：已在运行则复用
$existing = Get-EmulatorState
if ($existing.Processes.Count -gt 0) {
    $existing.Processes | ForEach-Object { Info "模拟器已在运行: $($_.ProcessName) PID $($_.Id)" }
    $serial = Get-ReadySerial
    if (-not $serial) { Die '模拟器进程存活但 adb 未就绪，请稍候或用 -Stop 后重启' }
    $boot = (& $AdbExe -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($boot -ne '1') { Die "设备 $serial 尚未完成开机 (boot_completed=$boot)" }
    Ok "复用现有设备 $serial"
    if ($InstallApk) { Install-LatestDebugApk -Serial $serial }
    if ($EnableIme) { Enable-Ime -Serial $serial }
    exit 0
}

# ---------------------------------------------------------------- 启动前预检
$avds = & $EmuExe -list-avds 2>$null | Out-String
if ($avds -notmatch "(?m)^\s*$([regex]::Escape($Avd))\s*$") { Die "AVD '$Avd' 不存在，可用: $($avds.Trim())" }

$stale = Remove-StaleInstanceState
if ($stale -gt 0) { Ok "清理了 $stale 个残留标记" }

# ---------------------------------------------------------------- 启动并等待开机（带自动重试）
$emuArgs = @('-avd', $Avd, '-no-snapshot-load', '-no-boot-anim', '-gpu', $Gpu, '-netdelay', 'none', '-netspeed', 'full')
$maxAttempts = 3
$serial = $null
$booted = $false

for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $outLog = Join-Path $LogDir "emulator-$stamp.out.log"
    $errLog = Join-Path $LogDir "emulator-$stamp.err.log"

    # 子进程需要这些环境变量来定位 SDK
    $env:ANDROID_SDK_ROOT = $Sdk
    $env:ANDROID_HOME = $Sdk

    if ($Foreground) {
        # 前台模式：脚本驻留，模拟器作为普通子进程存活（用于禁止 breakaway 的沙箱），输出直接进控制台
        $proc = Start-Process -FilePath $EmuExe -ArgumentList $emuArgs -PassThru
        $emuPid = $proc.Id
    } else {
        Info "第 $attempt/$maxAttempts 次尝试，日志: $outLog"
        # 经 cmd.exe 包装启动：输出重定向由 cmd 自身完成，
        # 规避 std 句柄继承偶发失效导致日志 0 字节、无法排障的问题
        $cmdExe = Join-Path $env:SystemRoot 'System32\cmd.exe'
        $inner = "`"$EmuExe`" $($emuArgs -join ' ') > `"$outLog`" 2> `"$errLog`""
        $emuPid = [WhaleIME.DetachedLauncher]::Launch($cmdExe, "/c `"$inner`"", $Sdk, 'NUL', 'NUL')
    }
    Ok "模拟器已启动, PID $emuPid"

    # 等 5 秒确认没有秒退
    Start-Sleep -Seconds 5
    $died = -not (Get-Process -Id $emuPid -ErrorAction SilentlyContinue)

    if (-not $died -and $NoWait) { Info '不等待开机（-NoWait）'; exit 0 }

    # 等待开机完成
    if (-not $died) {
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline) {
            $serial = Get-ReadySerial
            if ($serial) {
                $boot = (& $AdbExe -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
                if ($boot -eq '1') { $booted = $true; break }
            }
            if (-not (Get-Process -Id $emuPid -ErrorAction SilentlyContinue)) { $died = $true; break }
            Start-Sleep -Seconds 5
        }
        if (-not $died -and -not $booted) { Die "超过 $TimeoutSec 秒仍未完成开机（日志: $outLog）" }
    }

    if ($booted) { break }

    # ---- 进程中途死亡：区分"实例数超限"幽灵状态与其他失败 ----
    $logText = ''
    foreach ($f in @($outLog, $errLog)) {
        $logText += (Get-Content -LiteralPath $f -Raw -ErrorAction SilentlyContinue)
    }
    Remove-StaleInstanceState | Out-Null   # 清理本次失败留下的标记/锁
    if ($attempt -lt $maxAttempts -and $logText -match 'too many emulator instances') {
        Warn '检测到"too many emulator instances"（前次实例的幽灵状态，通常一两分钟后自行消散），90 秒后自动重试...'
        Start-Sleep -Seconds 90
        continue
    }
    Get-Content -LiteralPath $errLog -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "  $_" -f DarkGray }
    Die "模拟器启动失败（进程已退出），详见 $outLog"
}

if (-not $booted) { Die "重试 $maxAttempts 次后仍未成功启动" }

$api = (& $AdbExe -s $serial shell getprop ro.build.version.sdk 2>$null | Out-String).Trim()
$abi = (& $AdbExe -s $serial shell getprop ro.product.cpu.abi 2>$null | Out-String).Trim()
Ok "开机完成: $serial (API $api, $abi)"

if ($InstallApk) { Install-LatestDebugApk -Serial $serial }
if ($EnableIme) { Enable-Ime -Serial $serial }

Info "日志目录: $LogDir"
Info "查看状态: pwsh -File scripts\start-emulator.ps1 -Status"

# 前台模式：驻留直到模拟器退出，Ctrl+C 一并结束
if ($Foreground) {
    Info '前台模式：脚本与模拟器共存亡，Ctrl+C 一并结束'
    while (Get-Process -Id $emuPid -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 3 }
    Info '模拟器已退出'
}
exit 0
