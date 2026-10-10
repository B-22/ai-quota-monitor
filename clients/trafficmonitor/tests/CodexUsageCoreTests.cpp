#include "CodexUsageCore.h"
#include "CodexUsageVersion.h"
#include "ClaudeUsageParser.h"
#include "ClaudeUsageExport.h"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

void AssertTrue(bool condition, const char* message) {
    if (!condition) {
        std::cerr << message << '\n';
        std::exit(1);
    }
}

void AssertEqual(int actual, int expected, const char* message) {
    if (actual != expected) {
        std::cerr << message << ": expected " << expected << ", got " << actual << '\n';
        std::exit(1);
    }
}

void AssertEqual(const std::wstring& actual, const std::wstring& expected, const char* message) {
    if (actual != expected) {
        std::wcerr << message << L": expected [" << expected << L"], got [" << actual << L"]\n";
        std::exit(1);
    }
}

void AssertEqual(
    const std::string& actual,
    const std::string& expected,
    const char* message) {
    if (actual != expected) {
        std::cerr << message << ": expected [" << expected
                  << "], got [" << actual << "]\n";
        std::exit(1);
    }
}

void AssertContains(
    const std::string& actual,
    const std::string& expected,
    const char* message) {
    if (actual.find(expected) == std::string::npos) {
        std::cerr << message << ": missing [" << expected << "]\n";
        std::exit(1);
    }
}

void AssertNotContains(const std::wstring& actual, wchar_t needle, const char* message) {
    if (actual.find(needle) != std::wstring::npos) {
        std::wcerr << message << L": unexpected character [" << needle << L"] in [" << actual << L"]\n";
        std::exit(1);
    }
}

void ParsesUsageWindows() {
    const std::string json = R"json({
        "email": "user@example.com",
        "plan_type": "pro",
        "rate_limit": {
            "primary_window": {
                "used_percent": 23,
                "limit_window_seconds": 18000,
                "reset_after_seconds": 3600,
                "reset_at": 1780980000
            },
            "secondary_window": {
                "used_percent": 61,
                "limit_window_seconds": 604800,
                "reset_after_seconds": 86400,
                "reset_at": 1781066400
            }
        }
    })json";

    std::wstring error;
    UsageSnapshot snapshot = ParseUsageJson(json, &error);

    AssertTrue(snapshot.success, "snapshot should parse");
    AssertEqual(snapshot.fiveHour.remainingPercent, 77, "five-hour remaining percent");
    AssertEqual(snapshot.weekly.remainingPercent, 39, "weekly remaining percent");
    AssertEqual(snapshot.email, L"user@example.com", "email");
    AssertEqual(snapshot.planType, L"pro", "plan type");
}

void ClassifiesPrimaryWindowWithoutSecondaryWindow() {
    const std::string json = R"json({
        "rate_limit": {
            "primary_window": {
                "used_percent": 4,
                "limit_window_seconds": 604800,
                "reset_after_seconds": 580302,
                "reset_at": 1780980000
            }
        }
    })json";

    std::wstring error;
    UsageSnapshot snapshot = ParseUsageJson(json, &error);

    AssertTrue(snapshot.success, "snapshot should parse without secondary window");
    AssertTrue(error.empty(), "missing secondary window should not set an error");
    AssertEqual(snapshot.fiveHour.remainingPercent, -1, "missing five-hour window should be unavailable");
    AssertEqual(snapshot.weekly.remainingPercent, 96, "primary weekly remaining percent");
    AssertEqual(
        BuildUsageTooltip(snapshot),
        L"Codex: 5\u5c0f\u65f6\u5269\u4f59 --\nCodex: \u672c\u5468\u5269\u4f59 96% (6d 17h\u540e\u91cd\u7f6e)",
        "tooltip should mark missing five-hour window unavailable");
}

void ParsesOptionalClaudeFableWindow() {
    const std::string json = R"json({
        "five_hour": {
            "utilization": 12,
            "resets_at": "2026-08-21T06:00:00Z"
        },
        "seven_day": {
            "utilization": 34,
            "resets_at": "2026-08-25T06:00:00Z"
        },
        "limits": [
            {
                "kind": "weekly_scoped",
                "group": "weekly",
                "percent": 47,
                "resets_at": "2026-08-25T06:00:00Z",
                "scope": {
                    "model": {"id": null, "display_name": "Fable 5"},
                    "surface": null
                }
            },
            {
                "kind": "weekly_scoped",
                "group": "weekly",
                "percent": 81,
                "resets_at": null,
                "scope": {
                    "model": {"display_name": "Sonnet"}
                }
            }
        ]
    })json";

    std::wstring error;
    const ClaudeUsageData data = ParseClaudeUsageJson(json, &error);
    AssertTrue(data.success, "Claude usage response should parse");
    AssertTrue(error.empty(), "valid Claude usage should not set an error");
    AssertEqual(data.fiveHourPercent, 12, "Claude five-hour used percent");
    AssertEqual(data.sevenDayPercent, 34, "Claude weekly used percent");
    AssertEqual(data.fableWeeklyPercent, 47, "Fable weekly used percent");
    AssertTrue(
        data.fableWeeklyResetAt == "2026-08-25T06:00:00Z",
        "Fable reset timestamp should parse");
}

