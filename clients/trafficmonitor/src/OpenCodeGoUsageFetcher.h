#pragma once

#include "ProxyHelper.h"

#include <string>

// OpenCode Go subscription usage.
//
// OpenCode Go is billed in dollars: $12 / rolling 5h, $30 / rolling 7d,
// $60 / rolling 30d (opencode.ai/docs/go). There is NO official REST API for
// remaining quota; the official web console (opencode.ai/workspace/<id>/go)
// renders rollingUsage/weeklyUsage/monthlyUsage from an internal RPC. Both
// third-party projects (lvmiao233/QuotaHub, Yinxe/opencode-tui-usage) fetch
// that internal payload with the browser session cookie.
//
// This fetcher therefore:
//   1. (server, primary) calls the internal RPC with the user's browser
//      `auth` cookie (configured in [OpenCodeGo] AuthCookie=) and parses the
//      exact same usagePercent values the console shows;
//   2. (server, fallback) fetches the /workspace/<id>/go HTML page and parses
//      the same embedded payload;
//   3. (local, last resort) aggregates per-message USD cost from the local
//      opencode.db, which only covers requests made through the local
//      opencode client and may differ from the server-side total.
struct OpenCodeGoWindow {
    int usedPercent = -1;       // server-side used %, identical to the console
    long long resetInSec = -1;  // seconds until this window resets
    std::string status;         // "active" / "unlimited" / ...
};

struct OpenCodeGoUsageData {
    bool success = false;
    std::wstring errorMessage;
    std::string source;         // "server" | "local"
    OpenCodeGoWindow rolling;   // rolling 5h
    OpenCodeGoWindow weekly;    // weekly
    OpenCodeGoWindow monthly;   // monthly
    // Local-DB fallback fields (only meaningful when source == "local"):
    double cost5h = 0.0;
    double costWeek = 0.0;
    double costMonth = 0.0;
    int localPct5h = -1;        // remaining % of the $12 5h cap, -1 = no data
    bool hasGoUsage = false;
    double lastSuccessTime = 0;
};

class OpenCodeGoUsageFetcher {
public:
    OpenCodeGoUsageData Fetch();

    void SetEnabled(bool enabled) { enabled_ = enabled; }
    bool IsEnabled() const { return enabled_; }
    void SetProxyConfig(const ProxyConfig& config) { proxyConfig_ = config; }

    // Browser session cookie for opencode.ai (the `auth` cookie value, with
    // or without the "auth=" prefix). Without it the server paths fail and
    // the local-DB fallback is used instead.
    void SetAuthCookie(const std::string& cookie) { authCookie_ = cookie; }
    void SetWorkspaceId(const std::string& id) { workspaceId_ = id; }

    // Override of the local DB path (used by tests).
    void SetDbPath(const std::wstring& path) { dbPathOverride_ = path; }

    // Dollar caps for each rolling window (opencode.ai/docs/go).
    static constexpr double kCap5h = 12.0;
    static constexpr double kCapWeek = 30.0;
    static constexpr double kCapMonth = 60.0;

private:
    std::wstring ResolveDbPath() const;
    std::string ToUtf8(const std::wstring& text) const;

    bool FetchFromServer(OpenCodeGoUsageData& out);
    bool ResolveWorkspaceIdFromServer(OpenCodeGoUsageData& out,
                                      std::string& workspaceId);
    bool FetchFromLocalDb(OpenCodeGoUsageData& out);

    bool enabled_ = false;
    ProxyConfig proxyConfig_;
    std::string authCookie_;
    std::string workspaceId_;
    std::wstring dbPathOverride_;
    double lastSuccessTime_ = 0;
};
