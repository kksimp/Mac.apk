// misc.cpp -- time, memory mapping, system properties, the environment,
// process identity, limits, randomness and libm, for guest code.
//
// System properties and the environment are the guest's own stores, not the
// host's: the Java framework pushes its properties (SystemProperties.
// macapkPushToNative -> NativeCocoa.setSystemProperty) and Os.setenv lands in
// the same environment native code reads, so Java and native code agree, as
// upstream's shim arranges on the Mac.

#include "bionic_internal.h"

#include "winapk/mini_bionic.h"

#include <algorithm>
#include <cerrno>
#include <climits>
#include <limits>
#include <cmath>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <io.h>
#include <windows.h>
#include <bcrypt.h>
#include "winapk/arch.h"
#include <getopt.h>

// BSD's optreset, which <getopt.h> declares only under _BSD_SOURCE.
extern "C" int __mingw_optreset;

namespace winapk::bionic {

namespace {

// ---- time ---------------------------------------------------------------------------
struct GuestTimespec {
    int64_t tv_sec;
    int64_t tv_nsec;
};
struct GuestTimeval {
    int64_t tv_sec;
    int64_t tv_usec;
};

int64_t realtime_ns() {
    FILETIME ft;
    GetSystemTimePreciseAsFileTime(&ft);
    const int64_t t = (static_cast<int64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
    return (t - 116444736000000000LL) * 100;
}
int64_t monotonic_ns() {
    static const int64_t freq = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    LARGE_INTEGER c;
    QueryPerformanceCounter(&c);
    return static_cast<int64_t>(c.QuadPart / freq * 1000000000LL + (c.QuadPart % freq) * 1000000000LL / freq);
}
int64_t thread_cpu_ns(HANDLE thread, bool process) {
    FILETIME created, exited, kernel, user;
    const BOOL ok = process ? GetProcessTimes(GetCurrentProcess(), &created, &exited, &kernel, &user)
                            : GetThreadTimes(thread, &created, &exited, &kernel, &user);
    if (!ok) return 0;
    auto v = [](const FILETIME& f) { return (static_cast<int64_t>(f.dwHighDateTime) << 32) | f.dwLowDateTime; };
    return (v(kernel) + v(user)) * 100;
}

// Linux clock ids: REALTIME 0, MONOTONIC 1, PROCESS_CPUTIME 2, THREAD_CPUTIME 3,
// MONOTONIC_RAW 4, REALTIME_COARSE 5, MONOTONIC_COARSE 6, BOOTTIME 7.
WINAPK_GUEST_ABI int g_clock_gettime(int clock, GuestTimespec* ts) {
    int64_t ns;
    switch (clock) {
        case 0: case 5: ns = realtime_ns(); break;
        case 1: case 4: case 6: case 7: ns = monotonic_ns(); break;
        case 2: ns = thread_cpu_ns(nullptr, true); break;
        case 3: ns = thread_cpu_ns(GetCurrentThread(), false); break;
        default: *guest_errno() = kEINVAL; return -1;
    }
    ts->tv_sec = ns / 1000000000LL;
    ts->tv_nsec = ns % 1000000000LL;
    return 0;
}
WINAPK_GUEST_ABI int g_clock_getres(int, GuestTimespec* ts) {
    if (ts) {
        ts->tv_sec = 0;
        ts->tv_nsec = 100;
    }
    return 0;
}
WINAPK_GUEST_ABI int g_gettimeofday(GuestTimeval* tv, void*) {
    const int64_t ns = realtime_ns();
    if (tv) {
        tv->tv_sec = ns / 1000000000LL;
        tv->tv_usec = (ns % 1000000000LL) / 1000;
    }
    return 0;
}
WINAPK_GUEST_ABI int64_t g_time(int64_t* t) {
    const int64_t s = realtime_ns() / 1000000000LL;
    if (t) *t = s;
    return s;
}
// difftime returns a double (d0): the missing-function stub's 0 in x0 would
// leave the caller reading whatever d0 held.
WINAPK_GUEST_ABI double g_difftime(int64_t end, int64_t start) {
    return static_cast<double>(end) - static_cast<double>(start);
}
WINAPK_GUEST_ABI glong g_clock() { return thread_cpu_ns(nullptr, true) / 1000; }  // CLOCKS_PER_SEC 1000000

void sleep_ns(int64_t ns) {
    if (ns <= 0) return;
    // A high-resolution waitable timer where Windows has one; Sleep otherwise.
    static const bool high_res = [] {
        HANDLE t = CreateWaitableTimerExW(nullptr, nullptr, 0x00000002 /*HIGH_RESOLUTION*/, TIMER_ALL_ACCESS);
        if (t) CloseHandle(t);
        return t != nullptr;
    }();
    HANDLE timer = CreateWaitableTimerExW(nullptr, nullptr, high_res ? 0x00000002 : 0, TIMER_ALL_ACCESS);
    if (!timer) {
        Sleep(static_cast<DWORD>((ns + 999999) / 1000000));
        return;
    }
    LARGE_INTEGER due;
    due.QuadPart = -(ns / 100 > 0 ? ns / 100 : 1);
    SetWaitableTimer(timer, &due, 0, nullptr, nullptr, FALSE);
    WaitForSingleObject(timer, INFINITE);
    CloseHandle(timer);
}
WINAPK_GUEST_ABI int g_nanosleep(const GuestTimespec* req, GuestTimespec* rem) {
    if (!req || req->tv_nsec < 0 || req->tv_nsec >= 1000000000LL) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    sleep_ns(req->tv_sec * 1000000000LL + req->tv_nsec);
    if (rem) rem->tv_sec = rem->tv_nsec = 0;
    return 0;
}
WINAPK_GUEST_ABI int g_clock_nanosleep(int clock, int flags, const GuestTimespec* req, GuestTimespec* rem) {
    if (flags & 1) {  // TIMER_ABSTIME
        GuestTimespec now;
        g_clock_gettime(clock, &now);
        sleep_ns((req->tv_sec - now.tv_sec) * 1000000000LL + (req->tv_nsec - now.tv_nsec));
        return 0;
    }
    return g_nanosleep(req, rem) == 0 ? 0 : *guest_errno();
}
WINAPK_GUEST_ABI int g_usleep(uint32_t us) {
    sleep_ns(static_cast<int64_t>(us) * 1000);
    return 0;
}
WINAPK_GUEST_ABI unsigned g_sleep(unsigned s) {
    sleep_ns(static_cast<int64_t>(s) * 1000000000LL);
    return 0;
}

// struct tm is the same 9 ints on both sides, then Bionic adds tm_gmtoff (long)
// and tm_zone (const char*).
struct GuestTm {
    int tm_sec, tm_min, tm_hour, tm_mday, tm_mon, tm_year, tm_wday, tm_yday, tm_isdst;
    int64_t tm_gmtoff;
    const char* tm_zone;
};
void to_guest_tm(const std::tm& t, GuestTm* g, int64_t gmtoff, const char* zone) {
    g->tm_sec = t.tm_sec;
    g->tm_min = t.tm_min;
    g->tm_hour = t.tm_hour;
    g->tm_mday = t.tm_mday;
    g->tm_mon = t.tm_mon;
    g->tm_year = t.tm_year;
    g->tm_wday = t.tm_wday;
    g->tm_yday = t.tm_yday;
    g->tm_isdst = t.tm_isdst;
    g->tm_gmtoff = gmtoff;
    g->tm_zone = zone;
}
std::tm from_guest_tm(const GuestTm* g) {
    std::tm t{};
    t.tm_sec = g->tm_sec;
    t.tm_min = g->tm_min;
    t.tm_hour = g->tm_hour;
    t.tm_mday = g->tm_mday;
    t.tm_mon = g->tm_mon;
    t.tm_year = g->tm_year;
    t.tm_wday = g->tm_wday;
    t.tm_yday = g->tm_yday;
    t.tm_isdst = g->tm_isdst;
    return t;
}
WINAPK_GUEST_ABI GuestTm* g_gmtime_r(const int64_t* t, GuestTm* out) {
    std::tm tm;
    const __time64_t v = *t;
    if (_gmtime64_s(&tm, &v)) return nullptr;
    to_guest_tm(tm, out, 0, "UTC");
    return out;
}
WINAPK_GUEST_ABI GuestTm* g_localtime_r(const int64_t* t, GuestTm* out) {
    std::tm tm;
    const __time64_t v = *t;
    if (_localtime64_s(&tm, &v)) return nullptr;
    // The UTC offset in effect: the local broken-down time read back as UTC.
    std::tm copy = tm;
    const int64_t gmtoff = _mkgmtime64(&copy) - v;
    to_guest_tm(tm, out, gmtoff, tm.tm_isdst > 0 ? "DST" : "STD");
    return out;
}
WINAPK_GUEST_ABI GuestTm* g_gmtime(const int64_t* t) {
    static thread_local GuestTm tm;
    return g_gmtime_r(t, &tm);
}
WINAPK_GUEST_ABI GuestTm* g_localtime(const int64_t* t) {
    static thread_local GuestTm tm;
    return g_localtime_r(t, &tm);
}
WINAPK_GUEST_ABI int64_t g_mktime(GuestTm* g) {
    std::tm t = from_guest_tm(g);
    const int64_t r = _mktime64(&t);
    if (r != -1) to_guest_tm(t, g, g->tm_gmtoff, g->tm_zone);
    return r;
}
WINAPK_GUEST_ABI int64_t g_timegm(GuestTm* g) {
    std::tm t = from_guest_tm(g);
    return _mkgmtime64(&t);
}
WINAPK_GUEST_ABI size_t g_strftime(char* s, size_t n, const char* fmt, const GuestTm* g) {
    const std::tm t = from_guest_tm(g);
    return std::strftime(s, n, fmt, &t);
}
// tzset fills Bionic's timezone (seconds west of UTC, a 64-bit long), daylight
// and tzname from the host's zone. Guest code may read them without calling
// tzset, so they are filled once at startup too.
glong g_timezone = 0;
int g_daylight = 0;
char g_tzname_std[64] = "GMT", g_tzname_dst[64] = "GMT";
char* g_tzname[2] = {g_tzname_std, g_tzname_dst};
WINAPK_GUEST_ABI void g_tzset() {
    _tzset();
    // From Win32 rather than the CRT's _get_timezone family, which not every
    // CRT this builds against exports. Bias is in minutes, UTC = local + Bias.
    TIME_ZONE_INFORMATION tzi;
    if (GetTimeZoneInformation(&tzi) == TIME_ZONE_ID_INVALID) return;
    g_timezone = static_cast<glong>(tzi.Bias) * 60;
    g_daylight = tzi.DaylightDate.wMonth != 0;
    WideCharToMultiByte(CP_UTF8, 0, tzi.StandardName, -1, g_tzname_std, sizeof(g_tzname_std), nullptr, nullptr);
    WideCharToMultiByte(CP_UTF8, 0, tzi.DaylightName, -1, g_tzname_dst, sizeof(g_tzname_dst), nullptr, nullptr);
}
const bool g_tz_ready = [] {
    g_tzset();
    return true;
}();
// strptime: Bionic's (libc/tzcode/strptime.c, OpenBSD's with Android's %v
// and %P), in the C locale, which is the only one Bionic's strptime knows.
// After parsing it fills tm_yday, tm_wday, tm_mon and tm_mday from what was
// given, as Bionic does.
struct StrptimeState {
    int century = 1900;  // TM_YEAR_BASE: no %C seen
    int relyear = -1;
    int fields = 0;
};
constexpr int kFieldMon = 1, kFieldMday = 2, kFieldWday = 4, kFieldYday = 8, kFieldYear = 16;
const char* const kDayNames[7] = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
const char* const kDayAbbr[7] = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
const char* const kMonNames[12] = {"January", "February", "March",     "April",   "May",      "June",
                                   "July",    "August",   "September", "October", "November", "December"};
const char* const kMonAbbr[12] = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
const char* const kNaStd[4] = {"EST", "CST", "MST", "PST"};
const char* const kNaDst[4] = {"EDT", "CDT", "MDT", "PDT"};

bool sp_isspace(unsigned char c) { return c == ' ' || (c >= '\t' && c <= '\r'); }
bool sp_isdigit(unsigned char c) { return c >= '0' && c <= '9'; }

bool conv_num(const unsigned char** buf, int* dest, int llim, int ulim) {
    if (!sp_isdigit(**buf)) return false;
    int result = 0, rulim = ulim;
    do {
        result = result * 10 + (*(*buf)++ - '0');
        rulim /= 10;
    } while (result * 10 <= ulim && rulim && sp_isdigit(**buf));
    if (result < llim || result > ulim) return false;
    *dest = result;
    return true;
}
const unsigned char* find_string(const unsigned char* bp, int* tgt, const char* const* names, int n) {
    for (int i = 0; i < n; ++i) {
        const size_t len = std::strlen(names[i]);
        if (_strnicmp(names[i], reinterpret_cast<const char*>(bp), len) == 0) {
            *tgt = i;
            return bp + len;
        }
    }
    return nullptr;
}
int leaps_thru_end_of(int y) { return y >= 0 ? y / 4 - y / 100 + y / 400 : -(leaps_thru_end_of(-(y + 1)) + 1); }

const unsigned char* strptime_in(const unsigned char* bp, const char* fmt, GuestTm* tm, StrptimeState& st) {
    static const char kGmt[] = "GMT", kUtc[] = "UTC";
    while (unsigned char c = static_cast<unsigned char>(*fmt)) {
        int alt = 0;  // 1: %E, 2: %O
        if (sp_isspace(c)) {
            while (sp_isspace(*bp)) ++bp;
            ++fmt;
            continue;
        }
        if ((c = static_cast<unsigned char>(*fmt++)) != '%') {
            if (c != *bp++) return nullptr;
            continue;
        }
        auto legal = [&](int allowed) { return (alt & ~allowed) == 0; };
        auto sub = [&](const char* f) { return (bp = strptime_in(bp, f, tm, st)) != nullptr; };
        int i;
    again:
        switch (c = static_cast<unsigned char>(*fmt++)) {
            case '%':
                if (c != *bp++) return nullptr;
                break;
            case 'E':
                if (!legal(0)) return nullptr;
                alt |= 1;
                goto again;
            case 'O':
                if (!legal(0)) return nullptr;
                alt |= 2;
                goto again;
            case 'c':
                if (!legal(1) || !sub("%a %b %e %H:%M:%S %Y")) return nullptr;
                break;
            case 'D':
                if (!legal(0) || !sub("%m/%d/%y")) return nullptr;
                break;
            case 'F':
                if (!legal(0) || !sub("%Y-%m-%d")) return nullptr;
                break;
            case 'R':
                if (!legal(0) || !sub("%H:%M")) return nullptr;
                break;
            case 'r':
                if (!legal(0) || !sub("%I:%M:%S %p")) return nullptr;
                break;
            case 'T':
                if (!legal(0) || !sub("%H:%M:%S")) return nullptr;
                break;
            case 'v':
                if (!legal(0) || !sub("%e-%b-%Y")) return nullptr;
                break;
            case 'X':
                if (!legal(1) || !sub("%H:%M:%S")) return nullptr;
                break;
            case 'x':
                if (!legal(1) || !sub("%m/%d/%y")) return nullptr;
                break;
            case 'A': case 'a': {
                if (!legal(0)) return nullptr;
                size_t len = 0;
                for (i = 0; i < 7; ++i) {
                    len = std::strlen(kDayNames[i]);
                    if (_strnicmp(kDayNames[i], reinterpret_cast<const char*>(bp), len) == 0) break;
                    len = std::strlen(kDayAbbr[i]);
                    if (_strnicmp(kDayAbbr[i], reinterpret_cast<const char*>(bp), len) == 0) break;
                }
                if (i == 7) return nullptr;
                tm->tm_wday = i;
                bp += len;
                st.fields |= kFieldWday;
                break;
            }
            case 'B': case 'b': case 'h': {
                if (!legal(0)) return nullptr;
                size_t len = 0;
                for (i = 0; i < 12; ++i) {
                    len = std::strlen(kMonNames[i]);
                    if (_strnicmp(kMonNames[i], reinterpret_cast<const char*>(bp), len) == 0) break;
                    len = std::strlen(kMonAbbr[i]);
                    if (_strnicmp(kMonAbbr[i], reinterpret_cast<const char*>(bp), len) == 0) break;
                }
                if (i == 12) return nullptr;
                tm->tm_mon = i;
                bp += len;
                st.fields |= kFieldMon;
                break;
            }
            case 'C':
                if (!legal(1) || !conv_num(&bp, &i, 0, 99)) return nullptr;
                st.century = i * 100;
                break;
            case 'e':
                if (sp_isspace(*bp)) ++bp;
                [[fallthrough]];
            case 'd':
                if (!legal(2) || !conv_num(&bp, &tm->tm_mday, 1, 31)) return nullptr;
                st.fields |= kFieldMday;
                break;
            case 'k':
                if (!legal(0)) return nullptr;
                [[fallthrough]];
            case 'H':
                if (!legal(2) || !conv_num(&bp, &tm->tm_hour, 0, 23)) return nullptr;
                break;
            case 'l':
                if (!legal(0)) return nullptr;
                [[fallthrough]];
            case 'I':
                if (!legal(2) || !conv_num(&bp, &tm->tm_hour, 1, 12)) return nullptr;
                break;
            case 'j':
                if (!legal(0) || !conv_num(&bp, &tm->tm_yday, 1, 366)) return nullptr;
                tm->tm_yday--;
                st.fields |= kFieldYday;
                break;
            case 'M':
                if (!legal(2) || !conv_num(&bp, &tm->tm_min, 0, 59)) return nullptr;
                break;
            case 'm':
                if (!legal(2) || !conv_num(&bp, &tm->tm_mon, 1, 12)) return nullptr;
                tm->tm_mon--;
                st.fields |= kFieldMon;
                break;
            case 'P': case 'p':
                if (!legal(0)) return nullptr;
                if (_strnicmp("AM", reinterpret_cast<const char*>(bp), 2) == 0) {
                    if (tm->tm_hour > 12) return nullptr;
                    if (tm->tm_hour == 12) tm->tm_hour = 0;
                } else if (_strnicmp("PM", reinterpret_cast<const char*>(bp), 2) == 0) {
                    if (tm->tm_hour > 12) return nullptr;
                    if (tm->tm_hour < 12) tm->tm_hour += 12;
                } else {
                    return nullptr;
                }
                bp += 2;
                break;
            case 'S':
                if (!legal(2) || !conv_num(&bp, &tm->tm_sec, 0, 60)) return nullptr;
                break;
            case 's': {  // seconds since the epoch, as local time
                char* end = nullptr;
                errno = 0;
                const long long secs = std::strtoll(reinterpret_cast<const char*>(bp), &end, 10);
                const bool ok = end != reinterpret_cast<const char*>(bp) && secs >= 0 &&
                                !(secs == LLONG_MAX && errno == ERANGE);
                bp = reinterpret_cast<const unsigned char*>(end);
                const int64_t t = secs;
                if (!ok || !g_localtime_r(&t, tm)) return nullptr;
                st.fields = 0xffff;
                break;
            }
            case 'U': case 'W':
                if (!legal(2) || !conv_num(&bp, &i, 0, 53)) return nullptr;
                break;
            case 'w':
                if (!legal(2) || !conv_num(&bp, &tm->tm_wday, 0, 6)) return nullptr;
                st.fields |= kFieldWday;
                break;
            case 'u':
                if (!legal(2) || !conv_num(&bp, &i, 1, 7)) return nullptr;
                tm->tm_wday = i % 7;
                st.fields |= kFieldWday;
                break;
            case 'g':
                if (!conv_num(&bp, &i, 0, 99)) return nullptr;
                break;
            case 'G':
                do ++bp;
                while (sp_isdigit(*bp));
                break;
            case 'V':
                if (!conv_num(&bp, &i, 0, 53)) return nullptr;
                break;
            case 'Y':
                if (!legal(1) || !conv_num(&bp, &i, 0, 9999)) return nullptr;
                st.relyear = -1;
                tm->tm_year = i - 1900;
                st.fields |= kFieldYear;
                break;
            case 'y':
                if (!legal(3) || !conv_num(&bp, &st.relyear, 0, 99)) return nullptr;
                break;
            case 'Z':
                g_tzset();
                if (std::strncmp(reinterpret_cast<const char*>(bp), kGmt, 3) == 0 ||
                    std::strncmp(reinterpret_cast<const char*>(bp), kUtc, 3) == 0) {
                    tm->tm_isdst = 0;
                    tm->tm_gmtoff = 0;
                    tm->tm_zone = bp[0] == 'G' ? kGmt : kUtc;
                    bp += 3;
                } else {
                    const unsigned char* ep = find_string(bp, &i, g_tzname, 2);
                    if (!ep) return nullptr;
                    tm->tm_isdst = i;
                    tm->tm_gmtoff = -g_timezone;
                    tm->tm_zone = g_tzname[i];
                    bp = ep;
                }
                break;
            case 'z': {
                bool neg = false;
                while (sp_isspace(*bp)) ++bp;
                switch (*bp++) {
                    case 'G':
                        if (*bp++ != 'M') return nullptr;
                        [[fallthrough]];
                    case 'U':
                        if (*bp++ != 'T') return nullptr;
                        [[fallthrough]];
                    case 'Z':
                        tm->tm_isdst = 0;
                        tm->tm_gmtoff = 0;
                        tm->tm_zone = kUtc;
                        continue;
                    case '+': break;
                    case '-': neg = true; break;
                    default: {
                        --bp;
                        if (const unsigned char* ep = find_string(bp, &i, kNaStd, 4)) {
                            tm->tm_gmtoff = (-5 - i) * 3600;
                            tm->tm_zone = kNaStd[i];
                            bp = ep;
                            continue;
                        }
                        if (const unsigned char* ep = find_string(bp, &i, kNaDst, 4)) {
                            tm->tm_isdst = 1;
                            tm->tm_gmtoff = (-4 - i) * 3600;
                            tm->tm_zone = kNaDst[i];
                            bp = ep;
                            continue;
                        }
                        return nullptr;
                    }
                }
                if (!sp_isdigit(bp[0]) || !sp_isdigit(bp[1])) return nullptr;
                int offs = ((bp[0] - '0') * 10 + (bp[1] - '0')) * 3600;
                bp += 2;
                if (*bp == ':') ++bp;
                if (sp_isdigit(*bp)) {
                    offs += (*bp++ - '0') * 600;
                    if (!sp_isdigit(*bp)) return nullptr;
                    offs += (*bp++ - '0') * 60;
                }
                tm->tm_isdst = 0;
                tm->tm_gmtoff = neg ? -offs : offs;
                tm->tm_zone = nullptr;
                break;
            }
            case 'n': case 't':
                if (!legal(0)) return nullptr;
                while (sp_isspace(*bp)) ++bp;
                break;
            default:
                return nullptr;
        }
    }
    return bp;
}

WINAPK_GUEST_ABI char* g_strptime(const char* buf, const char* fmt, GuestTm* tm) {
    StrptimeState st;
    const unsigned char* bp = strptime_in(reinterpret_cast<const unsigned char*>(buf), fmt, tm, st);
    if (!bp) return nullptr;
    // %y last, as a %C may come after it.
    if (st.relyear != -1) {
        if (st.century == 1900) tm->tm_year = st.relyear <= 68 ? st.relyear + 100 : st.relyear;
        else tm->tm_year = st.relyear + st.century - 1900;
        st.fields |= kFieldYear;
    }
    if (st.fields & kFieldYear) {
        static const int kMonLengths[2][12] = {{31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31},
                                               {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31}};
        const int year = tm->tm_year + 1900;
        const int* lens = kMonLengths[(year % 4 == 0 && year % 100 != 0) || year % 400 == 0];
        if (!(st.fields & kFieldYday) && (st.fields & kFieldMon) && (st.fields & kFieldMday)) {
            tm->tm_yday = tm->tm_mday - 1;
            for (int i = 0; i < tm->tm_mon; ++i) tm->tm_yday += lens[i];
            st.fields |= kFieldYday;
        }
        if (st.fields & kFieldYday) {
            int days = tm->tm_yday;
            if (!(st.fields & kFieldWday)) {
                // EPOCH_WDAY 4 (Thursday), EPOCH_YEAR 1970, 365 % 7 == 1.
                tm->tm_wday = (4 + ((year - 1970) % 7) * 1 + leaps_thru_end_of(year - 1) - leaps_thru_end_of(1969) +
                               tm->tm_yday) % 7;
                if (tm->tm_wday < 0) tm->tm_wday += 7;
            }
            if (!(st.fields & kFieldMon)) {
                tm->tm_mon = 0;
                while (tm->tm_mon < 12 && days >= lens[tm->tm_mon]) days -= lens[tm->tm_mon++];
            }
            if (!(st.fields & kFieldMday)) tm->tm_mday = days + 1;
        }
    }
    return const_cast<char*>(reinterpret_cast<const char*>(bp));
}

// <netinet/in.h>'s constants: :: and ::1.
unsigned char g_in6addr_any[16] = {};
unsigned char g_in6addr_loopback[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

// ---- memory mapping -----------------------------------------------------------------
// Linux values: PROT_READ 1, WRITE 2, EXEC 4; MAP_SHARED 1, PRIVATE 2, FIXED
// 0x10, ANONYMOUS 0x20.
DWORD win_protect(int prot) {
    const bool r = prot & 1, w = prot & 2, x = prot & 4;
    if (x) return w ? PAGE_EXECUTE_READWRITE : r ? PAGE_EXECUTE_READ : PAGE_EXECUTE;
    if (w) return PAGE_READWRITE;
    if (r) return PAGE_READONLY;
    return PAGE_NOACCESS;
}

std::mutex g_maps_lock;
std::map<uintptr_t, size_t> g_maps;  // reservations made here, by base

// MAP_FIXED over address space the process already holds: a guest reserves a
// range with PROT_NONE and maps pieces of it in place. The Dart VM does this
// for every page of its compressed-pointer heap (a 4 GB-aligned reservation;
// Flutter apps: City Car, LocalSend). VirtualAlloc cannot reserve what is
// reserved, so the pages are committed, or for PROT_NONE decommitted, where
// they are; Linux hands out fresh zero pages there, so committed ones are
// decommitted first. Region by region, as a range may span allocations. False
// when any of the range is free or not private memory: the caller maps it
// anew.
bool map_in_place(void* addr, size_t len, bool commit) {
    uint8_t* const lo = static_cast<uint8_t*>(addr);
    uint8_t* const hi = lo + len;
    for (uint8_t* p = lo; p < hi;) {
        MEMORY_BASIC_INFORMATION mbi;
        if (!VirtualQuery(p, &mbi, sizeof mbi) || mbi.State == MEM_FREE || mbi.Type != MEM_PRIVATE) return false;
        p = static_cast<uint8_t*>(mbi.BaseAddress) + mbi.RegionSize;
    }
    // Region by region: VirtualFree and VirtualAlloc work within one allocation.
    for (uint8_t* p = lo; p < hi;) {
        MEMORY_BASIC_INFORMATION mbi;
        VirtualQuery(p, &mbi, sizeof mbi);
        uint8_t* const end = std::min(hi, static_cast<uint8_t*>(mbi.BaseAddress) + mbi.RegionSize);
        const size_t n = static_cast<size_t>(end - p);
        if (mbi.State == MEM_COMMIT) VirtualFree(p, n, MEM_DECOMMIT);
        if (commit && !VirtualAlloc(p, n, MEM_COMMIT, PAGE_READWRITE)) return false;
        p = end;
    }
    return true;
}

// MAP_SHARED mappings of a file (and read-only private ones): views of the
// file itself, by the address the guest got. A private copy (the fallback below) never reaches the file, and
// two mappings of one file never see each other's writes: Realm (Bit) writes
// its lock file's header through one mapping and reads `init_complete` back
// through another, and spun on 0 for ever; MMKV and SQLite's shared memory
// keep their data in such mappings too.
struct SharedView {
    void* view;  // what MapViewOfFile returned: the offset rounded down to the allocation granularity
    size_t len;  // what the guest asked for, from the address it got
};
std::map<uintptr_t, SharedView> g_views;  // under g_maps_lock

// A view of `len` bytes of the file `fd` has open, from `off`, or null to fall
// back to a private copy: an exec or MAP_FIXED mapping, a descriptor without
// a file, a range past the file's last page (Linux maps it and faults on
// access; a section cannot be larger than the file without growing it), an
// empty file (no section), or a read-only file asked for writing.
void* map_shared_view(size_t len, int prot, int fd, int64_t off) {
    if ((prot & 4) || off < 0 || fd_object(fd)) return nullptr;
    const intptr_t h = _get_osfhandle(fd);
    if (h == -1) return nullptr;
    const HANDLE file = reinterpret_cast<HANDLE>(h);
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(file, &size) || size.QuadPart == 0) return nullptr;
    const uint64_t last_page = (static_cast<uint64_t>(size.QuadPart) + 4095) & ~uint64_t{4095};
    if (static_cast<uint64_t>(off) + len > last_page) return nullptr;
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    const uint64_t base = static_cast<uint64_t>(off) & ~(static_cast<uint64_t>(si.dwAllocationGranularity) - 1);
    const size_t delta = static_cast<size_t>(static_cast<uint64_t>(off) - base);
    const bool write = prot & 2;
    // Size 0: the section is the file as it is now, so the file does not grow.
    const HANDLE section = CreateFileMappingW(file, nullptr, write ? PAGE_READWRITE : PAGE_READONLY, 0, 0, nullptr);
    if (!section) return nullptr;
    // To the section's end when the range reaches into the last page: a view
    // covers whole pages, so its tail past the file reads as zeros, as on Linux.
    const bool to_end = static_cast<uint64_t>(off) + len > static_cast<uint64_t>(size.QuadPart);
    void* view = MapViewOfFile(section, write ? FILE_MAP_WRITE : FILE_MAP_READ, static_cast<DWORD>(base >> 32),
                               static_cast<DWORD>(base), to_end ? 0 : delta + len);
    CloseHandle(section);  // the view keeps the section alive
    if (!view) return nullptr;
    if (prot == 0) {
        DWORD old;
        VirtualProtect(view, delta + len, PAGE_NOACCESS, &old);
    }
    void* p = static_cast<char*>(view) + delta;
    std::lock_guard<std::mutex> hold(g_maps_lock);
    g_views[reinterpret_cast<uintptr_t>(p)] = SharedView{view, len};
    return p;
}

// A writable MAP_SHARED mapping that could not be a view (above: a file still empty, or a range past its
// end, both of which Linux maps) is a private copy, and what the guest writes there has to reach the file
// some other way: a library that creates a file, maps its header and only then grows it wrote the header
// into the copy, and the file kept none of it. Play services' search index (Icing) is laid out so: its
// files came back with a zero checksum and an empty trie header, which it takes for corruption it cannot
// repair, and the process aborted at every start. The copy is written back, page by page where it
// changed, when the guest syncs or unmaps it, up to the file's size at that moment, as the kernel drops
// what lies past the end. Pages the guest did not touch are left to whatever else wrote the file (pwrite
// into a mapped range). Until then another reader of the file does not see the writes; a view does.
struct SharedCopy {
    HANDLE file;        // our own handle: the guest may close its descriptor right after mmap
    int64_t off;
    size_t len;
    char* shadow;       // the bytes as last read from or written to the file
};
std::map<uintptr_t, SharedCopy> g_copies;  // under g_maps_lock
constexpr size_t kSharedCopyMax = size_t{64} << 20;  // the shadow doubles the memory: small mappings only

void remember_shared_copy(void* p, size_t len, int fd, int64_t off) {
    if (len == 0 || len > kSharedCopyMax || off < 0 || fd_object(fd)) return;
    const intptr_t h = _get_osfhandle(fd);
    if (h == -1) return;
    HANDLE file = nullptr;
    if (!DuplicateHandle(GetCurrentProcess(), reinterpret_cast<HANDLE>(h), GetCurrentProcess(), &file, 0, FALSE,
                         DUPLICATE_SAME_ACCESS)) {
        return;
    }
    char* shadow = static_cast<char*>(std::malloc(len));
    if (!shadow) {
        CloseHandle(file);
        return;
    }
    std::memcpy(shadow, p, len);
    std::lock_guard<std::mutex> hold(g_maps_lock);
    g_copies[reinterpret_cast<uintptr_t>(p)] = SharedCopy{file, off, len, shadow};
}

// Writes the changed pages of `c` within [from, from + n) of the copy to the file. Under g_maps_lock.
void flush_shared_copy(uintptr_t base, SharedCopy& c, size_t from, size_t n) {
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(c.file, &size)) return;
    const int64_t in_file = size.QuadPart - c.off;  // how much of the mapping the file holds now
    if (in_file <= 0) return;
    const size_t end = std::min({c.len, from + n, static_cast<size_t>(in_file)});
    const char* mem = reinterpret_cast<const char*>(base);
    for (size_t at = from & ~size_t{4095}; at < end; at += 4096) {
        const size_t count = std::min<size_t>(4096, end - at);
        if (std::memcmp(mem + at, c.shadow + at, count) == 0) continue;
        OVERLAPPED where{};
        const uint64_t pos = static_cast<uint64_t>(c.off) + at;
        where.Offset = static_cast<DWORD>(pos);
        where.OffsetHigh = static_cast<DWORD>(pos >> 32);
        DWORD wrote = 0;
        if (WriteFile(c.file, mem + at, static_cast<DWORD>(count), &wrote, &where) && wrote == count) {
            std::memcpy(c.shadow + at, mem + at, count);
        }
    }
}

// MAP_FIXED of a file into a reservation this file made (PROT_NONE anonymous):
// Realm reserves a 64 MB section and maps its database at the start of it,
// then grows the mapping in place. A view can only go where nothing is
// allocated, so a reservation nothing in which is committed yet is released
// and made again around the view: before it, and after it from the next
// allocation-granularity boundary. Null (the private copy) for anything else:
// an address or offset off the allocation granularity (Realm then makes a new
// mapping instead of growing this one), memory in use, or a file range a
// view cannot cover (see map_shared_view).
void* map_fixed_view(void* addr, size_t len, int prot, int fd, int64_t off) {
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    const uintptr_t gran = si.dwAllocationGranularity;
    const auto a = reinterpret_cast<uintptr_t>(addr);
    if ((prot & 4) || off < 0 || (a % gran) || (static_cast<uint64_t>(off) % gran) || fd_object(fd)) return nullptr;
    const intptr_t h = _get_osfhandle(fd);
    if (h == -1) return nullptr;
    const HANDLE file = reinterpret_cast<HANDLE>(h);
    LARGE_INTEGER size{};
    if (!GetFileSizeEx(file, &size) || size.QuadPart == 0) return nullptr;
    const uint64_t last_page = (static_cast<uint64_t>(size.QuadPart) + 4095) & ~uint64_t{4095};
    if (static_cast<uint64_t>(off) + len > last_page) return nullptr;
    std::lock_guard<std::mutex> hold(g_maps_lock);
    auto it = g_maps.upper_bound(a);
    if (it == g_maps.begin()) return nullptr;
    --it;
    const uintptr_t base = it->first, end = it->first + it->second;
    if (a + len > end) return nullptr;
    MEMORY_BASIC_INFORMATION mbi{};
    if (!VirtualQuery(reinterpret_cast<void*>(base), &mbi, sizeof mbi) || mbi.State != MEM_RESERVE ||
        reinterpret_cast<uintptr_t>(mbi.AllocationBase) != base ||
        reinterpret_cast<uintptr_t>(mbi.BaseAddress) + mbi.RegionSize < end) {
        return nullptr;  // something in it is committed (or it is not one allocation)
    }
    const bool write = prot & 2;
    const HANDLE section = CreateFileMappingW(file, nullptr, write ? PAGE_READWRITE : PAGE_READONLY, 0, 0, nullptr);
    if (!section) return nullptr;
    const bool to_end = static_cast<uint64_t>(off) + len > static_cast<uint64_t>(size.QuadPart);
    VirtualFree(reinterpret_cast<void*>(base), 0, MEM_RELEASE);
    g_maps.erase(it);
    if (a > base && VirtualAlloc(reinterpret_cast<void*>(base), a - base, MEM_RESERVE, PAGE_NOACCESS)) {
        g_maps[base] = a - base;
    }
    void* view = MapViewOfFileEx(section, write ? FILE_MAP_WRITE : FILE_MAP_READ,
                                 static_cast<DWORD>(static_cast<uint64_t>(off) >> 32), static_cast<DWORD>(off),
                                 to_end ? 0 : len, addr);
    CloseHandle(section);
    uintptr_t tail = a;
    if (view) {
        MEMORY_BASIC_INFORMATION v{};
        VirtualQuery(view, &v, sizeof v);
        tail = (a + v.RegionSize + gran - 1) / gran * gran;
        if (prot == 0) {
            DWORD old;
            VirtualProtect(view, len, PAGE_NOACCESS, &old);
        }
        g_views[a] = SharedView{view, len};
    }
    if (tail < end && VirtualAlloc(reinterpret_cast<void*>(tail), end - tail, MEM_RESERVE, PAGE_NOACCESS)) {
        g_maps[tail] = end - tail;
    }
    return view;
}

// True when [addr, addr + len) touches a shared file view.
bool in_shared_view(const void* addr, size_t len) {
    const auto a = reinterpret_cast<uintptr_t>(addr);
    std::lock_guard<std::mutex> hold(g_maps_lock);
    // Views do not overlap: only the last one starting before the range's end can reach into it.
    auto it = g_views.lower_bound(a + len);
    if (it == g_views.begin()) return false;
    --it;
    return it->first + it->second.len > a;
}

WINAPK_GUEST_ABI void* g_mmap(void* addr, size_t len, int prot, int flags, int fd, int64_t off) {
    void* const kFailed = reinterpret_cast<void*>(-1);
    if (len == 0) {
        *guest_errno() = kEINVAL;
        return kFailed;
    }
    // MAP_FIXED maps at addr or fails; a mapping somewhere else would leave
    // the guest writing to the address it asked for.
    const bool fixed = flags & 0x10;
    void* want = fixed ? addr : nullptr;
    const bool anonymous = (flags & 0x20) || fd < 0;
    // MAP_SHARED, and MAP_PRIVATE read-only: on Linux a private page reads the
    // file's current bytes until the process writes it, so a read-only private
    // mapping sees later writes to the file too. Realm reads each commit
    // through a MAP_SHARED | MAP_FIXED mapping of its database in a section it
    // reserved; a copy taken at mmap kept the new, empty file ("Invalid top
    // array size (ref: 128, array size: 0)").
    if (((flags & 3) == 1 || ((flags & 3) == 2 && !(prot & 2))) && !anonymous) {
        if (void* v = fixed ? map_fixed_view(addr, len, prot, fd, off) : map_shared_view(len, prot, fd, off)) return v;
    }
    void* p = nullptr;
    if (fixed && map_in_place(addr, len, prot != 0 || !anonymous)) {
        if (prot == 0 && anonymous) return addr;
        p = addr;
    } else if (prot == 0 && anonymous) {
        // An address-space reservation (allocators reserve gigabytes this way
        // and commit pieces with mprotect or MAP_FIXED). Only reserving keeps
        // it off the commit charge, as Linux's overcommit does.
        void* r = VirtualAlloc(want, len, MEM_RESERVE, PAGE_NOACCESS);
        if (!r) {
            *guest_errno() = kENOMEM;
            return kFailed;
        }
        std::lock_guard<std::mutex> hold(g_maps_lock);
        g_maps[reinterpret_cast<uintptr_t>(r)] = len;
        return r;
    } else {
        // The pages are committed read-write first so a file's contents can
        // be copied in; the requested protection follows.
        p = VirtualAlloc(want, len, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (!p) {
            *guest_errno() = kENOMEM;
            return kFailed;
        }
        std::lock_guard<std::mutex> hold(g_maps_lock);
        g_maps[reinterpret_cast<uintptr_t>(p)] = len;
    }
    if (!(flags & 0x20) && fd >= 0) {
        // A file mapping: a private copy of the file's bytes (MAP_PRIVATE, or a
        // MAP_SHARED one map_shared_view could not make a view of): writes are
        // not carried back to the file.
        size_t done = 0;
        while (done < len) {
            const int64_t r = fd_pread(fd, static_cast<char*>(p) + done, len - done, off + static_cast<int64_t>(done));
            if (r <= 0) break;
            done += static_cast<size_t>(r);
        }
        if ((flags & 3) == 1 && (prot & 2)) remember_shared_copy(p, len, fd, off);
    }
    DWORD old;
    VirtualProtect(p, len, win_protect(prot), &old);
    return p;
}
WINAPK_GUEST_ABI int g_munmap(void* addr, size_t len) {
    std::lock_guard<std::mutex> hold(g_maps_lock);
    const auto a = reinterpret_cast<uintptr_t>(addr);
    if (auto v = g_views.find(a); v != g_views.end()) {
        // A view goes as a whole; unmapping only its head leaves it all mapped
        // (a view cannot be cut), which only keeps memory the guest gave up.
        if (len >= v->second.len) {
            UnmapViewOfFile(v->second.view);
            g_views.erase(v);
            // The rest of a reservation the view was mapped into (map_fixed_view) goes with it.
            for (auto r = g_maps.lower_bound(a); r != g_maps.end() && r->first < a + len;) {
                VirtualFree(reinterpret_cast<void*>(r->first), 0, MEM_RELEASE);
                r = g_maps.erase(r);
            }
        }
        return 0;
    }
    if (auto c = g_copies.find(a); c != g_copies.end()) {
        flush_shared_copy(a, c->second, 0, c->second.len);
        if (len >= c->second.len) {
            CloseHandle(c->second.file);
            std::free(c->second.shadow);
            g_copies.erase(c);
        }
    }
    auto it = g_maps.find(reinterpret_cast<uintptr_t>(addr));
    if (it != g_maps.end() && len >= it->second) {
        VirtualFree(addr, 0, MEM_RELEASE);
        g_maps.erase(it);
        return 0;
    }
    // Part of a mapping: decommit those pages, keep the reservation.
    VirtualFree(addr, len, MEM_DECOMMIT);
    return 0;
}
WINAPK_GUEST_ABI int g_mprotect(void* addr, size_t len, int prot) {
    DWORD old;
    // Opening up part of a reservation (see g_mmap) commits it; committing
    // pages that already are is allowed and only sets the protection.
    if (prot != 0 && VirtualAlloc(addr, len, MEM_COMMIT, win_protect(prot))) return 0;
    if (VirtualProtect(addr, len, win_protect(prot), &old)) return 0;
    set_errno_from_win32(GetLastError());
    return -1;
}
WINAPK_GUEST_ABI int g_madvise(void* addr, size_t len, int advice) {
    // MADV_DONTNEED: private pages read back as zeros; a shared file view
    // reads the file again, which it always does.
    if (advice == 4 && !in_shared_view(addr, len)) {
        MEMORY_BASIC_INFORMATION mbi;
        if (VirtualQuery(addr, &mbi, sizeof(mbi)) && (mbi.Protect & (PAGE_READWRITE | PAGE_EXECUTE_READWRITE))) {
            std::memset(addr, 0, len);
        }
    }
    return 0;
}
// A shared view's changes are the file's already (the system cache); msync
// starts writing them out. Private copies have nothing to write.
WINAPK_GUEST_ABI int g_msync(void* addr, size_t len, int) {
    if (in_shared_view(addr, len)) {
        FlushViewOfFile(addr, len);
        return 0;
    }
    // A copy that stands in for a shared mapping (remember_shared_copy): this is when it reaches the file.
    std::lock_guard<std::mutex> hold(g_maps_lock);
    const auto a = reinterpret_cast<uintptr_t>(addr);
    auto c = g_copies.upper_bound(a);
    if (c != g_copies.begin()) {
        --c;
        if (a < c->first + c->second.len) flush_shared_copy(c->first, c->second, a - c->first, len);
    }
    return 0;
}
WINAPK_GUEST_ABI int g_mlock(const void*, size_t) { return 0; }
WINAPK_GUEST_ABI int g_munlock(const void*, size_t) { return 0; }

// ---- system properties ---------------------------------------------------------------
std::mutex g_props_lock;
std::map<std::string, std::string>& props() {
    // What a device has before the framework pushes its own values.
    static std::map<std::string, std::string> m{
        {"ro.build.version.sdk", "29"},
        {"ro.build.version.release", "10"},
        {"ro.product.cpu.abi", "arm64-v8a"},
        {"ro.product.cpu.abilist", "arm64-v8a"},
        {"ro.product.cpu.abilist64", "arm64-v8a"},
        {"ro.product.cpu.abilist32", ""},
        {"ro.kernel.qemu", "0"},
        {"ro.debuggable", "0"},
        {"ro.secure", "1"},
    };
    return m;
}

constexpr size_t kPropValueMax = 92;  // PROP_VALUE_MAX

// __system_property_get: the value's length, 0 (and "") when unset.
WINAPK_GUEST_ABI int g_property_get(const char* name, char* value) {
    std::lock_guard<std::mutex> hold(g_props_lock);
    auto it = props().find(name ? name : "");
    if (it == props().end()) {
        value[0] = '\0';
        return 0;
    }
    const size_t n = it->second.size() < kPropValueMax - 1 ? it->second.size() : kPropValueMax - 1;
    std::memcpy(value, it->second.data(), n);
    value[n] = '\0';
    return static_cast<int>(n);
}
// __system_property_find hands out an opaque prop_info: here the map entry.
WINAPK_GUEST_ABI const void* g_property_find(const char* name) {
    std::lock_guard<std::mutex> hold(g_props_lock);
    auto it = props().find(name ? name : "");
    return it == props().end() ? nullptr : &*it;
}
WINAPK_GUEST_ABI int g_property_read(const void* pi, char* name, char* value) {
    const auto* e = static_cast<const std::pair<const std::string, std::string>*>(pi);
    if (name) {
        std::strncpy(name, e->first.c_str(), 31);
        name[31] = '\0';
    }
    std::strncpy(value, e->second.c_str(), kPropValueMax - 1);
    value[kPropValueMax - 1] = '\0';
    return static_cast<int>(std::strlen(value));
}
using PropCallback = void(WINAPK_GUEST_ABI*)(void*, const char*, const char*, uint32_t);
WINAPK_GUEST_ABI void g_property_read_callback(const void* pi, PropCallback cb, void* cookie) {
    const auto* e = static_cast<const std::pair<const std::string, std::string>*>(pi);
    cb(cookie, e->first.c_str(), e->second.c_str(), 0);
}
WINAPK_GUEST_ABI int g_property_set(const char* name, const char* value) {
    property_set(name, value);
    return 0;
}
WINAPK_GUEST_ABI int g_get_device_api_level() {
    char v[kPropValueMax];
    return g_property_get("ro.build.version.sdk", v) ? std::atoi(v) : -1;
}

// ---- environment -------------------------------------------------------------------------
std::mutex g_env_lock;
std::map<std::string, std::string>& env() {
    // A zygote-forked app's environment (upstream's bionic_shim.cpp seeds the
    // full device list; these are the variables native code reads).
    static std::map<std::string, std::string> m{
        {"PATH", "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:"
                 "/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"},
        {"ANDROID_ROOT", "/system"},
        {"ANDROID_DATA", "/data"},
        {"ANDROID_STORAGE", "/storage"},
        {"EXTERNAL_STORAGE", "/sdcard"},
        {"ANDROID_ART_ROOT", "/apex/com.android.art"},
        {"ANDROID_I18N_ROOT", "/apex/com.android.i18n"},
        {"ANDROID_TZDATA_ROOT", "/apex/com.android.tzdata"},
    };
    return m;
}
// getenv's results must stay valid; values are interned and never freed.
const char* intern(const std::string& s) {
    static std::mutex lock;
    static std::map<std::string, const char*> pool;
    std::lock_guard<std::mutex> hold(lock);
    auto it = pool.find(s);
    if (it != pool.end()) return it->second;
    char* p = static_cast<char*>(std::malloc(s.size() + 1));
    std::memcpy(p, s.c_str(), s.size() + 1);
    pool.emplace(s, p);
    return p;
}
// The guest's `environ`: a data symbol, so guest code reads the array behind
// it directly (SDL walks it). Rebuilt on every change, under g_env_lock; an
// old array is leaked, since another thread may still be walking it.
char** g_environ = nullptr;
void rebuild_environ() {
    auto& e = env();
    char** a = static_cast<char**>(std::calloc(e.size() + 1, sizeof(char*)));
    size_t i = 0;
    for (const auto& [k, v] : e) a[i++] = const_cast<char*>(intern(k + "=" + v));
    g_environ = a;
}
const bool g_environ_ready = [] {
    std::lock_guard<std::mutex> hold(g_env_lock);
    rebuild_environ();
    return true;
}();

WINAPK_GUEST_ABI char* g_getenv(const char* name) {
    const char* v = winapk::bionic_getenv(name);
    return const_cast<char*>(v);
}
WINAPK_GUEST_ABI int g_setenv(const char* name, const char* value, int overwrite) {
    const int r = winapk::bionic_setenv(name, value, overwrite);
    if (r < 0) {
        *guest_errno() = -r;
        return -1;
    }
    return 0;
}
WINAPK_GUEST_ABI int g_unsetenv(const char* name) {
    const int r = winapk::bionic_unsetenv(name);
    if (r < 0) {
        *guest_errno() = -r;
        return -1;
    }
    return 0;
}
// putenv("NAME=value"): Bionic's rejects a string with no name or no '='.
// The pair is copied into the store, as setenv's is; later writes through the
// caller's string do not reach it. Gecko's libmozglue wraps putenv, setenv,
// unsetenv and clearenv and looks each real one up with dlsym(RTLD_NEXT) on
// its first call: one that is missing is a deliberate crash there.
WINAPK_GUEST_ABI int g_putenv(char* str) {
    const char* eq = str ? std::strchr(str, '=') : nullptr;
    if (!eq || eq == str) {
        *guest_errno() = kEINVAL;
        return -1;
    }
    return g_setenv(std::string(str, static_cast<size_t>(eq - str)).c_str(), eq + 1, 1);
}
WINAPK_GUEST_ABI int g_clearenv() {
    std::lock_guard<std::mutex> hold(g_env_lock);
    env().clear();
    rebuild_environ();
    return 0;
}

// ---- process identity and limits ------------------------------------------------------------
WINAPK_GUEST_ABI int g_getpid() { return static_cast<int>(GetCurrentProcessId()); }
WINAPK_GUEST_ABI int g_getppid() { return 1; }
// An app's uid: AID_APP_START (10000) and up; the same for every id here.
WINAPK_GUEST_ABI uint32_t g_getuid() { return 10100; }
// kill(getpid()) and the process-group forms land on the calling thread, as
// a process-directed signal lands on whichever thread does not block it.
// Other processes are not the app's to signal here.
WINAPK_GUEST_ABI int g_kill(int pid, int sig) {
    if (pid != g_getpid() && pid != 0 && pid != -1) return *guest_errno() = kESRCH, -1;
    const int err = signal_send(0, sig);
    if (err) return *guest_errno() = err, -1;
    return 0;
}
WINAPK_GUEST_ABI int g_tgkill(int tgid, int tid, int sig) {
    if (tgid != g_getpid() && tgid != -1) return *guest_errno() = kESRCH, -1;
    const int err = signal_send(static_cast<uint32_t>(tid), sig);
    if (err) return *guest_errno() = err, -1;
    return 0;
}
WINAPK_GUEST_ABI int g_tkill(int tid, int sig) { return g_tgkill(-1, tid, sig); }

// fork, vfork and waitpid. Windows cannot copy a running process, and an app
// here has no other process it could have started, so a fork fails as a
// kernel without fork(2) fails it: -1 with ENOSYS. (The stub's 0 told the
// caller it was the child, which then ran its exec path and _exit'ed the
// app.) Nothing is ever a child of the app, so waitpid has nothing to wait
// for: ECHILD, as on Linux for a process with no children.
//
// TODO(child processes): an app that runs part of itself in processes it
// starts (Gecko's content and GPU processes: Fennec, issue #117; a terminal's
// shell) cannot work until guest code can start a second runtime process.
// Until then the failure is said once in the app's log, so a start that ends
// here does not look like a crash of ours.
WINAPK_GUEST_ABI int g_fork() {
    static std::atomic<bool> said{false};
    if (!said.exchange(true)) {
        std::fprintf(stderr, "[win.apk] fork(): the app tried to start a child process, which Win.apk does not "
                             "support yet; the call fails with ENOSYS\n");
        std::fflush(stderr);
    }
    return *guest_errno() = kENOSYS, -1;
}
WINAPK_GUEST_ABI int g_waitpid(int, int*, int) { return *guest_errno() = kECHILD, -1; }
// The rest of the family, for the same reasons. Missing, each answered 0 through the loader's fallback:
// posix_spawn "started" a process with whatever was in *pid, wait4 "reaped" process 0, and clone, like
// fork, told its caller it was the child.
void note_no_child(const char* call) {
    static std::atomic<bool> said{false};
    say_once(said, call, "the app tried to start a child process, which Win.apk does not support yet; the call "
                         "fails with ENOSYS");
}
// execve and its relatives return only to fail. (No argument is read, so one function serves the
// variadic execl forms too.)
WINAPK_GUEST_ABI int g_exec() {
    note_no_child("exec()");
    return *guest_errno() = kENOSYS, -1;
}
// posix_spawn and posix_spawnp answer the error itself.
WINAPK_GUEST_ABI int g_posix_spawn() {
    note_no_child("posix_spawn()");
    return kENOSYS;
}
WINAPK_GUEST_ABI int g_clone() {
    note_no_child("clone()");
    return *guest_errno() = kENOSYS, -1;
}
WINAPK_GUEST_ABI int g_wait() { return *guest_errno() = kECHILD, -1; }  // wait, wait3, wait4, waitid

// True when `pid` names this process to the scheduling calls: 0, its pid, or
// one of its threads (Linux's per-thread ids; here Windows thread ids).
bool is_own_task(int pid) {
    if (pid == 0 || pid == g_getpid()) return true;
    if (pid < 0) return false;
    const HANDLE t = OpenThread(THREAD_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid));
    if (!t) return false;
    const bool own = GetProcessIdOfThread(t) == GetCurrentProcessId();
    CloseHandle(t);
    return own;
}

// sched_getaffinity(2) as the kernel answers it: the CPUs this process may
// run on (Windows' process affinity, which every thread starts with; numbered
// across processor groups), in a mask of nr_cpu_ids bits rounded up to whole
// longs. Returns the bytes written, or -1 with errno set: EINVAL for a buffer
// that is not a whole number of longs or too small for every CPU, ESRCH for a
// task that is not this process.
int64_t affinity_syscall(int pid, size_t size, void* set) {
    const DWORD cpus = GetActiveProcessorCount(ALL_PROCESSOR_GROUPS);
    const size_t mask_size = (cpus + 63) / 64 * 8;
    if (size * 8 < cpus || (size & 7)) return *guest_errno() = kEINVAL, -1;
    if (!is_own_task(pid)) return *guest_errno() = kESRCH, -1;
    auto* bytes = static_cast<uint8_t*>(set);
    std::memset(bytes, 0, mask_size);
    auto set_cpu = [&](DWORD cpu) {
        if (cpu < cpus) bytes[cpu / 8] |= static_cast<uint8_t>(1u << (cpu % 8));
    };
    // CPU numbers continue from group to group, as Linux numbers them.
    auto group_base = [](WORD group) {
        DWORD base = 0;
        for (WORD g = 0; g < group; ++g) base += GetActiveProcessorCount(g);
        return base;
    };
    USHORT groups[64];
    USHORT ngroups = 64;
    DWORD_PTR process_mask = 0, system_mask = 0;
    if (GetProcessGroupAffinity(GetCurrentProcess(), &ngroups, groups) && ngroups > 1) {
        // Spread over several groups: every processor of each.
        for (USHORT i = 0; i < ngroups; ++i) {
            const DWORD base = group_base(groups[i]);
            for (DWORD c = 0; c < GetActiveProcessorCount(groups[i]); ++c) set_cpu(base + c);
        }
    } else if (GetProcessAffinityMask(GetCurrentProcess(), &process_mask, &system_mask)) {
        const DWORD base = ngroups == 1 ? group_base(groups[0]) : 0;
        for (DWORD c = 0; c < 64; ++c) {
            if ((process_mask >> c) & 1) set_cpu(base + c);
        }
    } else {
        for (DWORD c = 0; c < cpus; ++c) set_cpu(c);
    }
    return static_cast<int64_t>(mask_size < size ? mask_size : size);
}
// Bionic's wrapper: the syscall, then the rest of the caller's set cleared.
WINAPK_GUEST_ABI int g_sched_getaffinity(int pid, size_t size, void* set) {
    const int64_t n = affinity_syscall(pid, size, set);
    if (n < 0) return -1;
    std::memset(static_cast<uint8_t*>(set) + n, 0, size - static_cast<size_t>(n));
    return 0;
}

// ATrace (libandroid's <android/trace.h>): systrace markers. Nothing records
// a trace here, which is a device with tracing off: isEnabled is false and
// the markers do nothing.
WINAPK_GUEST_ABI bool g_atrace_is_enabled() { return false; }
WINAPK_GUEST_ABI void g_atrace_begin_section(const char*) {}
WINAPK_GUEST_ABI void g_atrace_end_section() {}
WINAPK_GUEST_ABI void g_atrace_async_section(const char*, int32_t) {}
WINAPK_GUEST_ABI void g_atrace_set_counter(const char*, int64_t) {}

struct GuestRlimit {
    uint64_t cur, max;
};
// RLIMIT_STACK 3, RLIMIT_NOFILE 7; anything else is unlimited.
WINAPK_GUEST_ABI int g_getrlimit(int resource, GuestRlimit* r) {
    const uint64_t kInfinity = ~uint64_t{0};
    r->cur = r->max = kInfinity;
    if (resource == 3) r->cur = r->max = 8 * 1024 * 1024;
    if (resource == 7) r->cur = r->max = 8192;
    return 0;
}
WINAPK_GUEST_ABI int g_setrlimit(int, const GuestRlimit*) { return 0; }
WINAPK_GUEST_ABI int g_prlimit(int, int resource, const GuestRlimit*, GuestRlimit* old) {
    if (old) g_getrlimit(resource, old);
    return 0;
}

// sysconf: the values Bionic answers on an arm64 device.
WINAPK_GUEST_ABI glong g_sysconf(int name) {
    SYSTEM_INFO si;
    GetSystemInfo(&si);
    switch (name) {
        case 0x27: return 4096;                                // _SC_PAGESIZE
        case 0x60: case 0x61: return si.dwNumberOfProcessors;  // _SC_NPROCESSORS_CONF/ONLN
        case 0x6: return 100;                                  // _SC_CLK_TCK
        case 0x5: return 8192;                                 // _SC_OPEN_MAX
        case 0x62: case 0x63: {                                // _SC_PHYS_PAGES, _SC_AVPHYS_PAGES
            MEMORYSTATUSEX ms{};
            ms.dwLength = sizeof(ms);
            GlobalMemoryStatusEx(&ms);
            return static_cast<glong>((name == 0x62 ? ms.ullTotalPhys : ms.ullAvailPhys) / 4096);
        }
        default: *guest_errno() = kEINVAL; return -1;
    }
}
WINAPK_GUEST_ABI int g_getpagesize() { return 4096; }

// uname: what an arm64 Android device reports (upstream reports the same
// release, so Java's os.version agrees).
struct GuestUtsname {
    char sysname[65], nodename[65], release[65], version[65], machine[65], domainname[65];
};
WINAPK_GUEST_ABI int g_uname(GuestUtsname* u) {
    std::memset(u, 0, sizeof(*u));
    std::strcpy(u->sysname, "Linux");
    std::strcpy(u->nodename, "localhost");
    std::strcpy(u->release, "5.10.0");
    std::strcpy(u->version, "#1 SMP PREEMPT");
    std::strcpy(u->machine, "aarch64");
    std::strcpy(u->domainname, "(none)");
    return 0;
}
// getauxval: AT_PAGESZ 6, AT_HWCAP 16; the rest is 0. Libraries pick their
// NEON, AES, SHA and CRC32 paths from AT_HWCAP, so an empty mask sends them to
// the slow generic code. Bits are Linux's arm64 HWCAP_*.
static uint64_t arm64_hwcap() {
    uint64_t caps = (1u << 0) | (1u << 1);  // FP, ASIMD: every ARMv8-A core
    if (IsProcessorFeaturePresent(PF_ARM_V8_CRYPTO_INSTRUCTIONS_AVAILABLE))
        caps |= (1u << 3) | (1u << 4) | (1u << 5) | (1u << 6);  // AES, PMULL, SHA1, SHA2
    if (IsProcessorFeaturePresent(PF_ARM_V8_CRC32_INSTRUCTIONS_AVAILABLE)) caps |= 1u << 7;
    if (IsProcessorFeaturePresent(PF_ARM_V81_ATOMIC_INSTRUCTIONS_AVAILABLE)) caps |= 1u << 8;
    return caps;
}
WINAPK_GUEST_ABI uint64_t g_getauxval(uint64_t type) {
    static const uint64_t hwcap = arm64_hwcap();
    if (type == 6) return 4096;
    if (type == 16) return hwcap;
    return 0;
}

// ---- randomness ---------------------------------------------------------------------------
WINAPK_GUEST_ABI void g_arc4random_buf(void* buf, size_t n) {
    BCryptGenRandom(nullptr, static_cast<PUCHAR>(buf), static_cast<ULONG>(n), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
}
WINAPK_GUEST_ABI uint32_t g_arc4random() {
    uint32_t v;
    g_arc4random_buf(&v, sizeof(v));
    return v;
}
WINAPK_GUEST_ABI uint32_t g_arc4random_uniform(uint32_t bound) {
    if (bound < 2) return 0;
    const uint32_t floor = (0u - bound) % bound;  // reject the biased low range
    for (;;) {
        const uint32_t v = g_arc4random();
        if (v >= floor) return v % bound;
    }
}
WINAPK_GUEST_ABI int64_t g_getrandom(void* buf, size_t n, unsigned) {
    g_arc4random_buf(buf, n);
    return static_cast<int64_t>(n);
}
// getentropy: at most 256 bytes (EIO above, as bionic), all or nothing. The
// missing-function stub's 0 would report success with the buffer untouched.
WINAPK_GUEST_ABI int g_getentropy(void* buf, size_t n) {
    if (n > 256) {
        *guest_errno() = kEIO;
        return -1;
    }
    g_arc4random_buf(buf, n);
    return 0;
}
// ---- syscall(2) ------------------------------------------------------------------------
// Code that bypasses libc (Chromium's and PartitionAlloc's locks, Rust and Go
// runtimes, abseil) calls syscall() with x86_64 Linux numbers. The common ones
// map onto the shim; the rest fail with ENOSYS, which callers expect from an
// older kernel.
// futex(2): FUTEX_WAIT and WAIT_BITSET sleep while *uaddr == val (WaitOnAddress
// wakes on any write to the address, the caller re-checks, as with a spurious
// futex wakeup); WAKE and WAKE_BITSET wake waiters.
int64_t guest_futex(uint32_t* uaddr, int op, uint32_t val, const GuestTimespec* ts) {
    const int cmd = op & 0x7f;  // drop FUTEX_PRIVATE_FLAG and FUTEX_CLOCK_REALTIME
    if (cmd == 0 || cmd == 9) {  // FUTEX_WAIT, FUTEX_WAIT_BITSET
        if (*reinterpret_cast<volatile uint32_t*>(uaddr) != val) {
            *guest_errno() = kEAGAIN;
            return -1;
        }
        DWORD ms = INFINITE;
        if (ts) {
            int64_t ns = ts->tv_sec * 1000000000LL + ts->tv_nsec;
            if (cmd == 9) {  // an absolute deadline on CLOCK_MONOTONIC (or REALTIME with the flag)
                GuestTimespec now;
                g_clock_gettime((op & 256) ? 0 : 1, &now);
                ns -= now.tv_sec * 1000000000LL + now.tv_nsec;
            }
            ms = ns <= 0 ? 0 : static_cast<DWORD>((ns + 999999) / 1000000);
        }
        if (!WaitOnAddress(uaddr, &val, sizeof(val), ms) && GetLastError() == ERROR_TIMEOUT) {
            *guest_errno() = kETIMEDOUT;
            return -1;
        }
        return 0;
    }
    if (cmd == 1 || cmd == 10) {  // FUTEX_WAKE, FUTEX_WAKE_BITSET
        if (val == 1) WakeByAddressSingle(uaddr);
        else WakeByAddressAll(uaddr);
        return 0;  // the number woken is unknown; callers treat it as a hint
    }
    *guest_errno() = kENOSYS;
    return -1;
}
// A sixth argument (mmap's offset) is the first one on the stack; a caller that
// passes fewer leaves its own frame there, which is only read for the calls that have six.
WINAPK_GUEST_ABI int64_t g_syscall(int64_t nr, int64_t a, int64_t b, int64_t c, int64_t d, int64_t e, int64_t f) {
    switch (nr) {
        // Memory by raw call: Gecko's allocator (mozjemalloc, in libmozglue.so) maps and unmaps
        // its chunks this way, so that nothing wrapping libc's mmap sees them; without these it
        // cannot make its first arena ("<jemalloc>: (malloc) Error initializing arena").
        case 9: return reinterpret_cast<int64_t>(g_mmap(reinterpret_cast<void*>(a), static_cast<size_t>(b),
                                                        static_cast<int>(c), static_cast<int>(d),
                                                        static_cast<int>(e), f));
        case 10: return g_mprotect(reinterpret_cast<void*>(a), static_cast<size_t>(b), static_cast<int>(c));
        case 11: return g_munmap(reinterpret_cast<void*>(a), static_cast<size_t>(b));
        case 202: return guest_futex(reinterpret_cast<uint32_t*>(a), static_cast<int>(b), static_cast<uint32_t>(c),
                                     reinterpret_cast<const GuestTimespec*>(d));
        case 186: return static_cast<int64_t>(GetCurrentThreadId());  // gettid
        case 39: return g_getpid();
        case 200: return g_tkill(static_cast<int>(a), static_cast<int>(b));
        case 234: return g_tgkill(static_cast<int>(a), static_cast<int>(b), static_cast<int>(c));
        case 24: SwitchToThread(); return 0;  // sched_yield
        case 204: return affinity_syscall(static_cast<int>(a), static_cast<size_t>(b), reinterpret_cast<void*>(c));
        case 318: return g_getrandom(reinterpret_cast<void*>(a), static_cast<size_t>(b), 0);
        case 228: return g_clock_gettime(static_cast<int>(a), reinterpret_cast<GuestTimespec*>(b));
        case 28: return g_madvise(reinterpret_cast<void*>(a), static_cast<size_t>(b), static_cast<int>(c));
        case 324:  // membarrier: MEMBARRIER_CMD_QUERY answers "no commands", so callers use fences
            if (a == 0) return 0;
            *guest_errno() = kENOSYS;
            return -1;
        default:
            *guest_errno() = kENOSYS;
            return -1;
    }
}

// random(3) and rand(3): Bionic's are NetBSD's additive feedback generator
// (upstream-netbsd/common/lib/libc/stdlib/random.c, USE_BETTER_RANDOM), and
// rand/srand are random/srandom, so a seeded sequence is the one an Android
// device gives. The state is the default 128-byte table or the caller's
// (initstate/setstate): word 0 records the type and the rear pointer, as there.
constexpr int kRandTypes = 5;
constexpr int kRandDegrees[kRandTypes] = {0, 7, 15, 31, 63};
constexpr int kRandSeps[kRandTypes] = {0, 3, 1, 3, 1};
std::mutex g_random_lock;
int32_t g_randtbl[32] = {
    3,
    static_cast<int32_t>(0x991539b1), 0x16a5bce3, 0x6774a4cd, 0x3e01511e, 0x4e508aaa, 0x61048c05,
    static_cast<int32_t>(0xf5500617), static_cast<int32_t>(0x846b7115), 0x6a19892c,
    static_cast<int32_t>(0x896a97af), static_cast<int32_t>(0xdb48f936), 0x14898454,
    0x37ffd106, static_cast<int32_t>(0xb58bff9c), 0x59e17104, static_cast<int32_t>(0xcf918a49), 0x09378c83,
    0x52c7a471, static_cast<int32_t>(0x8d293ea9), 0x1f4fc301, static_cast<int32_t>(0xc3db71be), 0x39b44e1c,
    static_cast<int32_t>(0xf8a44ef9), 0x4c8b80b1, 0x19edc328, static_cast<int32_t>(0x87bf4bdd),
    static_cast<int32_t>(0xc9b240e5), static_cast<int32_t>(0xe9ee4b1b), 0x4382aee7, 0x535b6b41,
    static_cast<int32_t>(0xf3bec5da),
};
int32_t* g_rstate = &g_randtbl[1];
int32_t* g_fptr = &g_randtbl[4];
int32_t* g_rptr = &g_randtbl[1];
int32_t* g_rend = &g_randtbl[32];
int g_rtype = 3, g_rdeg = 31, g_rsep = 3;

int32_t random_unlocked() {
    if (g_rtype == 0) {
        g_rstate[0] = static_cast<int32_t>((static_cast<uint32_t>(g_rstate[0]) * 1103515245u + 12345u) & 0x7fffffff);
        return g_rstate[0];
    }
    int32_t* f = g_fptr;
    int32_t* r = g_rptr;
    *f = static_cast<int32_t>(static_cast<uint32_t>(*f) + static_cast<uint32_t>(*r));
    const int32_t i = static_cast<int32_t>((static_cast<uint32_t>(*f) >> 1) & 0x7fffffff);
    if (++f >= g_rend) {
        f = g_rstate;
        ++r;
    } else if (++r >= g_rend) {
        r = g_rstate;
    }
    g_fptr = f;
    g_rptr = r;
    return i;
}
void srandom_unlocked(unsigned seed) {
    g_rstate[0] = static_cast<int32_t>(seed);
    if (g_rtype == 0) return;
    for (int i = 1; i < g_rdeg; ++i) {  // Park and Miller: x' = 16807 x mod (2^31 - 1)
        const int32_t x = g_rstate[i - 1], hi = x / 127773, lo = x % 127773;
        int32_t t = 16807 * lo - 2836 * hi;
        if (t <= 0) t += 0x7fffffff;
        g_rstate[i] = t;
    }
    g_fptr = &g_rstate[g_rsep];
    g_rptr = &g_rstate[0];
    for (int i = 0; i < 10 * g_rdeg; ++i) random_unlocked();
}
// Word -1 of the current state: the type, and where the rear pointer is.
void save_random_type() {
    g_rstate[-1] = g_rtype == 0 ? 0 : kRandTypes * static_cast<int32_t>(g_rptr - g_rstate) + g_rtype;
}
WINAPK_GUEST_ABI void g_srandom(unsigned seed) {
    std::lock_guard<std::mutex> hold(g_random_lock);
    srandom_unlocked(seed);
}
WINAPK_GUEST_ABI glong g_random() {
    std::lock_guard<std::mutex> hold(g_random_lock);
    return random_unlocked();
}
WINAPK_GUEST_ABI int g_rand() { return static_cast<int>(g_random()); }
WINAPK_GUEST_ABI void g_srand(unsigned seed) { g_srandom(seed); }
WINAPK_GUEST_ABI char* g_initstate(unsigned seed, char* arg_state, size_t n) {
    std::lock_guard<std::mutex> hold(g_random_lock);
    char* old = reinterpret_cast<char*>(&g_rstate[-1]);
    save_random_type();
    if (n < 8) return nullptr;
    const int type = n < 32 ? 0 : n < 64 ? 1 : n < 128 ? 2 : n < 256 ? 3 : 4;
    g_rtype = type;
    g_rdeg = kRandDegrees[type];
    g_rsep = kRandSeps[type];
    auto* words = reinterpret_cast<int32_t*>(arg_state);
    g_rstate = words + 1;
    g_rend = &g_rstate[g_rdeg];
    srandom_unlocked(seed);
    save_random_type();
    return old;
}
WINAPK_GUEST_ABI char* g_setstate(char* arg_state) {
    std::lock_guard<std::mutex> hold(g_random_lock);
    auto* words = reinterpret_cast<int32_t*>(arg_state);
    const int type = words[0] % kRandTypes, rear = words[0] / kRandTypes;
    char* old = reinterpret_cast<char*>(&g_rstate[-1]);
    save_random_type();
    if (type < 0) return nullptr;
    g_rtype = type;
    g_rdeg = kRandDegrees[type];
    g_rsep = kRandSeps[type];
    g_rstate = words + 1;
    if (type != 0) {
        g_rptr = &g_rstate[rear];
        g_fptr = &g_rstate[(rear + g_rsep) % g_rdeg];
    }
    g_rend = &g_rstate[g_rdeg];
    return old;
}

// The rand48 family: POSIX's 48-bit linear congruential generator,
// X' = (a * X + c) mod 2^48 with a = 0x5DEECE66D and c = 0xB unless lcong48
// changed them. X, a and c are kept as 16-bit words, low word first, the way
// Bionic's (NetBSD's) rand48 keeps them, and start from the same seed.
// The e/n/j forms step the caller's X with the shared a and c.
std::mutex g_rand48_lock;
uint16_t g_rand48_seed[3] = {0x330e, 0xabcd, 0x1234};
uint16_t g_rand48_mult[3] = {0xe66d, 0xdeec, 0x0005};
uint16_t g_rand48_add = 0x000b;

uint64_t rand48_value(const uint16_t x[3]) { return x[0] | uint64_t{x[1]} << 16 | uint64_t{x[2]} << 32; }

// One step of `x`, returning the new 48-bit X.
uint64_t rand48_step(uint16_t x[3]) {
    std::lock_guard<std::mutex> hold(g_rand48_lock);
    const uint64_t next = (rand48_value(g_rand48_mult) * rand48_value(x) + g_rand48_add) & ((uint64_t{1} << 48) - 1);
    x[0] = static_cast<uint16_t>(next);
    x[1] = static_cast<uint16_t>(next >> 16);
    x[2] = static_cast<uint16_t>(next >> 32);
    return next;
}

void rand48_reset_params() {
    g_rand48_mult[0] = 0xe66d;
    g_rand48_mult[1] = 0xdeec;
    g_rand48_mult[2] = 0x0005;
    g_rand48_add = 0x000b;
}

// [0, 1): X / 2^48.
double rand48_double(uint64_t x) { return std::ldexp(static_cast<double>(x), -48); }
// [0, 2^31): the top 31 bits.
glong rand48_nonnegative(uint64_t x) { return static_cast<glong>(x >> 17); }
// [-2^31, 2^31): the top 32 bits, signed.
glong rand48_signed(uint64_t x) { return static_cast<int32_t>(static_cast<uint32_t>(x >> 16)); }

WINAPK_GUEST_ABI double g_drand48() { return rand48_double(rand48_step(g_rand48_seed)); }
WINAPK_GUEST_ABI double g_erand48(uint16_t x[3]) { return rand48_double(rand48_step(x)); }
WINAPK_GUEST_ABI glong g_lrand48() { return rand48_nonnegative(rand48_step(g_rand48_seed)); }
WINAPK_GUEST_ABI glong g_nrand48(uint16_t x[3]) { return rand48_nonnegative(rand48_step(x)); }
WINAPK_GUEST_ABI glong g_mrand48() { return rand48_signed(rand48_step(g_rand48_seed)); }
WINAPK_GUEST_ABI glong g_jrand48(uint16_t x[3]) { return rand48_signed(rand48_step(x)); }

WINAPK_GUEST_ABI void g_srand48(glong seed) {
    std::lock_guard<std::mutex> hold(g_rand48_lock);
    g_rand48_seed[0] = 0x330e;
    g_rand48_seed[1] = static_cast<uint16_t>(seed);
    g_rand48_seed[2] = static_cast<uint16_t>(seed >> 16);
    rand48_reset_params();
}

WINAPK_GUEST_ABI uint16_t* g_seed48(const uint16_t x[3]) {
    static uint16_t previous[3];
    std::lock_guard<std::mutex> hold(g_rand48_lock);
    std::memcpy(previous, g_rand48_seed, sizeof(previous));
    std::memcpy(g_rand48_seed, x, sizeof(g_rand48_seed));
    rand48_reset_params();
    return previous;
}

WINAPK_GUEST_ABI void g_lcong48(const uint16_t p[7]) {
    std::lock_guard<std::mutex> hold(g_rand48_lock);
    std::memcpy(g_rand48_seed, p, sizeof(g_rand48_seed));
    std::memcpy(g_rand48_mult, p + 3, sizeof(g_rand48_mult));
    g_rand48_add = p[6];
}

// ---- getopt ------------------------------------------------------------------------------
// MinGW's getopt, getopt_long and getopt_long_only are OpenBSD's
// getopt_long.c, which is Bionic's too, and struct option has one layout on
// both sides (name 0, has_arg 8, flag 16, val 24). So the guest's getopt is
// the CRT's, and the guest's optarg, optind, opterr, optopt and optreset are
// the CRT's own variables: a guest that sets optind back to 1 restarts the
// parser it calls.
WINAPK_GUEST_ABI int g_getopt(int argc, char* const* argv, const char* opts) { return ::getopt(argc, argv, opts); }
WINAPK_GUEST_ABI int g_getopt_long(int argc, char* const* argv, const char* opts, const struct option* longopts,
                                   int* index) {
    return ::getopt_long(argc, argv, opts, longopts, index);
}
WINAPK_GUEST_ABI int g_getopt_long_only(int argc, char* const* argv, const char* opts,
                                        const struct option* longopts, int* index) {
    return ::getopt_long_only(argc, argv, opts, longopts, index);
}

// ---- process exit -------------------------------------------------------------------------
[[noreturn]] WINAPK_GUEST_ABI void g_exit(int code) {
    std::fprintf(stderr, "[win.apk] guest called exit(%d)\n", code);
    std::fflush(stderr);
    mini_bionic_run_atexit();
    std::fflush(nullptr);
    TerminateProcess(GetCurrentProcess(), static_cast<UINT>(code));
    std::abort();
}
[[noreturn]] WINAPK_GUEST_ABI void g__exit(int code) {
    std::fprintf(stderr, "[win.apk] guest called _exit(%d)\n", code);
    std::fflush(stderr);
    TerminateProcess(GetCurrentProcess(), static_cast<UINT>(code));
    std::abort();
}
WINAPK_GUEST_ABI int g_atexit(void (*)()) { return 0; }  // never run: an app's process never exits normally

// ---- libm -----------------------------------------------------------------------------------
#define WINAPK_M1(name) WINAPK_GUEST_ABI double m_##name(double x) { return std::name(x); } \
                        WINAPK_GUEST_ABI float m_##name##f(float x) { return std::name(x); }
#define WINAPK_M2(name) WINAPK_GUEST_ABI double m_##name(double x, double y) { return std::name(x, y); } \
                        WINAPK_GUEST_ABI float m_##name##f(float x, float y) { return std::name(x, y); }
WINAPK_M1(sqrt) WINAPK_M1(cbrt) WINAPK_M1(sin) WINAPK_M1(cos) WINAPK_M1(tan) WINAPK_M1(asin) WINAPK_M1(acos)
WINAPK_M1(atan) WINAPK_M1(sinh) WINAPK_M1(cosh) WINAPK_M1(tanh) WINAPK_M1(asinh) WINAPK_M1(acosh) WINAPK_M1(atanh)
WINAPK_M1(exp) WINAPK_M1(exp2) WINAPK_M1(expm1) WINAPK_M1(log) WINAPK_M1(log2) WINAPK_M1(log10) WINAPK_M1(log1p)
WINAPK_M1(fabs) WINAPK_M1(floor) WINAPK_M1(ceil) WINAPK_M1(round) WINAPK_M1(trunc) WINAPK_M1(rint)
WINAPK_M1(nearbyint) WINAPK_M1(erf) WINAPK_M1(erfc) WINAPK_M1(tgamma) WINAPK_M1(lgamma) WINAPK_M1(logb)
WINAPK_M2(pow) WINAPK_M2(atan2) WINAPK_M2(fmod) WINAPK_M2(hypot) WINAPK_M2(fmin) WINAPK_M2(fmax) WINAPK_M2(copysign)
WINAPK_M2(remainder) WINAPK_M2(fdim) WINAPK_M2(nextafter)
#undef WINAPK_M1
#undef WINAPK_M2
WINAPK_GUEST_ABI double m_ldexp(double x, int e) { return std::ldexp(x, e); }
WINAPK_GUEST_ABI float m_ldexpf(float x, int e) { return std::ldexp(x, e); }
WINAPK_GUEST_ABI double m_scalbn(double x, int e) { return std::scalbn(x, e); }
WINAPK_GUEST_ABI float m_scalbnf(float x, int e) { return std::scalbn(x, e); }
WINAPK_GUEST_ABI double m_frexp(double x, int* e) { return std::frexp(x, e); }
WINAPK_GUEST_ABI float m_frexpf(float x, int* e) { return std::frexp(x, e); }
WINAPK_GUEST_ABI double m_modf(double x, double* i) { return std::modf(x, i); }
WINAPK_GUEST_ABI float m_modff(float x, float* i) { return std::modf(x, i); }
WINAPK_GUEST_ABI double m_fma(double x, double y, double z) { return std::fma(x, y, z); }
WINAPK_GUEST_ABI float m_fmaf(float x, float y, float z) { return std::fma(x, y, z); }
WINAPK_GUEST_ABI glong m_lround(double x) { return std::llround(x); }
WINAPK_GUEST_ABI glong m_lroundf(float x) { return std::llround(x); }
WINAPK_GUEST_ABI glong m_lrint(double x) { return std::llrint(x); }
WINAPK_GUEST_ABI glong m_lrintf(float x) { return std::llrint(x); }
WINAPK_GUEST_ABI long long m_llround(double x) { return std::llround(x); }
WINAPK_GUEST_ABI long long m_llrint(double x) { return std::llrint(x); }
WINAPK_GUEST_ABI long long m_llroundf(float x) { return std::llround(x); }
WINAPK_GUEST_ABI long long m_llrintf(float x) { return std::llrint(x); }

// lroundl, llroundl, lrintl, llrintl: Android's long double is binary128 (see
// strtold in string.cpp), which the host has no arithmetic for, so the value
// is rounded exactly in integers: sign, the 113-bit significand and the
// exponent. Out of range (and NaN) is FE_INVALID territory, where Bionic's
// (FreeBSD's) lround returns LONG_MAX and its lrint the conversion's
// "integer indefinite", LONG_MIN.
// The FP rounding mode as MXCSR encodes it: 0 nearest, 1 down, 2 up, 3 toward zero.
int current_rounding_mode() {
    // FPCR.RMode (bits 23:22) has up and down the other way round.
    uint64_t fpcr;
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    const int rmode = static_cast<int>((fpcr >> 22) & 3);
    return rmode == 1 ? 2 : rmode == 2 ? 1 : rmode;
}

enum class QuadRound { kAway, kCurrent };
int64_t quad_to_int(GuestQuad q, QuadRound how) {
    const uint64_t lo = static_cast<uint64_t>(q[0]), hi = static_cast<uint64_t>(q[1]);
    const bool neg = hi >> 63;
    const int bexp = static_cast<int>((hi >> 48) & 0x7fff);
    const int64_t invalid = how == QuadRound::kAway ? INT64_MAX : INT64_MIN;
    if (bexp == 0x7fff) return invalid;
    const uint64_t top = (hi & 0xffffffffffffull) | (bexp ? 1ull << 48 : 0);  // the integer bit, if normal
    const unsigned __int128 sig = (static_cast<unsigned __int128>(top) << 64) | lo;
    if (sig == 0) return 0;
    const int e = (bexp ? bexp : 1) - 16383;  // value = sig * 2^(e - 112)
    if (e >= 64) return invalid;
    unsigned __int128 ip;
    int frac;  // the fraction against one half: -1 below, 0 equal, 1 above; -2 none
    if (e >= 112) {
        ip = sig << (e - 112);
        frac = -2;
    } else if (e < -1) {
        ip = 0;
        frac = -1;
    } else {
        const int shift = 112 - e;  // 1..113
        ip = shift >= 128 ? 0 : sig >> shift;
        const unsigned __int128 rem = sig & ((static_cast<unsigned __int128>(1) << shift) - 1);
        const unsigned __int128 half = static_cast<unsigned __int128>(1) << (shift - 1);
        frac = rem == 0 ? -2 : rem < half ? -1 : rem == half ? 0 : 1;
    }
    bool up = false;
    if (frac != -2) {
        if (how == QuadRound::kAway) {
            up = frac >= 0;
        } else {
            switch (current_rounding_mode()) {  // the rounding control rint follows (0 nearest, 1 down, 2 up, 3 zero)
                case 0: up = frac > 0 || (frac == 0 && (ip & 1)); break;  // to nearest, ties to even
                case 1: up = neg; break;                                   // down
                case 2: up = !neg; break;                                  // up
                default: break;                                            // toward zero
            }
        }
    }
    if (up) ++ip;
    const unsigned __int128 limit = neg ? static_cast<unsigned __int128>(1) << 63 : INT64_MAX;
    if (ip > limit) return invalid;
    return neg ? static_cast<int64_t>(0 - static_cast<uint64_t>(ip)) : static_cast<int64_t>(ip);
}
// The rest of <math.h>'s long double functions: the binary128 argument is
// taken to the host's 80-bit long double (64-bit significand, the same
// exponent range), computed there and brought back. The last 49 bits of a
// binary128 significand are lost, which a device's soft-float would keep;
// a double's worth of precision is what these callers ask for in practice.
// Missing, powl answered 0 (the loader's "not implemented" fallback) and
// Google Maps' native map engine computed with it.
long double quad_to_ld(GuestQuad q) {
    const uint64_t lo = static_cast<uint64_t>(q[0]), hi = static_cast<uint64_t>(q[1]);
    const bool neg = hi >> 63;
    const int bexp = static_cast<int>((hi >> 48) & 0x7fff);
    const uint64_t frac_hi = hi & 0xffffffffffffull;  // the fraction's top 48 bits
    // The fraction's top 63 bits; the rest decides the rounding (to nearest, ties to even).
    uint64_t top63 = (frac_hi << 15) | (lo >> 49);
    const uint64_t rest = lo & ((1ull << 49) - 1), half = 1ull << 48;
    long double v;
    if (bexp == 0x7fff) {
        v = (frac_hi | lo) ? std::numeric_limits<long double>::quiet_NaN()
                           : std::numeric_limits<long double>::infinity();
    } else if (bexp == 0 && (frac_hi | lo) == 0) {
        v = 0.0L;
    } else {
        uint64_t sig = top63 | (bexp ? 1ull << 63 : 0);
        int e = (bexp ? bexp : 1) - 16383 - 63;
        if (rest > half || (rest == half && (sig & 1))) {
            if (++sig == 0) {  // carried out of 64 bits
                sig = 1ull << 63;
                ++e;
            }
        }
        if (!bexp) {  // subnormal binary128: far below any long double here but zero
            v = 0.0L;
        } else {
            v = std::ldexp(static_cast<long double>(sig), e);
        }
    }
    return neg ? -v : v;
}

}  // namespace

// Through frexp, not the bytes: the host's long double is a plain double on Windows ARM64, which is
// exact in binary128.
GuestQuad ld_to_quad(long double v) {
    const uint64_t sign = std::signbit(v) ? 1ull << 63 : 0;
    uint64_t exp = 0, fraction = 0;  // binary128's biased exponent; the fraction's top 64 bits
    if (std::isnan(v)) {
        exp = 0x7fff;
        fraction = 1ull << 63;  // a quiet NaN
    } else if (std::isinf(v)) {
        exp = 0x7fff;
    } else if (v != 0) {
        int e = 0;
        const long double m = std::frexp(std::fabs(v), &e);              // in [0.5, 1)
        const uint64_t sig = static_cast<uint64_t>(std::ldexp(m, 64));  // the leading 1 at bit 63, exactly
        exp = static_cast<uint64_t>(e - 1 + 16383);
        fraction = sig << 1;  // binary128's leading 1 is implicit
    }
    GuestQuad q = {static_cast<long long>(fraction << 48),
                   static_cast<long long>(sign | (exp << 48) | (fraction >> 16))};
    return q;
}

namespace {

#define WINAPK_QUAD1(name, expr) \
    WINAPK_GUEST_ABI GuestQuad m_##name(GuestQuad qx) { const long double x = quad_to_ld(qx); return ld_to_quad(expr); }
#define WINAPK_QUAD2(name, expr)                                    \
    WINAPK_GUEST_ABI GuestQuad m_##name(GuestQuad qx, GuestQuad qy) { \
        const long double x = quad_to_ld(qx), y = quad_to_ld(qy);   \
        return ld_to_quad(expr);                                    \
    }
WINAPK_QUAD2(powl, std::pow(x, y))
WINAPK_QUAD2(fmodl, std::fmod(x, y))
WINAPK_QUAD2(atan2l, std::atan2(x, y))
WINAPK_QUAD1(sqrtl, std::sqrt(x))
WINAPK_QUAD1(expl, std::exp(x))
WINAPK_QUAD1(exp2l, std::exp2(x))
WINAPK_QUAD1(logl, std::log(x))
WINAPK_QUAD1(log2l, std::log2(x))
WINAPK_QUAD1(log10l, std::log10(x))
WINAPK_QUAD1(sinl, std::sin(x))
WINAPK_QUAD1(cosl, std::cos(x))
WINAPK_QUAD1(tanl, std::tan(x))
WINAPK_QUAD1(atanl, std::atan(x))
WINAPK_QUAD1(floorl, std::floor(x))
WINAPK_QUAD1(ceill, std::ceil(x))
WINAPK_QUAD1(truncl, std::trunc(x))
WINAPK_QUAD1(roundl, std::round(x))
WINAPK_QUAD1(fabsl, std::fabs(x))
WINAPK_GUEST_ABI GuestQuad m_ldexpl(GuestQuad qx, int e) { return ld_to_quad(std::ldexp(quad_to_ld(qx), e)); }
#undef WINAPK_QUAD1
#undef WINAPK_QUAD2
WINAPK_GUEST_ABI glong m_lroundl(GuestQuad x) { return quad_to_int(x, QuadRound::kAway); }
WINAPK_GUEST_ABI long long m_llroundl(GuestQuad x) { return quad_to_int(x, QuadRound::kAway); }
WINAPK_GUEST_ABI glong m_lrintl(GuestQuad x) { return quad_to_int(x, QuadRound::kCurrent); }
WINAPK_GUEST_ABI long long m_llrintl(GuestQuad x) { return quad_to_int(x, QuadRound::kCurrent); }
WINAPK_GUEST_ABI int m_ilogb(double x) { return std::ilogb(x); }
WINAPK_GUEST_ABI void m_sincos(double x, double* s, double* c) {
    *s = std::sin(x);
    *c = std::cos(x);
}
WINAPK_GUEST_ABI void m_sincosf(float x, float* s, float* c) {
    *s = std::sin(x);
    *c = std::cos(x);
}
WINAPK_GUEST_ABI double m_nan(const char*) { return std::nan(""); }
WINAPK_GUEST_ABI float m_nanf(const char*) { return std::nanf(""); }
// Bionic's isinf/isnan/finite exist as functions, too.
WINAPK_GUEST_ABI int m_isinf(double x) { return std::isinf(x); }
WINAPK_GUEST_ABI int m_isnan(double x) { return std::isnan(x); }
WINAPK_GUEST_ABI int m_finite(double x) { return std::isfinite(x); }
WINAPK_GUEST_ABI int m_isinff(float x) { return std::isinf(x); }
WINAPK_GUEST_ABI int m_isnanf(float x) { return std::isnan(x); }

#define M(name) WINAPK_SHIM(#name, m_##name), WINAPK_SHIM(#name "f", m_##name##f)

// ---- scheduling policy and the passwd database ---------------------------------
//
// sched_getscheduler and the priority range: everything runs as SCHED_OTHER
// (0), whose priority range on Linux is 0..0; SCHED_FIFO (1) and SCHED_RR (2)
// span 1..99. ffmpeg and Hermes size thread priorities from these; the stub's
// 0 for both ends made the range empty.
WINAPK_GUEST_ABI int g_sched_getscheduler(int) { return 0; }
WINAPK_GUEST_ABI int g_sched_get_priority_min(int policy) { return policy == 1 || policy == 2 ? 1 : 0; }
WINAPK_GUEST_ABI int g_sched_get_priority_max(int policy) { return policy == 1 || policy == 2 ? 99 : 0; }

// getpwuid(_r): the app's own user, as Android names it (u0_a<n> for uid
// 10000+n; android.os.Process.getUidForName agrees). Bionic's LP64 struct
// passwd: pw_name, pw_passwd, pw_uid, pw_gid, pw_gecos, pw_dir, pw_shell.
struct GuestPasswd {
    char* pw_name;
    char* pw_passwd;
    uint32_t pw_uid;
    uint32_t pw_gid;
    char* pw_gecos;
    char* pw_dir;
    char* pw_shell;
};
int fill_passwd(uint32_t uid, GuestPasswd* pwd, char* buf, size_t buflen) {
    char name[32];
    if (uid >= 10000 && uid < 20000) std::snprintf(name, sizeof(name), "u0_a%u", uid - 10000);
    else if (uid == 0) std::snprintf(name, sizeof(name), "root");
    else std::snprintf(name, sizeof(name), "uid_%u", uid);
    const char* dir = "/data";
    const char* shell = "/system/bin/sh";
    const size_t need = std::strlen(name) + 1 + std::strlen(dir) + 1 + std::strlen(shell) + 1 + 1;
    if (buflen < need) return 34;  // ERANGE
    char* p = buf;
    pwd->pw_name = p;
    p = std::strcpy(p, name) + std::strlen(name) + 1;
    pwd->pw_passwd = p;
    *p++ = '\0';
    pwd->pw_uid = uid;
    pwd->pw_gid = uid;
    pwd->pw_gecos = pwd->pw_passwd;
    pwd->pw_dir = p;
    p = std::strcpy(p, dir) + std::strlen(dir) + 1;
    pwd->pw_shell = p;
    std::strcpy(p, shell);
    return 0;
}
WINAPK_GUEST_ABI int g_getpwuid_r(uint32_t uid, GuestPasswd* pwd, char* buf, size_t buflen, GuestPasswd** result) {
    if (result) *result = nullptr;
    if (!pwd || !buf) return 22;  // EINVAL
    const int rc = fill_passwd(uid, pwd, buf, buflen);
    if (rc == 0 && result) *result = pwd;
    return rc;
}
WINAPK_GUEST_ABI GuestPasswd* g_getpwuid(uint32_t uid) {
    thread_local GuestPasswd pwd;
    thread_local char buf[128];
    return fill_passwd(uid, &pwd, buf, sizeof(buf)) == 0 ? &pwd : nullptr;
}

// ---- <search.h>: tsearch and friends ----------------------------------------
//
// A plain binary search tree, as bionic's: the node's first member is the
// key, which is what a caller reads through the returned pointer. The
// comparator and twalk's action are the guest's, called as AAPCS64
// functions (an arm64 guest's through the bridge).
struct TNode {
    const void* key;
    TNode* left;
    TNode* right;
};
using TCompare = int(WINAPK_GUEST_ABI*)(const void*, const void*);
using TAction = void(WINAPK_GUEST_ABI*)(const void*, int, int);  // node, VISIT, level
WINAPK_GUEST_ABI void* g_tsearch(const void* key, void** rootp, TCompare cmp) {
    if (!rootp || !cmp) return nullptr;
    TNode** p = reinterpret_cast<TNode**>(rootp);
    while (*p) {
        const int c = cmp(key, (*p)->key);
        if (c == 0) return *p;
        p = c < 0 ? &(*p)->left : &(*p)->right;
    }
    *p = new TNode{key, nullptr, nullptr};
    return *p;
}
WINAPK_GUEST_ABI void* g_tfind(const void* key, void* const* rootp, TCompare cmp) {
    if (!rootp || !cmp) return nullptr;
    TNode* n = static_cast<TNode*>(*rootp);
    while (n) {
        const int c = cmp(key, n->key);
        if (c == 0) return n;
        n = c < 0 ? n->left : n->right;
    }
    return nullptr;
}
WINAPK_GUEST_ABI void* g_tdelete(const void* key, void** rootp, TCompare cmp) {
    if (!rootp || !*rootp || !cmp) return nullptr;
    TNode** p = reinterpret_cast<TNode**>(rootp);
    TNode* parent = nullptr;
    while (*p) {
        const int c = cmp(key, (*p)->key);
        if (c == 0) break;
        parent = *p;
        p = c < 0 ? &(*p)->left : &(*p)->right;
    }
    TNode* n = *p;
    if (!n) return nullptr;
    if (!n->left) {
        *p = n->right;
    } else if (!n->right) {
        *p = n->left;
    } else {
        // Two children: the in-order successor takes the node's place.
        TNode** s = &n->right;
        while ((*s)->left) s = &(*s)->left;
        TNode* succ = *s;
        *s = succ->right;
        succ->left = n->left;
        succ->right = n->right;
        *p = succ;
    }
    delete n;
    return parent ? static_cast<void*>(parent) : static_cast<void*>(rootp);  // the parent, or "the root went"
}
void twalk_from(const TNode* n, TAction action, int level) {
    if (!n) return;
    if (!n->left && !n->right) {
        action(n, 3, level);  // leaf
        return;
    }
    action(n, 0, level);  // preorder
    twalk_from(n->left, action, level + 1);
    action(n, 1, level);  // postorder
    twalk_from(n->right, action, level + 1);
    action(n, 2, level);  // endorder
}
WINAPK_GUEST_ABI void g_twalk(const void* root, TAction action) {
    if (root && action) twalk_from(static_cast<const TNode*>(root), action, 0);
}
using TFree = void(WINAPK_GUEST_ABI*)(void*);
void tdestroy_from(TNode* n, TFree free_node) {
    if (!n) return;
    tdestroy_from(n->left, free_node);
    tdestroy_from(n->right, free_node);
    if (free_node) free_node(const_cast<void*>(n->key));
    delete n;
}
WINAPK_GUEST_ABI void g_tdestroy(void* root, TFree free_node) { tdestroy_from(static_cast<TNode*>(root), free_node); }

// ---- calls that were missing ---------------------------------------------------------------
// See the same heading in stdio.cpp: the loader's fallback answers 0, which for these is "done" or a
// valid-looking result. Each now fails as Linux reports the failure, the unsupported ones saying so once.

// mremap: MAP_FAILED with ENOMEM, on which callers allocate, copy and unmap themselves. (0 was a
// mapping at address 0.)
// TODO(mremap): grow in place when the pages after the mapping are free; MREMAP_MAYMOVE as mmap + copy.
WINAPK_GUEST_ABI void* g_mremap() {
    static std::atomic<bool> said{false};
    say_once(said, "mremap()", "not supported; the call fails with ENOMEM");
    *guest_errno() = kENOMEM;
    return reinterpret_cast<void*>(~uintptr_t{0});
}
// System V shared memory: ENOSYS, as on Android, whose kernels are built without it. shmat's failure
// is (void*)-1, not null.
WINAPK_GUEST_ABI int64_t g_sysv_shm() {
    static std::atomic<bool> said{false};
    say_once(said, "shmget()/shmat()", "System V shared memory does not exist on Android; the call fails with ENOSYS");
    return *guest_errno() = kENOSYS, -1;
}
// What only root may do: EPERM, the answer an app gets on a device.
WINAPK_GUEST_ABI int g_root_only() { return *guest_errno() = kEPERM, -1; }
// Extended attributes: ENOTSUP, as on a file system without them.
WINAPK_GUEST_ABI int64_t g_no_xattr() { return *guest_errno() = kENOTSUP, -1; }

// sysinfo(2): memory and uptime, the fields callers size caches by. (Unwritten, the struct was read
// as it lay on the caller's stack.)
struct GuestSysinfo {
    int64_t uptime;
    uint64_t loads[3], totalram, freeram, sharedram, bufferram, totalswap, freeswap;
    uint16_t procs, pad;
    uint64_t totalhigh, freehigh;
    uint32_t mem_unit;
};
static_assert(sizeof(GuestSysinfo) == 112, "Linux struct sysinfo on x86_64");
WINAPK_GUEST_ABI int g_sysinfo(GuestSysinfo* out) {
    if (!out) return *guest_errno() = kEFAULT, -1;
    MEMORYSTATUSEX ms{};
    ms.dwLength = sizeof(ms);
    GlobalMemoryStatusEx(&ms);
    std::memset(out, 0, sizeof(*out));
    out->uptime = static_cast<int64_t>(GetTickCount64() / 1000);
    out->totalram = ms.ullTotalPhys;
    out->freeram = ms.ullAvailPhys;
    out->totalswap = ms.ullTotalPageFile > ms.ullTotalPhys ? ms.ullTotalPageFile - ms.ullTotalPhys : 0;
    out->freeswap = std::min<uint64_t>(out->totalswap, ms.ullAvailPageFile);
    out->procs = 1;
    out->mem_unit = 1;
    return 0;
}

// getpwnam_r, getgrnam_r, getgrgid_r: no such entry, which is 0 with *result null. (Unwritten,
// *result was whatever the caller's variable held.)
WINAPK_GUEST_ABI int g_no_such_entry(uint64_t, void*, char*, size_t, void** result) {
    if (result) *result = nullptr;
    return 0;
}

// iconv: iconv_open's failure is (iconv_t)-1 with EINVAL, "this conversion is not available". The
// fallback's 0 was a valid descriptor, and iconv's 0 "everything converted" with the input untouched:
// a caller that converts until its input is used up never finished.
// TODO(iconv): UTF-8, UTF-16 and UTF-32 as Bionic has them (MultiByteToWideChar covers the rest).
WINAPK_GUEST_ABI void* g_iconv_open(const char* to, const char* from) {
    static std::atomic<int> reported{0};
    if (reported.fetch_add(1) < 5) {  // a few pairs tell more than one; a caller that retries must not flood
        std::fprintf(stderr, "[win.apk] iconv_open(\"%s\", \"%s\"): iconv is not implemented yet; the call fails "
                             "with EINVAL\n", to ? to : "", from ? from : "");
        std::fflush(stderr);
    }
    *guest_errno() = kEINVAL;
    return reinterpret_cast<void*>(~uintptr_t{0});
}
WINAPK_GUEST_ABI int64_t g_iconv() { return *guest_errno() = kEBADF, -1; }  // iconv, iconv_close: no such descriptor

// POSIX regular expressions: regcomp fails with REG_ESPACE (12). The fallback's 0 was "compiled", and
// regexec's 0 "matches", for every pattern and every string.
// TODO(regex): regcomp/regexec over Bionic's regex_t (re_nsub at offset 8) -- std::regex has the POSIX
// basic and extended grammars.
WINAPK_GUEST_ABI int g_regcomp() {
    static std::atomic<bool> said{false};
    say_once(said, "regcomp()", "POSIX regular expressions are not implemented yet; the call fails with REG_ESPACE");
    return 12;
}
WINAPK_GUEST_ABI int g_regexec() { return 1; }  // REG_NOMATCH
WINAPK_GUEST_ABI size_t g_regerror(int, const void*, char* buf, size_t size) {
    static const char kText[] = "regular expressions are not implemented";
    if (buf && size) {
        std::strncpy(buf, kText, size - 1);
        buf[size - 1] = '\0';
    }
    return sizeof(kText);
}
WINAPK_GUEST_ABI void g_regfree(void*) {}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("tsearch", g_tsearch),
    WINAPK_SHIM("tfind", g_tfind),
    WINAPK_SHIM("tdelete", g_tdelete),
    WINAPK_SHIM("twalk", g_twalk),
    WINAPK_SHIM("tdestroy", g_tdestroy),
    WINAPK_SHIM("sched_getscheduler", g_sched_getscheduler),
    WINAPK_SHIM("sched_get_priority_min", g_sched_get_priority_min),
    WINAPK_SHIM("sched_get_priority_max", g_sched_get_priority_max),
    WINAPK_SHIM("getpwuid_r", g_getpwuid_r),
    WINAPK_SHIM("getpwnam_r", g_no_such_entry),
    WINAPK_SHIM("getgrnam_r", g_no_such_entry),
    WINAPK_SHIM("getgrgid_r", g_no_such_entry),
    WINAPK_SHIM("getpwuid", g_getpwuid),
    WINAPK_SHIM("clock_gettime", g_clock_gettime),
    WINAPK_SHIM("clock_getres", g_clock_getres),
    WINAPK_SHIM("gettimeofday", g_gettimeofday),
    WINAPK_SHIM("time", g_time),
    WINAPK_SHIM("difftime", g_difftime),
    WINAPK_SHIM("clock", g_clock),
    WINAPK_SHIM("nanosleep", g_nanosleep),
    WINAPK_SHIM("clock_nanosleep", g_clock_nanosleep),
    WINAPK_SHIM("usleep", g_usleep),
    WINAPK_SHIM("sleep", g_sleep),
    WINAPK_SHIM("gmtime_r", g_gmtime_r),
    WINAPK_SHIM("localtime_r", g_localtime_r),
    WINAPK_SHIM("gmtime", g_gmtime),
    WINAPK_SHIM("localtime", g_localtime),
    WINAPK_SHIM("mktime", g_mktime),
    WINAPK_SHIM("timegm", g_timegm),
    WINAPK_SHIM("strftime", g_strftime),
    WINAPK_SHIM("tzset", g_tzset),
    WINAPK_SHIM("mmap", g_mmap),
    WINAPK_SHIM("mmap64", g_mmap),
    WINAPK_SHIM("munmap", g_munmap),
    WINAPK_SHIM("mprotect", g_mprotect),
    WINAPK_SHIM("madvise", g_madvise),
    WINAPK_SHIM("msync", g_msync),
    WINAPK_SHIM("mlock", g_mlock),
    WINAPK_SHIM("munlock", g_munlock),
    WINAPK_SHIM("__system_property_get", g_property_get),
    WINAPK_SHIM("__system_property_find", g_property_find),
    WINAPK_SHIM("__system_property_read", g_property_read),
    WINAPK_SHIM("__system_property_read_callback", g_property_read_callback),
    WINAPK_SHIM("__system_property_set", g_property_set),
    WINAPK_SHIM("android_get_device_api_level", g_get_device_api_level),
    WINAPK_SHIM("syscall", g_syscall),
    WINAPK_SHIM("strftime_l", g_strftime),
    WINAPK_SHIM("strptime", g_strptime),
    WINAPK_SHIM("strptime_l", g_strptime),
    {"timezone", &g_timezone},
    {"daylight", &g_daylight},
    {"tzname", g_tzname},
    {"in6addr_any", g_in6addr_any},
    {"in6addr_loopback", g_in6addr_loopback},
    WINAPK_SHIM("getenv", g_getenv),
    {"environ", &g_environ},
    WINAPK_SHIM("secure_getenv", g_getenv),
    WINAPK_SHIM("setenv", g_setenv),
    WINAPK_SHIM("unsetenv", g_unsetenv),
    WINAPK_SHIM("putenv", g_putenv),
    WINAPK_SHIM("clearenv", g_clearenv),
    WINAPK_SHIM("getpid", g_getpid),
    WINAPK_SHIM("getppid", g_getppid),
    WINAPK_SHIM("getuid", g_getuid),
    WINAPK_SHIM("geteuid", g_getuid),
    WINAPK_SHIM("getgid", g_getuid),
    WINAPK_SHIM("getegid", g_getuid),
    WINAPK_SHIM("kill", g_kill),
    WINAPK_SHIM("tgkill", g_tgkill),
    WINAPK_SHIM("tkill", g_tkill),
    WINAPK_SHIM("fork", g_fork),
    WINAPK_SHIM("vfork", g_fork),
    WINAPK_SHIM("waitpid", g_waitpid),
    WINAPK_SHIM("wait", g_wait),
    WINAPK_SHIM("wait3", g_wait),
    WINAPK_SHIM("wait4", g_wait),
    WINAPK_SHIM("waitid", g_wait),
    WINAPK_SHIM("clone", g_clone),
    WINAPK_SHIM("posix_spawn", g_posix_spawn),
    WINAPK_SHIM("posix_spawnp", g_posix_spawn),
    WINAPK_SHIM("execve", g_exec),
    WINAPK_SHIM("execv", g_exec),
    WINAPK_SHIM("execvp", g_exec),
    WINAPK_SHIM("execvpe", g_exec),
    WINAPK_SHIM("execl", g_exec),
    WINAPK_SHIM("execlp", g_exec),
    WINAPK_SHIM("execle", g_exec),
    WINAPK_SHIM("fexecve", g_exec),
    WINAPK_SHIM("mremap", g_mremap),
    WINAPK_SHIM("shmget", g_sysv_shm),
    WINAPK_SHIM("shmat", g_sysv_shm),
    WINAPK_SHIM("shmdt", g_sysv_shm),
    WINAPK_SHIM("shmctl", g_sysv_shm),
    WINAPK_SHIM("chroot", g_root_only),
    WINAPK_SHIM("mount", g_root_only),
    WINAPK_SHIM("umount", g_root_only),
    WINAPK_SHIM("umount2", g_root_only),
    WINAPK_SHIM("unshare", g_root_only),
    WINAPK_SHIM("setns", g_root_only),
    WINAPK_SHIM("reboot", g_root_only),
    WINAPK_SHIM("swapon", g_root_only),
    WINAPK_SHIM("swapoff", g_root_only),
    WINAPK_SHIM("settimeofday", g_root_only),
    WINAPK_SHIM("clock_settime", g_root_only),
    WINAPK_SHIM("sethostname", g_root_only),
    WINAPK_SHIM("getxattr", g_no_xattr),
    WINAPK_SHIM("lgetxattr", g_no_xattr),
    WINAPK_SHIM("fgetxattr", g_no_xattr),
    WINAPK_SHIM("setxattr", g_no_xattr),
    WINAPK_SHIM("lsetxattr", g_no_xattr),
    WINAPK_SHIM("fsetxattr", g_no_xattr),
    WINAPK_SHIM("listxattr", g_no_xattr),
    WINAPK_SHIM("llistxattr", g_no_xattr),
    WINAPK_SHIM("flistxattr", g_no_xattr),
    WINAPK_SHIM("removexattr", g_no_xattr),
    WINAPK_SHIM("lremovexattr", g_no_xattr),
    WINAPK_SHIM("fremovexattr", g_no_xattr),
    WINAPK_SHIM("sysinfo", g_sysinfo),
    WINAPK_SHIM("iconv_open", g_iconv_open),
    WINAPK_SHIM("iconv", g_iconv),
    WINAPK_SHIM("iconv_close", g_iconv),
    WINAPK_SHIM("regcomp", g_regcomp),
    WINAPK_SHIM("regexec", g_regexec),
    WINAPK_SHIM("regerror", g_regerror),
    WINAPK_SHIM("regfree", g_regfree),
    WINAPK_SHIM("sched_getaffinity", g_sched_getaffinity),
    WINAPK_SHIM("ATrace_isEnabled", g_atrace_is_enabled),
    WINAPK_SHIM("ATrace_beginSection", g_atrace_begin_section),
    WINAPK_SHIM("ATrace_endSection", g_atrace_end_section),
    WINAPK_SHIM("ATrace_beginAsyncSection", g_atrace_async_section),
    WINAPK_SHIM("ATrace_endAsyncSection", g_atrace_async_section),
    WINAPK_SHIM("ATrace_setCounter", g_atrace_set_counter),
    WINAPK_SHIM("getrlimit", g_getrlimit),
    WINAPK_SHIM("getrlimit64", g_getrlimit),
    WINAPK_SHIM("setrlimit", g_setrlimit),
    WINAPK_SHIM("setrlimit64", g_setrlimit),
    WINAPK_SHIM("prlimit", g_prlimit),
    WINAPK_SHIM("prlimit64", g_prlimit),
    WINAPK_SHIM("sysconf", g_sysconf),
    WINAPK_SHIM("getpagesize", g_getpagesize),
    WINAPK_SHIM("uname", g_uname),
    WINAPK_SHIM("getauxval", g_getauxval),
    WINAPK_SHIM("arc4random", g_arc4random),
    WINAPK_SHIM("arc4random_buf", g_arc4random_buf),
    WINAPK_SHIM("arc4random_uniform", g_arc4random_uniform),
    WINAPK_SHIM("getrandom", g_getrandom),
    WINAPK_SHIM("getentropy", g_getentropy),
    WINAPK_SHIM("srand", g_srand),
    WINAPK_SHIM("rand", g_rand),
    WINAPK_SHIM("srandom", g_srandom),
    WINAPK_SHIM("random", g_random),
    WINAPK_SHIM("initstate", g_initstate),
    WINAPK_SHIM("setstate", g_setstate),
    WINAPK_SHIM("drand48", g_drand48),
    WINAPK_SHIM("erand48", g_erand48),
    WINAPK_SHIM("lrand48", g_lrand48),
    WINAPK_SHIM("nrand48", g_nrand48),
    WINAPK_SHIM("mrand48", g_mrand48),
    WINAPK_SHIM("jrand48", g_jrand48),
    WINAPK_SHIM("srand48", g_srand48),
    WINAPK_SHIM("seed48", g_seed48),
    WINAPK_SHIM("lcong48", g_lcong48),
    WINAPK_SHIM("getopt", g_getopt),
    WINAPK_SHIM("getopt_long", g_getopt_long),
    WINAPK_SHIM("getopt_long_only", g_getopt_long_only),
    WINAPK_SHIM("optarg", optarg),
    WINAPK_SHIM("optind", optind),
    WINAPK_SHIM("opterr", opterr),
    WINAPK_SHIM("optopt", optopt),
    WINAPK_SHIM("optreset", __mingw_optreset),
    WINAPK_SHIM("exit", g_exit),
    WINAPK_SHIM("_exit", g__exit),
    WINAPK_SHIM("_Exit", g__exit),
    WINAPK_SHIM("quick_exit", g__exit),  // returned to its caller, which the compiler knows it never does
    WINAPK_SHIM("atexit", g_atexit),
    M(sqrt), M(cbrt), M(sin), M(cos), M(tan), M(asin), M(acos), M(atan), M(sinh), M(cosh), M(tanh), M(asinh),
    M(acosh), M(atanh), M(exp), M(exp2), M(expm1), M(log), M(log2), M(log10), M(log1p), M(fabs), M(floor),
    M(ceil), M(round), M(trunc), M(rint), M(nearbyint), M(erf), M(erfc), M(tgamma), M(lgamma), M(logb),
    M(pow), M(atan2), M(fmod), M(hypot), M(fmin), M(fmax), M(copysign), M(remainder), M(fdim), M(nextafter),
    M(ldexp), M(scalbn), M(frexp), M(modf), M(fma), M(lround), M(lrint), M(sincos), M(nan), M(isinf), M(isnan),
    WINAPK_SHIM("llround", m_llround),
    WINAPK_SHIM("llrint", m_llrint),
    WINAPK_SHIM("llroundf", m_llroundf),
    WINAPK_SHIM("llrintf", m_llrintf),
    WINAPK_SHIM("lroundl", m_lroundl),
    WINAPK_SHIM("powl", m_powl),
    WINAPK_SHIM("fmodl", m_fmodl),
    WINAPK_SHIM("atan2l", m_atan2l),
    WINAPK_SHIM("sqrtl", m_sqrtl),
    WINAPK_SHIM("expl", m_expl),
    WINAPK_SHIM("exp2l", m_exp2l),
    WINAPK_SHIM("logl", m_logl),
    WINAPK_SHIM("log2l", m_log2l),
    WINAPK_SHIM("log10l", m_log10l),
    WINAPK_SHIM("sinl", m_sinl),
    WINAPK_SHIM("cosl", m_cosl),
    WINAPK_SHIM("tanl", m_tanl),
    WINAPK_SHIM("atanl", m_atanl),
    WINAPK_SHIM("floorl", m_floorl),
    WINAPK_SHIM("ceill", m_ceill),
    WINAPK_SHIM("truncl", m_truncl),
    WINAPK_SHIM("roundl", m_roundl),
    WINAPK_SHIM("fabsl", m_fabsl),
    WINAPK_SHIM("ldexpl", m_ldexpl),
    WINAPK_SHIM("llroundl", m_llroundl),
    WINAPK_SHIM("lrintl", m_lrintl),
    WINAPK_SHIM("llrintl", m_llrintl),
    WINAPK_SHIM("ilogb", m_ilogb),
    WINAPK_SHIM("finite", m_finite),
    WINAPK_SHIM("__isinf", m_isinf),
    WINAPK_SHIM("__isnan", m_isnan),
    WINAPK_SHIM("__isfinite", m_finite),
};

#undef M

}  // namespace

}  // namespace winapk::bionic

