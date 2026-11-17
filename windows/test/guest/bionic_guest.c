// bionic_guest.c -- guest-side test of the Windows Bionic shim (src/bionic/).
//
// Built like the other test guests (build_guest.sh): plain clang for
// aarch64-linux-android, no NDK, the libc imports declared by hand. Every check goes through the shim exactly as
// NDK code does, the calling convention and Bionic's layouts included.
// bionic_test(dir) returns the number of failed checks and prints one line per
// check; `dir` is a scratch directory.

typedef unsigned long size_t;
typedef long ssize_t;
typedef long off_t;
typedef int wchar_t;  // 32-bit on Android

// arm64 values: system call numbers, two of open's flags, struct stat and
// struct epoll_event (unpacked) differ from x86_64 (src/arm64/arm64_abi.cpp).
enum { kSysMmap = 222, kSysMunmap = 215, kSysMprotect = 226, kSysGettid = 178, kSysFutex = 98,
       kSysSchedGetaffinity = 123, kODirectory = 040000, kODirect = 0200000 };
struct stat { unsigned long dev, ino; unsigned mode, nlink, uid, gid; unsigned long rdev, pad1;
              long size; int blksize, pad2; long blocks, atime, atime_ns, mtime, mtime_ns, ctime, ctime_ns;
              unsigned unused[2]; };
#define WINAPK_EPOLL_PACKED
_Static_assert(sizeof(struct stat) == 128, "Bionic's struct stat on arm64");

int printf(const char*, ...);
int snprintf(char*, size_t, const char*, ...);
int sprintf(char*, const char*, ...);
int asprintf(char**, const char*, ...);
int sscanf(const char*, const char*, ...);
int strcmp(const char*, const char*);
size_t strlen(const char*);
char* strchr(const char*, int);
char* strstr(const char*, const char*);
char* strdup(const char*);
size_t strlcpy(char*, const char*, size_t);
int strcasecmp(const char*, const char*);
long strtol(const char*, char**, int);
unsigned long strtoul(const char*, char**, int);
double strtod(const char*, char**);
int atoi(const char*);
void* malloc(size_t);
void free(void*);
void* memset(void*, int, size_t);
void* memcpy(void*, const void*, size_t);
int memcmp(const void*, const void*, size_t);
int* __errno(void);
char* strerror(int);
int isdigit(int);
int toupper(int);
size_t wcslen(const wchar_t*);

typedef struct { int __private[10]; } pthread_mutex_t;
typedef struct { int __private[12]; } pthread_cond_t;
typedef long pthread_t;
typedef int pthread_key_t;
typedef int pthread_once_t;
int pthread_create(pthread_t*, const void*, void* (*)(void*), void*);
int pthread_join(pthread_t, void**);
int pthread_mutex_lock(pthread_mutex_t*);
int pthread_mutex_unlock(pthread_mutex_t*);
int pthread_mutex_trylock(pthread_mutex_t*);
int pthread_cond_wait(pthread_cond_t*, pthread_mutex_t*);
int pthread_cond_signal(pthread_cond_t*);
int pthread_key_create(pthread_key_t*, void (*)(void*));
int pthread_setspecific(pthread_key_t, const void*);
void* pthread_getspecific(pthread_key_t);
int pthread_once(pthread_once_t*, void (*)(void));
pthread_t pthread_self(void);

int open(const char*, int, ...);
int close(int);
int fsync(int);
int fdatasync(int);
ssize_t read(int, void*, size_t);
ssize_t write(int, const void*, size_t);
off_t lseek(int, off_t, int);
int posix_fallocate(int, off_t, off_t);
int ftruncate(int, off_t);
int fallocate(int, int, off_t, off_t);
int unlink(const char*);
int mkdir(const char*, unsigned);
int rmdir(const char*);
char* getcwd(char*, size_t);
int chdir(const char*);
int stat(const char*, struct stat*);
typedef struct DIR DIR;
struct dirent { unsigned long d_ino; long d_off; unsigned short d_reclen; unsigned char d_type; char d_name[256]; };
DIR* opendir(const char*);
DIR* fdopendir(int);
int dirfd(DIR*);
int openat(int, const char*, int, ...);
int __openat_2(int, const char*, int);
int fstatat(int, const char*, struct stat*, int);
int mkdirat(int, const char*, unsigned);
int strncmp(const char*, const char*, size_t);
struct dirent* readdir(DIR*);
int closedir(DIR*);
typedef struct FILE FILE;
FILE* fopen(const char*, const char*);
int fputs(const char*, FILE*);
int fprintf(FILE*, const char*, ...);
int fclose(FILE*);
char* fgets(char*, int, FILE*);

struct timespec { long tv_sec; long tv_nsec; };
int clock_gettime(int, struct timespec*);
double difftime(long, long);
int getentropy(void*, size_t);
int nanosleep(const struct timespec*, struct timespec*);
int __system_property_get(const char*, char*);
char* getenv(const char*);
int setenv(const char*, const char*, int);
int putenv(char*);
void* mmap(void*, size_t, int, int, int, long);
int munmap(void*, size_t);
int mprotect(void*, size_t, int);
extern char** environ;
long sysconf(int);

#define EXPORT __attribute__((visibility("default")))

static int failures;

static void check(const char* what, int ok, const char* got) {
    printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", what, got ? ": " : "", got ? got : "");
    if (!ok) ++failures;
}

static void check_str(const char* what, const char* got, const char* want) {
    check(what, strcmp(got, want) == 0, got);
}

// ---- printf -----------------------------------------------------------------
static void test_printf(void) {
    char b[256];
    snprintf(b, sizeof b, "%d %u %x %ld %lld", -42, 3000000000u, 0xbeef, -9000000000L, 123456789012LL);
    check_str("snprintf integers", b, "-42 3000000000 beef -9000000000 123456789012");
    snprintf(b, sizeof b, "%.3f|%8.2f|%-6.1f|%e|%g", 3.14159, 2.5, 1.25, 12345.678, 0.0001);
    check_str("snprintf doubles", b, "3.142|    2.50|1.2   |1.234568e+04|0.0001");
    // More than eight doubles and six integers: the rest go on the stack.
    snprintf(b, sizeof b, "%d %d %d %d %d %d %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f %.0f", 1, 2, 3, 4, 5, 6,
             1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0);
    check_str("snprintf stack arguments", b, "1 2 3 4 5 6 1 2 3 4 5 6 7 8 9 10");
    snprintf(b, sizeof b, "[%s] [%5s] [%-5s] [%.2s] [%c] [%%] [%s]", "abc", "ab", "ab", "abcdef", 'z', (char*)0);
    check_str("snprintf strings", b, "[abc] [   ab] [ab   ] [ab] [z] [%] [(null)]");
    snprintf(b, sizeof b, "%*d|%-*d|%.*f", 5, 7, 4, 8, 2, 1.0);
    check_str("snprintf star width/precision", b, "    7|8   |1.00");
    static const wchar_t w[] = {'w', 'i', 'd', 'e', 0x5d0, 0};
    snprintf(b, sizeof b, "%ls", w);
    check_str("snprintf %ls (32-bit wchar_t)", b, "wide\xd7\x90");
    snprintf(b, sizeof b, "%p", (void*)0x1234);
    check_str("snprintf %p", b, "0x1234");
    int n = 0;
    snprintf(b, sizeof b, "abc%nde", &n);
    check("snprintf %n", n == 3, b);
    const int len = snprintf(b, 5, "123456789");
    check("snprintf truncates and counts", len == 9 && strcmp(b, "1234") == 0, b);
    char* a = 0;
    asprintf(&a, "%s-%d", "x", 99);
    check_str("asprintf", a ? a : "(null)", "x-99");
    free(a);
    // The way engines read GL_VERSION, plus a 64-bit %ld, a set and a skip.
    int major = 0, minor = 0;
    long big = 0;
    float f = 0;
    char word[16] = {0};
    const int got = sscanf("OpenGL ES 3.0 (ANGLE) 9000000000 2.5 abc123", "OpenGL ES %d.%d %*s %ld %f %[a-z]", &major,
                           &minor, &big, &f, word);
    snprintf(b, sizeof b, "%d: %d.%d %ld %.1f %s", got, major, minor, big, f, word);
    check_str("sscanf", b, "5: 3.0 9000000000 2.5 abc");
}

// ---- strings ------------------------------------------------------------------
static void test_strings(void) {
    char b[64];
    check("strtol 64-bit", strtol("-9000000000", 0, 10) == -9000000000L, 0);
    check("strtoul hex", strtoul("ffffffffff", 0, 16) == 0xffffffffffUL, 0);
    check("strtod", strtod("2.5", 0) == 2.5, 0);
    check("atoi", atoi("1234") == 1234, 0);
    check("strchr/strstr", strchr("hello", 'l') && strstr("hello", "llo"), 0);
    check("strcasecmp", strcasecmp("HeLLo", "hello") == 0, 0);
    char* d = strdup("dup");
    check_str("strdup", d, "dup");
    free(d);
    strlcpy(b, "truncated", 6);
    check_str("strlcpy", b, "trunc");
    check("ctype", isdigit('7') && !isdigit('x') && toupper('q') == 'Q', 0);
    static const wchar_t w[] = {'a', 'b', 'c', 0};
    check("wcslen (32-bit)", wcslen(w) == 3, 0);
    check_str("strerror(ENOENT), Linux numbering", strerror(2), "No such file or directory");
}

// ---- threads --------------------------------------------------------------------
static pthread_mutex_t g_mu;  // zero: PTHREAD_MUTEX_INITIALIZER
static pthread_cond_t g_cv;
static int g_counter, g_ready;
static pthread_key_t g_key;
static int g_dtor_runs;
static pthread_once_t g_once;
static int g_once_runs;

static void once_fn(void) { ++g_once_runs; }
static void key_dtor(void* v) { (void)v; ++g_dtor_runs; }

static void* worker(void* arg) {
    pthread_setspecific(g_key, arg);
    pthread_once(&g_once, once_fn);
    for (int i = 0; i < 10000; ++i) {
        pthread_mutex_lock(&g_mu);
        ++g_counter;
        pthread_mutex_unlock(&g_mu);
    }
    pthread_mutex_lock(&g_mu);
    ++g_ready;
    pthread_cond_signal(&g_cv);
    pthread_mutex_unlock(&g_mu);
    return (void*)((long)arg * 2);
}

// ELF TLS (built with -fno-emulated-tls): a hidden variable reached through the
// local-dynamic model, an exported one through general-dynamic, and a .tbss
// array. Every thread must start from the initial values.
static __thread int t_hidden = 11;
__attribute__((visibility("default"))) __thread long t_exported = 22;
static __thread int t_zeroed[64];

static void* tls_worker(void* arg) {
    long id = (long)arg;
    int fresh = t_hidden == 11 && t_exported == 22 && t_zeroed[63] == 0;
    t_hidden += (int)id;
    t_exported *= id;
    t_zeroed[63] = (int)id;
    for (volatile int i = 0; i < 1000; ++i) {}
    int kept = t_hidden == 11 + id && t_exported == 22 * id && t_zeroed[63] == id;
    return (void*)(long)(fresh && kept);
}

static void test_tls(void) {
    check("TLS initial values on the main thread", t_hidden == 11 && t_exported == 22 && t_zeroed[0] == 0, 0);
    t_hidden = 100;
    pthread_t t[3];
    for (long i = 0; i < 3; ++i) pthread_create(&t[i], 0, tls_worker, (void*)(i + 2));
    long ok = 1;
    for (int i = 0; i < 3; ++i) {
        void* r = 0;
        pthread_join(t[i], &r);
        ok &= (long)r;
    }
    check("TLS is per thread (3 threads, .tdata and .tbss)", ok == 1, 0);
    check("TLS of the main thread is untouched by other threads", t_hidden == 100 && t_exported == 22, 0);
}

// UTF-8 conversion, locales and raw syscalls (libc++ and Chromium use them).
size_t mbrtowc(wchar_t*, const char*, size_t, void*);
size_t wcrtomb(char*, wchar_t, void*);
size_t mbstowcs(wchar_t*, const char*, size_t);
char* setlocale(int, const char*);
void* newlocale(int, const char*, void*);
void* uselocale(void*);
long syscall(long, ...);
int gettid(void);
struct futex_timespec { long tv_sec, tv_nsec; };

static void test_locale_and_syscall(void) {
    wchar_t c = 0;
    unsigned st = 0;
    check("mbrtowc decodes a 3-byte UTF-8 character", mbrtowc(&c, "\xe2\x82\xac!", 4, &st) == 3 && c == 0x20ac, 0);
    st = 0;
    int partial = mbrtowc(&c, "\xe2", 1, &st) == (size_t)-2;
    check("mbrtowc continues a split sequence", partial && mbrtowc(&c, "\x82\xac", 2, &st) == 2 && c == 0x20ac, 0);
    check("mbrtowc rejects an illegal byte", mbrtowc(&c, "\xff", 1, 0) == (size_t)-1 && *__errno() == 84, 0);
    char b[8] = {0};
    check("wcrtomb encodes U+1F600 as 4 bytes",
          wcrtomb(b, 0x1f600, 0) == 4 && (unsigned char)b[0] == 0xf0 && (unsigned char)b[3] == 0x80, 0);
    wchar_t w[8];
    check("mbstowcs converts a string", mbstowcs(w, "a\xc3\xa9z", 8) == 3 && w[1] == 0xe9 && w[3] == 0, 0);
    void* loc = newlocale(63, "C", 0);
    check("setlocale and newlocale/uselocale for C and C.UTF-8",
          setlocale(6, "") != 0 && loc != 0 && uselocale(loc) == (void*)-1 && uselocale(0) == loc, 0);
    check("syscall(gettid)", syscall(kSysGettid) == gettid(), 0);
    unsigned word = 5;
    struct futex_timespec ts = {0, 2000000};
    check("futex WAIT on a changed value fails with EAGAIN",
          syscall(kSysFutex, &word, 128, 4, 0) == -1 && *__errno() == 11, 0);
    check("futex WAIT times out with ETIMEDOUT", syscall(kSysFutex, &word, 128, 5, &ts) == -1 && *__errno() == 110, 0);
    check("futex WAKE succeeds", syscall(kSysFutex, &word, 129, 1) >= 0, 0);
    check("an unknown syscall fails with ENOSYS", syscall(9999) == -1 && *__errno() == 38, 0);
    // mozjemalloc (Gecko) maps its chunks by raw call: SYS_mmap with the offset as a sixth
    // argument, then SYS_munmap of the misaligned head.
    char* raw = (char*)syscall(kSysMmap, 0L, 1L << 20, 3L, 0x22L, -1L, 0L);
    int mapped = raw != (char*)-1 && raw != 0 && raw[1 << 19] == 0;
    if (mapped) raw[1 << 19] = 3;
    check("syscall(SYS_mmap) maps zeroed, writable memory", mapped && raw[1 << 19] == 3, 0);
    check("syscall(SYS_munmap) of a mapping's head leaves the rest",
          mapped && syscall(kSysMunmap, raw, 65536L) == 0 && raw[1 << 19] == 3, 0);
    check("syscall(SYS_mprotect), then SYS_munmap of the whole mapping",
          mapped && syscall(kSysMprotect, raw + 65536, 65536L, 1L) == 0 && syscall(kSysMunmap, raw, 1L << 20) == 0, 0);
}

