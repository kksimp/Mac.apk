// binder_win.cpp -- cross-process android.os.Binder on Windows: the natives of
// android.os.MacApkBinderTransport over named pipes, in place of upstream's
// Mach ports (runtime/src/binder_mach.cpp), and com.macapk.loader.WinProcesses.
//
// The Java half (MacApkBinderTransport) owns identity: which int stands for
// which Java object. This file gives it the same contract binder_mach.cpp does:
//
//   a local Binder        a HANDLE number this process exports (g_locals);
//                         remote processes name it as (our endpoint, number)
//   a BinderProxy         a HANDLE number for (another endpoint, its number),
//                         one per remote object (g_proxy_by_ref), reference
//                         counted as a Mach send right is (nativeReleaseSend)
//   transact()            a request on a pooled pipe connection, its reply on
//                         the same connection
//   FLAG_ONEWAY           a request with no reply, on the endpoint's one
//                         oneway connection, so oneway calls arrive in order
//   linkToDeath           a wait on the endpoint's process handle
//   a file descriptor     the open file's HANDLE duplicated into the receiving
//                         process (DuplicateHandle), where it becomes a NEW CRT
//                         fd (_open_osfhandle), the fd Win.apk's
//                         ParcelFileDescriptor and NativeOs use
//   the calling pid       GetNamedPipeClientProcessId: the OS's answer, never
//                         a value the caller wrote. The calling uid is derived
//                         from it in Java (MacApkBinderTransport.callingUidFor:
//                         the broker's record of that process), not taken
//                         from the message as upstream's phase 1 does.
//
// ENDPOINTS. Every process serves one pipe, \\.\pipe\winapk-<user SID>-s<session>
// -b-<pid>-<random>, one thread per connection (a callback into a process
// that is itself waiting on a call is served by another of its threads, as a
// binder thread pool does). A published name (the system broker's) is a
// second pipe, \\.\pipe\winapk-<user SID>-s<session>-<name>, that answers one
// question: which (endpoint, number) is the published object, so a looked-up
// object is the same proxy as the one a parcel later brings.
//
// SECURITY. Every pipe has a DACL for the current user and SYSTEM only, rejects
// remote clients, and its first instance is created with
// FILE_FLAG_FIRST_PIPE_INSTANCE, so a second process cannot take over a name
// that is in use. A client connects with SECURITY_IDENTIFICATION (the server
// cannot act as it) and, both ways, the peer must be a process of the same
// user running this same executable, or the connection is dropped. What this
// does not do, and cannot on Windows: every app runs with the user's rights,
// and a process of the same user can read or write another's memory. The
// identity checks stop an app's Java code from claiming to be another app;
// they do not sandbox native code.

#include "host_natives.h"

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include <fcntl.h>
#include <io.h>
#include <windows.h>
#include <sddl.h>
#include <bcrypt.h>
#include <winternl.h>

namespace winapk::host {

namespace {

// AOSP status_t values (system/core/libutils/include/utils/Errors.h).
constexpr int32_t kNoError = 0;
constexpr int32_t kDeadObject = -32;
constexpr int32_t kFailedTransaction = static_cast<int32_t>(0x80000002);
constexpr int32_t kBadValue = -22;

constexpr uint32_t kMagic = 0x4e424157;  // 'WABN'
constexpr uint32_t kMaxFrame = 64u << 20;
enum : uint32_t { kTransact = 1, kReply = 2, kGetRoot = 3, kRoot = 4 };
// MacApkBinderTransport's object kinds.
enum : int32_t { kKindNull = 0, kKindBinder = 1, kKindPfd = 2, kKindFd = 3 };
constexpr int32_t kFlagOneway = 1;  // IBinder.FLAG_ONEWAY
constexpr jint kUnknownCallerUid = 9999;  // MacApkBinderTransport.UNKNOWN_CALLER_UID

JavaVM* g_vm = nullptr;
jclass g_transport = nullptr;
jmethodID g_dispatch = nullptr;
jmethodID g_on_dead = nullptr;
jmethodID g_uid_for = nullptr;  // callingUidFor(int pid), absent in upstream's jar

// ---- identity ----------------------------------------------------------------------

std::wstring string_sid_of(HANDLE token) {
    DWORD n = 0;
    GetTokenInformation(token, TokenUser, nullptr, 0, &n);
    std::vector<uint8_t> buf(n ? n : 1);
    if (!GetTokenInformation(token, TokenUser, buf.data(), n, &n)) return {};
    wchar_t* s = nullptr;
    if (!ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(buf.data())->User.Sid, &s)) return {};
    std::wstring out(s);
    LocalFree(s);
    return out;
}

const std::wstring& self_sid() {
    static const std::wstring sid = [] {
        HANDLE t = nullptr;
        std::wstring s;
        if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &t)) {
            s = string_sid_of(t);
            CloseHandle(t);
        }
        return s;
    }();
    return sid;
}

const std::wstring& self_image() {
    static const std::wstring image = [] {
        std::wstring buf(32768, L'\0');
        DWORD n = static_cast<DWORD>(buf.size());
        if (!QueryFullProcessImageNameW(GetCurrentProcess(), 0, buf.data(), &n)) return std::wstring();
        buf.resize(n);
        return buf;
    }();
    return image;
}

bool same_path(const std::wstring& a, const std::wstring& b) {
    return !a.empty() && CompareStringOrdinal(a.c_str(), -1, b.c_str(), -1, TRUE) == CSTR_EQUAL;
}

// The pipe-name prefix of this user's session. Pipe names are global, so the
// user and session in the name keep two users' runtimes apart; the DACL and
// the peer check below are what stop one from posing as the other.
const std::wstring& prefix() {
    static const std::wstring p = [] {
        DWORD session = 0;
        ProcessIdToSessionId(GetCurrentProcessId(), &session);
        return L"\\\\.\\pipe\\winapk-" + self_sid() + L"-s" + std::to_wstring(session) + L"-";
    }();
    return p;
}

