#pragma once
#include "HubUsageParser.h"
#include <utility>

struct HubConfig {
    bool enabled = false;
    bool localFallback = true;
    bool localFallbackClaude = false;
    bool localFallbackCodex = false;
    std::wstring url;
    std::wstring token;
    std::string claudeAccountKey;
    std::string codexAccountKey;
};

// A Hub display host must opt in per provider before touching local credentials.
// The callback seam also lets tests prove that no provider request was attempted.
template <typename T, typename LocalFetch>
T SelectHubOrLocal(const std::optional<T>& quota, const HubConfig& config,
    bool providerFallback, LocalFetch fetchLocal) {
    if (quota) return *quota;
    if (!config.enabled || (config.localFallback && providerFallback)) return fetchLocal();
    return T{};
}

class HubUsageFetcher {
public:
    void SetConfig(HubConfig config) { config_ = std::move(config); }
    const HubConfig& Config() const { return config_; }
    HubUsageSnapshot Fetch() const;
private:
    HubConfig config_;
};
