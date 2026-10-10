#pragma once

#include <string>

struct ClaudeUsageData {
    bool success = false;
    std::wstring errorMessage;
    int fiveHourPercent = -1;  // used percent
    int sevenDayPercent = -1;
    int fableWeeklyPercent = -1;  // optional model-scoped weekly used percent
    std::string fiveHourResetAt;  // ISO 8601 UTC
    std::string sevenDayResetAt;
    std::string fableWeeklyResetAt;
    double lastSuccessTime = 0;  // Unix seconds

    // Credits / overage
    long long usedCredits = 0;   // raw integer from API
    std::string currency;        // e.g., "USD"
    int decimalPlaces = 2;

    // Subscription
    std::string subscriptionStatus; // "active", "canceled", etc.
};

// Parses Anthropic's OAuth usage response. Model-scoped limits such as Fable
// are optional and remain unavailable when the account does not report them.
ClaudeUsageData ParseClaudeUsageJson(
    const std::string& json,
    std::wstring* errorMessage = nullptr);
