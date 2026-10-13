#pragma once

#include "ClaudeUsageParser.h"
#include "CodexUsageCore.h"

#include <optional>
#include <string>

struct HubUsageSnapshot {
    bool responseValid = false;
    std::optional<ClaudeUsageData> claude;
    std::optional<UsageSnapshot> codex;
    long long codexCollectedAt = 0;
    std::wstring errorMessage;
};

// Freshness belongs to each provider row, never to the aggregate updatedAt.
// Account selectors are sanitized Hub identifiers, not provider credentials.
HubUsageSnapshot ParseHubUsageJson(
    const std::string& json, long long nowUnix,
    const std::string& claudeAccountKey = {},
    const std::string& codexAccountKey = {});
