// bionic_shim_binder_ndk.cpp -- the guest's libbinder_ndk.so: Android's NDK
// binder C API (AIBinder_*, AParcel_*, AStatus_*, AServiceManager_*), served
// by an in-process implementation.
//
// WHY THIS FILE EXISTS
// --------------------
// AI Douyin (com.ss.android.ugc.aweme.hubble 40.7.0, kksimp/Mac.apk#105) ships
// two Qualcomm Snapdragon Services libraries (libsnapdragon_services_adk.qti.so,
// libsnapdragon_services_qape.qti.so). Their init_array calls the NDK binder C
// API, every symbol was unresolved, and the arm64 loader aborts the process on
// the first call ("UNRESOLVED NATIVE SYMBOL CALLED"). libbinder_ndk was listed
// as a Bionic public library for dlopen but exported nothing.
//
// WHAT THIS IS, AND WHAT IT IS NOT
// --------------------------------
// macOS has no binder driver and no servicemanager, so there is exactly one kind
// of binder here: a LOCAL one, created by AIBinder_new in this process. That is
// the faithful model of an app on a device that has no remote service to reach:
//   - AIBinder_new / transact / prepareTransaction / Weak / DeathRecipient follow
//     libbinder_ndk (frameworks/native/libs/binder/ndk/ibinder.cpp): strong and
//     weak counts, onCreate/onDestroy, a local transact calls the class's
//     onTransact directly with an in parcel and an out parcel.
//   - AIBinder_linkToDeath/unlinkToDeath on a local binder return
//     STATUS_INVALID_OPERATION, exactly as ibinder.cpp does for !isRemote().
//   - AServiceManager_checkService/getService/waitForService look a name up in a
//     PROCESS-LOCAL registry fed by AServiceManager_addService; a name nobody
//     added answers NULL, which is what a device answers for a service that is
//     not running or not declared. waitForService returns NULL at once rather
//     than blocking, as a device does for an undeclared instance.
//   - AIBinder_toJavaBinder / fromJavaBinder answer NULL ("failure" per the
//     header): there is no Java<->native binder bridge for a native-only binder.
//   - AParcel is a growable byte buffer with a cursor. Its byte layout is this
//     file's own (nothing outside the process ever reads it, because there is no
//     kernel transport), so only write/read symmetry is the contract.
// Nothing is faked as a present remote service; vendor probe libraries get the
// real "no such service" answer and take their own fallback.
//
// ABI: the API is plain C (no variadics, no by-value structs); Apple arm64 and
// AAPCS64 agree for register arguments, and the guest function pointers
// (onCreate/onTransact/...) are called like any other guest callback.
//
// Signatures: NDK sysroot android/binder_ibinder.h, binder_parcel.h,
// binder_status.h (API 29), binder_manager.h, binder_ibinder_jni.h.
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include <unistd.h>

namespace {

using binder_status_t = int32_t;
using binder_exception_t = int32_t;
using transaction_code_t = uint32_t;
using binder_flags_t = uint32_t;

constexpr binder_status_t kStatusOk = 0;
constexpr binder_status_t kStatusUnknownError = INT32_MIN;
constexpr binder_status_t kStatusBadValue = -EINVAL;
constexpr binder_status_t kStatusInvalidOperation = -ENOSYS;
constexpr binder_status_t kStatusNoInit = -ENODEV;
constexpr binder_status_t kStatusNotEnoughData = -ENODATA;
constexpr binder_status_t kStatusNoMemory = -ENOMEM;
constexpr binder_status_t kStatusBadType = kStatusUnknownError + 1;
constexpr binder_status_t kStatusUnexpectedNull = kStatusUnknownError + 8;
constexpr binder_exception_t kExNone = 0;
constexpr binder_exception_t kExServiceSpecific = -8;
constexpr binder_exception_t kExTransactionFailed = -129;
constexpr binder_flags_t kFlagOneway = 1;

struct AParcel;
struct AIBinder;

typedef void* (*OnCreate)(void* args);
typedef void (*OnDestroy)(void* userData);
typedef binder_status_t (*OnTransact)(AIBinder*, transaction_code_t, const AParcel*, AParcel*);
typedef binder_status_t (*OnDump)(AIBinder*, int fd, const char** args, uint32_t numArgs);

struct AIBinder_Class {
    std::string descriptor;
    OnCreate onCreate;
    OnDestroy onDestroy;
    OnTransact onTransact;
    OnDump onDump = nullptr;
};

// ibinder.cpp keeps strong + weak counts; the object is freed when both reach
// zero, so an AIBinder_Weak can outlive the last strong reference safely.
struct AIBinder {
    const AIBinder_Class* clazz = nullptr;
    void* userData = nullptr;
    std::atomic<int32_t> strong{1};
    std::atomic<int32_t> weak{1};  // one implicit weak held while strong > 0
};

struct AIBinder_Weak { AIBinder* binder; };

struct AIBinder_DeathRecipient {
    void (*onDied)(void* cookie);
    void (*onUnlinked)(void* cookie) = nullptr;
};

struct AStatus {
    binder_exception_t exception = kExNone;
    binder_status_t status = kStatusOk;
    int32_t serviceSpecific = 0;
    std::string message;
};

void weakRelease(AIBinder* b) {
    if (b->weak.fetch_sub(1) == 1) delete b;
}

void strongRelease(AIBinder* b) {
    if (b->strong.fetch_sub(1) == 1) {
        if (b->clazz && b->clazz->onDestroy) b->clazz->onDestroy(b->userData);
        weakRelease(b);  // the implicit weak
    }
}

struct AParcel {
    std::vector<uint8_t> data;
    size_t pos = 0;
    std::vector<AIBinder*> held;  // strong refs for binders written into this parcel

