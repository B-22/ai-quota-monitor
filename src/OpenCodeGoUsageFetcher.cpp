#include "OpenCodeGoUsageFetcher.h"

#include "../third_party/sqlite/sqlite3.h"

#include <windows.h>
#include <winhttp.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <optional>
#include <regex>
#include <vector>

#pragma comment(lib, "winhttp.lib")

namespace {

constexpr const char* kHost = "opencode.ai";

// Internal RPC service ids (mirror the values used by QuotaHub /
// opencode-tui-usage; they are opaque server-function ids).
constexpr const char* kUsageRpcServerId =
    "c7389bd0e731f80f49593e5ee53835475f4e28594dd6bd83eb229bab753498cd";
constexpr const char* kWorkspaceRpcServerId =
    "def39973159c7f0483d8793a822b8dbb10d067e12c65455fcb4608459ba0234f";

constexpr const char* kUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

double NowSeconds() {
    return static_cast<double>(time(nullptr));
}

std::wstring ToWide(const char* text) {
    if (!text || !*text) return L"";
    const int chars = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
    if (chars <= 1) return {};
    std::wstring out(static_cast<size_t>(chars) - 1, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text, -1, out.data(), chars);
    return out;
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

std::string WideToUtf8(const std::wstring& s) {
    if (s.empty()) return {};
    const int len = WideCharToMultiByte(CP_UTF8, 0, s.data(),
                                        static_cast<int>(s.size()),
                                        nullptr, 0, nullptr, nullptr);
    if (len <= 0) return {};
    std::string out(static_cast<size_t>(len), '\0');
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()),
                        out.data(), len, nullptr, nullptr);
    return out;
}

// Percent-encode a URL query value (RFC 3986 unreserved set kept literal).
std::string UrlEncode(const std::string& in) {
    static const char* hex = "0123456789ABCDEF";
    std::string out;
    out.reserve(in.size());
    for (unsigned char c : in) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
            out += static_cast<char>(c);
        } else {
            out += '%';
            out += hex[c >> 4];
            out += hex[c & 0xF];
        }
    }
    return out;
}

// Aggregates per-message USD cost for opencode-go sessions in the local DB,
// bucketed into the three rolling windows (seconds since epoch).
const char* kUsageSql = R"sql(
SELECT
    COALESCE(SUM(CASE WHEN tc >= :h5  THEN c END), 0.0),
    COALESCE(SUM(CASE WHEN tc >= :wk  THEN c END), 0.0),
    COALESCE(SUM(CASE WHEN tc >= :mo  THEN c END), 0.0),
    COUNT(*)
FROM (
    SELECT
        json_extract(m.data, '$.cost')                                    AS c,
        COALESCE(json_extract(m.data, '$.time.completed'),
                 json_extract(m.data, '$.time.created')) / 1000.0         AS tc,
        json_extract(s.model, '$.providerID')                             AS pid
    FROM message m
    JOIN session s ON s.id = m.session_id
    WHERE json_extract(m.data, '$.role') = 'assistant'
      AND json_extract(m.data, '$.cost') IS NOT NULL
      AND json_extract(s.model, '$.providerID') = 'opencode-go'
      AND tc >= :mo
)
)sql";

struct WindowRegexes {
    const char* key;                       // "rollingUsage" etc.
    std::regex pctFirst;                   // ...usagePercent, resetInSec...
    std::regex resetFirst;                 // ...resetInSec, usagePercent...
    std::regex status;                     // ...status:"..."
};