// Pipes, eventfd, poll and epoll: the wake-up machinery of every event loop.
int pipe(int[2]);
int eventfd(unsigned, int);
struct pollfd { int fd; short events, revents; };
int poll(struct pollfd*, unsigned long, int);
typedef struct { unsigned long bits[16]; } guest_fd_set;
struct guest_timeval { long sec, usec; };
int select(int, guest_fd_set*, guest_fd_set*, guest_fd_set*, struct guest_timeval*);
#define GUEST_FD_ISSET(fd, s) (((s)->bits[(fd) / 64] >> ((fd) % 64)) & 1)
int epoll_create1(int);
struct WINAPK_EPOLL_PACKED epoll_event { unsigned events; unsigned long data; };
int epoll_ctl(int, int, int, struct epoll_event*);
int epoll_wait(int, struct epoll_event*, int, int);
int fcntl(int, int, ...);
int usleep(unsigned);
struct itimerspec { long interval_sec, interval_nsec, value_sec, value_nsec; };
int timerfd_create(int, int);
int timerfd_settime(int, int, const struct itimerspec*, struct itimerspec*);
int timerfd_gettime(int, struct itimerspec*);

static int g_wake_fd;
static void* waker(void* arg) {
    (void)arg;
    usleep(20000);
    unsigned long one = 1;
    write(g_wake_fd, &one, 8);
    return 0;
}

size_t malloc_usable_size(const void*);
int posix_memalign(void**, size_t, size_t);
void* realloc(void*, size_t);
static void test_malloc_usable_size(void) {
    void* p = malloc(100);
    check("malloc_usable_size of a malloc block is at least its size", p && malloc_usable_size(p) >= 100, 0);
    free(p);
    void* q = 0;
    check("malloc_usable_size of a posix_memalign(64) block", posix_memalign(&q, 64, 200) == 0 && q &&
              malloc_usable_size(q) >= 200, 0);
    // A size the over-aligned path cannot pad without wrapping, and a realloc that fails: the old block is kept.
    const size_t huge = (size_t)-1 - 8;
    void* r = 0;
    check("posix_memalign of a size that would wrap is ENOMEM", posix_memalign(&r, 64, huge) == 12 && !r, 0);
    if (q) memset(q, 0x5a, 200);
    *__errno() = 0;
    check("a failed realloc of an aligned block is ENOMEM", q && realloc(q, huge) == 0 && *__errno() == 12, 0);
    check("a failed realloc keeps the aligned block",
          q && ((unsigned char*)q)[199] == 0x5a && malloc_usable_size(q) >= 200, 0);
    free(q);
}

static void test_event_fds(void) {
    int p[2];
    char b[16] = {0};
    check("pipe write/read", pipe(p) == 0 && write(p[1], "ping", 4) == 4 && read(p[0], b, 16) == 4 && !strcmp(b, "ping"), b);
    struct pollfd pf = {p[0], 1, 0};
    check("poll: empty pipe times out", poll(&pf, 1, 10) == 0, 0);
    guest_fd_set rs = {{0}};
    rs.bits[p[0] / 64] |= 1UL << (p[0] % 64);
    struct guest_timeval tv = {0, 10000};
    check("select: empty pipe times out, clears the set and the timeout",
          select(p[0] + 1, &rs, 0, 0, &tv) == 0 && !GUEST_FD_ISSET(p[0], &rs) && tv.sec == 0 && tv.usec == 0, 0);
    write(p[1], "x", 1);
    check("poll: pipe readable", poll(&pf, 1, 100) == 1 && (pf.revents & 1), 0);
    rs.bits[p[0] / 64] |= 1UL << (p[0] % 64);
    tv = (struct guest_timeval){5, 0};
    check("select: pipe readable, the timeout keeps the time left",
          select(p[0] + 1, &rs, 0, 0, &tv) == 1 && GUEST_FD_ISSET(p[0], &rs) && tv.sec >= 4 &&
              tv.sec * 1000000 + tv.usec <= 5000000, 0);
    read(p[0], b, 1);
    fcntl(p[0], 4, 04000);  // F_SETFL O_NONBLOCK
    check("non-blocking empty read is EAGAIN", read(p[0], b, 1) == -1 && *__errno() == 11, 0);
    close(p[1]);
    check("read after the writer closed is EOF", read(p[0], b, 1) == 0, 0);
    close(p[0]);

    const int efd = eventfd(0, 0);
    const int ep = epoll_create1(0);
    struct epoll_event ev = {1, 42};
    check("epoll_ctl ADD an eventfd", efd >= 0 && ep >= 0 && epoll_ctl(ep, 1, efd, &ev) == 0, 0);
    g_wake_fd = efd;
    pthread_t t;
    pthread_create(&t, 0, waker, 0);
    struct epoll_event out[4];
    const int n = epoll_wait(ep, out, 4, 2000);
    check("epoll_wait wakes when another thread writes the eventfd", n == 1 && out[0].data == 42 && (out[0].events & 1), 0);
    unsigned long v = 0;
    check("eventfd read returns the count", read(efd, &v, 8) == 8 && v == 1, 0);
    pthread_join(t, 0);
    check("epoll_wait times out once drained", epoll_wait(ep, out, 4, 10) == 0, 0);
    close(ep);
    close(efd);

    // timerfd: Flutter's message loop sleeps on one in epoll_wait.
    const int tfd = timerfd_create(1, 04000);  // CLOCK_MONOTONIC, TFD_NONBLOCK
    const int ep2 = epoll_create1(0);
    struct epoll_event tev = {1, 7};
    check("timerfd_create + epoll_ctl ADD", tfd >= 0 && ep2 >= 0 && epoll_ctl(ep2, 1, tfd, &tev) == 0, 0);
    check("a disarmed timerfd reads EAGAIN", read(tfd, &v, 8) == -1 && *__errno() == 11, 0);
    struct itimerspec its = {0, 0, 0, 30000000};  // once, in 30 ms
    check("timerfd_settime", timerfd_settime(tfd, 0, &its, 0) == 0, 0);
    struct itimerspec left = {0, 0, 0, 0};
    check("timerfd_gettime shows it armed", timerfd_gettime(tfd, &left) == 0 && left.value_sec == 0 &&
              left.value_nsec > 0 && left.value_nsec <= 30000000, 0);
    check("epoll_wait sleeps until the timer is not due", epoll_wait(ep2, out, 4, 5) == 0, 0);
    const int tn = epoll_wait(ep2, out, 4, 2000);
    check("epoll_wait wakes when the timer fires", tn == 1 && out[0].data == 7, 0);
    v = 0;
    check("timerfd read returns one expiration", read(tfd, &v, 8) == 8 && v == 1, 0);
    check("a one-shot timerfd is disarmed after it fired", read(tfd, &v, 8) == -1 && *__errno() == 11, 0);
    close(ep2);
    close(tfd);
}

// Sockets on Winsock: a loopback TCP exchange, IPv6 addresses, name lookup.
struct sockaddr_in { unsigned short family, port; unsigned addr; char zero[8]; };
struct sockaddr_in6 { unsigned short family, port; unsigned flowinfo; unsigned char addr[16]; unsigned scope; };
struct addrinfo { int flags, family, socktype, protocol; unsigned addrlen; char* canonname; void* addr; struct addrinfo* next; };
int socket(int, int, int);
int bind(int, const void*, unsigned);
int listen(int, int);
int accept(int, void*, unsigned*);
int connect(int, const void*, unsigned);
int getsockname(int, void*, unsigned*);
int setsockopt(int, int, int, const void*, unsigned);
int getsockopt(int, int, int, void*, unsigned*);
long send(int, const void*, size_t, int);
long recv(int, void*, size_t, int);
int socketpair(int, int, int, int[2]);
int getaddrinfo(const char*, const char*, const struct addrinfo*, struct addrinfo**);
void freeaddrinfo(struct addrinfo*);
int inet_pton(int, const char*, void*);
const char* inet_ntop(int, const void*, char*, unsigned);

static int g_listener;
static void* echo_server(void* arg) {
    (void)arg;
    int c = accept(g_listener, 0, 0);
    char b[32];
    long n = recv(c, b, sizeof b, 0);
    if (n > 0) send(c, b, (size_t)n, 0);
    close(c);
    return 0;
}

static void test_sockets(void) {
    g_listener = socket(2, 1, 0);  // AF_INET, SOCK_STREAM
    int one = 1;
    struct sockaddr_in a = {2, 0, 0x0100007f, {0}};  // 127.0.0.1, any port
    unsigned len = sizeof a;
    check("socket/setsockopt(SO_REUSEADDR)/bind/listen",
          g_listener >= 0 && setsockopt(g_listener, 1, 2, &one, 4) == 0 && bind(g_listener, &a, sizeof a) == 0 &&
              listen(g_listener, 1) == 0 && getsockname(g_listener, &a, &len) == 0 && a.family == 2 && a.port != 0,
          0);
    pthread_t t;
    pthread_create(&t, 0, echo_server, 0);
    int c = socket(2, 1, 0);
    char b[32] = {0};
    check("connect + send + recv over loopback TCP",
          connect(c, &a, sizeof a) == 0 && send(c, "echo", 4, 0) == 4 && recv(c, b, sizeof b, 0) == 4 && !strcmp(b, "echo"), b);
    struct pollfd pf = {c, 1, 0};
    check("poll on a socket: readable at EOF", poll(&pf, 1, 1000) == 1 && read(c, b, 1) == 0, 0);
    int err = -1;
    len = 4;
    check("getsockopt(SO_ERROR) is 0", getsockopt(c, 1, 4, &err, &len) == 0 && err == 0, 0);
    close(c);
    pthread_join(t, 0);
    close(g_listener);

    int sv[2];
    check("socketpair passes data both ways",
          socketpair(1, 1, 0, sv) == 0 && write(sv[0], "a", 1) == 1 && read(sv[1], b, 1) == 1 && b[0] == 'a' &&
              write(sv[1], "b", 1) == 1 && read(sv[0], b, 1) == 1 && b[0] == 'b', 0);
    close(sv[0]);
    close(sv[1]);

    struct sockaddr_in6 a6;
    char text[64];
    check("inet_pton/inet_ntop with Linux's AF_INET6 (10)",
          inet_pton(10, "::1", a6.addr) == 1 && inet_ntop(10, a6.addr, text, sizeof text) && !strcmp(text, "::1"), text);
    struct addrinfo hints = {4, 2, 1, 0, 0, 0, 0, 0};  // AI_NUMERICHOST, AF_INET, SOCK_STREAM
    struct addrinfo* res = 0;
    int ok = getaddrinfo("127.0.0.1", "80", &hints, &res) == 0 && res && res->family == 2 && res->addrlen == 16 &&
             ((struct sockaddr_in*)res->addr)->port == 0x5000;
    check("getaddrinfo (numeric) in bionic's addrinfo layout", ok, 0);
    if (res) freeaddrinfo(res);
}

// Datagrams, multicast, interface names, and epoll on sockets: what a
// Flutter or Rust app's discovery and event loop are made of. Loopback only.
long sendto(int, const void*, size_t, int, const void*, unsigned);
long recvfrom(int, void*, size_t, int, void*, unsigned*);
struct ifaddrs { struct ifaddrs* next; char* name; unsigned flags; void* addr; void* netmask; void* broadaddr; void* data; };
int getifaddrs(struct ifaddrs**);
void freeifaddrs(struct ifaddrs*);
unsigned if_nametoindex(const char*);
char* if_indextoname(unsigned, char*);
struct if_nameindex { unsigned index; char* name; };
struct if_nameindex* if_nameindex(void);
void if_freenameindex(struct if_nameindex*);
struct ip_mreq { unsigned multiaddr, interface; };
// struct group_req: the interface index, padding, a sockaddr_storage of 128 bytes.
struct group_req { unsigned interface; unsigned pad; struct sockaddr_in group; char rest[112]; };
struct linger { int onoff, linger; };

// The errno of a failed call, for the check's line.
static const char* why(int ok) { return ok ? 0 : strerror(*__errno()); }

static long ms_now(int clock) {
    struct timespec t;
    clock_gettime(clock, &t);
    return t.tv_sec * 1000 + t.tv_nsec / 1000000;
}

// A UDP socket on `addr`, any port, which is returned in network order.
static int udp_bound(unsigned addr, unsigned short* port) {
    const int s = socket(2, 2, 0);  // AF_INET, SOCK_DGRAM
    int one = 1;
    struct sockaddr_in a = {2, 0, addr, {0}};
    unsigned len = sizeof a;
    if (s < 0 || setsockopt(s, 1, 2, &one, 4) != 0 || bind(s, &a, sizeof a) != 0 || getsockname(s, &a, &len) != 0) return -1;
    *port = a.port;
    return s;
}