    bool put(const void* p, size_t n, size_t align = 4) {
        size_t padded = (n + align - 1) & ~(align - 1);
        if (pos > data.size()) return false;
        if (pos + padded > data.size()) data.resize(pos + padded, 0);
        std::memcpy(data.data() + pos, p, n);
        if (padded > n) std::memset(data.data() + pos + n, 0, padded - n);
        pos += padded;
        return true;
    }
    bool get(void* p, size_t n, size_t align = 4) {
        size_t padded = (n + align - 1) & ~(align - 1);
        if (pos + padded > data.size()) return false;
        std::memcpy(p, data.data() + pos, n);
        pos += padded;
        return true;
    }
};

template <typename T> binder_status_t wr(AParcel* p, T v) {
    if (!p) return kStatusUnexpectedNull;
    return p->put(&v, sizeof(T), sizeof(T) < 4 ? 4 : sizeof(T)) ? kStatusOk : kStatusNoMemory;
}
template <typename T> binder_status_t rd(const AParcel* cp, T* v) {
    AParcel* p = const_cast<AParcel*>(cp);
    if (!p || !v) return kStatusUnexpectedNull;
    return p->get(v, sizeof(T), sizeof(T) < 4 ? 4 : sizeof(T)) ? kStatusOk : kStatusNotEnoughData;
}

template <typename T> binder_status_t wrArray(AParcel* p, const T* a, int32_t len) {
    if (!p) return kStatusUnexpectedNull;
    if (len < -1 || (len > 0 && !a)) return kStatusBadValue;
    if (len == -1 || (!a && len == 0)) { return wr<int32_t>(p, a ? 0 : -1); }
    binder_status_t s = wr<int32_t>(p, len);
    if (s != kStatusOk) return s;
    return p->put(a, sizeof(T) * (size_t)len) ? kStatusOk : kStatusNoMemory;
}
template <typename T>
binder_status_t rdArray(const AParcel* cp, void* arr, bool (*alloc)(void*, int32_t, T**)) {
    AParcel* p = const_cast<AParcel*>(cp);
    int32_t len;
    binder_status_t s = rd<int32_t>(cp, &len);
    if (s != kStatusOk) return s;
    if (len < -1) return kStatusBadValue;
    T* out = nullptr;
    if (!alloc(arr, len, &out)) return kStatusNoMemory;
    if (len <= 0) return kStatusOk;
    if (!out) return kStatusNoMemory;
    return p->get(out, sizeof(T) * (size_t)len) ? kStatusOk : kStatusNotEnoughData;
}

std::mutex g_svcMu;
std::map<std::string, AIBinder*>& svcMap() {
    static auto* m = new std::map<std::string, AIBinder*>();
    return *m;
}

}  // namespace

#define EXPORT extern "C" __attribute__((visibility("default")))
#define SHIM(ret, name) EXPORT ret __macapk_arm64_##name

