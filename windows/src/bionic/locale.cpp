// locale.cpp -- <locale.h>, multibyte conversion and the wide-character
// functions beyond the basics in string.cpp, for guest code.
//
// Bionic has two locales, "C" and "C.UTF-8", and its multibyte functions are
// UTF-8 in both. libc++ (libc++_shared.so, linked into most NDK apps) builds
// its std::locale on newlocale/uselocale and the *_l functions, so those must
// exist, but they only need to describe that one locale. The *_l variants of
// the string.h and ctype.h functions are aliases in string.cpp: the locale
// argument comes last, so the plain function ignores it.
//
// wchar_t is 32 bits (UTF-32) for the guest, so none of this is the CRT's.

#include "bionic_internal.h"

#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <string>

namespace winapk::bionic {

namespace {

using wchar32 = char32_t;

// ---- <locale.h> ---------------------------------------------------------------
// A locale_t is an opaque pointer; any non-null value names our one locale.
// LC_GLOBAL_LOCALE is (locale_t)-1.
struct GuestLocale {
    int utf8;
};
GuestLocale g_locale{1};
void* const kGlobalLocale = reinterpret_cast<void*>(-1);
thread_local void* t_locale = kGlobalLocale;

bool known_locale(const char* name) {
    return !name || !*name || !std::strcmp(name, "C") || !std::strcmp(name, "POSIX") ||
           !std::strcmp(name, "C.UTF-8") || !std::strcmp(name, "en_US.UTF-8") || !std::strcmp(name, "C.utf8");
}

WINAPK_GUEST_ABI char* g_setlocale(int category, const char* name) {
    static char current[] = "C.UTF-8";
    if (category < 0 || category > 12) {
        *guest_errno() = kEINVAL;
        return nullptr;
    }
    if (!known_locale(name)) {
        *guest_errno() = kENOENT;
        return nullptr;
    }
    return current;
}
WINAPK_GUEST_ABI void* g_newlocale(int, const char* name, void*) {
    if (!known_locale(name)) {
        *guest_errno() = kENOENT;
        return nullptr;
    }
    return &g_locale;
}
WINAPK_GUEST_ABI void* g_duplocale(void*) { return &g_locale; }
WINAPK_GUEST_ABI void g_freelocale(void*) {}
WINAPK_GUEST_ABI void* g_uselocale(void* loc) {
    void* old = t_locale;
    if (loc) t_locale = loc;
    return old;
}

// Bionic's struct lconv, with the "C" locale's values (CHAR_MAX = "not
// available").
struct GuestLconv {
    char* decimal_point;
    char* thousands_sep;
    char* grouping;
    char* int_curr_symbol;
    char* currency_symbol;
    char* mon_decimal_point;
    char* mon_thousands_sep;
    char* mon_grouping;
    char* positive_sign;
    char* negative_sign;
    char int_frac_digits, frac_digits, p_cs_precedes, p_sep_by_space, n_cs_precedes, n_sep_by_space,
        p_sign_posn, n_sign_posn, int_p_cs_precedes, int_p_sep_by_space, int_n_cs_precedes,
        int_n_sep_by_space, int_p_sign_posn, int_n_sign_posn;
};
WINAPK_GUEST_ABI GuestLconv* g_localeconv() {
    static char dot[] = ".", empty[] = "";
    static GuestLconv lc{dot,   empty, empty, empty, empty, empty, empty, empty, empty, empty,
                         CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX,
                         CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX, CHAR_MAX};
    return &lc;
}
WINAPK_GUEST_ABI size_t g_ctype_get_mb_cur_max() { return 4; }

// ---- UTF-8 <-> UTF-32 ----------------------------------------------------------
// Bionic's mbstate_t is 4 bytes; a pending partial sequence is kept in it as
// up to 3 bytes plus their count in the last byte.
struct MbState {
    unsigned char seq[3];
    unsigned char count;
};
constexpr size_t kIllegal = static_cast<size_t>(-1);
constexpr size_t kIncomplete = static_cast<size_t>(-2);

size_t sequence_length(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c & 0xe0) == 0xc0) return c >= 0xc2 ? 2 : 0;
    if ((c & 0xf0) == 0xe0) return 3;
    if ((c & 0xf8) == 0xf0) return c <= 0xf4 ? 4 : 0;
    return 0;
}