// Whether `process` belongs to this user and runs this executable.
bool trusted_peer(HANDLE process) {
    if (!process) return false;
    HANDLE t = nullptr;
    if (!OpenProcessToken(process, TOKEN_QUERY, &t)) return false;
    const std::wstring sid = string_sid_of(t);
    CloseHandle(t);
    if (sid.empty() || sid != self_sid()) return false;
    std::wstring buf(32768, L'\0');
    DWORD n = static_cast<DWORD>(buf.size());
    if (!QueryFullProcessImageNameW(process, 0, buf.data(), &n)) return false;
    buf.resize(n);
    return same_path(buf, self_image());
}

HANDLE open_peer(DWORD pid) {
    return OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE | PROCESS_DUP_HANDLE, FALSE, pid);
}

// Owner and SYSTEM only; no inheritance.
SECURITY_ATTRIBUTES* pipe_security() {
    static SECURITY_ATTRIBUTES sa = [] {
        SECURITY_ATTRIBUTES a{};
        a.nLength = sizeof a;
        a.bInheritHandle = FALSE;
        const std::wstring sddl = L"D:P(A;;GA;;;" + self_sid() + L")(A;;GA;;;SY)";
        PSECURITY_DESCRIPTOR sd = nullptr;
        if (ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1, &sd, nullptr))
            a.lpSecurityDescriptor = sd;
        return a;
    }();
    return sa.lpSecurityDescriptor ? &sa : nullptr;
}

// ---- framing -----------------------------------------------------------------------

struct Head {
    uint32_t magic, type, code, flags, target;
    int32_t status;
    uint32_t data_len, n_off, n_obj;
};

// One object of a message as it travels: a binder by (endpoint, number), or a
// handle value already valid in the receiving process.
struct WireObject {
    int32_t kind = kKindNull;
    std::wstring endpoint;
    uint32_t id = 0;
    uint64_t handle = 0;
};

struct Message {
    Head head{};
    std::vector<uint8_t> data;
    std::vector<int32_t> offsets;
    std::vector<WireObject> objects;
};

template <typename T>
void put(std::vector<uint8_t>& b, const T& v) {
    const auto* p = reinterpret_cast<const uint8_t*>(&v);
    b.insert(b.end(), p, p + sizeof v);
}

std::vector<uint8_t> encode(const Message& m) {
    std::vector<uint8_t> b;
    b.resize(sizeof(uint32_t));  // the frame length, filled in below
    Head h = m.head;
    h.magic = kMagic;
    h.data_len = static_cast<uint32_t>(m.data.size());
    h.n_off = static_cast<uint32_t>(m.offsets.size());
    h.n_obj = static_cast<uint32_t>(m.objects.size());
    put(b, h);
    b.insert(b.end(), m.data.begin(), m.data.end());
    for (int32_t o : m.offsets) put(b, o);
    for (const WireObject& o : m.objects) {
        put(b, o.kind);
        if (o.kind == kKindBinder) {
            put(b, o.id);
            put(b, static_cast<uint32_t>(o.endpoint.size()));
            const auto* p = reinterpret_cast<const uint8_t*>(o.endpoint.data());
            b.insert(b.end(), p, p + o.endpoint.size() * sizeof(wchar_t));
        } else if (o.kind == kKindPfd || o.kind == kKindFd) {
            put(b, o.handle);
        }
    }
    const uint32_t len = static_cast<uint32_t>(b.size() - sizeof(uint32_t));
    std::memcpy(b.data(), &len, sizeof len);
    return b;
}

struct Reader {
    const uint8_t* p;
    size_t left;
    template <typename T>
    bool get(T* v) {
        if (left < sizeof(T)) return false;
        std::memcpy(v, p, sizeof(T));
        p += sizeof(T);
        left -= sizeof(T);
        return true;
    }
};

bool decode(const std::vector<uint8_t>& frame, Message* m) {
    Reader r{frame.data(), frame.size()};
    if (!r.get(&m->head) || m->head.magic != kMagic) return false;
    const Head& h = m->head;
    if (h.data_len > r.left || h.n_off > (1u << 20) || h.n_obj > (1u << 20)) return false;
    m->data.assign(r.p, r.p + h.data_len);
    r.p += h.data_len;
    r.left -= h.data_len;
    m->offsets.resize(h.n_off);
    for (int32_t& o : m->offsets)
        if (!r.get(&o)) return false;
    m->objects.resize(h.n_obj);
    for (WireObject& o : m->objects) {
        if (!r.get(&o.kind)) return false;
        if (o.kind == kKindBinder) {
            uint32_t n = 0;
            if (!r.get(&o.id) || !r.get(&n) || n > 1024 || r.left < n * sizeof(wchar_t)) return false;
            o.endpoint.assign(reinterpret_cast<const wchar_t*>(r.p), n);
            r.p += n * sizeof(wchar_t);
            r.left -= n * sizeof(wchar_t);
        } else if (o.kind == kKindPfd || o.kind == kKindFd) {
            if (!r.get(&o.handle)) return false;
        } else if (o.kind != kKindNull) {
            return false;
        }
    }
    return r.left == 0;
}

// Synchronous I/O, for the server's (non-overlapped) pipes.
bool write_sync(HANDLE h, const std::vector<uint8_t>& b) {
    size_t done = 0;
    while (done < b.size()) {
        DWORD n = 0;
        if (!WriteFile(h, b.data() + done, static_cast<DWORD>(b.size() - done), &n, nullptr)) return false;
        done += n;
    }
    return true;
}
bool read_exact_sync(HANDLE h, void* out, size_t len) {
    auto* p = static_cast<uint8_t*>(out);
    while (len) {
        DWORD n = 0;
        if (!ReadFile(h, p, static_cast<DWORD>(len), &n, nullptr) || n == 0) return false;
        p += n;
        len -= n;
    }
    return true;
}
bool read_frame_sync(HANDLE h, std::vector<uint8_t>* frame) {
    uint32_t len = 0;
    if (!read_exact_sync(h, &len, sizeof len) || len > kMaxFrame) return false;
    frame->resize(len);
    return read_exact_sync(h, frame->data(), len);
}

