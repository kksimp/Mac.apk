// applink_match_test.cpp -- winapk-applink-test: the App Link sign-in window's
// decision (host/applink_match.h), whether a navigation is the sign-in's return
// to the app. No window and no Windows call.
//
//   winapk-applink-test

#include <cstdio>
#include <string>

#include "applink_match.h"

namespace {

int g_failures = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? "PASS" : "FAIL", what);
    if (!ok) ++g_failures;
}

using winapk::host::applink::returns_to_app;

}  // namespace

int main() {
    const std::wstring web = L"https://store.example.com/authorize";
    std::printf("verified App Link %ls\n", web.c_str());
    check("the address with a code", returns_to_app(L"https://store.example.com/authorize?code=abc&state=1", web));
    check("the address alone", returns_to_app(L"https://store.example.com/authorize", web));
    check("with a fragment", returns_to_app(L"https://store.example.com/authorize#code=abc", web));
    check("host case and default port", returns_to_app(L"HTTPS://Store.Example.COM:443/authorize?x=1", web));
    check("a path under it is another page", !returns_to_app(L"https://store.example.com/authorize/consent", web));
    check("a longer path", !returns_to_app(L"https://store.example.com/authorizeX?code=1", web));
    check("path case matters", !returns_to_app(L"https://store.example.com/Authorize?code=1", web));
    check("another host ending alike", !returns_to_app(L"https://store.example.com.evil.net/authorize", web));
    check("a subdomain", !returns_to_app(L"https://a.store.example.com/authorize", web));
    check("http is another scheme", !returns_to_app(L"http://store.example.com/authorize", web));
    check("another port", !returns_to_app(L"https://store.example.com:8443/authorize", web));
    check("userinfo does not hide the host", !returns_to_app(L"https://store.example.com@evil.net/authorize", web));
    check("userinfo dropped", returns_to_app(L"https://u@store.example.com/authorize", web));
    check("the sign-in page itself",
          !returns_to_app(L"https://www.example.com/id/login?redirect_uri=https%3A%2F%2Fstore.example.com%2Fauthorize",
                          web));
    check("the query is never the address",
          !returns_to_app(L"https://evil.net/?u=https://store.example.com/authorize", web));

    const std::wstring root = L"https://cb.example.com";
    std::printf("a root redirect %ls\n", root.c_str());
    check("root with a slash", returns_to_app(L"https://cb.example.com/?code=1", root));
    check("root without one", returns_to_app(L"https://cb.example.com?code=1", root));
    check("another page of the host", !returns_to_app(L"https://cb.example.com/login", root));
    check("explicit port in the prefix", returns_to_app(L"https://cb.example.com/", L"https://cb.example.com:443/"));

    const std::wstring scheme = L"eos.abc123xyz://epic/auth";
    std::printf("a link scheme %ls\n", scheme.c_str());
    check("the scheme with a code", returns_to_app(L"eos.abc123xyz://epic/auth?code=q", scheme));
    check("scheme case", returns_to_app(L"EOS.ABC123XYZ://epic/auth?code=q", scheme));
    check("another path of the scheme", !returns_to_app(L"eos.abc123xyz://epic/other?code=q", scheme));
    check("another scheme", !returns_to_app(L"eos.abc123xy://epic/auth?code=q", scheme));

    const std::wstring path_only = L"com.example.app:/oauth2redirect";
    std::printf("a scheme without an authority %ls\n", path_only.c_str());
    check("the redirect", returns_to_app(L"com.example.app:/oauth2redirect?code=1", path_only));
    check("with // it is another address", !returns_to_app(L"com.example.app://oauth2redirect?code=1", path_only));

    std::printf("malformed\n");
    check("empty prefix", !returns_to_app(L"https://a.com/", L""));
    check("no scheme", !returns_to_app(L"store.example.com/authorize", web));
    check("about:blank", !returns_to_app(L"about:blank", web));

    std::printf(g_failures ? "%d FAILED\n" : "all passed\n", g_failures);
    return g_failures ? 1 : 0;
}