// Sends `text` to group:port and expects it on `rx`. 1 received, 0 not, -1
// the host has no route for multicast (no network at all): nothing to test.
static int multicast_round_trip(int rx, unsigned group, unsigned short port, const char* text) {
    const int tx = socket(2, 2, 0);
    unsigned char ttl = 1, loop = 1;  // one byte each, as BSD code sets them
    struct sockaddr_in to = {2, port, group, {0}};
    int result = 0;
    if (setsockopt(tx, 0, 33, &ttl, 1) != 0 || setsockopt(tx, 0, 34, &loop, 1) != 0) result = 0;
    else if (sendto(tx, text, strlen(text), 0, &to, sizeof to) != (long)strlen(text)) {
        const int e = *__errno();
        result = (e == 101 || e == 113 || e == 100 || e == 99) ? -1 : 0;  // ENETUNREACH, EHOSTUNREACH, ENETDOWN, EADDRNOTAVAIL
    } else {
        struct pollfd pf = {rx, 1, 0};
        char b[32] = {0};
        result = poll(&pf, 1, 2000) == 1 && recvfrom(rx, b, sizeof b, 0, 0, 0) == (long)strlen(text) && !strcmp(b, text);
    }
    close(tx);
    return result;
}

static int g_late_fd;
static void* late_writer(void* arg) {
    (void)arg;
    usleep(100000);
    write(g_late_fd, "w", 1);
    return 0;
}

static void test_network(void) {
    // A datagram over loopback.
    unsigned short port = 0;
    const int rx = udp_bound(0x0100007f, &port);
    const int tx = socket(2, 2, 0);
    struct sockaddr_in to = {2, port, 0x0100007f, {0}}, from = {0, 0, 0, {0}};
    unsigned from_len = sizeof from;
    char b[32] = {0};
    struct pollfd pf = {rx, 1, 0};
    check("UDP: sendto, poll and recvfrom over loopback",
          rx >= 0 && tx >= 0 && sendto(tx, "dgram", 5, 0, &to, sizeof to) == 5 && poll(&pf, 1, 2000) == 1 &&
              recvfrom(rx, b, sizeof b, 0, &from, &from_len) == 5 && !strcmp(b, "dgram") && from.family == 2 &&
              from.addr == 0x0100007f, b);

    // Options: mapped, translated, accepted as tuning, refused.
    int one = 1, v = 0;
    unsigned len = 4;
    check("setsockopt(SO_REUSEPORT) on a datagram socket is SO_REUSEADDR",
          setsockopt(tx, 1, 15, &one, 4) == 0 && getsockopt(tx, 1, 15, &v, &len) == 0 && v == 1, 0);
    check("setsockopt(SO_PRIORITY) only tunes: accepted", setsockopt(tx, 1, 12, &one, 4) == 0, 0);
    check("setsockopt(SOL_UDP, UDP_SEGMENT) is ENOPROTOOPT, not a silent success",
          setsockopt(tx, 17, 103, &one, 4) == -1 && *__errno() == 92, 0);
    check("setsockopt of an unknown option is ENOPROTOOPT", setsockopt(tx, 1, 250, &one, 4) == -1 && *__errno() == 92, 0);
    const int tcp = socket(2, 1, 0);
    struct linger lin = {1, 7}, lout = {0, 0};
    len = sizeof lout;
    check("SO_LINGER keeps Linux's two ints (on, 7 s)",
          setsockopt(tcp, 1, 13, &lin, sizeof lin) == 0 && getsockopt(tcp, 1, 13, &lout, &len) == 0 && len == 8 &&
              lout.onoff == 1 && lout.linger == 7, 0);
    int idle = 30;
    int ok = setsockopt(tcp, 6, 4, &idle, 4) == 0;
    check("setsockopt(TCP_KEEPIDLE)", ok, why(ok));
    v = -1, len = 4;
    check("getsockopt(SO_DOMAIN) is AF_INET", getsockopt(tcp, 1, 39, &v, &len) == 0 && v == 2, 0);
    close(tcp);
    close(tx);
    close(rx);

    // Interface names and indexes agree with getifaddrs.
    struct ifaddrs* list = 0;
    char name[16] = {0}, back[16] = {0};
    check("getifaddrs lists an interface", getifaddrs(&list) == 0 && list && list->name, 0);
    if (list && list->name) strlcpy(name, list->name, sizeof name);
    if (list) freeifaddrs(list);
    const unsigned index = if_nametoindex(name);
    check("if_nametoindex of a getifaddrs name, and if_indextoname back", index != 0 && if_indextoname(index, back) == back &&
              !strcmp(back, name), name);
    check("if_nametoindex of no interface is 0 (ENODEV)", if_nametoindex("nope7") == 0 && *__errno() == 19, 0);
    check("if_indextoname of no index is NULL (ENXIO)", if_indextoname(0x7ffffff0, back) == 0 && *__errno() == 6, 0);
    struct if_nameindex* all = if_nameindex();
    int listed = 0;
    for (struct if_nameindex* i = all; i && i->index; ++i) listed += i->index == index && !strcmp(i->name, name);
    check("if_nameindex lists it once, and ends with index 0", listed == 1, 0);
    if (all) if_freenameindex(all);

    // Multicast: the IPv4 option, then the protocol-independent one Dart and
    // libuv use, on the default interface.
    const int m = udp_bound(0, &port);
    struct ip_mreq mreq = {0x014dffef, 0};  // 239.255.77.1
    ok = m >= 0 && setsockopt(m, 0, 35, &mreq, sizeof mreq) == 0;
    check("setsockopt(IP_ADD_MEMBERSHIP)", ok, why(ok));
    int got = multicast_round_trip(m, mreq.multiaddr, port, "mreq");
    check(got < 0 ? "SKIP (no multicast route): a datagram to the IP_ADD_MEMBERSHIP group arrives"
                  : "a datagram to the IP_ADD_MEMBERSHIP group arrives", got != 0, 0);
    check("setsockopt(IP_DROP_MEMBERSHIP)", setsockopt(m, 0, 36, &mreq, sizeof mreq) == 0, 0);
    struct group_req greq;
    memset(&greq, 0, sizeof greq);
    greq.group.family = 2;
    greq.group.addr = 0x024dffef;  // 239.255.77.2
    ok = sizeof greq == 136 && setsockopt(m, 0, 42, &greq, sizeof greq) == 0;
    check("setsockopt(MCAST_JOIN_GROUP) with Linux's struct group_req", ok, why(ok));
    got = multicast_round_trip(m, greq.group.addr, port, "greq");
    check(got < 0 ? "SKIP (no multicast route): a datagram to the MCAST_JOIN_GROUP group arrives"
                  : "a datagram to the MCAST_JOIN_GROUP group arrives", got != 0, 0);
    ok = setsockopt(m, 0, 45, &greq, sizeof greq) == 0;
    check("setsockopt(MCAST_LEAVE_GROUP)", ok, why(ok));
    check("MCAST_JOIN_GROUP with a short struct is EINVAL", setsockopt(m, 0, 42, &greq, 16) == -1 && *__errno() == 22, 0);
    close(m);

    // epoll on a connected socket. Edge-triggered "writable" is one event,
    // not one per call: reported level-triggered, an idle Dart or tokio loop
    // never slept.
    int sv[2];
    struct epoll_event out[4];
    const int ep = epoll_create1(0);
    struct epoll_event ev = {0x80000004u, 5};  // EPOLLOUT | EPOLLET
    check("epoll_ctl ADD a socket, EPOLLOUT|EPOLLET", socketpair(1, 1, 0, sv) == 0 && ep >= 0 && epoll_ctl(ep, 1, sv[0], &ev) == 0, 0);
    check("EPOLLOUT|EPOLLET on a connected socket is reported", epoll_wait(ep, out, 4, 1000) == 1 && (out[0].events & 4) &&
              out[0].data == 5, 0);
    long t0 = ms_now(1), cpu0 = ms_now(3);
    int n = epoll_wait(ep, out, 4, 300);
    long waited = ms_now(1) - t0, cpu = ms_now(3) - cpu0;
    char text[64];
    snprintf(text, sizeof text, "%d event(s), %ld ms, %ld ms of processor time", n, waited, cpu);
    check("...once: the next epoll_wait sleeps its whole 300 ms", n == 0 && waited >= 280 && waited < 3000, text);
    check("...and uses no processor time doing so", cpu < 100, text);

    // Edge-triggered "readable": an event when data arrives, none while it
    // lies unread, one again after a read.
    ev.events = 0x80000001u;  // EPOLLIN | EPOLLET
    check("EPOLL_CTL_MOD to EPOLLIN|EPOLLET: nothing to read, no event", epoll_ctl(ep, 3, sv[0], &ev) == 0 &&
              epoll_wait(ep, out, 4, 30) == 0, 0);
    write(sv[1], "1", 1);
    check("EPOLLIN|EPOLLET: an event when data arrives", epoll_wait(ep, out, 4, 2000) == 1 && (out[0].events & 1), 0);
    check("EPOLLIN|EPOLLET: none again while the data is unread", epoll_wait(ep, out, 4, 50) == 0, 0);
    check("...and none after it was read", read(sv[0], b, 1) == 1 && epoll_wait(ep, out, 4, 50) == 0, 0);
    write(sv[1], "2", 1);
    check("EPOLLIN|EPOLLET: an event for the next arrival", epoll_wait(ep, out, 4, 2000) == 1 && (out[0].events & 1), 0);
    read(sv[0], b, 1);

    // A blocking wait on a socket wakes when data arrives, not on a period.
    g_late_fd = sv[1];
    pthread_t t;
    pthread_create(&t, 0, late_writer, 0);
    t0 = ms_now(1);
    n = epoll_wait(ep, out, 4, 5000);
    waited = ms_now(1) - t0;
    snprintf(text, sizeof text, "%d event(s) after %ld ms", n, waited);
    check("epoll_wait on a socket wakes when another thread sends", n == 1 && waited >= 50 && waited < 3000, text);
    pthread_join(t, 0);
    read(sv[0], b, 1);
    pf.fd = sv[0];
    t0 = ms_now(1), cpu0 = ms_now(3);
    n = poll(&pf, 1, 300);
    waited = ms_now(1) - t0, cpu = ms_now(3) - cpu0;
    snprintf(text, sizeof text, "%d ready, %ld ms, %ld ms of processor time", n, waited, cpu);
    check("poll on an idle socket sleeps its whole 300 ms", n == 0 && waited >= 280 && waited < 3000 && cpu < 100, text);
    pthread_create(&t, 0, late_writer, 0);
    check("poll on a socket wakes when another thread sends", poll(&pf, 1, 5000) == 1 && (pf.revents & 1), 0);
    pthread_join(t, 0);
    read(sv[0], b, 1);

    // EPOLLONESHOT: one report, then nothing until EPOLL_CTL_MOD.
    ev.events = 0x40000001u;  // EPOLLIN | EPOLLONESHOT
    epoll_ctl(ep, 3, sv[0], &ev);
    write(sv[1], "3", 1);
    check("EPOLLONESHOT reports once", epoll_wait(ep, out, 4, 2000) == 1 && epoll_wait(ep, out, 4, 30) == 0, 0);
    check("...and again after EPOLL_CTL_MOD", epoll_ctl(ep, 3, sv[0], &ev) == 0 && epoll_wait(ep, out, 4, 2000) == 1, 0);
    close(sv[0]);
    close(sv[1]);

    // An eventfd, edge-triggered: every write is an event, read or not
    // (Rust's mio wakes its loop this way and never reads).
    const int efd = eventfd(0, 04000);
    unsigned long count = 1;
    ev.events = 0x80000001u;
    ev.data = 9;
    check("epoll_ctl ADD an eventfd, EPOLLIN|EPOLLET", efd >= 0 && epoll_ctl(ep, 1, efd, &ev) == 0, 0);
    write(efd, &count, 8);
    check("eventfd, edge-triggered: a write is one event", epoll_wait(ep, out, 4, 1000) == 1 && out[0].data == 9 &&
              epoll_wait(ep, out, 4, 30) == 0, 0);
    write(efd, &count, 8);
    check("...and a second write, the first unread, is another", epoll_wait(ep, out, 4, 1000) == 1 &&
              epoll_wait(ep, out, 4, 30) == 0, 0);
    close(efd);
    close(ep);
}

// The NDK's ALooper: a callback on a pipe, and a wake from another thread.
void* ALooper_prepare(int);
int ALooper_addFd(void*, int, int, int, int (*)(int, int, void*), void*);
int ALooper_pollOnce(int, int*, int*, void**);
void ALooper_wake(void*);
static int g_looper_hits;
static int on_pipe(int fd, int events, void* data) {
    char c;
    read(fd, &c, 1);
    g_looper_hits += (events & 1) && data == (void*)7;
    return 1;
}
static void* g_looper;
static void* looper_waker(void* arg) {
    (void)arg;
    usleep(20000);
    ALooper_wake(g_looper);
    return 0;
}
static void test_looper(void) {
    int p[2];
    pipe(p);
    g_looper = ALooper_prepare(0);
    check("ALooper_prepare + addFd", g_looper && ALooper_addFd(g_looper, p[0], -2, 1, on_pipe, (void*)7) == 1, 0);
    write(p[1], "z", 1);
    check("pollOnce runs the descriptor's callback", ALooper_pollOnce(100, 0, 0, 0) == -2 && g_looper_hits == 1, 0);
    check("pollOnce times out when idle", ALooper_pollOnce(10, 0, 0, 0) == -3, 0);
    pthread_t t;
    pthread_create(&t, 0, looper_waker, 0);
    check("ALooper_wake from another thread ends a blocking pollOnce", ALooper_pollOnce(2000, 0, 0, 0) == -1, 0);
    pthread_join(t, 0);
    close(p[0]);
    close(p[1]);
}

