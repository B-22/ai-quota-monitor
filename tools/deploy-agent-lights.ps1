<#
.SYNOPSIS
    One-shot deploy for the Cowork / Code indicator lights.

.DESCRIPTION
    TrafficMonitor normally runs elevated so it can draw on the taskbar, which
    means it holds an exclusive handle on plugins\AIUsagePreview.dll. A
    non-elevated session can neither stop it nor replace the DLL, so this
    script re-launches itself elevated once and then does the whole sequence:

        1. stop every TrafficMonitor instance and verify the count is zero
        2. copy the freshly built DLL into plugins\
        3. install the PermissionRequest hook that drives the Code red light
        4. start TrafficMonitor once and verify exactly one instance is running

    Aborts before touching anything if an instance cannot be stopped, so a
    half-replaced DLL is never left behind.

.PARAMETER TrafficMonitorDir
    Directory holding TrafficMonitor.exe, plugins\ and AIUsage.ini.

.PARAMETER BuildDll
    The newly built AIUsagePreview.dll to deploy.

.PARAMETER SkipHook
    Deploy the DLL only. The lights still work; the Code light just cannot
    turn red, because a permission prompt is invisible without the hook.
#>
[CmdletBinding()]
param(
    [string] $TrafficMonitorDir,
    [string] $BuildDll,
    [string] $BuildDir = 'build/lights-x64',
    [switch] $SkipHook,
    [switch] $KeepRowSetting
)

$ErrorActionPreference = 'Stop'

