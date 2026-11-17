// string.cpp -- <string.h>, <strings.h>, the <stdlib.h> conversions, <ctype.h>
// and FORTIFY's _chk variants, for guest code.
//
// Most of these are the CRT's own functions behind an entry point the guest can call.
// Two things are not: FORTIFY's checks (abort on overflow, as Bionic does) and
// the wide-character functions, because Android's wchar_t is 32 bits and
// Windows' is 16.

#include "bionic_internal.h"

#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>

namespace winapk::bionic {

namespace {

[[noreturn]] void fortify_fail(const char* what) {
    std::fprintf(stderr, "[win.apk] guest FORTIFY: %s: prevented buffer overflow\n", what);
    std::fflush(stderr);
    std::abort();
}

// ---- <string.h> -------------------------------------------------------------
WINAPK_GUEST_ABI void* g_memchr(const void* s, int c, size_t n) { return const_cast<void*>(std::memchr(s, c, n)); }
WINAPK_GUEST_ABI void* g_memrchr(const void* s, int c, size_t n) {
    const auto* p = static_cast<const unsigned char*>(s);
    for (size_t i = n; i-- > 0;) {
        if (p[i] == static_cast<unsigned char>(c)) return const_cast<unsigned char*>(p + i);
    }
    return nullptr;
}
WINAPK_GUEST_ABI void* g_memmem(const void* h, size_t hn, const void* n, size_t nn) {
    if (nn == 0) return const_cast<void*>(h);
    const auto* hp = static_cast<const unsigned char*>(h);
    for (size_t i = 0; nn <= hn && i <= hn - nn; ++i) {
        if (std::memcmp(hp + i, n, nn) == 0) return const_cast<unsigned char*>(hp + i);
    }
    return nullptr;
}
WINAPK_GUEST_ABI char* g_strchr(const char* s, int c) { return const_cast<char*>(std::strchr(s, c)); }
WINAPK_GUEST_ABI char* g_strrchr(const char* s, int c) { return const_cast<char*>(std::strrchr(s, c)); }
WINAPK_GUEST_ABI char* g_strstr(const char* h, const char* n) { return const_cast<char*>(std::strstr(h, n)); }
WINAPK_GUEST_ABI char* g_strpbrk(const char* s, const char* a) { return const_cast<char*>(std::strpbrk(s, a)); }
WINAPK_GUEST_ABI size_t g_strspn(const char* s, const char* a) { return std::strspn(s, a); }
WINAPK_GUEST_ABI size_t g_strcspn(const char* s, const char* r) { return std::strcspn(s, r); }
WINAPK_GUEST_ABI size_t g_strnlen(const char* s, size_t n) { return strnlen(s, n); }
WINAPK_GUEST_ABI char* g_strncpy(char* d, const char* s, size_t n) { return std::strncpy(d, s, n); }
WINAPK_GUEST_ABI char* g_strcat(char* d, const char* s) { return std::strcat(d, s); }
WINAPK_GUEST_ABI char* g_strncat(char* d, const char* s, size_t n) { return std::strncat(d, s, n); }
WINAPK_GUEST_ABI char* g_stpcpy(char* d, const char* s) {
    const size_t n = std::strlen(s);
    std::memcpy(d, s, n + 1);
    return d + n;
}
WINAPK_GUEST_ABI size_t g_strlcpy(char* d, const char* s, size_t n) {
    const size_t len = std::strlen(s);
    if (n) {
        const size_t c = len < n - 1 ? len : n - 1;
        std::memcpy(d, s, c);
        d[c] = '\0';
    }
    return len;
}
WINAPK_GUEST_ABI size_t g_strlcat(char* d, const char* s, size_t n) {
    const size_t dl = strnlen(d, n);
    if (dl == n) return n + std::strlen(s);
    return dl + g_strlcpy(d + dl, s, n - dl);
}
WINAPK_GUEST_ABI char* g_strdup(const char* s) { return _strdup(s); }
WINAPK_GUEST_ABI char* g_strndup(const char* s, size_t n) {
    const size_t len = strnlen(s, n);
    char* d = static_cast<char*>(std::malloc(len + 1));
    if (!d) return nullptr;
    std::memcpy(d, s, len);
    d[len] = '\0';
    return d;
}
WINAPK_GUEST_ABI int g_strcasecmp(const char* a, const char* b) { return _stricmp(a, b); }
WINAPK_GUEST_ABI int g_strncasecmp(const char* a, const char* b, size_t n) { return _strnicmp(a, b, n); }
WINAPK_GUEST_ABI int g_strcoll(const char* a, const char* b) { return std::strcmp(a, b); }  // C locale
WINAPK_GUEST_ABI size_t g_strxfrm(char* d, const char* s, size_t n) {  // C locale: a copy
    const size_t len = std::strlen(s);
    if (n > 0) {
        const size_t copy = len < n ? len : n - 1;
        std::memcpy(d, s, copy);
        d[copy] = 0;
    }
    return len;
}
WINAPK_GUEST_ABI char* g_strtok_r(char* s, const char* d, char** save) { return strtok_s(s, d, save); }
WINAPK_GUEST_ABI char* g_strtok(char* s, const char* d) {
    static thread_local char* save = nullptr;
    return strtok_s(s, d, &save);
}
WINAPK_GUEST_ABI char* g_strsep(char** sp, const char* delim) {
    char* s = *sp;
    if (!s) return nullptr;
    char* e = s + std::strcspn(s, delim);
    if (*e) {
        *e = '\0';
        *sp = e + 1;
    } else {
        *sp = nullptr;
    }
    return s;
}
WINAPK_GUEST_ABI void g_bzero(void* p, size_t n) { std::memset(p, 0, n); }

// Linux's messages, by Linux number.
const char* linux_strerror(int e) {
    switch (e) {
        case 0: return "Success";
        case 1: return "Operation not permitted";
        case 2: return "No such file or directory";
        case 3: return "No such process";
        case 4: return "Interrupted system call";
        case 5: return "I/O error";
        case 6: return "No such device or address";
        case 7: return "Argument list too long";
        case 8: return "Exec format error";
        case 9: return "Bad file descriptor";
        case 10: return "No child processes";
        case 11: return "Try again";
        case 12: return "Out of memory";
        case 13: return "Permission denied";
        case 14: return "Bad address";
        case 16: return "Device or resource busy";
        case 17: return "File exists";
        case 18: return "Cross-device link";
        case 19: return "No such device";
        case 20: return "Not a directory";
        case 21: return "Is a directory";
        case 22: return "Invalid argument";
        case 23: return "File table overflow";
        case 24: return "Too many open files";
        case 25: return "Not a typewriter";
        case 26: return "Text file busy";
        case 27: return "File too large";
        case 28: return "No space left on device";
        case 29: return "Illegal seek";
        case 30: return "Read-only file system";
        case 31: return "Too many links";
        case 32: return "Broken pipe";
        case 33: return "Math argument out of domain of func";
        case 34: return "Math result not representable";
        case 35: return "Resource deadlock would occur";
        case 36: return "File name too long";
        case 37: return "No record locks available";
        case 38: return "Function not implemented";
        case 39: return "Directory not empty";
        case 40: return "Too many symbolic links encountered";
        case 61: return "No data available";
        case 62: return "Timer expired";
        case 75: return "Value too large for defined data type";
        case 84: return "Illegal byte sequence";
        case 95: return "Operation not supported on transport endpoint";
        case 98: return "Address already in use";
        case 104: return "Connection reset by peer";
        case 110: return "Connection timed out";
        case 111: return "Connection refused";
        case 113: return "No route to host";
        case 115: return "Operation now in progress";
        default: return nullptr;
    }
}

WINAPK_GUEST_ABI char* g_strerror(int e) { return const_cast<char*>(errno_text(e)); }
// XSI strerror_r (Bionic's default for C), and the GNU one.
WINAPK_GUEST_ABI int g_strerror_r(int e, char* buf, size_t n) {
    const char* m = g_strerror(e);
    if (n == 0) return kERANGE;
    g_strlcpy(buf, m, n);
    return std::strlen(m) < n ? 0 : kERANGE;
}
WINAPK_GUEST_ABI char* g_gnu_strerror_r(int e, char* buf, size_t n) {
    g_strerror_r(e, buf, n);
    return buf;
}

// ---- <stdlib.h> conversions ---------------------------------------------------
// The CRT sets its own errno (ERANGE on overflow); the guest sees it translated.
template <typename T, typename F>
T converted(F f) {
    errno = 0;
    T v = f();
    if (errno) set_errno_from_crt(errno);
    return v;
}
WINAPK_GUEST_ABI int g_atoi(const char* s) { return std::atoi(s); }
WINAPK_GUEST_ABI glong g_atol(const char* s) { return _atoi64(s); }
WINAPK_GUEST_ABI long long g_atoll(const char* s) { return _atoi64(s); }
WINAPK_GUEST_ABI double g_atof(const char* s) { return std::atof(s); }
// Guest long is 64 bits, so the 64-bit CRT functions back it.
WINAPK_GUEST_ABI glong g_strtol(const char* s, char** e, int b) {
    return converted<long long>([&] { return std::strtoll(s, e, b); });
}
WINAPK_GUEST_ABI gulong g_strtoul(const char* s, char** e, int b) {
    return converted<unsigned long long>([&] { return std::strtoull(s, e, b); });
}
WINAPK_GUEST_ABI long long g_strtoll(const char* s, char** e, int b) {
    return converted<long long>([&] { return std::strtoll(s, e, b); });
}
WINAPK_GUEST_ABI unsigned long long g_strtoull(const char* s, char** e, int b) {
    return converted<unsigned long long>([&] { return std::strtoull(s, e, b); });
}
WINAPK_GUEST_ABI double g_strtod(const char* s, char** e) {
    return converted<double>([&] { return std::strtod(s, e); });
}
WINAPK_GUEST_ABI float g_strtof(const char* s, char** e) {
    return converted<float>([&] { return std::strtof(s, e); });
}
// Android's long double is IEEE binary128, returned in q0. The CRT's is a plain
// double, returned in d0, which the caller would not read. ld_to_quad converts.
WINAPK_GUEST_ABI GuestQuad g_strtold(const char* s, char** e) {
    return ld_to_quad(converted<long double>([&] { return std::strtold(s, e); }));
}
WINAPK_GUEST_ABI glong g_labs(glong v) { return v < 0 ? -v : v; }
WINAPK_GUEST_ABI int g_abs(int v) { return v < 0 ? -v : v; }
WINAPK_GUEST_ABI long long g_llabs(long long v) { return v < 0 ? -v : v; }

// ---- <ctype.h> (the "C" locale) ---------------------------------------------
WINAPK_GUEST_ABI int g_isalnum(int c) { return std::isalnum(c & 0xff); }
WINAPK_GUEST_ABI int g_isalpha(int c) { return std::isalpha(c & 0xff); }
WINAPK_GUEST_ABI int g_isdigit(int c) { return c >= '0' && c <= '9'; }
WINAPK_GUEST_ABI int g_isxdigit(int c) { return std::isxdigit(c & 0xff); }
WINAPK_GUEST_ABI int g_islower(int c) { return c >= 'a' && c <= 'z'; }
WINAPK_GUEST_ABI int g_isupper(int c) { return c >= 'A' && c <= 'Z'; }
WINAPK_GUEST_ABI int g_isspace(int c) { return c == ' ' || (c >= '\t' && c <= '\r'); }
WINAPK_GUEST_ABI int g_isprint(int c) { return c >= 0x20 && c < 0x7f; }
WINAPK_GUEST_ABI int g_isgraph(int c) { return c > 0x20 && c < 0x7f; }
WINAPK_GUEST_ABI int g_ispunct(int c) { return g_isgraph(c) && !g_isalnum(c); }
WINAPK_GUEST_ABI int g_iscntrl(int c) { return (c >= 0 && c < 0x20) || c == 0x7f; }
WINAPK_GUEST_ABI int g_isblank(int c) { return c == ' ' || c == '\t'; }
WINAPK_GUEST_ABI int g_isascii(int c) { return c >= 0 && c < 0x80; }
WINAPK_GUEST_ABI int g_tolower(int c) { return (c >= 'A' && c <= 'Z') ? c + 32 : c; }
WINAPK_GUEST_ABI int g_toupper(int c) { return (c >= 'a' && c <= 'z') ? c - 32 : c; }

// ---- wide characters (32-bit wchar_t) -----------------------------------------
using wchar32 = char32_t;
WINAPK_GUEST_ABI size_t g_wcslen(const wchar32* s) {
    size_t n = 0;
    while (s[n]) ++n;
    return n;
}
WINAPK_GUEST_ABI int g_wcscmp(const wchar32* a, const wchar32* b) {
    while (*a && *a == *b) ++a, ++b;
    return *a < *b ? -1 : *a > *b ? 1 : 0;
}
WINAPK_GUEST_ABI wchar32* g_wcscpy(wchar32* d, const wchar32* s) {
    wchar32* r = d;
    while ((*d++ = *s++)) {}
    return r;
}
WINAPK_GUEST_ABI wchar32* g_wmemcpy(wchar32* d, const wchar32* s, size_t n) {
    return static_cast<wchar32*>(std::memcpy(d, s, n * sizeof(wchar32)));
}
WINAPK_GUEST_ABI wchar32* g_wmemmove(wchar32* d, const wchar32* s, size_t n) {
    return static_cast<wchar32*>(std::memmove(d, s, n * sizeof(wchar32)));
}
WINAPK_GUEST_ABI wchar32* g_wmemset(wchar32* d, wchar32 c, size_t n) {
    for (size_t i = 0; i < n; ++i) d[i] = c;
    return d;
}
WINAPK_GUEST_ABI int g_wmemcmp(const wchar32* a, const wchar32* b, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
    }
    return 0;
}
WINAPK_GUEST_ABI wchar32* g_wmemchr(const wchar32* s, wchar32 c, size_t n) {
    for (size_t i = 0; i < n; ++i) {
        if (s[i] == c) return const_cast<wchar32*>(s + i);
    }
    return nullptr;
}

// ---- FORTIFY ------------------------------------------------------------------
WINAPK_GUEST_ABI void* g_memcpy_chk(void* d, const void* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("memcpy");
    return std::memcpy(d, s, n);
}
WINAPK_GUEST_ABI void* g_memmove_chk(void* d, const void* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("memmove");
    return std::memmove(d, s, n);
}
WINAPK_GUEST_ABI void* g_memset_chk(void* d, int c, size_t n, size_t dn) {
    if (n > dn) fortify_fail("memset");
    return std::memset(d, c, n);
}
WINAPK_GUEST_ABI size_t g_strlen_chk(const char* s, size_t sn) {
    const size_t n = strnlen(s, sn);
    if (n >= sn) fortify_fail("strlen");
    return n;
}
WINAPK_GUEST_ABI char* g_strcpy_chk(char* d, const char* s, size_t dn) {
    if (std::strlen(s) >= dn) fortify_fail("strcpy");
    return std::strcpy(d, s);
}
WINAPK_GUEST_ABI char* g_stpcpy_chk(char* d, const char* s, size_t dn) {
    if (std::strlen(s) >= dn) fortify_fail("stpcpy");
    return g_stpcpy(d, s);
}
WINAPK_GUEST_ABI char* g_strncpy_chk(char* d, const char* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("strncpy");
    return std::strncpy(d, s, n);
}
WINAPK_GUEST_ABI char* g_strncpy_chk2(char* d, const char* s, size_t n, size_t dn, size_t sn) {
    if (n > dn) fortify_fail("strncpy");
    const size_t sl = strnlen(s, n);
    if (sl >= sn && sl < n) fortify_fail("strncpy: read overflow");
    return std::strncpy(d, s, n);
}
WINAPK_GUEST_ABI char* g_strcat_chk(char* d, const char* s, size_t dn) {
    if (std::strlen(d) + std::strlen(s) >= dn) fortify_fail("strcat");
    return std::strcat(d, s);
}
WINAPK_GUEST_ABI char* g_strncat_chk(char* d, const char* s, size_t n, size_t dn) {
    const size_t sl = strnlen(s, n);
    if (std::strlen(d) + sl >= dn) fortify_fail("strncat");
    return std::strncat(d, s, n);
}
WINAPK_GUEST_ABI size_t g_strlcpy_chk(char* d, const char* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("strlcpy");
    return g_strlcpy(d, s, n);
}
WINAPK_GUEST_ABI size_t g_strlcat_chk(char* d, const char* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("strlcat");
    return g_strlcat(d, s, n);
}
WINAPK_GUEST_ABI char* g_strchr_chk(const char* s, int c, size_t sn) {
    for (size_t i = 0; i < sn; ++i) {
        if (s[i] == static_cast<char>(c)) return const_cast<char*>(s + i);
        if (!s[i]) return nullptr;
    }
    fortify_fail("strchr");
}
WINAPK_GUEST_ABI char* g_strrchr_chk(const char* s, int c, size_t sn) {
    const size_t n = strnlen(s, sn);
    if (n >= sn) fortify_fail("strrchr");
    return const_cast<char*>(std::strrchr(s, c));
}
WINAPK_GUEST_ABI void* g_memchr_chk(const void* s, int c, size_t n, size_t sn) {
    if (n > sn) fortify_fail("memchr");
    return const_cast<void*>(std::memchr(s, c, n));
}
WINAPK_GUEST_ABI void* g_memrchr_chk(const void* s, int c, size_t n, size_t sn) {
    if (n > sn) fortify_fail("memrchr");
    return g_memrchr(s, c, n);
}
// mempcpy, stpncpy and memccpy, with the FORTIFY forms of the first two. The UCRT has none of them.
WINAPK_GUEST_ABI void* g_mempcpy(void* d, const void* s, size_t n) {
    return static_cast<char*>(std::memcpy(d, s, n)) + n;
}
WINAPK_GUEST_ABI void* g_mempcpy_chk(void* d, const void* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("mempcpy");
    return g_mempcpy(d, s, n);
}
// stpncpy: strncpy, answering where the copy's terminator went (d + n when there was no room for one).
WINAPK_GUEST_ABI char* g_stpncpy(char* d, const char* s, size_t n) {
    const size_t len = strnlen(s, n);
    std::memcpy(d, s, len);
    std::memset(d + len, 0, n - len);
    return d + len;
}
WINAPK_GUEST_ABI char* g_stpncpy_chk(char* d, const char* s, size_t n, size_t dn) {
    if (n > dn) fortify_fail("stpncpy");
    return g_stpncpy(d, s, n);
}
WINAPK_GUEST_ABI char* g_stpncpy_chk2(char* d, const char* s, size_t n, size_t dn, size_t sn) {
    if (n > dn) fortify_fail("stpncpy");
    const size_t sl = strnlen(s, n);
    if (sl >= sn && sl < n) fortify_fail("stpncpy: read overflow");
    return g_stpncpy(d, s, n);
}
WINAPK_GUEST_ABI void* g_memccpy(void* d, const void* s, int c, size_t n) {
    const void* hit = std::memchr(s, c, n);
    const size_t len = hit ? static_cast<size_t>(static_cast<const char*>(hit) - static_cast<const char*>(s)) + 1 : n;
    std::memcpy(d, s, len);
    return hit ? static_cast<char*>(d) + len : nullptr;
}

// ---- <libgen.h>: bionic's dirname and basename leave their argument alone and
// answer in a per-thread buffer, as POSIX allows.
WINAPK_GUEST_ABI char* g_dirname(const char* path) {
    static thread_local char buf[4096];
    if (!path || !*path) return std::strcpy(buf, ".");
    size_t end = std::strlen(path);
    while (end > 1 && path[end - 1] == '/') --end;  // trailing slashes
    while (end > 0 && path[end - 1] != '/') --end;   // the last component
    if (end == 0) return std::strcpy(buf, ".");
    while (end > 1 && path[end - 1] == '/') --end;  // the slashes before it
    if (end >= sizeof buf) return *guest_errno() = kENAMETOOLONG, nullptr;
    std::memcpy(buf, path, end);
    buf[end] = '\0';
    return buf;
}
WINAPK_GUEST_ABI char* g_basename(const char* path) {
    static thread_local char buf[4096];
    if (!path || !*path) return std::strcpy(buf, ".");
    size_t end = std::strlen(path);
    while (end > 1 && path[end - 1] == '/') --end;
    size_t start = end;
    while (start > 0 && path[start - 1] != '/') --start;
    if (end - start >= sizeof buf) return *guest_errno() = kENAMETOOLONG, nullptr;
    std::memcpy(buf, path + start, end - start);
    buf[end - start] = '\0';
    return buf;
}
// GNU basename (<string.h>): the text after the last slash, in place.
WINAPK_GUEST_ABI const char* g_gnu_basename(const char* path) {
    const char* slash = std::strrchr(path, '/');
    return slash ? slash + 1 : path;
}

// ---- fnmatch: shell wildcards (*, ?, [...] with ! or ^ and ranges, \ escapes) ----
constexpr int kFnmNoescape = 0x01, kFnmPathname = 0x02, kFnmPeriod = 0x04, kFnmCasefold = 0x10;

bool fn_match(const char* p, const char* s, const char* start, int flags) {
    auto fold = [&](unsigned char c) { return (flags & kFnmCasefold) ? std::tolower(c) : c; };
    auto leading_dot = [&](const char* at) {
        return (flags & kFnmPeriod) && *at == '.' &&
               (at == start || ((flags & kFnmPathname) && at[-1] == '/'));
    };
    for (; *p; ++p, ++s) {
        switch (*p) {
            case '?':
                if (!*s || ((flags & kFnmPathname) && *s == '/') || leading_dot(s)) return false;
                break;
            case '*': {
                while (p[1] == '*') ++p;
                if (leading_dot(s)) return false;
                for (const char* t = s;; ++t) {
                    if (fn_match(p + 1, t, start, flags)) return true;
                    if (!*t || ((flags & kFnmPathname) && *t == '/')) return false;
                }
            }
            case '[': {
                if (!*s || ((flags & kFnmPathname) && *s == '/') || leading_dot(s)) return false;
                const char* q = p + 1;
                const bool negate = *q == '!' || *q == '^';
                if (negate) ++q;
                bool hit = false;
                const char* first = q;
                for (; *q && (*q != ']' || q == first); ++q) {
                    unsigned char lo = static_cast<unsigned char>(*q), hi = lo;
                    if (q[1] == '-' && q[2] && q[2] != ']') {
                        hi = static_cast<unsigned char>(q[2]);
                        q += 2;
                    }
                    const int c = fold(static_cast<unsigned char>(*s));
                    if (c >= fold(lo) && c <= fold(hi)) hit = true;
                }
                if (*q != ']') {  // no closing bracket: a literal '['
                    if (*s != '[') return false;
                    break;
                }
                if (hit == negate) return false;
                p = q;
                break;
            }
            case '\\':
                if (!(flags & kFnmNoescape) && p[1]) ++p;
                [[fallthrough]];
            default:
                if (fold(static_cast<unsigned char>(*p)) != fold(static_cast<unsigned char>(*s))) return false;
        }
    }
    return *s == '\0';
}
WINAPK_GUEST_ABI int g_fnmatch(const char* pattern, const char* string, int flags) {
    return fn_match(pattern, string, string, flags) ? 0 : 1;  // FNM_NOMATCH
}

}  // namespace

