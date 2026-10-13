#include "HubUsageParser.h"
#include "HubUsageFetcher.h"
#include <cstdlib>
#include <iostream>

void Check(bool ok, const char* message) {
    if (!ok) { std::cerr << message << '\n'; std::exit(1); }
}

int main() {
    HubConfig config;
    config.enabled = true;
    int claudeCalls = 0;
    auto localClaude = [&] { ++claudeCalls; ClaudeUsageData data; data.success = true; return data; };
    Check(!SelectHubOrLocal(std::optional<ClaudeUsageData>{}, config,
        config.localFallbackClaude, localClaude).success && claudeCalls == 0,
        "Hub enabled with absent Claude never touches local credentials by default");
    ClaudeUsageData hubClaude; hubClaude.success = true;
    config.localFallbackClaude = true;
    Check(SelectHubOrLocal(std::optional<ClaudeUsageData>{hubClaude}, config,
        config.localFallbackClaude, localClaude).success && claudeCalls == 0,
        "fresh Hub bypasses local provider even with fallback opted in");
    config.localFallback = false;
    Check(!SelectHubOrLocal(std::optional<ClaudeUsageData>{}, config,
        config.localFallbackClaude, localClaude).success && claudeCalls == 0,
        "master fallback off prevents provider requests");
    config.enabled = false;
    Check(SelectHubOrLocal(std::optional<ClaudeUsageData>{}, config,
        false, localClaude).success && claudeCalls == 1, "disabled Hub preserves original source");
    const auto now = *ParseIso8601UtcSeconds("2026-01-01T12:02:00Z");
    const std::string fixture = R"({"updatedAt":"2099-01-01T00:00:00Z","staleAfterMs":300000,
      "limits":{"providers":[
       {"provider":"claude","accountKey":"old","status":"ok","stale":false,"updatedAt":"2026-01-01T12:00:00Z",
        "windows":[{"kind":"session","usedPercent":80},{"kind":"weekly","usedPercent":60}]},
       {"provider":"claude","accountKey":"new","status":"ok","stale":false,"updatedAt":"2026-01-01T12:01:00Z",
        "windows":[{"kind":"session","usedPercent":0,"resetsAt":"2026-01-01T15:00:00Z"},
         {"kind":"weekly","usedPercent":null},
         {"kind":"weekly","label":"Fable weekly","additional":true,"usedPercent":25}]},
       {"provider":"codex","accountKey":"one","status":"ok","stale":false,"updatedAt":"2026-01-01T12:00:30Z",
        "windows":[{"kind":"session","usedPercent":null,"remainingPercent":null},
         {"kind":"weekly","remainingPercent":45.5,"resetsAt":"2026-01-08T12:00:00Z"}]}
      ]}})";
    const auto result = ParseHubUsageJson(fixture, now);
    Check(result.responseValid && result.claude && result.codex, "both providers parsed");
    Check(result.claude->fiveHourPercent == 0, "zero is valid and latest account selected, not summed");
    Check(result.claude->sevenDayPercent == -1, "null remains unknown");
    Check(result.claude->fableWeeklyPercent == 25, "additional Fable does not overwrite weekly");
    Check(result.codex->fiveHour.remainingPercent == -1, "missing Codex window remains unknown");
    Check(result.codex->weekly.usedPercent == 55, "remaining percentage converted with rounding");
    Check(result.codexCollectedAt == *ParseIso8601UtcSeconds("2026-01-01T12:00:30Z"), "provider time, not envelope time");
    Check(result.claude->lastSuccessTime == *ParseIso8601UtcSeconds("2026-01-01T12:01:00Z"), "Claude collection time preserved");
    Check(ParseHubUsageJson(fixture, now, "old").claude->fiveHourPercent == 80, "explicit account selection");
    Check(!ParseHubUsageJson(fixture, now, "missing").claude, "missing selector unavailable");
    Check(!ParseHubUsageJson(fixture, now + 600).claude, "old provider rejected despite fresh aggregate time");
    Check(!ParseHubUsageJson(fixture, now - 1000).codex, "future provider rejected");
    Check(!ParseHubUsageJson("{", now).responseValid, "malformed JSON rejected");
    Check(!ParseHubUsageJson("{}", now).responseValid, "missing contract rejected");
    Check(ParseHubUsageJson(R"({"limits":{"providers":[]}})", now).responseValid, "empty Hub is valid but unavailable");
    for (const auto& bad : {std::string("true"), std::string("null")}) {
        auto changed = fixture;
        const auto p = changed.find("\"stale\":false");
        changed.replace(p, 13, "\"stale\":" + bad);
        Check(!ParseHubUsageJson(changed, now, "old").claude, "stale or malformed stale row rejected");
    }
    auto error = fixture;
    const auto p = error.find("\"status\":\"ok\"");
    error.replace(p, 13, "\"status\":\"auth_error\"");
    Check(!ParseHubUsageJson(error, now, "old").claude, "authentication failure not shown as zero");
    auto invalid = fixture;
    const auto q = invalid.find("\"usedPercent\":80");
    invalid.replace(q, 16, "\"usedPercent\":180");
    // Old row still has a valid weekly window, but invalid session stays unknown.
    Check(ParseHubUsageJson(invalid, now, "old").claude->fiveHourPercent == -1, "out of range percentages unknown");
    return 0;
}
