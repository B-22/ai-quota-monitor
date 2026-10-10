#pragma once

#include "ClaudeUsageParser.h"
#include "ProxyHelper.h"

#include <optional>
#include <string>
#include <vector>

class ClaudeUsageFetcher {
public:
    ClaudeUsageData Fetch();
    void SetProxyConfig(const ProxyConfig& config) { proxyConfig_ = config; }

private:
    struct AuthHeaders {
        std::string accessToken;
        std::string error;
        bool forceRefresh = false;
    };

    AuthHeaders GetHeaders(bool forceRefresh = false);
    std::optional<std::string> RefreshCliToken(const std::string& refreshToken);
    std::optional<std::string> HttpGet(const std::string& host, const std::string& path,
        const std::vector<std::wstring>& headers, unsigned long& statusCode, std::wstring* errorMessage);

    // Cached data
    ClaudeUsageData cachedData_;
    double nextFetchTime_ = 0;
    double backoffUntil_ = 0;
    double backoffSeconds_ = 0;
    bool refreshInProgress_ = false;
    bool profileFetched_ = false;
    std::string cachedSubscriptionStatus_;
    ProxyConfig proxyConfig_;
};
