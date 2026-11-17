// printf.cpp -- the printf family, sscanf, and liblog's formatted entry points.
//
// A guest's variadic arguments arrive the AAPCS64 way: integers and pointers
// in x0..x7 and then on the stack, doubles and long doubles in v0..v7 and then
// on the stack. The CRT's printf would read them the Windows ARM64 way (floating
// point in integer registers), so formatting cannot be handed to it whole. Instead the format
// string is parsed here, each argument is fetched from the guest's va_list by
// its real class (abi_thunk.h's SysVVaList), and each conversion is formatted
// on its own by the CRT's snprintf. Variadic functions get their va_list from
// make_sysv_variadic_entry; the v* forms receive the guest's own.
//
// Bionic's behaviour kept: %m prints strerror(errno), %p prints "0x..." (or
// "0x0"), %s of a null pointer prints "(null)", %ls and %lc take 32-bit
// characters, and a FORTIFY'd call aborts on overflow.

#include "bionic_internal.h"

#include "winapk/abi_thunk.h"

#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace winapk::bionic {

namespace {

// Where formatted output goes: a bounded buffer, or a growing string.
struct Sink {
    char* buf = nullptr;     // bounded output, or nullptr
    size_t cap = 0;          // its size, including the terminator
    std::string* str = nullptr;
    size_t total = 0;        // characters produced, written or not

    void put(const char* s, size_t n) {
        if (str) str->append(s, n);
        if (buf && total < cap) {
            const size_t room = cap - 1 - total;
            std::memcpy(buf + total, s, n < room ? n : room);
        }
        total += n;
    }
    void finish() {
        if (buf && cap) buf[total < cap ? total : cap - 1] = '\0';
    }
};

void put_utf8(std::string* out, uint32_t c) {
    if (c < 0x80) {
        out->push_back(static_cast<char>(c));
    } else if (c < 0x800) {
        out->push_back(static_cast<char>(0xc0 | (c >> 6)));
        out->push_back(static_cast<char>(0x80 | (c & 0x3f)));
    } else if (c < 0x10000) {
        out->push_back(static_cast<char>(0xe0 | (c >> 12)));
        out->push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | (c & 0x3f)));
    } else {
        out->push_back(static_cast<char>(0xf0 | (c >> 18)));
        out->push_back(static_cast<char>(0x80 | ((c >> 12) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3f)));
        out->push_back(static_cast<char>(0x80 | (c & 0x3f)));
    }
}

// Formats one conversion with the CRT; `spec` is a complete host format.
template <typename T>
void emit(Sink& out, const std::string& spec, T value) {
    char small[128];
    const int n = std::snprintf(small, sizeof(small), spec.c_str(), value);
    if (n < 0) return;
    if (static_cast<size_t>(n) < sizeof(small)) {
        out.put(small, static_cast<size_t>(n));
        return;
    }
    std::string big(static_cast<size_t>(n) + 1, '\0');
    std::snprintf(&big[0], big.size(), spec.c_str(), value);
    out.put(big.data(), static_cast<size_t>(n));
}