namespace winapk {

void property_set(const char* name, const char* value) {
    if (!name) return;
    std::lock_guard<std::mutex> hold(bionic::g_props_lock);
    if (value) bionic::props()[name] = value;
    else bionic::props().erase(name);
}

const char* bionic_getenv(const char* name) {
    if (!name) return nullptr;
    std::lock_guard<std::mutex> hold(bionic::g_env_lock);
    auto it = bionic::env().find(name);
    return it == bionic::env().end() ? nullptr : bionic::intern(it->second);
}

int bionic_setenv(const char* name, const char* value, int overwrite) {
    if (!name || !*name || std::strchr(name, '=') || !value) return -bionic::kEINVAL;
    std::lock_guard<std::mutex> hold(bionic::g_env_lock);
    auto& e = bionic::env();
    if (overwrite || e.find(name) == e.end()) {
        e[name] = value;
        bionic::rebuild_environ();
    }
    return 0;
}

int bionic_unsetenv(const char* name) {
    if (!name || !*name || std::strchr(name, '=')) return -bionic::kEINVAL;
    std::lock_guard<std::mutex> hold(bionic::g_env_lock);
    if (bionic::env().erase(name)) bionic::rebuild_environ();
    return 0;
}

}  // namespace winapk

namespace winapk::bionic {
ShimTable misc_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

void say_once(std::atomic<bool>& said, const char* call, const char* what) {
    if (said.exchange(true)) return;
    std::fprintf(stderr, "[win.apk] %s: %s\n", call, what);
    std::fflush(stderr);
}
}  // namespace winapk::bionic
