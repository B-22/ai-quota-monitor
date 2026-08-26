#include "AgentStatusWatcher.h"

#include <Windows.h>

#include <algorithm>
#include <cstdlib>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

#include "JsonLite.h"

namespace agentstatus {
namespace {

constexpr long long kUnixEpochInFileTime = 116444736000000000LL;
// Cowork audit logs reach tens of megabytes. A pending permission prompt is by
// construction the newest thing in the file, so only the tail is ever read.
constexpr size_t kAuditTailBytes = 512 * 1024;
constexpr size_t kTranscriptTailBytes = 256 * 1024;
constexpr size_t kMetaMaxBytes = 512 * 1024;

long long FileTimeToUnixMs(const FILETIME& ft) {
    ULARGE_INTEGER v{};
    v.LowPart = ft.dwLowDateTime;
    v.HighPart = ft.dwHighDateTime;
    const long long raw = static_cast<long long>(v.QuadPart);
    if (raw <= kUnixEpochInFileTime) return 0;
    return (raw - kUnixEpochInFileTime) / 10000LL;
}

long long NowUnixMs() {
    FILETIME ft{};
    ::GetSystemTimeAsFileTime(&ft);
    return FileTimeToUnixMs(ft);
}

std::wstring EnvVar(const wchar_t* name) {
    wchar_t buf[MAX_PATH * 2];
    const DWORD n = ::GetEnvironmentVariableW(name, buf, ARRAYSIZE(buf));
    if (n == 0 || n >= ARRAYSIZE(buf)) return {};
    return std::wstring(buf, n);
}

std::wstring Utf8ToWide(std::string_view s) {
    if (s.empty()) return {};
    const int n = ::MultiByteToWideChar(
        CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    if (n <= 0) return {};
    std::wstring out(static_cast<size_t>(n), L'\0');
    ::MultiByteToWideChar(
        CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), n);
    return out;
}

// Reads at most `maxBytes` from the END of a file. Returns an empty string on
// any failure; a missing or locked session file just means "no information",
// never an error the user should see.
std::string ReadTail(const std::wstring& path, size_t maxBytes) {
    HANDLE h = ::CreateFileW(
        path.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
        nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return {};

    LARGE_INTEGER size{};
    if (!::GetFileSizeEx(h, &size) || size.QuadPart <= 0) {
        ::CloseHandle(h);
        return {};
    }
    const long long total = size.QuadPart;
    const long long want =
        std::min<long long>(total, static_cast<long long>(maxBytes));
    LARGE_INTEGER off{};
    off.QuadPart = total - want;
    if (off.QuadPart > 0) {
        ::SetFilePointerEx(h, off, nullptr, FILE_BEGIN);
    }

    std::string data(static_cast<size_t>(want), '\0');
    DWORD read = 0;
    const BOOL ok =
        ::ReadFile(h, data.data(), static_cast<DWORD>(want), &read, nullptr);
    ::CloseHandle(h);
    if (!ok) return {};
    data.resize(read);
    return data;
}

bool FileInfo(const std::wstring& path, long long* mtimeMs) {
    WIN32_FILE_ATTRIBUTE_DATA fad{};
    if (!::GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &fad)) {
        return false;
    }
    if (mtimeMs) *mtimeMs = FileTimeToUnixMs(fad.ftLastWriteTime);
    return true;
}

bool DirExists(const std::wstring& path) {
    const DWORD a = ::GetFileAttributesW(path.c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

std::wstring Join(const std::wstring& a, const std::wstring& b) {
    if (a.empty()) return b;
    if (a.back() == L'\\' || a.back() == L'/') return a + b;
    return a + L"\\" + b;
}

struct DirEntry {
    std::wstring name;
    long long mtimeMs = 0;
};

// Enumerates entries matching `pattern` inside `dir`. `wantDirs` selects
// directories, otherwise files. "." and ".." are never returned.
//
// The write time comes straight out of the enumeration record. Re-stating each
// entry afterwards would cost an extra syscall per file, and this walks every
// Cowork session ever created (hundreds of them) on every pass.
std::vector<DirEntry> ListDirEx(const std::wstring& dir,
                                const wchar_t* pattern,
                                bool wantDirs) {
    std::vector<DirEntry> out;
    WIN32_FIND_DATAW fd{};
    HANDLE h = ::FindFirstFileExW(
        Join(dir, pattern).c_str(), FindExInfoBasic, &fd,
        wantDirs ? FindExSearchLimitToDirectories : FindExSearchNameMatch,
        nullptr, 0);
    if (h == INVALID_HANDLE_VALUE) return out;
    do {
        const bool isDir = (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
        if (isDir != wantDirs) continue;
        if (fd.cFileName[0] == L'.'
            && (fd.cFileName[1] == L'\0'
                || (fd.cFileName[1] == L'.' && fd.cFileName[2] == L'\0'))) {
            continue;
        }
        out.push_back(
            DirEntry{fd.cFileName, FileTimeToUnixMs(fd.ftLastWriteTime)});
    } while (::FindNextFileW(h, &fd));
    ::FindClose(h);
    return out;
}

// Name-only view for the callers that do not care about timestamps.
std::vector<std::wstring> ListDir(const std::wstring& dir,
                                  const wchar_t* pattern,
                                  bool wantDirs) {
    std::vector<std::wstring> out;
    for (auto& e : ListDirEx(dir, pattern, wantDirs)) {
        out.push_back(std::move(e.name));
    }
    return out;
}

// Pulls a string field out of a raw JSON line without building a full parse
// tree. Audit records embed entire tool payloads; parsing them just to read
// two fields would dominate the scan cost.
std::string_view RawStringField(std::string_view line,
                                std::string_view key,
                                size_t from = 0) {
    std::string needle;
    needle.reserve(key.size() + 4);
    needle += '"';
    needle += key;
    needle += "\":\"";
    const size_t at = line.find(needle, from);
    if (at == std::string_view::npos) return {};
    const size_t start = at + needle.size();
    size_t end = start;
    while (end < line.size() && line[end] != '"') {
        if (line[end] == '\\') ++end;  // skip the escaped character
        ++end;
    }
    if (end > line.size()) return {};
    return line.substr(start, end - start);
}

// Reads a TOP-LEVEL string field out of one JSON line, stepping over nested
// objects, arrays and strings instead of searching the raw text.
//
// RawStringField above is enough for keys that only ever appear once, but not
// for "type": every audit record embeds the whole assistant message it
// describes, so the key occurs several times per line — and the assistant's
// own text can contain anything at all, quoted JSON included. Only the
// outermost occurrence identifies the record.
std::string_view TopLevelStringField(std::string_view line,
                                     std::string_view key) {
    size_t i = 0;
    auto skipWs = [&] {
        while (i < line.size() && (line[i] == ' ' || line[i] == '\t'
                                   || line[i] == '\r' || line[i] == '\n')) {
            ++i;
        }
    };
    // Consumes the string starting at the opening quote and returns its body.
    auto readString = [&]() -> std::string_view {
        if (i >= line.size() || line[i] != '"') return {};
        const size_t start = ++i;
        while (i < line.size() && line[i] != '"') {
            if (line[i] == '\\') ++i;
            ++i;
        }
        if (i >= line.size()) return {};
        const std::string_view body = line.substr(start, i - start);
        ++i;  // closing quote
        return body;
    };

    skipWs();
    if (i >= line.size() || line[i] != '{') return {};
    ++i;
    while (i < line.size()) {
        skipWs();
        if (i < line.size() && line[i] == '}') return {};
        const size_t keyStart = i;
        const std::string_view name = readString();
        if (i == keyStart) return {};  // not a key: malformed line
        skipWs();
        if (i >= line.size() || line[i] != ':') return {};
        ++i;
        skipWs();
        if (i >= line.size()) return {};
        if (line[i] == '"') {
            const std::string_view value = readString();
            if (name == key) return value;
        } else if (line[i] == '{' || line[i] == '[') {
            int depth = 0;
            while (i < line.size()) {
                const char c = line[i];
                if (c == '"') {
                    readString();
                    continue;
                }
                if (c == '{' || c == '[') ++depth;
                if (c == '}' || c == ']') --depth;
                ++i;
                if (depth == 0) break;
            }
        } else {
            while (i < line.size() && line[i] != ',' && line[i] != '}') ++i;
        }
        skipWs();
        if (i < line.size() && line[i] == ',') ++i;
        else return {};
    }
    return {};
}

void ForEachLine(std::string_view text,
                 const std::function<void(std::string_view)>& fn) {
    size_t pos = 0;
    while (pos < text.size()) {
        size_t nl = text.find('\n', pos);
        if (nl == std::string_view::npos) nl = text.size();
        size_t end = nl;
        if (end > pos && text[end - 1] == '\r') --end;
        if (end > pos) fn(text.substr(pos, end - pos));
        pos = nl + 1;
    }
}

Light Max(Light a, Light b) {
    return static_cast<int>(a) >= static_cast<int>(b) ? a : b;
}

// Collapses per-session states into one light: the most urgent state wins, and
// `count` reports how many sessions share it. A session that finished while
// another still runs therefore leaves the light yellow, which is what the user
// needs to see ("not done yet"), and a permission prompt anywhere turns it red
// even while other sessions keep working.
GroupStatus AggregateImpl(std::vector<SessionInfo> sessions) {
    // Drop sessions already seen under a different root (MSIX virtualization
    // exposes the same directory twice). Sessions with an empty key are never
    // deduplicated, so callers that have no stable identity keep every entry.
    {
        std::vector<SessionInfo> unique;
        unique.reserve(sessions.size());
        for (auto& s : sessions) {
            if (!s.key.empty()) {
                const bool dup = std::any_of(
                    unique.begin(), unique.end(),
                    [&](const SessionInfo& o) { return o.key == s.key; });
                if (dup) continue;
            }
            unique.push_back(std::move(s));
        }
        sessions = std::move(unique);
    }

    GroupStatus g;
    g.total = static_cast<int>(sessions.size());
    for (const auto& s : sessions) {
        g.light = Max(g.light, s.state);
        g.lastActivityMs = std::max(g.lastActivityMs, s.lastActivityMs);
    }
    if (g.total > 0 && g.light == Light::Gray) g.light = Light::Green;
    for (const auto& s : sessions) {
        if (s.state == g.light) ++g.count;
    }
    if (g.count == 0) g.count = g.total;
    std::sort(sessions.begin(), sessions.end(),
              [](const SessionInfo& a, const SessionInfo& b) {
                  if (a.state != b.state) {
                      return static_cast<int>(a.state) > static_cast<int>(b.state);
                  }
                  return a.lastActivityMs > b.lastActivityMs;
              });
    g.sessions = std::move(sessions);
    return g;
}

// ---------------------------------------------------------------------------
// Cowork
// ---------------------------------------------------------------------------

// A Cowork turn runs as a short-lived Claude CLI child process registered
// under the session's private .claude/sessions directory. A live PID there
// means "this session is executing right now"; no PID means the turn ended.
//
// This is a CORROBORATING signal only — see AuditIndicatesBusy for why it
// cannot be the primary one. Confirming the process needs an OpenProcess
// handle, and the elevated taskbar host does not get one for the Claude CLI,
// so inside TrafficMonitor this function returns false for the whole turn.
bool CoworkProcessRunning(const std::wstring& sessionDir) {
    const std::wstring reg = Join(Join(sessionDir, L".claude"), L"sessions");
    for (const auto& name : ListDir(reg, L"*.json", false)) {
        const std::string body = ReadTail(Join(reg, name), 64 * 1024);
        if (body.empty()) continue;
        jsonlite::Parser parser(body);
        auto root = parser.Parse();
        if (!root) continue;
        const auto* pidV = root->Find("pid");
        if (!pidV) continue;
        const auto pid = pidV->AsInt();
        if (!pid || *pid <= 0) continue;
        std::string procStart;
        if (const auto* ps = root->Find("procStart")) {
            if (auto s = ps->AsString()) procStart.assign(*s);
        }
        if (AgentStatusWatcher::ProcessAlive(
                static_cast<unsigned long>(*pid), procStart)) {
            return true;
        }
    }
    return false;
}

bool AuditPendingImpl(std::string_view tail) {
    if (tail.empty()) return false;

    std::vector<std::string> pending;
    ForEachLine(tail, [&](std::string_view line) {
        const size_t at = line.find("\"permission_re");
        if (at == std::string_view::npos) return;
        const std::string_view subtype = RawStringField(line, "subtype");
        if (subtype != "permission_request" && subtype != "permission_response") {
            return;
        }
        // Take the uuid that follows the subtype marker so a uuid nested in an
        // embedded tool payload cannot be mistaken for the record's own.
        const std::string_view uuid = RawStringField(line, "uuid", at);
        if (uuid.empty()) return;
        const std::string key(uuid);
        if (subtype == "permission_request") {
            pending.push_back(key);
        } else {
            pending.erase(std::remove(pending.begin(), pending.end(), key),
                          pending.end());
        }
    });
    return !pending.empty();
}

// Every finished Cowork turn is closed by a top-level `result` record, so the
// newest record identifies the state: anything else means the turn is still in
// flight. Checked against every audit log on this machine, 321 of the 348
// idle sessions end in `result`; the remainder are sessions whose app was
// killed mid-turn, which the caller's grace window handles.
bool AuditBusyImpl(std::string_view tail) {
    if (tail.empty()) return false;

    std::vector<std::string_view> lines;
    ForEachLine(tail, [&](std::string_view line) { lines.push_back(line); });
    // Newest first. The last line can be half written — the file is appended
    // to while this reads it — and a record with no top-level "type" tells us
    // nothing, so both are skipped rather than guessed at.
    for (auto it = lines.rbegin(); it != lines.rend(); ++it) {
        const std::string_view type = TopLevelStringField(*it, "type");
        if (type.empty()) continue;
        return type != "result";
    }
    return false;
}

// Red beats yellow beats green. The permission check and the busy check read
// the same audit tail, so it is fetched once per session.
Light CoworkStateFor(const std::wstring& sessionDir,
                     const WatcherConfig& cfg,
                     long long nowMs) {
    const std::wstring auditPath = Join(sessionDir, L"audit.jsonl");
    const std::string tail = ReadTail(auditPath, kAuditTailBytes);

    if (AgentStatusWatcher::AuditHasPendingPermission(tail)) return Light::Red;

    // A live CLI child is proof on its own; the elevated taskbar host cannot
    // see one, which is exactly why the audit log carries the verdict there.
    if (CoworkProcessRunning(sessionDir)) return Light::Yellow;

    if (AgentStatusWatcher::AuditIndicatesBusy(tail)) {
        // An unfinished turn that has stopped writing is a session whose app
        // went away, not one that is still working.
        long long auditMtime = 0;
        FileInfo(auditPath, &auditMtime);
        const long long grace =
            static_cast<long long>(std::max(1, cfg.busyGraceMinutes)) * 60'000LL;
        if (auditMtime > 0 && nowMs - auditMtime <= grace) return Light::Yellow;
    }
    return Light::Green;
}

GroupStatus ScanCowork(const WatcherConfig& cfg, long long nowMs) {
    const long long cutoff =
        nowMs - static_cast<long long>(cfg.idleTimeoutMinutes) * 60'000LL;
    std::vector<SessionInfo> out;

    for (const auto& root : AgentStatusWatcher::CoworkRoots()) {
        for (const auto& acct : ListDir(root, L"*", true)) {
            const std::wstring acctDir = Join(root, acct);
            for (const auto& org : ListDir(acctDir, L"*", true)) {
                const std::wstring orgDir = Join(acctDir, org);
                for (const auto& entry :
                     ListDirEx(orgDir, L"local_*.json", false)) {
                    // Reject on the write time the enumeration already
                    // reported. With hundreds of archived sessions on disk this
                    // filter is what keeps a scan cheap: everything past it
                    // opens no file at all.
                    const long long mtime = entry.mtimeMs;
                    if (mtime < cutoff) continue;
                    const std::wstring& metaName = entry.name;
                    const std::wstring metaPath = Join(orgDir, metaName);

                    const std::string body = ReadTail(metaPath, kMetaMaxBytes);
                    if (body.empty()) continue;
                    jsonlite::Parser parser(body);
                    auto meta = parser.Parse();
                    if (!meta) continue;
                    if (const auto* ar = meta->Find("isArchived")) {
                        if (ar->AsBool().value_or(false)) continue;
                    }

                    long long last = 0;
                    if (const auto* la = meta->Find("lastActivityAt")) {
                        last = static_cast<long long>(la->AsNumber().value_or(0));
                    }
                    if (last <= 0) {
                        if (const auto* ca = meta->Find("createdAt")) {
                            last =
                                static_cast<long long>(ca->AsNumber().value_or(0));
                        }
                    }
                    if (last <= 0) last = mtime;
                    if (last < cutoff) continue;

                    SessionInfo s;
                    // metaName is local_<uuid>.json; the uuid is unique per
                    // session, so the file name identifies it across roots.
                    s.key = metaName;
                    s.lastActivityMs = last;
                    if (const auto* t = meta->Find("title")) {
                        if (auto v = t->AsString()) s.title = Utf8ToWide(*v);
                    }
                    if (s.title.empty()) {
                        s.title = metaName.substr(
                            0, metaName.size() > 5 ? metaName.size() - 5 : 0);
                    }
                    if (const auto* f = meta->Find("userSelectedFolders")) {
                        if (const auto* arr = f->AsArray()) {
                            if (!arr->empty()) {
                                if (auto v = (*arr)[0].AsString()) {
                                    s.cwd = Utf8ToWide(*v);
                                }
                            }
                        }
                    }

                    // local_<uuid>.json -> local_<uuid>
                    std::wstring dirName = metaName;
                    if (dirName.size() > 5) dirName.resize(dirName.size() - 5);
                    const std::wstring sessionDir = Join(orgDir, dirName);

                    s.state = CoworkStateFor(sessionDir, cfg, nowMs);
                    out.push_back(std::move(s));
                }
            }
        }
    }
    return AgentStatusWatcher::Aggregate(std::move(out));
}

// ---------------------------------------------------------------------------
// Code
// ---------------------------------------------------------------------------

std::wstring ClaudeHome() {
    std::wstring p = EnvVar(L"USERPROFILE");
    if (p.empty()) return {};
    return Join(p, L".claude");
}

// Locates <home>\projects\<slug>\<sessionId>.jsonl. The slug is derived from
// the working directory, so the file is found by scanning rather than by
// reconstructing the encoding rule.
std::wstring FindTranscript(const std::wstring& home,
                            const std::wstring& sessionId) {
    const std::wstring projects = Join(home, L"projects");
    const std::wstring leaf = sessionId + L".jsonl";
    for (const auto& slug : ListDir(projects, L"*", true)) {
        const std::wstring candidate = Join(Join(projects, slug), leaf);
        if (::GetFileAttributesW(candidate.c_str()) != INVALID_FILE_ATTRIBUTES) {
            return candidate;
        }
    }
    return {};
}

// Fallback busy/idle detection when hook markers are unavailable. The last
// assistant record's stop_reason is decisive: "end_turn" means the turn closed
// and Claude is waiting for input; anything else (notably "tool_use") means
// the turn is still in flight.
bool TranscriptBusy(const std::wstring& transcript) {
    return AgentStatusWatcher::TranscriptIndicatesBusy(
        ReadTail(transcript, kTranscriptTailBytes));
}

bool TranscriptBusyImpl(std::string_view tail) {
    if (tail.empty()) return false;
    std::vector<std::string_view> lines;
    ForEachLine(tail, [&](std::string_view l) { lines.push_back(l); });
    for (auto it = lines.rbegin(); it != lines.rend(); ++it) {
        const std::string_view reason = RawStringField(*it, "stop_reason");
        if (reason.empty()) continue;
        return reason != "end_turn";
    }
    return false;
}

GroupStatus ScanCode(const WatcherConfig& cfg, long long nowMs) {
    const long long cutoff =
        nowMs - static_cast<long long>(cfg.idleTimeoutMinutes) * 60'000LL;
    const std::wstring home = ClaudeHome();
    if (home.empty()) return {};

    const std::wstring reg = Join(home, L"sessions");
    std::vector<SessionInfo> out;

    for (const auto& name : ListDir(reg, L"*.json", false)) {
        const std::string body = ReadTail(Join(reg, name), 64 * 1024);
        if (body.empty()) continue;
        jsonlite::Parser parser(body);
        auto root = parser.Parse();
        if (!root) continue;

        // Only the desktop Code tab. CLI and SSH sessions register here too
        // but belong to neither light.
        std::string entry;
        if (const auto* e = root->Find("entrypoint")) {
            if (auto v = e->AsString()) entry.assign(*v);
        }
        if (entry != "claude-desktop") continue;

        const auto* pidV = root->Find("pid");
        if (!pidV) continue;
        const auto pid = pidV->AsInt();
        if (!pid || *pid <= 0) continue;
        std::string procStart;
        if (const auto* ps = root->Find("procStart")) {
            if (auto v = ps->AsString()) procStart.assign(*v);
        }
        // The registry keeps entries for sessions that exited without cleanup,
        // so liveness decides membership, not the file's presence.
        if (!AgentStatusWatcher::ProcessAlive(
                static_cast<unsigned long>(*pid), procStart)) {
            continue;
        }

        std::string sessionId;
        if (const auto* s = root->Find("sessionId")) {
            if (auto v = s->AsString()) sessionId.assign(*v);
        }
        if (sessionId.empty()) continue;

        SessionInfo info;
        info.key = Utf8ToWide(sessionId);
        if (const auto* n = root->Find("name")) {
            if (auto v = n->AsString()) info.title = Utf8ToWide(*v);
        }
        if (const auto* c = root->Find("cwd")) {
            if (auto v = c->AsString()) info.cwd = Utf8ToWide(*v);
        }
        if (info.title.empty()) info.title = Utf8ToWide(sessionId);

        const std::wstring wSessionId = Utf8ToWide(sessionId);
        const std::wstring transcript = FindTranscript(home, wSessionId);
        long long last = 0;
        if (!transcript.empty()) FileInfo(transcript, &last);
        if (last <= 0) {
            if (const auto* st = root->Find("startedAt")) {
                last = static_cast<long long>(st->AsNumber().value_or(0));
            }
        }
        info.lastActivityMs = last;
        if (last > 0 && last < cutoff) continue;

        // A permission prompt never reaches the transcript, so the only way to
        // see one is the PermissionRequest hook, which touches
        // <markerDir>\<sessionId>.wait.
        //
        // The prompt counts as open only while nothing newer has been appended
        // to the transcript. Answering it lets the tool run, which advances the
        // transcript past the marker and clears the light on its own. That is
        // why a single hook suffices: pairing it with a PostToolUse hook would
        // have cost a process launch on every tool call just to delete a file.
        bool waiting = false;
        if (!cfg.markerDir.empty()) {
            long long markerMs = 0;
            if (FileInfo(Join(cfg.markerDir, wSessionId + L".wait"), &markerMs)
                && markerMs > 0 && markerMs >= cutoff && markerMs >= last) {
                waiting = true;
            }
        }

        if (waiting) {
            info.state = Light::Red;
        } else if (!transcript.empty() && TranscriptBusy(transcript)) {
            info.state = Light::Yellow;
        } else {
            info.state = Light::Green;
        }
        out.push_back(std::move(info));
    }
    return AgentStatusWatcher::Aggregate(std::move(out));
}

}  // namespace

std::vector<std::wstring> AgentStatusWatcher::CoworkRoots() {
    std::vector<std::wstring> roots;
    const wchar_t* kLeaf = L"local-agent-mode-sessions";

    const std::wstring appdata = EnvVar(L"APPDATA");
    if (!appdata.empty()) {
        roots.push_back(Join(Join(appdata, L"Claude"), kLeaf));
    }
    const std::wstring local = EnvVar(L"LOCALAPPDATA");
    if (!local.empty()) {
        roots.push_back(Join(Join(local, L"Claude-3p"), kLeaf));
        // The Store build ships as MSIX, so its %APPDATA%\Claude is redirected
        // into the package's LocalCache and the normal path does not exist.
        const std::wstring packages = Join(local, L"Packages");
        for (const auto& pkg : ListDir(packages, L"Claude_*", true)) {
            std::wstring p = Join(packages, pkg);
            p = Join(p, L"LocalCache");
            p = Join(p, L"Roaming");
            p = Join(p, L"Claude");
            roots.push_back(Join(p, kLeaf));
        }
    }

    std::vector<std::wstring> alive;
    for (auto& r : roots) {
        if (DirExists(r)) alive.push_back(std::move(r));
    }
    return alive;
}

bool AgentStatusWatcher::ProcessAlive(unsigned long pid,
                                      const std::string& procStart) {
    if (pid == 0) return false;
    HANDLE h = ::OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (h == nullptr) return false;

    bool alive = true;
    DWORD exitCode = 0;
    if (::GetExitCodeProcess(h, &exitCode) && exitCode != STILL_ACTIVE) {
        alive = false;
    }
    if (alive && !procStart.empty()) {
        FILETIME creation{}, exitT{}, kernel{}, user{};
        if (::GetProcessTimes(h, &creation, &exitT, &kernel, &user)) {
            ULARGE_INTEGER c{};
            c.LowPart = creation.dwLowDateTime;
            c.HighPart = creation.dwHighDateTime;
            const unsigned long long expected =
                std::strtoull(procStart.c_str(), nullptr, 10);
            // A mismatch means the PID was recycled by an unrelated process,
            // so the registry entry is stale rather than live.
            if (expected != 0 && expected != c.QuadPart) alive = false;
        }
    }
    ::CloseHandle(h);
    return alive;
}

GroupStatus AgentStatusWatcher::Aggregate(std::vector<SessionInfo> sessions) {
    return AggregateImpl(std::move(sessions));
}

bool AgentStatusWatcher::AuditHasPendingPermission(std::string_view auditTail) {
    return AuditPendingImpl(auditTail);
}

bool AgentStatusWatcher::AuditIndicatesBusy(std::string_view auditTail) {
    return AuditBusyImpl(auditTail);
}

bool AgentStatusWatcher::TranscriptIndicatesBusy(std::string_view tail) {
    return TranscriptBusyImpl(tail);
}

StatusSnapshot AgentStatusWatcher::Scan(const WatcherConfig& cfg) {
    const long long now = NowUnixMs();
    StatusSnapshot snap;
    snap.cowork = ScanCowork(cfg, now);
    snap.code = ScanCode(cfg, now);
    snap.valid = true;
    return snap;
}

}  // namespace agentstatus