int format(Sink& out, const char* fmt, SysVVaList* va) {
    if (!fmt) return -1;
    const char* p = fmt;
    while (*p) {
        if (*p != '%') {
            const char* q = std::strchr(p, '%');
            const size_t n = q ? static_cast<size_t>(q - p) : std::strlen(p);
            out.put(p, n);
            p += n;
            continue;
        }
        const char* start = p++;
        if (*p == '%') {
            out.put("%", 1);
            ++p;
            continue;
        }
        // Flags, width, precision. A '*' takes an int argument.
        std::string flags;
        while (*p && std::strchr("-+ #0'", *p)) {
            if (*p != '\'') flags.push_back(*p);  // grouping: not in the "C" locale
            ++p;
        }
        std::string width;
        if (*p == '*') {
            const int w = static_cast<int>(sysv_va_next_gp(va));
            if (w < 0) flags.push_back('-');
            width = std::to_string(w < 0 ? -w : w);
            ++p;
        } else {
            while (*p >= '0' && *p <= '9') width.push_back(*p++);
        }
        std::string precision;
        bool has_precision = false;
        if (*p == '.') {
            has_precision = true;
            ++p;
            if (*p == '*') {
                const int pr = static_cast<int>(sysv_va_next_gp(va));
                if (pr >= 0) precision = std::to_string(pr);
                else has_precision = false;
                ++p;
            } else {
                while (*p >= '0' && *p <= '9') precision.push_back(*p++);
                if (precision.empty()) precision = "0";
            }
        }
        // Length: hh h l ll j z t q L.
        int len = 0;  // 0 int, 1 char, 2 short, 3 64-bit, 4 long double
        if (*p == 'h') {
            ++p;
            len = 2;
            if (*p == 'h') { ++p; len = 1; }
        } else if (*p == 'l') {
            ++p;
            len = 3;
            if (*p == 'l') ++p;
        } else if (*p == 'j' || *p == 'z' || *p == 't' || *p == 'q') {
            ++p;
            len = 3;
        } else if (*p == 'L') {
            ++p;
            len = 4;
        }
        const char conv = *p ? *p++ : '\0';
        const std::string head = "%" + flags + width + (has_precision ? "." + precision : "");
        switch (conv) {
            case 'd': case 'i': {
                const uint64_t raw = sysv_va_next_gp(va);
                long long v = len == 3 ? static_cast<long long>(raw)
                            : len == 1 ? static_cast<signed char>(raw)
                            : len == 2 ? static_cast<short>(raw)
                                       : static_cast<int>(raw);
                emit(out, head + "ll" + conv, v);
                break;
            }
            case 'u': case 'o': case 'x': case 'X': {
                const uint64_t raw = sysv_va_next_gp(va);
                unsigned long long v = len == 3 ? raw
                                     : len == 1 ? static_cast<unsigned char>(raw)
                                     : len == 2 ? static_cast<unsigned short>(raw)
                                                : static_cast<unsigned int>(raw);
                emit(out, head + "ll" + conv, v);
                break;
            }
            case 'c': {
                const uint32_t c = static_cast<uint32_t>(sysv_va_next_gp(va));
                std::string s;
                if (len == 3) put_utf8(&s, c);  // %lc: a 32-bit wint_t
                else s.push_back(static_cast<char>(c));
                emit(out, "%" + flags + width + "s", s.c_str());
                break;
            }
            case 's': {
                const void* arg = reinterpret_cast<const void*>(sysv_va_next_gp(va));
                if (len == 3) {
                    // %ls: 32-bit wchar_t; the precision counts characters.
                    std::string s;
                    if (!arg) {
                        s = "(null)";
                    } else {
                        const auto* w = static_cast<const char32_t*>(arg);
                        const size_t limit = has_precision ? std::strtoull(precision.c_str(), nullptr, 10) : SIZE_MAX;
                        for (size_t i = 0; i < limit && w[i]; ++i) put_utf8(&s, static_cast<uint32_t>(w[i]));
                    }
                    emit(out, "%" + flags + width + "s", s.c_str());
                } else {
                    emit(out, head + "s", arg ? static_cast<const char*>(arg) : "(null)");
                }
                break;
            }
            case 'p': {
                const uint64_t v = sysv_va_next_gp(va);
                emit(out, "%" + flags + width + "s", (std::string("0x") + [&] {
                    char b[24];
                    std::snprintf(b, sizeof(b), "%llx", static_cast<unsigned long long>(v));
                    return std::string(b);
                }()).c_str());
                break;
            }
            case 'f': case 'F': case 'e': case 'E': case 'g': case 'G': case 'a': case 'A': {
                // The UCRT has no 80-bit long double; it is formatted as a double.
                const double v = len == 4 ? static_cast<double>(guest_va_next_long_double(va)) : sysv_va_next_fp(va);
                emit(out, head + conv, v);
                break;
            }
            case 'n': {
                void* where = reinterpret_cast<void*>(sysv_va_next_gp(va));
                if (where) {
                    if (len == 3) *static_cast<long long*>(where) = static_cast<long long>(out.total);
                    else if (len == 2) *static_cast<short*>(where) = static_cast<short>(out.total);
                    else if (len == 1) *static_cast<signed char*>(where) = static_cast<signed char>(out.total);
                    else *static_cast<int*>(where) = static_cast<int>(out.total);
                }
                break;
            }
            case 'm': {
                emit(out, "%" + flags + width + "s", errno_text(*guest_errno()));
                break;
            }
            default:
                // Not a conversion this engine knows: print it as written.
                out.put(start, static_cast<size_t>(p - start));
                break;
        }
    }
    return static_cast<int>(out.total);
}

