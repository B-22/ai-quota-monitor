#pragma once

// Runtime status of the two Claude Desktop surfaces, surfaced as two taskbar
// indicator lights.
//
//   Cowork  -> %APPDATA%\Claude\local-agent-mode-sessions\<acct>\<org>\
//                local_<uuid>.json                 (session metadata)
//                local_<uuid>\audit.jsonl          (permission + lifecycle)
//                local_<uuid>\.claude\sessions\*   (per-turn CLI child PID)
//   Code    -> %USERPROFILE%\.claude\sessions\*.json      (live registry)
//              %USERPROFILE%\.claude\projects\*\<sid>.jsonl (transcript)
//              <plugin config>\agent-status\code\*         (hook markers)
//
// Cowork carries no usable hook surface: every Cowork turn runs against a
// fresh per-session config directory, so the global ~/.claude/settings.json
// hooks never fire there. Its state therefore comes entirely from audit.jsonl,
// which records permission_request/permission_response pairs and closes every
// finished turn with a `result` record.
//
// audit.jsonl is deliberately the PRIMARY source for "working", not the CLI
// child process that serves the turn. Reading a process requires OpenProcess,
// and the taskbar host runs elevated while the Claude CLI does not: on this
// setup the handle never arrives, so a process-only rule leaves the light
// stuck on green for the whole turn. Reading a file has no such asymmetry —
// it is the very same file the permission check already reads successfully.
//
// Code does fire hooks, so its "waiting for you" state comes from marker files
// dropped by a PermissionRequest hook. When the hooks are not installed the
// watcher degrades gracefully to transcript parsing, which still distinguishes
// working from idle but cannot see a pending permission prompt.

#include <string>
#include <string_view>
#include <vector>

namespace agentstatus {

// Ordered by urgency. Aggregation always reports the highest-ranked state
// present in the group, so one session waiting on the user is never masked by
// another session that happens to be busy.
enum class Light {
    Gray = 0,    // no active session
    Green = 1,   // idle, waiting for your instruction
    Yellow = 2,  // working
    Red = 3,     // blocked on you (permission prompt / question)
};

struct SessionInfo {
    // Stable identity used to drop duplicates. One physical Cowork session can
    // be reachable through more than one root: on Store/MSIX installs the
    // package's LocalCache\Roaming\Claude is a virtualized view of the very
    // same %APPDATA%\Claude directory, so a plain scan of every root sees each
    // session twice and would double the count shown on the taskbar.
    std::wstring key;
    std::wstring title;
    std::wstring cwd;
    Light state = Light::Gray;
    long long lastActivityMs = 0;  // unix epoch milliseconds
};

// Aggregate for one surface (Cowork or Code).
struct GroupStatus {
    Light light = Light::Gray;
    int count = 0;   // sessions sitting in `light`
    int total = 0;   // active sessions overall
    long long lastActivityMs = 0;
    std::vector<SessionInfo> sessions;

    bool Empty() const { return total == 0; }
};

struct WatcherConfig {
    // A session older than this stops counting as active. Also drives the
    // Code light's auto-hide.
    int idleTimeoutMinutes = 30;
    // Code light collapses to zero width once it has been Gray this long.
    // Cowork never hides; it shows a grey dot instead.
    bool hideCodeWhenIdle = true;
    // Directory the PermissionRequest hook touches <sessionId>.wait in. Empty
    // disables the marker path, leaving working/idle detection intact but
    // giving up the "waiting for you" state.
    std::wstring markerDir;
    // How long an unfinished Cowork turn may go quiet before the light stops
    // calling it "working". A turn that is merely waiting on a slow tool
    // writes nothing to audit.jsonl in the meantime — the longest such gap
    // observed in real logs is about eight minutes — while a session whose app
    // was killed mid-turn never writes again at all. This is what keeps the
    // second case from holding the light yellow until the idle timeout.
    int busyGraceMinutes = 10;
};

// Snapshot of both surfaces. Cheap to copy; the UI thread holds one of these
// and never touches the filesystem itself.
struct StatusSnapshot {
    GroupStatus cowork;
    GroupStatus code;
    bool valid = false;
};

// Scans the on-disk session state. Every method is filesystem-only and
// allocates nothing that outlives the call, so it is safe to run on a
// background thread while the UI thread reads the last published snapshot.
class AgentStatusWatcher final {
public:
    // Full rescan. Typical cost is a directory enumeration plus a handful of
    // small reads: sessions whose metadata file has not been touched inside
    // the idle window are rejected on mtime alone and never parsed.
    static StatusSnapshot Scan(const WatcherConfig& cfg);

    // Roots that may hold Cowork sessions: classic %APPDATA%\Claude, the
    // third-party build under %LOCALAPPDATA%\Claude-3p, and the Store/MSIX
    // package, whose %APPDATA%\Claude is virtualized and does not exist at
    // the normal path.
    static std::vector<std::wstring> CoworkRoots();

    // True when `pid` is alive AND its creation time matches `procStart`
    // (a FILETIME rendered as a decimal string). The second half matters:
    // Windows recycles PIDs aggressively, and ~/.claude/sessions keeps stale
    // entries for sessions that exited without cleanup.
    static bool ProcessAlive(unsigned long pid, const std::string& procStart);

    // Collapses per-session states into a single light. The most urgent state
    // present wins and `count` reports how many sessions share it, so a
    // finished session never downgrades the light while another still runs.
    // Exposed so the rule can be tested without touching the filesystem.
    static GroupStatus Aggregate(std::vector<SessionInfo> sessions);

    // True when `auditTail` (raw audit.jsonl text) ends with at least one
    // permission_request whose matching permission_response never arrived.
    // Request and response share a uuid, so the pairing is exact.
    static bool AuditHasPendingPermission(std::string_view auditTail);

    // True when the newest record in `auditTail` is not the `result` that
    // closes a turn, i.e. Cowork is still working on the last message.
    //
    // Only the record's OWN top-level "type" counts. Every audit line embeds
    // the assistant message it describes, so the key appears several times per
    // line and a substring search would read the wrong one — including from
    // text the model itself wrote. A half-written trailing line (the file is
    // appended to while this runs) is skipped rather than guessed at.
    static bool AuditIndicatesBusy(std::string_view auditTail);

    // True when the newest assistant record in `transcriptTail` closed with a
    // stop_reason other than "end_turn", i.e. the turn is still in flight.
    static bool TranscriptIndicatesBusy(std::string_view transcriptTail);
};

}  // namespace agentstatus