// Decodes one character from `s` (at most `n` bytes), continuing the partial
// sequence in `st`. Returns the bytes consumed from `s`, 0 for NUL, or
// kIllegal / kIncomplete like mbrtowc.
size_t decode(wchar32* out, const char* s, size_t n, MbState* st) {
    unsigned char buf[4];
    size_t have = st->count;
    std::memcpy(buf, st->seq, have);
    size_t used = 0;
    if (have == 0) {
        if (n == 0) return kIncomplete;
        buf[0] = static_cast<unsigned char>(s[0]);
        have = used = 1;
    }
    const size_t len = sequence_length(buf[0]);
    if (len == 0) {
        st->count = 0;
        *guest_errno() = 84;  // EILSEQ
        return kIllegal;
    }
    while (have < len && used < n) {
        const unsigned char c = static_cast<unsigned char>(s[used]);
        if ((c & 0xc0) != 0x80) {
            st->count = 0;
            *guest_errno() = 84;
            return kIllegal;
        }
        buf[have++] = c;
        ++used;
    }
    if (have < len) {
        std::memcpy(st->seq, buf, have);
        st->count = static_cast<unsigned char>(have);
        return kIncomplete;
    }
    st->count = 0;
    char32_t c;
    switch (len) {
        case 1: c = buf[0]; break;
        case 2: c = ((buf[0] & 0x1f) << 6) | (buf[1] & 0x3f); break;
        case 3: c = ((buf[0] & 0x0f) << 12) | ((buf[1] & 0x3f) << 6) | (buf[2] & 0x3f); break;
        default:
            c = ((buf[0] & 0x07) << 18) | ((buf[1] & 0x3f) << 12) | ((buf[2] & 0x3f) << 6) | (buf[3] & 0x3f);
            break;
    }
    // Overlong forms, surrogates and values past U+10FFFF are illegal.
    if ((len == 3 && c < 0x800) || (len == 4 && (c < 0x10000 || c > 0x10ffff)) || (c >= 0xd800 && c <= 0xdfff)) {
        *guest_errno() = 84;
        return kIllegal;
    }
    if (out) *out = c;
    return c == 0 ? 0 : used;
}

// Encodes `c` into `s` (4 bytes of room). Returns the length or kIllegal.
size_t encode(char* s, wchar32 c) {
    if (c < 0x80) {
        s[0] = static_cast<char>(c);
        return 1;
    }
    if (c < 0x800) {
        s[0] = static_cast<char>(0xc0 | (c >> 6));
        s[1] = static_cast<char>(0x80 | (c & 0x3f));
        return 2;
    }
    if (c >= 0xd800 && c <= 0xdfff) {
        *guest_errno() = 84;
        return kIllegal;
    }
    if (c < 0x10000) {
        s[0] = static_cast<char>(0xe0 | (c >> 12));
        s[1] = static_cast<char>(0x80 | ((c >> 6) & 0x3f));
        s[2] = static_cast<char>(0x80 | (c & 0x3f));
        return 3;
    }
    if (c <= 0x10ffff) {
        s[0] = static_cast<char>(0xf0 | (c >> 18));
        s[1] = static_cast<char>(0x80 | ((c >> 12) & 0x3f));
        s[2] = static_cast<char>(0x80 | ((c >> 6) & 0x3f));
        s[3] = static_cast<char>(0x80 | (c & 0x3f));
        return 4;
    }
    *guest_errno() = 84;
    return kIllegal;
}