# Defaults are derived from this script's own location so the repository never
# carries a machine-specific absolute path. Layout assumed:
#   <root>\TrafficMonitor-AIUsage\tools\   <- this script
#   <root>\TrafficMonitor\                 <- TrafficMonitor.exe + plugins\
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $TrafficMonitorDir) {
    $TrafficMonitorDir = Join-Path (Split-Path -Parent $repoRoot) 'TrafficMonitor'
}
if (-not $BuildDll) {
    $BuildDll = Join-Path $repoRoot (Join-Path $BuildDir 'Release\AIUsagePreview.dll')
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = ([Security.Principal.WindowsPrincipal]$identity).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host '需要管理员权限才能替换正在使用的插件 DLL，正在请求提权...' -ForegroundColor Yellow
    $argList = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass',
        '-File', "`"$PSCommandPath`"",
        '-TrafficMonitorDir', "`"$TrafficMonitorDir`"",
        '-BuildDll', "`"$BuildDll`""
    )
    if ($SkipHook) { $argList += '-SkipHook' }
    # -Wait so the caller sees the elevated run finish rather than returning
    # immediately with nothing deployed.
    Start-Process -FilePath 'powershell' -ArgumentList $argList -Verb RunAs -Wait
    return
}

# ---- Pre-flight -----------------------------------------------------------
$exe = Join-Path $TrafficMonitorDir 'TrafficMonitor.exe'
$pluginsDir = Join-Path $TrafficMonitorDir 'plugins'
$dstDll = Join-Path $pluginsDir 'AIUsagePreview.dll'

foreach ($p in @($exe, $pluginsDir, $BuildDll)) {
    if (-not (Test-Path -LiteralPath $p)) { throw "not found: $p" }
}
Write-Host '[OK] 预检通过：TrafficMonitor.exe / plugins / 新 DLL 均存在' -ForegroundColor Green

# ---- Stop -----------------------------------------------------------------
Get-Process TrafficMonitor -ErrorAction SilentlyContinue |
    Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Milliseconds 900
$remaining = @(Get-Process TrafficMonitor -ErrorAction SilentlyContinue).Count
if ($remaining -ne 0) {
    throw "仍有 $remaining 个 TrafficMonitor 实例未结束，已中止部署（未替换任何文件）"
}
Write-Host '[OK] 所有 TrafficMonitor 实例已结束' -ForegroundColor Green

# ---- Deploy ---------------------------------------------------------------
Copy-Item -LiteralPath $BuildDll -Destination $dstDll -Force
Write-Host "[OK] 插件 DLL 已更新 -> $dstDll" -ForegroundColor Green

# Restore the default two-row layout (its own wiki: "通常情况下任务栏窗口中的项目
# 会以两行的格式显示"). The window is TASKBAR_WND_HEIGHT tall there, and a display
# item that has the row pair to itself gets all of it - which is what the usage
# dashboard needs for full-size rings. Single-row mode (horizontal_arrange =
# true) shrinks the window to two thirds of that height, so it is strictly
# worse for this plugin.
#
# The item COUNT is what decides whether the dashboard gets the pair: see the
# plugin_display_item cleanup below.
if (-not $KeepRowSetting) {
    $cfg = Join-Path $TrafficMonitorDir 'config.ini'
    if (Test-Path -LiteralPath $cfg) {
        $lines = Get-Content -LiteralPath $cfg -Encoding UTF8
        $hit = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^\s*horizontal_arrange\s*=') {
                if ($lines[$i] -notmatch '=\s*false\s*$') {
                    $lines[$i] = 'horizontal_arrange = false'
                    $hit = $true
                }
                break
            }
        }
        if ($hit) {
            Set-Content -LiteralPath $cfg -Value $lines -Encoding UTF8
            Write-Host '[OK] 已恢复 horizontal_arrange = false（两行排列，圆环恢复原来大小）' -ForegroundColor Green
        }
        else {
            Write-Host '[SKIP] horizontal_arrange 已是 false' -ForegroundColor DarkGray
        }
    }
}

# TrafficMonitor gives each taskbar display item HALF the window height in its
# default two-row layout; only an item that gets the row pair to itself keeps
# the full height. Plugin API 7 cannot ask for it, so the item COUNT decides:
# the dashboard must be the plugin's only display item or its rings render at
# half size with the label and value lines overlapping.
#
# The plugin no longer registers the two light items in the default placement
# (they are drawn inside the dashboard), but config.ini can still carry them
# from an earlier version - stale names would keep the slot split. Clean them
# out while TrafficMonitor is stopped.
if (-not $KeepRowSetting) {
    $cfg = Join-Path $TrafficMonitorDir 'config.ini'
    if (Test-Path -LiteralPath $cfg) {
        $lines = Get-Content -LiteralPath $cfg -Encoding UTF8
        $changed = $false
        for ($i = 0; $i -lt $lines.Count; $i++) {
            if ($lines[$i] -match '^\s*plugin_display_item\s*=\s*(.*)$') {
                $items = @($Matches[1] -split ',' | ForEach-Object { $_.Trim() } |
                    Where-Object { $_ -and $_ -ne 'AIUsageCoworkLight' -and $_ -ne 'AIUsageCodeLight' })
                $joined = ($items -join ',')
                if ($joined -ne $Matches[1].Trim()) {
                    $lines[$i] = "plugin_display_item = $joined"
                    $changed = $true
                }
                break
            }
        }
        if ($changed) {
            Set-Content -LiteralPath $cfg -Value $lines -Encoding UTF8
            Write-Host '[OK] 已从显示项里移除两个独立灯项（灯已画在仪表盘内），圆环恢复满尺寸' -ForegroundColor Green
        }
        else {
            Write-Host '[SKIP] 显示项已无独立灯项' -ForegroundColor DarkGray
        }
    }
}

if (-not $SkipHook) {
    $hookScript = Join-Path $PSScriptRoot 'install-code-status-hook.ps1'
    if (Test-Path -LiteralPath $hookScript) {
        & $hookScript -ConfigDir $TrafficMonitorDir
    }
    else {
        Write-Warning "未找到 $hookScript，已跳过 hook 安装（Code 灯不会变红）"
    }
}

# ---- Start ----------------------------------------------------------------
Start-Process -FilePath $exe -WorkingDirectory $TrafficMonitorDir
Start-Sleep -Seconds 2
$count = @(Get-Process TrafficMonitor -ErrorAction SilentlyContinue).Count
if ($count -ne 1) {
    Write-Warning "TrafficMonitor 进程数为 $count（预期 1），请检查是否有残留实例"
}
else {
    Write-Host '[OK] TrafficMonitor 已启动，实例数 = 1' -ForegroundColor Green
}

Write-Host ''
Write-Host '完成。接下来在 TrafficMonitor 里启用两个显示项：' -ForegroundColor Cyan
Write-Host '  右键任务栏窗口 -> 显示设置 -> 勾选 "Claude Cowork Status" 和 "Claude Code Status"'
Write-Host ''
Read-Host '按回车关闭'
