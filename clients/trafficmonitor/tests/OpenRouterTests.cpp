// OpenRouter parsing and quota-window arithmetic.
//
// The payload shapes below are copied from live responses (the credits one
// verbatim, minus the account identifiers). Two things here are easy to get
// wrong and expensive to get wrong:
//
//   1. THE TIER. The 50-vs-1000 free-model daily allowance keys off
//      total_credits, which is LIFETIME PURCHASED credit, not the current
//      balance. Reading the balance instead silently demotes any account
//      that has spent a few cents.
//
//   2. THE DAY WINDOW. The free quota resets at UTC midnight, not local
//      midnight. In UTC+8 that is 08:00 the same morning, so a local-day
//      window would report the wrong bucket for eight hours out of every
//      twenty-four.
#include "OpenRouterUsageFetcher.h"

#include <cmath>
#include <iostream>
#include <string>

namespace {

int failures = 0;

void Expect(bool condition, const std::string& message) {
    if (!condition) {
        std::cerr << "FAIL: " << message << '\n';
        ++failures;
    }
}

void ExpectEqual(long long actual, long long expected, const std::string& what) {
    if (actual != expected) {
        std::cerr << "FAIL: " << what << " expected " << expected
                  << " got " << actual << '\n';
        ++failures;
    }
}

void ExpectEqual(const std::string& actual, const std::string& expected,
                 const std::string& what) {
    if (actual != expected) {
        std::cerr << "FAIL: " << what << " expected " << expected
                  << " got " << actual << '\n';
        ++failures;
    }
}

void TestCreditsParse() {
    // Live response from GET /api/v1/credits.
    const std::string json =
        R"({"data":{"total_credits":10,"total_usage":0.007386874}})";
    OpenRouterUsageData data;
    Expect(ParseOpenRouterCredits(json, &data), "credits parse succeeds");
    Expect(data.hasCredits, "hasCredits set");
    Expect(std::fabs(data.totalCredits - 10.0) < 1e-9, "total_credits read");
    Expect(std::fabs(data.balance - 9.992613126) < 1e-6,
           "balance = total_credits - total_usage");
}

void TestCreditsParseRejectsGarbage() {
    OpenRouterUsageData data;
    Expect(!ParseOpenRouterCredits("not json", &data), "garbage rejected");
    Expect(!ParseOpenRouterCredits(R"({"data":{}})", &data),
           "missing fields rejected");
    Expect(!data.hasCredits, "hasCredits stays false on failure");
}

void TestRequestCountAcceptsStringAndNumber() {
    // The cookbook warns count metrics can come back as strings.
    const std::string asString =
        R"({"data":{"data":[{"date__day":"2026-08-26","request_count":"327"}],)"
        R"("metadata":{"row_count":1,"truncated":false}}})";
    long long requests = -1;
    std::wstring err;
    Expect(ParseOpenRouterRequestCount(asString, &requests, &err),
           "string request_count parses");
    ExpectEqual(requests, 327, "string request_count value");

    const std::string asNumber =
        R"({"data":{"data":[{"request_count":41}],"metadata":{}}})";
    requests = -1;
    Expect(ParseOpenRouterRequestCount(asNumber, &requests, &err),
           "numeric request_count parses");
    ExpectEqual(requests, 41, "numeric request_count value");
}

void TestRequestCountSumsRows() {
    // If a dimension ever gets added back, the ring must still show the
    // account total rather than whichever row happened to come first.
    const std::string json =
        R"({"data":{"data":[{"request_count":"10"},{"request_count":"5"},)"
        R"({"request_count":2}],"metadata":{}}})";
    long long requests = -1;
    std::wstring err;
    Expect(ParseOpenRouterRequestCount(json, &requests, &err), "multi-row parses");
    ExpectEqual(requests, 17, "rows summed");
}

void TestRequestCountEmptyIsZeroNotError() {
    // No requests yet today is a real answer, not a failure: the ring should
    // read 0/1000, not "--".
    const std::string json = R"({"data":{"data":[],"metadata":{"row_count":0}}})";
    long long requests = -1;
    std::wstring err;
    Expect(ParseOpenRouterRequestCount(json, &requests, &err), "empty rows parse");
    ExpectEqual(requests, 0, "empty rows count as zero");
}

void TestRequestCountRejectsError() {
    const std::string json =
        R"({"error":{"message":"Only management keys can access analytics","code":403}})";
    long long requests = -1;
    std::wstring err;
    Expect(!ParseOpenRouterRequestCount(json, &requests, &err),
           "403 body rejected");
    Expect(!err.empty(), "error message set");
}

void TestUtcDayRange() {
    // 2026-08-26T00:42:00Z -> the whole 2026-08-26 UTC day.
    // 1787704920 = 2026-08-26T00:42:00Z.
    std::string start, end;
    OpenRouterUtcDayRange(1787704920LL, &start, &end);
    ExpectEqual(start, "2026-08-26T00:00:00Z", "UTC day start");
    ExpectEqual(end, "2026-08-27T00:00:00Z", "UTC day end");
}

void TestUtcDayRangeAcrossLocalMidnight() {
    // 2026-08-26T20:00:00Z is 2026-08-27 04:00 in UTC+8. The quota bucket is
    // still the 26th, because the reset is on the UTC boundary.
    std::string start, end;
    OpenRouterUtcDayRange(1787774400LL, &start, &end);
    ExpectEqual(start, "2026-08-26T00:00:00Z",
                "UTC bucket ignores local date rollover");
    ExpectEqual(end, "2026-08-27T00:00:00Z", "UTC bucket end unchanged");
}

void TestUtcDayRangeAtBoundary() {
    // Exactly UTC midnight belongs to the day that is starting.
    std::string start, end;
    OpenRouterUtcDayRange(1787788800LL, &start, &end);
    ExpectEqual(start, "2026-08-27T00:00:00Z", "midnight starts the new day");
    ExpectEqual(end, "2026-08-28T00:00:00Z", "midnight end is the next day");
}

}  // namespace

int main() {
    TestCreditsParse();
    TestCreditsParseRejectsGarbage();
    TestRequestCountAcceptsStringAndNumber();
    TestRequestCountSumsRows();
    TestRequestCountEmptyIsZeroNotError();
    TestRequestCountRejectsError();
    TestUtcDayRange();
    TestUtcDayRangeAcrossLocalMidnight();
    TestUtcDayRangeAtBoundary();

    if (failures != 0) {
        std::cerr << failures << " OpenRouter test(s) failed\n";
        return 1;
    }
    std::cout << "OpenRouter tests passed\n";
    return 0;
}