static void test_threads(void) {
    pthread_key_create(&g_key, key_dtor);
    pthread_t t[4];
    for (long i = 0; i < 4; ++i) pthread_create(&t[i], 0, worker, (void*)(i + 1));
    pthread_mutex_lock(&g_mu);
    while (g_ready < 4) pthread_cond_wait(&g_cv, &g_mu);
    pthread_mutex_unlock(&g_mu);
    long sum = 0;
    for (int i = 0; i < 4; ++i) {
        void* r = 0;
        pthread_join(t[i], &r);
        sum += (long)r;
    }
    char b[96];
    snprintf(b, sizeof b, "counter=%d results=%ld once=%d dtors=%d", g_counter, sum, g_once_runs, g_dtor_runs);
    check("4 threads: mutex, condition, join, once, key destructors",
          g_counter == 40000 && sum == 20 && g_once_runs == 1 && g_dtor_runs == 4, b);
    check("trylock on a held mutex fails",
          pthread_mutex_lock(&g_mu) == 0 && pthread_mutex_trylock(&g_mu) != 0 && pthread_mutex_unlock(&g_mu) == 0, 0);
    check("pthread_self is stable", pthread_self() == pthread_self(), 0);
}

// ---- files --------------------------------------------------------------------------
static void test_files(const char* dir) {
    char sub[512], path[512], line[64];
    snprintf(sub, sizeof sub, "%s/bionic_test_dir", dir);
    mkdir(sub, 0755);
    snprintf(path, sizeof path, "%s/file.txt", sub);
    const int fd = open(path, 01 | 0100 | 01000, 0644);  // O_WRONLY | O_CREAT | O_TRUNC
    check("open(O_CREAT)", fd >= 0, 0);
    check("write", write(fd, "hello\n", 6) == 6, 0);
    close(fd);
    struct stat st;
    check("stat size and regular file", stat(path, &st) == 0 && st.size == 6 && (st.mode & 0170000) == 0100000, 0);
    const int rfd = open(path, 0);
    char buf[8] = {0};
    check("read back", rfd >= 0 && read(rfd, buf, 5) == 5 && strcmp(buf, "hello") == 0, buf);
    check("lseek", lseek(rfd, 0, 2) == 6, 0);
    close(rfd);
    // posix_fallocate grows the file and keeps its contents and position;
    // asking for less than its size changes nothing.
    const int afd = open(path, 02);  // O_RDWR
    check("posix_fallocate", afd >= 0 && lseek(afd, 2, 0) == 2 && posix_fallocate(afd, 0, 65536) == 0, 0);
    check("posix_fallocate keeps the position", lseek(afd, 0, 1) == 2, 0);
    check("posix_fallocate grows the file", stat(path, &st) == 0 && st.size == 65536, 0);
    check("posix_fallocate below the size is a no-op", posix_fallocate(afd, 0, 10) == 0 && stat(path, &st) == 0 &&
          st.size == 65536, 0);
    check("fallocate(FALLOC_FL_PUNCH_HOLE) is EOPNOTSUPP", fallocate(afd, 3, 0, 4096) == -1 && *__errno() == 95, 0);
    check("the contents stay", lseek(afd, 0, 0) == 0 && read(afd, buf, 5) == 5 && strcmp(buf, "hello") == 0, buf);
    check("ftruncate back", ftruncate(afd, 6) == 0, 0);
    close(afd);
    FILE* f = fopen(path, "a");
    fprintf(f, "%s %d\n", "line", 2);
    fclose(f);
    f = fopen(path, "r");
    fgets(line, sizeof line, f);
    fgets(line, sizeof line, f);
    fclose(f);
    check_str("fopen/fprintf/fgets", line, "line 2\n");
    int found = 0;
    DIR* d = opendir(sub);
    for (struct dirent* e; d && (e = readdir(d));) found |= strcmp(e->d_name, "file.txt") == 0 && e->d_type == 8;
    if (d) closedir(d);
    check("opendir/readdir", found, 0);
    check("open a missing file sets ENOENT", open("Z:/no/such/file", 0) < 0 && *__errno() == 2, 0);
    check("close of a bad descriptor is EBADF", close(-1) == -1 && *__errno() == 9, 0);
    check("read of a bad descriptor is EBADF", read(-1, &st, 1) == -1 && *__errno() == 9, 0);
    unlink(path);
    rmdir(sub);
    check("unlink + rmdir", stat(sub, &st) != 0, 0);
    // Android's absolute paths land under the android root (the runner sets
    // it to `dir`), and /dev/null is the null device.
    mkdir("/data", 0755);
    f = fopen("/data/mapped.txt", "w");
    if (f) { fputs("mapped\n", f); fclose(f); }
    snprintf(path, sizeof path, "%s/data/mapped.txt", dir);
    line[0] = 0;
    f = fopen(path, "r");
    if (f) { fgets(line, sizeof line, f); fclose(f); }
    check_str("/data/... maps under the android root", line, "mapped\n");
    unlink("/data/mapped.txt");
    rmdir("/data");
    const int nfd = open("/dev/null", 01);
    check("/dev/null accepts writes", nfd >= 0 && write(nfd, "x", 1) == 1, 0);
    if (nfd >= 0) close(nfd);
    // The working directory is rooted, as POSIX code expects (it resolves any
    // other answer against getcwd() again), and chdir takes it back.
    char cwd[1024];
    check("getcwd is rooted, with '/' separators",
          getcwd(cwd, sizeof cwd) == cwd && cwd[0] == '/' && !strchr(cwd, '\\'), cwd);
    check("chdir takes getcwd's answer", chdir(cwd) == 0, 0);
    snprintf(path, sizeof path, "%s/cwd_probe.txt", cwd);
    f = fopen(path, "w");
    if (f) fclose(f);
    check("a path under getcwd's answer opens", f != 0 && stat(path, &st) == 0, path);
    unlink(path);
}

// ---- zlib (libz.so) ---------------------------------------------------------------
// Android's z_stream: uLong is 64 bits (LP64), so it is 112 bytes where Windows
// zlib's is 88 (see src/bionic/zlib.cpp). The constants are zlib.h's: Z_OK 0,
// Z_STREAM_END 1, Z_STREAM_ERROR -2, Z_DATA_ERROR -3, Z_BUF_ERROR -5,
// Z_VERSION_ERROR -6; Z_NO_FLUSH 0, Z_FINISH 4; Z_DEFLATED 8.
typedef struct {
    const unsigned char* next_in;
    unsigned avail_in;
    unsigned long total_in;
    unsigned char* next_out;
    unsigned avail_out;
    unsigned long total_out;
    const char* msg;
    void* state;
    void* (*zalloc)(void*, unsigned, unsigned);
    void (*zfree)(void*, void*);
    void* opaque;
    int data_type;
    unsigned long adler;
    unsigned long reserved;
} z_stream;
const char* zlibVersion(void);
unsigned long zlibCompileFlags(void);
int deflateInit2_(z_stream*, int, int, int, int, int, const char*, int);
int deflate(z_stream*, int);
int deflateEnd(z_stream*);
int inflateInit2_(z_stream*, int, const char*, int);
int inflate(z_stream*, int);
int inflateCopy(z_stream*, z_stream*);
int inflateEnd(z_stream*);
unsigned long crc32(unsigned long, const unsigned char*, unsigned);
unsigned long adler32(unsigned long, const unsigned char*, unsigned);
unsigned long crc32_combine(unsigned long, unsigned long, long);
unsigned long adler32_combine(unsigned long, unsigned long, long);
unsigned long compressBound(unsigned long);
int compress2(unsigned char*, unsigned long*, const unsigned char*, unsigned long, int);
int uncompress(unsigned char*, unsigned long*, const unsigned char*, unsigned long);
// gzFile's public part, which zlib.h's gzgetc() macro reads in place.
struct gzFile_s { unsigned have; unsigned char* next; long pos; };
typedef struct gzFile_s* gzFile;
#define GZGETC(g) ((g)->have ? ((g)->have--, (g)->pos++, *((g)->next)++) : gzgetc(g))
gzFile gzopen(const char*, const char*);
int gzwrite(gzFile, const void*, unsigned);
int gzprintf(gzFile, const char*, ...);
int gzread(gzFile, void*, unsigned);
int gzgetc(gzFile);
long gztell(gzFile);
int gzclose(gzFile);

static int g_z_allocs, g_z_frees;
static void* z_alloc(void* opaque, unsigned items, unsigned size) {
    if (opaque == &g_z_allocs) ++g_z_allocs;
    return malloc((size_t)items * size);
}
static void z_free(void* opaque, void* p) {
    if (opaque == &g_z_allocs) ++g_z_frees;
    free(p);
}

static void test_zlib(const char* dir) {
    static const unsigned char nine[] = "123456789";
    check("crc32(\"123456789\") is CBF43926", crc32(0, nine, 9) == 0xcbf43926UL, 0);
    check("adler32(\"123456789\") is 091E01DE", adler32(1, nine, 9) == 0x091e01deUL, 0);
    check("crc32_combine and adler32_combine (64-bit z_off_t)",
          crc32_combine(crc32(0, nine, 4), crc32(0, nine + 4, 5), 5) == 0xcbf43926UL &&
              adler32_combine(adler32(1, nine, 4), adler32(1, nine + 4, 5), 5) == 0x091e01deUL, 0);
    check("zlibVersion is 1.x", zlibVersion()[0] == '1', zlibVersion());
    const unsigned long flags = zlibCompileFlags();
    check("zlibCompileFlags: 64-bit uLong and z_off_t", ((flags >> 2) & 3) == 2 && ((flags >> 6) & 3) == 2, 0);

    enum { N = 256 * 1024 };
    unsigned char* data = malloc(N);
    unsigned char* packed = malloc(compressBound(N));
    unsigned char* back = malloc(N);
    unsigned char* back2 = malloc(N);
    for (int i = 0; i < N; ++i) data[i] = (unsigned char)("Win.apk zlib "[i % 13] + (i / 4096) % 7);

    // gzip, in 1000-byte output slices, through the guest's own allocator.
    z_stream s;
    memset(&s, 0, sizeof s);
    s.zalloc = z_alloc;
    s.zfree = z_free;
    s.opaque = &g_z_allocs;
    int r = deflateInit2_(&s, 9, 8, 15 + 16, 8, 0, "1.3.1", (int)sizeof s);
    check("deflateInit2_ (gzip) with a 112-byte z_stream", r == 0 && s.state != 0, 0);
    s.next_in = data;
    s.avail_in = N;
    s.next_out = packed;
    while (r == 0 && s.next_out + 1000 <= packed + N) {
        s.avail_out = 1000;
        r = deflate(&s, 4);
    }
    const unsigned long packed_len = s.total_out;
    check("deflate in slices ends with Z_STREAM_END",
          r == 1 && s.total_in == N && packed_len == (unsigned long)(s.next_out - packed) && packed_len < N / 10, 0);
    check("deflate's adler is the data's CRC-32 (gzip)", s.adler == crc32(0, data, N), 0);
    r = deflateEnd(&s);
    check("deflateEnd; the guest's zalloc/zfree got its opaque",
          r == 0 && s.state == 0 && g_z_allocs > 0 && g_z_allocs == g_z_frees, 0);

    // What Telegram's tgnet does with a gzip_packed reply: header auto-detect
    // (15 + 32), one inflate(Z_NO_FLUSH) into a buffer that fits.
    memset(&s, 0, sizeof s);
    r = inflateInit2_(&s, 15 + 32, "1.2.13", (int)sizeof s);
    s.next_in = packed;
    s.avail_in = (unsigned)packed_len;
    s.next_out = back;
    s.avail_out = N;
    if (r == 0) r = inflate(&s, 0);
    check("inflateInit2_ + inflate: Z_STREAM_END and the data",
          r == 1 && s.total_out == N && memcmp(back, data, N) == 0, 0);
    check("inflate's totals and CRC-32", s.total_in == packed_len && s.adler == crc32(0, data, N), 0);
    inflateEnd(&s);

    // Many calls on one stream: 17 bytes in, 511 out at a time.
    memset(&s, 0, sizeof s);
    memset(back, 0, N);
    r = inflateInit2_(&s, 15 + 16, "1.3", (int)sizeof s);
    s.next_in = packed;
    s.next_out = back;
    while (r == 0) {
        s.avail_in = (unsigned)(packed + packed_len - s.next_in);
        if (s.avail_in > 17) s.avail_in = 17;
        s.avail_out = (unsigned)(back + N - s.next_out);
        if (s.avail_out > 511) s.avail_out = 511;
        r = inflate(&s, 0);
    }
    check("inflate in 17/511-byte slices", r == 1 && s.total_out == N && memcmp(back, data, N) == 0, 0);
    inflateEnd(&s);

    // The totals are 64 bits: counters already past 4 GiB keep counting.
    memset(&s, 0, sizeof s);
    r = inflateInit2_(&s, 15 + 16, "1.3.1", (int)sizeof s);
    s.total_in = 0x1fffffff0UL;
    s.total_out = 0x2fffffff8UL;
    s.next_in = packed;
    s.avail_in = (unsigned)packed_len;
    s.next_out = back;
    s.avail_out = N;
    if (r == 0) r = inflate(&s, 4);
    check("total_in/total_out count past 4 GiB",
          r == 1 && s.total_in == 0x1fffffff0UL + packed_len && s.total_out == 0x2fffffff8UL + N, 0);
    inflateEnd(&s);

    // inflateCopy halfway: both streams finish on their own, and the copy's
    // state goes through the guest's allocator too.
    g_z_allocs = g_z_frees = 0;
    memset(&s, 0, sizeof s);
    memset(back, 0, N);
    s.zalloc = z_alloc;
    s.zfree = z_free;
    s.opaque = &g_z_allocs;
    r = inflateInit2_(&s, 15 + 16, "1.3.1", (int)sizeof s);
    s.next_in = packed;
    s.avail_in = (unsigned)(packed_len / 2);
    s.next_out = back;
    s.avail_out = N;
    if (r == 0) r = inflate(&s, 0);
    z_stream t;
    const int copied = r == 0 && inflateCopy(&t, &s) == 0;
    const unsigned long half = s.total_out;
    memcpy(back2, back, half);
    t.next_out = back2 + half;
    s.avail_in = t.avail_in = (unsigned)(packed_len - packed_len / 2);
    const int rs = inflate(&s, 4), rt = copied ? inflate(&t, 4) : -99;
    check("inflateCopy mid-stream; both copies finish",
          copied && t.state != s.state && rs == 1 && rt == 1 && t.total_out == N && memcmp(back, data, N) == 0 &&
              memcmp(back2, data, N) == 0, 0);
    inflateEnd(&s);
    if (copied) inflateEnd(&t);
    check("the copy's allocations are freed too", g_z_allocs > 1 && g_z_allocs == g_z_frees, 0);

    // Wrong stream size (Windows zlib's 88), corrupt input, an ended stream.
    memset(&s, 0, sizeof s);
    check("inflateInit2_ refuses an 88-byte z_stream", inflateInit2_(&s, 15, "1.3.1", 88) == -6, 0);
    r = inflateInit2_(&s, 15, "1.3.1", (int)sizeof s);
    s.next_in = data;
    s.avail_in = 64;
    s.next_out = back;
    s.avail_out = N;
    if (r == 0) r = inflate(&s, 0);
    check("inflate of garbage: Z_DATA_ERROR with a message", r == -3 && s.msg != 0, s.msg);
    inflateEnd(&s);
    check("inflate on an ended stream: Z_STREAM_ERROR", inflate(&s, 0) == -2 && s.state == 0, 0);

    // One-call forms, with 64-bit lengths.
    unsigned long plen = compressBound(N), blen = N;
    r = compress2(packed, &plen, data, N, 6);
    memset(back, 0, N);
    const int ru = uncompress(back, &blen, packed, plen);
    check("compress2 + uncompress", r == 0 && ru == 0 && blen == N && plen < N / 10 && memcmp(back, data, N) == 0, 0);
    blen = 100;
    check("uncompress into a short buffer: Z_BUF_ERROR", uncompress(back, &blen, packed, plen) == -5 && blen == 100, 0);

    // gzip files on an Android path, gzprintf with guest varargs, and the
    // gzgetc() macro reading the host's gzFile.
    gzFile f = gzopen("/zlib_test.gz", "wb");
    const int w = f ? gzwrite(f, data, 5000) : 0;
    const int p = f ? gzprintf(f, "|%d|%s|%.2f|", 42, "gz", 2.5) : 0;
    const int c = f ? gzclose(f) : -1;
    check("gzopen/gzwrite/gzprintf/gzclose", f && w == 5000 && p == 12 && c == 0, 0);
    memset(back, 0, 6000);
    f = gzopen("/zlib_test.gz", "rb");
    const int c0 = f ? gzgetc(f) : -1;
    const int c1 = f ? GZGETC(f) : -1;
    const long at = f ? gztell(f) : -1;
    const int n = f ? gzread(f, back + 2, 6000) : -1;
    if (f) gzclose(f);
    check("gzgetc, the gzgetc() macro and gztell", c0 == data[0] && c1 == data[1] && at == 2, 0);
    check("gzread reads it back",
          n == 5010 && memcmp(back + 2, data + 2, 4998) == 0 && memcmp(back + 5000, "|42|gz|2.50|", 12) == 0, 0);
    char path[512];
    snprintf(path, sizeof path, "%s/zlib_test.gz", dir);
    struct stat st;
    check("the .gz file is under the android root", stat(path, &st) == 0 && st.size > 0 && st.size < 5000, 0);
    unlink("/zlib_test.gz");
    free(data);
    free(packed);
    free(back);
    free(back2);
}

