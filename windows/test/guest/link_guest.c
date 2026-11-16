// link_guest.c -- the Android arm64 libraries for the guest linker test
// (test/linker_test.cpp). One source, eight libraries, picked with -D:
//
//   WINAPK_LINK_ROOT    libwinapk_link_root.so    needs mid, libc.so, libdl.so
//   WINAPK_LINK_MID     libwinapk_link_mid.so     needs base, liblog.so
//   WINAPK_LINK_BASE    libwinapk_link_base.so    needs mid (a DT_NEEDED cycle)
//   WINAPK_LINK_PLUGIN  libwinapk_link_plugin.so  no dependencies; dlopen'ed and dlclose'd by root
//
// and, for the order symbols are looked up in (what libmozglue.so is to
// Firefox's libxul.so: a library that defines libc's names for the ones that
// list it before libc.so):
//
//   WINAPK_LINK_INTERPOSE  libwinapk_link_interpose.so  no dependencies; exports strlen and dlerror
//   WINAPK_LINK_USER       libwinapk_link_user.so       needs interpose, libc.so, libdl.so
//   WINAPK_LINK_LATE       libwinapk_link_late.so       needs libc.so, interpose
//   WINAPK_LINK_PLAIN      libwinapk_link_plain.so      needs libc.so
//
// build_guest.sh links them against stub system libraries so the DT_NEEDED
// entries look like an NDK build's. The cycle is real: base is linked against
// a placeholder with mid's soname, then mid is linked against base.
//
// Constructors call an external function on purpose. LLVM evaluates a
// constructor that only stores a constant at compile time and deletes it.

typedef unsigned long size_t;

size_t strlen(const char*);
int strcmp(const char*, const char*);

#define EXPORT __attribute__((visibility("default")))

#if defined(WINAPK_LINK_BASE)

int link_mid_twice(int v);  // in mid, which needs base: the cycle

static int g_base_value;

__attribute__((constructor)) static void base_init(void) { g_base_value = 40 + (int)strlen("ok"); }

EXPORT int link_base_value(void) { return g_base_value; }

EXPORT int link_base_calls_mid(int v) { return link_mid_twice(v); }

// Found through dlsym on a library that depends on this one, the way a JNI
// method in a dependency is.
EXPORT int Java_WinApkLink_fromBase(void) { return 7; }

#elif defined(WINAPK_LINK_MID)

int link_base_value(void);

static int g_mid_seen;

// base is a dependency of mid, so its constructor has run by now: 42.
__attribute__((constructor)) static void mid_init(void) { g_mid_seen = link_base_value() + (int)strlen(""); }

EXPORT int link_mid_twice(int v) { return 2 * v; }

EXPORT int link_mid_seen(void) { return g_mid_seen; }

#elif defined(WINAPK_LINK_ROOT)

typedef struct {
    const char* dli_fname;
    void* dli_fbase;
    const char* dli_sname;
    void* dli_saddr;
} Dl_info;

void* dlopen(const char*, int);
void* dlsym(void*, const char*);
int dlclose(void*);
char* dlerror(void);
int dladdr(const void*, Dl_info*);

#define RTLD_NOW 2
#define RTLD_NOLOAD 4
#define RTLD_DEFAULT ((void*)0)

int link_mid_seen(void);
int link_base_calls_mid(int);  // defined two levels down, in base

static int g_root_seen;

__attribute__((constructor)) static void root_init(void) { g_root_seen = link_mid_seen() + (int)strlen(""); }

// 42 only if base's and mid's constructors ran before root's.
EXPORT int link_root_init_order(void) { return g_root_seen; }

// An undefined symbol bound through the local group, two levels down, into a
// library that itself calls back up the cycle: 2 * 5 = 10.
EXPORT int link_root_indirect(void) { return link_base_calls_mid(5); }

// dlopen by bare name (found in the search path) and dlsym on the handle: 81.
EXPORT int link_root_dlopen(void) {
    void* h = dlopen("libwinapk_link_plugin.so", RTLD_NOW);
    if (!h) return -1;
    int (*square)(int) = (int (*)(int))dlsym(h, "link_plugin_square");
    if (!square) return -2;
    int r = square(9);
    dlclose(h);
    return r;
}

// A failed dlopen sets dlerror, which reads back once.
EXPORT int link_root_dlerror(void) {
    if (dlopen("libwinapk_not_there.so", RTLD_NOW)) return -1;
    const char* e = dlerror();
    if (!e || strlen(e) == 0) return -2;
    if (dlerror()) return -3;
    return 1;
}

// dladdr names this very function.
EXPORT int link_root_dladdr(void) {
    Dl_info info;
    if (!dladdr((const void*)&link_root_dladdr, &info)) return -1;
    if (!info.dli_sname || strcmp(info.dli_sname, "link_root_dladdr") != 0) return -2;
    if (info.dli_saddr != (void*)&link_root_dladdr) return -3;
    if (!info.dli_fname || !info.dli_fbase) return -4;
    return 1;
}