// Overlapped I/O, for client connections: a wait also ends when the server
// process does, whatever else holds its end of the pipe.
bool io_overlapped(HANDLE h, bool write, void* buf, size_t len, HANDLE peer) {
    auto* p = static_cast<uint8_t*>(buf);
    HANDLE ev = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    if (!ev) return false;
    bool ok = true;
    while (len && ok) {
        OVERLAPPED ov{};
        ov.hEvent = ev;
        ResetEvent(ev);
        DWORD n = 0;
        const BOOL started = write ? WriteFile(h, p, static_cast<DWORD>(len), &n, &ov)
                                   : ReadFile(h, p, static_cast<DWORD>(len), &n, &ov);
        if (!started) {
            if (GetLastError() != ERROR_IO_PENDING) { ok = false; break; }
            HANDLE waits[2] = {ev, peer};
            const DWORD w = WaitForMultipleObjects(peer ? 2 : 1, waits, FALSE, INFINITE);
            if (w != WAIT_OBJECT_0) {
                CancelIoEx(h, &ov);
                GetOverlappedResult(h, &ov, &n, TRUE);
                ok = false;
                break;
            }
            if (!GetOverlappedResult(h, &ov, &n, FALSE)) { ok = false; break; }
        }
        if (n == 0) { ok = false; break; }
        p += n;
        len -= n;
    }
    CloseHandle(ev);
    return ok;
}
bool read_frame_overlapped(HANDLE h, HANDLE peer, std::vector<uint8_t>* frame) {
    uint32_t len = 0;
    if (!io_overlapped(h, false, &len, sizeof len, peer) || len > kMaxFrame) return false;
    frame->resize(len);
    return io_overlapped(h, false, frame->data(), len, peer);
}

// ---- tables ------------------------------------------------------------------------

struct Endpoint {
    std::wstring name;
    std::mutex lock;          // guards the fields below
    HANDLE process = nullptr; // the verified server process, once known
    DWORD pid = 0;
    std::vector<HANDLE> idle; // connected two-way pipes
    HANDLE oneway = nullptr;
    std::mutex oneway_lock;   // one oneway call on the wire at a time, in order
    HANDLE wait = nullptr;    // RegisterWaitForSingleObject on `process`
    std::atomic<bool> dead{false};
};

struct Proxy {
    std::shared_ptr<Endpoint> ep;
    uint32_t id = 0;
    int refs = 0;
    bool want_death = false;
};

std::mutex g_lock;
std::map<std::wstring, std::shared_ptr<Endpoint>> g_endpoints;
std::map<int32_t, Proxy> g_proxies;
std::map<std::pair<std::wstring, uint32_t>, int32_t> g_proxy_by_ref;
std::set<int32_t> g_locals;
std::atomic<int32_t> g_next{1};
std::wstring g_self;  // this process's endpoint, once serving
std::atomic<int> g_thread_number{0};

// ---- server ------------------------------------------------------------------------

struct ServeArgs {
    HANDLE pipe;
    int32_t root;  // > 0: a published pipe, which only answers GET_ROOT with it
};

JNIEnv* attach_thread(const char* what) {
    JNIEnv* env = nullptr;
    if (!g_vm) return nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) return env;
    char name[48];
    std::snprintf(name, sizeof name, "%s:%d", what, g_thread_number.fetch_add(1) + 1);
    JavaVMAttachArgs args{JNI_VERSION_1_8, name, nullptr};
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK) return nullptr;
    return env;
}

jintArray to_java(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray a = env->NewIntArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) env->SetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return a;
}
std::vector<int32_t> from_java(JNIEnv* env, jintArray a) {
    std::vector<int32_t> v;
    if (!a) return v;
    v.resize(static_cast<size_t>(env->GetArrayLength(a)));
    if (!v.empty()) env->GetIntArrayRegion(a, 0, static_cast<jsize>(v.size()), v.data());
    return v;
}

// An incoming binder reference, as the int MacApkBinderTransport knows it:
// our own number for one of our objects, else the proxy handle for (endpoint,
// id), created or given one more reference (the Java side releases a
// duplicate it already has, as with a Mach send right).
int32_t incoming_binder(const WireObject& o) {
    if (o.endpoint.empty()) return 0;
    std::lock_guard<std::mutex> hold(g_lock);
    if (o.endpoint == g_self) return static_cast<int32_t>(o.id);
    auto key = std::make_pair(o.endpoint, o.id);
    auto it = g_proxy_by_ref.find(key);
    if (it != g_proxy_by_ref.end()) {
        ++g_proxies[it->second].refs;
        return it->second;
    }
    auto& e = g_endpoints[o.endpoint];
    if (!e) {
        e = std::make_shared<Endpoint>();
        e->name = o.endpoint;
    }
    const int32_t h = g_next.fetch_add(1);
    Proxy p;
    p.ep = e;
    p.id = o.id;
    p.refs = 1;
    g_proxies[h] = p;
    g_proxy_by_ref[key] = h;
    return h;
}

// Ports and kinds as MacApkBinderTransport reads them, from a decoded message.
// A handle that arrived becomes a new CRT fd of this process.
void incoming_objects(const Message& m, std::vector<int32_t>* ports, std::vector<int32_t>* kinds) {
    for (const WireObject& o : m.objects) {
        int32_t port = 0;
        if (o.kind == kKindBinder) {
            port = incoming_binder(o);
        } else if (o.kind == kKindPfd || o.kind == kKindFd) {
            HANDLE h = reinterpret_cast<HANDLE>(static_cast<uintptr_t>(o.handle));
            port = h ? _open_osfhandle(reinterpret_cast<intptr_t>(h), _O_BINARY) : -1;
            if (port < 0 && h) CloseHandle(h);
        }
        ports->push_back(port);
        kinds->push_back(o.kind);
    }
}