void HidesClaudeFableWindowWhenPlanDoesNotReportIt() {
    const std::string json = R"json({
        "limits": [
            {"kind":"session","percent":5,"resets_at":null},
            {"kind":"weekly_all","percent":9,"resets_at":null}
        ]
    })json";

    const ClaudeUsageData data = ParseClaudeUsageJson(json);
    AssertTrue(data.success, "Claude limits array should parse");
    AssertEqual(data.fiveHourPercent, 5, "nullable session reset should not hide usage");
    AssertEqual(data.sevenDayPercent, 9, "nullable weekly reset should not hide usage");
    AssertEqual(data.fableWeeklyPercent, -1, "missing Fable bucket should stay unavailable");
}

void ParsesLegacyClaudeFableWindow() {
    const std::string json = R"json({
        "seven_day_overage_included": {
            "utilization": 63,
            "resets_at": "2026-08-26T00:00:00Z"
        }
    })json";

    const ClaudeUsageData data = ParseClaudeUsageJson(json);
    AssertTrue(data.success, "legacy Claude Fable response should parse");
    AssertEqual(data.fableWeeklyPercent, 63, "legacy Fable used percent");
}

void BuildsSanitizedClaudeUsageExport() {
    ClaudeUsageData data;
    data.success = true;
    data.lastSuccessTime = 1000;
    data.fiveHourPercent = 38;
    data.sevenDayPercent = 73;
    data.fableWeeklyPercent = 63;

    AssertEqual(
        ClassifyClaudeUsageExportStatus(data, 1100),
        std::string("ok"),
        "fresh Claude export status");
    AssertTrue(
        ShouldWriteClaudeUsageExport(-1, 10, "ok", ""),
        "first export should write immediately");
    AssertTrue(
        !ShouldWriteClaudeUsageExport(599, 10, "ok", "ok"),
        "ten-minute export should not write early");
    AssertTrue(
        ShouldWriteClaudeUsageExport(600, 10, "ok", "ok"),
        "ten-minute export should write at the boundary");
    AssertTrue(
        ShouldWriteClaudeUsageExport(30, 10, "stale", "ok"),
        "status changes should write immediately");

    ClaudeUsageExportRecord record;
    record.status = "ok";
    record.generatedAtUnix = 1100;
    record.generatedAtLocal = "2026-08-21 17:00";
    record.sourceFetchedAtUnix = 1000;
    record.sourceAgeSeconds = 100;
    record.refreshIntervalMinutes = 10;
    record.fiveHourUsedPercent = 38;
    record.fiveHourResetUtc = "2026-08-21T10:00:00Z";
    record.fiveHourResetLocal = "2026-08-21 18:00";
    record.sevenDayUsedPercent = 73;
    record.sevenDayResetUtc = "2026-08-25T06:00:00Z";
    record.sevenDayResetLocal = "2026-08-25 14:00";
    record.fableWeeklyUsedPercent = 63;

    const std::string text = BuildClaudeUsageExportText(record);
    AssertContains(text, "status=ok", "export status");
    AssertContains(text, "refresh_interval_minutes=10", "export interval");
    AssertContains(
        text,
        "[claude_5h]\navailable=1\nused_percent=38\nremaining_percent=62",
        "five-hour export");
    AssertContains(
        text,
        "[claude_7d]\navailable=1\nused_percent=73\nremaining_percent=27",
        "weekly export");
    AssertContains(
        text,
        "reset_at_local=2026-08-25 14:00",
        "weekly local reset");
    AssertContains(
        text,
        "[claude_fable_7d]\navailable=1\nused_percent=63\nremaining_percent=37",
        "Fable export");
    AssertTrue(
        text.find("token=") == std::string::npos,
        "export must not contain a token field");
    AssertTrue(
        text.find("credits=") == std::string::npos,
        "export must not contain Credits data");

    data.errorMessage = L"cached after fetch error";
    AssertEqual(
        ClassifyClaudeUsageExportStatus(data, 1100),
        std::string("stale"),
        "cached error should mark export stale");
    data.errorMessage.clear();
    AssertEqual(
        ClassifyClaudeUsageExportStatus(data, 2000),
        std::string("stale"),
        "old Claude export status");
    data.success = false;
    AssertEqual(
        ClassifyClaudeUsageExportStatus(data, 1100),
        std::string("unavailable"),
        "failed Claude export status");
}

