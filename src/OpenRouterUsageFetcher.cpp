#include "OpenRouterUsageFetcher.h"

#include "JsonLite.h"

#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <cstdio>
#include <ctime>
#include <string>
#include <vector>

#pragma comment(lib, "winhttp.lib")

namespace {

constexpr const wchar_t* kHost = L"openrouter.ai";
constexpr const wchar_t* kUserAgent = L"AIUsage/1.0 (openrouter)";

double NowSeconds() {
    return static_cast<double>(time(nullptr));
}

std::wstring Utf8ToWide(const std::string& s) {
    if (s.empty()) return {};
    const int len = MultiByteToWideChar(CP_UTF8, 0, s.data(),
                                        static_cast<int>(s.size()), nullptr, 0);
    if (len <= 0) return {};
    std::wstring out(len, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                        out.data(), len);
    return out;
}

// One request against openrouter.ai. `verb` is L"GET" or L"POST"; a non-empty
// body is sent as application/json.
std::string HttpJson(const ProxyConfig& proxy,
                     const std::wstring& host,
                     const wchar_t* verb,
                     const std::wstring& path,
                     const std::string& bearer,
                     const std::string& body,
                     unsigned long& statusCode,
                     std::wstring* errorMessage) {
    if (errorMessage) errorMessage->clear();
    statusCode = 0;

    // verifyExitIp=false: OpenRouter is a read-only quota lookup and the
    // account is not IP-bound, so the AllowedExitIPs fail-closed check would
    // only produce noise on a rotating proxy node. Same call as OpenCode Go.
    HINTERNET session = OpenHttpSession(proxy, kUserAgent, host.c_str(),
                                        errorMessage, /*verifyExitIp=*/false);
    if (!session) {
        if (errorMessage && errorMessage->empty()) *errorMessage = L"WinHttpOpen failed";
        return {};
    }

    std::string response;
    HINTERNET connect = nullptr, request = nullptr;
    do {
        connect = WinHttpConnect(session, host.c_str(),
                                 INTERNET_DEFAULT_HTTPS_PORT, 0);
        if (!connect) { if (errorMessage) *errorMessage = L"WinHttpConnect failed"; break; }
        request = WinHttpOpenRequest(connect, verb, path.c_str(), nullptr,
                                     WINHTTP_NO_REFERER,
                                     WINHTTP_DEFAULT_ACCEPT_TYPES,
                                     WINHTTP_FLAG_SECURE);
        if (!request) { if (errorMessage) *errorMessage = L"WinHttpOpenRequest failed"; break; }

        const std::wstring auth = L"Authorization: Bearer " + Utf8ToWide(bearer);
        WinHttpAddRequestHeaders(request, auth.c_str(), static_cast<DWORD>(-1L),
                                 WINHTTP_ADDREQ_FLAG_ADD);
        if (!body.empty()) {
            WinHttpAddRequestHeaders(request, L"Content-Type: application/json",
                                     static_cast<DWORD>(-1L), WINHTTP_ADDREQ_FLAG_ADD);
        }

        WinHttpSetTimeouts(request, 8000, 8000, 8000, 8000);
        if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                body.empty() ? WINHTTP_NO_REQUEST_DATA
                                             : const_cast<char*>(body.data()),
                                static_cast<DWORD>(body.size()),
                                static_cast<DWORD>(body.size()), 0)) {
            if (errorMessage) *errorMessage = L"WinHttpSendRequest failed";
            break;
        }
        if (!WinHttpReceiveResponse(request, nullptr)) {
            if (errorMessage) *errorMessage = L"WinHttpReceiveResponse failed";
            break;
        }
        DWORD status = 0;
        DWORD statusSize = sizeof(status);
        WinHttpQueryHeaders(request,
                            WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                            WINHTTP_NO_HEADER_INDEX);
        statusCode = status;

        // Read the body even on an error status: OpenRouter puts a usable
        // reason in {"error":{"message":...}} and it belongs in the tooltip.
        char buf[8192];
        DWORD read = 0;
        while (WinHttpReadData(request, buf, sizeof(buf), &read) && read > 0) {
            response.append(buf, read);
            read = 0;
        }
    } while (false);

    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return response;
}