// ---- rand48, statfs, getopt ---------------------------------------------------------
long lrand48(void);
long mrand48(void);
double drand48(void);
void srand48(long);
unsigned long getauxval(unsigned long);
long nrand48(unsigned short[3]);
long jrand48(unsigned short[3]);
unsigned short* seed48(unsigned short[3]);
// Bionic's struct statfs on arm64 (the kernel's: 64-bit fields, f_fsid two ints).
struct statfs { unsigned long f_type, f_bsize, f_blocks, f_bfree, f_bavail, f_files, f_ffree; int f_fsid[2];
                unsigned long f_namelen, f_frsize, f_flags, f_spare[4]; };
int statfs(const char*, struct statfs*);
int fstatfs(int, struct statfs*);
struct option { const char* name; int has_arg; int* flag; int val; };
int getopt(int, char* const*, const char*);
int getopt_long(int, char* const*, const char*, const struct option*, int*);
extern char* optarg;
extern int optind;

static void test_rand48_statfs_getopt(const char* dir) {
    // rand48 is fully specified by POSIX; glibc gives the same values.
    srand48(12345);
    const long l1 = lrand48(), l2 = lrand48(), m3 = mrand48();
    const double d4 = drand48();
    check("srand48, then lrand48/mrand48/drand48",
          l1 == 483889296 && l2 == 1973930609 && m3 == 888376418 && d4 > 0.72477972027 && d4 < 0.72477972028, 0);
    srand48(7);
    mrand48();
    check("mrand48 is signed 32-bit", mrand48() == -1365648288L, 0);
    unsigned short xs[3] = {0x1111, 0x2222, 0x3333};
    const long n = nrand48(xs), j = jrand48(xs);
    check("nrand48/jrand48 step the caller's state", n == 175951553 && j == 1299530545, 0);
    unsigned short seed[3] = {0x330e, 12345, 0};  // what srand48(12345) sets
    check("seed48 returns the previous state and sets the new one", seed48(seed) != 0 && lrand48() == 483889296, 0);

    struct statfs sd, sf;
    char path[512];
    snprintf(path, sizeof path, "%s/statfs_probe.txt", dir);
    const int fd = open(path, 01 | 0100 | 01000, 0644);  // O_WRONLY | O_CREAT | O_TRUNC
    check("statfs on a directory: ext4's magic, 4 KiB blocks, sane counts",
          statfs(dir, &sd) == 0 && sd.f_type == 0xef53 && sd.f_bsize == 4096 && sd.f_frsize == 4096 &&
              sd.f_blocks > 0 && sd.f_bavail <= sd.f_bfree && sd.f_bfree <= sd.f_blocks && sd.f_namelen == 255, 0);
    check("statfs on a file, and fstatfs, see the same filesystem",
          fd >= 0 && statfs(path, &sf) == 0 && sf.f_blocks == sd.f_blocks && fstatfs(fd, &sf) == 0 &&
              sf.f_blocks == sd.f_blocks, 0);
    if (fd >= 0) close(fd);
    unlink(path);
    *__errno() = 0;
    check("statfs of a missing path: ENOENT", statfs("/no/such/dir", &sd) == -1 && *__errno() == 2, 0);

    char* args[] = {"prog", "-a", "-b", "val", "--long=x", "rest", 0};
    static const struct option longs[] = {{"long", 1, 0, 'L'}, {0, 0, 0, 0}};
    char seen[64] = {0};
    int k = 0;
    for (int c; (c = getopt_long(6, args, "ab:", longs, 0)) != -1 && k < 40;) {
        seen[k++] = (char)c;
        if ((c == 'b' || c == 'L') && optarg) {
            seen[k++] = '=';
            for (const char* q = optarg; *q && k < 50; ++q) seen[k++] = *q;
        }
        seen[k++] = ' ';
    }
    check_str("getopt_long and optarg", seen, "a b=val L=x ");
    check("optind stops at the first operand", optind == 5, 0);
    optind = 1;
    char* again[] = {"prog", "-b", "v", 0};
    check("optind = 1 restarts getopt", getopt(3, again, "ab:") == 'b' && strcmp(optarg, "v") == 0 && optind == 3, 0);
}

// ---- the rest -------------------------------------------------------------------------
static void test_misc(void) {
    struct timespec a, b2;
    clock_gettime(1, &a);
    struct timespec ten_ms = {0, 10000000};
    nanosleep(&ten_ms, 0);
    clock_gettime(1, &b2);
    const long ns = (b2.tv_sec - a.tv_sec) * 1000000000L + (b2.tv_nsec - a.tv_nsec);
    check("CLOCK_MONOTONIC advances across nanosleep(10 ms)", ns >= 9000000 && ns < 500000000, 0);
    clock_gettime(0, &a);
    check("CLOCK_REALTIME is after 2020", a.tv_sec > 1577836800L, 0);
    check("difftime returns the difference as a double", difftime(1700000100L, 1700000000L) == 100.0, 0);
    unsigned char entropy[64] = {0};
    int nonzero = 0;
    const int got_entropy = getentropy(entropy, sizeof entropy) == 0;
    for (int i = 0; i < 64; ++i) nonzero |= entropy[i];
    check("getentropy fills the buffer", got_entropy && nonzero, 0);
    check("getentropy refuses more than 256 bytes with EIO",
          getentropy(entropy, 257) == -1 && *__errno() == 5, 0);
    char v[92];
    check("__system_property_get", __system_property_get("ro.product.cpu.abi", v) > 0 && strcmp(v, "arm64-v8a") == 0, v);
    check("getauxval(AT_HWCAP) has FP and ASIMD", (getauxval(16) & 3) == 3, 0);
    setenv("WINAPK_TEST", "yes", 1);
    check("setenv/getenv", getenv("WINAPK_TEST") && strcmp(getenv("WINAPK_TEST"), "yes") == 0, 0);
    static char put[] = "WINAPK_PUT=one=two";
    check("putenv sets a variable; the value starts after the first '='",
          putenv(put) == 0 && getenv("WINAPK_PUT") && strcmp(getenv("WINAPK_PUT"), "one=two") == 0, 0);
    static char bare[] = "WINAPK_BARE";
    check("putenv refuses a string without '=' with EINVAL", putenv(bare) == -1 && *__errno() == 22, 0);
    char* m = mmap(0, 65536, 3, 0x22, -1, 0);  // PROT_READ|WRITE, MAP_PRIVATE|ANONYMOUS
    check("mmap anonymous is zeroed and writable", m != (char*)-1 && m[100] == 0 && (m[100] = 7) == 7, 0);
    munmap(m, 65536);
    // An allocator-style reservation: 8 GiB of PROT_NONE, then one piece
    // opened with mprotect.
    char* big = mmap(0, 1UL << 33, 0, 0x22, -1, 0);
    int opened = big != (char*)-1 && mprotect(big + (1UL << 32), 65536, 3) == 0;
    if (opened) big[(1UL << 32) + 10] = 5;
    check("mmap reserves 8 GiB with PROT_NONE; mprotect commits a piece", opened && big[(1UL << 32) + 10] == 5, 0);
    // The Dart VM's heap: pieces of the reservation mapped in place with
    // MAP_FIXED, read-write and back to PROT_NONE; each mapping is fresh zeros.
    char* page = big != (char*)-1 ? big + (1UL << 33) - (1UL << 19) : (char*)-1;
    char* rw = page != (char*)-1 ? mmap(page, 1UL << 19, 3, 0x32, -1, 0) : (char*)-1;  // MAP_FIXED
    int in_place = rw == page;
    if (in_place) rw[4096] = 9;
    in_place = in_place && rw[4096] == 9 && mmap(page, 1UL << 19, 0, 0x4032, -1, 0) == page &&  // PROT_NONE
               mmap(page, 1UL << 19, 3, 0x32, -1, 0) == page && page[4096] == 0;
    check("mmap MAP_FIXED inside a PROT_NONE reservation maps there, zeroed", in_place, 0);
    if (big != (char*)-1) munmap(big, 1UL << 33);
    int found = 0;
    for (char** e = environ; e && *e; ++e) found |= strcmp(*e, "WINAPK_TEST=yes") == 0;
    check("environ lists the guest environment", found, 0);
    check("sysconf(_SC_PAGESIZE)", sysconf(0x27) == 4096, 0);
}

// What the signal calls set must read back (crash reporters chain to the
// handler they replace), and libgen, fnmatch and mkstemp. Delivery is
// tested in arm64_signal_guest.c. See src/bionic/signal.cpp.
struct guest_sigaction {  // Bionic LP64: sa_flags first (bits/signal_types.h)
    int flags, pad;
    void* handler;
    unsigned long mask;
    void* restorer;
};
struct guest_stack {
    void* sp;
    int flags;
    unsigned long size;
};
int sigemptyset(unsigned long*);
int sigaddset(unsigned long*, int);
int sigismember(const unsigned long*, int);
int sigaction(int, const struct guest_sigaction*, struct guest_sigaction*);
int sigaltstack(const struct guest_stack*, struct guest_stack*);
int sigprocmask(int, const unsigned long*, unsigned long*);
int timer_create(int, void*, void**);
char* dirname(const char*);
char* basename(const char*);
int fnmatch(const char*, const char*, int);
int mkstemp(char*);
static void on_signal(int sig) { (void)sig; }

static void test_signals_and_paths(const char* dir) {
    unsigned long set;
    check("sigemptyset/sigaddset/sigismember",
          sigemptyset(&set) == 0 && sigaddset(&set, 11) == 0 && sigismember(&set, 11) == 1 &&
              sigismember(&set, 6) == 0 && set == (1UL << 10),
          0);
    struct guest_sigaction act = {0x08000000, 0, (void*)on_signal, 0, 0}, old;
    memset(&old, 0x55, sizeof old);
    int first = sigaction(11, &act, &old);
    check("sigaction reports SIG_DFL first, all of it zeroed", first == 0 && old.handler == 0 && old.flags == 0, 0);
    struct guest_sigaction back;
    check("sigaction reports the handler set before",
          sigaction(11, 0, &back) == 0 && back.handler == (void*)on_signal && back.flags == 0x08000000, 0);
    sigaction(11, &old, 0);  // back to SIG_DFL: a real fault must reach the crash path, not on_signal
    struct guest_stack ss;
    memset(&ss, 0x55, sizeof ss);
    check("sigaltstack reports SS_DISABLE before one is set", sigaltstack(0, &ss) == 0 && ss.flags == 2, 0);
    unsigned long block = 1UL << 1, was = 99;
    sigprocmask(0, &block, 0);  // SIG_BLOCK
    check("sigprocmask keeps the thread's mask", sigprocmask(2, 0, &was) == 0 && was == block, 0);
    unsigned long none = 0;
    sigprocmask(2, &none, 0);  // SIG_SETMASK: a blocked SIGSEGV would turn a later fault into a crash
    void* timer = 0;
    check("timer_create gives an id", timer_create(1, 0, &timer) == 0 && timer != 0, 0);
    check("dirname", strcmp(dirname("/data/app/lib.so"), "/data/app") == 0 && strcmp(dirname("lib.so"), ".") == 0 &&
                         strcmp(dirname("/lib.so"), "/") == 0, 0);
    check("basename", strcmp(basename("/data/app/lib.so/"), "lib.so") == 0, 0);
    check("fnmatch", fnmatch("*.so", "libc.so", 0) == 0 && fnmatch("lib[a-c]?.so", "libcx.so", 0) == 0 &&
                         fnmatch("*.so", "lib/c.so", 2) != 0 && fnmatch("[!a]*", "abc", 0) != 0, 0);
    char path[512];
    snprintf(path, sizeof path, "%s/tmpXXXXXX", dir);
    const int fd = mkstemp(path);
    check("mkstemp creates a new file", fd >= 0 && strcmp(path + strlen(path) - 6, "XXXXXX") != 0, path);
    if (fd >= 0) close(fd);
}