void FormatsDisplayText() {
    AssertEqual(FormatRemainingPercent(77), L"77%", "remaining percent text");
    AssertEqual(FormatRemainingPercent(-1), L"--", "negative percent text");
    AssertEqual(FormatRemainingPercent(101), L"--", "out-of-range percent text");
    AssertEqual(
        FormatIsoDateAsMonthDay("2099-08-13"), L"8.13",
        "date-only subscription expiry should use month.day");
    AssertEqual(
        FormatIsoDateAsMonthDay("2026-02-30"), L"--",
        "invalid subscription expiry should be rejected");
    AssertEqual(
        FormatIsoDateAsMonthDay("2024-02-29"), L"2.29",
        "leap-day subscription expiry should be accepted");
}

void AdvancesSubscriptionExpiryToNextCycle() {
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-09-16", "monthly", 2026, 8, 26)),
        Utf8ToWide("2026-09-16"),
        "anchor still in the future should be returned unchanged");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-08-16", "monthly", 2026, 8, 26)),
        Utf8ToWide("2026-09-16"),
        "past monthly anchor should roll forward exactly one cycle");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-01-16", "monthly", 2026, 8, 26)),
        Utf8ToWide("2026-09-16"),
        "many-cycles-stale monthly anchor should catch up to the next occurrence");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-01-31", "monthly", 2026, 3, 15)),
        Utf8ToWide("2026-03-31"),
        "short-February clamp should not permanently shrink later months");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2025-09-16", "yearly", 2026, 8, 26)),
        Utf8ToWide("2026-09-16"),
        "past yearly anchor should roll forward exactly one cycle");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-08-26", "monthly", 2026, 8, 26)),
        Utf8ToWide("2026-08-26"),
        "anchor equal to today should not roll forward yet");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("2026-08-16", "", 2026, 8, 26)),
        Utf8ToWide("2026-08-16"),
        "empty cycle should behave like the old static date, unchanged");
    AssertEqual(
        Utf8ToWide(AdvanceExpiryToFuture("bad-date", "monthly", 2026, 8, 26)),
        Utf8ToWide("bad-date"),
        "malformed anchor should be returned unchanged rather than throw");
}

void ParsesIso8601Timestamps() {
    const auto utc = ParseIso8601UtcSeconds("2026-07-27T08:30:45Z");
    const auto offset = ParseIso8601UtcSeconds("2026-07-27T16:30:45.123+08:00");
    const auto compactOffset = ParseIso8601UtcSeconds("2026-07-27T03:00:45-0530");
    const auto equivalentUtc = ParseIso8601UtcSeconds("2026-07-27T08:30:45Z");

    AssertTrue(utc.has_value(), "UTC ISO timestamp should parse");
    AssertTrue(offset.has_value(), "offset ISO timestamp should parse");
    AssertTrue(compactOffset.has_value(), "compact offset ISO timestamp should parse");
    AssertEqual(static_cast<int>(*offset - *utc), 0, "offset timestamp should normalize to UTC");
    AssertEqual(static_cast<int>(*compactOffset - *equivalentUtc), 0, "negative offset should normalize to UTC");
    AssertTrue(!ParseIso8601UtcSeconds("2026-02-30T08:00:00Z").has_value(), "invalid date should fail");
    AssertTrue(!ParseIso8601UtcSeconds("2026-07-27T25:00:00Z").has_value(), "invalid time should fail");
}

void ClassifiesFreshnessAndCountdownWindow() {
    AssertTrue(ClassifyFreshness(59) == FreshnessLevel::Fresh, "59 seconds should be fresh");
    AssertTrue(ClassifyFreshness(60) == FreshnessLevel::Warning, "one minute should warn");
    AssertTrue(ClassifyFreshness(299) == FreshnessLevel::Warning, "under five minutes should warn");
    AssertTrue(ClassifyFreshness(300) == FreshnessLevel::Stale, "five minutes should be stale");
    AssertTrue(
        ClassifyFreshnessForDisplay(60, true) == FreshnessLevel::Fresh,
        "in-flight refresh should suppress the one-minute boundary flash");
    AssertTrue(
        ClassifyFreshnessForDisplay(119, true) == FreshnessLevel::Fresh,
        "short in-flight refresh should remain visually fresh");
    AssertTrue(
        ClassifyFreshnessForDisplay(120, true) == FreshnessLevel::Warning,
        "long in-flight refresh should still warn");
    AssertTrue(
        ClassifyFreshnessForDisplay(60, false) == FreshnessLevel::Warning,
        "completed failed refresh should warn immediately");
    AssertTrue(!ShouldReplaceResetWithFreshness(600), "ten minutes should retain reset weekday");
    AssertTrue(ShouldReplaceResetWithFreshness(601), "over ten minutes should replace reset weekday");

    AssertTrue(ShouldShowCountdown(12 * 3600, 12), "countdown should show at threshold");
    AssertTrue(!ShouldShowCountdown(12 * 3600 + 1, 12), "countdown should hide before threshold");
    AssertTrue(!ShouldShowCountdown(0, 12), "expired countdown should hide");
}

