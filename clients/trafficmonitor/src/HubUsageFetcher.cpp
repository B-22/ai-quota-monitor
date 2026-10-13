#include "HubUsageFetcher.h"
#include <windows.h>
#include <winhttp.h>
#include <ctime>

namespace {
struct HttpHandle {
    HINTERNET value = nullptr;
    ~HttpHandle() { if (value) WinHttpCloseHandle(value); }
};
}

HubUsageSnapshot HubUsageFetcher::Fetch() const {
    HubUsageSnapshot fail;
    if (!config_.enabled) return fail;
    // Error strings are deliberately constant: never expose URL, token or body.
    fail.errorMessage = L"Hub unavailable";
    if (config_.token.empty() || config_.token.find_first_of(L"\r\n") != std::wstring::npos
        || config_.url.empty() || config_.url.find_first_of(L"\r\n?#") != std::wstring::npos)
        return fail;
    URL_COMPONENTS parts{};
    parts.dwStructSize = sizeof(parts);
    parts.dwHostNameLength = parts.dwUrlPathLength = parts.dwUserNameLength = parts.dwPasswordLength = -1;
    if (!WinHttpCrackUrl(config_.url.c_str(), 0, 0, &parts)
        || parts.dwUserNameLength || parts.dwPasswordLength) return fail;
    const std::wstring host(parts.lpszHostName, parts.dwHostNameLength);
    const bool secure = parts.nScheme == INTERNET_SCHEME_HTTPS;
    const bool loopback = host == L"127.0.0.1" || host == L"localhost" || host == L"::1" || host == L"[::1]";
    if (!secure && !(parts.nScheme == INTERNET_SCHEME_HTTP && loopback)) return fail;
    std::wstring path = parts.dwUrlPathLength
        ? std::wstring(parts.lpszUrlPath, parts.dwUrlPathLength) : std::wstring();
    while (!path.empty() && path.back() == L'/') path.pop_back();
    path += L"/api/stats";
    HttpHandle session{WinHttpOpen(L"AIUsage-Hub/1.0", WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY,
        WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0)};
    if (!session.value) return fail;
    WinHttpSetTimeouts(session.value, 3000, 3000, 3000, 3000);
    HttpHandle connection{WinHttpConnect(session.value, host.c_str(), parts.nPort, 0)};
    if (!connection.value) return fail;
    HttpHandle request{WinHttpOpenRequest(connection.value, L"GET", path.c_str(), nullptr,
        WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, secure ? WINHTTP_FLAG_SECURE : 0)};
    if (!request.value) return fail;
    // Refuse all redirects so bearer authentication cannot follow another host.
    DWORD redirectPolicy = WINHTTP_OPTION_REDIRECT_POLICY_NEVER;
    if (!WinHttpSetOption(request.value, WINHTTP_OPTION_REDIRECT_POLICY,
        &redirectPolicy, sizeof(redirectPolicy))) return fail;
    const std::wstring headers = L"Authorization: Bearer " + config_.token + L"\r\nAccept: application/json\r\n";
    if (!WinHttpSendRequest(request.value, headers.c_str(), static_cast<DWORD>(headers.size()),
        WINHTTP_NO_REQUEST_DATA, 0, 0, 0) || !WinHttpReceiveResponse(request.value, nullptr)) return fail;
    DWORD code = 0, size = sizeof(code);
    if (!WinHttpQueryHeaders(request.value, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
        WINHTTP_HEADER_NAME_BY_INDEX, &code, &size, WINHTTP_NO_HEADER_INDEX) || code != 200) return fail;
    std::string body;
    constexpr size_t cap = 1024 * 1024;
    for (;;) {
        DWORD available = 0;
        if (!WinHttpQueryDataAvailable(request.value, &available)) return fail;
        if (!available) break;
        if (available > cap - body.size()) return fail;
        std::string chunk(available, '\0');
        DWORD read = 0;
        if (!WinHttpReadData(request.value, chunk.data(), available, &read) || !read) return fail;
        body.append(chunk.data(), read);
    }
    return ParseHubUsageJson(body, static_cast<long long>(time(nullptr)),
        config_.claudeAccountKey, config_.codexAccountKey);
}
