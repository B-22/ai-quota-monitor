#include "HubUsageParser.h"
#include "JsonLite.h"

#include <algorithm>
#include <cmath>

namespace {
std::string Text(const jsonlite::Value& v, const char* key) {
    const auto* field = v.Find(key);
    const auto text = field ? field->AsString() : std::nullopt;
    return text ? std::string(*text) : std::string();
}

std::optional<double> Percent(const jsonlite::Value& v, const char* key) {
    const auto* field = v.Find(key);
    const auto n = field ? field->AsNumber() : std::nullopt;
    if (!n || !std::isfinite(*n) || *n < 0 || *n > 100) return {};
    return n;
}

int Used(const jsonlite::Value& window) {
    if (const auto used = Percent(window, "usedPercent"))
        return static_cast<int>(std::lround(*used));
    if (const auto remaining = Percent(window, "remainingPercent"))
        return static_cast<int>(std::lround(100 - *remaining));
    return -1;
}

const jsonlite::Value* Latest(
    const jsonlite::Value::Array& rows, const char* provider,
    const std::string& accountKey, long long now, long long staleSeconds) {
    const jsonlite::Value* best = nullptr;
    long long latest = 0;
    for (const auto& row : rows) {
        if (Text(row, "provider") != provider || Text(row, "status") != "ok") continue;
        if (!accountKey.empty() && Text(row, "accountKey") != accountKey) continue;
        const auto* stale = row.Find("stale");
        // Fail closed on missing/malformed freshness metadata.
        if (!stale || stale->AsBool() != std::optional<bool>(false)) continue;
        const auto collected = ParseIso8601UtcSeconds(Text(row, "updatedAt"));
        const auto* windows = row.Find("windows");
        if (!collected || *collected <= 0 || *collected > now + 60
            || (staleSeconds > 0 && now - *collected > staleSeconds)
            || !windows || !windows->AsArray()) continue;
        bool hasQuota = false;
        for (const auto& w : *windows->AsArray()) {
            const auto kind = Text(w, "kind");
            const auto* additional = w.Find("additional");
            if (additional && additional->AsBool().value_or(false)) continue;
            if ((kind == "session" || kind == "weekly") && Used(w) >= 0) hasQuota = true;
        }
        if (hasQuota && *collected > latest) { latest = *collected; best = &row; }
    }
    return best;
}
} // namespace

HubUsageSnapshot ParseHubUsageJson(
    const std::string& json, long long nowUnix,
    const std::string& claudeAccountKey, const std::string& codexAccountKey) {
    HubUsageSnapshot result;
    const auto root = jsonlite::Parser(json).Parse();
    const auto* limits = root ? root->Find("limits") : nullptr;
    const auto* providers = limits ? limits->Find("providers") : nullptr;
    const auto* rows = providers ? providers->AsArray() : nullptr;
    if (!rows) { result.errorMessage = L"Hub response invalid"; return result; }
    result.responseValid = true;
    long long staleSeconds = 300;
    if (const auto* ms = root->Find("staleAfterMs")) {
        if (const auto n = ms->AsNumber(); n && std::isfinite(*n) && *n >= 0 && *n <= 86400000)
            staleSeconds = *n == 0 ? 0 : std::max(1LL, static_cast<long long>(*n / 1000));
    }
    if (const auto* row = Latest(*rows, "claude", claudeAccountKey, nowUnix, staleSeconds)) {
        ClaudeUsageData data;
        data.lastSuccessTime = static_cast<double>(*ParseIso8601UtcSeconds(Text(*row, "updatedAt")));
        for (const auto& w : *row->Find("windows")->AsArray()) {
            const int used = Used(w);
            const auto kind = Text(w, "kind");
            const auto* extra = w.Find("additional");
            const bool additional = extra && extra->AsBool().value_or(false);
            const auto reset = Text(w, "resetsAt");
            const std::string resetValid = ParseIso8601UtcSeconds(reset) ? reset : "";
            if (!additional && kind == "session") { data.fiveHourPercent = used; data.fiveHourResetAt = resetValid; }
            else if (!additional && kind == "weekly") { data.sevenDayPercent = used; data.sevenDayResetAt = resetValid; }
            else if (additional && Text(w, "label").find("Fable") != std::string::npos) {
                data.fableWeeklyPercent = used; data.fableWeeklyResetAt = resetValid;
            }
        }
        data.success = true;
        result.claude = data;
    }
    if (const auto* row = Latest(*rows, "codex", codexAccountKey, nowUnix, staleSeconds)) {
        UsageSnapshot data;
        // Defaults in the legacy snapshot use 0 for usedPercent; set unknown explicitly.
        data.fiveHour.usedPercent = data.weekly.usedPercent = -1;
        for (const auto& w : *row->Find("windows")->AsArray()) {
            const auto kind = Text(w, "kind");
            const auto* extra = w.Find("additional");
            if (extra && extra->AsBool().value_or(false)) continue;
            UsageWindow* window = kind == "session" ? &data.fiveHour : kind == "weekly" ? &data.weekly : nullptr;
            if (!window) continue;
            window->usedPercent = Used(w);
            window->remainingPercent = window->usedPercent < 0 ? -1 : 100 - window->usedPercent;
            window->resetAtUnixSeconds = ParseIso8601UtcSeconds(Text(w, "resetsAt")).value_or(0);
        }
        data.success = true;
        result.codex = data;
        result.codexCollectedAt = *ParseIso8601UtcSeconds(Text(*row, "updatedAt"));
    }
    return result;
}
