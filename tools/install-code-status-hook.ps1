<#
.SYNOPSIS
    Installs the Claude Code hook that drives the red ("waiting for you")
    state of the AIUsage taskbar Code light.

.DESCRIPTION
    A permission prompt is never written to the session transcript, so the
    plugin cannot see one by reading files alone. Claude Code does fire a
    PermissionRequest hook, and the desktop Code tab shares
    ~/.claude/settings.json with the CLI, so a single hook covers both.

    The hook touches <ConfigDir>\agent-status\code\<session_id>.wait. The
    plugin treats that marker as an open prompt only while it is newer than
    the session transcript: answering the prompt lets the tool run, the
    transcript advances past the marker, and the light clears itself. No
    second hook runs on every tool call.

    Cowork is deliberately not covered. Cowork turns execute against a fresh
    per-session config directory, so hooks in ~/.claude/settings.json never
    fire there. The plugin reads Cowork's own audit.jsonl instead, which
    records permission_request / permission_response pairs directly.

    The script is idempotent: re-running it rewrites its own hook entry and
    leaves every other hook and setting untouched.

.PARAMETER ConfigDir
    TrafficMonitor directory holding AIUsage.ini. Marker files and the signal
    script are created underneath it.

.PARAMETER Uninstall
    Remove the hook entry and the marker directory.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ConfigDir,
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'

# Tag identifying entries this script owns, so uninstall and re-install never
# touch a hook the user added by hand.
$marker = 'AIUsageCodeLight'

$settingsPath = Join-Path $env:USERPROFILE '.claude\settings.json'
$statusRoot = Join-Path $ConfigDir 'agent-status'
$markerDir = Join-Path $statusRoot 'code'
$scriptPath = Join-Path $statusRoot 'code-signal.ps1'

function Read-Settings {
    if (-not (Test-Path -LiteralPath $settingsPath)) { return @{} }
    $raw = Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($raw)) { return @{} }
    # -AsHashtable is unavailable on Windows PowerShell 5.1, so convert the
    # PSCustomObject tree by hand to keep the file editable as nested maps.
    return ConvertTo-Hashtable (ConvertFrom-Json $raw)
}

function ConvertTo-Hashtable($obj) {
    if ($null -eq $obj) { return $null }
    if ($obj -is [System.Collections.IEnumerable] -and $obj -isnot [string]) {
        return @($obj | ForEach-Object { ConvertTo-Hashtable $_ })
    }
    if ($obj -is [PSCustomObject]) {
        $h = @{}
        foreach ($p in $obj.PSObject.Properties) {
            $h[$p.Name] = ConvertTo-Hashtable $p.Value
        }
        return $h
    }
    return $obj
}

# True for a handler this script installed. The tag lives in statusMessage;
# the command is also checked so an entry written by an older version, or one
# whose statusMessage the user cleared, is still recognised and replaced
# instead of being duplicated on every reinstall.
function Test-OwnedHandler($h) {
    if ($null -eq $h) { return $false }
    if ($h.statusMessage -and ($h.statusMessage -eq $marker)) { return $true }
    if ($h.command -and ($h.command -like '*code-signal.ps1*')) { return $true }
    if ($h.command -and ($h.command -like "*$marker*")) { return $true }
    return $false
}

# Drops every hook entry this script owns from one event's handler list.
#
# PowerShell unrolls arrays on return, so this deliberately does NOT wrap the
# result with the comma operator: every caller re-wraps with @() instead.
# Doing both nests the list one level deeper, which silently turns the event's
# handler array into an array-of-arrays that Claude Code cannot read.
function Remove-OwnedEntries($eventList) {
    if ($null -eq $eventList) { return @() }
    $kept = @()
    foreach ($group in @($eventList)) {
        $handlers = @()
        foreach ($h in @($group.hooks)) {
            if (Test-OwnedHandler $h) { continue }
            $handlers += $h
        }
        if ($handlers.Count -gt 0) {
            $group.hooks = @($handlers)
            $kept += $group
        }
    }
    return $kept
}