void BuildsMultiLineSuccessTooltip() {
    UsageSnapshot snapshot;
    snapshot.success = true;
    snapshot.fiveHour.remainingPercent = 77;
    snapshot.fiveHour.resetAfterSeconds = 3600;
    snapshot.weekly.remainingPercent = 39;
    snapshot.weekly.resetAfterSeconds = 86400;

    const std::wstring tooltip = BuildUsageTooltip(snapshot);

    AssertEqual(
        tooltip,
        L"Codex: 5\u5c0f\u65f6\u5269\u4f59 77% (1h 0m\u540e\u91cd\u7f6e)\nCodex: \u672c\u5468\u5269\u4f59 39% (1d 0h\u540e\u91cd\u7f6e)",
        "multi-line success tooltip");
}

void ParsesResetCreditsAndExpiryWarning() {
    const std::string json = R"json({
        "available_count": 3,
        "credits": [
            {
                "status": "available",
                "title": "Full reset",
                "granted_at": "2026-07-29T00:00:00Z",
                "expires_at": "2026-07-31T00:00:00Z"
            },
            {
                "status": "available",
                "granted_at": "2026-07-29T01:00:00Z",
                "expires_at": "2026-07-30T12:00:00Z"
            },
            {
                "status": "redeemed",
                "expires_at": "2026-07-30T01:00:00Z"
            }
        ]
    })json";

    std::wstring error;
    const ResetCreditsSnapshot snapshot =
        ParseResetCreditsJson(json, &error);
    const long long now =
        *ParseIso8601UtcSeconds("2026-07-30T00:00:00Z");
    const long long expectedExpiry =
        *ParseIso8601UtcSeconds("2026-07-30T12:00:00Z");

    AssertTrue(snapshot.success, "reset credits should parse");
    AssertEqual(snapshot.availableCount, 3, "available reset-credit count");
    AssertEqual(
        static_cast<int>(
            EarliestAvailableResetCreditExpiry(snapshot, now)
            - expectedExpiry),
        0,
        "earliest available reset-credit expiry");
    AssertTrue(
        IsResetCreditExpiringSoon(snapshot, now, 48),
        "credit within 48 hours should warn");
    AssertTrue(
        !IsResetCreditExpiringSoon(snapshot, now, 11),
        "credit outside 11 hours should not warn");
    AssertEqual(
        FormatResetCreditWarning(3, 12 * 3600),
        L"3\u536112h",
        "reset-credit warning text");
}