const char* errno_text(int e) {
    static thread_local char buf[64];
    if (const char* m = linux_strerror(e)) return m;
    std::snprintf(buf, sizeof(buf), "Unknown error %d", e);
    return buf;
}

namespace {

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("dirname", g_dirname),
    WINAPK_SHIM("basename", g_basename),
    WINAPK_SHIM("__gnu_basename", g_gnu_basename),
    WINAPK_SHIM("fnmatch", g_fnmatch),
    WINAPK_SHIM("memchr", g_memchr),
    WINAPK_SHIM("memrchr", g_memrchr),
    WINAPK_SHIM("memmem", g_memmem),
    WINAPK_SHIM("strchr", g_strchr),
    WINAPK_SHIM("strrchr", g_strrchr),
    WINAPK_SHIM("strstr", g_strstr),
    WINAPK_SHIM("strpbrk", g_strpbrk),
    WINAPK_SHIM("strspn", g_strspn),
    WINAPK_SHIM("strcspn", g_strcspn),
    WINAPK_SHIM("strnlen", g_strnlen),
    WINAPK_SHIM("strncpy", g_strncpy),
    WINAPK_SHIM("strcat", g_strcat),
    WINAPK_SHIM("strncat", g_strncat),
    WINAPK_SHIM("stpcpy", g_stpcpy),
    WINAPK_SHIM("strlcpy", g_strlcpy),
    WINAPK_SHIM("strlcat", g_strlcat),
    WINAPK_SHIM("strdup", g_strdup),
    WINAPK_SHIM("strndup", g_strndup),
    WINAPK_SHIM("strcasecmp", g_strcasecmp),
    WINAPK_SHIM("strncasecmp", g_strncasecmp),
    WINAPK_SHIM("strcoll", g_strcoll),
    WINAPK_SHIM("strtok", g_strtok),
    WINAPK_SHIM("strtok_r", g_strtok_r),
    WINAPK_SHIM("strsep", g_strsep),
    WINAPK_SHIM("bzero", g_bzero),
    WINAPK_SHIM("strerror", g_strerror),
    WINAPK_SHIM("strerror_r", g_strerror_r),
    WINAPK_SHIM("__gnu_strerror_r", g_gnu_strerror_r),
    WINAPK_SHIM("atoi", g_atoi),
    WINAPK_SHIM("atol", g_atol),
    WINAPK_SHIM("atoll", g_atoll),
    WINAPK_SHIM("atof", g_atof),
    WINAPK_SHIM("strtol", g_strtol),
    WINAPK_SHIM("strtoul", g_strtoul),
    WINAPK_SHIM("strtoll", g_strtoll),
    WINAPK_SHIM("strtoull", g_strtoull),
    WINAPK_SHIM("strtoimax", g_strtoll),
    WINAPK_SHIM("strtoumax", g_strtoull),
    WINAPK_SHIM("strtod", g_strtod),
    WINAPK_SHIM("strtof", g_strtof),
    WINAPK_SHIM("strtold", g_strtold),
    WINAPK_SHIM("abs", g_abs),
    WINAPK_SHIM("labs", g_labs),
    WINAPK_SHIM("llabs", g_llabs),
    WINAPK_SHIM("isalnum", g_isalnum),
    WINAPK_SHIM("isalpha", g_isalpha),
    WINAPK_SHIM("isdigit", g_isdigit),
    WINAPK_SHIM("isxdigit", g_isxdigit),
    WINAPK_SHIM("islower", g_islower),
    WINAPK_SHIM("isupper", g_isupper),
    WINAPK_SHIM("isspace", g_isspace),
    WINAPK_SHIM("isprint", g_isprint),
    WINAPK_SHIM("isgraph", g_isgraph),
    WINAPK_SHIM("ispunct", g_ispunct),
    WINAPK_SHIM("iscntrl", g_iscntrl),
    WINAPK_SHIM("isblank", g_isblank),
    WINAPK_SHIM("isascii", g_isascii),
    WINAPK_SHIM("tolower", g_tolower),
    WINAPK_SHIM("toupper", g_toupper),
    WINAPK_SHIM("wcslen", g_wcslen),
    WINAPK_SHIM("wcscmp", g_wcscmp),
    WINAPK_SHIM("wcscpy", g_wcscpy),
    WINAPK_SHIM("wmemcpy", g_wmemcpy),
    WINAPK_SHIM("wmemmove", g_wmemmove),
    WINAPK_SHIM("wmemset", g_wmemset),
    WINAPK_SHIM("wmemcmp", g_wmemcmp),
    WINAPK_SHIM("wmemchr", g_wmemchr),
    WINAPK_SHIM("__memcpy_chk", g_memcpy_chk),
    WINAPK_SHIM("__memmove_chk", g_memmove_chk),
    WINAPK_SHIM("__memset_chk", g_memset_chk),
    WINAPK_SHIM("__strlen_chk", g_strlen_chk),
    WINAPK_SHIM("__strcpy_chk", g_strcpy_chk),
    WINAPK_SHIM("__stpcpy_chk", g_stpcpy_chk),
    WINAPK_SHIM("__strncpy_chk", g_strncpy_chk),
    WINAPK_SHIM("__strncpy_chk2", g_strncpy_chk2),
    WINAPK_SHIM("__strcat_chk", g_strcat_chk),
    WINAPK_SHIM("__strncat_chk", g_strncat_chk),
    WINAPK_SHIM("__strlcpy_chk", g_strlcpy_chk),
    WINAPK_SHIM("__strlcat_chk", g_strlcat_chk),
    WINAPK_SHIM("__strchr_chk", g_strchr_chk),
    WINAPK_SHIM("__strrchr_chk", g_strrchr_chk),
    WINAPK_SHIM("__memchr_chk", g_memchr_chk),
    WINAPK_SHIM("__memrchr_chk", g_memrchr_chk),
    WINAPK_SHIM("mempcpy", g_mempcpy),
    WINAPK_SHIM("__mempcpy_chk", g_mempcpy_chk),
    WINAPK_SHIM("stpncpy", g_stpncpy),
    WINAPK_SHIM("__stpncpy_chk", g_stpncpy_chk),
    WINAPK_SHIM("__stpncpy_chk2", g_stpncpy_chk2),
    WINAPK_SHIM("memccpy", g_memccpy),
    WINAPK_SHIM("strxfrm", g_strxfrm),
    // The _l variants (locale.cpp explains): the locale argument comes last
    // and is ignored.
    WINAPK_SHIM("strcoll_l", g_strcoll),
    WINAPK_SHIM("strxfrm_l", g_strxfrm),
    WINAPK_SHIM("strtol_l", g_strtol),
    WINAPK_SHIM("strtoul_l", g_strtoul),
    WINAPK_SHIM("strtoll_l", g_strtoll),
    WINAPK_SHIM("strtoull_l", g_strtoull),
    WINAPK_SHIM("strtod_l", g_strtod),
    WINAPK_SHIM("strtof_l", g_strtof),
    WINAPK_SHIM("strtold_l", g_strtold),
    WINAPK_SHIM("isalnum_l", g_isalnum),
    WINAPK_SHIM("isalpha_l", g_isalpha),
    WINAPK_SHIM("isdigit_l", g_isdigit),
    WINAPK_SHIM("isxdigit_l", g_isxdigit),
    WINAPK_SHIM("islower_l", g_islower),
    WINAPK_SHIM("isupper_l", g_isupper),
    WINAPK_SHIM("isspace_l", g_isspace),
    WINAPK_SHIM("isprint_l", g_isprint),
    WINAPK_SHIM("isgraph_l", g_isgraph),
    WINAPK_SHIM("ispunct_l", g_ispunct),
    WINAPK_SHIM("iscntrl_l", g_iscntrl),
    WINAPK_SHIM("isblank_l", g_isblank),
    WINAPK_SHIM("tolower_l", g_tolower),
    WINAPK_SHIM("toupper_l", g_toupper),
};

}  // namespace

ShimTable string_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
