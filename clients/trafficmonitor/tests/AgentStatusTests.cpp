#include "AgentStatusWatcher.h"


#include <windows.h>

#include <iostream>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

agentstatus::SessionInfo Session(agentstatus::Light state, long long at,
                                 const wchar_t* key = L"") {
    agentstatus::SessionInfo s;
    s.state = state;
    s.lastActivityMs = at;
    s.title = L"s";
    s.key = key;
    return s;
}

// A permission_request record carries a tool payload; the pairing must key off
// the record's own uuid, which follows the subtype marker.
std::string RequestLine(const char* uuid, const char* payloadUuid) {
    std::string s =
        R"({"type":"system","subtype":"permission_request","uuid":")";
    s += uuid;
    s += R"(","tool_name":"Bash","tool_input":{"note":"uuid ")";
    s += payloadUuid;
    s += R"("}})";
    return s;
}

std::string ResponseLine(const char* uuid) {
    std::string s =
        R"({"type":"system","subtype":"permission_response","uuid":")";
    s += uuid;
    s += R"(","tool_name":"Bash","decision":"once","granted":true})";
    return s;
}

} // namespace

int main() {
    using agentstatus::AgentStatusWatcher;
    using agentstatus::Light;

    // ---- Aggregation: the rule the two taskbar lights are built on ----
    {
        const auto g = AgentStatusWatcher::Aggregate({});
        Expect(g.light == Light::Gray, "No sessions must aggregate to grey");
        Expect(g.Empty(), "No sessions must report an empty group");
        Expect(g.total == 0, "Empty group must report zero total");
    }
    {
        // The case the user asked about: one session finished while another is
        // still running. Yellow must win so the light does not claim "done".
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Green, 100), Session(Light::Yellow, 200)});
        Expect(g.light == Light::Yellow,
            "A finished session must not downgrade a running one to green");
        Expect(g.count == 1, "Count must report only the sessions still running");
        Expect(g.total == 2, "Total must report every active session");
    }
    {
        // Red outranks yellow: a permission prompt is the only state that
        // needs the user right now, so a busy session must not mask it.
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Yellow, 100), Session(Light::Red, 90),
             Session(Light::Yellow, 80)});
        Expect(g.light == Light::Red, "A waiting session must outrank busy ones");
        Expect(g.count == 1, "Count must report the waiting sessions only");
        Expect(g.total == 3, "Total must count all three sessions");
        Expect(g.sessions.front().state == Light::Red,
            "The most urgent session must sort first for the tooltip");
    }
    {
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Green, 100), Session(Light::Green, 200)});
        Expect(g.light == Light::Green, "All-idle sessions must aggregate green");
        Expect(g.count == 2, "Count must report both idle sessions");
        Expect(g.lastActivityMs == 200,
            "Group activity must track the newest session");
    }
    {
        // A session with no state yet still counts as present, so the light
        // must not fall back to grey and vanish underneath the user.
        const auto g = AgentStatusWatcher::Aggregate({Session(Light::Gray, 10)});
        Expect(g.light == Light::Green,
            "An active session with no state must not report grey");
        Expect(!g.Empty(), "An active session must not report an empty group");
    }

    // ---- Deduplication across roots ----
    {
        // A Store/MSIX install exposes %APPDATA%\Claude a second time under
        // the package's LocalCache, so the same session is found twice. Without
        // dedup the taskbar count doubles.
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Yellow, 100, L"local_a.json"),
             Session(Light::Yellow, 100, L"local_a.json")});
        Expect(g.total == 1, "The same session seen under two roots must count once");
        Expect(g.count == 1, "Deduplication must not inflate the state count");
    }
    {
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Red, 100, L"local_a.json"),
             Session(Light::Red, 100, L"local_a.json"),
             Session(Light::Green, 90, L"local_b.json")});
        Expect(g.total == 2, "Distinct sessions must survive deduplication");
        Expect(g.light == Light::Red, "Dedup must not change the winning state");
        Expect(g.count == 1, "Only the one distinct red session must be counted");
    }
    {
        // Entries without a key carry no identity and must never be merged.
        const auto g = AgentStatusWatcher::Aggregate(
            {Session(Light::Green, 100), Session(Light::Green, 100)});
        Expect(g.total == 2, "Keyless sessions must not be deduplicated");
    }

    // ---- Cowork: permission pairing over audit.jsonl ----
    {
        Expect(!AgentStatusWatcher::AuditHasPendingPermission(""),
            "Empty audit text must not report a pending prompt");
    }
    {
        const std::string log =
            RequestLine("aaa", "zzz") + "\n" + ResponseLine("aaa") + "\n";
        Expect(!AgentStatusWatcher::AuditHasPendingPermission(log),
            "An answered permission prompt must clear the waiting state");
    }
    {
        const std::string log = RequestLine("aaa", "zzz") + "\n";
        Expect(AgentStatusWatcher::AuditHasPendingPermission(log),
            "An unanswered permission prompt must report waiting");
    }
    {
        // The uuid embedded in the tool payload must never be mistaken for the
        // record's own, or a prompt would look answered by an unrelated reply.
        const std::string log =
            RequestLine("aaa", "bbb") + "\n" + ResponseLine("bbb") + "\n";
        Expect(AgentStatusWatcher::AuditHasPendingPermission(log),
            "A reply to a different uuid must not clear the prompt");
    }
    {
        // Two prompts, one answered: the other still holds the light red.
        const std::string log = RequestLine("aaa", "x") + "\n"
                              + RequestLine("bbb", "y") + "\n"
                              + ResponseLine("aaa") + "\n";
        Expect(AgentStatusWatcher::AuditHasPendingPermission(log),
            "A second outstanding prompt must keep the light red");
    }
    {
        // Unrelated audit traffic between the pair must not break matching.
        const std::string log =
            RequestLine("aaa", "x") + "\n"
            + R"({"type":"assistant","subtype":"thinking_tokens"})" + "\n"
            + R"({"type":"command_lifecycle","state":"started"})" + "\n"
            + ResponseLine("aaa") + "\n";
        Expect(!AgentStatusWatcher::AuditHasPendingPermission(log),
            "Interleaved records must not prevent pairing");
    }
    {
        // Records with CRLF endings must parse identically.
        const std::string log =
            RequestLine("aaa", "x") + "\r\n" + ResponseLine("aaa") + "\r\n";
        Expect(!AgentStatusWatcher::AuditHasPendingPermission(log),
            "CRLF line endings must not break pairing");
    }

    // ---- Cowork: working / idle from the tail of audit.jsonl ----
    //
    // This is the rule the taskbar actually runs on. The CLI-child check that
    // used to decide it needs an OpenProcess handle the elevated host never
    // gets, so the light stayed green for the whole turn; audit.jsonl is a
    // plain file read, which the host does successfully already.
    {
        Expect(!AgentStatusWatcher::AuditIndicatesBusy(""),
            "Empty audit text must not report busy");
    }
    {
        // A finished turn: `type` sits AFTER the payload on real result
        // records, so this only passes if fields are read positionally.
        const std::string log =
            std::string(R"({"type":"user"})") + "\n"
            + R"({"session_id":"s","result":"done","type":"result",)"
              R"("duration_ms":12})" + "\n";
        Expect(!AgentStatusWatcher::AuditIndicatesBusy(log),
            "A turn closed by a result record must report idle");
    }
    {
        // The user's message is in, the result is not: still working.
        const std::string log =
            std::string(R"({"type":"result","subtype":"success"})") + "\n"
            + R"({"type":"user","message":{"role":"user"}})" + "\n"
            + R"({"type":"system","subtype":"thinking_tokens"})" + "\n";
        Expect(AgentStatusWatcher::AuditIndicatesBusy(log),
            "Records after the last result must report busy");
    }
    {
        // Every audit record embeds the message it describes, and that nested
        // object has a "type" of its own. Here the record IS finished, but the
        // first "type" in the raw text says otherwise: reading fields
        // positionally is the only thing that gets this right.
        const std::string log =
            R"({"message":{"type":"message","role":"assistant"},)"
            R"("type":"result","subtype":"success"})";
        Expect(!AgentStatusWatcher::AuditIndicatesBusy(log),
            "A nested type ahead of the record's own must not read as busy");
    }
    {
        // The mirror image: an unfinished record whose payload happens to
        // carry type "result".
        const std::string log =
            R"({"payload":{"kind":"tool","type":"result"},)"
            R"("type":"assistant","message":{"role":"assistant"}})";
        Expect(AgentStatusWatcher::AuditIndicatesBusy(log),
            "A nested \"type\":\"result\" must not read as a finished turn");
    }
    {
        // The assistant's own text is echoed into the record verbatim, so it
        // can contain a quoted record of any shape.
        const std::string log =
            R"({"note":"sample: {\"type\":\"result\"}","type":"assistant"})";
        Expect(AgentStatusWatcher::AuditIndicatesBusy(log),
            "A quoted record inside message text must not decide the state");
    }
    {
        // audit.jsonl is appended to while it is read, so the newest line can
        // be half written. Fall back to the last complete record instead of
        // guessing from a fragment.
        const std::string log =
            std::string(R"({"type":"result","subtype":"success"})") + "\n"
            + R"({"type":"sys)";
        Expect(!AgentStatusWatcher::AuditIndicatesBusy(log),
            "A truncated trailing record must fall back to the previous one");
    }
    {
        const std::string log =
            std::string(R"({"type":"user"})") + "\r\n"
            + R"({"type":"result","subtype":"success"})" + "\r\n";
        Expect(!AgentStatusWatcher::AuditIndicatesBusy(log),
            "CRLF line endings must not break busy detection");
    }
    {
        // A pending permission prompt also leaves the turn unfinished. Red
        // outranks yellow in the caller, but the busy rule itself must not
        // pretend the turn is over.
        const std::string log = RequestLine("aaa", "zzz") + "\n";
        Expect(AgentStatusWatcher::AuditIndicatesBusy(log),
            "An outstanding permission prompt must still count as unfinished");
    }

    // ---- Code: busy detection from the transcript ----
    {
        Expect(!AgentStatusWatcher::TranscriptIndicatesBusy(""),
            "Empty transcript text must not report busy");
    }
    {
        const std::string log =
            R"({"type":"assistant","message":{"stop_reason":"end_turn"}})";
        Expect(!AgentStatusWatcher::TranscriptIndicatesBusy(log),
            "A closed turn (end_turn) must report idle");
    }
    {
        const std::string log =
            R"({"type":"assistant","message":{"stop_reason":"tool_use"}})";
        Expect(AgentStatusWatcher::TranscriptIndicatesBusy(log),
            "A turn stopped for a tool call must report busy");
    }
    {
        // Only the NEWEST stop_reason decides; earlier turns are history.
        const std::string log =
            std::string(R"({"message":{"stop_reason":"tool_use"}})") + "\n"
            + R"({"message":{"stop_reason":"end_turn"}})" + "\n";
        Expect(!AgentStatusWatcher::TranscriptIndicatesBusy(log),
            "The newest stop_reason must decide, not an earlier one");
    }
    {
        const std::string log =
            std::string(R"({"message":{"stop_reason":"end_turn"}})") + "\n"
            + R"({"message":{"stop_reason":"tool_use"}})" + "\n";
        Expect(AgentStatusWatcher::TranscriptIndicatesBusy(log),
            "A new turn after a closed one must report busy again");
    }
    {
        // Trailing records without a stop_reason (attachments, reminders) are
        // appended constantly and must not hide the real turn state.
        const std::string log =
            std::string(R"({"message":{"stop_reason":"end_turn"}})") + "\n"
            + R"({"type":"attachment","attachment":{"type":"reminder"}})" + "\n";
        Expect(!AgentStatusWatcher::TranscriptIndicatesBusy(log),
            "Trailing records without stop_reason must not change the state");
    }

    // ---- Code: the state each session reports through its hooks ----
    {
        using agentstatus::HookStatus;

        HookStatus hs;
        Expect(!AgentStatusWatcher::ParseHookStatus("", &hs),
            "No status file must not parse as a state");
        Expect(!AgentStatusWatcher::ParseHookStatus(R"({"ts":12})", &hs),
            "A record without a state must be refused, not defaulted");
        Expect(!AgentStatusWatcher::ParseHookStatus(R"({"state":""})", &hs),
            "An empty state must be refused");
        // The hook truncates and rewrites in place, so a scan can read the
        // file halfway through the write.
        Expect(!AgentStatusWatcher::ParseHookStatus(R"({"state":"work)", &hs),
            "A half-written record must be refused rather than guessed at");

        Expect(AgentStatusWatcher::ParseHookStatus(
                   R"({"state":"working","ts":1787883635000})", &hs),
            "An ordinary record must parse");
        Expect(hs.state == "working" && hs.ts == 1787883635000LL,
            "Both fields must survive the parse");

        // ---- The rule the Code light is decided by ----
        const long long now = 1'000'000'000'000LL;
        auto light = [&](const char* state, long long ts, long long transcript) {
            HookStatus st;
            st.state = state;
            st.ts = ts;
            return AgentStatusWatcher::HookStateToLight(st, transcript, 10, now);
        };

        Expect(light("working", now - 5'000, now - 9'000) == Light::Yellow,
            "A session that reported working must be yellow");
        Expect(light("idle", now - 5'000, now - 9'000) == Light::Green,
            "A session that reported idle must be green");

        // Idle is the resting state and stays true however long it sits; only
        // the two states that claim something is happening expire.
        Expect(light("idle", now - 5 * 60 * 60'000LL, 0) == Light::Green,
            "An old idle record must still read as idle");

        // A turn killed or interrupted before its Stop hook ran leaves
        // "working" behind forever. Gray hands the decision back to the
        // transcript instead of holding the light yellow.
        Expect(light("working", now - 11 * 60'000LL, 0) == Light::Gray,
            "A working record nobody refreshed must stop being believed");
        Expect(light("working", now - 9 * 60'000LL, 0) == Light::Yellow,
            "A working record inside the grace window must still count");
        Expect(light("working", 0, 0) == Light::Gray,
            "A working record with no timestamp cannot be aged, so it is unused");

        // A permission prompt is red for as long as it stands: nothing else on
        // this machine can see one, so nothing else can clear it either.
        Expect(light("waiting", now - 5'000, now - 9'000) == Light::Red,
            "An open permission prompt must be red");
        Expect(light("waiting", now - 3 * 60 * 60'000LL, 0) == Light::Red,
            "A prompt left standing for hours must still be red");
        // Answering it fires no hook. What it does is let the tool run, and
        // that appends to the transcript.
        Expect(light("waiting", now - 9'000, now - 5'000) == Light::Gray,
            "A transcript written after the prompt means the prompt was answered");

        Expect(light("compacting", now, 0) == Light::Gray,
            "A state this build does not know must fall back, not light up");
    }

    // ---- Stale session registry entries ----
    {
        Expect(!AgentStatusWatcher::ProcessAlive(0, ""),
            "PID 0 must never count as a live session");
        // A live PID whose recorded creation time disagrees means Windows
        // recycled the number; the registry entry is stale, not running.
        const unsigned long self =
            static_cast<unsigned long>(::GetCurrentProcessId());
        Expect(!AgentStatusWatcher::ProcessAlive(self, "1"),
            "A mismatched procStart must reject a recycled PID");
        Expect(AgentStatusWatcher::ProcessAlive(self, ""),
            "A live PID with no recorded procStart must count as running");
    }

    if (failures == 0) {
        std::cout << "All AgentStatus tests passed\n";
    }
    return failures == 0 ? 0 : 1;
}