// RTLD_DEFAULT sees libc, but not libraries loaded without RTLD_GLOBAL.
EXPORT int link_root_rtld_default(void) {
    if (!dlsym(RTLD_DEFAULT, "malloc")) return -1;
    if (dlsym(RTLD_DEFAULT, "link_base_value")) return -2;
    return 1;
}

// dlclose unloads: the plugin's destructor and its __cxa_atexit entry run
// (sink: +1 and +10), RTLD_NOLOAD no longer finds it, and the next dlopen
// starts it afresh (its data back to the constructor's value, a new TLS
// block). 1 on success, else the step that failed.
EXPORT int link_root_dlclose_cycle(void) {
    void* h = dlopen("libwinapk_link_plugin.so", RTLD_NOW);
    if (!h) return -1;
    int sink = 0;
    void (*set_sink)(int*) = (void (*)(int*))dlsym(h, "link_plugin_set_fini_sink");
    void (*poke)(void) = (void (*)(void))dlsym(h, "link_plugin_poke");
    int (*bump)(void) = (int (*)(void))dlsym(h, "link_plugin_tls_bump");
    int (*peek)(void) = (int (*)(void))dlsym(h, "link_plugin_peek");
    if (!set_sink || !poke || !bump || !peek) return -2;
    set_sink(&sink);
    poke();
    if (bump() != 4 || bump() != 5) return -3;
    if (dlclose(h) != 0) return -4;
    if (sink != 11) return -5;
    if (dlopen("libwinapk_link_plugin.so", RTLD_NOLOAD)) return -6;
    h = dlopen("libwinapk_link_plugin.so", RTLD_NOW);
    if (!h) return -7;
    peek = (int (*)(void))dlsym(h, "link_plugin_peek");
    bump = (int (*)(void))dlsym(h, "link_plugin_tls_bump");
    if (!peek || !bump) return -8;
    if (peek() != 0) return -9;
    if (bump() != 4) return -10;
    dlclose(h);
    return 1;
}

#elif defined(WINAPK_LINK_INTERPOSE)

// libc's name with an answer libc never gives.
EXPORT size_t strlen(const char* s) {
    (void)s;
    return 1000;
}

// The linker's own name: no library gets this one, whatever the order.
EXPORT char* dlerror(void) { return (char*)"interposed"; }

#elif defined(WINAPK_LINK_USER) || defined(WINAPK_LINK_LATE) || defined(WINAPK_LINK_PLAIN)

char* dlerror(void);

// strlen as this library was bound to it: 3 from libc, 1000 from interpose.
// USER lists interpose before libc.so, so it gets interpose's, as on a
// device. LATE lists libc.so first and gets libc's. PLAIN has libc.so alone:
// libc's, unless interpose was opened with RTLD_GLOBAL before it was loaded.
#if defined(WINAPK_LINK_USER)
EXPORT int link_user_strlen(void) { return (int)strlen("abc"); }
// 1 when dlerror is the linker's: no error is pending after the first call.
EXPORT int link_user_dlerror(void) {
    dlerror();
    return dlerror() == 0;
}
#elif defined(WINAPK_LINK_LATE)
EXPORT int link_late_strlen(void) { return (int)strlen("abc"); }
#else
EXPORT int link_plain_strlen(void) { return (int)strlen("abc"); }
#endif

#elif defined(WINAPK_LINK_PLUGIN)

int __cxa_atexit(void (*)(void*), void*, void*);
int __cxa_thread_atexit_impl(void (*)(void*), void*, void*);

// Any address inside the image stands for __dso_handle (there is no crtbegin
// in a -nostdlib build).
static char g_dso_anchor;
static int g_poked;
static int* g_fini_sink;
static __thread int t_plugin = 3;

__attribute__((constructor)) static void plugin_init(void) { g_poked = (int)strlen(""); }
__attribute__((destructor)) static void plugin_fini(void) {
    if (g_fini_sink) *g_fini_sink += 1;
}
static void atexit_entry(void* p) { *(int*)p += 10; }
static void thread_dtor(void* p) { *(int*)p += 100; }

EXPORT int link_plugin_square(int v) { return v * v; }
EXPORT void link_plugin_set_fini_sink(int* sink) {
    g_fini_sink = sink;
    __cxa_atexit(atexit_entry, sink, &g_dso_anchor);
}
EXPORT void link_plugin_poke(void) { g_poked = 5; }
EXPORT int link_plugin_peek(void) { return g_poked; }
EXPORT int link_plugin_tls_bump(void) { return ++t_plugin; }
// A C++ thread_local destructor for the calling thread, which keeps the
// plugin loaded until that thread exits.
EXPORT void link_plugin_register_thread_dtor(int* sink) { __cxa_thread_atexit_impl(thread_dtor, sink, &g_dso_anchor); }

#endif
