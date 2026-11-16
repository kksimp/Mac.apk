// native_location.cpp -- com.macapk.loader.LocationBridge on Windows: the PC's
// location (Windows.Devices.Geolocation) for android.location.LocationManager
// and, through it, the Play services fused provider. Upstream's
// location_bridge.mm does this with CoreLocation.
//
// Without it every Android app saw location switched off: Waze, Moovit and
// the like asked for a location permission the user could never usefully give.
//
// Who may read the location is Windows' decision, made in Settings > Privacy &
// security > Location: the system switch (HKLM), the user's "let apps access
// your location", "let desktop apps access your location", and since 24H2 one
// entry per program. Those are read from the consent store in the registry, so
// asking costs nothing and shows nothing. The per-app Android grant stays the
// Win.apk dialog's (the launcher's runtime-permission verb), as on the Mac.
//
// WinRT is used through its ABI, declared here: MinGW's headers lack the
// geolocation namespace. combase is loaded at run time, so nothing changes for
// a program that never touches location. Every WinRT call is made on one
// thread of our own (in the multithreaded apartment); Java threads only post
// commands to it. Fixes and status changes arrive on the thread pool.

#include "host_natives.h"

#include <cmath>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>

#include <windows.h>
#include <inspectable.h>
#include <unknwn.h>

