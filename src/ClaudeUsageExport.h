#pragma once

#include "ClaudeUsageParser.h"

#include <string>

struct ClaudeUsageExportRecord {
    std::string status;  // ok, stale, unavailable
    long long generatedAtUnix = 0;
    std::string generatedAtLocal;
    long long sourceFetchedAtUnix = 0;
    long long sourceAgeSeconds = -1;
    int refreshIntervalMinutes = 10;

    int fiveHourUsedPercent = -1;
    std::string fiveHourResetUtc;
    std::string fiveHourResetLocal;

    int sevenDayUsedPercent = -1;
    std::string sevenDayResetUtc;
    std::string sevenDayResetLocal;

    int fableWeeklyUsedPercent = -1;
    std::string fableWeeklyResetUtc;
    std::string fableWeeklyResetLocal;
};

// Export data older than this is explicitly marked stale so downstream
// automation can fail closed instead of spending quota based on old values.
constexpr long long kClaudeUsageExportStaleAfterSeconds = 15 * 60;

std::string ClassifyClaudeUsageExportStatus(
    const ClaudeUsageData& data,
    long long generatedAtUnix,
    long long staleAfterSeconds = kClaudeUsageExportStaleAfterSeconds);

bool ShouldWriteClaudeUsageExport(
    long long secondsSinceLastWrite,
    int refreshIntervalMinutes,
    const std::string& currentStatus,
    const std::string& previousStatus);

std::string BuildClaudeUsageExportText(
    const ClaudeUsageExportRecord& record);