MbState* state_or(void* ps, MbState* fallback) { return ps ? static_cast<MbState*>(ps) : fallback; }

WINAPK_GUEST_ABI size_t g_mbrtowc(wchar32* pwc, const char* s, size_t n, void* ps) {
    static thread_local MbState internal;
    MbState* st = state_or(ps, &internal);
    if (!s) {  // resets the state, like mbrtowc(NULL, "", 1, ps)
        st->count = 0;
        return 0;
    }
    return decode(pwc, s, n, st);
}
WINAPK_GUEST_ABI size_t g_mbrlen(const char* s, size_t n, void* ps) {
    static thread_local MbState internal;
    return g_mbrtowc(nullptr, s, n, state_or(ps, &internal));
}
WINAPK_GUEST_ABI int g_mbtowc(wchar32* pwc, const char* s, size_t n) {
    if (!s) return 0;  // UTF-8 has no shift state
    MbState st{};
    const size_t r = decode(pwc, s, n, &st);
    if (r == kIllegal || r == kIncomplete) {
        *guest_errno() = 84;
        return -1;
    }
    return static_cast<int>(r);
}
WINAPK_GUEST_ABI int g_mblen(const char* s, size_t n) { return g_mbtowc(nullptr, s, n); }
WINAPK_GUEST_ABI int g_mbsinit(const void* ps) { return !ps || static_cast<const MbState*>(ps)->count == 0; }

WINAPK_GUEST_ABI size_t g_wcrtomb(char* s, wchar32 c, void*) {
    char buf[4];
    if (!s) return 1;  // resets the state
    const size_t n = encode(buf, c);
    if (n != kIllegal) std::memcpy(s, buf, n);
    return n;
}
WINAPK_GUEST_ABI int g_wctomb(char* s, wchar32 c) {
    if (!s) return 0;
    const size_t n = g_wcrtomb(s, c, nullptr);
    return n == kIllegal ? -1 : static_cast<int>(n);
}
WINAPK_GUEST_ABI wchar32 g_btowc(int c) { return (c >= 0 && c < 0x80) ? static_cast<wchar32>(c) : 0xffffffffu; }
WINAPK_GUEST_ABI int g_wctob(wchar32 c) { return c < 0x80 ? static_cast<int>(c) : -1; }

// mbsnrtowcs: at most `nms` bytes of `*src`, at most `len` characters to `dst`
// (all of them if `dst` is null, then `*src` is not advanced).
WINAPK_GUEST_ABI size_t g_mbsnrtowcs(wchar32* dst, const char** src, size_t nms, size_t len, void* ps) {
    static thread_local MbState internal;
    MbState* st = state_or(ps, &internal);
    const char* s = *src;
    size_t out = 0;
    while (!dst || out < len) {
        wchar32 c;
        const size_t r = decode(&c, s, nms, st);
        if (r == kIllegal) {
            if (dst) *src = s;
            return kIllegal;
        }
        if (r == kIncomplete) {
            s += nms;
            break;
        }
        if (r == 0) {  // the terminating NUL
            if (dst) {
                dst[out] = 0;
                *src = nullptr;
            }
            return out;
        }
        if (dst) dst[out] = c;
        ++out;
        s += r;
        nms -= r;
    }
    if (dst) *src = s;
    return out;
}
WINAPK_GUEST_ABI size_t g_mbsrtowcs(wchar32* dst, const char** src, size_t len, void* ps) {
    return g_mbsnrtowcs(dst, src, SIZE_MAX, len, ps);
}
WINAPK_GUEST_ABI size_t g_mbstowcs(wchar32* dst, const char* src, size_t len) {
    MbState st{};
    return g_mbsnrtowcs(dst, &src, SIZE_MAX, len, &st);
}