namespace winapk::host {

namespace {

// ---- the WinRT ABI we use ------------------------------------------------------

struct DateTimeAbi { INT64 universal_time; };  // 100 ns ticks since 1601-01-01
struct TimeSpanAbi { INT64 duration; };        // 100 ns ticks
struct EventToken { INT64 value; };

// Windows.Foundation.IReference<double>
struct IRefDouble : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Value(double* value) = 0;
};

struct IGeocoordinate : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Latitude(double* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Longitude(double* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Altitude(IRefDouble** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Accuracy(double* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AltitudeAccuracy(IRefDouble** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Heading(IRefDouble** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Speed(IRefDouble** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Timestamp(DateTimeAbi* value) = 0;
};

struct IGeoposition : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Coordinate(IGeocoordinate** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CivicAddress(IInspectable** value) = 0;
};

// IAsyncOperation<Geoposition>: only GetResults is called, on the pointer the
// call returned, so its parameterized IID is not needed.
struct IAsyncGeoposition : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE put_Completed(IUnknown* handler) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Completed(IUnknown** handler) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetResults(IGeoposition** results) = 0;
};

struct IAsyncInfoAbi : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Id(UINT32* id) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* status) = 0;  // 0 started, 1 completed, 2 canceled, 3 error
    virtual HRESULT STDMETHODCALLTYPE get_ErrorCode(HRESULT* error) = 0;
    virtual HRESULT STDMETHODCALLTYPE Cancel() = 0;
    virtual HRESULT STDMETHODCALLTYPE Close() = 0;
};

struct IGeolocator : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DesiredAccuracy(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_DesiredAccuracy(int value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MovementThreshold(double* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_MovementThreshold(double value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ReportInterval(UINT32* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_ReportInterval(UINT32 value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_LocationStatus(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGeopositionAsync(IAsyncGeoposition** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGeopositionAsyncWithAgeAndTimeout(TimeSpanAbi maximum_age,
                                                                           TimeSpanAbi timeout,
                                                                           IAsyncGeoposition** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_PositionChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_PositionChanged(EventToken token) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_StatusChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_StatusChanged(EventToken token) = 0;
};

struct IPositionChangedEventArgs : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Position(IGeoposition** value) = 0;
};

struct IStatusChangedEventArgs : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
};

// From windows-rs (Windows.Devices.Geolocation) and the SDK headers.
constexpr GUID kIidGeolocator = {0xa9c3bf62, 0x4524, 0x4989, {0x8a, 0xa9, 0xde, 0x01, 0x9d, 0x2e, 0x55, 0x1f}};
constexpr GUID kIidPositionChangedArgs = {0x37859ce5, 0x9d1e, 0x46c5, {0xbf, 0x3b, 0x6a, 0xd8, 0xca, 0xc1, 0xa0, 0x93}};
constexpr GUID kIidStatusChangedArgs = {0x3453d2da, 0x8c93, 0x4111, {0xa2, 0x05, 0x9a, 0xec, 0xfc, 0x9b, 0xe5, 0xc0}};
constexpr GUID kIidAsyncInfo = {0x00000036, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
constexpr GUID kIidAgileObject = {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
constexpr GUID kIidUnknown = {0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
// TypedEventHandler<Geolocator, PositionChangedEventArgs> and <..., StatusChangedEventArgs>:
// parameterized IIDs, derived from their signatures as the WinRT type system
// does (SHA-1 over the pinterface namespace and the signature string).
constexpr GUID kIidPositionHandler = {0xdf3c6164, 0x4e7b, 0x5e8e, {0x9a, 0x7e, 0x13, 0xda, 0x05, 0x9d, 0xec, 0x1e}};
constexpr GUID kIidStatusHandler = {0x97fcf582, 0xde6b, 0x5cd3, {0x96, 0x90, 0xe2, 0xec, 0xbb, 0x66, 0xda, 0x4d}};

// PositionStatus (Ready 0, Initializing 1, NoData 2 need no handling).
constexpr int kStatusDisabled = 3, kStatusNotAvailable = 5;
// PositionAccuracy.
constexpr int kAccuracyDefault = 0, kAccuracyHigh = 1;
// LocationBridge.AUTH_*.
constexpr jint kAuthAuthorized = 3, kAuthDenied = 2;

// ---- combase, loaded at run time ---------------------------------------------------

using RoInitializeFn = HRESULT(WINAPI*)(int);
using RoActivateInstanceFn = HRESULT(WINAPI*)(HSTRING, IInspectable**);
using WindowsCreateStringFn = HRESULT(WINAPI*)(const wchar_t*, UINT32, HSTRING*);
using WindowsDeleteStringFn = HRESULT(WINAPI*)(HSTRING);

template <typename F>
F proc(HMODULE m, const char* name) {
    return reinterpret_cast<F>(reinterpret_cast<void*>(GetProcAddress(m, name)));
}

struct Combase {
    RoInitializeFn ro_initialize = nullptr;
    RoActivateInstanceFn ro_activate = nullptr;
    WindowsCreateStringFn create_string = nullptr;
    WindowsDeleteStringFn delete_string = nullptr;

    bool load() {
        HMODULE m = LoadLibraryW(L"combase.dll");
        if (!m) return false;
        ro_initialize = proc<RoInitializeFn>(m, "RoInitialize");
        ro_activate = proc<RoActivateInstanceFn>(m, "RoActivateInstance");
        create_string = proc<WindowsCreateStringFn>(m, "WindowsCreateString");
        delete_string = proc<WindowsDeleteStringFn>(m, "WindowsDeleteString");
        return ro_initialize && ro_activate && create_string && delete_string;
    }
};

template <typename T>
void release(T*& p) {
    if (p) p->Release();
    p = nullptr;
}

// ---- the shared state ----------------------------------------------------------------

JavaVM* g_vm = nullptr;
jclass g_bridge = nullptr;  // global ref to LocationBridge
jmethodID g_on_location = nullptr;
jmethodID g_on_authorization = nullptr;

std::mutex g_lock;
std::condition_variable g_wake;
enum class Command { kStart, kStop, kFetchOnce };
std::deque<std::pair<Command, bool>> g_commands;  // bool: reduced accuracy (kStart)
int g_bound = -1;                                // -1 starting, 0 no, 1 yes
bool g_have_fix = false;
double g_fix[12] = {};
bool g_fetch_in_flight = false;
int g_last_status = -1;  // the Geolocator's last PositionStatus, -1 none yet

JNIEnv* java_env() {
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return nullptr;
    return env;
}

double ref_value(IRefDouble* r, double absent) {
    double v = absent;
    if (r && FAILED(r->get_Value(&v))) v = absent;
    if (std::isnan(v)) v = absent;  // Heading is NaN when standing still
    return v;
}

// One Geoposition to LocationBridge.onNativeLocation, and kept as the cached fix.
void report(IGeoposition* pos) {
    IGeocoordinate* c = nullptr;
    if (!pos || FAILED(pos->get_Coordinate(&c)) || !c) return;
    double lat = 0, lon = 0, acc = -1;
    IRefDouble *alt = nullptr, *alt_acc = nullptr, *heading = nullptr, *speed = nullptr;
    DateTimeAbi ts{};
    c->get_Latitude(&lat);
    c->get_Longitude(&lon);
    c->get_Accuracy(&acc);
    c->get_Altitude(&alt);
    c->get_AltitudeAccuracy(&alt_acc);
    c->get_Heading(&heading);
    c->get_Speed(&speed);
    c->get_Timestamp(&ts);
    double f[12];
    f[0] = lat;
    f[1] = lon;
    f[2] = ref_value(alt, 0);
    f[3] = f[2];
    f[4] = acc;
    // Without an altitude there is no accuracy for it, and LocationBridge then
    // leaves the Location without one.
    f[5] = alt ? ref_value(alt_acc, -1) : -1;
    f[6] = ref_value(speed, -1);
    f[7] = -1;
    f[8] = ref_value(heading, -1);
    f[9] = -1;
    const INT64 unix_epoch_ticks = 116444736000000000LL;
    INT64 ticks = ts.universal_time;
    if (ticks <= unix_epoch_ticks) {
        // No timestamp: the fix is as new as its arrival.
        FILETIME now;
        GetSystemTimeAsFileTime(&now);
        ticks = (static_cast<INT64>(now.dwHighDateTime) << 32) | now.dwLowDateTime;
    }
    f[10] = static_cast<double>((ticks - unix_epoch_ticks) / 10000);
    f[11] = 0;
    release(alt);
    release(alt_acc);
    release(heading);
    release(speed);
    release(c);
    {
        std::lock_guard<std::mutex> l(g_lock);
        std::memcpy(g_fix, f, sizeof f);
        g_have_fix = true;
    }
    JNIEnv* env = java_env();
    if (!env || !g_on_location) return;
    env->CallStaticVoidMethod(g_bridge, g_on_location, f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9],
                              static_cast<jlong>(f[10]), JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void report_status(int status) {
    int before;
    {
        std::lock_guard<std::mutex> l(g_lock);
        before = g_last_status;
        g_last_status = status;
    }
    // Initializing / NoData come and go while a fix is sought; only the
    // ones that change what the app may do are news.
    const bool was_off = before == kStatusDisabled || before == kStatusNotAvailable;
    const bool is_off = status == kStatusDisabled || status == kStatusNotAvailable;
    if (before != -1 && was_off == is_off) return;
    JNIEnv* env = java_env();
    if (!env || !g_on_authorization) return;
    env->CallStaticVoidMethod(g_bridge, g_on_authorization, is_off ? kAuthDenied : kAuthAuthorized, JNI_FALSE);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

// A TypedEventHandler<Geolocator, Args>: delegates are IUnknown-based, and are
// called on the thread pool, so the object is agile.
class Handler final : public IUnknown {
public:
    Handler(const GUID& iid, bool position) : iid_(iid), position_(position) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
        if (!out) return E_POINTER;
        if (IsEqualGUID(riid, kIidUnknown) || IsEqualGUID(riid, kIidAgileObject) || IsEqualGUID(riid, iid_)) {
            *out = this;
            AddRef();
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs_); }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG n = InterlockedDecrement(&refs_);
        if (n == 0) delete this;
        return static_cast<ULONG>(n);
    }
    // The delegate's own method, the first after IUnknown's.
    virtual HRESULT STDMETHODCALLTYPE Invoke(IInspectable* /*sender*/, IInspectable* args) {
        if (!args) return S_OK;
        if (position_) {
            IPositionChangedEventArgs* a = nullptr;
            if (SUCCEEDED(args->QueryInterface(kIidPositionChangedArgs, reinterpret_cast<void**>(&a))) && a) {
                IGeoposition* p = nullptr;
                if (SUCCEEDED(a->get_Position(&p)) && p) {
                    report(p);
                    p->Release();
                }
                a->Release();
            }
        } else {
            IStatusChangedEventArgs* a = nullptr;
            if (SUCCEEDED(args->QueryInterface(kIidStatusChangedArgs, reinterpret_cast<void**>(&a))) && a) {
                int s = -1;
                if (SUCCEEDED(a->get_Status(&s))) report_status(s);
                a->Release();
            }
        }
        return S_OK;
    }

private:
    ~Handler() = default;  // not virtual: no slot may follow Invoke's in the vtable
    GUID iid_;
    bool position_;
    LONG refs_ = 1;
};

// One position, waited for here (the worker thread has nothing else to do).
void fetch_once(IGeolocator* geo) {
    IAsyncGeoposition* op = nullptr;
    // Up to ten minutes old is fine for a last-known location; Windows answers
    // from its cache at once when it has one.
    const TimeSpanAbi max_age{10LL * 60 * 10000000};
    const TimeSpanAbi timeout{20LL * 10000000};
    if (SUCCEEDED(geo->GetGeopositionAsyncWithAgeAndTimeout(max_age, timeout, &op)) && op) {
        IAsyncInfoAbi* info = nullptr;
        if (SUCCEEDED(op->QueryInterface(kIidAsyncInfo, reinterpret_cast<void**>(&info))) && info) {
            int st = 0;
            for (int i = 0; i < 25 * 20 * 2 && SUCCEEDED(info->get_Status(&st)) && st == 0; ++i) Sleep(40);
            if (st == 1) {
                IGeoposition* p = nullptr;
                if (SUCCEEDED(op->GetResults(&p)) && p) {
                    report(p);
                    p->Release();
                }
            } else if (st == 0) {
                info->Cancel();
            }
            info->Close();
            info->Release();
        }
        op->Release();
    }
    int status = -1;
    if (SUCCEEDED(geo->get_LocationStatus(&status))) report_status(status);
    std::lock_guard<std::mutex> l(g_lock);
    g_fetch_in_flight = false;
}

void worker() {
    Combase rt;
    IGeolocator* geo = nullptr;
    if (rt.load() && SUCCEEDED(rt.ro_initialize(1 /* RO_INIT_MULTITHREADED */))) {
        const wchar_t kClass[] = L"Windows.Devices.Geolocation.Geolocator";
        HSTRING name = nullptr;
        if (SUCCEEDED(rt.create_string(kClass, static_cast<UINT32>(wcslen(kClass)), &name))) {
            IInspectable* inst = nullptr;
            if (SUCCEEDED(rt.ro_activate(name, &inst)) && inst) {
                inst->QueryInterface(kIidGeolocator, reinterpret_cast<void**>(&geo));
                inst->Release();
            }
            rt.delete_string(name);
        }
    }
    {
        std::lock_guard<std::mutex> l(g_lock);
        g_bound = geo ? 1 : 0;
    }
    g_wake.notify_all();
    if (!geo) {
        std::fprintf(stderr, "[win.apk] location: Windows.Devices.Geolocation is not available\n");
        return;
    }
    Handler* on_position = nullptr;
    Handler* on_status = nullptr;
    EventToken position_token{}, status_token{};
    for (;;) {
        std::pair<Command, bool> cmd;
        {
            std::unique_lock<std::mutex> l(g_lock);
            g_wake.wait(l, [] { return !g_commands.empty(); });
            cmd = g_commands.front();
            g_commands.pop_front();
        }
        if (cmd.first == Command::kFetchOnce) {
            fetch_once(geo);
            continue;
        }
        // Start (or re-aim) and Stop both begin by ending a running session.
        if (on_position) {
            geo->remove_PositionChanged(position_token);
            geo->remove_StatusChanged(status_token);
            release(on_position);
            release(on_status);
        }
        if (cmd.first == Command::kStop) continue;
        geo->put_DesiredAccuracy(cmd.second ? kAccuracyDefault : kAccuracyHigh);
        geo->put_MovementThreshold(0);
        geo->put_ReportInterval(1000);
        on_status = new Handler(kIidStatusHandler, false);
        on_position = new Handler(kIidPositionHandler, true);
        if (FAILED(geo->add_StatusChanged(on_status, &status_token)) ||
            FAILED(geo->add_PositionChanged(on_position, &position_token))) {
            std::fprintf(stderr, "[win.apk] location: could not start position updates\n");
            geo->remove_StatusChanged(status_token);
            release(on_position);
            release(on_status);
        }
    }
}

void post(Command c, bool reduced = false) {
    {
        std::lock_guard<std::mutex> l(g_lock);
        g_commands.emplace_back(c, reduced);
    }
    g_wake.notify_all();
}

// ---- Windows' location settings ---------------------------------------------------------

const wchar_t kConsent[] =
    L"Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\location";

// The consent store's "Value" ("Allow" / "Deny") under `key`; empty when unset.
std::wstring consent(HKEY root, const std::wstring& key) {
    wchar_t buf[32] = {};
    DWORD size = sizeof buf;
    if (RegGetValueW(root, key.c_str(), L"Value", RRF_RT_REG_SZ, nullptr, buf, &size) != ERROR_SUCCESS) return {};
    return buf;
}

bool denied(HKEY root, const std::wstring& key) { return _wcsicmp(consent(root, key).c_str(), L"Deny") == 0; }

bool services_enabled() { return !denied(HKEY_LOCAL_MACHINE, kConsent); }

bool user_denied() {
    if (denied(HKEY_CURRENT_USER, kConsent)) return true;
    const std::wstring desktop = std::wstring(kConsent) + L"\\NonPackaged";
    if (denied(HKEY_CURRENT_USER, desktop)) return true;
    // Since 24H2 each program has its own entry: its path, '\' written as '#'.
    wchar_t exe[MAX_PATH * 2] = {};
    const DWORD n = GetModuleFileNameW(nullptr, exe, MAX_PATH * 2);
    if (n == 0 || n >= MAX_PATH * 2) return false;
    std::wstring path(exe, n);
    for (wchar_t& ch : path) {
        if (ch == L'\\') ch = L'#';
    }
    return denied(HKEY_CURRENT_USER, desktop + L"\\" + path);
}

// ---- the natives --------------------------------------------------------------------------

jboolean JNICALL Location_bound(JNIEnv* env, jclass cls) {
    std::unique_lock<std::mutex> l(g_lock);
    if (g_bound == -1 && !g_bridge) {
        if (env->GetJavaVM(&g_vm) != JNI_OK) return JNI_FALSE;
        g_bridge = static_cast<jclass>(env->NewGlobalRef(cls));
        g_on_location = env->GetStaticMethodID(cls, "onNativeLocation", "(DDDDDDDDDDJZ)V");
        g_on_authorization = env->GetStaticMethodID(cls, "onNativeAuthorization", "(IZ)V");
        if (!g_on_location || !g_on_authorization) {
            env->ExceptionClear();
            g_bound = 0;
            return JNI_FALSE;
        }
        std::thread(worker).detach();
    }
    // Activating the Geolocator takes milliseconds; the cap only guards a
    // WinRT that never answers.
    g_wake.wait_for(l, std::chrono::seconds(5), [] { return g_bound != -1; });
    return g_bound == 1 ? JNI_TRUE : JNI_FALSE;
}

// The Mac's CoreLocation helper app; Windows needs none.
void JNICALL Location_setHelper(JNIEnv*, jclass, jstring) {}

// Windows asks the user itself (24H2) the first time a program reads the
// location; there is no prompt to request ahead of that.
jboolean JNICALL Location_canPrompt(JNIEnv*, jclass) { return JNI_FALSE; }

jint JNICALL Location_authStatus(JNIEnv*, jclass) {
    if (user_denied()) return kAuthDenied;
    // The Geolocator saying Disabled while the system switch is on: this
    // program was refused (the 24H2 prompt answered No).
    int last;
    {
        std::lock_guard<std::mutex> l(g_lock);
        last = g_last_status;
    }
    if (last == kStatusDisabled && services_enabled()) return kAuthDenied;
    return kAuthAuthorized;
}

// Windows has no per-program "approximate only" switch.
jboolean JNICALL Location_reducedAccuracy(JNIEnv*, jclass) { return JNI_FALSE; }

jboolean JNICALL Location_servicesEnabled(JNIEnv*, jclass) { return services_enabled() ? JNI_TRUE : JNI_FALSE; }

// The newest fix this process has. When there is none yet, one is asked for
// (Windows' own cache usually answers at once) and arrives through
// onNativeLocation, so the next read has it.
jdoubleArray JNICALL Location_cachedFix(JNIEnv* env, jclass) {
    double f[12];
    {
        std::lock_guard<std::mutex> l(g_lock);
        if (!g_have_fix) {
            if (!g_fetch_in_flight && g_bound == 1) {
                g_fetch_in_flight = true;
                g_commands.emplace_back(Command::kFetchOnce, false);
                g_wake.notify_all();
            }
            return nullptr;
        }
        std::memcpy(f, g_fix, sizeof f);
    }
    jdoubleArray a = env->NewDoubleArray(12);
    if (a) env->SetDoubleArrayRegion(a, 0, 12, f);
    return a;
}

void JNICALL Location_requestAuth(JNIEnv*, jclass) {}

void JNICALL Location_start(JNIEnv*, jclass, jboolean reduced) { post(Command::kStart, reduced == JNI_TRUE); }

void JNICALL Location_stop(JNIEnv*, jclass) { post(Command::kStop); }

// Where the user changes who may read the location: Windows' Settings page.
jstring JNICALL Location_settingsUrl(JNIEnv* env, jclass) {
    return env->NewStringUTF("ms-settings:privacy-location");
}

constexpr const char* kCls = "com.macapk.loader.LocationBridge";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeBound", "()Z", Location_bound),
    WINAPK_HOST_NATIVE(kCls, "nativeSetHelper", "(Ljava/lang/String;)V", Location_setHelper),
    WINAPK_HOST_NATIVE(kCls, "nativeCanPrompt", "()Z", Location_canPrompt),
    WINAPK_HOST_NATIVE(kCls, "nativeAuthStatus", "()I", Location_authStatus),
    WINAPK_HOST_NATIVE(kCls, "nativeReducedAccuracy", "()Z", Location_reducedAccuracy),
    WINAPK_HOST_NATIVE(kCls, "nativeServicesEnabled", "()Z", Location_servicesEnabled),
    WINAPK_HOST_NATIVE(kCls, "nativeCachedFix", "()[D", Location_cachedFix),
    WINAPK_HOST_NATIVE(kCls, "nativeRequestAuth", "()V", Location_requestAuth),
    WINAPK_HOST_NATIVE(kCls, "nativeStart", "(Z)V", Location_start),
    WINAPK_HOST_NATIVE(kCls, "nativeStop", "()V", Location_stop),
    WINAPK_HOST_NATIVE(kCls, "nativeHostSettingsUrl", "()Ljava/lang/String;", Location_settingsUrl),
};

}  // namespace

HostMethods native_location_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