// ---- AStatus ----------------------------------------------------------------
SHIM(AStatus*, AStatus_newOk)() { return new AStatus(); }
SHIM(AStatus*, AStatus_fromExceptionCode)(binder_exception_t e) {
    auto* s = new AStatus(); s->exception = e; return s;
}
SHIM(AStatus*, AStatus_fromExceptionCodeWithMessage)(binder_exception_t e, const char* m) {
    auto* s = new AStatus(); s->exception = e; if (m) s->message = m; return s;
}
SHIM(AStatus*, AStatus_fromServiceSpecificError)(int32_t c) {
    auto* s = new AStatus(); s->exception = kExServiceSpecific; s->serviceSpecific = c; return s;
}
SHIM(AStatus*, AStatus_fromServiceSpecificErrorWithMessage)(int32_t c, const char* m) {
    auto* s = new AStatus(); s->exception = kExServiceSpecific; s->serviceSpecific = c;
    if (m) s->message = m; return s;
}
// Status::fromStatusT: OK is ok(); anything else is EX_TRANSACTION_FAILED + code.
SHIM(AStatus*, AStatus_fromStatus)(binder_status_t st) {
    auto* s = new AStatus();
    if (st != kStatusOk) { s->exception = kExTransactionFailed; s->status = st; }
    return s;
}
SHIM(bool, AStatus_isOk)(const AStatus* s) { return s && s->exception == kExNone; }
SHIM(binder_exception_t, AStatus_getExceptionCode)(const AStatus* s) { return s ? s->exception : kExNone; }
SHIM(int32_t, AStatus_getServiceSpecificError)(const AStatus* s) {
    return (s && s->exception == kExServiceSpecific) ? s->serviceSpecific : 0;
}
SHIM(binder_status_t, AStatus_getStatus)(const AStatus* s) { return s ? s->status : kStatusOk; }
SHIM(const char*, AStatus_getMessage)(const AStatus* s) {
    return (s && !s->message.empty()) ? s->message.c_str() : nullptr;
}
SHIM(void, AStatus_delete)(AStatus* s) { delete s; }

// ---- AIBinder_Class / AIBinder ---------------------------------------------
SHIM(AIBinder_Class*, AIBinder_Class_define)(const char* desc, OnCreate c, OnDestroy d, OnTransact t) {
    if (!desc || !c || !d || !t) return nullptr;
    // ibinder.cpp keeps a registry keyed by descriptor and refuses a redefine
    // with different callbacks; a leaked per-call class is the same to callers
    // that define once, and a redefine returns the first class when equal.
    static std::mutex mu;
    static auto* classes = new std::map<std::string, AIBinder_Class*>();
    std::lock_guard<std::mutex> g(mu);
    auto it = classes->find(desc);
    if (it != classes->end()) {
        auto* e = it->second;
        return (e->onCreate == c && e->onDestroy == d && e->onTransact == t) ? e : nullptr;
    }
    auto* k = new AIBinder_Class{desc, c, d, t};
    (*classes)[desc] = k;
    return k;
}
SHIM(void, AIBinder_Class_setOnDump)(AIBinder_Class* k, OnDump d) { if (k) k->onDump = d; }