// wcsnrtombs: at most `nwc` characters of `*src`, at most `len` bytes to `dst`
// (all of them if `dst` is null). A character that does not fit stops it.
WINAPK_GUEST_ABI size_t g_wcsnrtombs(char* dst, const wchar32** src, size_t nwc, size_t len, void*) {
    const wchar32* s = *src;
    size_t out = 0;
    for (; nwc > 0; ++s, --nwc) {
        char buf[4];
        if (*s == 0) {
            if (dst) {
                if (out >= len) break;
                dst[out] = 0;
                *src = nullptr;
            }
            return out;
        }
        const size_t n = encode(buf, *s);
        if (n == kIllegal) {
            if (dst) *src = s;
            return kIllegal;
        }
        if (dst) {
            if (out + n > len) break;
            std::memcpy(dst + out, buf, n);
        }
        out += n;
    }
    if (dst) *src = s;
    return out;
}
WINAPK_GUEST_ABI size_t g_wcsrtombs(char* dst, const wchar32** src, size_t len, void* ps) {
    return g_wcsnrtombs(dst, src, SIZE_MAX, len, ps);
}
WINAPK_GUEST_ABI size_t g_wcstombs(char* dst, const wchar32* src, size_t len) {
    return g_wcsnrtombs(dst, &src, SIZE_MAX, len, nullptr);
}

// ---- <wctype.h> (ASCII and Latin-1 classes, like Bionic's "C" locale) --------
bool latin1_letter(wchar32 c) { return c >= 0xc0 && c <= 0xff && c != 0xd7 && c != 0xf7; }
WINAPK_GUEST_ABI int g_iswupper(wchar32 c) { return (c >= 'A' && c <= 'Z') || (c >= 0xc0 && c <= 0xde && c != 0xd7); }
WINAPK_GUEST_ABI int g_iswlower(wchar32 c) { return (c >= 'a' && c <= 'z') || (c >= 0xdf && c <= 0xff && c != 0xf7); }
WINAPK_GUEST_ABI int g_iswalpha(wchar32 c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || latin1_letter(c) || c > 0xff;
}
WINAPK_GUEST_ABI int g_iswdigit(wchar32 c) { return c >= '0' && c <= '9'; }
WINAPK_GUEST_ABI int g_iswxdigit(wchar32 c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}
WINAPK_GUEST_ABI int g_iswalnum(wchar32 c) { return g_iswalpha(c) || g_iswdigit(c); }
WINAPK_GUEST_ABI int g_iswspace(wchar32 c) {
    return c == ' ' || (c >= '\t' && c <= '\r') || c == 0x85 || c == 0xa0 || c == 0x1680 ||
           (c >= 0x2000 && c <= 0x200a) || c == 0x2028 || c == 0x2029 || c == 0x202f || c == 0x205f ||
           c == 0x3000;
}
WINAPK_GUEST_ABI int g_iswblank(wchar32 c) { return c == ' ' || c == '\t'; }
WINAPK_GUEST_ABI int g_iswcntrl(wchar32 c) { return c < 0x20 || (c >= 0x7f && c < 0xa0); }
WINAPK_GUEST_ABI int g_iswprint(wchar32 c) { return !g_iswcntrl(c) && c <= 0x10ffff; }
WINAPK_GUEST_ABI int g_iswgraph(wchar32 c) { return g_iswprint(c) && !g_iswspace(c); }
WINAPK_GUEST_ABI int g_iswpunct(wchar32 c) { return g_iswgraph(c) && !g_iswalnum(c); }
WINAPK_GUEST_ABI wchar32 g_towupper(wchar32 c) {
    if ((c >= 'a' && c <= 'z') || (c >= 0xe0 && c <= 0xfe && c != 0xf7)) return c - 0x20;
    return c;
}
WINAPK_GUEST_ABI wchar32 g_towlower(wchar32 c) {
    if ((c >= 'A' && c <= 'Z') || (c >= 0xc0 && c <= 0xde && c != 0xd7)) return c + 0x20;
    return c;
}