// ---- signal delivery ---------------------------------------------------------------
// Faults reach the handler sigaction installed, with Linux's siginfo_t and
// ucontext_t, on the alternate stack when asked, and what the handler leaves
// in the ucontext is what the thread resumes with (Mono and Dart redirect
// rip; a guard-page scheme fixes the mapping and returns). raise(), kill()
// and pthread_kill deliver to the calling thread at once and to another
// thread by injection; sa_mask, SA_NODEFER, SA_RESETHAND and a blocked
// signal's pending delivery behave as on Linux.
int raise(int);
int kill(int, int);
int getpid(void);
int sched_yield(void);
int pthread_kill(pthread_t, int);
int sigsetjmp(long*, int);
void siglongjmp(long*, int);
void abort(void);
void (*signal(int, void (*)(int)))(int);

// Signal delivery with a ucontext_t is tested in arm64_signal_guest.c.

// ---- *at() calls and Android's CA store ----------------------------------------
// Dart's dart:io opens every file with openat and lists directories with
// fdopendir; its TLS loads /system/etc/security/cacerts that way. The runner
// sets WINAPK_TEST_CACERT to "<subject_hash_old> <first base64 line>" of a
// root from the Windows store, computed with CryptoAPI.
static void test_at_and_cacerts(const char* dir) {
    char sub[512], path[512], buf[256];
    snprintf(sub, sizeof sub, "%s/bionic_at_dir", dir);
    mkdir(sub, 0755);
    snprintf(path, sizeof path, "%s/at.txt", sub);
    int fd = open(path, 01 | 0100 | 01000, 0644);
    if (fd >= 0) { write(fd, "at!\n", 4); close(fd); }
    const int dfd = open(sub, kODirectory);
    check("open(O_DIRECTORY)", dfd >= 0, 0);
    fd = open(path, kODirectory);
    check("open(O_DIRECTORY) of a file fails with ENOTDIR", fd == -1 && *__errno() == 20, 0);
    fd = open(path, kODirect);
    check("open(O_DIRECT) of a file opens it", fd >= 0, 0);
    if (fd >= 0) close(fd);
    fd = openat(dfd, "at.txt", 0);
    memset(buf, 0, sizeof buf);
    check("openat relative to a directory descriptor", fd >= 0 && read(fd, buf, 4) == 4 && strcmp(buf, "at!\n") == 0,
          buf);
    if (fd >= 0) close(fd);
    fd = __openat_2(-100, path, 0);  // AT_FDCWD
    check("__openat_2 with AT_FDCWD", fd >= 0, 0);
    if (fd >= 0) close(fd);
    struct stat st;
    check("fstatat relative to a directory descriptor", fstatat(dfd, "at.txt", &st, 0) == 0 && st.size == 4, 0);
    check("openat of a missing file sets ENOENT", openat(dfd, "missing", 0) < 0 && *__errno() == 2, 0);
    snprintf(path, sizeof path, "%s/made", sub);
    check("mkdirat relative to a directory descriptor", mkdirat(dfd, "made", 0755) == 0 && stat(path, &st) == 0 &&
                                                            (st.mode & 0170000) == 0040000, 0);
    rmdir(path);
    snprintf(path, sizeof path, "%s/at.txt", sub);
    // LevelDB syncs the database directory after its MANIFEST, and a file
    // opened read-only may be synced too.
    check("fsync of a directory descriptor", fsync(dfd) == 0 && fdatasync(dfd) == 0, 0);
    fd = openat(dfd, "at.txt", 0);
    check("fsync of a read-only descriptor", fd >= 0 && fsync(fd) == 0, 0);
    if (fd >= 0) close(fd);
    DIR* d = fdopendir(dfd);
    int found = 0;
    for (struct dirent* e; d && (e = readdir(d));) found |= strcmp(e->d_name, "at.txt") == 0;
    check("fdopendir lists the directory", found, 0);
    check("dirfd gives fdopendir's descriptor", d && dirfd(d) == dfd, 0);
    if (d) closedir(d);  // closes dfd
    unlink(path);
    rmdir(sub);

    const int cfd = openat(-100, "/system/etc/security/cacerts", kODirectory | 02000000);  // O_CLOEXEC
    check("/system/etc/security/cacerts opens", cfd >= 0, 0);
    d = cfd >= 0 ? fdopendir(cfd) : 0;
    int roots = 0, match = 0;
    const char* want = getenv("WINAPK_TEST_CACERT");
    for (struct dirent* e; d && (e = readdir(d));) {
        if (e->d_name[0] == '.') continue;
        ++roots;
        if (!want || strncmp(e->d_name, want, 8) != 0 || e->d_name[8] != '.') continue;
        fd = openat(dirfd(d), e->d_name, 0);
        memset(buf, 0, sizeof buf);
        if (fd >= 0 && read(fd, buf, sizeof buf - 1) > 0 && strstr(buf, want + 9)) match = 1;
        if (fd >= 0) close(fd);
    }
    if (d) closedir(d);
    snprintf(buf, sizeof buf, "%d roots", roots);
    check("the CA store has the roots Windows trusts", roots > 0, buf);
    check("a root from the Windows store is there, named <subject_hash_old>.N", want && match, want);
    check("the Android 14 spelling (/apex/com.android.conscrypt/cacerts) is the same store",
          stat("/apex/com.android.conscrypt/cacerts", &st) == 0 && (st.mode & 0170000) == 0040000, 0);
}

// ---- gaps the app sweep found ------------------------------------------------------
// random(3), fork/waitpid, sched_getaffinity, FORTIFY's FD_*, cleanup
// handlers, link, fts, popen/system, fscanf, strptime, the lround family and
// ATrace. See src/bionic/misc.cpp, stdio.cpp, pthread.cpp, fd.cpp, printf.cpp.
long random(void);
void srandom(unsigned);
int rand(void);
void srand(unsigned);
char* initstate(unsigned, char*, size_t);
char* setstate(char*);
int vfork(void);
int fork(void);
int waitpid(int, int*, int);
int sched_getaffinity(int, size_t, void*);
typedef struct { unsigned long fds_bits[16]; } fd_set;
void __FD_SET_chk(int, fd_set*, size_t);
void __FD_CLR_chk(int, fd_set*, size_t);
int __FD_ISSET_chk(int, const fd_set*, size_t);
struct cleanup_t { struct cleanup_t* prev; void (*routine)(void*); void* arg; };
void __pthread_cleanup_push(struct cleanup_t*, void (*)(void*), void*);
void __pthread_cleanup_pop(struct cleanup_t*, int);
void pthread_exit(void*);
int link(const char*, const char*);
FILE* popen(const char*, const char*);
int pclose(FILE*);
int system(const char*);
int fscanf(FILE*, const char*, ...);
struct tm { int sec, min, hour, mday, mon, year, wday, yday, isdst; long gmtoff; const char* zone; };
char* strptime(const char*, const char*, struct tm*);
long lroundf(float);
long long llroundf(float);
long long llrintf(float);
long lroundl(long double);
long long llroundl(long double);
long lrintl(long double);
long long llrintl(long double);
long double strtold(const char*, char**);
long double wcstold(const wchar_t*, wchar_t**);
long double powl(long double, long double);
long double sqrtl(long double);
// The sign, exponent and top 48 fraction bits of a binary128.
static unsigned long quad_hi(long double v) {
    union { long double ld; unsigned long w[2]; } u;
    u.ld = v;
    return u.w[1];
}
_Bool ATrace_isEnabled(void);
void ATrace_beginSection(const char*);
void ATrace_endSection(void);
void ATrace_beginAsyncSection(const char*, int);
void ATrace_endAsyncSection(const char*, int);
void ATrace_setCounter(const char*, long);

typedef struct FTSENT {
    struct FTSENT *cycle, *parent, *link;
    long number;
    void* pointer;
    char *accpath, *path;
    int err, symfd;
    size_t pathlen, namelen;
    unsigned long ino, dev;
    unsigned nlink;
    short level;
    unsigned short info, flags, instr;
    struct stat* statp;
    char name[1];
} FTSENT;
typedef struct FTS FTS;
FTS* fts_open(char* const*, int, int (*)(const FTSENT**, const FTSENT**));
FTSENT* fts_read(FTS*);
FTSENT* fts_children(FTS*, int);
int fts_set(FTS*, FTSENT*, int);
int fts_close(FTS*);

static char g_cleanup_log[16];
static void cleanup_note(void* arg) { strlcpy(g_cleanup_log + strlen(g_cleanup_log), arg, 2); }
static void* cleanup_worker(void* arg) {
    (void)arg;
    struct cleanup_t a, b, c;
    __pthread_cleanup_push(&a, cleanup_note, "a");
    __pthread_cleanup_push(&b, cleanup_note, "b");
    __pthread_cleanup_pop(&b, 1);  // runs b
    __pthread_cleanup_push(&c, cleanup_note, "c");
    __pthread_cleanup_pop(&c, 0);  // does not run c
    pthread_exit((void*)5);        // runs a
    return 0;
}

static int by_name(const FTSENT** x, const FTSENT** y) { return strcmp((*x)->name, (*y)->name); }

static void put_file(const char* path, const char* text) {
    FILE* f = fopen(path, "w");
    if (f) {
        fputs(text, f);
        fclose(f);
    }
}

