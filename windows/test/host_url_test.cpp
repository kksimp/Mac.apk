// host_url_test.cpp -- winapk-host-url-test: which URLs go to Windows' own handlers, and as what
// (host/host_url.h). No Windows call.
//
//   winapk-host-url-test

#include <cstdio>
#include <string>

#include "host_url.h"

namespace {

int g_failures = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

using winapk::host::host_open_target;

bool refused(const std::wstring& url) { return host_open_target(url).empty(); }
bool as_is(const std::wstring& url) { return host_open_target(url) == url; }

}  // namespace

int main() {
    std::printf("what a phone opens outside the app\n");
    check("https", as_is(L"https://example.com/a?b=c#d"));
    check("http", as_is(L"http://example.com/"));
    check("scheme case", as_is(L"HTTPS://example.com/"));
    check("mailto", as_is(L"mailto:a@example.com?subject=hi%20there"));
    check("tel", as_is(L"tel:+15551234567"));
    check("sms", as_is(L"sms:+15551234567"));
    check("the location settings", as_is(L"ms-settings:privacy-location"));
    check("the location settings, any case",
          host_open_target(L"MS-Settings:Privacy-Location") == L"ms-settings:privacy-location");

    std::printf("what stays in the runtime\n");
    check("shell:", refused(L"shell:startup"));
    check("ms-msdt:", refused(L"ms-msdt:/id PCWDiagnostic"));
    check("search-ms:", refused(L"search-ms:query=x&crumb=location:\\\\evil\\share"));
    check("ms-appinstaller:", refused(L"ms-appinstaller:?source=https://evil.example/a.appx"));
    check("another ms-settings page", refused(L"ms-settings:windowsupdate"));
    check("file:", refused(L"file:///C:/Windows/System32/calc.exe"));
    check("FILE:", refused(L"FILE:///C:/x"));
    check("a drive path", refused(L"C:\\Windows\\System32\\calc.exe"));
    check("a UNC path", refused(L"\\\\server\\share\\a.exe"));
    check("no scheme", refused(L"example.com"));
    check("a leading space", refused(L" https://example.com"));
    check("an app's own scheme", refused(L"myapp://callback"));
    check("market:", refused(L"market://details?id=x"));
    check("intent:", refused(L"intent://x#Intent;scheme=https;end"));
    check("javascript:", refused(L"javascript:alert(1)"));
    check("a control character", refused(L"https://example.com/\n--flag"));
    check("an empty URL", refused(L""));

    std::printf("what ends a command-line argument is escaped\n");
    check("quote and space",
          host_open_target(L"https://example.com/a\" --x b") == L"https://example.com/a%22%20--x%20b");
    check("angle brackets", host_open_target(L"mailto:a@b.c?body=<x>") == L"mailto:a@b.c?body=%3Cx%3E");

    std::printf("geo: as a web map\n");
    const std::wstring map = L"https://www.google.com/maps/search/?api=1&query=";
    check("coordinates", host_open_target(L"geo:37.422,-122.084") == map + L"37.422,-122.084");
    check("coordinates with a zoom", host_open_target(L"geo:37.422,-122.084?z=15") == map + L"37.422,-122.084");
    check("coordinates with a CRS", host_open_target(L"geo:48.2,16.3;crs=wgs84;u=40") == map + L"48.2,16.3");
    check("a place", host_open_target(L"geo:0,0?q=1600+Amphitheatre+Parkway") == map + L"1600+Amphitheatre+Parkway");
    check("a place after a zoom", host_open_target(L"geo:0,0?z=3&q=Paris") == map + L"Paris");
    check("a labelled point", host_open_target(L"geo:0,0?q=37.4,-122.1(Office)") == map + L"37.4,-122.1(Office)");
    check("a place that is not ASCII", host_open_target(L"geo:0,0?q=Z\u00fcrich") == map + L"Z%C3%BCrich");
    check("a place cannot add a parameter", host_open_target(L"geo:0,0?q=a\"b#c") == map + L"a%22b");
    check("a place cannot add a query", host_open_target(L"geo:0,0?q=a=b") == map + L"a%3Db");
    check("no coordinates", refused(L"geo:"));
    check("coordinates that are not numbers", refused(L"geo:abc,def"));
    check("an unpaired surrogate", refused(std::wstring(L"geo:0,0?q=") + static_cast<wchar_t>(0xd800)));

    if (g_failures) {
        std::printf("%d FAILED\n", g_failures);
        return 1;
    }
    std::printf("all passed\n");
    return 0;
}
