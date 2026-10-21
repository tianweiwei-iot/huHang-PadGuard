<#
.SYNOPSIS
    PadGuard 孩子端 Device Owner (DO) 一键部署脚本（L0 部署基线）

.DESCRIPTION
    将孩子端设为 Device Owner，使其获得系统级不可绕过的管控能力：
      - setUninstallBlocked(true) 系统级硬拦卸载（无需家长授权码也能拦）
      - 设置中不再出现"停用设备管理员"入口
      - 配合 Kiosk（DeviceOwnerEnforcer）可从物理上消除桌面这一逃逸目标
    这是"不可卸载"在数学上唯一成立的路径（详见《DO部署基线引导手册.md》）。

    三种激活方式：
      1) adb dpm set-device-owner（本脚本默认，最可靠，需 USB 调试）
      2) NFC provisioning（见手册 §2，适合批量出厂）
      3) QR provisioning（见手册 §3，适合批量出厂）

.PARAMETER Package
    孩子端应用包名。默认 com.padguard.child.debug（请按实际构建 flavor 调整）。
.PARAMETER Receiver
    AndroidManifest 中 device-admin receiver 的完整类名。
    默认 com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver（请以真机 manifest 的 android:name 为准）。
.PARAMETER Adb
    adb 可执行文件路径；默认从 PATH 与常见 Android SDK 位置探测。
.PARAMETER Revert
    撤销激活（remove-active-admin）。注意：DO 只能经"恢复出厂设置"彻底移除，
    本开关仅用于调试期解除，生产机请用恢复出厂。

.EXAMPLE
    .\do-provisioning.ps1
    .\do-provisioning.ps1 -Package com.padguard.child.debug -Receiver com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver
    .\do-provisioning.ps1 -Revert
#>

[CmdletBinding()]
param(
    [string]$Package = "com.padguard.child.debug",
    [string]$Receiver = "com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver",
    [string]$Adb = "",
    [switch]$Revert
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    if ($script:Adb -and (Test-Path $script:Adb)) { return $script:Adb }
    $cands = @(
        "adb",
        "$env:ANDROID_HOME\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe"
    )
    foreach ($c in $cands) {
        if ($c -eq "adb") {
            $p = Get-Command adb -ErrorAction SilentlyContinue
            if ($p) { return $p.Source }
        } elseif (Test-Path $c) {
            return $c
        }
    }
    return $null
}

function Run-Adb {
    param([string[]]$Args)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $script:AdbPath
    $psi.Arguments = ($Args -join " ")
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = $proc.StandardOutput.ReadToEnd()
    $err = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    return [PSCustomObject]@{ ExitCode = $proc.ExitCode; StdOut = $out; StdErr = $err }
}

Write-Host "==================================================" -ForegroundColor Cyan
Write-Host " PadGuard 孩子端 Device Owner 部署工具" -ForegroundColor Cyan
Write-Host "==================================================" -ForegroundColor Cyan

$script:AdbPath = Find-Adb
if (-not $script:AdbPath) {
    Write-Host "✗ 未找到 adb。请安装 Android SDK 平台工具，或将 Adb 参数指向 adb.exe。" -ForegroundColor Red
    exit 1
}
Write-Host "✓ adb: $script:AdbPath" -ForegroundColor Green

$dev = Run-Adb @("devices")
if ($dev.ExitCode -ne 0) {
    Write-Host "✗ adb devices 执行失败:`n$($dev.StdErr)" -ForegroundColor Red
    exit 1
}
$lines = ($dev.StdOut -split "`n") | Where-Object { $_ -match "\t(device|emulator)" }
if ($lines.Count -eq 0) {
    Write-Host "✗ 未发现已连接设备。请开启 USB 调试并连接平板后重试。" -ForegroundColor Red
    exit 1
}
if ($lines.Count -gt 1) {
    Write-Host "✗ 检测到多台设备，请只连接一台目标平板后再运行。" -ForegroundColor Red
    $lines | ForEach-Object { Write-Host "  $_" }
    exit 1
}
$deviceId = ($lines[0] -split "`t")[0]
Write-Host "✓ 目标设备: $deviceId" -ForegroundColor Green

if ($Revert) {
    Write-Host ">> 撤销设备管理员（调试用，生产机请用恢复出厂彻底移除 DO）..." -ForegroundColor Yellow
    $r = Run-Adb @("shell", "dpm", "remove-active-admin", "$Package/$Receiver", "-n")
    if ($r.ExitCode -eq 0) { Write-Host "✓ 已解除（仅调试态）。" -ForegroundColor Green }
    else { Write-Host "✗ 解除失败:`n$($r.StdErr)" -ForegroundColor Red }
    exit $r.ExitCode
}

# 1) 确保孩子端已安装（DO 必须作用于已安装且非系统核心的包）
$pkg = Run-Adb @("shell", "pm", "list", "packages", $Package)
if ($pkg.StdOut -notmatch [regex]::Escape($Package)) {
    Write-Host "✗ 设备上未安装 $Package。请先安装孩子端 APK 再执行 DO 部署。" -ForegroundColor Red
    exit 1
}
Write-Host "✓ 已安装 $Package" -ForegroundColor Green

# 2) 关键前置：设备必须处于"未设置锁屏密码 / 未添加其他账号"的出厂态附近，
#    DO 设置失败最常见原因：已有屏幕锁密码或已登录非制造商账号。给出明确提示。
Write-Host ">> 前置检查：请确认平板当前「未设置锁屏密码」且「未登录非出厂 Google/厂商账号」。" -ForegroundColor Yellow
Write-Host "   （若已设密码，请先在系统设置中移除锁屏密码再继续；DO 设置完成后可重新设置。）" -ForegroundColor Yellow

# 3) 执行 set-device-owner
Write-Host ">> 执行 dpm set-device-owner ..." -ForegroundColor Cyan
$set = Run-Adb @("shell", "dpm", "set-device-owner", "$Package/$Receiver")
if ($set.ExitCode -eq 0 -and $set.StdErr -notmatch "Error|error|失败") {
    Write-Host "✓ Device Owner 设置成功。" -ForegroundColor Green
} else {
    Write-Host "✗ set-device-owner 失败。常见原因与对策：" -ForegroundColor Red
    Write-Host "   - 已设锁屏密码：到系统设置移除密码后重试" -ForegroundColor Yellow
    Write-Host "   - 已登录非出厂账号：退出/移除该账号后重试" -ForegroundColor Yellow
    Write-Host "   - 已是非 DO 设备管理员：先 remove-active-admin 再试" -ForegroundColor Yellow
    Write-Host "   - 提示：DO 只能经恢复出厂清除`n" -ForegroundColor Yellow
    Write-Host ($set.StdOut + $set.StdErr) -ForegroundColor Red
    exit 1
}

# 4) 验证
$owners = Run-Adb @("shell", "dpm", "list-owners")
Write-Host ">> dpm list-owners:" -ForegroundColor Cyan
Write-Host $owners.StdOut
if ($owners.StdOut -match [regex]::Escape($Package)) {
    Write-Host "✓ 验证通过：$Package 已是 Device Owner。" -ForegroundColor Green
    Write-Host "   后续：打开孩子端完成绑定；非 DO 下无法物理锁死的能力现已成立。" -ForegroundColor Green
} else {
    Write-Host "⚠ 设置命令返回成功，但 list-owners 未包含本包，请手动确认。" -ForegroundColor Yellow
}