SHIM(AIBinder*, AIBinder_new)(const AIBinder_Class* k, void* args) {
    if (!k) return nullptr;
    auto* b = new AIBinder();
    b->clazz = k;
    b->userData = k->onCreate ? k->onCreate(args) : nullptr;
    return b;
}
SHIM(bool, AIBinder_isRemote)(const AIBinder*) { return false; }
SHIM(bool, AIBinder_isAlive)(const AIBinder*) { return true; }
SHIM(binder_status_t, AIBinder_ping)(AIBinder* b) { return b ? kStatusOk : kStatusUnexpectedNull; }
SHIM(binder_status_t, AIBinder_dump)(AIBinder* b, int fd, const char** args, uint32_t n) {
    if (!b) return kStatusUnexpectedNull;
    if (!b->clazz || !b->clazz->onDump) return kStatusOk;
    return b->clazz->onDump(b, fd, args, n);
}
// ibinder.cpp: only a REMOTE binder can be linked to death.
SHIM(binder_status_t, AIBinder_linkToDeath)(AIBinder* b, AIBinder_DeathRecipient*, void*) {
    return b ? kStatusInvalidOperation : kStatusUnexpectedNull;
}
SHIM(binder_status_t, AIBinder_unlinkToDeath)(AIBinder* b, AIBinder_DeathRecipient*, void*) {
    return b ? kStatusInvalidOperation : kStatusUnexpectedNull;
}
SHIM(uid_t, AIBinder_getCallingUid)() { return getuid(); }
SHIM(pid_t, AIBinder_getCallingPid)() { return getpid(); }
SHIM(void, AIBinder_incStrong)(AIBinder* b) { if (b) b->strong.fetch_add(1); }
SHIM(void, AIBinder_decStrong)(AIBinder* b) { if (b) strongRelease(b); }
SHIM(int32_t, AIBinder_debugGetRefCount)(AIBinder* b) { return b ? b->strong.load() : 0; }
SHIM(bool, AIBinder_associateClass)(AIBinder* b, const AIBinder_Class* k) {
    if (!b || !k) return false;
    if (!b->clazz) { b->clazz = k; return true; }
    return b->clazz == k;
}
SHIM(const AIBinder_Class*, AIBinder_getClass)(AIBinder* b) { return b ? b->clazz : nullptr; }
SHIM(void*, AIBinder_getUserData)(AIBinder* b) { return b ? b->userData : nullptr; }

SHIM(binder_status_t, AIBinder_prepareTransaction)(AIBinder* b, AParcel** in) {
    if (!b || !in) return kStatusUnexpectedNull;
    if (!b->clazz) return kStatusBadType;
    *in = new AParcel();
    return kStatusOk;
}
// A local transaction: run the class's onTransact on the calling thread with
// the in parcel rewound; the in parcel is consumed (nulled) as the header says.
SHIM(binder_status_t, AIBinder_transact)(AIBinder* b, transaction_code_t code, AParcel** in,
                                         AParcel** out, binder_flags_t flags) {
    if (!b || !in || !*in) return kStatusUnexpectedNull;
    if (!b->clazz || !b->clazz->onTransact) { delete *in; *in = nullptr; return kStatusBadType; }
    AParcel* inP = *in;
    *in = nullptr;
    inP->pos = 0;
    AParcel* outP = new AParcel();
    binder_status_t st = b->clazz->onTransact(b, code, inP, outP);
    for (AIBinder* h : inP->held) strongRelease(h);
    delete inP;
    if ((flags & kFlagOneway) || !out) { for (AIBinder* h : outP->held) strongRelease(h); delete outP; }
    else { outP->pos = 0; *out = outP; }
    return st;
}

SHIM(AIBinder_Weak*, AIBinder_Weak_new)(AIBinder* b) {
    if (!b) return nullptr;
    b->weak.fetch_add(1);
    return new AIBinder_Weak{b};
}
SHIM(void, AIBinder_Weak_delete)(AIBinder_Weak* w) {
    if (!w) return;
    weakRelease(w->binder);
    delete w;
}
SHIM(AIBinder*, AIBinder_Weak_promote)(AIBinder_Weak* w) {
    if (!w) return nullptr;
    int32_t n = w->binder->strong.load();
    while (n > 0) {
        if (w->binder->strong.compare_exchange_weak(n, n + 1)) return w->binder;
    }
    return nullptr;
}

SHIM(AIBinder_DeathRecipient*, AIBinder_DeathRecipient_new)(void (*onDied)(void*)) {
    if (!onDied) return nullptr;
    return new AIBinder_DeathRecipient{onDied};
}
SHIM(void, AIBinder_DeathRecipient_setOnUnlinked)(AIBinder_DeathRecipient* r, void (*cb)(void*)) {
    if (r) r->onUnlinked = cb;
}
SHIM(void, AIBinder_DeathRecipient_delete)(AIBinder_DeathRecipient* r) { delete r; }

// No Java<->native binder bridge for a native-only binder: "nullptr on failure".
SHIM(AIBinder*, AIBinder_fromJavaBinder)(void* /*env*/, void* /*binder*/) { return nullptr; }
SHIM(void*, AIBinder_toJavaBinder)(void* /*env*/, AIBinder* /*binder*/) { return nullptr; }