int format_bounded(char* buf, size_t cap, const char* fmt, SysVVaList* va) {
    Sink s;
    s.buf = buf;
    s.cap = cap;
    const int n = format(s, fmt, va);
    s.finish();
    return n;
}

std::string format_string(const char* fmt, SysVVaList* va) {
    std::string str;
    Sink s;
    s.str = &str;
    format(s, fmt, va);
    return str;
}

[[noreturn]] void fortify_fail(const char* what) {
    std::fprintf(stderr, "[win.apk] guest FORTIFY: %s: prevented buffer overflow\n", what);
    std::fflush(stderr);
    std::abort();
}

// ---- the v* forms: the guest passes its own va_list --------------------------
WINAPK_GUEST_ABI int g_vsnprintf(char* buf, size_t n, const char* fmt, SysVVaList* va) {
    return format_bounded(buf, n, fmt, va);
}
WINAPK_GUEST_ABI int g_vsprintf(char* buf, const char* fmt, SysVVaList* va) {
    return format_bounded(buf, SIZE_MAX, fmt, va);
}
WINAPK_GUEST_ABI int g_vasprintf(char** out, const char* fmt, SysVVaList* va) {
    const std::string s = format_string(fmt, va);
    *out = static_cast<char*>(std::malloc(s.size() + 1));
    if (!*out) return -1;
    std::memcpy(*out, s.c_str(), s.size() + 1);
    return static_cast<int>(s.size());
}
WINAPK_GUEST_ABI int g_vfprintf(void* file, const char* fmt, SysVVaList* va) {
    const std::string s = format_string(fmt, va);
    return file_write(file, s.data(), s.size()) == s.size() ? static_cast<int>(s.size()) : -1;
}
WINAPK_GUEST_ABI int g_vprintf(const char* fmt, SysVVaList* va) { return g_vfprintf(guest_stdout(), fmt, va); }
WINAPK_GUEST_ABI int g_vdprintf(int fd, const char* fmt, SysVVaList* va) {
    const std::string s = format_string(fmt, va);
    return fd_write(fd, s.data(), s.size()) < 0 ? -1 : static_cast<int>(s.size());
}
WINAPK_GUEST_ABI int g_vsprintf_chk(char* buf, int, size_t dn, const char* fmt, SysVVaList* va) {
    const int n = format_bounded(buf, dn, fmt, va);
    if (n >= 0 && static_cast<size_t>(n) >= dn) fortify_fail("vsprintf");
    return n;
}
WINAPK_GUEST_ABI int g_vsnprintf_chk(char* buf, size_t n, int, size_t dn, const char* fmt, SysVVaList* va) {
    if (n > dn) fortify_fail("vsnprintf");
    return format_bounded(buf, n, fmt, va);
}