static void test_sweep_gaps(const char* dir) {
    char b[512], path[512], other[512];

    srandom(1);
    long r1 = random(), r2 = random();
    snprintf(b, sizeof b, "%ld %ld", r1, r2);
    check_str("srandom(1); random() is the glibc/BSD additive sequence", b, "1804289383 846930886");
    srand(1);
    check("rand() is random() (Bionic)", rand() == 1804289383, 0);
    static char state2[64], state0[16];
    char* old = initstate(7, state2, sizeof state2);
    r1 = random();
    r2 = random();
    const long r3 = random();
    snprintf(b, sizeof b, "%ld %ld %ld", r1, r2, r3);
    check_str("initstate(7, 64 bytes): the x**15 + x + 1 generator", b, "1539280666 119640454 760216337");
    initstate(7, state0, sizeof state0);
    snprintf(b, sizeof b, "%ld", random());
    check_str("initstate(7, 16 bytes): the linear congruential type", b, "1282168116");
    check("setstate back to the 64-byte state continues it", setstate(state2) == state0 && random() == 1561606870, 0);
    setstate(old);
    srandom(42);
    snprintf(b, sizeof b, "%ld", random());
    check_str("setstate back to the default table", b, "71876166");
    check("initstate with fewer than 8 bytes fails", initstate(1, state0, 4) == 0, 0);
    setstate(old);

    *__errno() = 0;
    check("vfork fails with ENOSYS (no child is made)", vfork() == -1 && *__errno() == 38, 0);
    check("fork fails with ENOSYS", fork() == -1 && *__errno() == 38, 0);
    int status = 0;
    check("waitpid: no children (ECHILD)", waitpid(-1, &status, 0) == -1 && *__errno() == 10, 0);

    unsigned long mask[16];
    memset(mask, 0xff, sizeof mask);
    int cpus = 0;
    const int aff = sched_getaffinity(0, sizeof mask, mask);
    for (int i = 0; i < 1024; ++i) cpus += (mask[i / 64] >> (i % 64)) & 1;
    snprintf(b, sizeof b, "%d of %ld", cpus, sysconf(0x60));
    check("sched_getaffinity: the process's CPUs, the rest cleared", aff == 0 && cpus >= 1 && cpus <= sysconf(0x60), b);
    check("sched_getaffinity of this thread's id", sched_getaffinity(gettid(), sizeof mask, mask) == 0, 0);
    check("sched_getaffinity with a set that is not whole longs: EINVAL",
          sched_getaffinity(0, 4, mask) == -1 && *__errno() == 22, 0);
    const long bytes = syscall(kSysSchedGetaffinity, 0, sizeof mask, mask);  // the raw call returns the mask's size
    check("syscall(SYS_sched_getaffinity) returns the bytes written", bytes > 0 && bytes % 8 == 0, 0);

    fd_set set;
    memset(&set, 0, sizeof set);
    __FD_SET_chk(70, &set, sizeof set);
    const int on = __FD_ISSET_chk(70, &set, sizeof set);
    __FD_CLR_chk(70, &set, sizeof set);
    check("__FD_SET_chk / __FD_ISSET_chk / __FD_CLR_chk", on == 1 && set.fds_bits[1] == 0 &&
                                                               __FD_ISSET_chk(70, &set, sizeof set) == 0, 0);

    pthread_t t;
    void* result = 0;
    g_cleanup_log[0] = 0;
    pthread_create(&t, 0, cleanup_worker, 0);
    pthread_join(t, &result);
    check_str("cleanup handlers: pop(1) runs, pop(0) not, pthread_exit runs the pending one", g_cleanup_log, "ba");
    check("pthread_exit's value reaches pthread_join", result == (void*)5, 0);

    // link
    snprintf(path, sizeof path, "%s/link_src.txt", dir);
    snprintf(other, sizeof other, "%s/link_dst.txt", dir);
    put_file(path, "linked\n");
    unlink(other);
    check("link makes a second name", link(path, other) == 0, 0);
    FILE* f = fopen(other, "r");
    b[0] = 0;
    if (f) {
        fgets(b, sizeof b, f);
        fclose(f);
    }
    check_str("the second name reads the same file", b, "linked\n");
    check("link onto an existing name: EEXIST", link(path, other) == -1 && *__errno() == 17, 0);
    snprintf(b, sizeof b, "%s/no_such_file", dir);
    check("link of a missing file: ENOENT", link(b, other) == -1 && *__errno() == 2, 0);
    check("link of a directory: EPERM", link(dir, other) == -1 && *__errno() == 1, 0);
    unlink(other);
    unlink(path);

    // fts
    char root[512];
    snprintf(root, sizeof root, "%s/fts_root", dir);
    mkdir(root, 0755);
    snprintf(path, sizeof path, "%s/sub", root);
    mkdir(path, 0755);
    snprintf(path, sizeof path, "%s/a.txt", root);
    put_file(path, "aaa");
    snprintf(path, sizeof path, "%s/sub/b.txt", root);
    put_file(path, "bb");
    char* roots[] = {root, 0};
    FTS* fts = fts_open(roots, 0x10, by_name);  // FTS_PHYSICAL
    char walk[256] = "";
    int b_ok = 0;
    for (FTSENT* e; fts && (e = fts_read(fts));) {
        snprintf(walk + strlen(walk), sizeof walk - strlen(walk), "%s%d:%s", walk[0] ? " " : "", e->info,
                 e->level == 0 ? "root" : e->name);
        if (strcmp(e->name, "b.txt") == 0) {
            b_ok = e->level == 2 && e->statp && e->statp->size == 2 && strstr(e->path, "fts_root/sub/b.txt") &&
                   strcmp(e->accpath, e->path) == 0;
        }
    }
    const int end_errno = *__errno();
    // FTS_D 1, FTS_F 8, FTS_DP 6.
    check_str("fts_read walks pre- and post-order, sorted", walk, "1:root 8:a.txt 1:sub 8:b.txt 6:sub 6:root");
    check("fts entries: level, stat, path, accpath", b_ok, 0);
    check("fts_read ends with errno 0", end_errno == 0, 0);
    check("fts_close", fts && fts_close(fts) == 0, 0);
    fts = fts_open(roots, 0x10, by_name);
    FTSENT* top = fts ? fts_read(fts) : 0;
    FTSENT* kids = top ? fts_children(fts, 0) : 0;
    snprintf(walk, sizeof walk, "%s,%s", kids ? kids->name : "-", kids && kids->link ? kids->link->name : "-");
    check_str("fts_children lists the directory", walk, "a.txt,sub");
    walk[0] = 0;
    for (FTSENT* e; fts && (e = fts_read(fts));) {
        if (strcmp(e->name, "sub") == 0 && e->info == 1) fts_set(fts, e, 4);  // FTS_SKIP
        snprintf(walk + strlen(walk), sizeof walk - strlen(walk), "%s%d:%s", walk[0] ? " " : "", e->info, e->name);
    }
    check_str("fts_set(FTS_SKIP) skips a directory", walk, "8:a.txt 1:sub 6:sub 6:fts_root");
    if (fts) fts_close(fts);
    snprintf(b, sizeof b, "%s/missing", dir);
    char* missing[] = {b, 0};
    fts = fts_open(missing, 0x10, 0);
    FTSENT* m = fts ? fts_read(fts) : 0;
    check("fts on a missing root: FTS_NS with ENOENT", m && m->info == 10 && m->err == 2, 0);
    if (fts) fts_close(fts);
    check("fts_open with an unknown option: EINVAL", fts_open(roots, 0x100, 0) == 0 && *__errno() == 22, 0);
    unlink(path);
    snprintf(path, sizeof path, "%s/sub", root);
    rmdir(path);
    snprintf(path, sizeof path, "%s/a.txt", root);
    unlink(path);
    rmdir(root);

    // popen / system: there is no shell, as when exec of /system/bin/sh fails
    f = popen("getprop ro.build.fingerprint", "r");
    check("popen gives a stream", f != 0, 0);
    check("popen's stream reads end-of-file", f && fgets(b, sizeof b, f) == 0, 0);
    check("pclose: the shell's exit status 127", f && pclose(f) == (127 << 8), 0);
    check("popen with a bad mode: EINVAL", popen("ls", "rw") == 0 && *__errno() == 22, 0);
    check("system(NULL) is 1, system(cmd) exits 127", system(0) == 1 && system("ls") == (127 << 8), 0);

    // fscanf
    snprintf(path, sizeof path, "%s/scan.txt", dir);
    put_file(path, "12 9000000000 word 2.5\nrest\n");
    f = fopen(path, "r");
    int i = 0;
    long l = 0;
    double d = 0;
    char word[16] = "";
    const int n = f ? fscanf(f, "%d %ld %15s %lf", &i, &l, word, &d) : -2;
    snprintf(b, sizeof b, "%d: %d %ld %s %.1f", n, i, l, word, d);
    check_str("fscanf", b, "4: 12 9000000000 word 2.5");
    check("fscanf leaves the stream after what it read", f && fscanf(f, "%15s", word) == 1 && strcmp(word, "rest") == 0,
          word);
    check("fscanf at end-of-file is EOF", f && fscanf(f, "%d", &i) == -1, 0);
    if (f) fclose(f);
    unlink(path);

    // strptime
    struct tm tm;
    memset(&tm, 0, sizeof tm);
    const char* in = "2024-02-29 13:45:07 rest";
    char* end = strptime(in, "%Y-%m-%d %H:%M:%S", &tm);
    snprintf(b, sizeof b, "%d %d %d %d:%d:%d yday %d wday %d", tm.year, tm.mon, tm.mday, tm.hour, tm.min, tm.sec,
             tm.yday, tm.wday);
    check_str("strptime fills the fields, yday and wday", b, "124 1 29 13:45:7 yday 59 wday 4");
    check("strptime returns where it stopped", end == in + 19, 0);
    memset(&tm, 0, sizeof tm);
    end = strptime("Tue, 05 Mar 2024 08:09:10 +0130", "%a, %d %b %Y %H:%M:%S %z", &tm);
    check("strptime with %a %b %z", end && tm.wday == 2 && tm.mon == 2 && tm.gmtoff == 5400, 0);
    memset(&tm, 0, sizeof tm);
    strptime("07:30 PM 69", "%I:%M %p %y", &tm);
    check("strptime %p and %y (69 is 1969)", tm.hour == 19 && tm.year == 69, 0);
    check("strptime refuses month 13", strptime("2024-13-01", "%Y-%m-%d", &tm) == 0, 0);

    // The rounding family, float and long double (binary128).
    volatile float hf = 2.5f;
    volatile long double h = 2.5L, h3 = 3.5L, nh = -2.5L, big = 1e30L, tiny = 0.4999999999999999999999999L;
    check("lroundf / llroundf / llrintf", lroundf(hf) == 3 && llroundf(hf) == 3 && llrintf(hf) == 2, 0);
    check("lroundl / llroundl away from zero", lroundl(h) == 3 && llroundl(nh) == -3 && lroundl(tiny) == 0, 0);
    check("lrintl / llrintl to nearest even", lrintl(h) == 2 && llrintl(h3) == 4 && lrintl(nh) == -2, 0);
    check("lroundl out of range is LONG_MAX", lroundl(big) == 0x7fffffffffffffffL, 0);
    // Compared by their bits: comparing binary128 values needs libgcc's soft-float.
    check("strtold(\"1.5\") is binary128 1.5", quad_hi(strtold("1.5", 0)) == 0x3fff800000000000ul, 0);
    check("strtold(\"-0.375\") is binary128 -0.375", quad_hi(strtold("-0.375", 0)) == 0xbffd800000000000ul, 0);
    const wchar_t minus_two[] = {'-', '2', 0};
    check("wcstold returns binary128, not the host's long double", quad_hi(wcstold(minus_two, 0)) == 0xc000000000000000ul,
          0);
    check("powl(2, 10) is 1024", quad_hi(powl(2.0L, 10.0L)) == 0x4009000000000000ul, 0);
    check("sqrtl(2.25) is 1.5", quad_hi(sqrtl(2.25L)) == 0x3fff800000000000ul, 0);

    ATrace_beginSection("test");
    ATrace_endSection();
    ATrace_beginAsyncSection("a", 1);
    ATrace_endAsyncSection("a", 1);
    ATrace_setCounter("c", 5);
    check("ATrace: tracing is off, the markers do nothing", !ATrace_isEnabled(), 0);
}

// ---- calls the loader's fallback answers ------------------------------------
// FORTIFY forms and plain calls the shim did not have. An import nobody provides is bound to a stub
// that answers 0, which for these meant "done", "nothing ready" or "descriptor 0": __pwrite_chk's 0
// kept Play services writing for ever. Each is either the real thing now or fails as Linux reports
// it (src/bionic/, "calls that were missing").
struct iovec { void* base; size_t len; };
ssize_t writev(int, const struct iovec*, int);
ssize_t readv(int, const struct iovec*, int);
ssize_t pwritev(int, const struct iovec*, int, off_t);
ssize_t preadv(int, const struct iovec*, int, off_t);
ssize_t __pwrite_chk(int, const void*, size_t, off_t, size_t);
ssize_t __pread_chk(int, void*, size_t, off_t, size_t);
ssize_t __readlinkat_chk(int, const char*, char*, size_t, size_t);
char* __getcwd_chk(char*, size_t, size_t);
void* __memrchr_chk(const void*, int, size_t, size_t);
void* __mempcpy_chk(void*, const void*, size_t, size_t);
char* __stpncpy_chk(char*, const char*, size_t, size_t);
char* __stpncpy_chk2(char*, const char*, size_t, size_t, size_t);
void* memccpy(void*, const void*, int, size_t);
int __poll_chk(struct pollfd*, unsigned long, int, size_t);
int __ppoll_chk(struct pollfd*, unsigned long, const struct timespec*, const void*, size_t);
int epoll_pwait2(int, struct epoll_event*, int, const struct timespec*, const void*);
ssize_t sendfile(int, int, off_t*, size_t);
int truncate(const char*, off_t);
int renameat(int, const char*, int, const char*);
int renameat2(int, const char*, int, const char*, unsigned);
int readdir_r(DIR*, struct dirent*, struct dirent**);
void rewinddir(DIR*);
int scandir(const char*, struct dirent***, int (*)(const struct dirent*),
            int (*)(const struct dirent**, const struct dirent**));
int alphasort(const struct dirent**, const struct dirent**);
int mkstemps(char*, int);
int getc_unlocked(FILE*);
int posix_spawn(int*, const char*, const void*, const void*, char* const[], char* const[]);
int execv(const char*, char* const[]);
int wait4(int, int*, int, void*);
int openpty(int*, int*, char*, const void*, const void*);
int inotify_init1(int);
void* iconv_open(const char*, const char*);
int regcomp(void*, const char*, int);
void* mremap(void*, size_t, size_t, int, ...);
int symlink(const char*, const char*);
int sigtimedwait(const unsigned long*, void*, const struct timespec*);
int sigwait(const unsigned long*, int*);
int pthread_spin_init(long*, int);
int pthread_spin_lock(long*);
int pthread_spin_trylock(long*);
int pthread_spin_unlock(long*);
int pthread_mutex_clocklock(pthread_mutex_t*, int, const struct timespec*);
typedef struct { unsigned count; int reserved[3]; } sem_t;
int sem_init(sem_t*, int, unsigned);
int sem_post(sem_t*);
int sem_clockwait(sem_t*, int, const struct timespec*);
const char* nl_langinfo(int);
struct sysinfo {
    long uptime;
    unsigned long loads[3], totalram, freeram, sharedram, bufferram, totalswap, freeswap;
    unsigned short procs, pad;
    unsigned long totalhigh, freehigh;
    unsigned mem_unit;
};
int sysinfo(struct sysinfo*);
int gethostbyname_r(const char*, void*, char*, size_t, void**, int*);
int ASharedMemory_create(const char*, size_t);
size_t ASharedMemory_getSize(int);

static int only_txt(const struct dirent* e) {
    const size_t n = strlen(e->d_name);
    return n > 4 && strcmp(e->d_name + n - 4, ".txt") == 0;
}

// CLOCK_MONOTONIC's time `ms` from now.
static struct timespec in_ms(long ms) {
    struct timespec t;
    clock_gettime(1, &t);
    t.tv_nsec += ms * 1000000;
    t.tv_sec += t.tv_nsec / 1000000000;
    t.tv_nsec %= 1000000000;
    return t;
}

// Threads reading pages of one descriptor at once, each page filled with its own number: SQLite and
// LevelDB share a descriptor across threads this way. Returns the number of wrong pages read.
static int g_pages_fd = -1;
static void* page_reader(void* arg) {
    long bad = 0;
    unsigned char page[4096];
    for (int i = 0; i < 2000; ++i) {
        const int n = (int)(((long)arg + i * 7) % 16);
        if (__pread_chk(g_pages_fd, page, sizeof page, (off_t)n * 4096, sizeof page) != 4096 || page[0] != n ||
            page[4095] != n) {
            ++bad;
        }
    }
    return (void*)bad;
}

