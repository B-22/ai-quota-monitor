<#
.SYNOPSIS
    Installs the Claude Code hooks that drive the AIUsage taskbar Code light.

.DESCRIPTION
    The light used to be inferred from the session transcript, which can only
    ever say "the last record I saw was still mid-turn". It cannot see a
    permission prompt at all -- prompts are never written to the transcript --
    and it lags behind the session by however long the transcript takes to be
    flushed.

    Claude Code fires hooks on every transition, and the desktop Code tab
    shares ~/.claude/settings.json with the CLI, so one set of hooks covers
    both. Each hook rewrites a two-field record:

        <ConfigDir>\agent-status\code\<session id>.json
        {"state":"working|waiting|idle","ts":<unix ms>}

    and the plugin reads that instead of guessing. Nothing but the state and a
    timestamp is written: no prompt text, no command line, no file path, no
    token counts.

        SessionStart      -> idle      (a new tab, nothing running yet)
        UserPromptSubmit  -> working   (you sent a message)
        PermissionRequest -> waiting   (Claude is blocked on your approval)
        Notification      -> waiting, unless the message is the 60-second
                             "Claude is waiting for your input" reminder,
                             which means idle. The other three messages this
                             build can send -- needs your permission / needs
                             your input / needs your approval for a review
                             artifact -- all mean the same thing to the light.
        Stop              -> idle      (the turn finished)
        SessionEnd        -> record deleted

    Deliberately NOT hooked: PreToolUse and PostToolUse. They fire on every
    single tool call, and each one costs a process launch inside the turn.
    Answering a permission prompt clears the light without them, because the
    plugin only believes a "waiting" record while the transcript has not moved
    past it -- and letting the tool run is exactly what moves the transcript.

    Cowork is deliberately not covered either. Cowork turns execute against a
    fresh per-session config directory, so hooks in ~/.claude/settings.json
    never fire there. The plugin reads Cowork's own audit.jsonl instead.

    Two of these hooks sit on the critical path of every single turn, so the
    signal is written as JScript run by cscript.exe: measured on this machine,
    one cscript launch costs ~50 ms against ~1070 ms for one powershell.exe.
    An equivalent PowerShell signal is installed alongside it and is used
    automatically if Windows Script Host has been disabled by policy.

    The script is idempotent: re-running it rewrites its own hook entries and
    leaves every other hook and setting untouched.

.PARAMETER ConfigDir
    TrafficMonitor directory holding AIUsage.ini. Status files and the signal
    scripts are created underneath it.

.PARAMETER ForcePowerShell
    Skip the cscript probe and install the PowerShell signal. Slower; for
    working around a broken Windows Script Host without editing this file.

.PARAMETER Uninstall
    Remove the hook entries and the status directory.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ConfigDir,
    [switch] $ForcePowerShell,
    [switch] $Uninstall
)

$ErrorActionPreference = 'Stop'

# Tag identifying entries this script owns, so uninstall and re-install never
# touch a hook the user added by hand.
$marker = 'AIUsageCodeLight'

# Every event this script has ever installed a handler for. Older versions
# used only PermissionRequest and SessionEnd; both are still listed so an
# upgrade cleans them out instead of leaving a second handler behind.
$ownedEvents = @(
    'SessionStart', 'UserPromptSubmit', 'PermissionRequest',
    'Notification', 'Stop', 'SessionEnd'
)

$settingsPath = Join-Path $env:USERPROFILE '.claude\settings.json'
$statusRoot = Join-Path $ConfigDir 'agent-status'
$statusDir = Join-Path $statusRoot 'code'
$jsPath = Join-Path $statusRoot 'code-signal.js'
$psPath = Join-Path $statusRoot 'code-signal.ps1'

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
    if ($h.command -and ($h.command -like '*code-signal.js*')) { return $true }
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
            foreach ($evt in $ownedEvents) {
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

New-Item -ItemType Directory -Force -Path $statusDir | Out-Null

# Records left by a session that is long gone, and the .wait markers the
# pre-JSON version of this script used. The plugin ignores both, but a stale
# file is one more thing to wonder about while debugging.
Get-ChildItem -LiteralPath $statusDir -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Extension -in @('.json', '.wait') } |
    Remove-Item -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------------------
# The signal, twice: once as JScript and once as PowerShell.
#
# Both run INSIDE the turn, so both are written for start-up cost rather than
# elegance: a regex over the raw payload instead of a JSON parse, and no output
# at all. A hook that writes to stdout feeds that text back to Claude, and a
# hook that throws surfaces as an error in the session, so every failure path
# is swallowed on purpose. A taskbar light must never be able to disturb a
# Claude session.
# ---------------------------------------------------------------------------