// ---- AServiceManager (process-local registry; see the header comment) ------
SHIM(binder_exception_t, AServiceManager_addService)(AIBinder* b, const char* name) {
    if (!b || !name) return kStatusUnexpectedNull;
    std::lock_guard<std::mutex> g(g_svcMu);
    auto& m = svcMap();
    auto it = m.find(name);
    if (it != m.end()) strongRelease(it->second);
    b->strong.fetch_add(1);
    m[name] = b;
    return kStatusOk;
}
SHIM(AIBinder*, AServiceManager_checkService)(const char* name) {
    if (!name) return nullptr;
    std::lock_guard<std::mutex> g(g_svcMu);
    auto& m = svcMap();
    auto it = m.find(name);
    if (it == m.end()) return nullptr;
    it->second->strong.fetch_add(1);
    return it->second;
}
SHIM(AIBinder*, AServiceManager_getService)(const char* name) {
    return __macapk_arm64_AServiceManager_checkService(name);
}
SHIM(AIBinder*, AServiceManager_waitForService)(const char* name) {
    return __macapk_arm64_AServiceManager_checkService(name);
}
SHIM(bool, AServiceManager_isDeclared)(const char* /*instance*/) { return false; }

// ---- AParcel ----------------------------------------------------------------
SHIM(void, AParcel_delete)(AParcel* p) {
    if (!p) return;
    for (AIBinder* h : p->held) strongRelease(h);
    delete p;
}
SHIM(binder_status_t, AParcel_setDataPosition)(const AParcel* cp, int32_t position) {
    auto* p = const_cast<AParcel*>(cp);
    if (!p) return kStatusUnexpectedNull;
    if (position < 0 || (size_t)position > p->data.size()) return kStatusBadValue;
    p->pos = (size_t)position;
    return kStatusOk;
}
SHIM(int32_t, AParcel_getDataPosition)(const AParcel* p) { return p ? (int32_t)p->pos : 0; }
SHIM(int32_t, AParcel_getDataSize)(const AParcel* p) { return p ? (int32_t)p->data.size() : 0; }

SHIM(binder_status_t, AParcel_writeStrongBinder)(AParcel* p, AIBinder* b) {
    if (!p) return kStatusUnexpectedNull;
    uint64_t v = (uint64_t)(uintptr_t)b;
    binder_status_t s = wr<uint64_t>(p, v);
    if (s == kStatusOk && b) { b->strong.fetch_add(1); p->held.push_back(b); }
    return s;
}
SHIM(binder_status_t, AParcel_readStrongBinder)(const AParcel* p, AIBinder** out) {
    if (!out) return kStatusUnexpectedNull;
    uint64_t v;
    binder_status_t s = rd<uint64_t>(p, &v);
    if (s != kStatusOk) return s;
    AIBinder* b = (AIBinder*)(uintptr_t)v;
    if (b) b->strong.fetch_add(1);
    *out = b;
    return kStatusOk;
}
// A parcelable file descriptor is dup'd across the (in-process) transport.
SHIM(binder_status_t, AParcel_writeParcelFileDescriptor)(AParcel* p, int fd) {
    if (fd < 0) return wr<int32_t>(p, -1);
    int d = dup(fd);
    if (d < 0) return kStatusBadValue;
    return wr<int32_t>(p, d);
}
SHIM(binder_status_t, AParcel_readParcelFileDescriptor)(const AParcel* p, int* fd) {
    int32_t v;
    binder_status_t s = rd<int32_t>(p, &v);
    if (s == kStatusOk && fd) *fd = v;
    return s;
}

// Status header, Parcel::writeStatusHeader shape: exception code, and for a
// non-ok status the message and the service-specific code.
SHIM(binder_status_t, AParcel_writeStatusHeader)(AParcel* p, const AStatus* st) {
    if (!p || !st) return kStatusUnexpectedNull;
    binder_status_t s = wr<int32_t>(p, st->exception);
    if (s != kStatusOk || st->exception == kExNone) return s;
    int32_t ml = (int32_t)st->message.size();
    if ((s = wr<int32_t>(p, ml)) != kStatusOk) return s;
    if (ml && !p->put(st->message.data(), (size_t)ml)) return kStatusNoMemory;
    if ((s = wr<int32_t>(p, st->status)) != kStatusOk) return s;
    return wr<int32_t>(p, st->serviceSpecific);
}
SHIM(binder_status_t, AParcel_readStatusHeader)(const AParcel* cp, AStatus** out) {
    AParcel* p = const_cast<AParcel*>(cp);
    if (!p || !out) return kStatusUnexpectedNull;
    int32_t ex;
    binder_status_t s = rd<int32_t>(cp, &ex);
    if (s != kStatusOk) return s;
    auto* st = new AStatus();
    st->exception = ex;
    if (ex != kExNone) {
        int32_t ml;
        if ((s = rd<int32_t>(cp, &ml)) != kStatusOk || ml < 0) { delete st; return s ? s : kStatusBadValue; }
        if (ml) {
            st->message.resize((size_t)ml);
            if (!p->get(&st->message[0], (size_t)ml)) { delete st; return kStatusNotEnoughData; }
        }
        if ((s = rd<int32_t>(cp, &st->status)) != kStatusOk ||
            (s = rd<int32_t>(cp, &st->serviceSpecific)) != kStatusOk) { delete st; return s; }
    }
    *out = st;
    return kStatusOk;
}