// ---- sscanf ------------------------------------------------------------------
//
// Every scanf argument is a pointer, so they all come from the guest's integer
// registers and stack, and the CRT can do the parsing once the format speaks
// its language: Android's long is 64 bits, so %ld and friends become %lld.
// The pointers are handed over as a Microsoft va_list, an array of 8-byte
// slots. Not covered: %Lf (an 80-bit long double here, 64-bit there), %ls and
// %l[ (32-bit wchar_t), %m (POSIX allocation).
// The format in the CRT's terms, and the guest's pointers as a Microsoft
// va_list (null-terminated).
void scan_args(const char* fmt, SysVVaList* va, std::string* out, std::vector<void*>* args) {
    for (const char* p = fmt; *p;) {
        if (*p != '%') {
            *out += *p++;
            continue;
        }
        *out += *p++;
        if (*p == '%') {
            *out += *p++;
            continue;
        }
        bool suppressed = false;
        if (*p == '*') {
            suppressed = true;
            *out += *p++;
        }
        while (*p >= '0' && *p <= '9') *out += *p++;
        std::string length;
        while (*p && std::strchr("hljztLq", *p)) length += *p++;
        const char conv = *p;
        if (!conv) break;
        if (std::strchr("diuoxXn", conv) && (length == "l" || length == "q")) length = "ll";
        *out += length;
        *out += *p++;
        if (conv == '[') {
            // The set runs to the first ']' that is not its first member.
            if (*p == '^') *out += *p++;
            if (*p == ']') *out += *p++;
            while (*p && *p != ']') *out += *p++;
            if (*p) *out += *p++;
        }
        if (!suppressed) args->push_back(reinterpret_cast<void*>(sysv_va_next_gp(va)));
    }
    args->push_back(nullptr);
}
int scan(const char* in, const char* fmt, SysVVaList* va) {
    if (!in || !fmt) return -1;
    std::string out;
    std::vector<void*> args;
    scan_args(fmt, va, &out, &args);
    return std::vsscanf(in, out.c_str(), reinterpret_cast<va_list>(args.data()));
}
// fscanf and friends: the same translation, read from the stream by the CRT,
// which leaves the stream where the conversion stopped, as Bionic's does.
int scan_file(void* f, const char* fmt, SysVVaList* va) {
    if (!f || !fmt) return -1;
    std::string out;
    std::vector<void*> args;
    scan_args(fmt, va, &out, &args);
    return std::vfscanf(static_cast<FILE*>(host_stream(f)), out.c_str(), reinterpret_cast<va_list>(args.data()));
}

WINAPK_GUEST_ABI int g_vsscanf(const char* in, const char* fmt, SysVVaList* va) { return scan(in, fmt, va); }
WINAPK_GUEST_ABI int g_vfscanf(void* f, const char* fmt, SysVVaList* va) { return scan_file(f, fmt, va); }
WINAPK_GUEST_ABI int g_vscanf(const char* fmt, SysVVaList* va) { return scan_file(stdin, fmt, va); }

// liblog.
WINAPK_GUEST_ABI int g_android_log_vprint(int prio, const char* tag, const char* fmt, SysVVaList* va) {
    log_line(prio, tag, format_string(fmt, va).c_str());
    return 1;
}