void ParsesAndMergesResetRadarSignals() {
    const long long now =
        *ParseIso8601UtcSeconds("2026-07-30T00:10:00Z");
    const std::string summaryJson = R"json({
        "monitored_at": "2026-07-22T06:57:20+08:00",
        "window_open": true,
        "status": "open",
        "window": {
            "open": true,
            "status": "open",
            "message": "Lands in the next hour",
            "opened_at": "2026-07-30T07:50:00+08:00",
            "closed_at": null
        },
        "prediction": {
            "updated_at": "2026-07-30T00:04:00Z",
            "probability_24h": 0.55,
            "probability_48h": 0.75
        }
    })json";
    const std::string quietForecastJson = R"json({
        "updated_at": "2026-07-30T00:05:00Z",
        "official_signal": null,
        "probabilities": {
            "rounded_24h": 15,
            "rounded_48h": 30
        }
    })json";
    const std::string activeForecastJson = R"json({
        "updated_at": "2026-07-30T00:05:00Z",
        "official_signal": {
            "at": "2026-07-30T00:01:00Z",
            "summary": "Reset announced"
        },
        "probabilities": {
            "rounded_24h": 90,
            "rounded_48h": 95
        }
    })json";
    const std::string malformedSignalForecastJson = R"json({
        "updated_at": "2026-07-30T00:05:00Z",
        "official_signal": false,
        "probabilities": {
            "rounded_24h": 15,
            "rounded_48h": 30
        }
    })json";

    std::wstring error;
    ResetRadarSnapshot summary =
        ParseResetRadarSummaryJson(summaryJson, &error);
    ResetRadarSnapshot quiet =
        ParseResetForecastJson(quietForecastJson, &error);
    ResetRadarSnapshot active =
        ParseResetForecastJson(activeForecastJson, &error);
    const ResetRadarSnapshot malformedSignal =
        ParseResetForecastJson(malformedSignalForecastJson, &error);
    summary.checkedAtUnixSeconds = now;
    quiet.checkedAtUnixSeconds = now;
    active.checkedAtUnixSeconds = now;

    AssertTrue(summary.success, "radar summary should parse");
    AssertTrue(
        summary.updatedAtUnixSeconds == summary.openedAtUnixSeconds,
        "broken monitored_at must not become the summary signal time");
    AssertTrue(quiet.success, "quiet reset forecast should parse");
    AssertTrue(active.success, "active reset forecast should parse");
    AssertTrue(!quiet.forecastWindowOpen, "null official signal should be quiet");
    AssertTrue(active.forecastWindowOpen, "object official signal should open window");
    AssertTrue(
        !malformedSignal.forecastWindowOpen,
        "unexpected official signal types must fail quiet");

    const ResetRadarSnapshot merged =
        MergeResetRadarSnapshots(summary, quiet, now);
    AssertTrue(merged.windowOpen, "fresh summary signal should open merged window");
    AssertEqual(merged.probability24h, 15, "forecast probability should win");
    AssertEqual(merged.probability48h, 30, "forecast 48h probability should win");

    const ResetRadarSnapshot mergedWithEmptyRunway =
        MergeResetRadarSnapshotsWithRunway({}, merged, {}, now);
    AssertTrue(
        mergedWithEmptyRunway.windowOpen,
        "an empty Runway source must not discard an open community window");
    AssertTrue(
        mergedWithEmptyRunway.summaryWindowOpen,
        "community summary state must survive the three-source merge");

    const ResetRadarSnapshot activeMerged =
        MergeResetRadarSnapshots({}, active, now);
    AssertTrue(
        activeMerged.windowOpen,
        "fresh official signal should open merged window");
    AssertEqual(
        activeMerged.message,
        L"Reset announced",
        "official signal summary");

    const long long staleNow = now + 13 * 60 * 60;
    const ResetRadarSnapshot stale =
        MergeResetRadarSnapshots(summary, active, staleNow);
    AssertTrue(
        !stale.windowOpen,
        "signals without a recent successful fetch should be ignored");

    ResetRadarSnapshot refreshedSummary = summary;
    refreshedSummary.checkedAtUnixSeconds = staleNow;
    const ResetRadarSnapshot longRunningWindow =
        MergeResetRadarSnapshots(refreshedSummary, {}, staleNow);
    AssertTrue(
        longRunningWindow.windowOpen,
        "an explicitly open summary may remain active beyond 12 hours");

    const long long expiredNow = now + 73 * 60 * 60;
    refreshedSummary.checkedAtUnixSeconds = expiredNow;
    const ResetRadarSnapshot expiredWindow =
        MergeResetRadarSnapshots(refreshedSummary, {}, expiredNow);
    AssertTrue(
        !expiredWindow.windowOpen,
        "an unclosed summary window must expire after the lifecycle cap");

    const std::string contradictorySummaryJson = R"json({
        "monitored_at": "2026-07-22T06:57:20+08:00",
        "window_open": true,
        "status": "open",
        "window": {
            "open": true,
            "status": "open",
            "opened_at": "2026-07-30T07:50:00+08:00",
            "closed_at": "2026-07-30T08:00:00+08:00"
        }
    })json";
    const ResetRadarSnapshot contradictory =
        ParseResetRadarSummaryJson(contradictorySummaryJson, &error);
    AssertTrue(
        !contradictory.success,
        "closed_at must override contradictory open flags");

    const std::string mismatchedSummaryJson = R"json({
        "window_open": true,
        "status": "open",
        "window": {
            "open": false,
            "status": "closed",
            "opened_at": "2026-07-30T07:50:00+08:00",
            "closed_at": null
        }
    })json";
    const ResetRadarSnapshot mismatched =
        ParseResetRadarSummaryJson(mismatchedSummaryJson, &error);
    AssertTrue(
        !mismatched.success,
        "top-level and nested window states must agree");

    ResetRadarSnapshot futureSummary = summary;
    futureSummary.checkedAtUnixSeconds = now;
    futureSummary.openedAtUnixSeconds = now + 10 * 60;
    const ResetRadarSnapshot futureWindow =
        MergeResetRadarSnapshots(futureSummary, {}, now);
    AssertTrue(
        !futureWindow.windowOpen,
        "a window opened too far in the future must be ignored");
}