// wctype/iswctype: Bionic's wctype_t values are 1 + the class index below.
const char* const kClasses[] = {"alnum", "alpha", "blank", "cntrl", "digit", "graph",
                                "lower", "print", "punct", "space", "upper", "xdigit"};
WINAPK_GUEST_ABI glong g_wctype(const char* name) {
    for (size_t i = 0; i < sizeof(kClasses) / sizeof(kClasses[0]); ++i) {
        if (!std::strcmp(name, kClasses[i])) return static_cast<glong>(i + 1);
    }
    return 0;
}
WINAPK_GUEST_ABI int g_iswctype(wchar32 c, glong type) {
    using F = int(WINAPK_GUEST_ABI*)(wchar32);
    static const F kTests[] = {g_iswalnum, g_iswalpha, g_iswblank, g_iswcntrl, g_iswdigit, g_iswgraph,
                               g_iswlower, g_iswprint, g_iswpunct, g_iswspace, g_iswupper, g_iswxdigit};
    if (type < 1 || type > 12) return 0;
    return kTests[type - 1](c);
}

// ---- more of <wchar.h> -----------------------------------------------------------
WINAPK_GUEST_ABI size_t g_wcsnlen(const wchar32* s, size_t n) {
    size_t i = 0;
    while (i < n && s[i]) ++i;
    return i;
}
WINAPK_GUEST_ABI int g_wcsncmp(const wchar32* a, const wchar32* b, size_t n) {
    for (; n > 0; ++a, ++b, --n) {
        if (*a != *b) return *a < *b ? -1 : 1;
        if (!*a) return 0;
    }
    return 0;
}
WINAPK_GUEST_ABI int g_wcscoll(const wchar32* a, const wchar32* b) { return g_wcsncmp(a, b, SIZE_MAX); }
WINAPK_GUEST_ABI size_t g_wcsxfrm(wchar32* d, const wchar32* s, size_t n) {
    const size_t len = g_wcsnlen(s, SIZE_MAX);
    if (n > 0) {
        const size_t copy = len < n ? len : n - 1;
        std::memcpy(d, s, copy * sizeof(wchar32));
        d[copy] = 0;
    }
    return len;
}
WINAPK_GUEST_ABI wchar32* g_wcschr(const wchar32* s, wchar32 c) {
    for (;; ++s) {
        if (*s == c) return const_cast<wchar32*>(s);
        if (!*s) return nullptr;
    }
}
WINAPK_GUEST_ABI wchar32* g_wcsrchr(const wchar32* s, wchar32 c) {
    const wchar32* last = nullptr;
    for (;; ++s) {
        if (*s == c) last = s;
        if (!*s) return const_cast<wchar32*>(last);
    }
}
WINAPK_GUEST_ABI wchar32* g_wcsstr(const wchar32* h, const wchar32* n) {
    const size_t nl = g_wcsnlen(n, SIZE_MAX);
    if (nl == 0) return const_cast<wchar32*>(h);
    for (; *h; ++h) {
        if (g_wcsncmp(h, n, nl) == 0) return const_cast<wchar32*>(h);
    }
    return nullptr;
}
WINAPK_GUEST_ABI wchar32* g_wcsncpy(wchar32* d, const wchar32* s, size_t n) {
    size_t i = 0;
    for (; i < n && s[i]; ++i) d[i] = s[i];
    for (; i < n; ++i) d[i] = 0;
    return d;
}
WINAPK_GUEST_ABI wchar32* g_wcscat(wchar32* d, const wchar32* s) {
    wchar32* end = d + g_wcsnlen(d, SIZE_MAX);
    while ((*end++ = *s++)) {}
    return d;
}
WINAPK_GUEST_ABI size_t g_wcslcpy(wchar32* d, const wchar32* s, size_t n) {
    const size_t len = g_wcsnlen(s, SIZE_MAX);
    if (n > 0) {
        const size_t copy = len < n ? len : n - 1;
        std::memcpy(d, s, copy * sizeof(wchar32));
        d[copy] = 0;
    }
    return len;
}
WINAPK_GUEST_ABI size_t g_wcslcat(wchar32* d, const wchar32* s, size_t n) {
    const size_t dl = g_wcsnlen(d, n);
    if (dl == n) return n + g_wcsnlen(s, SIZE_MAX);
    return dl + g_wcslcpy(d + dl, s, n - dl);
}