// Strings: int32 byte length (-1 for null) + bytes, 4-aligned. A length of -1
// in AParcel_writeString's `length` parameter means "NUL-terminated".
SHIM(binder_status_t, AParcel_writeString)(AParcel* p, const char* str, int32_t length) {
    if (!p) return kStatusUnexpectedNull;
    if (!str) {
        if (length != -1) return kStatusBadValue;
        return wr<int32_t>(p, -1);
    }
    if (length < 0) length = (int32_t)std::strlen(str);
    binder_status_t s = wr<int32_t>(p, length);
    if (s != kStatusOk) return s;
    return (length == 0 || p->put(str, (size_t)length)) ? kStatusOk : kStatusNoMemory;
}
SHIM(binder_status_t, AParcel_readString)(const AParcel* cp, void* stringData,
                                          bool (*alloc)(void*, int32_t, char**)) {
    AParcel* p = const_cast<AParcel*>(cp);
    int32_t len;
    binder_status_t s = rd<int32_t>(cp, &len);
    if (s != kStatusOk) return s;
    char* buf = nullptr;
    if (len < 0) return alloc(stringData, -1, &buf) ? kStatusOk : kStatusNoMemory;
    if (!alloc(stringData, len + 1, &buf) || !buf) return kStatusNoMemory;
    if (len && !p->get(buf, (size_t)len)) return kStatusNotEnoughData;
    buf[len] = 0;
    return kStatusOk;
}
SHIM(binder_status_t, AParcel_writeStringArray)(AParcel* p, const void* arr, int32_t length,
                                                const char* (*getter)(const void*, size_t, int32_t*)) {
    if (!p) return kStatusUnexpectedNull;
    if (!arr) return length == -1 ? wr<int32_t>(p, -1) : kStatusBadValue;
    binder_status_t s = wr<int32_t>(p, length);
    for (int32_t i = 0; s == kStatusOk && i < length; i++) {
        int32_t l = 0;
        const char* c = getter(arr, (size_t)i, &l);
        s = __macapk_arm64_AParcel_writeString(p, c, c ? l : -1);
    }
    return s;
}
SHIM(binder_status_t, AParcel_readStringArray)(const AParcel* cp, void* arr,
                                               bool (*alloc)(void*, int32_t),
                                               bool (*elemAlloc)(void*, size_t, int32_t, char**)) {
    AParcel* p = const_cast<AParcel*>(cp);
    int32_t n;
    binder_status_t s = rd<int32_t>(cp, &n);
    if (s != kStatusOk) return s;
    if (!alloc(arr, n)) return kStatusNoMemory;
    for (int32_t i = 0; i < n; i++) {
        int32_t len;
        if ((s = rd<int32_t>(cp, &len)) != kStatusOk) return s;
        char* buf = nullptr;
        if (len < 0) { if (!elemAlloc(arr, (size_t)i, -1, &buf)) return kStatusNoMemory; continue; }
        if (!elemAlloc(arr, (size_t)i, len + 1, &buf) || !buf) return kStatusNoMemory;
        if (len && !p->get(buf, (size_t)len)) return kStatusNotEnoughData;
        buf[len] = 0;
    }
    return kStatusOk;
}
SHIM(binder_status_t, AParcel_writeParcelableArray)(AParcel* p, const void* arr, int32_t length,
                                                    binder_status_t (*writer)(AParcel*, const void*, size_t)) {
    if (!p) return kStatusUnexpectedNull;
    if (!arr) return length == -1 ? wr<int32_t>(p, -1) : kStatusBadValue;
    binder_status_t s = wr<int32_t>(p, length);
    for (int32_t i = 0; s == kStatusOk && i < length; i++) s = writer(p, arr, (size_t)i);
    return s;
}
SHIM(binder_status_t, AParcel_readParcelableArray)(const AParcel* cp, void* arr,
                                                   bool (*alloc)(void*, int32_t),
                                                   binder_status_t (*reader)(const AParcel*, void*, size_t)) {
    int32_t n;
    binder_status_t s = rd<int32_t>(cp, &n);
    if (s != kStatusOk) return s;
    if (!alloc(arr, n)) return kStatusNoMemory;
    for (int32_t i = 0; s == kStatusOk && i < n; i++) s = reader(cp, arr, (size_t)i);
    return s;
}