const std::vector<WindowRegexes>& WindowPatterns() {
    static const std::vector<WindowRegexes> patterns = [] {
        const char* keys[3] = {"rollingUsage", "weeklyUsage", "monthlyUsage"};
        std::vector<WindowRegexes> out;
        for (const char* key : keys) {
            const std::string prefix = std::string(key)
                + R"(\s*:\s*\$R\[\d+\]\s*=\s*\{)";
            out.push_back(WindowRegexes{
                key,
                std::regex(prefix
                    + R"([^}]*?usagePercent\s*:\s*(-?\d+(?:\.\d+)?)[^}]*?resetInSec\s*:\s*(-?\d+(?:\.\d+)?)[^}]*\})"),
                std::regex(prefix
                    + R"([^}]*?resetInSec\s*:\s*(-?\d+(?:\.\d+)?)[^}]*?usagePercent\s*:\s*(-?\d+(?:\.\d+)?)[^}]*\})"),
                std::regex(prefix + R"re([^}]*?status\s*:\s*"([^"]*)")re"),
            });
        }
        return out;
    }();
    return patterns;
}

// Extracts the three usage windows from an internal payload / HTML page.
// Returns true if at least the rolling window was found.
bool ParseUsageText(const std::string& text, OpenCodeGoUsageData& out) {
    bool any = false;
    for (const auto& w : WindowPatterns()) {
        std::smatch m;
        int percent = -1;
        long long resetSec = -1;
        if (std::regex_search(text, m, w.pctFirst) && m.size() >= 3) {
            percent = static_cast<int>(std::lround(std::atof(m[1].str().c_str())));
            resetSec = static_cast<long long>(std::atoll(m[2].str().c_str()));
        } else if (std::regex_search(text, m, w.resetFirst) && m.size() >= 3) {
            resetSec = static_cast<long long>(std::atoll(m[1].str().c_str()));
            percent = static_cast<int>(std::lround(std::atof(m[2].str().c_str())));
        }
        std::string status;
        std::smatch sm;
        if (std::regex_search(text, sm, w.status) && sm.size() >= 2) {
            status = sm[1].str();
        }
        OpenCodeGoWindow window;
        window.usedPercent = std::clamp(percent, -1, 100);
        window.resetInSec = resetSec;
        window.status = status;
        if (std::string(w.key) == "rollingUsage") out.rolling = window;
        else if (std::string(w.key) == "weeklyUsage") out.weekly = window;
        else out.monthly = window;
        if (window.usedPercent >= 0) any = true;
    }
    return any;
}

}  // namespace

std::wstring OpenCodeGoUsageFetcher::ResolveDbPath() const {
    wchar_t profile[MAX_PATH] = {};
    const DWORD len = GetEnvironmentVariableW(L"USERPROFILE", profile, MAX_PATH);
    if (len == 0 || len >= MAX_PATH) return L"";
    std::wstring path = profile;
    path += L"\\.local\\share\\opencode\\opencode.db";
    return path;
}

std::string OpenCodeGoUsageFetcher::ToUtf8(const std::wstring& text) const {
    if (text.empty()) return {};
    const int bytes = WideCharToMultiByte(CP_UTF8, 0, text.c_str(), -1,
                                          nullptr, 0, nullptr, nullptr);
    if (bytes <= 1) return {};
    std::string out(static_cast<size_t>(bytes) - 1, '\0');
    WideCharToMultiByte(CP_UTF8, 0, text.c_str(), -1, out.data(), bytes,
                        nullptr, nullptr);
    return out;
}

namespace {

// GET https://opencode.ai<path> with the given extra headers. Returns the
// response body on 2xx, empty otherwise.
std::string HttpGet(const ProxyConfig& proxy,
                    const std::string& path,
                    const std::vector<std::wstring>& headers,
                    unsigned long& statusCode,
                    std::wstring* errorMessage) {
    if (errorMessage) errorMessage->clear();
    statusCode = 0;
    const std::wstring whost = L"opencode.ai";
    const std::wstring wpath = Utf8ToWide(path);

    HINTERNET session = OpenHttpSession(proxy, L"AIUsage/1.0 (opencode-go)",
                                        whost.c_str(), errorMessage,
                                        /*verifyExitIp=*/false);
    if (!session) {
        if (errorMessage && errorMessage->empty()) *errorMessage = L"WinHttpOpen failed";
        return {};
    }
    std::string body;
    HINTERNET connect = nullptr, request = nullptr;
    do {
        connect = WinHttpConnect(session, whost.c_str(),
                                 INTERNET_DEFAULT_HTTPS_PORT, 0);
        if (!connect) { if (errorMessage) *errorMessage = L"WinHttpConnect failed"; break; }
        request = WinHttpOpenRequest(connect, L"GET", wpath.c_str(), nullptr,
                                     WINHTTP_NO_REFERER,
                                     WINHTTP_DEFAULT_ACCEPT_TYPES,
                                     WINHTTP_FLAG_SECURE);
        if (!request) { if (errorMessage) *errorMessage = L"WinHttpOpenRequest failed"; break; }
        for (const auto& h : headers) {
            WinHttpAddRequestHeaders(request, h.c_str(), static_cast<DWORD>(-1L),
                                     WINHTTP_ADDREQ_FLAG_ADD);
        }
        WinHttpSetTimeouts(request, 8000, 8000, 8000, 8000);
        if (!WinHttpSendRequest(request, WINHTTP_NO_ADDITIONAL_HEADERS, 0,
                                WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
            if (errorMessage) *errorMessage = L"WinHttpSendRequest failed";
            break;
        }
        if (!WinHttpReceiveResponse(request, nullptr)) {
            if (errorMessage) *errorMessage = L"WinHttpReceiveResponse failed";
            break;
        }
        DWORD status = 0;
        DWORD statusSize = sizeof(status);
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize,
                            WINHTTP_NO_HEADER_INDEX);
        statusCode = status;
        if (status < 200 || status >= 300) break;
        char buf[8192];
        DWORD read = 0;
        while (WinHttpReadData(request, buf, sizeof(buf), &read) && read > 0) {
            body.append(buf, read);
            read = 0;
        }
    } while (false);
    if (request) WinHttpCloseHandle(request);
    if (connect) WinHttpCloseHandle(connect);
    WinHttpCloseHandle(session);
    return body;
}

}  // namespace

bool OpenCodeGoUsageFetcher::ResolveWorkspaceIdFromServer(
    OpenCodeGoUsageData& out, std::string& workspaceId) {
    workspaceId.clear();
    std::wstring err;
    unsigned long status = 0;
    const std::string path = std::string("/_server?id=") + kWorkspaceRpcServerId;
    std::vector<std::wstring> headers = {
        L"Cookie: " + Utf8ToWide("auth=" + authCookie_),
        Utf8ToWide(std::string("x-server-id: ") + kWorkspaceRpcServerId),
        L"x-server-instance: server-fn:aiusage",
        Utf8ToWide(std::string("User-Agent: ") + kUserAgent),
        L"Origin: https://opencode.ai",
        L"Referer: https://opencode.ai",
        L"Accept: text/javascript, application/json;q=0.9, */*;q=0.8",
    };
    const std::string text = HttpGet(proxyConfig_, path, headers, status, &err);
    if (status == 401 || status == 403) {
        out.errorMessage = L"\u8BA4\u8BC1\u5931\u8D25 (HTTP " + std::to_wstring(status)
            + L") \uFF0C\u8BF7\u68C0\u67E5 AuthCookie";
        return false;
    }
    if (status < 200 || status >= 300) {
        out.errorMessage = L"\u5DE5\u4F5C\u533A\u67E5\u8BE2 HTTP " + std::to_wstring(status);
        return false;
    }
    static const std::regex entry(
        R"re(id\s*:\s*"(wrk_[^"]+)"[^{}]*?name\s*:\s*"([^"]*)")re");
    std::smatch m;
    if (std::regex_search(text, m, entry)) {
        workspaceId = m[1].str();
        return true;
    }
    out.errorMessage = L"\u65E0\u6CD5\u89E3\u6790\u5DE5\u4F5C\u533A ID";
    return false;
}

bool OpenCodeGoUsageFetcher::FetchFromServer(OpenCodeGoUsageData& out) {
    if (authCookie_.empty()) {
        out.errorMessage = L"\u672A\u914D\u7F6E AuthCookie \uFF08ini [OpenCodeGo] "
            L"AuthCookie=\u586B\u5165\u6D4F\u89C8\u5668\u767B\u5F55 cookie\uFF09";
        return false;
    }
    std::string workspaceId = workspaceId_;
    if (workspaceId.empty()) {
        if (!ResolveWorkspaceIdFromServer(out, workspaceId) || workspaceId.empty()) {
            return false;
        }
    }

    const std::string args = "{\"t\":{\"t\":9,\"i\":0,\"l\":1,"
        "\"a\":[{\"t\":1,\"s\":\"" + workspaceId + "\"}],\"o\":0},"
        "\"f\":31,\"m\":[]}";
    const std::string rpcPath = std::string("/_server?id=") + kUsageRpcServerId
        + "&args=" + UrlEncode(args);
    std::vector<std::wstring> headers = {
        L"Cookie: " + Utf8ToWide("auth=" + authCookie_),
        Utf8ToWide(std::string("x-server-id: ") + kUsageRpcServerId),
        L"x-server-instance: server-fn:aiusage",
        Utf8ToWide(std::string("User-Agent: ") + kUserAgent),
        L"Accept: */*",
    };

    unsigned long status = 0;
    std::wstring err;
    std::string text = HttpGet(proxyConfig_, rpcPath, headers, status, &err);
    if (status == 401 || status == 403) {
        out.errorMessage = L"\u8BA4\u8BC1\u5931\u8D25 (HTTP " + std::to_wstring(status)
            + L") \uFF0C\u8BF7\u91CD\u65B0\u590D\u5236 AuthCookie";
        return false;
    }
    if (status >= 200 && status < 300 && ParseUsageText(text, out)) {
        return true;
    }

    // Fallback: fetch the console page and parse the same embedded payload.
    const std::string pagePath = std::string("/workspace/") + workspaceId + "/go";
    std::vector<std::wstring> pageHeaders = {
        L"Cookie: " + Utf8ToWide("auth=" + authCookie_),
        Utf8ToWide(std::string("User-Agent: ") + kUserAgent),
        L"Accept: text/html, application/xhtml+xml",
    };
    text = HttpGet(proxyConfig_, pagePath, pageHeaders, status, &err);
    if (status >= 200 && status < 300 && ParseUsageText(text, out)) {
        return true;
    }
    out.errorMessage = L"\u670D\u52A1\u5668\u6570\u636E\u83B7\u53D6\u5931\u8D25 (HTTP "
        + std::to_wstring(status) + L")";
    return false;
}

bool OpenCodeGoUsageFetcher::FetchFromLocalDb(OpenCodeGoUsageData& out) {
    std::wstring dbPath = dbPathOverride_.empty() ? ResolveDbPath() : dbPathOverride_;
    if (dbPath.empty()) {
        out.errorMessage = L"\u65E0\u6CD5\u5B9A\u4F4D USERPROFILE";
        return false;
    }

    const std::string utf8Path = ToUtf8(dbPath);
    sqlite3* db = nullptr;
    if (sqlite3_open_v2(utf8Path.c_str(), &db, SQLITE_OPEN_READONLY, nullptr) != SQLITE_OK) {
        out.errorMessage = db
            ? L"\u6253\u5F00 opencode.db \u5931\u8D25: " + ToWide(sqlite3_errmsg(db))
            : L"\u6253\u5F00 opencode.db \u5931\u8D25";
        if (db) sqlite3_close(db);
        return false;
    }
    sqlite3_busy_timeout(db, 2000);

    sqlite3_stmt* stmt = nullptr;
    if (sqlite3_prepare_v2(db, kUsageSql, -1, &stmt, nullptr) != SQLITE_OK) {
        out.errorMessage = L"SQL \u9884\u7F16\u8BD1\u5931\u8D25: " + ToWide(sqlite3_errmsg(db));
        sqlite3_close(db);
        return false;
    }

    const double now = NowSeconds();
    sqlite3_bind_double(stmt, sqlite3_bind_parameter_index(stmt, ":h5"), now - 5.0 * 3600.0);
    sqlite3_bind_double(stmt, sqlite3_bind_parameter_index(stmt, ":wk"), now - 7.0 * 86400.0);
    sqlite3_bind_double(stmt, sqlite3_bind_parameter_index(stmt, ":mo"), now - 30.0 * 86400.0);

    bool ok = false;
    if (sqlite3_step(stmt) == SQLITE_ROW) {
        out.cost5h = sqlite3_column_double(stmt, 0);
        out.costWeek = sqlite3_column_double(stmt, 1);
        out.costMonth = sqlite3_column_double(stmt, 2);
        out.hasGoUsage = sqlite3_column_int(stmt, 3) > 0;
        ok = true;
    } else {
        out.errorMessage = L"SQL \u67E5\u8BE2\u5931\u8D25: " + ToWide(sqlite3_errmsg(db));
    }
    sqlite3_finalize(stmt);
    sqlite3_close(db);

    if (!ok) return false;
    if (out.hasGoUsage) {
        const double frac = out.cost5h / kCap5h;
        out.localPct5h = static_cast<int>(std::clamp(
            std::round((1.0 - frac) * 100.0), 0.0, 100.0));
    } else {
        out.localPct5h = -1;
    }
    out.source = "local";
    return true;
}

OpenCodeGoUsageData OpenCodeGoUsageFetcher::Fetch() {
    OpenCodeGoUsageData out;
    if (!enabled_) {
        out.errorMessage = L"\u5DF2\u7981\u7528";
        return out;
    }
    if (FetchFromServer(out)) {
        out.source = "server";
        out.success = true;
        out.lastSuccessTime = NowSeconds();
        return out;
    }
    // Server path failed (or no cookie). Fall back to the local DB estimate.
    // When a cookie WAS configured, carry the server failure reason forward
    // so the tooltip can tell the user why the numbers are estimates.
    OpenCodeGoUsageData local;
    if (FetchFromLocalDb(local)) {
        local.success = true;
        local.lastSuccessTime = NowSeconds();
        if (!authCookie_.empty() && !out.errorMessage.empty()) {
            local.errorMessage = L"\u670D\u52A1\u5668 " + out.errorMessage
                + L" \uFF0C\u5C55\u793A\u672C\u5730\u4F30\u7B97";
        }
        return local;
    }
    if (out.errorMessage.empty()) out.errorMessage = local.errorMessage;
    return out;
}