void ParsesCodexRunwayResetTodayFeed() {
    const long long now =
        *ParseIso8601UtcSeconds("2026-07-31T12:00:00Z");
    const std::string completedJson = R"json({
        "schemaVersion": 1,
        "generatedAt": "2026-07-31T12:00:00Z",
        "lastSuccessfulCheckAt": "2026-07-31T12:00:00Z",
        "monitor": {
            "status": "ok",
            "errorCode": null
        },
        "events": [{
            "kind": "reset_completed",
            "announcedAt": "2026-07-31T12:00:00Z",
            "effectiveAt": "2026-07-31T12:00:00Z",
            "confidence": 0.9,
            "rationale": "Explicit Codex quota reset announcement.",
            "source": {
                "handle": "thsottiaux",
                "postId": "1951234567890123456",
                "url": "https://x.com/thsottiaux/status/1951234567890123456"
            },
            "scope": {
                "plans": ["all"],
                "windows": ["weekly"]
            }
        }]
    })json";

    std::wstring error;
    const ResetRadarSnapshot completed =
        ParseCodexRunwayResetStatusJson(completedJson, now, &error);
    AssertTrue(completed.success, "Runway feed should parse");
    AssertTrue(completed.todayState == ResetTodayState::Yes,
        "same-day completed reset should resolve to yes");
    AssertTrue(
        !completed.windowOpen,
        "same-day completed reset should close the reset window");
    AssertEqual(completed.confidencePercent, 90, "Runway confidence");
    AssertEqual(
        completed.evidenceUrl,
        L"https://x.com/thsottiaux/status/1951234567890123456",
        "Runway evidence URL");
    AssertTrue(
        completed.latestResetAtUnixSeconds == now,
        "completed reset timestamp should be preserved");

    const std::string scheduledJson = R"json({
        "schemaVersion": 1,
        "generatedAt": "2026-07-31T12:00:00Z",
        "lastSuccessfulCheckAt": "2026-07-31T12:00:00Z",
        "monitor": {
            "status": "ok",
            "errorCode": null
        },
        "events": [{
            "kind": "reset_scheduled",
            "announcedAt": "2026-07-31T10:00:00Z",
            "effectiveAt": "2026-07-31T14:00:00Z",
            "confidence": 0.8,
            "rationale": "Explicit Codex quota reset schedule.",
            "source": {
                "handle": "thsottiaux",
                "postId": "1951234567890123457",
                "url": "https://x.com/thsottiaux/status/1951234567890123457"
            },
            "scope": {
                "plans": ["all"],
                "windows": ["weekly"]
            }
        }]
    })json";
    const ResetRadarSnapshot scheduled =
        ParseCodexRunwayResetStatusJson(scheduledJson, now, &error);
    AssertTrue(scheduled.success, "scheduled Runway feed should parse");
    AssertTrue(
        scheduled.windowOpen,
        "pending same-day schedule should open the reset window");

    const std::string quietJson = R"json({
        "schemaVersion": 1,
        "generatedAt": "2026-07-31T12:00:00Z",
        "lastSuccessfulCheckAt": "2026-07-31T12:00:00Z",
        "monitor": {
            "status": "ok",
            "errorCode": null
        },
        "events": []
    })json";
    const ResetRadarSnapshot quiet =
        ParseCodexRunwayResetStatusJson(quietJson, now, &error);
    AssertTrue(quiet.success, "quiet Runway feed should parse");
    AssertTrue(
        quiet.todayState == ResetTodayState::No,
        "fresh feed without same-day evidence should resolve to no");

    ResetRadarSnapshot forecast;
    forecast.success = true;
    forecast.windowOpen = true;
    forecast.forecastWindowOpen = true;
    forecast.probability24h = 88;
    forecast.updatedAtUnixSeconds = now;
    forecast.probabilityUpdatedAtUnixSeconds = now;
    forecast.checkedAtUnixSeconds = now;
    const ResetRadarSnapshot merged =
        MergeResetRadarSnapshotsWithRunway(quiet, {}, forecast, now);
    AssertTrue(
        merged.runwayPrimary,
        "fresh conclusive Runway state should be primary");
    AssertTrue(
        !merged.windowOpen,
        "Runway no should override a secondary open-window forecast");
    AssertEqual(
        merged.probability24h,
        88,
        "secondary probability should remain available");

    const long long staleNow = now + 31 * 60 * 60;
    const ResetRadarSnapshot stale =
        ParseCodexRunwayResetStatusJson(quietJson, staleNow, &error);
    AssertTrue(stale.success, "stale Runway payload should remain parseable");
    AssertTrue(
        stale.todayState == ResetTodayState::Unknown,
        "stale Runway payload should fail to unknown");

    std::string invalidSourceJson = completedJson;
    const std::string validHost = "https://x.com/thsottiaux/status/";
    const size_t sourcePosition = invalidSourceJson.find(validHost);
    AssertTrue(
        sourcePosition != std::string::npos,
        "test fixture should contain source host");
    invalidSourceJson.replace(
        sourcePosition, validHost.size(), "https://example.com/status/");
    const ResetRadarSnapshot invalid =
        ParseCodexRunwayResetStatusJson(invalidSourceJson, now, &error);
    AssertTrue(
        !invalid.success,
        "non-canonical Runway evidence URL should be rejected");
}