// What a message carries for our ports and kinds. File descriptors are
// duplicated into `target`; `sent` collects them so a failed send can close them.
bool outgoing_objects(const std::vector<int32_t>& ports, const std::vector<int32_t>& kinds, HANDLE target,
                      std::vector<WireObject>* out, std::vector<HANDLE>* sent) {
    for (size_t i = 0; i < ports.size(); ++i) {
        WireObject o;
        o.kind = kinds[i];
        if (o.kind == kKindBinder) {
            if (ports[i] == 0) {
                o.kind = kKindNull;
            } else {
                std::lock_guard<std::mutex> hold(g_lock);
                if (g_locals.count(ports[i])) {
                    o.endpoint = g_self;
                    o.id = static_cast<uint32_t>(ports[i]);
                } else {
                    auto it = g_proxies.find(ports[i]);
                    if (it == g_proxies.end()) return false;
                    o.endpoint = it->second.ep->name;
                    o.id = it->second.id;
                }
            }
        } else if (o.kind == kKindPfd || o.kind == kKindFd) {
            const HANDLE mine = reinterpret_cast<HANDLE>(_get_osfhandle(ports[i]));
            HANDLE theirs = nullptr;
            if (mine == INVALID_HANDLE_VALUE || !target ||
                !DuplicateHandle(GetCurrentProcess(), mine, target, &theirs, 0, FALSE, DUPLICATE_SAME_ACCESS)) {
                return false;
            }
            sent->push_back(theirs);
            o.handle = reinterpret_cast<uintptr_t>(theirs);
        } else {
            o.kind = kKindNull;
        }
        out->push_back(std::move(o));
    }
    return true;
}

// Closes, in `target`, handles we duplicated into it for a message it never got.
void recall(HANDLE target, const std::vector<HANDLE>& sent) {
    for (HANDLE h : sent) DuplicateHandle(target, h, nullptr, nullptr, 0, FALSE, DUPLICATE_CLOSE_SOURCE);
}

DWORD WINAPI serve_connection(void* param) {
    std::unique_ptr<ServeArgs> args(static_cast<ServeArgs*>(param));
    HANDLE pipe = args->pipe;
    ULONG client_pid = 0;
    HANDLE client = nullptr;
    if (GetNamedPipeClientProcessId(pipe, &client_pid)) client = open_peer(client_pid);
    if (!trusted_peer(client)) {
        std::fprintf(stderr, "[binder] connection from pid %lu refused: not this user's Win.apk runtime\n",
                     static_cast<unsigned long>(client_pid));
        if (client) CloseHandle(client);
        DisconnectNamedPipe(pipe);
        CloseHandle(pipe);
        return 0;
    }
    JNIEnv* env = attach_thread("binder");
    jint calling_uid = -1;
    bool uid_known = false;
    std::vector<uint8_t> frame;
    while (env && read_frame_sync(pipe, &frame)) {
        Message in;
        if (!decode(frame, &in)) {
            std::fprintf(stderr, "[binder] malformed message from pid %lu dropped\n",
                         static_cast<unsigned long>(client_pid));
            break;
        }
        if (in.head.type == kGetRoot) {
            Message root;
            root.head.type = kRoot;
            WireObject o;
            if (args->root > 0) {
                o.kind = kKindBinder;
                std::lock_guard<std::mutex> hold(g_lock);
                o.endpoint = g_self;
                o.id = static_cast<uint32_t>(args->root);
            }
            root.objects.push_back(o);
            if (!write_sync(pipe, encode(root))) break;
            continue;
        }
        if (in.head.type != kTransact || args->root > 0) break;
        if (!uid_known && g_uid_for) {
            // Kept for the connection (one client process) once known; a
            // process that has not registered yet (its first call to the
            // broker is the registration) is asked about again next time.
            calling_uid = env->CallStaticIntMethod(g_transport, g_uid_for, static_cast<jint>(client_pid));
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
                calling_uid = -1;
            }
            uid_known = calling_uid >= 0 && calling_uid != kUnknownCallerUid;
        }
        std::vector<int32_t> ports, kinds;
        incoming_objects(in, &ports, &kinds);
        jbyteArray jdata = env->NewByteArray(static_cast<jsize>(in.data.size()));
        if (jdata && !in.data.empty())
            env->SetByteArrayRegion(jdata, 0, static_cast<jsize>(in.data.size()),
                                    reinterpret_cast<const jbyte*>(in.data.data()));
        jintArray joffs = to_java(env, in.offsets), jports = to_java(env, ports), jkinds = to_java(env, kinds);
        auto r = static_cast<jobjectArray>(env->CallStaticObjectMethod(
            g_transport, g_dispatch, static_cast<jint>(in.head.target), static_cast<jint>(in.head.code),
            static_cast<jint>(in.head.flags), jdata, joffs, jports, jkinds, static_cast<jint>(client_pid),
            calling_uid));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            r = nullptr;
        }
        env->DeleteLocalRef(jdata);
        env->DeleteLocalRef(joffs);
        env->DeleteLocalRef(jports);
        env->DeleteLocalRef(jkinds);
        if (in.head.flags & kFlagOneway) {
            if (r) env->DeleteLocalRef(r);
            continue;
        }
        Message reply;
        reply.head.type = kReply;
        reply.head.status = kFailedTransaction;
        std::vector<HANDLE> sent;
        if (r) {
            // This thread stays attached for the whole connection and never
            // returns to Java, so every local ref made here must be freed here.
            auto int_element = [&](jsize i) {
                auto a = static_cast<jintArray>(env->GetObjectArrayElement(r, i));
                std::vector<int32_t> v = from_java(env, a);
                if (a) env->DeleteLocalRef(a);
                return v;
            };
            std::vector<int32_t> sv = int_element(0);
            if (!sv.empty()) reply.head.status = sv[0];
            auto d = static_cast<jbyteArray>(env->GetObjectArrayElement(r, 1));
            if (d) {
                reply.data.resize(static_cast<size_t>(env->GetArrayLength(d)));
                if (!reply.data.empty())
                    env->GetByteArrayRegion(d, 0, static_cast<jsize>(reply.data.size()),
                                            reinterpret_cast<jbyte*>(reply.data.data()));
                env->DeleteLocalRef(d);
            }
            reply.offsets = int_element(2);
            std::vector<int32_t> rp = int_element(3);
            std::vector<int32_t> rk = int_element(4);
            if (rp.size() != rk.size() || !outgoing_objects(rp, rk, client, &reply.objects, &sent)) {
                recall(client, sent);
                sent.clear();
                reply = Message();
                reply.head.type = kReply;
                reply.head.status = kBadValue;
            }
            env->DeleteLocalRef(r);
        }
        if (!write_sync(pipe, encode(reply))) {
            recall(client, sent);  // the caller died mid-call: its fds go with it
            break;
        }
    }
    if (env && g_vm) g_vm->DetachCurrentThread();
    if (client) CloseHandle(client);
    DisconnectNamedPipe(pipe);
    CloseHandle(pipe);
    return 0;
}

