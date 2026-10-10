#include "ClaudeUsageParser.h"

#include "JsonLite.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <string_view>

namespace {

bool ContainsAsciiCaseInsensitive(std::string_view text, std::string_view needle) {
    if (needle.empty()) return true;
    return std::search(
               text.begin(), text.end(), needle.begin(), needle.end(),
               [](unsigned char left, unsigned char right) {
                   return std::tolower(left) == std::tolower(right);
               }) != text.end();
}

std::string ReadOptionalString(
    const jsonlite::Value& value,
    std::string_view key) {
    const jsonlite::Value* field = value.Find(key);
    if (!field || !field->IsString()) return {};
    const auto text = field->AsString();
    return text ? std::string(*text) : std::string();
}

void ParseLegacyWindow(
    const jsonlite::Value& root,
    std::string_view key,
    int& percent,
    std::string& resetAt) {
    const jsonlite::Value* window = root.Find(key);
    if (!window || !window->IsObject()) return;
    const jsonlite::Value* utilization = window->Find("utilization");
    if (!utilization) utilization = window->Find("percent");
    if (utilization) {
        const auto value = utilization->AsNumber();
        if (value) percent = static_cast<int>(std::round(*value));
    }
    resetAt = ReadOptionalString(*window, "resets_at");
}

}  // namespace

ClaudeUsageData ParseClaudeUsageJson(
    const std::string& json,
    std::wstring* errorMessage) {
    if (errorMessage) errorMessage->clear();

    jsonlite::Parser parser(json);
    const auto root = parser.Parse();
    if (!root || !root->IsObject()) {
        ClaudeUsageData data;
        data.errorMessage = L"JSON parse failed";
        if (errorMessage) *errorMessage = data.errorMessage;
        return data;
    }

    ClaudeUsageData data;
    data.success = true;

    ParseLegacyWindow(
        *root, "five_hour", data.fiveHourPercent, data.fiveHourResetAt);
    ParseLegacyWindow(
        *root, "seven_day", data.sevenDayPercent, data.sevenDayResetAt);

    // Current responses use a flat limits array. A Fable cap is a
    // weekly_scoped entry whose model display name contains "Fable". Its
    // resets_at value is nullable, so percentage parsing must not depend on it.
    const jsonlite::Value* limits = root->Find("limits");
    if (limits && limits->IsArray()) {
        const auto* entries = limits->AsArray();
        if (entries) {
            for (const auto& entry : *entries) {
                const jsonlite::Value* kind = entry.Find("kind");
                const jsonlite::Value* percent = entry.Find("percent");
                if (!kind || !percent) continue;
                const auto kindText = kind->AsString();
                const auto percentValue = percent->AsNumber();
                if (!kindText || !percentValue) continue;

                const int usedPercent =
                    static_cast<int>(std::round(*percentValue));
                const std::string resetAt =
                    ReadOptionalString(entry, "resets_at");

                if (*kindText == "session") {
                    if (data.fiveHourPercent < 0) {
                        data.fiveHourPercent = usedPercent;
                        data.fiveHourResetAt = resetAt;
                    }
                    continue;
                }
                if (*kindText == "weekly_all") {
                    if (data.sevenDayPercent < 0) {
                        data.sevenDayPercent = usedPercent;
                        data.sevenDayResetAt = resetAt;
                    }
                    continue;
                }
                if (*kindText != "weekly_scoped") continue;

                const jsonlite::Value* scope = entry.Find("scope");
                const jsonlite::Value* model =
                    scope && scope->IsObject() ? scope->Find("model") : nullptr;
                const jsonlite::Value* displayName =
                    model && model->IsObject()
                        ? model->Find("display_name") : nullptr;
                const auto modelText = displayName
                    ? displayName->AsString()
                    : std::optional<std::string_view>{};
                if (!modelText
                    || !ContainsAsciiCaseInsensitive(*modelText, "fable")) {
                    continue;
                }

                // If the API ever reports multiple Fable-scoped entries,
                // display the most consumed one so the tighter cap wins.
                if (data.fableWeeklyPercent < usedPercent) {
                    data.fableWeeklyPercent = usedPercent;
                    data.fableWeeklyResetAt = resetAt;
                }
            }
        }
    }

    // Compatibility with older/internal Claude clients that expose the same
    // bucket under a top-level name instead of limits[].
    if (data.fableWeeklyPercent < 0) {
        ParseLegacyWindow(
            *root, "seven_day_overage_included",
            data.fableWeeklyPercent, data.fableWeeklyResetAt);
    }
    if (data.fableWeeklyPercent < 0) {
        ParseLegacyWindow(
            *root, "seven_day_fable",
            data.fableWeeklyPercent, data.fableWeeklyResetAt);
    }

    const jsonlite::Value* extraUsage = root->Find("extra_usage");
    if (extraUsage && extraUsage->IsObject()) {
        const jsonlite::Value* usedCredits =
            extraUsage->Find("used_credits");
        if (usedCredits) {
            const auto value = usedCredits->AsNumber();
            if (value) data.usedCredits = static_cast<long long>(*value);
        }
        data.currency = ReadOptionalString(*extraUsage, "currency");
        const jsonlite::Value* decimalPlaces =
            extraUsage->Find("decimal_places");
        if (decimalPlaces) {
            const auto value = decimalPlaces->AsNumber();
            if (value) data.decimalPlaces = static_cast<int>(*value);
        }
    }

    return data;
}
