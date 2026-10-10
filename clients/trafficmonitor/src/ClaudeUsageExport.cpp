#include "ClaudeUsageExport.h"

#include <algorithm>
#include <sstream>

namespace {

int NormalizeUsedPercent(int value) {
    return value >= 0 && value <= 100 ? value : -1;
}

void AppendWindow(
    std::ostringstream& out,
    const char* section,
    int usedPercent,
    const std::string& resetUtc,
    const std::string& resetLocal) {
    const int used = NormalizeUsedPercent(usedPercent);
    out << '[' << section << "]\n"
        << "available=" << (used >= 0 ? 1 : 0) << '\n'
        << "used_percent=" << used << '\n'
        << "remaining_percent=" << (used >= 0 ? 100 - used : -1) << '\n'
        << "reset_at_utc=" << resetUtc << '\n'
        << "reset_at_local=" << resetLocal << "\n\n";
}

}  // namespace

std::string ClassifyClaudeUsageExportStatus(
    const ClaudeUsageData& data,
    long long generatedAtUnix,
    long long staleAfterSeconds) {
    if (!data.success) return "unavailable";
    if (data.lastSuccessTime <= 0 || generatedAtUnix <= 0) return "stale";

    const long long fetchedAt = static_cast<long long>(data.lastSuccessTime);
    const long long age = std::max(0LL, generatedAtUnix - fetchedAt);
    if (!data.errorMessage.empty() || age > std::max(0LL, staleAfterSeconds)) {
        return "stale";
    }
    return "ok";
}

bool ShouldWriteClaudeUsageExport(
    long long secondsSinceLastWrite,
    int refreshIntervalMinutes,
    const std::string& currentStatus,
    const std::string& previousStatus) {
    if (secondsSinceLastWrite < 0) return true;
    if (currentStatus != previousStatus) return true;
    const long long intervalSeconds =
        static_cast<long long>(std::max(1, refreshIntervalMinutes)) * 60;
    return secondsSinceLastWrite >= intervalSeconds;
}

std::string BuildClaudeUsageExportText(
    const ClaudeUsageExportRecord& record) {
    std::ostringstream out;
    out << "# Sanitized Claude quota snapshot for local automation.\n"
        << "# No OAuth token, cookie, account, Credits, or billing data is included.\n"
        << "schema_version=1\n"
        << "status=" << record.status << '\n'
        << "generated_at_unix=" << record.generatedAtUnix << '\n'
        << "generated_at_local=" << record.generatedAtLocal << '\n'
        << "source_fetched_at_unix=" << record.sourceFetchedAtUnix << '\n'
        << "source_age_seconds=" << record.sourceAgeSeconds << '\n'
        << "refresh_interval_minutes=" << record.refreshIntervalMinutes
        << "\n\n";

    AppendWindow(
        out, "claude_5h", record.fiveHourUsedPercent,
        record.fiveHourResetUtc, record.fiveHourResetLocal);
    AppendWindow(
        out, "claude_7d", record.sevenDayUsedPercent,
        record.sevenDayResetUtc, record.sevenDayResetLocal);
    AppendWindow(
        out, "claude_fable_7d", record.fableWeeklyUsedPercent,
        record.fableWeeklyResetUtc, record.fableWeeklyResetLocal);
    return out.str();
}
