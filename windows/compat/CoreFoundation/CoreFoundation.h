// CoreFoundation.h -- the slice of Apple's CoreFoundation string API that
// upstream's runtime/src/native_sqlite.cpp uses, implemented on Win32.
//
// native_sqlite.cpp gives SQLite Unicode-aware upper(), lower(), LIKE and
// collations through CFString. Rather than keep a Windows copy of that file,
// the Windows build compiles it unchanged with this directory first on the
// include path. Only what that file calls is here:
//
//   CFString        UTF-16 text, like CFString's own storage
//   case mapping    LCMapStringEx with LCMAP_LINGUISTIC_CASING (full Unicode)
//   comparison      CompareStringEx, invariant or the user's locale
//
// A CFStringRef is a heap object freed with CFRelease, as on the Mac.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>

#include <windows.h>

using CFIndex = std::ptrdiff_t;
using UInt8 = std::uint8_t;
using UniChar = std::uint16_t;
using CFStringEncoding = std::uint32_t;
using CFOptionFlags = unsigned long;
using CFStringCompareFlags = CFOptionFlags;
using CFComparisonResult = CFIndex;
using CFAllocatorRef = const void*;
using CFLocaleRef = const void*;

struct __CFString {
    std::wstring text;
};
using CFStringRef = const __CFString*;
using CFMutableStringRef = __CFString*;

struct CFRange {
    CFIndex location;
    CFIndex length;
};

inline constexpr CFAllocatorRef kCFAllocatorDefault = nullptr;
inline constexpr CFStringEncoding kCFStringEncodingUTF8 = 0x08000100;
inline constexpr CFStringCompareFlags kCFCompareCaseInsensitive = 1;
inline constexpr CFStringCompareFlags kCFCompareNonliteral = 16;
inline constexpr CFStringCompareFlags kCFCompareLocalized = 32;

inline CFRange CFRangeMake(CFIndex location, CFIndex length) { return {location, length}; }

inline void CFRelease(const void* cf) { delete static_cast<const __CFString*>(cf); }

// Returns nullptr for bytes that are not valid UTF-8, as CoreFoundation does.
inline CFStringRef CFStringCreateWithBytes(CFAllocatorRef, const UInt8* bytes, CFIndex n, CFStringEncoding,
                                           bool) {
    auto* s = new __CFString();
    if (n <= 0) return s;
    const char* p = reinterpret_cast<const char*>(bytes);
    const int len = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, p, static_cast<int>(n), nullptr, 0);
    if (len <= 0) {
        delete s;
        return nullptr;
    }
    s->text.resize(static_cast<size_t>(len));
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, p, static_cast<int>(n), &s->text[0], len);
    return s;
}

inline CFMutableStringRef CFStringCreateMutableCopy(CFAllocatorRef, CFIndex, CFStringRef s) {
    return s ? new __CFString(*s) : nullptr;
}

inline CFIndex CFStringGetLength(CFStringRef s) { return s ? static_cast<CFIndex>(s->text.size()) : 0; }

inline UniChar CFStringGetCharacterAtIndex(CFStringRef s, CFIndex i) {
    return static_cast<UniChar>(s->text[static_cast<size_t>(i)]);
}

inline CFIndex CFStringGetMaximumSizeForEncoding(CFIndex length, CFStringEncoding) { return length * 3; }

// UTF-8 only, which is all native_sqlite.cpp asks for. Returns the number of
// UTF-16 units converted and the byte count in *used.
inline CFIndex CFStringGetBytes(CFStringRef s, CFRange range, CFStringEncoding, UInt8, bool, UInt8* buffer,
                                CFIndex max, CFIndex* used) {
    if (used) *used = 0;
    if (!s || range.length <= 0) return 0;
    const wchar_t* p = s->text.data() + range.location;
    const int n = WideCharToMultiByte(CP_UTF8, 0, p, static_cast<int>(range.length),
                                      reinterpret_cast<char*>(buffer), static_cast<int>(max), nullptr, nullptr);
    if (n <= 0) return 0;
    if (used) *used = n;
    return range.length;
}

inline void cf_map_case(CFMutableStringRef s, DWORD flags) {
    if (!s || s->text.empty()) return;
    const int n = static_cast<int>(s->text.size());
    const int len = LCMapStringEx(LOCALE_NAME_INVARIANT, flags | LCMAP_LINGUISTIC_CASING, s->text.c_str(), n,
                                  nullptr, 0, nullptr, nullptr, 0);
    if (len <= 0) return;
    std::wstring out(static_cast<size_t>(len), L'\0');
    LCMapStringEx(LOCALE_NAME_INVARIANT, flags | LCMAP_LINGUISTIC_CASING, s->text.c_str(), n, &out[0], len, nullptr,
                  nullptr, 0);
    s->text.swap(out);
}

inline void CFStringUppercase(CFMutableStringRef s, CFLocaleRef) { cf_map_case(s, LCMAP_UPPERCASE); }
inline void CFStringLowercase(CFMutableStringRef s, CFLocaleRef) { cf_map_case(s, LCMAP_LOWERCASE); }

// kCFCompareLessThan (-1), kCFCompareEqualTo (0), kCFCompareGreaterThan (1).
// Nonliteral comparison (canonically equivalent forms compare equal) is what
// CompareStringEx does by default; Localized picks the user's locale over the
// invariant one.
inline CFComparisonResult CFStringCompare(CFStringRef a, CFStringRef b, CFStringCompareFlags flags) {
    const wchar_t* locale = (flags & kCFCompareLocalized) ? LOCALE_NAME_USER_DEFAULT : LOCALE_NAME_INVARIANT;
    DWORD cmp = 0;
    if (flags & kCFCompareCaseInsensitive) cmp |= LINGUISTIC_IGNORECASE;
    const int r = CompareStringEx(locale, cmp, a->text.c_str(), static_cast<int>(a->text.size()), b->text.c_str(),
                                  static_cast<int>(b->text.size()), nullptr, nullptr, 0);
    return r == CSTR_LESS_THAN ? -1 : r == CSTR_GREATER_THAN ? 1 : 0;
}
