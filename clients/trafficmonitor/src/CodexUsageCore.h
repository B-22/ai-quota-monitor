#pragma once

#include <optional>
#include <string>
#include <vector>

struct UsageWindow {
    int usedPercent = 0;
    int remainingPercent = -1;
    int windowSeconds = 0;
    int resetAfterSeconds = 0;
    long long resetAtUnixSeconds = 0;
};

struct ResetCredit {
    std::wstring status;
    std::wstring title;
    long long grantedAtUnixSeconds = 0;
    long long expiresAtUnixSeconds = 0;
};

struct ResetCreditsSnapshot {
    bool success = false;
    int availableCount = 0;
    long long fetchedAtUnixSeconds = 0;
    std::wstring errorMessage;
    std::vector<ResetCredit> credits;
};

enum class ResetTodayState {
    Unknown,
    No,
    Yes,
};

struct ResetRadarSnapshot {
    bool success = false;
    bool windowOpen = false;
    bool summaryWindowOpen = false;
    bool forecastWindowOpen = false;
    bool runwaySourceAvailable = false;
    bool runwayPrimary = false;
    ResetTodayState todayState = ResetTodayState::Unknown;
    int probability24h = -1;
    int probability48h = -1;
    int confidencePercent = -1;
    long long updatedAtUnixSeconds = 0;
    long long openedAtUnixSeconds = 0;
    long long closedAtUnixSeconds = 0;
    long long probabilityUpdatedAtUnixSeconds = 0;
    long long checkedAtUnixSeconds = 0;
    long long nextScheduledAtUnixSeconds = 0;
    long long latestResetAtUnixSeconds = 0;
    std::wstring message;
    std::wstring evidenceKind;
    std::wstring evidenceUrl;
    std::vector<std::wstring> scopePlans;
    std::vector<std::wstring> scopeWindows;
    std::wstring errorMessage;
};

struct UsageSnapshot {
    bool success = false;
    std::wstring email;
    std::wstring planType;
    std::wstring errorMessage;
    UsageWindow fiveHour;
    UsageWindow weekly;
    ResetCreditsSnapshot resetCredits;
    ResetRadarSnapshot resetRadar;
};

enum class FreshnessLevel {
    Fresh,
    Warning,
    Stale,
};

// Trims a taskbar tooltip to the host ceiling, on a line boundary.
//
// TrafficMonitor refuses a tooltip longer than roughly 810 characters:
// it pops a "遇到不适当的参数" message box on the first refresh that goes
// over, and nothing lands in error.log. Cutting mid-line would leave a
// half-written number, which reads as broken data rather than as a
// truncation, so the cut lands on a newline and says what it did.
//
// The result never exceeds maxChars.
std::wstring TruncateTooltip(const std::wstring& text, size_t maxChars);

// ---------------------------------------------------------------------------
// Tooltip column alignment
//
// The taskbar tooltip is drawn by GDI in the system status font -- Microsoft
// YaHei UI 9pt here -- which is PROPORTIONAL. Measured with GDI at 96 dpi: a
// space is 4 px, '#' is 8 px and '.' is 3 px. Two consequences drove this
// code:
//
//   * padding a label with spaces can never line up a column, because the
//     labels themselves differ by more than a space: "Claude Fable" is 83 px
//     and "Codex 5h" is 63 px, and
//   * an ASCII bar changes width with its fill level: "[##########]" is 96 px
//     while "[..........]" is 46 px, so the percent column jumped by half a
//     bar between refreshes.
//
// The bar is fixed by drawing it with equal-advance block glyphs. The columns
// are fixed by measuring the real font at run time and padding to a pixel
// target with a space, a thin space U+2009 and a hair space U+200A, keeping
// only the ones the font actually renders narrower than the one above them.
//
// Padding costs characters, and the host caps the tooltip near 810 of them
// (see TruncateTooltip), so the padder always uses the widest space that
// still fits.
class ITextWidth {
public:
    virtual ~ITextWidth() = default;
    // Advance width of `text` in pixels, in the font the tooltip is drawn in.
    virtual double Measure(const std::wstring& text) const = 0;
};