// ---- the variadic forms: make_sysv_variadic_entry hands over the va_list -----
WINAPK_GUEST_ABI int h_printf(const char* fmt, SysVVaList* va) { return g_vprintf(fmt, va); }
WINAPK_GUEST_ABI int h_sscanf(const char* in, const char* fmt, SysVVaList* va) { return scan(in, fmt, va); }
WINAPK_GUEST_ABI int h_fscanf(void* f, const char* fmt, SysVVaList* va) { return scan_file(f, fmt, va); }
WINAPK_GUEST_ABI int h_scanf(const char* fmt, SysVVaList* va) { return scan_file(stdin, fmt, va); }
WINAPK_GUEST_ABI int h_fprintf(void* f, const char* fmt, SysVVaList* va) { return g_vfprintf(f, fmt, va); }
WINAPK_GUEST_ABI int h_dprintf(int fd, const char* fmt, SysVVaList* va) { return g_vdprintf(fd, fmt, va); }
WINAPK_GUEST_ABI int h_sprintf(char* b, const char* fmt, SysVVaList* va) { return g_vsprintf(b, fmt, va); }
WINAPK_GUEST_ABI int h_snprintf(char* b, size_t n, const char* fmt, SysVVaList* va) {
    return g_vsnprintf(b, n, fmt, va);
}
WINAPK_GUEST_ABI int h_asprintf(char** o, const char* fmt, SysVVaList* va) { return g_vasprintf(o, fmt, va); }
WINAPK_GUEST_ABI int h_sprintf_chk(char* b, int fl, size_t dn, const char* fmt, SysVVaList* va) {
    return g_vsprintf_chk(b, fl, dn, fmt, va);
}
WINAPK_GUEST_ABI int h_snprintf_chk(char* b, size_t n, int fl, size_t dn, const char* fmt, SysVVaList* va) {
    return g_vsnprintf_chk(b, n, fl, dn, fmt, va);
}
WINAPK_GUEST_ABI int h_android_log_print(int prio, const char* tag, const char* fmt, SysVVaList* va) {
    return g_android_log_vprint(prio, tag, fmt, va);
}
[[noreturn]] WINAPK_GUEST_ABI void h_android_log_assert(const char* cond, const char* tag, const char* fmt,
                                                         SysVVaList* va) {
    const std::string text = fmt ? format_string(fmt, va) : std::string("assertion failed: ") + (cond ? cond : "");
    log_line(7, tag, text.c_str());
    std::fprintf(stderr, "[win.apk] guest called __android_log_assert; aborting\n");
    std::fflush(stderr);
    std::abort();
}

}  // namespace

std::string format_va(const char* fmt, SysVVaList* va) { return format_string(fmt, va); }

ShimTable printf_symbols() {
    static const std::vector<ShimSymbol> symbols = [] {
        auto variadic = [](int named, auto handler) {
            return make_sysv_variadic_entry(named, reinterpret_cast<void*>(handler));
        };
        return std::vector<ShimSymbol>{
            WINAPK_SHIM("vsnprintf", g_vsnprintf),
            WINAPK_SHIM("vsprintf", g_vsprintf),
            WINAPK_SHIM("vasprintf", g_vasprintf),
            WINAPK_SHIM("vfprintf", g_vfprintf),
            WINAPK_SHIM("vprintf", g_vprintf),
            WINAPK_SHIM("vdprintf", g_vdprintf),
            WINAPK_SHIM("__vsprintf_chk", g_vsprintf_chk),
            WINAPK_SHIM("__vsnprintf_chk", g_vsnprintf_chk),
            WINAPK_SHIM("__android_log_vprint", g_android_log_vprint),
            WINAPK_SHIM("vsscanf", g_vsscanf),
            WINAPK_SHIM("vfscanf", g_vfscanf),
            WINAPK_SHIM("vscanf", g_vscanf),
            {"sscanf", variadic(2, &h_sscanf)},
            {"fscanf", variadic(2, &h_fscanf)},
            {"scanf", variadic(1, &h_scanf)},
            {"printf", variadic(1, &h_printf)},
            {"fprintf", variadic(2, &h_fprintf)},
            {"dprintf", variadic(2, &h_dprintf)},
            {"sprintf", variadic(2, &h_sprintf)},
            {"snprintf", variadic(3, &h_snprintf)},
            {"asprintf", variadic(2, &h_asprintf)},
            {"__sprintf_chk", variadic(4, &h_sprintf_chk)},
            {"__snprintf_chk", variadic(5, &h_snprintf_chk)},
            {"__android_log_print", variadic(3, &h_android_log_print)},
            {"__android_log_assert", variadic(3, &h_android_log_assert)},
        };
    }();
    return ShimTable{symbols.data(), symbols.size()};
}

}  // namespace winapk::bionic