void HasGeneratedVersion() {
    AssertTrue(std::wstring(CODEX_USAGE_VERSION_WIDE).size() > 0, "generated version should not be empty");
}

// The taskbar host rejects a tooltip past ~810 characters with a
// "遇到不适当的参数" message box and no log entry, so the ceiling is
// enforced in code. What matters here is that enforcing it never
// produces a half-written line: a tip ending in "余额 $9." reads as
// corrupt data, while an explicit truncation marker reads as a setting.
void TestTruncateTooltipKeepsShortTextIntact() {
    const std::wstring tip = L" Claude  5h  80%\n Codex  7d  20%\n";
    AssertTrue(TruncateTooltip(tip, 780) == tip,
               "short tooltip is returned unchanged");
}

void TestTruncateTooltipHonoursTheCap() {
    std::wstring tip;
    for (int i = 0; i < 60; ++i) {
        tip += L" provider line with a number 12345/67890\n";
    }
    AssertTrue(tip.size() > 780, "fixture is long enough to be cut");
    const std::wstring out = TruncateTooltip(tip, 780);
    AssertTrue(out.size() <= 780,
               "truncated tooltip must not exceed the cap");
}

void TestTruncateTooltipCutsOnALineBoundary() {
    std::wstring tip;
    for (int i = 0; i < 60; ++i) {
        tip += L" provider line with a number 12345/67890\n";
    }
    const std::wstring out = TruncateTooltip(tip, 780);
    const std::wstring marker = L" \u2026 (\u5DF2\u622A\u65AD)\n";
    AssertTrue(out.size() > marker.size(), "output carries the marker");
    AssertTrue(
        out.compare(out.size() - marker.size(), marker.size(), marker) == 0,
        "truncation is announced with the marker");
    // The character before the marker must close a line, never sit
    // mid-number.
    AssertTrue(out[out.size() - marker.size() - 1] == L'\n',
               "the cut lands on a line boundary");
}

void TestTruncateTooltipHandlesNoNewline() {
    // One enormous line with nowhere to cut cleanly still has to obey
    // the cap, otherwise the host rejects it anyway.
    const std::wstring tip(2000, L'x');
    const std::wstring out = TruncateTooltip(tip, 780);
    AssertTrue(out.size() <= 780,
               "a single long line is still capped");
}


// ---------------------------------------------------------------------------
// Tooltip column alignment
//
// The real tooltip font is proportional, which is what broke the columns in
// the first place, so the fake here is deliberately proportional too: a wide
// glyph, a narrow one, and three space widths that do not divide evenly into
// each other. A monospace fake would pass even if the padder were wrong.
class FakeTextWidth : public ITextWidth {
public:
    double Measure(const std::wstring& text) const override {
        double total = 0.0;
        for (wchar_t ch : text) {
            switch (ch) {
                case L' ':      total += 3.66; break;  // space
                case L' ': total += 2.72; break;  // thin space
                case L' ': total += 1.55; break;  // hair space
                case L'█': total += 9.0; break;   // full block
                case L'─': total += 9.0; break;   // light horizontal
                case L'W':      total += 12.2; break;
                case L'i':      total += 3.20; break;
                default:        total += 7.04; break;
            }
        }
        return total;
    }
};

// A font that reports nothing -- CreateFontIndirect failed, or the tooltip is
// being built on a session with no display. The tooltip must still be built.
class DeadTextWidth : public ITextWidth {
public:
    double Measure(const std::wstring&) const override { return 0.0; }
};

void TestFixedWidthBarNeverChangesWidth() {
    const FakeTextWidth metrics;
    const double full = metrics.Measure(FixedWidthBar(100));
    for (int pct = 0; pct <= 100; pct += 7) {
        AssertTrue(metrics.Measure(FixedWidthBar(pct)) == full,
                   "the bar is the same width at every fill level");
        AssertTrue(FixedWidthBar(pct).size() == 12,
                   "the bar is always 10 cells inside brackets");
    }
}