static void test_missing_forms(const char* dir) {
    char b[512], path[512], other[512];
    struct stat st;

    // Files: the FORTIFY forms of pread/pwrite, vectors, sendfile, truncate, renameat.
    snprintf(path, sizeof path, "%s/forms.bin", dir);
    int fd = open(path, 01102, 0600);  // O_RDWR | O_CREAT | O_TRUNC
    check("__pwrite_chk writes what it is given", fd >= 0 && __pwrite_chk(fd, "0123456789", 10, 0, 16) == 10,
          why(fd >= 0));
    memset(b, 0, sizeof b);
    check("__pread_chk reads it back", __pread_chk(fd, b, 4, 3, sizeof b) == 4 && strcmp(b, "3456") == 0, b);
    struct iovec out[3] = {{"ab", 2}, {"", 0}, {"cdef", 4}};
    check("writev writes every buffer in one call", lseek(fd, 0, 2) == 10 && writev(fd, out, 3) == 6, 0);
    char r1[4] = "", r2[8] = "";
    struct iovec in[2] = {{r1, 3}, {r2, 7}};
    lseek(fd, 8, 0);
    check("readv fills the buffers in order and stops at the end",
          readv(fd, in, 2) == 8 && memcmp(r1, "89a", 3) == 0 && memcmp(r2, "bcdef", 5) == 0, 0);
    check("readv at the end of the file is 0", readv(fd, in, 2) == 0, 0);
    check("pwritev / preadv work at an offset and leave the position",
          pwritev(fd, out, 3, 2) == 6 && preadv(fd, in, 1, 2) == 3 && memcmp(r1, "abc", 3) == 0 &&
              lseek(fd, 0, 1) == 16,
          0);
    check("pread past the end is 0", __pread_chk(fd, b, 4, 1000, sizeof b) == 0, 0);
    check("ftruncate to the same size keeps the position", lseek(fd, 7, 0) == 7 && ftruncate(fd, 16) == 0 &&
                                                               lseek(fd, 0, 1) == 7, 0);
    snprintf(other, sizeof other, "%s/pages.bin", dir);
    g_pages_fd = open(other, 01102, 0600);
    for (int n = 0; n < 16; ++n) {
        memset(b, n, sizeof b);
        for (int k = 0; k < 8; ++k) write(g_pages_fd, b, sizeof b);
    }
    lseek(g_pages_fd, 5, 0);
    pthread_t readers[4];
    long wrong = 0;
    for (long i = 0; i < 4; ++i) pthread_create(&readers[i], 0, page_reader, (void*)i);
    for (int i = 0; i < 4; ++i) {
        void* r = 0;
        pthread_join(readers[i], &r);
        wrong += (long)r;
    }
    check("pread from four threads at once reads every page it asks for and leaves the position",
          wrong == 0 && lseek(g_pages_fd, 0, 1) == 5, 0);
    close(g_pages_fd);
    unlink(other);
    int p[2] = {-1, -1};
    pipe(p);
    write(p[1], "xyz", 3);
    in[0].len = 2;
    check("pread on a pipe fails with ESPIPE", __pread_chk(p[0], b, 1, 0, sizeof b) == -1 && *__errno() == 29, 0);
    check("readv on a pipe returns what is there", readv(p[0], in, 2) == 3 && r1[1] == 'y' && r2[0] == 'z', 0);
    check("writev to a pipe", writev(p[1], out, 3) == 6 && read(p[0], b, 16) == 6 && memcmp(b, "abcdef", 6) == 0, 0);
    off_t at = 2;  // the file holds "01abcdef89abcdef"
    check("sendfile copies from an offset and moves it",
          sendfile(p[1], fd, &at, 5) == 5 && at == 7 && read(p[0], b, 16) == 5 && memcmp(b, "abcde", 5) == 0, 0);
    at = 14;
    check("sendfile stops at the end of the file", sendfile(p[1], fd, &at, 100) == 2 && read(p[0], b, 16) == 2, 0);
    close(fd);
    check("truncate by path", truncate(path, 4) == 0 && stat(path, &st) == 0 && st.size == 4, 0);
    snprintf(other, sizeof other, "%s/forms2.bin", dir);
    check("renameat(AT_FDCWD) renames", renameat(-100, path, -100, other) == 0 && stat(other, &st) == 0 &&
                                            stat(path, &st) != 0, 0);
    put_file(path, "x");
    check("renameat2(RENAME_NOREPLACE) refuses a taken name: EEXIST",
          renameat2(-100, path, -100, other, 1) == -1 && *__errno() == 17 && stat(path, &st) == 0, 0);
    unlink(path);
    unlink(other);
    check("__readlinkat_chk fails as readlinkat does", __readlinkat_chk(-100, path, b, 16, 16) == -1, 0);
    check("__getcwd_chk", __getcwd_chk(b, sizeof b, sizeof b) == b && b[0] == '/', b);
    check("symlink: EPERM, as on a file system without links", symlink(other, path) == -1 && *__errno() == 1, 0);

    // Strings.
    const char* abc = "abcabc";
    char s8[8];
    check("__memrchr_chk", __memrchr_chk(abc, 'b', 6, 7) == abc + 4, 0);
    check("__mempcpy_chk returns the end of the copy",
          __mempcpy_chk(s8, "xy", 3, sizeof s8) == s8 + 3 && strcmp(s8, "xy") == 0, 0);
    memset(s8, 'z', sizeof s8);
    check("__stpncpy_chk pads and returns the terminator",
          __stpncpy_chk(s8, "ab", 6, sizeof s8) == s8 + 2 && s8[5] == 0 && s8[6] == 'z', 0);
    check("__stpncpy_chk2 without room for a terminator returns the end",
          __stpncpy_chk2(s8, "abcdef", 4, sizeof s8, 7) == s8 + 4 && memcmp(s8, "abcd", 4) == 0, 0);
    check("memccpy stops after the byte, or answers null",
          memccpy(s8, "a,b", ',', 3) == s8 + 2 && memccpy(s8, "abc", ',', 3) == 0, 0);

    // Waits: each takes its timeout, where the stub's 0 came back at once.
    struct pollfd pf = {p[0], 1, 0};
    long t0 = ms_now(1);
    check("__poll_chk waits out its timeout on an empty pipe",
          __poll_chk(&pf, 1, 50, sizeof pf) == 0 && ms_now(1) - t0 >= 40, 0);
    const struct timespec fifty = {0, 50000000};
    t0 = ms_now(1);
    check("__ppoll_chk too", __ppoll_chk(&pf, 1, &fifty, 0, sizeof pf) == 0 && ms_now(1) - t0 >= 40, 0);
    const int ep = epoll_create1(0);
    struct epoll_event ev = {1, 0}, got;
    epoll_ctl(ep, 1, p[0], &ev);
    t0 = ms_now(1);
    check("epoll_pwait2 waits out its timespec", epoll_pwait2(ep, &got, 1, &fifty, 0) == 0 && ms_now(1) - t0 >= 40,
          0);
    write(p[1], "!", 1);
    check("epoll_pwait2 reports the pipe once it is written", epoll_pwait2(ep, &got, 1, &fifty, 0) == 1, 0);
    close(ep);
    close(p[0]);
    close(p[1]);

    // Directories.
    snprintf(path, sizeof path, "%s/forms_dir", dir);
    mkdir(path, 0700);
    static const char* const kNames[] = {"b.txt", "a.txt", "c.dat"};
    for (int i = 0; i < 3; ++i) {
        snprintf(other, sizeof other, "%s/%s", path, kNames[i]);
        put_file(other, "x");
    }
    DIR* d = opendir(path);
    struct dirent entry, *res = &entry;
    int seen = 0, rounds = 0;
    while (d && rounds++ < 100 && readdir_r(d, &entry, &res) == 0 && res) seen += entry.d_name[0] != '.';
    check("readdir_r ends with a null result", seen == 3 && res == 0, 0);
    if (d) rewinddir(d);
    seen = 0;
    for (struct dirent* e; d && (e = readdir(d));) seen += e->d_name[0] != '.';
    check("rewinddir starts the listing over", seen == 3, 0);
    if (d) closedir(d);
    struct dirent** list = 0;
    const int n = scandir(path, &list, only_txt, alphasort);
    check("scandir filters with the callback and sorts with alphasort",
          n == 2 && strcmp(list[0]->d_name, "a.txt") == 0 && strcmp(list[1]->d_name, "b.txt") == 0, 0);
    for (int i = 0; i < n; ++i) free(list[i]);
    if (n >= 0) free(list);
    for (int i = 0; i < 3; ++i) {
        snprintf(other, sizeof other, "%s/%s", path, kNames[i]);
        unlink(other);
    }
    rmdir(path);

    snprintf(path, sizeof path, "%s/tmpXXXXXX.txt", dir);
    fd = mkstemps(path, 4);
    check("mkstemps keeps the suffix",
          fd > 2 && strcmp(path + strlen(path) - 4, ".txt") == 0 && !strstr(path, "XXXXXX"), path);
    write(fd, "q", 1);
    close(fd);
    FILE* f = fopen(path, "r");
    check("getc_unlocked reads, then answers EOF", f && getc_unlocked(f) == 'q' && getc_unlocked(f) == -1, 0);
    if (f) fclose(f);
    unlink(path);

    // What Win.apk cannot do fails as Linux reports it (and says so once in the log).
    int pid = 0;
    char* argv[] = {"sh", 0};
    check("posix_spawn answers ENOSYS", posix_spawn(&pid, "/system/bin/sh", 0, 0, argv, environ) == 38, 0);
    check("execv returns -1 with ENOSYS", execv("/system/bin/sh", argv) == -1 && *__errno() == 38, 0);
    check("wait4 has no child: ECHILD", wait4(-1, 0, 0, 0) == -1 && *__errno() == 10, 0);
    int master = 0, slave = 0;
    check("openpty: ENOENT, there is no /dev/ptmx", openpty(&master, &slave, 0, 0, 0) == -1 && *__errno() == 2, 0);
    check("inotify_init1 fails with ENOSYS, not descriptor 0", inotify_init1(0) == -1 && *__errno() == 38, 0);
    check("iconv_open fails with (iconv_t)-1 and EINVAL",
          iconv_open("UTF-8", "UTF-16LE") == (void*)-1 && *__errno() == 22, 0);
    char re[64];
    check("regcomp does not claim to have compiled", regcomp(re, "a+", 1) != 0, 0);
    check("mremap fails with MAP_FAILED and ENOMEM", mremap(b, 4096, 8192, 1) == (void*)-1 && *__errno() == 12, 0);
    void* host = b;
    int herr = 0;
    check("gethostbyname_r: no result, NO_RECOVERY",
          gethostbyname_r("localhost", b, path, sizeof path, &host, &herr) == -1 && host == 0 && herr == 3, 0);

    // Signals waited for, not handled.
    const unsigned long usr1 = 1ul << 9;  // SIGUSR1 is 10
    sigprocmask(0, &usr1, 0);             // SIG_BLOCK
    t0 = ms_now(1);
    check("sigtimedwait times out with EAGAIN",
          sigtimedwait(&usr1, 0, &fifty) == -1 && *__errno() == 11 && ms_now(1) - t0 >= 40, 0);
    raise(10);
    int sig = 0;
    check("sigwait takes the pending signal", sigwait(&usr1, &sig) == 0 && sig == 10, 0);
    raise(10);
    check("sigtimedwait takes it too", sigtimedwait(&usr1, 0, &fifty) == 10, 0);
    sigprocmask(1, &usr1, 0);  // SIG_UNBLOCK: nothing is left pending

    // Locks with a deadline, and the spin lock.
    long spin = 7;
    check("pthread_spin_lock holds the lock: trylock is EBUSY until unlock",
          pthread_spin_init(&spin, 0) == 0 && pthread_spin_lock(&spin) == 0 && pthread_spin_trylock(&spin) == 16 &&
              pthread_spin_unlock(&spin) == 0 && pthread_spin_trylock(&spin) == 0,
          0);
    static pthread_mutex_t mu;
    pthread_mutex_lock(&mu);
    struct timespec deadline = in_ms(50);
    t0 = ms_now(1);
    check("pthread_mutex_clocklock times out on a held mutex",
          pthread_mutex_clocklock(&mu, 1, &deadline) == 110 && ms_now(1) - t0 >= 40, 0);
    pthread_mutex_unlock(&mu);
    deadline = in_ms(50);
    check("pthread_mutex_clocklock takes a free one", pthread_mutex_clocklock(&mu, 1, &deadline) == 0, 0);
    pthread_mutex_unlock(&mu);
    sem_t sem;
    sem_init(&sem, 0, 0);
    deadline = in_ms(50);
    t0 = ms_now(1);
    check("sem_clockwait times out on an empty semaphore: ETIMEDOUT",
          sem_clockwait(&sem, 1, &deadline) == -1 && *__errno() == 110 && ms_now(1) - t0 >= 40, 0);
    sem_post(&sem);
    deadline = in_ms(50);
    check("sem_clockwait takes a posted one", sem_clockwait(&sem, 1, &deadline) == 0, 0);

    fd = ASharedMemory_create("region", 8192);
    char* shared = fd > 2 ? mmap(0, 8192, 3, 1, fd, 0) : (char*)-1;  // PROT_READ | PROT_WRITE, MAP_SHARED
    check("ASharedMemory_create gives a region of the size asked, to map",
          fd > 2 && ASharedMemory_getSize(fd) == 8192 && shared != (char*)-1 && (shared[8191] = 'm') == 'm', 0);
    if (shared != (char*)-1) munmap(shared, 8192);
    if (fd > 2) close(fd);

    check_str("nl_langinfo(CODESET)", nl_langinfo(1), "UTF-8");
    struct sysinfo si;
    memset(&si, 0, sizeof si);
    check("sysinfo reports memory", sysinfo(&si) == 0 && si.totalram > 0 && si.freeram <= si.totalram &&
                                        si.mem_unit == 1, 0);
}

EXPORT int bionic_test(const char* dir) {
    printf("Bionic shim, from guest code\n");
    test_printf();
    test_strings();
    test_threads();
    test_tls();
    test_locale_and_syscall();
    test_malloc_usable_size();
    test_event_fds();
    test_sockets();
    test_network();
    test_looper();
    test_files(dir);
    test_at_and_cacerts(dir);
    test_zlib(dir);
    test_rand48_statfs_getopt(dir);
    test_misc();
    test_signals_and_paths(dir);
    test_sweep_gaps(dir);
    test_missing_forms(dir);
    return failures;
}
