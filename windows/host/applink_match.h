// applink_match.h -- the App Link sign-in window's one decision: is this
// navigation the sign-in's return to the app? Pure (no Windows call, no JNI),
// so test/applink_match_test.cpp checks it on its own.
//
// The Java side (com.macapk.loader.AppLinks.signInReturnPrefix) passes the
// return prefix: the sign-in's redirect_uri up to its query and fragment,
// scheme (and, for http/https, host) lower-cased -- `https://store.example.com/authorize`
// for a verified App Link, `eos.abc123://epic/auth` or `com.example:/oauth2redirect`
// for one of the app's own link schemes. A navigation returns to the app when,
// without its query and fragment, it is exactly that address: an OAuth server
// redirects to the redirect_uri as registered, query added, and a looser match
// (a path under it, the whole host) would hand the app a page of the sign-in
// itself when the redirect shares a host with it.
//
//   https://store.example.com/authorize?code=x      yes
//   https://STORE.example.com:443/authorize#state   yes (case, default port)
//   https://store.example.com/authorize/done        no  (another page)
//   https://store.example.com/authorizeX            no  (another path)
//   https://store.example.com.evil.net/authorize    no  (another host)
//   http://store.example.com/authorize              no  (another scheme)
//
// The path is compared as written (paths are case-sensitive), the scheme and
// authority without case, and a default port (443, 80) as no port, the way
// Chromium canonicalises the URL WebView2 reports.

#pragma once

#include <cwctype>
#include <string>

namespace winapk::host::applink {

struct UrlParts {
    std::wstring scheme;     // lower case
    std::wstring authority;  // lower case, default port dropped; empty for `scheme:/path`
    bool has_authority = false;
    std::wstring path;       // as written, without query and fragment
};

inline std::wstring lower(std::wstring s) {
    for (wchar_t& c : s) c = static_cast<wchar_t>(std::towlower(c));
    return s;
}

// False when `url` has no scheme.
inline bool split_url(const std::wstring& url, UrlParts* out) {
    const size_t colon = url.find(L':');
    if (colon == 0 || colon == std::wstring::npos) return false;
    for (size_t i = 0; i < colon; ++i) {
        const wchar_t c = url[i];
        if (!(std::iswalnum(c) || c == L'+' || c == L'-' || c == L'.')) return false;
    }
    out->scheme = lower(url.substr(0, colon));
    size_t end = url.find_first_of(L"?#", colon + 1);
    if (end == std::wstring::npos) end = url.size();
    size_t at = colon + 1;
    out->has_authority = url.compare(at, 2, L"//") == 0 && at + 2 <= end;
    out->authority.clear();
    if (out->has_authority) {
        at += 2;
        size_t slash = url.find(L'/', at);
        if (slash == std::wstring::npos || slash > end) slash = end;
        std::wstring auth = lower(url.substr(at, slash - at));
        // userinfo is never part of a match
        const size_t user = auth.rfind(L'@');
        if (user != std::wstring::npos) auth.erase(0, user + 1);
        const std::wstring port = out->scheme == L"https" ? L":443" : out->scheme == L"http" ? L":80" : L"";
        if (!port.empty() && auth.size() > port.size() &&
            auth.compare(auth.size() - port.size(), port.size(), port) == 0) {
            auth.resize(auth.size() - port.size());
        }
        out->authority = auth;
        at = slash;
    }
    out->path = url.substr(at, end - at);
    return true;
}

// See the header comment.
inline bool returns_to_app(const std::wstring& url, const std::wstring& prefix) {
    UrlParts u, p;
    if (prefix.empty() || !split_url(url, &u) || !split_url(prefix, &p)) return false;
    if (u.scheme != p.scheme || u.has_authority != p.has_authority || u.authority != p.authority) return false;
    // An empty path and "/" are one address on a web URL.
    std::wstring up = u.path, pp = p.path;
    if (u.has_authority) {
        if (up.empty()) up = L"/";
        if (pp.empty()) pp = L"/";
    }
    return up == pp;
}

}  // namespace winapk::host::applink