void TestPadRightLandsWithinOneHairSpace() {
    const FakeTextWidth metrics;
    const PadSpaceWidths spaces = MeasurePadSpaces(metrics);
    AssertTrue(spaces.space > spaces.thin && spaces.thin > spaces.hair,
               "the three padding widths are ordered widest first");
    // Every label in one column block padded to the widest of them: this is
    // exactly what BuildTip does, and the point of the whole exercise.
    const std::wstring labels[] = {
        L" Claude Fable", L" Claude 5h", L" Codex 7d", L" Kiro", L" Wii"};
    double target = 0.0;
    for (const std::wstring& label : labels) {
        target = (std::max)(target, metrics.Measure(label));
    }
    target += spaces.space * 2.0;
    for (const std::wstring& label : labels) {
        const std::wstring padded =
            PadRightToWidth(label, target, metrics, spaces);
        const double error = metrics.Measure(padded) - target;
        AssertTrue(error > -spaces.hair && error < spaces.hair,
                   "a padded label lands within a hair space of the column");
        AssertTrue(padded.compare(0, label.size(), label) == 0,
                   "padding is appended, never inserted");
    }
}

void TestPadRightNeverTrimsOrOvershootsCheaply() {
    const FakeTextWidth metrics;
    const PadSpaceWidths spaces = MeasurePadSpaces(metrics);
    const std::wstring wide = L"WWWWWWWWWW";
    AssertTrue(PadRightToWidth(wide, 10.0, metrics, spaces) == wide,
               "text already past the target is returned untouched");
    // Padding costs characters against the host's ~810 ceiling, so a gap that
    // a plain space can fill must not be built out of hair spaces.
    const std::wstring padded =
        PadRightToWidth(L"i", 3.20 + spaces.space * 3.0, metrics, spaces);
    AssertTrue(padded == L"i   ", "wide gaps are padded with plain spaces");
}

// GDI reports whole pixels, and at 96 dpi it rounds the thin space to the same
// 4 px as a plain space in the tooltip font. Dropping the thin space must not
// take the hair space down with it: that would leave a 4 px padding step and
// put a visible wobble back into the percent column.
class RoundedTextWidth : public ITextWidth {
public:
    double Measure(const std::wstring& text) const override {
        double total = 0.0;
        for (wchar_t ch : text) {
            switch (ch) {
                case L' ':      total += 4.0; break;
                case L' ': total += 4.0; break;  // rounded up to a space
                case L' ': total += 2.0; break;
                default:        total += 7.0; break;
            }
        }
        return total;
    }
};

void TestARoundedThinSpaceDoesNotDiscardTheHairSpace() {
    const RoundedTextWidth metrics;
    const PadSpaceWidths spaces = MeasurePadSpaces(metrics);
    AssertTrue(spaces.space == 4.0, "the plain space survives");
    AssertTrue(spaces.thin == 0.0,
               "a thin space no narrower than a space is dropped");
    AssertTrue(spaces.hair == 2.0,
               "the hair space is kept: it is still narrower than a space");
    const std::wstring padded = PadRightToWidth(L"x", 7.0 + 6.0, metrics, spaces);
    AssertTrue(metrics.Measure(padded) == 13.0,
               "a 6 px gap is filled exactly by a space and a hair space");
}

void TestPaddingIsSkippedWhenTheFontCannotBeMeasured() {
    const DeadTextWidth metrics;
    const PadSpaceWidths spaces = MeasurePadSpaces(metrics);
    AssertTrue(spaces.space == 0.0, "unusable metrics report a zero space");
    AssertTrue(PadRightToWidth(L" Codex 7d", 400.0, metrics, spaces)
                   == L" Codex 7d",
               "without metrics the row is left ragged, not mangled");
}

}  // namespace

int main() {
    TestFixedWidthBarNeverChangesWidth();
    TestPadRightLandsWithinOneHairSpace();
    TestPadRightNeverTrimsOrOvershootsCheaply();
    TestARoundedThinSpaceDoesNotDiscardTheHairSpace();
    TestPaddingIsSkippedWhenTheFontCannotBeMeasured();
    TestTruncateTooltipKeepsShortTextIntact();
    TestTruncateTooltipHonoursTheCap();
    TestTruncateTooltipCutsOnALineBoundary();
    TestTruncateTooltipHandlesNoNewline();
    ParsesUsageWindows();
    ClassifiesPrimaryWindowWithoutSecondaryWindow();
    ParsesOptionalClaudeFableWindow();
    HidesClaudeFableWindowWhenPlanDoesNotReportIt();
    ParsesLegacyClaudeFableWindow();
    BuildsSanitizedClaudeUsageExport();
    FormatsDisplayText();
    AdvancesSubscriptionExpiryToNextCycle();
    ParsesIso8601Timestamps();
    ClassifiesFreshnessAndCountdownWindow();
    BuildsMultiLineSuccessTooltip();
    ParsesResetCreditsAndExpiryWarning();
    ParsesAndMergesResetRadarSignals();
    ParsesCodexRunwayResetTodayFeed();
    HasGeneratedVersion();
    return 0;
}
