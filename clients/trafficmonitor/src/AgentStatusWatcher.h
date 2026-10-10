#pragma once

// Runtime status of the two Claude Desktop surfaces, surfaced as two taskbar
// indicator lights.
//
//   Cowork  -> %APPDATA%\Claude\local-agent-mode-sessions\<acct>\<org>\
//                local_<uuid>.json                 (session metadata)
//                local_<uuid>\audit.jsonl          (permission + lifecycle)
//                local_<uuid>\.claude\sessions\*   (per-turn CLI child PID)
//   Code    -> %USERPROFILE%\.claude\sessions\*.json         (live registry)
//              <plugin config>\agent-status\code\<sid>.json (hook status)
//              %USERPROFILE%\.claude\projects\*\<sid>.jsonl  (transcript)
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
// Code does fire hooks, and the desktop Code tab shares
// ~/.claude/settings.json with the CLI, so a Code session reports its own
// state instead of having it guessed at: a small hook rewrites
// <plugin config>\agent-status\code\<session id>.json on every transition.
// That is the only way to see a permission prompt at all, since prompts never
// reach the transcript, and it is the better answer for working/idle too:
// the session itself is talking, rather than the plugin inferring from the
// last record that happened to be flushed.
//
// Without the hooks installed the watcher falls back to transcript parsing,
// which still separates working from idle but cannot see a pending prompt.
// The same fallback covers a status file that went stale, which is what a
// session killed mid-turn leaves behind.

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
    // Directory the Code hooks rewrite <session id>.json in. Empty disables
    // the hook path, leaving working/idle detection to the transcript and
    // giving up the "waiting for you" state.
    std::wstring statusDir;
    // How long an unfinished Cowork turn may go quiet before the light stops
    // calling it "working". A turn that is merely waiting on a slow tool
    // writes nothing to audit.jsonl in the meantime — the longest such gap
    // observed in real logs is about eight minutes — while a session whose app
    // was killed mid-turn never writes again at all. This is what keeps the
    // second case from holding the light yellow until the idle timeout.
    // The same window guards a Code status file whose session died before the
    // hook could correct it: a "working" record nothing has touched for this
    // long stops being believed, and the transcript decides instead.
    int busyGraceMinutes = 10;
};

// The last thing a Claude Code session reported about itself, read back from
// <statusDir>\<session id>.json. Deliberately tiny: the hook runs inside the
// session's own turn, so it writes two fields and exits.
struct HookStatus {
    // "working", "waiting" (on you) or "idle". Anything else is ignored
    // rather than guessed at, so a newer hook writing a state this build does
    // not know falls back to the transcript instead of lighting up wrongly.
    std::string state;
    // Unix milliseconds the hook stamped the record with. Callers substitute
    // the file's own mtime when the record carries no usable stamp.
    long long ts = 0;
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

    // Parses one hook status file. False when `json` is not a record this
    // understands, which is also what a half-written file looks like: the hook
    // truncates and rewrites, so a scan can catch it mid-write.
    static bool ParseHookStatus(std::string_view json, HookStatus* out);

    // The light one status record implies, given when the session transcript
    // was last written. Gray means "no usable opinion": an unknown state, or a
    // record the two staleness rules below no longer believe. It tells the
    // caller to fall back to the transcript, not to show a grey light.
    //
    // Both rules exist because the hooks fire on TRANSITIONS, so a record is
    // written once and then left alone:
    //
    //   "working" expires after `busyGraceMinutes`. A turn that ended without
    //   its Stop hook running (an interrupt, a hook that timed out) would
    //   otherwise hold the light yellow for as long as the session stays open.
    //
    //   "waiting" expires the moment the transcript moves past it. Answering a
    //   permission prompt is not itself a hook event; what it does is let the
    //   tool run, which appends to the transcript. Comparing the two stamps is
    //   therefore what clears the red light, and it is why no hook has to run
    //   on every single tool call to do it. A prompt nobody answers keeps the
    //   light red for as long as it stands, which is the point of it.
    static Light HookStateToLight(const HookStatus& status,
                                  long long transcriptMs,
                                  int busyGraceMinutes,
                                  long long nowMs);

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