// wcsto*: the number is ASCII, so it is narrowed and handed to the CRT; the
// end pointer is mapped back by offset.
template <typename T, typename F>
T wide_number(const wchar32* s, wchar32** end, F convert) {
    std::string narrow;
    for (const wchar32* p = s; *p && *p < 0x80; ++p) narrow.push_back(static_cast<char>(*p));
    char* e = nullptr;
    errno = 0;
    T v = convert(narrow.c_str(), &e);
    if (errno) set_errno_from_crt(errno);
    if (end) *end = const_cast<wchar32*>(s + (e - narrow.c_str()));
    return v;
}
WINAPK_GUEST_ABI glong g_wcstol(const wchar32* s, wchar32** e, int b) {
    return wide_number<long long>(s, e, [b](const char* n, char** x) { return std::strtoll(n, x, b); });
}
WINAPK_GUEST_ABI long long g_wcstoll(const wchar32* s, wchar32** e, int b) {
    return wide_number<long long>(s, e, [b](const char* n, char** x) { return std::strtoll(n, x, b); });
}
WINAPK_GUEST_ABI gulong g_wcstoul(const wchar32* s, wchar32** e, int b) {
    return wide_number<unsigned long long>(s, e, [b](const char* n, char** x) { return std::strtoull(n, x, b); });
}
WINAPK_GUEST_ABI unsigned long long g_wcstoull(const wchar32* s, wchar32** e, int b) {
    return wide_number<unsigned long long>(s, e, [b](const char* n, char** x) { return std::strtoull(n, x, b); });
}
WINAPK_GUEST_ABI double g_wcstod(const wchar32* s, wchar32** e) {
    return wide_number<double>(s, e, [](const char* n, char** x) { return std::strtod(n, x); });
}
WINAPK_GUEST_ABI float g_wcstof(const wchar32* s, wchar32** e) {
    return wide_number<float>(s, e, [](const char* n, char** x) { return std::strtof(n, x); });
}
// A guest long double is binary128 in q0, not the host's (strtold, string.cpp): returned as the host's it
// would be a double in d0, which the caller does not read.
WINAPK_GUEST_ABI GuestQuad g_wcstold(const wchar32* s, wchar32** e) {
    return ld_to_quad(wide_number<long double>(s, e, [](const char* n, char** x) { return std::strtold(n, x); }));
}

// Bionic's _ctype_ (OpenBSD gen/ctype_.c): a pointer to a 1 + 256 entry class
// table indexed by c + 1. Guest code built against Bionic's <ctype.h> inlines
// it (isalnum is `_ctype_[c + 1] & (_U|_L|_N)`), so a zeroed stand-in is a null
// pointer and the first classified character faults. Upper 128 entries are 0.
constexpr char kCtU = 0x01, kCtL = 0x02, kCtN = 0x04, kCtS = 0x08, kCtP = 0x10, kCtC = 0x20, kCtX = 0x40,
               kCtB = static_cast<char>(0x80);
