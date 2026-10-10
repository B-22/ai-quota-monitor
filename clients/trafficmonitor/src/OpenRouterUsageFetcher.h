#pragma once

#include "ProxyHelper.h"

#include <string>

// OpenRouter credit balance and free-model daily request count.
//
// Two independent sources, because no single key can serve both:
//
//   1. GET /api/v1/credits  — normal API key (sk-or-v1-...).
//      Returns {total_credits, total_usage}. Balance is the difference.
//      total_credits is LIFETIME PURCHASED credit, which is also what
//      decides the free-model daily allowance: >= $10 ever purchased means
//      1000 requests/day, below that 50/day, and the unlock is permanent
//      even after the balance is spent back down.
//
//   2. POST /api/v1/analytics/query — MANAGEMENT key only.
//      A normal key gets 403 "Only management keys can access analytics".
//      This is the only way to obtain a request COUNT: /api/v1/key reports
//      usage_daily in USD, which stays at 0.00 forever on free models, and
//      the X-RateLimit-* headers only appear on the 429 response itself.
//
// Source 2 is optional. Without a management key the ring falls back to the
// credit balance and the daily count reads "--".
struct OpenRouterUsageData {
    bool success = false;          // credits lookup succeeded
    std::wstring errorMessage;

    // --- credits (source 1) ---
    bool hasCredits = false;
    double totalCredits = 0.0;     // lifetime purchased, USD
    double totalUsage = 0.0;       // lifetime spent, USD
    double balance = 0.0;          // totalCredits - totalUsage
    int freeDailyLimit = 0;        // derived: 50 or 1000
    bool tierFromApi = false;      // false when overridden in the ini

    // --- analytics (source 2, optional) ---
    bool hasRequestCount = false;
    long long requestsToday = -1;  // free+paid requests in the current UTC day
    int requestUsedPercent = -1;   // requestsToday / freeDailyLimit, 0..100
    std::wstring analyticsError;   // kept separate: a management-key problem
                                   // must not blank out the balance display

    double lastSuccessTime = 0;
};

class OpenRouterUsageFetcher {
public:
    OpenRouterUsageData Fetch();

    void SetEnabled(bool enabled) { enabled_ = enabled; }
    bool IsEnabled() const { return enabled_; }
    void SetProxyConfig(const ProxyConfig& config) { proxyConfig_ = config; }

    // Normal API key (sk-or-v1-...). Drives the balance and the tier.
    void SetApiKey(const std::string& key) { apiKey_ = key; }
    // Management key from Settings -> Management Keys. Optional; drives the
    // today's-request-count readout only.
    void SetManagementKey(const std::string& key) { managementKey_ = key; }

    // 0 = derive from total_credits (default). 50/1000 force a tier, for
    // accounts where the purchased-credit heuristic does not apply.
    void SetFreeDailyLimitOverride(int limit) { freeDailyLimitOverride_ = limit; }

    // Test seam: redirect both calls at a local stub server.
    void SetHostOverride(const std::wstring& host) { hostOverride_ = host; }

    static constexpr double kTierThresholdUsd = 10.0;
    static constexpr int kFreeDailyLow = 50;
    static constexpr int kFreeDailyHigh = 1000;

private:
    // Analytics is the expensive call and its data only moves when requests
    // are made; the balance is cheap. Both still share one refresh tick.
    static constexpr double kMinInterval = 60.0;
    static constexpr double kBackoffMin = 120.0;
    static constexpr double kBackoffMax = 900.0;

    bool FetchCredits(OpenRouterUsageData& out);
    bool FetchTodayRequestCount(OpenRouterUsageData& out);

    bool enabled_ = false;
    ProxyConfig proxyConfig_;
    std::string apiKey_;
    std::string managementKey_;
    int freeDailyLimitOverride_ = 0;
    std::wstring hostOverride_;

    double nextFetchTime_ = 0.0;
    double backoffUntil_ = 0.0;
    double backoffSeconds_ = 0.0;
    OpenRouterUsageData cachedData_;
};

// Exposed for tests.
//
// UTC-midnight bounds of the day containing `nowUnix`, formatted as the
// ISO-8601 instants the analytics API takes. The free-model quota resets on
// the UTC boundary, so this window is exactly the quota window.
void OpenRouterUtcDayRange(long long nowUnix,
                           std::string* startIso,
                           std::string* endIso);

// Parses {"data":{"total_credits":10,"total_usage":0.0073}}.
bool ParseOpenRouterCredits(const std::string& json, OpenRouterUsageData* out);

// Parses the analytics envelope
// {"data":{"data":[{"request_count":"327"}],"metadata":{...}}} and sums
// request_count across rows. Counts arrive as either JSON numbers or strings,
// so both are accepted.
bool ParseOpenRouterRequestCount(const std::string& json,
                                 long long* requestsOut,
                                 std::wstring* errorMessage);