// Width of the three padding characters, resolved once per tooltip build.
struct PadSpaceWidths {
    double space = 0.0;  // U+0020
    double thin = 0.0;   // U+2009
    double hair = 0.0;   // U+200A
};

PadSpaceWidths MeasurePadSpaces(const ITextWidth& metrics);

// Appends padding to `text` until it is `targetPx` wide. Never trims: a label
// already past the target is returned unchanged (its row is the one that set
// the column width, or it is an outlier that overflows on purpose).
std::wstring PadRightToWidth(const std::wstring& text, double targetPx,
                             const ITextWidth& metrics,
                             const PadSpaceWidths& spaces);

// A 10-cell progress bar whose width does not change with the fill level.
//
// The glyphs are picked for GDI, which is what actually draws the tooltip, and
// GDI disagrees with GDI+ here. Measured in Microsoft YaHei UI 9pt at 96 dpi:
// U+2588 FULL BLOCK and U+2500 BOX DRAWINGS LIGHT HORIZONTAL are both 9 px, so
// the bar is 12 characters and the same width however full it is. The shade
// blocks U+2591/U+2592/U+2593 are the obvious choice and the wrong one: GDI
// substitutes a fallback font for them that draws solid black at this size --
// indistinguishable from a full block -- and measures 12 px, which would put
// the jitter straight back.
std::wstring FixedWidthBar(int remainingPercent);

std::wstring Utf8ToWide(const std::string& input);
std::wstring FormatIsoDateAsMonthDay(const std::string& text);
std::string AdvanceExpiryToFuture(
    const std::string& anchorDate, const std::string& cycle,
    int todayYear, int todayMonth, int todayDay);
UsageSnapshot ParseUsageJson(const std::string& jsonText, std::wstring* errorMessage);
ResetCreditsSnapshot ParseResetCreditsJson(const std::string& jsonText, std::wstring* errorMessage);
ResetRadarSnapshot ParseResetRadarSummaryJson(const std::string& jsonText, std::wstring* errorMessage);
ResetRadarSnapshot ParseResetForecastJson(const std::string& jsonText, std::wstring* errorMessage);
ResetRadarSnapshot ParseCodexRunwayResetStatusJson(
    const std::string& jsonText,
    long long nowUnixSeconds,
    std::wstring* errorMessage);
ResetRadarSnapshot MergeResetRadarSnapshots(
    const ResetRadarSnapshot& summary,
    const ResetRadarSnapshot& forecast,
    long long nowUnixSeconds,
    long long maximumSignalAgeSeconds = 12 * 60 * 60);
ResetRadarSnapshot MergeResetRadarSnapshotsWithRunway(
    const ResetRadarSnapshot& runway,
    const ResetRadarSnapshot& summary,
    const ResetRadarSnapshot& forecast,
    long long nowUnixSeconds,
    long long maximumSignalAgeSeconds = 12 * 60 * 60);
std::optional<long long> ParseIso8601UtcSeconds(const std::string& text);
long long EarliestAvailableResetCreditExpiry(
    const ResetCreditsSnapshot& snapshot,
    long long nowUnixSeconds);
bool IsResetCreditExpiringSoon(
    const ResetCreditsSnapshot& snapshot,
    long long nowUnixSeconds,
    int warningHours);
std::wstring FormatResetCreditWarning(
    int availableCount,
    long long secondsRemaining);
FreshnessLevel ClassifyFreshness(double ageSeconds);
FreshnessLevel ClassifyFreshnessForDisplay(double ageSeconds, bool refreshInProgress);
bool ShouldReplaceResetWithFreshness(double ageSeconds);
bool ShouldShowCountdown(long long secondsRemaining, int showBeforeHours);
std::wstring FormatRemainingPercent(int remainingPercent);
std::wstring FormatResetAfter(int seconds);
std::wstring BuildUsageTooltip(const UsageSnapshot& snapshot);