#define SCALAR(T, Name)                                                              \
    SHIM(binder_status_t, AParcel_write##Name)(AParcel* p, T v) { return wr<T>(p, v); } \
    SHIM(binder_status_t, AParcel_read##Name)(const AParcel* p, T* v) { return rd<T>(p, v); }
SCALAR(int32_t, Int32)
SCALAR(uint32_t, Uint32)
SCALAR(int64_t, Int64)
SCALAR(uint64_t, Uint64)
SCALAR(float, Float)
SCALAR(double, Double)
SCALAR(char16_t, Char)
SCALAR(int8_t, Byte)
// Bool travels as an int32 (Parcel::writeBool).
SHIM(binder_status_t, AParcel_writeBool)(AParcel* p, bool v) { return wr<int32_t>(p, v ? 1 : 0); }
SHIM(binder_status_t, AParcel_readBool)(const AParcel* p, bool* v) {
    int32_t x;
    binder_status_t s = rd<int32_t>(p, &x);
    if (s == kStatusOk && v) *v = x != 0;
    return s;
}

#define ARRAY(T, Name)                                                                    \
    SHIM(binder_status_t, AParcel_write##Name##Array)(AParcel* p, const T* a, int32_t n) { \
        return wrArray<T>(p, a, n);                                                        \
    }                                                                                      \
    SHIM(binder_status_t, AParcel_read##Name##Array)(const AParcel* p, void* arr,          \
                                                     bool (*alloc)(void*, int32_t, T**)) { \
        return rdArray<T>(p, arr, alloc);                                                  \
    }
ARRAY(int32_t, Int32)
ARRAY(uint32_t, Uint32)
ARRAY(int64_t, Int64)
ARRAY(uint64_t, Uint64)
ARRAY(float, Float)
ARRAY(double, Double)
ARRAY(char16_t, Char)
ARRAY(int8_t, Byte)

SHIM(binder_status_t, AParcel_writeBoolArray)(AParcel* p, const void* arr, int32_t length,
                                              bool (*getter)(const void*, size_t)) {
    if (!p) return kStatusUnexpectedNull;
    if (!arr) return length == -1 ? wr<int32_t>(p, -1) : kStatusBadValue;
    binder_status_t s = wr<int32_t>(p, length);
    for (int32_t i = 0; s == kStatusOk && i < length; i++) s = wr<int32_t>(p, getter(arr, (size_t)i) ? 1 : 0);
    return s;
}
SHIM(binder_status_t, AParcel_readBoolArray)(const AParcel* p, void* arr,
                                             bool (*alloc)(void*, int32_t),
                                             void (*setter)(void*, size_t, bool)) {
    int32_t n;
    binder_status_t s = rd<int32_t>(p, &n);
    if (s != kStatusOk) return s;
    if (!alloc(arr, n)) return kStatusNoMemory;
    for (int32_t i = 0; s == kStatusOk && i < n; i++) {
        int32_t x;
        if ((s = rd<int32_t>(p, &x)) == kStatusOk) setter(arr, (size_t)i, x != 0);
    }
    return s;
}

// Bionic's old-style assertion handler (assert.h without the function name):
// __assert(file, line, msg). Same policy as __macapk_arm64___assert2 in
// bionic_shim.cpp: report and return, so an init path can print its state.
EXPORT void __macapk_arm64___assert(const char* file, int line, const char* msg) {
    std::fprintf(stderr, "[arm64-shim] __assert: %s:%d: %s\n", file ? file : "?", line,
                 msg ? msg : "?");
    std::fflush(stderr);
}