// Pulls {"error":{"message":"..."}} out of a failed response.
std::wstring ExtractApiError(const std::string& json, unsigned long status) {
    jsonlite::Parser parser(json);
    if (auto root = parser.Parse(); root.has_value()) {
        if (const jsonlite::Value* err = root->Find("error"); err != nullptr) {
            if (const jsonlite::Value* msg = err->Find("message"); msg != nullptr) {
                if (auto text = msg->AsString(); text.has_value()) {
                    return Utf8ToWide(std::string(*text));
                }
            }
        }
    }
    return L"HTTP " + std::to_wstring(status);
}

}  // namespace

void OpenRouterUtcDayRange(long long nowUnix,
                           std::string* startIso,
                           std::string* endIso) {
    // Truncate to the UTC day. 86400-second arithmetic is exact here because
    // Unix time has no leap seconds.
    const long long dayStart = (nowUnix / 86400) * 86400;
    const auto format = [](long long unixSeconds) {
        const time_t t = static_cast<time_t>(unixSeconds);
        tm utc{};
        gmtime_s(&utc, &t);
        char buf[32];
        snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02dZ",
                 utc.tm_year + 1900, utc.tm_mon + 1, utc.tm_mday,
                 utc.tm_hour, utc.tm_min, utc.tm_sec);
        return std::string(buf);
    };
    if (startIso) *startIso = format(dayStart);
    // End is the next UTC midnight rather than "now": asking for the whole
    // day costs nothing and avoids a half-open window skewing the bucket.
    if (endIso) *endIso = format(dayStart + 86400);
}

bool ParseOpenRouterCredits(const std::string& json, OpenRouterUsageData* out) {
    if (out == nullptr) return false;
    jsonlite::Parser parser(json);
    auto root = parser.Parse();
    if (!root.has_value()) return false;

    const jsonlite::Value* data = root->Find("data");
    if (data == nullptr) return false;
    const jsonlite::Value* total = data->Find("total_credits");
    const jsonlite::Value* used = data->Find("total_usage");
    if (total == nullptr || used == nullptr) return false;

    auto totalValue = total->AsNumber();
    auto usedValue = used->AsNumber();
    if (!totalValue.has_value() || !usedValue.has_value()) return false;

    out->totalCredits = *totalValue;
    out->totalUsage = *usedValue;
    out->balance = *totalValue - *usedValue;
    out->hasCredits = true;
    return true;
}

bool ParseOpenRouterRequestCount(const std::string& json,
                                 long long* requestsOut,
                                 std::wstring* errorMessage) {
    if (requestsOut == nullptr) return false;
    jsonlite::Parser parser(json);
    auto root = parser.Parse();
    if (!root.has_value()) {
        if (errorMessage) *errorMessage = L"analytics JSON parse failed";
        return false;
    }

    // {"data":{"data":[...],"metadata":{...}}} — the outer "data" is the
    // usual OpenRouter envelope, the inner one is the row array.
    const jsonlite::Value* envelope = root->Find("data");
    if (envelope == nullptr) {
        if (errorMessage) *errorMessage = L"analytics response has no data";
        return false;
    }
    const jsonlite::Value* rowsValue = envelope->Find("data");
    // Tolerate the envelope being dropped, i.e. a bare {"data":[...]}.
    const jsonlite::Value::Array* rows =
        rowsValue != nullptr ? rowsValue->AsArray() : envelope->AsArray();
    if (rows == nullptr) {
        if (errorMessage) *errorMessage = L"analytics rows missing";
        return false;
    }

    long long total = 0;
    for (const jsonlite::Value& row : *rows) {
        const jsonlite::Value* count = row.Find("request_count");
        if (count == nullptr) continue;
        // Count metrics come back as either a number or a decimal string.
        if (auto number = count->AsNumber(); number.has_value()) {
            total += static_cast<long long>(*number);
        } else if (auto text = count->AsString(); text.has_value()) {
            total += _atoi64(std::string(*text).c_str());
        }
    }

    // Zero rows is a legitimate answer: no requests yet today.
    *requestsOut = total;
    return true;
}