$js = @'
// AIUsageCodeLight - taskbar indicator signal. Generated by
// tools/install-code-status-hook.ps1; edits are overwritten on reinstall.
// Run as: cscript //nologo //E:jscript code-signal.js <action> <statusDir>
try {
    var args = WScript.Arguments;
    if (args.length < 2) { WScript.Quit(0); }
    var action = args(0);
    var dir = args(1);

    // Claude always pipes the hook payload in. With no redirected stdin this
    // throws rather than blocking, which is the right answer either way.
    var raw = WScript.StdIn.ReadAll();
    var m = /"session_id"\s*:\s*"([^"]{1,200})"/.exec(raw);
    if (!m) { WScript.Quit(0); }
    var sid = m[1];
    // The id becomes a file name, and it arrives from outside this script.
    if (!/^[A-Za-z0-9._-]+$/.test(sid)) { WScript.Quit(0); }

    var fso = new ActiveXObject("Scripting.FileSystemObject");
    var file = fso.BuildPath(dir, sid + ".json");

    if (action === "clear") {
        if (fso.FileExists(file)) { fso.DeleteFile(file, true); }
        WScript.Quit(0);
    }

    var state = action;
    if (action === "notify") {
        // Notification covers both "Claude needs your permission / input /
        // approval", which block the turn, and the 60-second idle reminder,
        // which does not. Only the reminder names the input it waits for.
        state = /"message"\s*:\s*"[^"]*waiting for your input/.test(raw)
            ? "idle" : "waiting";
    }

    if (!fso.FolderExists(dir)) { fso.CreateFolder(dir); }
    var f = fso.CreateTextFile(file, true, false);
    f.Write('{"state":"' + state + '","ts":' + new Date().getTime() + '}');
    f.Close();
}
catch (e) { }
WScript.Quit(0);
'@
Set-Content -LiteralPath $jsPath -Value $js -Encoding ASCII

$signal = @'
# AIUsageCodeLight - taskbar indicator signal. Generated by
# tools/install-code-status-hook.ps1; edits are overwritten on reinstall.
# The slow fallback for machines where Windows Script Host is unavailable.
param([string]$Action, [string]$Dir)
try {
    $raw = [Console]::In.ReadToEnd()
    if ($raw -notmatch '"session_id"\s*:\s*"([^"]{1,200})"') { exit 0 }
    $sid = $Matches[1]
    # The id becomes a file name, and it arrives from outside this script.
    if ($sid -notmatch '^[A-Za-z0-9._-]+$') { exit 0 }
    $file = Join-Path $Dir ($sid + '.json')

    if ($Action -eq 'clear') {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
        exit 0
    }

    $state = $Action
    if ($Action -eq 'notify') {
        if ($raw -match '"message"\s*:\s*"[^"]*waiting for your input') {
            $state = 'idle'
        }
        else { $state = 'waiting' }
    }

    if (-not (Test-Path -LiteralPath $Dir)) {
        New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    }
    $ts = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    [IO.File]::WriteAllText($file, '{"state":"' + $state + '","ts":' + $ts + '}')
}
catch { }
exit 0
'@
Set-Content -LiteralPath $psPath -Value $signal -Encoding UTF8

# ---- Which of the two actually works here? --------------------------------
# Windows Script Host can be switched off by policy, and it fails silently
# from a hook's point of view: the light would just never move. So the fast
# path is proven once, here, against a throwaway session id.
$useJs = $false
if (-not $ForcePowerShell) {
    $cscript = Join-Path $env:SystemRoot 'System32\cscript.exe'
    if (Test-Path -LiteralPath $cscript) {
        $probeId = 'aiusage-probe'
        $probeFile = Join-Path $statusDir "$probeId.json"
        Remove-Item -LiteralPath $probeFile -Force -ErrorAction SilentlyContinue
        try {
            $payload = '{"session_id":"' + $probeId + '","hook_event_name":"Probe"}'
            $payload | & $cscript //nologo //E:jscript $jsPath 'idle' $statusDir |
                Out-Null
            $useJs = Test-Path -LiteralPath $probeFile
        }
        catch { $useJs = $false }
        Remove-Item -LiteralPath $probeFile -Force -ErrorAction SilentlyContinue
    }
}

if ($useJs) {
    $runner = "cscript //nologo //E:jscript `"$jsPath`""
    Write-Host '[OK] Using the cscript signal (about 50 ms per hook)'
}
else {
    $runner = "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass " +
              "-File `"$psPath`" -Action"
    Write-Warning ('Windows Script Host is unavailable, falling back to the ' +
                   'PowerShell signal (about 1 s per hook).')
}

$settings = Read-Settings
if (-not $settings.ContainsKey('hooks')) { $settings['hooks'] = @{} }

function Set-Hook($evt, $action) {
    $kept = @(Remove-OwnedEntries $settings.hooks[$evt])
    $cmd = "$runner $action `"$statusDir`""
    $entry = @{
        hooks = @(
            @{
                type          = 'command'
                # Short, because the whole point is that this runs inside the
                # turn. Five seconds is already an eternity for one write.
                timeout       = 5
                command       = $cmd
                statusMessage = "$marker"
            }
        )
    }
    $settings.hooks[$evt] = @($kept) + @($entry)
}

Set-Hook 'SessionStart'      'idle'
Set-Hook 'UserPromptSubmit'  'working'
Set-Hook 'PermissionRequest' 'waiting'
Set-Hook 'Notification'      'notify'
Set-Hook 'Stop'              'idle'
Set-Hook 'SessionEnd'        'clear'

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

Write-Host "[OK] Installed $($ownedEvents.Count) hooks"
Write-Host "[OK] Status directory: $statusDir"
Write-Host "     Restart any open Claude Code session for the hooks to load."
