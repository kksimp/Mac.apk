// host_url.h -- which URLs an app may hand to Windows' own handlers, and as what. Pure (no Windows call, no
// JNI), so test/host_url_test.cpp checks it on its own; native_cocoa.cpp (an intent no app handles) and
// native_browser_tab.cpp (a sign-in window that could not open) give the result to ShellExecute.
//
// The URL comes from the app, or from a page shown in its WebView, and ShellExecute starts whatever Windows
// registered for the scheme: `shell:`, `ms-msdt:`, `search-ms:` or `ms-appinstaller:` reach programs no
// Android link could. So only what a phone opens outside an app goes out:
//
//   http:, https:               the browser
//   mailto:, tel:, sms:         the mail app, Phone Link
//   geo:                        a web map, since Windows has no geo: handler of its own
//   ms-settings:privacy-location  the host's location settings (LocationBridge.hostSettingsUrl), exactly
//
// Anything else is refused (an empty target). Characters that would end the URL on a handler's command line
// (space, quote, angle brackets) are percent-encoded, and a URL with a control character is refused.

#pragma once

#include <cwchar>
#include <cwctype>
#include <string>

namespace winapk::host {

// The scheme, lower-cased, or empty when `url` does not start with one.
inline std::wstring host_url_scheme(const std::wstring& url) {
    const size_t colon = url.find(L':');
    if (colon == 0 || colon == std::wstring::npos || !std::iswalpha(url[0]) || url[0] > 0x7f) return {};
    std::wstring s = url.substr(0, colon);
    for (wchar_t& c : s) {
        if (c > 0x7f || !(std::iswalnum(c) || c == L'+' || c == L'-' || c == L'.')) return {};
        c = static_cast<wchar_t>(std::towlower(c));
    }
    return s;
}

namespace host_url_detail {

inline void append_pct(std::wstring* out, unsigned byte) {
    static const wchar_t kHex[] = L"0123456789ABCDEF";
    out->push_back(L'%');
    out->push_back(kHex[(byte >> 4) & 0xf]);
    out->push_back(kHex[byte & 0xf]);
}

// A geo: query value for a URL's query: unreserved characters, '+' and existing %XX kept, the rest as UTF-8
// percent escapes. False on an unpaired surrogate.
inline bool append_query_value(std::wstring* out, const std::wstring& v) {
    for (size_t i = 0; i < v.size(); ++i) {
        const wchar_t c = v[i];
        if (c < 0x80 && (std::iswalnum(c) || std::wcschr(L"-._~%+,()!*'", c))) {
            out->push_back(c);
            continue;
        }
        unsigned cp = c;
        if (c >= 0xd800 && c <= 0xdbff) {
            if (i + 1 >= v.size() || v[i + 1] < 0xdc00 || v[i + 1] > 0xdfff) return false;
            cp = 0x10000 + ((cp - 0xd800) << 10) + (v[++i] - 0xdc00);
        } else if (c >= 0xdc00 && c <= 0xdfff) {
            return false;
        }
        if (cp < 0x80) {
            append_pct(out, cp);
        } else if (cp < 0x800) {
            append_pct(out, 0xc0 | (cp >> 6));
            append_pct(out, 0x80 | (cp & 0x3f));
        } else if (cp < 0x10000) {
            append_pct(out, 0xe0 | (cp >> 12));
            append_pct(out, 0x80 | ((cp >> 6) & 0x3f));
            append_pct(out, 0x80 | (cp & 0x3f));
        } else {
            append_pct(out, 0xf0 | (cp >> 18));
            append_pct(out, 0x80 | ((cp >> 12) & 0x3f));
            append_pct(out, 0x80 | ((cp >> 6) & 0x3f));
            append_pct(out, 0x80 | (cp & 0x3f));
        }
    }
    return true;
}

// geo:lat,lng[;crs...][?z=..&q=...] (RFC 5870 and Android's q= extension) as a map search: the q= place
// when there is one, else the coordinates. Empty when it names neither.
inline std::wstring geo_to_map(const std::wstring& url) {
    const size_t start = 4;  // after "geo:"
    const size_t qmark = url.find(L'?', start);
    std::wstring coords = url.substr(start, (qmark == std::wstring::npos ? url.size() : qmark) - start);
    coords = coords.substr(0, coords.find(L';'));
    std::wstring place;
    if (qmark != std::wstring::npos) {
        std::wstring query = url.substr(qmark + 1);
        query = query.substr(0, query.find(L'#'));
        for (size_t at = 0; at <= query.size();) {
            size_t amp = query.find(L'&', at);
            if (amp == std::wstring::npos) amp = query.size();
            if (query.compare(at, 2, L"q=") == 0) place = query.substr(at + 2, amp - at - 2);
            at = amp + 1;
        }
    }
    if (place.empty()) {
        const size_t comma = coords.find(L',');
        if (comma == 0 || comma == std::wstring::npos || comma + 1 >= coords.size()) return {};
        for (wchar_t c : coords) {
            if (!(c == L',' || c == L'.' || c == L'-' || c == L'+' || (c >= L'0' && c <= L'9'))) return {};
        }
        place = coords;
    }
    std::wstring out = L"https://www.google.com/maps/search/?api=1&query=";
    if (!append_query_value(&out, place)) return {};
    return out;
}

}  // namespace host_url_detail

// What ShellExecute is given for `url`, or empty when the URL stays in the runtime.
inline std::wstring host_open_target(const std::wstring& url) {
    for (wchar_t c : url) {
        if (c < 0x20 || c == 0x7f) return {};
    }
    const std::wstring scheme = host_url_scheme(url);
    if (scheme == L"geo") return host_url_detail::geo_to_map(url);
    if (scheme == L"ms-settings") {
        std::wstring lower = url;
        for (wchar_t& c : lower) c = static_cast<wchar_t>(std::towlower(c));
        return lower == L"ms-settings:privacy-location" ? lower : std::wstring();
    }
    if (scheme != L"http" && scheme != L"https" && scheme != L"mailto" && scheme != L"tel" && scheme != L"sms") {
        return {};
    }
    std::wstring out;
    out.reserve(url.size());
    for (wchar_t c : url) {
        if (c == L' ' || c == L'"' || c == L'<' || c == L'>') {
            host_url_detail::append_pct(&out, static_cast<unsigned>(c));
        } else {
            out.push_back(c);
        }
    }
    return out;
}

}  // namespace winapk::host