bool OpenRouterUsageFetcher::FetchCredits(OpenRouterUsageData& out) {
    if (apiKey_.empty()) {
        out.errorMessage = L"[OpenRouter] ApiKey 未配置";  // 未配置
        return false;
    }

    const std::wstring host = hostOverride_.empty() ? kHost : hostOverride_;
    unsigned long status = 0;
    std::wstring err;
    const std::string body = HttpJson(proxyConfig_, host, L"GET",
                                      L"/api/v1/credits", apiKey_, {},
                                      status, &err);
    if (body.empty() && status == 0) {
        out.errorMessage = err.empty() ? L"credits request failed" : err;
        return false;
    }
    if (status < 200 || status >= 300) {
        out.errorMessage = ExtractApiError(body, status);
        return false;
    }
    if (!ParseOpenRouterCredits(body, &out)) {
        out.errorMessage = L"credits JSON parse failed";
        return false;
    }

    if (freeDailyLimitOverride_ > 0) {
        out.freeDailyLimit = freeDailyLimitOverride_;
        out.tierFromApi = false;
    } else {
        // Lifetime purchased credit, not the current balance: the 1000/day
        // unlock is permanent once $10 has ever been bought.
        out.freeDailyLimit = out.totalCredits >= kTierThresholdUsd
                                 ? kFreeDailyHigh
                                 : kFreeDailyLow;
        out.tierFromApi = true;
    }
    return true;
}

bool OpenRouterUsageFetcher::FetchTodayRequestCount(OpenRouterUsageData& out) {
    if (managementKey_.empty()) {
        // Not an error: the management key is optional.
        return false;
    }

    std::string startIso, endIso;
    OpenRouterUtcDayRange(static_cast<long long>(NowSeconds()), &startIso, &endIso);

    // No dimensions: one bucket for the whole UTC day is all the ring needs,
    // and leaving dimensions out keeps the row count at 1 so `truncated`
    // cannot silently drop part of the total.
    std::string body =
        "{\"metrics\":[\"request_count\"],\"granularity\":\"day\","
        "\"time_range\":{\"start\":\"" + startIso +
        "\",\"end\":\"" + endIso + "\"}}";

    const std::wstring host = hostOverride_.empty() ? kHost : hostOverride_;
    unsigned long status = 0;
    std::wstring err;
    const std::string response = HttpJson(proxyConfig_, host, L"POST",
                                          L"/api/v1/analytics/query",
                                          managementKey_, body, status, &err);
    if (response.empty() && status == 0) {
        out.analyticsError = err.empty() ? L"analytics request failed" : err;
        return false;
    }
    if (status < 200 || status >= 300) {
        out.analyticsError = ExtractApiError(response, status);
        return false;
    }

    long long requests = 0;
    std::wstring parseError;
    if (!ParseOpenRouterRequestCount(response, &requests, &parseError)) {
        out.analyticsError = parseError;
        return false;
    }

    out.requestsToday = requests;
    out.hasRequestCount = true;
    if (out.freeDailyLimit > 0) {
        const double pct = 100.0 * static_cast<double>(requests) /
                           static_cast<double>(out.freeDailyLimit);
        out.requestUsedPercent = std::clamp(static_cast<int>(pct + 0.5), 0, 100);
    }
    return true;
}

OpenRouterUsageData OpenRouterUsageFetcher::Fetch() {
    if (!enabled_) return {};

    const double now = NowSeconds();
    if (now < backoffUntil_ || now < nextFetchTime_) {
        return cachedData_;
    }

    OpenRouterUsageData data;
    if (!FetchCredits(data)) {
        // Keep showing the last good numbers while backing off, so a blip
        // does not blank the ring.
        backoffSeconds_ = backoffSeconds_ <= 0.0
                              ? kBackoffMin
                              : std::min(backoffSeconds_ * 2.0, kBackoffMax);
        backoffUntil_ = now + backoffSeconds_;
        if (cachedData_.success) {
            cachedData_.errorMessage = data.errorMessage;
            return cachedData_;
        }
        cachedData_ = data;
        return cachedData_;
    }

    // The count is best-effort; a management-key failure leaves the balance
    // intact and only blanks the daily readout.
    FetchTodayRequestCount(data);

    data.success = true;
    data.lastSuccessTime = now;
    backoffSeconds_ = 0.0;
    backoffUntil_ = 0.0;
    nextFetchTime_ = now + kMinInterval;
    cachedData_ = data;
    return cachedData_;
}