const char g_c_ctype[1 + 256] = {
    0,
    kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC,
    kCtC, kCtC | kCtS, kCtC | kCtS, kCtC | kCtS, kCtC | kCtS, kCtC | kCtS, kCtC, kCtC,
    kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC,
    kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC, kCtC,
    kCtS | kCtB, kCtP, kCtP, kCtP, kCtP, kCtP, kCtP, kCtP,
    kCtP, kCtP, kCtP, kCtP, kCtP, kCtP, kCtP, kCtP,
    kCtN, kCtN, kCtN, kCtN, kCtN, kCtN, kCtN, kCtN,
    kCtN, kCtN, kCtP, kCtP, kCtP, kCtP, kCtP, kCtP,
    kCtP, kCtU | kCtX, kCtU | kCtX, kCtU | kCtX, kCtU | kCtX, kCtU | kCtX, kCtU | kCtX, kCtU,
    kCtU, kCtU, kCtU, kCtU, kCtU, kCtU, kCtU, kCtU,
    kCtU, kCtU, kCtU, kCtU, kCtU, kCtU, kCtU, kCtU,
    kCtU, kCtU, kCtU, kCtP, kCtP, kCtP, kCtP, kCtP,
    kCtP, kCtL | kCtX, kCtL | kCtX, kCtL | kCtX, kCtL | kCtX, kCtL | kCtX, kCtL | kCtX, kCtL,
    kCtL, kCtL, kCtL, kCtL, kCtL, kCtL, kCtL, kCtL,
    kCtL, kCtL, kCtL, kCtL, kCtL, kCtL, kCtL, kCtL,
    kCtL, kCtL, kCtL, kCtP, kCtP, kCtP, kCtP, kCtC,
};
const char* g_ctype = g_c_ctype;