if ($Uninstall) {
    if (Test-Path -LiteralPath $settingsPath) {
        $s = Read-Settings
        if ($s.ContainsKey('hooks')) {
            foreach ($evt in @('PermissionRequest', 'SessionEnd')) {
                if ($s.hooks.ContainsKey($evt)) {
                    $kept = @(Remove-OwnedEntries $s.hooks[$evt])
                    if ($kept.Count -gt 0) { $s.hooks[$evt] = @($kept) }
                    else { $s.hooks.Remove($evt) }
                }
            }
            if ($s.hooks.Keys.Count -eq 0) { $s.Remove('hooks') }
        }
        $json = $s | ConvertTo-Json -Depth 20
        Set-Content -LiteralPath $settingsPath -Value $json -Encoding UTF8
        Write-Host "[OK] Hook entries removed from $settingsPath"
    }
    if (Test-Path -LiteralPath $statusRoot) {
        Remove-Item -LiteralPath $statusRoot -Recurse -Force
        Write-Host "[OK] Removed $statusRoot"
    }
    return
}

if (-not (Test-Path -LiteralPath $ConfigDir -PathType Container)) {
    throw "ConfigDir not found: $ConfigDir"
}

New-Item -ItemType Directory -Force -Path $markerDir | Out-Null

# The signal script. Kept tiny and free of output: a hook that writes to stdout
# feeds that text back to Claude, and a hook that throws shows up as an error
# in the session. Every failure path here is swallowed on purpose, because a
# taskbar light must never be able to disturb a Claude session.
$signal = @'
# AIUsageCodeLight - taskbar indicator signal. Generated by
# tools/install-code-status-hook.ps1; edits are overwritten on reinstall.
param([string]$Action, [string]$MarkerDir)
try {
    $raw = [Console]::In.ReadToEnd()
    if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }
    $payload = $raw | ConvertFrom-Json
    $sid = $payload.session_id
    if ([string]::IsNullOrWhiteSpace($sid)) { exit 0 }
    $file = Join-Path $MarkerDir ($sid + '.wait')
    if ($Action -eq 'wait') {
        if (-not (Test-Path -LiteralPath $MarkerDir)) {
            New-Item -ItemType Directory -Force -Path $MarkerDir | Out-Null
        }
        # Truncating write; the plugin only ever reads the timestamp.
        Set-Content -LiteralPath $file -Value '' -Encoding ASCII
    }
    else {
        if (Test-Path -LiteralPath $file) {
            Remove-Item -LiteralPath $file -Force
        }
    }
}
catch { }
exit 0
'@
Set-Content -LiteralPath $scriptPath -Value $signal -Encoding UTF8

$settings = Read-Settings
if (-not $settings.ContainsKey('hooks')) { $settings['hooks'] = @{} }

function Set-Hook($evt, $action) {
    $kept = @(Remove-OwnedEntries $settings.hooks[$evt])
    $cmd = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass " +
           "-File `"$scriptPath`" -Action $action -MarkerDir `"$markerDir`""
    $entry = @{
        hooks = @(
            @{
                type          = 'command'
                command       = $cmd
                timeout       = 10
                statusMessage = "$marker"
            }
        )
    }
    $settings.hooks[$evt] = @($kept) + @($entry)
}

Set-Hook 'PermissionRequest' 'wait'
# Cleanup only. Without it an abandoned prompt would leave a stale marker on
# disk; the plugin already ignores markers older than its idle timeout.
Set-Hook 'SessionEnd' 'clear'

# Back up before the first write so a bad edit is always recoverable.
if (Test-Path -LiteralPath $settingsPath) {
    $backup = "$settingsPath.aiusage-backup"
    if (-not (Test-Path -LiteralPath $backup)) {
        Copy-Item -LiteralPath $settingsPath -Destination $backup
        Write-Host "[OK] Backed up settings to $backup"
    }
}
else {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $settingsPath) | Out-Null
}

$out = $settings | ConvertTo-Json -Depth 20
# Fail before touching the real file if the result is not valid JSON: a broken
# settings.json would stop every Claude Code session from starting.
$null = ConvertFrom-Json $out
Set-Content -LiteralPath $settingsPath -Value $out -Encoding UTF8

Write-Host "[OK] Installed PermissionRequest hook -> $scriptPath"
Write-Host "[OK] Marker directory: $markerDir"
Write-Host "     Restart any open Claude Code session for the hook to load."