HANDLE create_instance(const std::wstring& name, bool first) {
    return CreateNamedPipeW(name.c_str(), PIPE_ACCESS_DUPLEX | (first ? FILE_FLAG_FIRST_PIPE_INSTANCE : 0),
                            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                            PIPE_UNLIMITED_INSTANCES, 64 * 1024, 64 * 1024, 0, pipe_security());
}

struct ListenArgs {
    std::wstring name;
    HANDLE first;  // the instance created before the name was handed out
    int32_t root;
};

// Accepts connections on `name` for the life of the process; each one is
// served on its own thread.
DWORD WINAPI listen_loop(void* param) {
    std::unique_ptr<ListenArgs> args(static_cast<ListenArgs*>(param));
    HANDLE next = args->first;
    for (;;) {
        if (next == INVALID_HANDLE_VALUE) {
            next = create_instance(args->name, false);
            if (next == INVALID_HANDLE_VALUE) {
                Sleep(100);
                continue;
            }
        }
        const BOOL connected = ConnectNamedPipe(next, nullptr) || GetLastError() == ERROR_PIPE_CONNECTED;
        if (!connected) {
            CloseHandle(next);
            next = INVALID_HANDLE_VALUE;
            continue;
        }
        auto* serve = new ServeArgs{next, args->root};
        HANDLE t = CreateThread(nullptr, 1 << 20, serve_connection, serve, STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
        if (t) {
            CloseHandle(t);
        } else {
            CloseHandle(next);
            delete serve;
        }
        next = INVALID_HANDLE_VALUE;
    }
    return 0;
}

bool start_listening(const std::wstring& name, HANDLE first, int32_t root) {
    auto* args = new ListenArgs{name, first, root};
    HANDLE t = CreateThread(nullptr, 256 * 1024, listen_loop, args, STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
    if (!t) {
        delete args;
        return false;
    }
    CloseHandle(t);
    return true;
}

// This process's endpoint, serving from the first call on.
bool ensure_server() {
    static std::once_flag once;
    static bool ok = false;
    std::call_once(once, [] {
        // A random part, so a later process that gets the same pid is never
        // taken for this one by a proxy that outlived it.
        uint64_t nonce = 0;
        if (BCryptGenRandom(nullptr, reinterpret_cast<PUCHAR>(&nonce), sizeof nonce,
                            BCRYPT_USE_SYSTEM_PREFERRED_RNG) != 0) {
            LARGE_INTEGER t{};
            QueryPerformanceCounter(&t);
            nonce = static_cast<uint64_t>(t.QuadPart);
        }
        wchar_t tail[64];
        std::swprintf(tail, 64, L"b-%lu-%016llx", static_cast<unsigned long>(GetCurrentProcessId()),
                      static_cast<unsigned long long>(nonce));
        const std::wstring name = prefix() + tail;
        HANDLE first = create_instance(name, true);
        if (first == INVALID_HANDLE_VALUE) {
            std::fprintf(stderr, "[binder] cannot create this process's endpoint (error %lu)\n", GetLastError());
            return;
        }
        {
            std::lock_guard<std::mutex> hold(g_lock);
            g_self = name;
        }
        ok = start_listening(name, first, 0);
    });
    return ok;
}

// ---- client ------------------------------------------------------------------------

// A new connection to `ep`, its server verified; INVALID_HANDLE_VALUE when the
// endpoint is gone (or is not this user's runtime).
HANDLE connect_to(Endpoint& ep, bool* gone) {
    *gone = false;
    for (int attempt = 0; attempt < 100; ++attempt) {
        HANDLE h = CreateFileW(ep.name.c_str(), GENERIC_READ | GENERIC_WRITE, 0, nullptr, OPEN_EXISTING,
                               FILE_FLAG_OVERLAPPED | SECURITY_SQOS_PRESENT | SECURITY_IDENTIFICATION, nullptr);
        if (h == INVALID_HANDLE_VALUE) {
            const DWORD e = GetLastError();
            if (e == ERROR_PIPE_BUSY) {
                WaitNamedPipeW(ep.name.c_str(), 1000);
                continue;
            }
            *gone = (e == ERROR_FILE_NOT_FOUND || e == ERROR_PATH_NOT_FOUND);
            return INVALID_HANDLE_VALUE;
        }
        ULONG pid = 0;
        GetNamedPipeServerProcessId(h, &pid);
        std::lock_guard<std::mutex> hold(ep.lock);
        if (!ep.process) {
            HANDLE p = open_peer(pid);
            if (!trusted_peer(p)) {
                std::fprintf(stderr, "[binder] %ls is served by pid %lu, not this user's Win.apk runtime\n",
                             ep.name.c_str(), static_cast<unsigned long>(pid));
                if (p) CloseHandle(p);
                CloseHandle(h);
                *gone = true;
                return INVALID_HANDLE_VALUE;
            }
            ep.process = p;
            ep.pid = pid;
        } else if (pid != ep.pid) {
            // The name now answers from another process: the one we knew died.
            CloseHandle(h);
            *gone = true;
            return INVALID_HANDLE_VALUE;
        }
        return h;
    }
    return INVALID_HANDLE_VALUE;
}

void mark_dead(Endpoint& ep) { ep.dead = true; }

// The server process of `ep`, connecting once if it was never reached.
HANDLE process_of(const std::shared_ptr<Endpoint>& ep) {
    {
        std::lock_guard<std::mutex> hold(ep->lock);
        if (ep->process) return ep->process;
    }
    if (ep->dead) return nullptr;
    bool gone = false;
    HANDLE c = connect_to(*ep, &gone);
    if (c == INVALID_HANDLE_VALUE) {
        if (gone) mark_dead(*ep);
        return nullptr;
    }
    std::lock_guard<std::mutex> hold(ep->lock);
    ep->idle.push_back(c);
    return ep->process;
}

bool endpoint_dead(const std::shared_ptr<Endpoint>& ep) {
    if (ep->dead) return true;
    HANDLE p = process_of(ep);
    if (!p) return ep->dead.load();
    if (WaitForSingleObject(p, 0) == WAIT_OBJECT_0) mark_dead(*ep);
    return ep->dead.load();
}

void CALLBACK on_process_exit(void* param, BOOLEAN) {
    auto* ep = static_cast<Endpoint*>(param);
    mark_dead(*ep);
    std::vector<int32_t> handles;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        for (auto& [h, p] : g_proxies)
            if (p.ep.get() == ep && p.want_death) {
                p.want_death = false;
                handles.push_back(h);
            }
    }
    JNIEnv* env = attach_thread("binder-death");
    if (!env) return;
    for (int32_t h : handles) {
        env->CallStaticVoidMethod(g_transport, g_on_dead, static_cast<jint>(h));
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
}

std::shared_ptr<Endpoint> proxy_endpoint(int32_t handle, uint32_t* id) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_proxies.find(handle);
    if (it == g_proxies.end()) return nullptr;
    if (id) *id = it->second.id;
    return it->second.ep;
}

jobjectArray result(JNIEnv* env, int32_t status, const Message* in) {
    jobjectArray r = env->NewObjectArray(5, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(r, 0, to_java(env, {status}));
    if (in) {
        std::vector<int32_t> ports, kinds;
        incoming_objects(*in, &ports, &kinds);
        jbyteArray d = env->NewByteArray(static_cast<jsize>(in->data.size()));
        if (!in->data.empty())
            env->SetByteArrayRegion(d, 0, static_cast<jsize>(in->data.size()),
                                    reinterpret_cast<const jbyte*>(in->data.data()));
        env->SetObjectArrayElement(r, 1, d);
        env->SetObjectArrayElement(r, 2, to_java(env, in->offsets));
        env->SetObjectArrayElement(r, 3, to_java(env, ports));
        env->SetObjectArrayElement(r, 4, to_java(env, kinds));
    }
    return r;
}

// One two-way exchange on a pooled connection of `ep`. Returns false with
// *dead set when the far process is gone.
bool exchange(const std::shared_ptr<Endpoint>& ep, const std::vector<uint8_t>& request, Message* reply, bool* dead) {
    *dead = false;
    for (int attempt = 0; attempt < 2; ++attempt) {
        HANDLE c = INVALID_HANDLE_VALUE;
        {
            std::lock_guard<std::mutex> hold(ep->lock);
            if (!ep->idle.empty()) {
                c = ep->idle.back();
                ep->idle.pop_back();
            }
        }
        const bool fresh = (c == INVALID_HANDLE_VALUE);
        if (fresh) {
            bool gone = false;
            c = connect_to(*ep, &gone);
            if (c == INVALID_HANDLE_VALUE) {
                if (gone) mark_dead(*ep);
                *dead = ep->dead;
                return false;
            }
        }
        HANDLE peer = process_of(ep);
        std::vector<uint8_t> frame;
        if (io_overlapped(c, true, const_cast<uint8_t*>(request.data()), request.size(), peer) &&
            read_frame_overlapped(c, peer, &frame)) {
            std::lock_guard<std::mutex> hold(ep->lock);
            ep->idle.push_back(c);
            return decode(frame, reply);
        }
        CloseHandle(c);
        if (endpoint_dead(ep)) {
            *dead = true;
            return false;
        }
        // A pooled connection the server closed (idle too long, a restart of
        // its listener): one more try on a fresh one.
        if (fresh) return false;
    }
    return false;
}

bool send_oneway(const std::shared_ptr<Endpoint>& ep, const std::vector<uint8_t>& request, bool* dead) {
    *dead = false;
    std::lock_guard<std::mutex> order(ep->oneway_lock);
    for (int attempt = 0; attempt < 2; ++attempt) {
        if (!ep->oneway) {
            bool gone = false;
            HANDLE c = connect_to(*ep, &gone);
            if (c == INVALID_HANDLE_VALUE) {
                if (gone) mark_dead(*ep);
                *dead = ep->dead;
                return false;
            }
            ep->oneway = c;
        }
        if (io_overlapped(ep->oneway, true, const_cast<uint8_t*>(request.data()), request.size(), process_of(ep)))
            return true;
        CloseHandle(ep->oneway);
        ep->oneway = nullptr;
        if (endpoint_dead(ep)) {
            *dead = true;
            return false;
        }
    }
    return false;
}

std::wstring java_wstring(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* c = env->GetStringChars(s, nullptr);
    std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return w;
}

// A published name's pipe: only letters, digits, '.', '_' and '-'.
bool published_pipe(JNIEnv* env, jstring jname, std::wstring* out) {
    const std::wstring name = java_wstring(env, jname);
    if (name.empty() || name.size() > 128) return false;
    for (wchar_t c : name)
        if (!(iswalnum(c) || c == L'.' || c == L'_' || c == L'-')) return false;
    *out = prefix() + name;
    return true;
}

// ---- natives -----------------------------------------------------------------------

jboolean JNICALL Binder_init(JNIEnv* env, jclass cls) {
    if (!g_transport) {
        env->GetJavaVM(&g_vm);
        g_dispatch = env->GetStaticMethodID(cls, "dispatch", "(III[B[I[I[III)[Ljava/lang/Object;");
        g_on_dead = env->GetStaticMethodID(cls, "onDeadName", "(I)V");
        if (!g_dispatch || !g_on_dead) {
            env->ExceptionClear();
            return JNI_FALSE;
        }
        g_uid_for = env->GetStaticMethodID(cls, "callingUidFor", "(I)I");
        if (!g_uid_for) env->ExceptionClear();
        g_transport = static_cast<jclass>(env->NewGlobalRef(cls));
    }
    return (!self_sid().empty() && pipe_security()) ? JNI_TRUE : JNI_FALSE;
}

jint JNICALL Binder_createPort(JNIEnv*, jclass) {
    if (!ensure_server()) return 0;
    const int32_t h = g_next.fetch_add(1);
    std::lock_guard<std::mutex> hold(g_lock);
    g_locals.insert(h);
    return h;
}

// Serve `name` (the broker's); negative when another process already does.
jint JNICALL Binder_checkIn(JNIEnv* env, jclass, jstring jname) {
    std::wstring pipe;
    if (!published_pipe(env, jname, &pipe)) return -static_cast<jint>(ERROR_INVALID_NAME);
    if (!ensure_server()) return -1;
    HANDLE first = create_instance(pipe, true);
    if (first == INVALID_HANDLE_VALUE) return -static_cast<jint>(GetLastError());
    const int32_t h = g_next.fetch_add(1);
    {
        std::lock_guard<std::mutex> hold(g_lock);
        g_locals.insert(h);
    }
    if (!start_listening(pipe, first, h)) return -1;
    return h;
}

// The object published under `name` as a binder handle, 0 when nothing serves it.
jint JNICALL Binder_lookUp(JNIEnv* env, jclass, jstring jname) {
    std::wstring pipe;
    if (!published_pipe(env, jname, &pipe)) return 0;
    Endpoint lookup;
    lookup.name = pipe;
    bool gone = false;
    HANDLE c = connect_to(lookup, &gone);
    if (c == INVALID_HANDLE_VALUE) return 0;
    Message ask;
    ask.head.type = kGetRoot;
    std::vector<uint8_t> frame;
    Message answer;
    std::vector<uint8_t> req = encode(ask);
    bool good = io_overlapped(c, true, req.data(), req.size(), lookup.process) &&
                read_frame_overlapped(c, lookup.process, &frame) && decode(frame, &answer) &&
                answer.head.type == kRoot && answer.objects.size() == 1 && answer.objects[0].kind == kKindBinder;
    CloseHandle(c);
    if (lookup.process) CloseHandle(lookup.process);
    if (!good) return 0;
    return incoming_binder(answer.objects[0]);
}

void JNICALL Binder_startThreads(JNIEnv*, jclass, jint) {
    // One thread per connection, started as clients connect: a fixed pool could
    // deadlock on nested calls between two processes, which a binder pool
    // avoids by growing on demand (and this does by design).
    ensure_server();
}

jobjectArray JNICALL Binder_transact(JNIEnv* env, jclass, jint dest, jint code, jint flags, jint,
                                     jbyteArray jdata, jintArray joffs, jintArray jports, jintArray jkinds) {
    uint32_t id = 0;
    std::shared_ptr<Endpoint> ep = proxy_endpoint(dest, &id);
    if (!ep || ep->dead) return result(env, kDeadObject, nullptr);
    Message m;
    m.head.type = kTransact;
    m.head.code = static_cast<uint32_t>(code);
    m.head.flags = static_cast<uint32_t>(flags);
    m.head.target = id;
    if (jdata) {
        m.data.resize(static_cast<size_t>(env->GetArrayLength(jdata)));
        if (!m.data.empty())
            env->GetByteArrayRegion(jdata, 0, static_cast<jsize>(m.data.size()), reinterpret_cast<jbyte*>(m.data.data()));
    }
    m.offsets = from_java(env, joffs);
    std::vector<int32_t> ports = from_java(env, jports), kinds = from_java(env, jkinds);
    if (ports.size() != kinds.size()) return result(env, kBadValue, nullptr);
    HANDLE target = process_of(ep);
    if (!target) return result(env, ep->dead ? kDeadObject : kFailedTransaction, nullptr);
    std::vector<HANDLE> sent;
    if (!outgoing_objects(ports, kinds, target, &m.objects, &sent)) {
        recall(target, sent);
        return result(env, kBadValue, nullptr);
    }
    const std::vector<uint8_t> request = encode(m);
    bool dead = false;
    if (flags & kFlagOneway) {
        if (!send_oneway(ep, request, &dead)) {
            recall(target, sent);
            return result(env, dead ? kDeadObject : kFailedTransaction, nullptr);
        }
        return result(env, kNoError, nullptr);
    }
    Message reply;
    if (!exchange(ep, request, &reply, &dead)) {
        recall(target, sent);
        return result(env, dead ? kDeadObject : kFailedTransaction, nullptr);
    }
    if (reply.head.type != kReply) return result(env, kFailedTransaction, nullptr);
    return result(env, reply.head.status, &reply);
}

jboolean JNICALL Binder_requestDeath(JNIEnv*, jclass, jint port) {
    std::shared_ptr<Endpoint> ep = proxy_endpoint(port, nullptr);
    if (!ep) return JNI_FALSE;
    HANDLE p = process_of(ep);
    if (!p || endpoint_dead(ep)) return JNI_FALSE;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_proxies.find(port);
        if (it == g_proxies.end()) return JNI_FALSE;
        it->second.want_death = true;
    }
    std::lock_guard<std::mutex> hold(ep->lock);
    if (!ep->wait &&
        !RegisterWaitForSingleObject(&ep->wait, ep->process, on_process_exit, ep.get(), INFINITE,
                                     WT_EXECUTEONLYONCE)) {
        ep->wait = nullptr;
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jboolean JNICALL Binder_isDead(JNIEnv*, jclass, jint port) {
    std::shared_ptr<Endpoint> ep = proxy_endpoint(port, nullptr);
    if (!ep) return JNI_TRUE;  // an unknown handle is no live binder (Mach: an invalid name)
    return endpoint_dead(ep) ? JNI_TRUE : JNI_FALSE;
}

void JNICALL Binder_releaseSend(JNIEnv*, jclass, jint port) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_proxies.find(port);
    if (it == g_proxies.end()) return;  // one of our own objects: nothing to release
    if (--it->second.refs > 0) return;
    g_proxy_by_ref.erase(std::make_pair(it->second.ep->name, it->second.id));
    g_proxies.erase(it);
}

// ---- com.macapk.loader.WinProcesses -------------------------------------------------

using NtQueryInformationProcessFn = LONG(NTAPI*)(HANDLE, int, void*, ULONG, ULONG*);

// The command line from the process's own parameters block, through its PEB
// (what ProcessCommandLineInformation returns too), for systems that do not
// answer that class.
std::wstring command_line_from_peb(HANDLE p, NtQueryInformationProcessFn query) {
    PROCESS_BASIC_INFORMATION pbi{};
    ULONG n = 0;
    if (query(p, 0 /* ProcessBasicInformation */, &pbi, sizeof pbi, &n) < 0 || !pbi.PebBaseAddress) return {};
    // 64-bit: PEB.ProcessParameters at 0x20, RTL_USER_PROCESS_PARAMETERS.CommandLine at 0x70.
    uintptr_t params = 0;
    SIZE_T got = 0;
    if (!ReadProcessMemory(p, reinterpret_cast<const uint8_t*>(pbi.PebBaseAddress) + 0x20, &params, sizeof params,
                           &got) || !params)
        return {};
    UNICODE_STRING us{};
    if (!ReadProcessMemory(p, reinterpret_cast<const void*>(params + 0x70), &us, sizeof us, &got) || !us.Buffer ||
        us.Length == 0 || us.Length > 65534)
        return {};
    std::wstring out(us.Length / sizeof(wchar_t), L'\0');
    if (!ReadProcessMemory(p, us.Buffer, out.data(), us.Length, &got)) return {};
    return out;
}

// The command line Windows recorded for `pid`, when it is this user's Win.apk
// runtime; else null. ProcessCommandLineInformation (Windows 8.1 and later),
// else the PEB.
jstring JNICALL Proc_commandLine(JNIEnv* env, jclass, jint pid) {
    HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, FALSE,
                           static_cast<DWORD>(pid));
    if (!trusted_peer(p)) {
        if (p) CloseHandle(p);
        return nullptr;
    }
    static auto query = reinterpret_cast<NtQueryInformationProcessFn>(
        GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess"));
    std::wstring line;
    if (query) {
        ULONG need = 0;
        query(p, 60 /* ProcessCommandLineInformation */, nullptr, 0, &need);
        if (need > 0 && need < (1u << 20)) {
            std::vector<uint8_t> buf(need);
            if (query(p, 60, buf.data(), need, &need) >= 0) {
                const auto* us = reinterpret_cast<const UNICODE_STRING*>(buf.data());
                line.assign(us->Buffer, us->Length / sizeof(wchar_t));
            }
        }
        if (line.empty()) line = command_line_from_peb(p, query);
    }
    CloseHandle(p);
    if (line.empty()) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(line.data()), static_cast<jsize>(line.size()));
}

// Whether `pid` is alive and this user's Win.apk runtime.
jboolean JNICALL Proc_isRuntime(JNIEnv*, jclass, jint pid) {
    HANDLE p = open_peer(static_cast<DWORD>(pid));
    bool ok = trusted_peer(p) && WaitForSingleObject(p, 0) == WAIT_TIMEOUT;
    if (p) CloseHandle(p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The process's creation time (FILETIME ticks), which with the pid names one
// process for good; 0 when it cannot be read.
jlong JNICALL Proc_startTime(JNIEnv*, jclass, jint pid) {
    HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid));
    if (!p) return 0;
    FILETIME created{}, exited{}, kernel{}, user{};
    jlong t = 0;
    if (GetProcessTimes(p, &created, &exited, &kernel, &user))
        t = static_cast<jlong>((static_cast<uint64_t>(created.dwHighDateTime) << 32) | created.dwLowDateTime);
    CloseHandle(p);
    return t;
}

// The pipe-name prefix this user's runtimes share (tests and logs).
jstring JNICALL Proc_pipePrefix(JNIEnv* env, jclass) {
    const std::wstring& p = prefix();
    return env->NewString(reinterpret_cast<const jchar*>(p.data()), static_cast<jsize>(p.size()));
}

constexpr const char* kTransport = "android.os.MacApkBinderTransport";
constexpr const char* kProc = "com.macapk.loader.WinProcesses";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kTransport, "nativeInit", "()Z", Binder_init),
    WINAPK_HOST_NATIVE(kTransport, "nativeCreatePort", "()I", Binder_createPort),
    WINAPK_HOST_NATIVE(kTransport, "nativeCheckIn", "(Ljava/lang/String;)I", Binder_checkIn),
    WINAPK_HOST_NATIVE(kTransport, "nativeLookUp", "(Ljava/lang/String;)I", Binder_lookUp),
    WINAPK_HOST_NATIVE(kTransport, "nativeStartThreads", "(I)V", Binder_startThreads),
    WINAPK_HOST_NATIVE(kTransport, "nativeTransact", "(IIII[B[I[I[I)[Ljava/lang/Object;", Binder_transact),
    WINAPK_HOST_NATIVE(kTransport, "nativeRequestDeath", "(I)Z", Binder_requestDeath),
    WINAPK_HOST_NATIVE(kTransport, "nativeIsDead", "(I)Z", Binder_isDead),
    WINAPK_HOST_NATIVE(kTransport, "nativeReleaseSend", "(I)V", Binder_releaseSend),
    WINAPK_HOST_NATIVE(kProc, "commandLine", "(I)Ljava/lang/String;", Proc_commandLine),
    WINAPK_HOST_NATIVE(kProc, "isRuntime", "(I)Z", Proc_isRuntime),
    WINAPK_HOST_NATIVE(kProc, "startTime", "(I)J", Proc_startTime),
    WINAPK_HOST_NATIVE(kProc, "pipePrefix", "()Ljava/lang/String;", Proc_pipePrefix),
};

}  // namespace

HostMethods native_binder_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