// nl_langinfo, as Bionic answers it for its one locale (C.UTF-8); the items are <langinfo.h>'s, 1 to
// 55 in this order. Missing, it answered null through the loader's fallback, which callers hand
// straight to strcmp or iconv_open.
WINAPK_GUEST_ABI const char* g_nl_langinfo(int item) {
    static const char* const kItems[] = {
        "",  // 0 is no item
        "UTF-8", "%F %T %z", "%F", "%T", "%I:%M:%S %p", "AM", "PM",                      // CODESET .. PM_STR
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",     // DAY_1 .. DAY_7
        "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat",                                  // ABDAY_1 .. ABDAY_7
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December",                                                           // MON_1 .. MON_12
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",  // ABMON_1 .. ABMON_12
        "", "", "", "", "",                                 // ERA, ERA_D_FMT, ERA_D_T_FMT, ERA_T_FMT, ALT_DIGITS
        ".", "", "^[yY]", "^[nN]", "",                      // RADIXCHAR, THOUSEP, YESEXPR, NOEXPR, CRNCYSTR
    };
    const size_t count = sizeof(kItems) / sizeof(kItems[0]);
    return item > 0 && static_cast<size_t>(item) < count ? kItems[item] : "";
}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("nl_langinfo", g_nl_langinfo),
    WINAPK_SHIM("nl_langinfo_l", g_nl_langinfo),
    WINAPK_SHIM("setlocale", g_setlocale),
    WINAPK_SHIM("newlocale", g_newlocale),
    WINAPK_SHIM("duplocale", g_duplocale),
    WINAPK_SHIM("freelocale", g_freelocale),
    WINAPK_SHIM("uselocale", g_uselocale),
    WINAPK_SHIM("localeconv", g_localeconv),
    WINAPK_SHIM("__ctype_get_mb_cur_max", g_ctype_get_mb_cur_max),
    {"_ctype_", &g_ctype},
    WINAPK_SHIM("mbrtowc", g_mbrtowc),
    WINAPK_SHIM("mbrlen", g_mbrlen),
    WINAPK_SHIM("mbtowc", g_mbtowc),
    WINAPK_SHIM("mblen", g_mblen),
    WINAPK_SHIM("mbsinit", g_mbsinit),
    WINAPK_SHIM("wcrtomb", g_wcrtomb),
    WINAPK_SHIM("wctomb", g_wctomb),
    WINAPK_SHIM("btowc", g_btowc),
    WINAPK_SHIM("wctob", g_wctob),
    WINAPK_SHIM("mbsnrtowcs", g_mbsnrtowcs),
    WINAPK_SHIM("mbsrtowcs", g_mbsrtowcs),
    WINAPK_SHIM("mbstowcs", g_mbstowcs),
    WINAPK_SHIM("wcsnrtombs", g_wcsnrtombs),
    WINAPK_SHIM("wcsrtombs", g_wcsrtombs),
    WINAPK_SHIM("wcstombs", g_wcstombs),
    WINAPK_SHIM("iswupper", g_iswupper),
    WINAPK_SHIM("iswlower", g_iswlower),
    WINAPK_SHIM("iswalpha", g_iswalpha),
    WINAPK_SHIM("iswdigit", g_iswdigit),
    WINAPK_SHIM("iswxdigit", g_iswxdigit),
    WINAPK_SHIM("iswalnum", g_iswalnum),
    WINAPK_SHIM("iswspace", g_iswspace),
    WINAPK_SHIM("iswblank", g_iswblank),
    WINAPK_SHIM("iswcntrl", g_iswcntrl),
    WINAPK_SHIM("iswprint", g_iswprint),
    WINAPK_SHIM("iswgraph", g_iswgraph),
    WINAPK_SHIM("iswpunct", g_iswpunct),
    WINAPK_SHIM("towupper", g_towupper),
    WINAPK_SHIM("towlower", g_towlower),
    WINAPK_SHIM("wctype", g_wctype),
    WINAPK_SHIM("iswctype", g_iswctype),
    // The _l variants: the locale argument comes last and is ignored.
    WINAPK_SHIM("iswupper_l", g_iswupper),
    WINAPK_SHIM("iswlower_l", g_iswlower),
    WINAPK_SHIM("iswalpha_l", g_iswalpha),
    WINAPK_SHIM("iswdigit_l", g_iswdigit),
    WINAPK_SHIM("iswxdigit_l", g_iswxdigit),
    WINAPK_SHIM("iswalnum_l", g_iswalnum),
    WINAPK_SHIM("iswspace_l", g_iswspace),
    WINAPK_SHIM("iswblank_l", g_iswblank),
    WINAPK_SHIM("iswcntrl_l", g_iswcntrl),
    WINAPK_SHIM("iswprint_l", g_iswprint),
    WINAPK_SHIM("iswgraph_l", g_iswgraph),
    WINAPK_SHIM("iswpunct_l", g_iswpunct),
    WINAPK_SHIM("towupper_l", g_towupper),
    WINAPK_SHIM("towlower_l", g_towlower),
    WINAPK_SHIM("wctype_l", g_wctype),
    WINAPK_SHIM("iswctype_l", g_iswctype),
    WINAPK_SHIM("wcscoll_l", g_wcscoll),
    WINAPK_SHIM("wcsxfrm_l", g_wcsxfrm),
    WINAPK_SHIM("wcsnlen", g_wcsnlen),
    WINAPK_SHIM("wcsncmp", g_wcsncmp),
    WINAPK_SHIM("wcscoll", g_wcscoll),
    WINAPK_SHIM("wcsxfrm", g_wcsxfrm),
    WINAPK_SHIM("wcschr", g_wcschr),
    WINAPK_SHIM("wcsrchr", g_wcsrchr),
    WINAPK_SHIM("wcsstr", g_wcsstr),
    WINAPK_SHIM("wcsncpy", g_wcsncpy),
    WINAPK_SHIM("wcscat", g_wcscat),
    WINAPK_SHIM("wcslcpy", g_wcslcpy),
    WINAPK_SHIM("wcslcat", g_wcslcat),
    WINAPK_SHIM("wcstol", g_wcstol),
    WINAPK_SHIM("wcstoll", g_wcstoll),
    WINAPK_SHIM("wcstoul", g_wcstoul),
    WINAPK_SHIM("wcstoull", g_wcstoull),
    WINAPK_SHIM("wcstod", g_wcstod),
    WINAPK_SHIM("wcstof", g_wcstof),
    WINAPK_SHIM("wcstold", g_wcstold),
};

}  // namespace

ShimTable locale_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
