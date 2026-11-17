// pthread.cpp -- Bionic's <pthread.h>, <semaphore.h> and sched_yield.
//
// Bionic's synchronization objects are arrays of int32 the guest owns (a
// pthread_mutex_t is 40 bytes, a pthread_cond_t 48), only 4-byte aligned, and
// their static initializers are all zeros, or a type in bits 14-15 for
// PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP and its error-checking twin. So they
// are built the way Bionic builds them, futex-style on words in the guest's
// own memory, with WaitOnAddress / WakeByAddress in place of the futex
// syscall. No side table, nothing to initialize lazily.
//
// Threads: pthread_t is a pointer to a host record. A guest start routine is
// AAPCS64; the host thread procedure calls it and then runs the guest's
// thread-specific-data destructors, as Bionic's pthread_exit does. Threads the
// guest did not create (the JVM's) get a record on first use.

#include "bionic_internal.h"

#include "winapk/arm64_guest.h"
#include "winapk/elf_tls.h"
#include "winapk/guest_stack.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <vector>

#include <windows.h>

namespace winapk::bionic {

namespace {

// ---- futex words ----------------------------------------------------------------

std::atomic<int32_t>* word(void* p, size_t index) {
    return reinterpret_cast<std::atomic<int32_t>*>(static_cast<int32_t*>(p) + index);
}

// Waits while *w == expected, up to ms (INFINITE for no limit). Returns false
// on timeout.
bool futex_wait(std::atomic<int32_t>* w, int32_t expected, DWORD ms) {
    if (WaitOnAddress(w, &expected, sizeof(expected), ms)) return true;
    return GetLastError() != ERROR_TIMEOUT;
}
void futex_wake_one(std::atomic<int32_t>* w) { WakeByAddressSingle(w); }
void futex_wake_all(std::atomic<int32_t>* w) { WakeByAddressAll(w); }

// A struct timespec (64-bit fields on x86_64).
struct GuestTimespec {
    int64_t tv_sec;
    int64_t tv_nsec;
};

// Milliseconds from now until an absolute CLOCK_REALTIME / CLOCK_MONOTONIC time.
DWORD ms_until(const GuestTimespec* abstime, bool monotonic) {
    if (!abstime) return INFINITE;
    int64_t now_ns;
    if (monotonic) {
        LARGE_INTEGER c, f;
        QueryPerformanceCounter(&c);
        QueryPerformanceFrequency(&f);
        now_ns = static_cast<int64_t>(static_cast<double>(c.QuadPart) * 1e9 / static_cast<double>(f.QuadPart));
    } else {
        FILETIME ft;
        GetSystemTimePreciseAsFileTime(&ft);
        const int64_t t = (static_cast<int64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
        now_ns = (t - 116444736000000000LL) * 100;  // since 1970
    }
    const int64_t target = abstime->tv_sec * 1000000000LL + abstime->tv_nsec;
    if (target <= now_ns) return 0;
    const int64_t ms = (target - now_ns + 999999) / 1000000;
    return ms >= INFINITE ? INFINITE - 1 : static_cast<DWORD>(ms);
}

// ---- mutex ------------------------------------------------------------------------
// word 0: bits 0-1 lock state (0 free, 1 locked, 2 locked with waiters), bits
// 14-15 type (Bionic's encoding); word 1: owner thread id; word 2: recursion.
constexpr int32_t kStateMask = 3;
constexpr int32_t kTypeShift = 14;
constexpr int kNormal = 0, kRecursive = 1, kErrorCheck = 2;

int mutex_type(void* m) { return (word(m, 0)->load(std::memory_order_relaxed) >> kTypeShift) & 3; }

void lock_word(std::atomic<int32_t>* w) {
    const int32_t type_bits = w->load(std::memory_order_relaxed) & ~kStateMask;
    int32_t expected = type_bits;
    if (w->compare_exchange_strong(expected, type_bits | 1, std::memory_order_acquire)) return;
    // Contended: mark it, then wait until it is released.
    while ((w->exchange(type_bits | 2, std::memory_order_acquire) & kStateMask) != 0) {
        futex_wait(w, type_bits | 2, INFINITE);
    }
}

bool trylock_word(std::atomic<int32_t>* w) {
    const int32_t type_bits = w->load(std::memory_order_relaxed) & ~kStateMask;
    int32_t expected = type_bits;
    return w->compare_exchange_strong(expected, type_bits | 1, std::memory_order_acquire);
}

void unlock_word(std::atomic<int32_t>* w) {
    const int32_t type_bits = w->load(std::memory_order_relaxed) & ~kStateMask;
    if ((w->exchange(type_bits, std::memory_order_release) & kStateMask) == 2) futex_wake_one(w);
}

WINAPK_GUEST_ABI int g_mutexattr_init(int32_t* a) { *a = 0; return 0; }
WINAPK_GUEST_ABI int g_mutexattr_destroy(int32_t*) { return 0; }
WINAPK_GUEST_ABI int g_mutexattr_settype(int32_t* a, int type) {
    if (type < 0 || type > 2) return kEINVAL;
    *a = (*a & ~3) | type;
    return 0;
}
WINAPK_GUEST_ABI int g_mutexattr_gettype(const int32_t* a, int* type) { *type = *a & 3; return 0; }
WINAPK_GUEST_ABI int g_mutexattr_setpshared(int32_t*, int) { return 0; }

WINAPK_GUEST_ABI int g_mutex_init(void* m, const int32_t* attr) {
    std::memset(m, 0, 40);
    const int type = attr ? (*attr & 3) : kNormal;
    word(m, 0)->store(type << kTypeShift, std::memory_order_relaxed);
    return 0;
}
WINAPK_GUEST_ABI int g_mutex_destroy(void*) { return 0; }

WINAPK_GUEST_ABI int g_mutex_lock(void* m) {
    const int type = mutex_type(m);
    if (type != kNormal) {
        const int32_t self = static_cast<int32_t>(GetCurrentThreadId());
        if (word(m, 1)->load(std::memory_order_relaxed) == self) {
            if (type == kErrorCheck) return kEDEADLK;
            word(m, 2)->fetch_add(1, std::memory_order_relaxed);
            return 0;
        }
        lock_word(word(m, 0));
        word(m, 1)->store(self, std::memory_order_relaxed);
        word(m, 2)->store(1, std::memory_order_relaxed);
        return 0;
    }
    lock_word(word(m, 0));
    return 0;
}

WINAPK_GUEST_ABI int g_mutex_trylock(void* m) {
    const int type = mutex_type(m);
    const int32_t self = static_cast<int32_t>(GetCurrentThreadId());
    if (type == kRecursive && word(m, 1)->load(std::memory_order_relaxed) == self) {
        word(m, 2)->fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    if (!trylock_word(word(m, 0))) return kEBUSY;
    if (type != kNormal) {
        word(m, 1)->store(self, std::memory_order_relaxed);
        word(m, 2)->store(1, std::memory_order_relaxed);
    }
    return 0;
}

WINAPK_GUEST_ABI int g_mutex_unlock(void* m) {
    const int type = mutex_type(m);
    if (type != kNormal) {
        if (word(m, 1)->load(std::memory_order_relaxed) != static_cast<int32_t>(GetCurrentThreadId())) return kEPERM;
        if (word(m, 2)->fetch_sub(1, std::memory_order_relaxed) > 1) return 0;
        word(m, 1)->store(0, std::memory_order_relaxed);
    }
    unlock_word(word(m, 0));
    return 0;
}

int mutex_lock_until(void* m, const GuestTimespec* abstime, bool monotonic) {
    // Polls trylock; a timed mutex wait is rare enough not to need a waiter queue.
    for (;;) {
        if (g_mutex_trylock(m) == 0) return 0;
        if (ms_until(abstime, monotonic) == 0) return kETIMEDOUT;
        Sleep(1);
    }
}
WINAPK_GUEST_ABI int g_mutex_timedlock(void* m, const GuestTimespec* abstime) {
    return mutex_lock_until(m, abstime, false);
}
// The forms with the clock named (API 30, libc++'s timed_mutex) and Bionic's older ones. Missing, each
// answered 0 through the loader's fallback: "locked", with nothing locked.
WINAPK_GUEST_ABI int g_mutex_clocklock(void* m, int clock, const GuestTimespec* abstime) {
    return mutex_lock_until(m, abstime, clock == 1);
}
WINAPK_GUEST_ABI int g_mutex_timedlock_monotonic_np(void* m, const GuestTimespec* abstime) {
    return mutex_lock_until(m, abstime, true);
}
WINAPK_GUEST_ABI int g_mutex_lock_timeout_np(void* m, unsigned ms) {
    const ULONGLONG end = GetTickCount64() + ms;
    for (;;) {
        if (g_mutex_trylock(m) == 0) return 0;
        if (GetTickCount64() >= end) return kEBUSY;  // Bionic's answer for this one, not ETIMEDOUT
        Sleep(1);
    }
}

// ---- condition variable --------------------------------------------------------
// word 0: attributes (bit 0: CLOCK_MONOTONIC); word 1: a sequence number every
// signal bumps, so a waiter that saw it before unlocking never misses a wake.
WINAPK_GUEST_ABI int g_condattr_init(int64_t* a) { *a = 0; return 0; }
WINAPK_GUEST_ABI int g_condattr_destroy(int64_t*) { return 0; }
WINAPK_GUEST_ABI int g_condattr_setclock(int64_t* a, int clock) {
    *a = clock == 1 ? 1 : 0;  // CLOCK_MONOTONIC is 1
    return 0;
}
WINAPK_GUEST_ABI int g_condattr_getclock(const int64_t* a, int* clock) { *clock = (*a & 1) ? 1 : 0; return 0; }
WINAPK_GUEST_ABI int g_condattr_setpshared(int64_t*, int) { return 0; }

WINAPK_GUEST_ABI int g_cond_init(void* c, const int64_t* attr) {
    std::memset(c, 0, 48);
    word(c, 0)->store(attr && (*attr & 1) ? 1 : 0, std::memory_order_relaxed);
    return 0;
}
WINAPK_GUEST_ABI int g_cond_destroy(void*) { return 0; }

int cond_wait(void* c, void* m, DWORD ms) {
    std::atomic<int32_t>* seq = word(c, 1);
    const int32_t seen = seq->load(std::memory_order_relaxed);
    g_mutex_unlock(m);
    const bool woke = futex_wait(seq, seen, ms);
    g_mutex_lock(m);
    return woke ? 0 : kETIMEDOUT;
}
WINAPK_GUEST_ABI int g_cond_wait(void* c, void* m) { return cond_wait(c, m, INFINITE); }
WINAPK_GUEST_ABI int g_cond_timedwait(void* c, void* m, const GuestTimespec* abstime) {
    return cond_wait(c, m, ms_until(abstime, (word(c, 0)->load(std::memory_order_relaxed) & 1) != 0));
}
WINAPK_GUEST_ABI int g_cond_timedwait_monotonic_np(void* c, void* m, const GuestTimespec* abstime) {
    return cond_wait(c, m, ms_until(abstime, true));
}
WINAPK_GUEST_ABI int g_cond_clockwait(void* c, void* m, int clock, const GuestTimespec* abstime) {
    return cond_wait(c, m, ms_until(abstime, clock == 1));
}
WINAPK_GUEST_ABI int g_cond_signal(void* c) {
    word(c, 1)->fetch_add(1, std::memory_order_release);
    futex_wake_one(word(c, 1));
    return 0;
}
WINAPK_GUEST_ABI int g_cond_broadcast(void* c) {
    word(c, 1)->fetch_add(1, std::memory_order_release);
    futex_wake_all(word(c, 1));
    return 0;
}

// ---- read-write lock --------------------------------------------------------------
// word 0: -1 while a writer holds it, else the number of readers.
WINAPK_GUEST_ABI int g_rwlock_init(void* l, const void*) { std::memset(l, 0, 56); return 0; }
WINAPK_GUEST_ABI int g_rwlock_destroy(void*) { return 0; }
WINAPK_GUEST_ABI int g_rwlock_tryrdlock(void* l) {
    std::atomic<int32_t>* w = word(l, 0);
    int32_t v = w->load(std::memory_order_relaxed);
    while (v >= 0) {
        if (w->compare_exchange_weak(v, v + 1, std::memory_order_acquire)) return 0;
    }
    return kEBUSY;
}
WINAPK_GUEST_ABI int g_rwlock_rdlock(void* l) {
    while (g_rwlock_tryrdlock(l) != 0) futex_wait(word(l, 0), -1, INFINITE);
    return 0;
}
WINAPK_GUEST_ABI int g_rwlock_trywrlock(void* l) {
    int32_t expected = 0;
    return word(l, 0)->compare_exchange_strong(expected, -1, std::memory_order_acquire) ? 0 : kEBUSY;
}
WINAPK_GUEST_ABI int g_rwlock_wrlock(void* l) {
    std::atomic<int32_t>* w = word(l, 0);
    for (;;) {
        int32_t v = 0;
        if (w->compare_exchange_strong(v, -1, std::memory_order_acquire)) return 0;
        futex_wait(w, v, INFINITE);
    }
}
WINAPK_GUEST_ABI int g_rwlock_unlock(void* l) {
    std::atomic<int32_t>* w = word(l, 0);
    if (w->load(std::memory_order_relaxed) == -1) w->store(0, std::memory_order_release);
    else w->fetch_sub(1, std::memory_order_release);
    futex_wake_all(w);
    return 0;
}

// The timed forms poll, as the timed mutex does. Missing, they answered 0 ("locked") without a lock.
int rwlock_lock_until(void* l, bool write, const GuestTimespec* abstime, bool monotonic) {
    for (;;) {
        if ((write ? g_rwlock_trywrlock(l) : g_rwlock_tryrdlock(l)) == 0) return 0;
        if (ms_until(abstime, monotonic) == 0) return kETIMEDOUT;
        Sleep(1);
    }
}
WINAPK_GUEST_ABI int g_rwlock_timedrdlock(void* l, const GuestTimespec* t) {
    return rwlock_lock_until(l, false, t, false);
}
WINAPK_GUEST_ABI int g_rwlock_timedwrlock(void* l, const GuestTimespec* t) {
    return rwlock_lock_until(l, true, t, false);
}
WINAPK_GUEST_ABI int g_rwlock_timedrdlock_monotonic_np(void* l, const GuestTimespec* t) {
    return rwlock_lock_until(l, false, t, true);
}
WINAPK_GUEST_ABI int g_rwlock_timedwrlock_monotonic_np(void* l, const GuestTimespec* t) {
    return rwlock_lock_until(l, true, t, true);
}
WINAPK_GUEST_ABI int g_rwlock_clockrdlock(void* l, int clock, const GuestTimespec* t) {
    return rwlock_lock_until(l, false, t, clock == 1);
}
WINAPK_GUEST_ABI int g_rwlock_clockwrlock(void* l, int clock, const GuestTimespec* t) {
    return rwlock_lock_until(l, true, t, clock == 1);
}

// ---- spin lock (pthread_spinlock_t: one word, 0 free, 1 held) -----------------------
// Missing, pthread_spin_lock answered 0 through the loader's fallback: every caller "held" the lock at
// once.
WINAPK_GUEST_ABI int g_spin_init(void* l, int) {
    word(l, 0)->store(0, std::memory_order_release);
    return 0;
}
WINAPK_GUEST_ABI int g_spin_destroy(void*) { return 0; }
WINAPK_GUEST_ABI int g_spin_trylock(void* l) {
    int32_t expected = 0;
    return word(l, 0)->compare_exchange_strong(expected, 1, std::memory_order_acquire) ? 0 : kEBUSY;
}
WINAPK_GUEST_ABI int g_spin_lock(void* l) {
    for (unsigned spins = 0; g_spin_trylock(l) != 0; ++spins) {
        if (spins < 1000) YieldProcessor();
        else SwitchToThread();  // the holder may be descheduled
    }
    return 0;
}
WINAPK_GUEST_ABI int g_spin_unlock(void* l) {
    word(l, 0)->store(0, std::memory_order_release);
    return 0;
}

// ---- once ------------------------------------------------------------------------
using GuestVoid = void(WINAPK_GUEST_ABI*)();
WINAPK_GUEST_ABI int g_once(void* once, GuestVoid fn) {
    std::atomic<int32_t>* w = word(once, 0);  // 0 not run, 1 running, 2 done
    for (;;) {
        int32_t v = w->load(std::memory_order_acquire);
        if (v == 2) return 0;
        if (v == 0 && w->compare_exchange_strong(v, 1, std::memory_order_acquire)) {
            fn();
            w->store(2, std::memory_order_release);
            futex_wake_all(w);
            return 0;
        }
        futex_wait(w, 1, INFINITE);
    }
}

// ---- thread-specific data ----------------------------------------------------------
constexpr int kKeysMax = 128;  // Bionic's PTHREAD_KEYS_MAX
using GuestDtor = void(WINAPK_GUEST_ABI*)(void*);
std::mutex g_keys_lock;
bool g_key_used[kKeysMax];
GuestDtor g_key_dtor[kKeysMax];

thread_local void* t_values[kKeysMax];

WINAPK_GUEST_ABI int g_key_create(int32_t* key, GuestDtor dtor) {
    std::lock_guard<std::mutex> hold(g_keys_lock);
    for (int i = 0; i < kKeysMax; ++i) {
        if (!g_key_used[i]) {
            g_key_used[i] = true;
            g_key_dtor[i] = dtor;
            *key = i;
            return 0;
        }
    }
    return kEAGAIN;
}
WINAPK_GUEST_ABI int g_key_delete(int32_t key) {
    if (key < 0 || key >= kKeysMax) return kEINVAL;
    std::lock_guard<std::mutex> hold(g_keys_lock);
    g_key_used[key] = false;
    g_key_dtor[key] = nullptr;
    return 0;
}
WINAPK_GUEST_ABI void* g_getspecific(int32_t key) {
    return key >= 0 && key < kKeysMax ? t_values[key] : nullptr;
}
WINAPK_GUEST_ABI int g_setspecific(int32_t key, const void* v) {
    if (key < 0 || key >= kKeysMax) return kEINVAL;
    t_values[key] = const_cast<void*>(v);
    return 0;
}

// POSIX: up to PTHREAD_DESTRUCTOR_ITERATIONS rounds while values remain.
void run_key_destructors() {
    for (int round = 0; round < 4; ++round) {
        bool any = false;
        for (int i = 0; i < kKeysMax; ++i) {
            void* v = t_values[i];
            if (!v) continue;
            GuestDtor d;
            {
                std::lock_guard<std::mutex> hold(g_keys_lock);
                d = g_key_used[i] ? g_key_dtor[i] : nullptr;
            }
            t_values[i] = nullptr;
            if (d) {
                d(v);
                any = true;
            }
        }
        if (!any) return;
    }
}

// ---- threads ------------------------------------------------------------------------
using GuestStart = void*(WINAPK_GUEST_ABI*)(void*);

struct Thread {
    HANDLE handle = nullptr;
    DWORD tid = 0;
    GuestStart start = nullptr;
    void* arg = nullptr;
    void* result = nullptr;
    std::atomic<bool> detached{false};
    bool guest_created = false;
};

thread_local Thread* t_self = nullptr;

// pthread_cleanup_push/pop: Bionic's macros put a __pthread_cleanup_t on the
// caller's stack and link it into the thread's list with these two calls;
// pthread_exit runs whatever is still linked, newest first.
struct GuestCleanup {
    GuestCleanup* prev;
    GuestDtor routine;
    void* arg;
};
thread_local GuestCleanup* t_cleanup = nullptr;

WINAPK_GUEST_ABI void g_cleanup_push(GuestCleanup* c, GuestDtor routine, void* arg) {
    c->routine = routine;
    c->arg = arg;
    c->prev = t_cleanup;
    t_cleanup = c;
}
WINAPK_GUEST_ABI void g_cleanup_pop(GuestCleanup* c, int execute) {
    t_cleanup = c->prev;
    if (execute) c->routine(c->arg);
}

Thread* self_record() {
    if (!t_self) {
        t_self = new Thread();
        t_self->tid = GetCurrentThreadId();
        DuplicateHandle(GetCurrentProcess(), GetCurrentThread(), GetCurrentProcess(), &t_self->handle, 0, FALSE,
                        DUPLICATE_SAME_ACCESS);
    }
    return t_self;
}

[[noreturn]] void finish_thread(Thread* t, void* result) {
    t->result = result;
    // Bionic's order: C++ thread_local destructors, the cleanup handlers still
    // pushed, then the key destructors.
    tls_run_thread_dtors();
    while (GuestCleanup* c = t_cleanup) {
        t_cleanup = c->prev;
        c->routine(c->arg);
    }
    run_key_destructors();
    if (t->detached.load()) {
        CloseHandle(t->handle);
        delete t;
    }
    ExitThread(0);
}

DWORD WINAPI thread_main(void* p) {
    auto* t = static_cast<Thread*>(p);
    t_self = t;
    arm64::enter_thread();                    // an arm64 host: the TLS block behind TPIDR_EL0 (arm64_guest.h)
    commit_guest_stack_for_current_thread();  // the stack a Linux thread would have mapped
    signal_altstack_for_current_thread();     // and its alternate signal stack, as Bionic's
    finish_thread(t, t->start(t->arg));
}

// pthread_attr_t (56 bytes): flags, stack_base, stack_size, guard_size,
// sched_policy, sched_priority.
struct GuestAttr {
    uint32_t flags;
    void* stack_base;
    size_t stack_size;
    size_t guard_size;
    int32_t sched_policy;
    int32_t sched_priority;
    char reserved[16];
};
static_assert(sizeof(GuestAttr) == 56, "Bionic's pthread_attr_t");
constexpr uint32_t kAttrDetached = 1;
constexpr size_t kDefaultStack = 1024 * 1024 - 4096 * 2;  // Bionic's default, less the guards

WINAPK_GUEST_ABI int g_attr_init(GuestAttr* a) {
    std::memset(a, 0, sizeof(*a));
    a->stack_size = kDefaultStack;
    a->guard_size = 4096;
    return 0;
}
WINAPK_GUEST_ABI int g_attr_destroy(GuestAttr*) { return 0; }
WINAPK_GUEST_ABI int g_attr_setdetachstate(GuestAttr* a, int s) {
    a->flags = s ? (a->flags | kAttrDetached) : (a->flags & ~kAttrDetached);
    return 0;
}
WINAPK_GUEST_ABI int g_attr_getdetachstate(const GuestAttr* a, int* s) { *s = (a->flags & kAttrDetached) ? 1 : 0; return 0; }
WINAPK_GUEST_ABI int g_attr_setstacksize(GuestAttr* a, size_t n) {
    if (n < 16384) return kEINVAL;
    a->stack_size = n;
    return 0;
}
WINAPK_GUEST_ABI int g_attr_getstacksize(const GuestAttr* a, size_t* n) { *n = a->stack_size; return 0; }
// A caller-provided stack cannot be used (CreateThread allocates its own);
// its size is honoured, and getstack reads back what was set.
WINAPK_GUEST_ABI int g_attr_setstack(GuestAttr* a, void* base, size_t n) {
    a->stack_base = base;
    a->stack_size = n;
    return 0;
}
WINAPK_GUEST_ABI int g_attr_getstack(const GuestAttr* a, void** base, size_t* n) {
    *base = a->stack_base;
    *n = a->stack_size;
    return 0;
}
WINAPK_GUEST_ABI int g_attr_setguardsize(GuestAttr* a, size_t n) { a->guard_size = n; return 0; }
WINAPK_GUEST_ABI int g_attr_getguardsize(const GuestAttr* a, size_t* n) { *n = a->guard_size; return 0; }
WINAPK_GUEST_ABI int g_attr_setschedpolicy(GuestAttr* a, int p) { a->sched_policy = p; return 0; }
WINAPK_GUEST_ABI int g_attr_getschedpolicy(const GuestAttr* a, int* p) { *p = a->sched_policy; return 0; }
WINAPK_GUEST_ABI int g_attr_setschedparam(GuestAttr* a, const int32_t* prio) { a->sched_priority = *prio; return 0; }
WINAPK_GUEST_ABI int g_attr_getschedparam(const GuestAttr* a, int32_t* prio) { *prio = a->sched_priority; return 0; }
WINAPK_GUEST_ABI int g_attr_setscope(GuestAttr*, int scope) { return scope == 0 ? 0 : kENOTSUP; }
WINAPK_GUEST_ABI int g_attr_setinheritsched(GuestAttr*, int) { return 0; }

WINAPK_GUEST_ABI int g_create(Thread** out, const GuestAttr* attr, GuestStart start, void* arg) {
    auto* t = new Thread();
    t->start = start;
    t->arg = arg;
    t->guest_created = true;
    t->detached = attr && (attr->flags & kAttrDetached);
    const size_t stack = attr && attr->stack_size ? attr->stack_size : kDefaultStack;
    t->handle = CreateThread(nullptr, stack, thread_main, t, CREATE_SUSPENDED | STACK_SIZE_PARAM_IS_A_RESERVATION,
                             &t->tid);
    if (!t->handle) {
        delete t;
        return kEAGAIN;
    }
    *out = t;
    ResumeThread(t->handle);
    return 0;
}

WINAPK_GUEST_ABI int g_join(Thread* t, void** result) {
    if (!t) return kEINVAL;
    if (t == t_self) return kEDEADLK;
    WaitForSingleObject(t->handle, INFINITE);
    if (result) *result = t->result;
    CloseHandle(t->handle);
    delete t;
    return 0;
}
WINAPK_GUEST_ABI int g_detach(Thread* t) {
    if (!t) return kEINVAL;
    t->detached = true;
    // Already finished: nobody else will free it.
    if (WaitForSingleObject(t->handle, 0) == WAIT_OBJECT_0) {
        CloseHandle(t->handle);
        delete t;
    }
    return 0;
}
WINAPK_GUEST_ABI Thread* g_self() { return self_record(); }
WINAPK_GUEST_ABI int g_equal(Thread* a, Thread* b) { return a == b; }
[[noreturn]] WINAPK_GUEST_ABI void g_exit(void* result) { finish_thread(self_record(), result); }
WINAPK_GUEST_ABI int g_gettid() { return static_cast<int>(GetCurrentThreadId()); }
WINAPK_GUEST_ABI int g_pthread_gettid_np(Thread* t) { return t ? static_cast<int>(t->tid) : -1; }

WINAPK_GUEST_ABI int g_setname_np(Thread* t, const char* name) {
    using SetDescription = HRESULT(WINAPI*)(HANDLE, PCWSTR);
    static const auto set = reinterpret_cast<SetDescription>(
        GetProcAddress(GetModuleHandleW(L"kernel32.dll"), "SetThreadDescription"));
    if (!set || !t || !name) return 0;
    wchar_t w[64];
    MultiByteToWideChar(CP_UTF8, 0, name, -1, w, 64);
    w[63] = L'\0';
    set(t->handle, w);
    return 0;
}
// prctl(2): the options apps use at startup. PR_SET_NAME names the calling
// thread (Linux keeps 15 characters); PR_SET_VMA (naming anonymous memory) and
// the process-wide flags are accepted and have no effect here.
thread_local char t_prctl_name[16];
WINAPK_GUEST_ABI int g_prctl(int option, uint64_t a2, uint64_t, uint64_t, uint64_t) {
    switch (option) {
        case 15:  // PR_SET_NAME
            std::strncpy(t_prctl_name, reinterpret_cast<const char*>(a2), 15);
            t_prctl_name[15] = '\0';
            g_setname_np(self_record(), t_prctl_name);
            return 0;
        case 16:  // PR_GET_NAME
            std::memcpy(reinterpret_cast<char*>(a2), t_prctl_name, 16);
            return 0;
        case 3: return 1;  // PR_GET_DUMPABLE
        case 1: case 4: case 29: case 36: case 38: case 0x53564d41: return 0;
        // PR_SET_PDEATHSIG, PR_SET_DUMPABLE, PR_SET_TIMERSLACK,
        // PR_SET_CHILD_SUBREAPER, PR_SET_NO_NEW_PRIVS, PR_SET_VMA
        default:
            *guest_errno() = kEINVAL;
            return -1;
    }
}
WINAPK_GUEST_ABI int g_getname_np(Thread*, char* buf, size_t n) {
    if (n) buf[0] = '\0';
    return 0;
}

// The calling thread's stack, which ART, V8 and Mono read to size their own
// guard regions.
WINAPK_GUEST_ABI int g_getattr_np(Thread* t, GuestAttr* a) {
    g_attr_init(a);
    if (t && t->detached.load()) a->flags |= kAttrDetached;
    if (t == t_self || !t) {
        ULONG_PTR lo = 0, hi = 0;
        GetCurrentThreadStackLimits(&lo, &hi);
        a->stack_base = reinterpret_cast<void*>(lo);
        a->stack_size = hi - lo;
    }
    return 0;
}

// pthread_kill: tgkill on the thread's id (signal.cpp delivers or injects).
WINAPK_GUEST_ABI int g_kill(Thread* t, int sig) {
    if (!t) return kESRCH;
    return signal_send(t->tid, sig);
}
WINAPK_GUEST_ABI int g_atfork(void*, void*, void*) { return 0; }
WINAPK_GUEST_ABI int g_sched_yield() { SwitchToThread(); return 0; }
WINAPK_GUEST_ABI int g_setschedparam(Thread*, int, const int32_t*) { return 0; }
WINAPK_GUEST_ABI int g_getschedparam(Thread*, int* policy, int32_t* prio) {
    *policy = 0;
    *prio = 0;
    return 0;
}

// ---- semaphores (sem_t: an unsigned count, then reserved words) -----------------------
WINAPK_GUEST_ABI int g_sem_init(void* s, int, unsigned value) {
    std::memset(s, 0, 16);
    word(s, 0)->store(static_cast<int32_t>(value), std::memory_order_relaxed);
    return 0;
}
WINAPK_GUEST_ABI int g_sem_destroy(void*) { return 0; }
WINAPK_GUEST_ABI int g_sem_trywait(void* s) {
    std::atomic<int32_t>* w = word(s, 0);
    int32_t v = w->load(std::memory_order_relaxed);
    while (v > 0) {
        if (w->compare_exchange_weak(v, v - 1, std::memory_order_acquire)) return 0;
    }
    *guest_errno() = kEAGAIN;
    return -1;
}
int sem_wait_ms(void* s, const GuestTimespec* abstime, bool monotonic) {
    for (;;) {
        if (g_sem_trywait(s) == 0) return 0;
        const DWORD ms = ms_until(abstime, monotonic);
        if (ms == 0) {
            *guest_errno() = kETIMEDOUT;
            return -1;
        }
        futex_wait(word(s, 0), 0, ms);
    }
}
WINAPK_GUEST_ABI int g_sem_wait(void* s) { return sem_wait_ms(s, nullptr, false); }
WINAPK_GUEST_ABI int g_sem_timedwait(void* s, const GuestTimespec* t) { return sem_wait_ms(s, t, false); }
WINAPK_GUEST_ABI int g_sem_timedwait_monotonic_np(void* s, const GuestTimespec* t) { return sem_wait_ms(s, t, true); }
// sem_clockwait (API 30). Missing, it answered 0: the semaphore "taken" at once, however often.
WINAPK_GUEST_ABI int g_sem_clockwait(void* s, int clock, const GuestTimespec* t) {
    return sem_wait_ms(s, t, clock == 1);
}
WINAPK_GUEST_ABI int g_sem_post(void* s) {
    word(s, 0)->fetch_add(1, std::memory_order_release);
    futex_wake_one(word(s, 0));
    return 0;
}
WINAPK_GUEST_ABI int g_sem_getvalue(void* s, int* v) {
    *v = word(s, 0)->load(std::memory_order_relaxed);
    return 0;
}

const ShimSymbol kSymbols[] = {
    WINAPK_SHIM("pthread_mutexattr_init", g_mutexattr_init),
    WINAPK_SHIM("pthread_mutexattr_destroy", g_mutexattr_destroy),
    WINAPK_SHIM("pthread_mutexattr_settype", g_mutexattr_settype),
    WINAPK_SHIM("pthread_mutexattr_gettype", g_mutexattr_gettype),
    WINAPK_SHIM("pthread_mutexattr_setpshared", g_mutexattr_setpshared),
    WINAPK_SHIM("pthread_mutex_init", g_mutex_init),
    WINAPK_SHIM("pthread_mutex_destroy", g_mutex_destroy),
    WINAPK_SHIM("pthread_mutex_lock", g_mutex_lock),
    WINAPK_SHIM("pthread_mutex_trylock", g_mutex_trylock),
    WINAPK_SHIM("pthread_mutex_unlock", g_mutex_unlock),
    WINAPK_SHIM("pthread_mutex_timedlock", g_mutex_timedlock),
    WINAPK_SHIM("pthread_mutex_clocklock", g_mutex_clocklock),
    WINAPK_SHIM("pthread_mutex_timedlock_monotonic_np", g_mutex_timedlock_monotonic_np),
    WINAPK_SHIM("pthread_mutex_lock_timeout_np", g_mutex_lock_timeout_np),
    WINAPK_SHIM("pthread_condattr_init", g_condattr_init),
    WINAPK_SHIM("pthread_condattr_destroy", g_condattr_destroy),
    WINAPK_SHIM("pthread_condattr_setclock", g_condattr_setclock),
    WINAPK_SHIM("pthread_condattr_getclock", g_condattr_getclock),
    WINAPK_SHIM("pthread_condattr_setpshared", g_condattr_setpshared),
    WINAPK_SHIM("pthread_cond_init", g_cond_init),
    WINAPK_SHIM("pthread_cond_destroy", g_cond_destroy),
    WINAPK_SHIM("pthread_cond_wait", g_cond_wait),
    WINAPK_SHIM("pthread_cond_timedwait", g_cond_timedwait),
    WINAPK_SHIM("pthread_cond_timedwait_monotonic_np", g_cond_timedwait_monotonic_np),
    WINAPK_SHIM("pthread_cond_timedwait_monotonic", g_cond_timedwait_monotonic_np),
    WINAPK_SHIM("pthread_cond_clockwait", g_cond_clockwait),
    WINAPK_SHIM("pthread_cond_signal", g_cond_signal),
    WINAPK_SHIM("pthread_cond_broadcast", g_cond_broadcast),
    WINAPK_SHIM("pthread_rwlock_init", g_rwlock_init),
    WINAPK_SHIM("pthread_rwlock_destroy", g_rwlock_destroy),
    WINAPK_SHIM("pthread_rwlock_rdlock", g_rwlock_rdlock),
    WINAPK_SHIM("pthread_rwlock_tryrdlock", g_rwlock_tryrdlock),
    WINAPK_SHIM("pthread_rwlock_wrlock", g_rwlock_wrlock),
    WINAPK_SHIM("pthread_rwlock_trywrlock", g_rwlock_trywrlock),
    WINAPK_SHIM("pthread_rwlock_unlock", g_rwlock_unlock),
    WINAPK_SHIM("pthread_rwlock_timedrdlock", g_rwlock_timedrdlock),
    WINAPK_SHIM("pthread_rwlock_timedwrlock", g_rwlock_timedwrlock),
    WINAPK_SHIM("pthread_rwlock_timedrdlock_monotonic_np", g_rwlock_timedrdlock_monotonic_np),
    WINAPK_SHIM("pthread_rwlock_timedwrlock_monotonic_np", g_rwlock_timedwrlock_monotonic_np),
    WINAPK_SHIM("pthread_rwlock_clockrdlock", g_rwlock_clockrdlock),
    WINAPK_SHIM("pthread_rwlock_clockwrlock", g_rwlock_clockwrlock),
    WINAPK_SHIM("pthread_spin_init", g_spin_init),
    WINAPK_SHIM("pthread_spin_destroy", g_spin_destroy),
    WINAPK_SHIM("pthread_spin_lock", g_spin_lock),
    WINAPK_SHIM("pthread_spin_trylock", g_spin_trylock),
    WINAPK_SHIM("pthread_spin_unlock", g_spin_unlock),
    WINAPK_SHIM("pthread_once", g_once),
    WINAPK_SHIM("pthread_key_create", g_key_create),
    WINAPK_SHIM("pthread_key_delete", g_key_delete),
    WINAPK_SHIM("pthread_getspecific", g_getspecific),
    WINAPK_SHIM("pthread_setspecific", g_setspecific),
    WINAPK_SHIM("pthread_attr_init", g_attr_init),
    WINAPK_SHIM("pthread_attr_destroy", g_attr_destroy),
    WINAPK_SHIM("pthread_attr_setdetachstate", g_attr_setdetachstate),
    WINAPK_SHIM("pthread_attr_getdetachstate", g_attr_getdetachstate),
    WINAPK_SHIM("pthread_attr_setstacksize", g_attr_setstacksize),
    WINAPK_SHIM("pthread_attr_getstacksize", g_attr_getstacksize),
    WINAPK_SHIM("pthread_attr_setstack", g_attr_setstack),
    WINAPK_SHIM("pthread_attr_getstack", g_attr_getstack),
    WINAPK_SHIM("pthread_attr_setguardsize", g_attr_setguardsize),
    WINAPK_SHIM("pthread_attr_getguardsize", g_attr_getguardsize),
    WINAPK_SHIM("pthread_attr_setschedpolicy", g_attr_setschedpolicy),
    WINAPK_SHIM("pthread_attr_getschedpolicy", g_attr_getschedpolicy),
    WINAPK_SHIM("pthread_attr_setschedparam", g_attr_setschedparam),
    WINAPK_SHIM("pthread_attr_getschedparam", g_attr_getschedparam),
    WINAPK_SHIM("pthread_attr_setscope", g_attr_setscope),
    WINAPK_SHIM("pthread_attr_setinheritsched", g_attr_setinheritsched),
    WINAPK_SHIM("pthread_create", g_create),
    WINAPK_SHIM("pthread_join", g_join),
    WINAPK_SHIM("pthread_detach", g_detach),
    WINAPK_SHIM("pthread_self", g_self),
    WINAPK_SHIM("pthread_equal", g_equal),
    WINAPK_SHIM("pthread_exit", g_exit),
    WINAPK_SHIM("__pthread_cleanup_push", g_cleanup_push),
    WINAPK_SHIM("__pthread_cleanup_pop", g_cleanup_pop),
    WINAPK_SHIM("pthread_gettid_np", g_pthread_gettid_np),
    WINAPK_SHIM("pthread_setname_np", g_setname_np),
    WINAPK_SHIM("prctl", g_prctl),
    WINAPK_SHIM("pthread_getname_np", g_getname_np),
    WINAPK_SHIM("pthread_getattr_np", g_getattr_np),
    WINAPK_SHIM("pthread_kill", g_kill),
    WINAPK_SHIM("pthread_atfork", g_atfork),
    WINAPK_SHIM("pthread_setschedparam", g_setschedparam),
    WINAPK_SHIM("pthread_getschedparam", g_getschedparam),
    WINAPK_SHIM("gettid", g_gettid),
    WINAPK_SHIM("sched_yield", g_sched_yield),
    WINAPK_SHIM("sem_init", g_sem_init),
    WINAPK_SHIM("sem_destroy", g_sem_destroy),
    WINAPK_SHIM("sem_wait", g_sem_wait),
    WINAPK_SHIM("sem_trywait", g_sem_trywait),
    WINAPK_SHIM("sem_timedwait", g_sem_timedwait),
    WINAPK_SHIM("sem_timedwait_monotonic_np", g_sem_timedwait_monotonic_np),
    WINAPK_SHIM("sem_clockwait", g_sem_clockwait),
    WINAPK_SHIM("sem_post", g_sem_post),
    WINAPK_SHIM("sem_getvalue", g_sem_getvalue),
};

}  // namespace

uint32_t thread_id_of(const void* pthread) {
    return pthread ? static_cast<const Thread*>(pthread)->tid : 0;
}

ShimTable pthread_symbols() { return WINAPK_SHIM_TABLE(kSymbols); }

}  // namespace winapk::bionic
