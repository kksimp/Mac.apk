// native_bluetooth.cpp -- com.macapk.loader.HostBluetooth on Windows: talking
// to Bluetooth devices, for BluetoothSocket / BluetoothServerSocket (classic
// RFCOMM), BluetoothLeScanner (LE advertisements) and BluetoothGatt (the LE
// GATT client). The radio itself, its switch and the paired list are
// native_radios.cpp's.
//
// RFCOMM is Winsock's AF_BTH: a connect finds the channel of the service UUID
// with an SDP query (WSALookupService), a listen registers an SDP record for
// its channel (WSASetService) so phones find it. Reads block in recv on the
// Java thread that called them, as Bionic's do, and a close from another
// thread ends them with an IOException, as on Android.
//
// LE is WinRT (Windows.Devices.Bluetooth, its GenericAttributeProfile and
// Advertisement namespaces) through the ABI declared here, since MinGW's
// headers stop at Windows.Devices.Enumeration; the IIDs of the parameterized
// interfaces (the event handlers and completion handlers) are derived from
// their signatures (bluetooth_util.h). combase is loaded at run time, so a
// program that never touches LE loads nothing. Every WinRT call is made on a
// thread of ours in the multithreaded apartment: one for the advertisement
// watcher, one per GATT connection, each fed commands through a queue; the
// Java side drains their event queues on threads of its own, which is where
// the app's callbacks run (Android delivers them on binder threads).
//
// Windows holds an LE link while something holds the device and its
// characteristics, so a disconnect is a release of everything, and
// notifications arrive only after the client characteristic configuration
// was written through the dedicated call, which the CCCD write is routed to.

#include "host_natives.h"

#include "bluetooth_util.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <winsock2.h>
#include <ws2bth.h>
#include <windows.h>
#include <inspectable.h>
#include <unknwn.h>

namespace winapk::host {

namespace {

static_assert(sizeof(bt::Guid) == sizeof(GUID), "bt::Guid mirrors GUID");

const GUID& as_guid(const bt::Guid& g) { return *reinterpret_cast<const GUID*>(&g); }
bt::Guid from_guid(const GUID& g) {
    bt::Guid out;
    std::memcpy(&out, &g, sizeof out);
    return out;
}

void throw_io(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/io/IOException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::string utf8(const wchar_t* w, size_t n) {
    if (!w || n == 0) return {};
    const int len = WideCharToMultiByte(CP_UTF8, 0, w, static_cast<int>(n), nullptr, 0, nullptr, nullptr);
    if (len <= 0) return {};
    std::string s(static_cast<size_t>(len), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, static_cast<int>(n), &s[0], len, nullptr, nullptr);
    return s;
}

std::wstring wide(const char* s) {
    if (!s || !*s) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 1) return {};
    std::wstring w(static_cast<size_t>(n - 1), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s, -1, &w[0], n);
    return w;
}

std::string jstring_utf8(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

jbyteArray to_java_bytes(JNIEnv* env, const std::vector<uint8_t>& v) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) {
        env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jbyte*>(v.data()));
    }
    return a;
}

jobjectArray to_java_strings(JNIEnv* env, const std::vector<std::string>& v) {
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    jobjectArray a = env->NewObjectArray(static_cast<jsize>(v.size()), string_class, nullptr);
    for (size_t i = 0; a && i < v.size(); ++i) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(a, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(string_class);
    return a;
}

// ---- RFCOMM (Winsock) -----------------------------------------------------------------

// Java's own networking calls WSAStartup for itself; so must we, once.
bool winsock() {
    static const bool ok = [] {
        WSADATA data;
        return WSAStartup(MAKEWORD(2, 2), &data) == 0;
    }();
    return ok;
}

std::string wsa_text(int err) {
    wchar_t* msg = nullptr;
    const DWORD flags = FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS;
    const DWORD n =
        FormatMessageW(flags, nullptr, static_cast<DWORD>(err), 0, reinterpret_cast<wchar_t*>(&msg), 0, nullptr);
    std::string text = n ? utf8(msg, n) : "";
    if (msg) LocalFree(msg);
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ')) text.pop_back();
    if (text.empty()) text = "Winsock error " + std::to_string(err);
    return text;
}

// The sockets Java holds, by a handle of ours: a SOCKET number is reused by
// Windows as soon as it is closed, a handle here never is, so a read racing
// a close on another thread cannot land on a stranger's socket.
struct Endpoint {
    SOCKET socket = INVALID_SOCKET;
    uint64_t remote = 0;  // the peer's address (connected sockets)
    bool server = false;
    // A listening socket's SDP record, to delete at close.
    bt::Guid service{};
    std::wstring service_name;
    SOCKADDR_BTH local{};
};

std::mutex g_rfcomm_lock;
std::map<jlong, Endpoint> g_endpoints;
jlong g_next_endpoint = 1;

jlong add_endpoint(Endpoint e) {
    std::lock_guard<std::mutex> l(g_rfcomm_lock);
    const jlong h = g_next_endpoint++;
    g_endpoints[h] = e;
    return h;
}

bool endpoint(jlong h, Endpoint* out) {
    std::lock_guard<std::mutex> l(g_rfcomm_lock);
    auto it = g_endpoints.find(h);
    if (it == g_endpoints.end()) return false;
    *out = it->second;
    return true;
}

bool take_endpoint(jlong h, Endpoint* out) {
    std::lock_guard<std::mutex> l(g_rfcomm_lock);
    auto it = g_endpoints.find(h);
    if (it == g_endpoints.end()) return false;
    *out = it->second;
    g_endpoints.erase(it);
    return true;
}

// Secure (authenticated, encrypted) links, as createRfcommSocketToServiceRecord
// asks for; the insecure variant leaves the link as the pairing left it.
void secure_socket(SOCKET s, bool secure) {
    if (!secure) return;
    ULONG on = TRUE;
    setsockopt(s, SOL_RFCOMM, SO_BTH_AUTHENTICATE, reinterpret_cast<const char*>(&on), sizeof on);
    setsockopt(s, SOL_RFCOMM, SO_BTH_ENCRYPT, reinterpret_cast<const char*>(&on), sizeof on);
}

// The RFCOMM channel of `service` on the device at `address`, by an SDP
// query of the device (not the cache: the channel can change between runs).
// 0 when the device does not offer the service or cannot be reached.
ULONG sdp_channel(uint64_t address, const bt::Guid& service) {
    const std::wstring context = L"(" + wide(bt::address_text(address).c_str()) + L")";
    WSAQUERYSETW query{};
    query.dwSize = sizeof query;
    query.dwNameSpace = NS_BTH;
    query.lpServiceClassId = const_cast<GUID*>(&as_guid(service));
    query.lpszContext = const_cast<wchar_t*>(context.c_str());
    HANDLE lookup = nullptr;
    const DWORD flags = LUP_FLUSHCACHE | LUP_RETURN_ADDR;
    if (WSALookupServiceBeginW(&query, flags, &lookup) != 0) return 0;
    ULONG port = 0;
    std::vector<char> buffer(4096);
    for (;;) {
        DWORD size = static_cast<DWORD>(buffer.size());
        auto* result = reinterpret_cast<WSAQUERYSETW*>(buffer.data());
        const int rc = WSALookupServiceNextW(lookup, flags, &size, result);
        if (rc != 0) {
            if (WSAGetLastError() == WSAEFAULT && size > buffer.size()) {
                buffer.resize(size);
                continue;
            }
            break;
        }
        for (DWORD i = 0; i < result->dwNumberOfCsAddrs && !port; ++i) {
            const SOCKET_ADDRESS& a = result->lpcsaBuffer[i].RemoteAddr;
            if (a.lpSockaddr && a.iSockaddrLength >= static_cast<int>(sizeof(SOCKADDR_BTH))) {
                port = reinterpret_cast<const SOCKADDR_BTH*>(a.lpSockaddr)->port;
            }
        }
        if (port) break;
    }
    WSALookupServiceEnd(lookup);
    return port;
}

jboolean JNICALL Bt_rfcommAvailable(JNIEnv*, jclass) {
    if (!winsock()) return JNI_FALSE;
    // The Bluetooth stack registers the address family only with a radio
    // present (driver loaded); without one the socket call fails.
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) return JNI_FALSE;
    closesocket(s);
    return JNI_TRUE;
}

jlong JNICALL Bt_rfcommConnect(JNIEnv* env, jclass, jstring jaddress, jstring juuid, jboolean secure) {
    uint64_t address = 0;
    bt::Guid service;
    if (!winsock() || !bt::address_parse(jstring_utf8(env, jaddress), &address) ||
        !bt::guid_parse(jstring_utf8(env, juuid), &service)) {
        throw_io(env, "bad Bluetooth address or service UUID");
        return 0;
    }
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) {
        throw_io(env, "no Bluetooth: " + wsa_text(WSAGetLastError()));
        return 0;
    }
    secure_socket(s, secure == JNI_TRUE);
    SOCKADDR_BTH peer{};
    peer.addressFamily = AF_BTH;
    peer.btAddr = address;
    peer.port = sdp_channel(address, service);
    // Without a channel from the query the stack is left to resolve the
    // service class itself when connecting (it runs the same query, from its
    // cache first), so a device that answered one way answers the other.
    if (peer.port == 0) peer.serviceClassId = as_guid(service);
    if (connect(s, reinterpret_cast<const sockaddr*>(&peer), sizeof peer) != 0) {
        const int err = WSAGetLastError();
        closesocket(s);
        throw_io(env, "connect to " + bt::address_text(address) + " failed: " + wsa_text(err));
        return 0;
    }
    Endpoint e;
    e.socket = s;
    e.remote = address;
    return add_endpoint(e);
}

jlong JNICALL Bt_rfcommListen(JNIEnv* env, jclass, jstring jname, jstring juuid, jboolean secure) {
    bt::Guid service;
    if (!winsock() || !bt::guid_parse(jstring_utf8(env, juuid), &service)) {
        throw_io(env, "bad service UUID");
        return 0;
    }
    SOCKET s = socket(AF_BTH, SOCK_STREAM, BTHPROTO_RFCOMM);
    if (s == INVALID_SOCKET) {
        throw_io(env, "no Bluetooth: " + wsa_text(WSAGetLastError()));
        return 0;
    }
    secure_socket(s, secure == JNI_TRUE);
    SOCKADDR_BTH local{};
    local.addressFamily = AF_BTH;
    local.port = BT_PORT_ANY;
    if (bind(s, reinterpret_cast<const sockaddr*>(&local), sizeof local) != 0 || listen(s, 2) != 0) {
        const int err = WSAGetLastError();
        closesocket(s);
        throw_io(env, "listen failed: " + wsa_text(err));
        return 0;
    }
    int len = sizeof local;
    getsockname(s, reinterpret_cast<sockaddr*>(&local), &len);
    // The SDP record: the service class UUID on our channel, so a phone's
    // createRfcommSocketToServiceRecord finds it.
    Endpoint e;
    e.socket = s;
    e.server = true;
    e.service = service;
    e.service_name = wide(jstring_utf8(env, jname).c_str());
    e.local = local;
    CSADDR_INFO addr{};
    addr.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&e.local);
    addr.LocalAddr.iSockaddrLength = sizeof e.local;
    addr.RemoteAddr = addr.LocalAddr;
    addr.iSocketType = SOCK_STREAM;
    addr.iProtocol = BTHPROTO_RFCOMM;
    WSAQUERYSETW record{};
    record.dwSize = sizeof record;
    record.dwNameSpace = NS_BTH;
    record.lpServiceClassId = const_cast<GUID*>(&as_guid(e.service));
    record.lpszServiceInstanceName = const_cast<wchar_t*>(e.service_name.c_str());
    record.dwNumberOfCsAddrs = 1;
    record.lpcsaBuffer = &addr;
    if (WSASetServiceW(&record, RNRSERVICE_REGISTER, 0) != 0) {
        const int err = WSAGetLastError();
        closesocket(s);
        throw_io(env, "could not publish the service: " + wsa_text(err));
        return 0;
    }
    return add_endpoint(e);
}

void unpublish(const Endpoint& e) {
    CSADDR_INFO addr{};
    SOCKADDR_BTH local = e.local;
    addr.LocalAddr.lpSockaddr = reinterpret_cast<sockaddr*>(&local);
    addr.LocalAddr.iSockaddrLength = sizeof local;
    addr.RemoteAddr = addr.LocalAddr;
    addr.iSocketType = SOCK_STREAM;
    addr.iProtocol = BTHPROTO_RFCOMM;
    WSAQUERYSETW record{};
    record.dwSize = sizeof record;
    record.dwNameSpace = NS_BTH;
    record.lpServiceClassId = const_cast<GUID*>(&as_guid(e.service));
    record.lpszServiceInstanceName = const_cast<wchar_t*>(e.service_name.c_str());
    record.dwNumberOfCsAddrs = 1;
    record.lpcsaBuffer = &addr;
    WSASetServiceW(&record, RNRSERVICE_DELETE, 0);
}

// A connection, or 0 when `timeout_ms` (>= 0) passed without one.
jlong JNICALL Bt_rfcommAccept(JNIEnv* env, jclass, jlong server, jint timeout_ms) {
    Endpoint e;
    if (!endpoint(server, &e) || !e.server) {
        throw_io(env, "server socket closed");
        return 0;
    }
    if (timeout_ms >= 0) {
        fd_set set;
        FD_ZERO(&set);
        FD_SET(e.socket, &set);
        timeval tv{timeout_ms / 1000, (timeout_ms % 1000) * 1000};
        const int n = select(0, &set, nullptr, nullptr, &tv);
        if (n == 0) return 0;
        if (n < 0) {
            throw_io(env, "accept failed: " + wsa_text(WSAGetLastError()));
            return 0;
        }
    }
    SOCKADDR_BTH peer{};
    int len = sizeof peer;
    SOCKET s = accept(e.socket, reinterpret_cast<sockaddr*>(&peer), &len);
    if (s == INVALID_SOCKET) {
        throw_io(env, "accept failed: " + wsa_text(WSAGetLastError()));
        return 0;
    }
    Endpoint c;
    c.socket = s;
    c.remote = peer.btAddr;
    return add_endpoint(c);
}

jint JNICALL Bt_rfcommRead(JNIEnv* env, jclass, jlong h, jbyteArray buf, jint off, jint len) {
    Endpoint e;
    if (!endpoint(h, &e) || e.server) {
        throw_io(env, "socket closed");
        return -1;
    }
    if (len <= 0) return 0;
    std::vector<char> tmp(static_cast<size_t>(len));
    const int n = recv(e.socket, tmp.data(), len, 0);
    if (n == 0) return -1;  // the peer closed: end of stream, as on Android
    if (n < 0) {
        // The usual way here: close() on another thread ended the read.
        Endpoint still;
        throw_io(env, endpoint(h, &still) ? "read failed: " + wsa_text(WSAGetLastError()) : "socket closed");
        return -1;
    }
    env->SetByteArrayRegion(buf, off, n, reinterpret_cast<const jbyte*>(tmp.data()));
    return n;
}

void JNICALL Bt_rfcommWrite(JNIEnv* env, jclass, jlong h, jbyteArray buf, jint off, jint len) {
    Endpoint e;
    if (!endpoint(h, &e) || e.server) {
        throw_io(env, "socket closed");
        return;
    }
    if (len <= 0) return;
    std::vector<char> tmp(static_cast<size_t>(len));
    env->GetByteArrayRegion(buf, off, len, reinterpret_cast<jbyte*>(tmp.data()));
    int done = 0;
    while (done < len) {
        const int n = send(e.socket, tmp.data() + done, len - done, 0);
        if (n <= 0) {
            throw_io(env, "write failed: " + wsa_text(WSAGetLastError()));
            return;
        }
        done += n;
    }
}

jint JNICALL Bt_rfcommAvailableBytes(JNIEnv* env, jclass, jlong h) {
    Endpoint e;
    if (!endpoint(h, &e) || e.server) {
        throw_io(env, "socket closed");
        return 0;
    }
    u_long n = 0;
    return ioctlsocket(e.socket, FIONREAD, &n) == 0 ? static_cast<jint>(n) : 0;
}

jint JNICALL Bt_rfcommMtu(JNIEnv*, jclass, jlong h) {
    Endpoint e;
    if (!endpoint(h, &e) || e.server) return 0;
    ULONG mtu = 0;
    int len = sizeof mtu;
    if (getsockopt(e.socket, SOL_RFCOMM, SO_BTH_MTU, reinterpret_cast<char*>(&mtu), &len) != 0) return 0;
    return static_cast<jint>(mtu);
}

jstring JNICALL Bt_rfcommRemoteAddress(JNIEnv* env, jclass, jlong h) {
    Endpoint e;
    if (!endpoint(h, &e) || e.server) return nullptr;
    return env->NewStringUTF(bt::address_text(e.remote).c_str());
}

void JNICALL Bt_rfcommClose(JNIEnv*, jclass, jlong h) {
    Endpoint e;
    if (!take_endpoint(h, &e)) return;
    if (e.server) unpublish(e);
    // shutdown first: it wakes a recv or accept blocked on another thread
    // before the handle goes.
    shutdown(e.socket, SD_BOTH);
    closesocket(e.socket);
}

// ---- the WinRT ABI we use ----------------------------------------------------------------

struct EventToken { INT64 value; };
using WinRtBool = unsigned char;

struct IAsyncInfoAbi : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Id(UINT32* id) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* status) = 0;  // 0 started, 1 completed, 2 canceled, 3 error
    virtual HRESULT STDMETHODCALLTYPE get_ErrorCode(HRESULT* error) = 0;
    virtual HRESULT STDMETHODCALLTYPE Cancel() = 0;
    virtual HRESULT STDMETHODCALLTYPE Close() = 0;
};

// IAsyncOperation<T>: the pointer a call returns is used as is, so only the
// completion handler's IID (parameterized by T) is ever needed.
template <typename T>
struct IAsyncOp : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE put_Completed(IUnknown* handler) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Completed(IUnknown** handler) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetResults(T* results) = 0;
};

template <typename T>
struct IVectorView : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetAt(UINT32 index, T* item) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Size(UINT32* size) = 0;
    virtual HRESULT STDMETHODCALLTYPE IndexOf(T item, UINT32* index, WinRtBool* found) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetMany(UINT32 start, UINT32 capacity, T* items, UINT32* actual) = 0;
};

template <typename T>
struct IVector : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetAt(UINT32 index, T* item) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Size(UINT32* size) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetView(IVectorView<T>** view) = 0;
    // The mutators follow; none is called.
};

template <typename T>
struct IReference : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Value(T* value) = 0;
};

struct IBuffer : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Capacity(UINT32* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Length(UINT32* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Length(UINT32 value) = 0;
};

struct IBufferByteAccess : IUnknown {
    virtual HRESULT STDMETHODCALLTYPE Buffer(BYTE** value) = 0;
};

struct IBufferFactory : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE Create(UINT32 capacity, IBuffer** buffer) = 0;
};

struct IGattDeviceService;
struct IGattCharacteristic;
struct IGattDescriptor;
struct IGattReadResult;
struct IGattWriteResult;
struct IGattDeviceServicesResult;
struct IGattCharacteristicsResult;
struct IGattDescriptorsResult;
struct IGattReadCccdResult;
struct IBluetoothLEDevice;
struct IBluetoothLEAdvertisement;
struct IBluetoothLEAdvertisementDataSection;

struct IBluetoothLEDeviceStatics : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE FromIdAsync(HSTRING id, IAsyncOp<IBluetoothLEDevice*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE FromBluetoothAddressAsync(UINT64 address, IAsyncOp<IBluetoothLEDevice*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDeviceSelector(HSTRING* selector) = 0;
};

struct IBluetoothLEDevice : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DeviceId(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Name(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_GattServices(IVectorView<IGattDeviceService*>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ConnectionStatus(int* value) = 0;  // 0 disconnected, 1 connected
    virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGattService(GUID uuid, IGattDeviceService** service) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_NameChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_NameChanged(EventToken token) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_GattServicesChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_GattServicesChanged(EventToken token) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_ConnectionStatusChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_ConnectionStatusChanged(EventToken token) = 0;
};

struct IBluetoothLEDevice3 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DeviceAccessInformation(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE RequestAccessAsync(IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGattServicesAsync(IAsyncOp<IGattDeviceServicesResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGattServicesWithCacheModeAsync(int cache_mode,
                                                                        IAsyncOp<IGattDeviceServicesResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGattServicesForUuidAsync(GUID uuid,
                                                                 IAsyncOp<IGattDeviceServicesResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetGattServicesForUuidWithCacheModeAsync(
        GUID uuid, int cache_mode, IAsyncOp<IGattDeviceServicesResult*>** op) = 0;
};

struct IGattDeviceServicesResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<BYTE>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Services(IVectorView<IGattDeviceService*>** value) = 0;
};

struct IGattDeviceService : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetCharacteristics(GUID uuid, IVectorView<IGattCharacteristic*>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetIncludedServices(GUID uuid, IVectorView<IGattDeviceService*>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DeviceId(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
};

struct IGattSession : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DeviceId(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CanMaintainConnection(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_MaintainConnection(WinRtBool value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MaintainConnection(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MaxPduSize(UINT16* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_SessionStatus(int* value) = 0;
};

struct IGattDeviceService3 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DeviceAccessInformation(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Session(IGattSession** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_SharingMode(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE RequestAccessAsync(IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE OpenAsync(int sharing_mode, IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCharacteristicsAsync(IAsyncOp<IGattCharacteristicsResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetCharacteristicsWithCacheModeAsync(
        int cache_mode, IAsyncOp<IGattCharacteristicsResult*>** op) = 0;
};

struct IGattCharacteristicsResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<BYTE>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Characteristics(IVectorView<IGattCharacteristic*>** value) = 0;
};

struct IGattCharacteristic : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetDescriptors(GUID uuid, IVectorView<IGattDescriptor*>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_CharacteristicProperties(UINT32* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ProtectionLevel(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_ProtectionLevel(int value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_UserDescription(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_PresentationFormats(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReadValueAsync(IAsyncOp<IGattReadResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReadValueWithCacheModeAsync(int cache_mode, IAsyncOp<IGattReadResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteValueAsync(IBuffer* value, IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteValueWithOptionAsync(IBuffer* value, int option, IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReadClientCharacteristicConfigurationDescriptorAsync(
        IAsyncOp<IGattReadCccdResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteClientCharacteristicConfigurationDescriptorAsync(int value,
                                                                                            IAsyncOp<int>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_ValueChanged(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_ValueChanged(EventToken token) = 0;
};

struct IGattCharacteristic3 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE GetDescriptorsAsync(IAsyncOp<IGattDescriptorsResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDescriptorsWithCacheModeAsync(
        int cache_mode, IAsyncOp<IGattDescriptorsResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDescriptorsForUuidAsync(GUID uuid, IAsyncOp<IGattDescriptorsResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE GetDescriptorsForUuidWithCacheModeAsync(
        GUID uuid, int cache_mode, IAsyncOp<IGattDescriptorsResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteValueWithResultAsync(IBuffer* value, IAsyncOp<IGattWriteResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteValueWithResultAndOptionAsync(IBuffer* value, int option,
                                                                         IAsyncOp<IGattWriteResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteClientCharacteristicConfigurationDescriptorWithResultAsync(
        int value, IAsyncOp<IGattWriteResult*>** op) = 0;
};

struct IGattDescriptorsResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<BYTE>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Descriptors(IVectorView<IGattDescriptor*>** value) = 0;
};

struct IGattDescriptor : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_ProtectionLevel(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_ProtectionLevel(int value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Uuid(GUID* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AttributeHandle(UINT16* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReadValueAsync(IAsyncOp<IGattReadResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE ReadValueWithCacheModeAsync(int cache_mode, IAsyncOp<IGattReadResult*>** op) = 0;
    virtual HRESULT STDMETHODCALLTYPE WriteValueAsync(IBuffer* value, IAsyncOp<int>** op) = 0;
};

struct IGattDescriptor2 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE WriteValueWithResultAsync(IBuffer* value, IAsyncOp<IGattWriteResult*>** op) = 0;
};

struct IGattReadResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Value(IBuffer** value) = 0;
};

struct IGattReadResult2 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<BYTE>** value) = 0;
};

struct IGattWriteResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ProtocolError(IReference<BYTE>** value) = 0;
};

struct IGattReadCccdResult : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ClientCharacteristicConfigurationDescriptor(int* value) = 0;
};

struct IGattValueChangedEventArgs : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_CharacteristicValue(IBuffer** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Timestamp(INT64* value) = 0;
};

struct IBluetoothLEAdvertisementWatcher : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_MinSamplingInterval(INT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MaxSamplingInterval(INT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MinOutOfRangeTimeout(INT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_MaxOutOfRangeTimeout(INT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Status(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ScanningMode(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_ScanningMode(int value) = 0;  // 0 passive, 1 active
    virtual HRESULT STDMETHODCALLTYPE get_SignalStrengthFilter(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_SignalStrengthFilter(IInspectable* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AdvertisementFilter(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_AdvertisementFilter(IInspectable* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE Start() = 0;
    virtual HRESULT STDMETHODCALLTYPE Stop() = 0;
    virtual HRESULT STDMETHODCALLTYPE add_Received(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_Received(EventToken token) = 0;
    virtual HRESULT STDMETHODCALLTYPE add_Stopped(IUnknown* handler, EventToken* token) = 0;
    virtual HRESULT STDMETHODCALLTYPE remove_Stopped(EventToken token) = 0;
};

struct IBluetoothLEAdvertisementWatcher2 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_AllowExtendedAdvertisements(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_AllowExtendedAdvertisements(WinRtBool value) = 0;
};

struct IBluetoothLEAdvertisementReceivedEventArgs : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_RawSignalStrengthInDBm(INT16* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddress(UINT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_AdvertisementType(int* value) = 0;  // 4 scan response, 5 extended
    virtual HRESULT STDMETHODCALLTYPE get_Timestamp(INT64* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Advertisement(IBluetoothLEAdvertisement** value) = 0;
};

struct IBluetoothLEAdvertisementReceivedEventArgs2 : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_BluetoothAddressType(int* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_TransmitPowerLevelInDBm(IReference<INT16>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsAnonymous(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsConnectable(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsScannable(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsDirected(WinRtBool* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_IsScanResponse(WinRtBool* value) = 0;
};

struct IBluetoothLEAdvertisement : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Flags(IReference<UINT32>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Flags(IReference<UINT32>* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_LocalName(HSTRING* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_LocalName(HSTRING value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ServiceUuids(IVector<GUID>** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_ManufacturerData(IInspectable** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_DataSections(IVector<IBluetoothLEAdvertisementDataSection*>** value) = 0;
};

struct IBluetoothLEAdvertisementDataSection : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_DataType(BYTE* value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_DataType(BYTE value) = 0;
    virtual HRESULT STDMETHODCALLTYPE get_Data(IBuffer** value) = 0;
    virtual HRESULT STDMETHODCALLTYPE put_Data(IBuffer* value) = 0;
};

struct IBluetoothLEAdvertisementWatcherStoppedEventArgs : IInspectable {
    virtual HRESULT STDMETHODCALLTYPE get_Error(int* value) = 0;  // BluetoothError: 0 success, 1 radio not available...
};

// Interface IIDs, from windows-rs (the SDK's metadata).
constexpr GUID kIidUnknown = {0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
constexpr GUID kIidAgileObject = {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};
constexpr GUID kIidAsyncInfo = {0x00000036, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
constexpr GUID kIidBufferByteAccess = {0x905a0fe1, 0xbc53, 0x11df, {0x8c, 0x49, 0x00, 0x1e, 0x4f, 0xc6, 0x86, 0xda}};
constexpr GUID kIidBufferFactory = {0x71af914d, 0xc10f, 0x484b, {0xbc, 0x50, 0x14, 0xbc, 0x62, 0x3b, 0x3a, 0x27}};
constexpr GUID kIidLeDeviceStatics = {0xc8cf1a19, 0xf0b6, 0x4bf0, {0x86, 0x89, 0x41, 0x30, 0x3d, 0xe2, 0xd9, 0xf4}};
constexpr GUID kIidLeDevice = {0xb5ee2f7b, 0x4ad8, 0x4642, {0xac, 0x48, 0x80, 0xa0, 0xb5, 0x00, 0xe8, 0x87}};
constexpr GUID kIidLeDevice3 = {0xaee9e493, 0x44ac, 0x40dc, {0xaf, 0x33, 0xb2, 0xc1, 0x3c, 0x01, 0xca, 0x46}};
constexpr GUID kIidServicesResult = {0x171dd3ee, 0x016d, 0x419d, {0x83, 0x8a, 0x57, 0x6c, 0xf4, 0x75, 0xa3, 0xd8}};
constexpr GUID kIidService3 = {0xb293a950, 0x0c53, 0x437c, {0xa9, 0xb3, 0x5c, 0x32, 0x10, 0xc6, 0xe5, 0x69}};
constexpr GUID kIidCharacteristicsResult = {0x1194945c, 0xb257, 0x4f3e,
                                             {0x9d, 0xb7, 0xf6, 0x8b, 0xc9, 0xa9, 0xae, 0xf2}};
constexpr GUID kIidCharacteristic = {0x59cb50c1, 0x5934, 0x4f68, {0xa1, 0x98, 0xeb, 0x86, 0x4f, 0xa4, 0x4e, 0x6b}};
constexpr GUID kIidCharacteristic3 = {0x3f3c663e, 0x93d4, 0x406b, {0xb8, 0x17, 0xdb, 0x81, 0xf8, 0xed, 0x53, 0xb3}};
constexpr GUID kIidDescriptorsResult = {0x9bc091f3, 0x95e7, 0x4489, {0x8d, 0x25, 0xff, 0x81, 0x95, 0x5a, 0x57, 0xb9}};
constexpr GUID kIidDescriptor2 = {0x8f563d39, 0xd630, 0x406c, {0xba, 0x11, 0x10, 0xcd, 0xd1, 0x6b, 0x0e, 0x5e}};
constexpr GUID kIidReadResult = {0x63a66f08, 0x1aea, 0x4c4c, {0xa5, 0x0f, 0x97, 0xba, 0xe4, 0x74, 0xb3, 0x48}};
constexpr GUID kIidReadResult2 = {0xa10f50a0, 0xfb43, 0x48af, {0xba, 0xaa, 0x63, 0x8a, 0x5c, 0x63, 0x29, 0xfe}};
constexpr GUID kIidWriteResult = {0x4991ddb1, 0xcb2b, 0x44f7, {0x99, 0xfc, 0xd2, 0x9a, 0x28, 0x71, 0xdc, 0x9b}};
constexpr GUID kIidReadCccdResult = {0x63a66f09, 0x1aea, 0x4c4c, {0xa5, 0x0f, 0x97, 0xba, 0xe4, 0x74, 0xb3, 0x48}};
constexpr GUID kIidValueChangedArgs = {0xd21bdb54, 0x06e3, 0x4ed8, {0xa2, 0x63, 0xac, 0xfa, 0xc8, 0xba, 0x73, 0x13}};
constexpr GUID kIidWatcher = {0xa6ac336f, 0xf3d3, 0x4297, {0x8d, 0x6c, 0xc8, 0x1e, 0xa6, 0x62, 0x3f, 0x40}};
constexpr GUID kIidWatcher2 = {0x01bf26bc, 0xb164, 0x5805, {0x90, 0xa3, 0xe8, 0xa7, 0x99, 0x7f, 0xf2, 0x25}};
constexpr GUID kIidReceivedArgs = {0x27987ddf, 0xe596, 0x41be, {0x8d, 0x43, 0x9e, 0x67, 0x31, 0xd4, 0xa9, 0x13}};
constexpr GUID kIidReceivedArgs2 = {0x12d9c87b, 0x0399, 0x5f0e, {0xa3, 0x48, 0x53, 0xb0, 0x2b, 0x6b, 0x16, 0x2e}};
constexpr GUID kIidStoppedArgs = {0xdd40f84d, 0xe7b9, 0x43e3, {0x9c, 0x04, 0x06, 0x85, 0xd0, 0x85, 0xfd, 0x8c}};

// The parameterized IIDs: TypedEventHandler<Sender, Args> and
// AsyncOperationCompletedHandler<T>, from the types' signatures.
const char kGatt[] = "Windows.Devices.Bluetooth.GenericAttributeProfile.";
const char kAdv[] = "Windows.Devices.Bluetooth.Advertisement.";

bt::Guid event_handler_iid(const std::string& sender, const std::string& args) {
    return bt::parameterized_iid("pinterface({9de1c534-6ae1-11e0-84e1-18a905bcc53f};" + sender + ";" + args + ")");
}

bt::Guid completed_handler_iid(const std::string& result) {
    return bt::parameterized_iid("pinterface({fcdcf02c-e5d8-4478-915a-4d90b74b83a5};" + result + ")");
}

// A runtime class's signature, from its namespace prefix, name and default
// interface.
std::string rc(const char* prefix, const char* name, const GUID& default_interface) {
    return bt::class_signature(std::string(prefix) + name, from_guid(default_interface));
}

const char kDevices[] = "Windows.Devices.Bluetooth.";

struct Iids {
    bt::Guid received = event_handler_iid(rc(kAdv, "BluetoothLEAdvertisementWatcher", kIidWatcher),
                                          rc(kAdv, "BluetoothLEAdvertisementReceivedEventArgs", kIidReceivedArgs));
    bt::Guid stopped = event_handler_iid(rc(kAdv, "BluetoothLEAdvertisementWatcher", kIidWatcher),
                                         rc(kAdv, "BluetoothLEAdvertisementWatcherStoppedEventArgs", kIidStoppedArgs));
    bt::Guid value_changed = event_handler_iid(rc(kGatt, "GattCharacteristic", kIidCharacteristic),
                                               rc(kGatt, "GattValueChangedEventArgs", kIidValueChangedArgs));
    bt::Guid connection_changed =
        event_handler_iid(rc(kDevices, "BluetoothLEDevice", kIidLeDevice), "cinterface(IInspectable)");
    bt::Guid device_done = completed_handler_iid(rc(kDevices, "BluetoothLEDevice", kIidLeDevice));
    bt::Guid services_done = completed_handler_iid(rc(kGatt, "GattDeviceServicesResult", kIidServicesResult));
    bt::Guid characteristics_done =
        completed_handler_iid(rc(kGatt, "GattCharacteristicsResult", kIidCharacteristicsResult));
    bt::Guid descriptors_done = completed_handler_iid(rc(kGatt, "GattDescriptorsResult", kIidDescriptorsResult));
    bt::Guid read_done = completed_handler_iid(rc(kGatt, "GattReadResult", kIidReadResult));
    bt::Guid write_done = completed_handler_iid(rc(kGatt, "GattWriteResult", kIidWriteResult));
    bt::Guid cccd_read_done = completed_handler_iid(
        rc(kGatt, "GattReadClientCharacteristicConfigurationDescriptorResult", kIidReadCccdResult));
    bt::Guid status_done = completed_handler_iid(std::string("enum(") + kGatt + "GattCommunicationStatus;i4)");
};

const Iids& iids() {
    static const Iids i;
    return i;
}

// ---- combase, loaded at run time ---------------------------------------------------------------

using RoInitializeFn = HRESULT(WINAPI*)(int);
using RoActivateInstanceFn = HRESULT(WINAPI*)(HSTRING, IInspectable**);
using RoGetActivationFactoryFn = HRESULT(WINAPI*)(HSTRING, REFIID, void**);
using WindowsCreateStringFn = HRESULT(WINAPI*)(const wchar_t*, UINT32, HSTRING*);
using WindowsDeleteStringFn = HRESULT(WINAPI*)(HSTRING);
using WindowsGetStringRawBufferFn = const wchar_t*(WINAPI*)(HSTRING, UINT32*);

template <typename F>
F proc(HMODULE m, const char* name) {
    return reinterpret_cast<F>(reinterpret_cast<void*>(GetProcAddress(m, name)));
}

struct Combase {
    RoInitializeFn ro_initialize = nullptr;
    RoActivateInstanceFn ro_activate = nullptr;
    RoGetActivationFactoryFn ro_factory = nullptr;
    WindowsCreateStringFn create_string = nullptr;
    WindowsDeleteStringFn delete_string = nullptr;
    WindowsGetStringRawBufferFn raw_buffer = nullptr;
    bool ok = false;

    Combase() {
        HMODULE m = LoadLibraryW(L"combase.dll");
        if (!m) return;
        ro_initialize = proc<RoInitializeFn>(m, "RoInitialize");
        ro_activate = proc<RoActivateInstanceFn>(m, "RoActivateInstance");
        ro_factory = proc<RoGetActivationFactoryFn>(m, "RoGetActivationFactory");
        create_string = proc<WindowsCreateStringFn>(m, "WindowsCreateString");
        delete_string = proc<WindowsDeleteStringFn>(m, "WindowsDeleteString");
        raw_buffer = proc<WindowsGetStringRawBufferFn>(m, "WindowsGetStringRawBuffer");
        ok = ro_initialize && ro_activate && ro_factory && create_string && delete_string && raw_buffer;
    }

    // Called on every thread of ours that makes WinRT calls.
    void init_thread() const {
        if (ok) ro_initialize(1 /* RO_INIT_MULTITHREADED */);
    }

    std::string text(HSTRING s) const {
        UINT32 n = 0;
        const wchar_t* w = s ? raw_buffer(s, &n) : nullptr;
        return utf8(w, n);
    }

    template <typename T>
    T* factory(const wchar_t* cls, const GUID& iid) const {
        if (!ok) return nullptr;
        HSTRING name = nullptr;
        if (FAILED(create_string(cls, static_cast<UINT32>(wcslen(cls)), &name))) return nullptr;
        T* out = nullptr;
        if (FAILED(ro_factory(name, iid, reinterpret_cast<void**>(&out)))) out = nullptr;
        delete_string(name);
        return out;
    }

    template <typename T>
    T* activate(const wchar_t* cls, const GUID& iid) const {
        if (!ok) return nullptr;
        HSTRING name = nullptr;
        if (FAILED(create_string(cls, static_cast<UINT32>(wcslen(cls)), &name))) return nullptr;
        T* out = nullptr;
        IInspectable* inst = nullptr;
        if (SUCCEEDED(ro_activate(name, &inst)) && inst) {
            if (FAILED(inst->QueryInterface(iid, reinterpret_cast<void**>(&out)))) out = nullptr;
            inst->Release();
        }
        delete_string(name);
        return out;
    }
};

const Combase& combase() {
    static const Combase c;
    return c;
}

template <typename T>
void release(T*& p) {
    if (p) p->Release();
    p = nullptr;
}

template <typename T>
T* query(IUnknown* from, const GUID& iid) {
    T* out = nullptr;
    if (from && FAILED(from->QueryInterface(iid, reinterpret_cast<void**>(&out)))) out = nullptr;
    return out;
}

std::vector<uint8_t> buffer_bytes(IBuffer* b) {
    std::vector<uint8_t> out;
    if (!b) return out;
    UINT32 n = 0;
    b->get_Length(&n);
    IBufferByteAccess* access = query<IBufferByteAccess>(b, kIidBufferByteAccess);
    BYTE* p = nullptr;
    if (access && SUCCEEDED(access->Buffer(&p)) && p && n) out.assign(p, p + n);
    release(access);
    return out;
}

// A Windows.Storage.Streams.Buffer with `bytes` in it, for a write.
IBuffer* make_buffer(const std::vector<uint8_t>& bytes) {
    IBufferFactory* factory = combase().factory<IBufferFactory>(L"Windows.Storage.Streams.Buffer", kIidBufferFactory);
    if (!factory) return nullptr;
    IBuffer* b = nullptr;
    if (FAILED(factory->Create(static_cast<UINT32>(bytes.size()), &b))) b = nullptr;
    release(factory);
    if (!b) return nullptr;
    IBufferByteAccess* access = query<IBufferByteAccess>(b, kIidBufferByteAccess);
    BYTE* p = nullptr;
    if (!access || FAILED(access->Buffer(&p)) || !p) {
        release(access);
        release(b);
        return nullptr;
    }
    if (!bytes.empty()) std::memcpy(p, bytes.data(), bytes.size());
    b->put_Length(static_cast<UINT32>(bytes.size()));
    release(access);
    return b;
}

// A WinRT delegate: IUnknown plus one Invoke, called on the thread pool, so
// the object is agile. Invoke's two arguments are pointer-sized either way
// (sender and args for an event handler; the operation and its AsyncStatus
// for a completion handler).
class Delegate final : public IUnknown {
public:
    using Fn = std::function<void(IInspectable*, void*)>;
    Delegate(const bt::Guid& iid, Fn fn) : iid_(iid), fn_(std::move(fn)) {}
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
        if (!out) return E_POINTER;
        if (IsEqualGUID(riid, kIidUnknown) || IsEqualGUID(riid, kIidAgileObject) || IsEqualGUID(riid, as_guid(iid_))) {
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
    virtual HRESULT STDMETHODCALLTYPE Invoke(IInspectable* sender, void* args) {
        fn_(sender, args);
        return S_OK;
    }

private:
    ~Delegate() = default;  // not virtual: no slot may follow Invoke's in the vtable
    bt::Guid iid_;
    Fn fn_;
    LONG refs_ = 1;
};

// Waits for an async operation: through its completion handler when the
// runtime accepts ours, else by polling its status. True when it completed
// (then GetResults may be called); on a timeout the operation is cancelled.
struct Done {
    std::mutex m;
    std::condition_variable cv;
    bool done = false;
};

bool await(IInspectable* op, const bt::Guid& handler_iid, int timeout_ms) {
    if (!op) return false;
    IAsyncInfoAbi* info = query<IAsyncInfoAbi>(op, kIidAsyncInfo);
    if (!info) return false;
    auto done = std::make_shared<Done>();
    Delegate* handler = new Delegate(handler_iid, [done](IInspectable*, void*) {
        std::lock_guard<std::mutex> l(done->m);
        done->done = true;
        done->cv.notify_all();
    });
    const bool subscribed = SUCCEEDED(static_cast<IAsyncOp<void*>*>(static_cast<void*>(op))->put_Completed(handler));
    handler->Release();
    int status = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    if (subscribed) {
        std::unique_lock<std::mutex> l(done->m);
        done->cv.wait_until(l, deadline, [&] { return done->done; });
    }
    while (SUCCEEDED(info->get_Status(&status)) && status == 0 && std::chrono::steady_clock::now() < deadline) Sleep(5);
    if (status == 0) info->Cancel();
    info->Close();
    info->Release();
    return status == 1;
}

template <typename T>
T* await_result(IAsyncOp<T*>* op, const bt::Guid& handler_iid, int timeout_ms) {
    T* result = nullptr;
    if (op) {
        if (await(op, handler_iid, timeout_ms) && FAILED(op->GetResults(&result))) result = nullptr;
        op->Release();
    }
    return result;
}

// An IAsyncOperation<enum>'s value, or -1.
int await_int(IAsyncOp<int>* op, const bt::Guid& handler_iid, int timeout_ms) {
    int value = -1;
    if (op) {
        if (await(op, handler_iid, timeout_ms) && FAILED(op->GetResults(&value))) value = -1;
        op->Release();
    }
    return value;
}

// GattCommunicationStatus and the ATT error that came with it, as Android's
// GATT status: the ATT error code itself (BluetoothGatt.GATT_* are those
// codes), 133 (GATT_ERROR, what a lost link reports) otherwise.
constexpr int kGattSuccess = 0, kGattError = 133;

int gatt_status(int communication_status, IReference<BYTE>* protocol_error) {
    if (communication_status == 0) return kGattSuccess;
    BYTE att = 0;
    if (communication_status == 2 && protocol_error && SUCCEEDED(protocol_error->get_Value(&att)) && att) return att;
    return kGattError;
}

constexpr int kTimeoutConnect = 30000, kTimeoutOp = 15000;
constexpr int kCacheUncached = 1;

// ---- the LE scan --------------------------------------------------------------------------

// What one advertisement becomes for BluetoothLeScanner: a ScanResult, or an
// error that ends the scan (address 0, error set).
struct ScanEvent {
    uint64_t address = 0;
    int rssi = 0;
    int tx_power = 127;  // ScanResult.TX_POWER_NOT_PRESENT
    bool connectable = false;
    bool legacy = true;
    std::string name;
    std::vector<uint8_t> record;
    int error = 0;  // BluetoothError when the watcher stopped by itself
};

std::mutex g_scan_lock;
std::condition_variable g_scan_wake;
std::deque<ScanEvent> g_scan_events;
enum class ScanCommand { kNone, kStart, kStop, kQuit };
ScanCommand g_scan_command = ScanCommand::kNone;
int g_scan_started = -1;  // the last Start's answer: 0 ok, else a ScanCallback.SCAN_FAILED_* code
bool g_scan_running = false;
std::thread g_scan_thread;
// The scan response's bytes go after the advertisement's, as Android's
// ScanRecord holds them; Windows delivers the two separately.
std::map<uint64_t, std::vector<uint8_t>> g_last_advertisement;
std::map<uint64_t, std::string> g_last_name;

void push_scan_event(ScanEvent e) {
    std::lock_guard<std::mutex> l(g_scan_lock);
    if (!g_scan_running && e.error == 0) return;
    if (g_scan_events.size() > 4096) g_scan_events.pop_front();  // the app is not draining; keep the newest
    g_scan_events.push_back(std::move(e));
    g_scan_wake.notify_all();
}

void on_advertisement(IInspectable* args_obj) {
    auto* args = query<IBluetoothLEAdvertisementReceivedEventArgs>(args_obj, kIidReceivedArgs);
    if (!args) return;
    ScanEvent e;
    INT16 rssi = 0;
    int type = 0;
    args->get_RawSignalStrengthInDBm(&rssi);
    args->get_BluetoothAddress(&e.address);
    args->get_AdvertisementType(&type);
    e.rssi = rssi;
    e.legacy = type != 5;
    e.connectable = type == 0 || type == 1;
    bool scan_response = type == 4;
    auto* args2 = query<IBluetoothLEAdvertisementReceivedEventArgs2>(args, kIidReceivedArgs2);
    if (args2) {
        WinRtBool b = 0;
        if (SUCCEEDED(args2->get_IsConnectable(&b))) e.connectable = b != 0;
        if (SUCCEEDED(args2->get_IsScanResponse(&b))) scan_response = b != 0;
        IReference<INT16>* tx = nullptr;
        INT16 dbm = 0;
        if (SUCCEEDED(args2->get_TransmitPowerLevelInDBm(&tx)) && tx && SUCCEEDED(tx->get_Value(&dbm))) {
            e.tx_power = dbm;
        }
        release(tx);
        release(args2);
    }
    IBluetoothLEAdvertisement* adv = nullptr;
    std::vector<bt::AdSection> sections;
    if (SUCCEEDED(args->get_Advertisement(&adv)) && adv) {
        HSTRING name = nullptr;
        if (SUCCEEDED(adv->get_LocalName(&name)) && name) {
            e.name = combase().text(name);
            combase().delete_string(name);
        }
        IVector<IBluetoothLEAdvertisementDataSection*>* list = nullptr;
        if (SUCCEEDED(adv->get_DataSections(&list)) && list) {
            UINT32 n = 0;
            list->get_Size(&n);
            for (UINT32 i = 0; i < n; ++i) {
                IBluetoothLEAdvertisementDataSection* s = nullptr;
                if (FAILED(list->GetAt(i, &s)) || !s) continue;
                bt::AdSection section{};
                IBuffer* data = nullptr;
                s->get_DataType(&section.type);
                if (SUCCEEDED(s->get_Data(&data))) section.data = buffer_bytes(data);
                release(data);
                sections.push_back(std::move(section));
                s->Release();
            }
            list->Release();
        }
        adv->Release();
    }
    args->Release();
    const std::vector<uint8_t> bytes = bt::advertisement_bytes(sections);
    {
        std::lock_guard<std::mutex> l(g_scan_lock);
        if (g_last_advertisement.size() > 2048) {
            g_last_advertisement.clear();
            g_last_name.clear();
        }
        if (scan_response) {
            e.record = g_last_advertisement[e.address];
            e.record.insert(e.record.end(), bytes.begin(), bytes.end());
        } else {
            g_last_advertisement[e.address] = bytes;
            e.record = bytes;
        }
        // A name seen in an earlier packet (the scan response, usually)
        // stays with the device, as the stack's name cache does.
        if (!e.name.empty()) g_last_name[e.address] = e.name;
        else e.name = g_last_name[e.address];
    }
    push_scan_event(std::move(e));
}

void scan_worker() {
    const Combase& rt = combase();
    rt.init_thread();
    IBluetoothLEAdvertisementWatcher* watcher = nullptr;
    Delegate* on_received = nullptr;
    Delegate* on_stopped = nullptr;
    EventToken received_token{}, stopped_token{};
    for (;;) {
        ScanCommand cmd;
        {
            std::unique_lock<std::mutex> l(g_scan_lock);
            g_scan_wake.wait(l, [] { return g_scan_command != ScanCommand::kNone; });
            cmd = g_scan_command;
            g_scan_command = ScanCommand::kNone;
        }
        if (cmd == ScanCommand::kQuit) break;
        if (cmd == ScanCommand::kStop) {
            if (watcher) watcher->Stop();
            std::lock_guard<std::mutex> l(g_scan_lock);
            g_scan_running = false;
            g_scan_wake.notify_all();
            continue;
        }
        int answer = 0;
        if (!watcher) {
            watcher = rt.activate<IBluetoothLEAdvertisementWatcher>(
                L"Windows.Devices.Bluetooth.Advertisement.BluetoothLEAdvertisementWatcher", kIidWatcher);
            if (watcher) {
                watcher->put_ScanningMode(1);  // active: scan responses carry the names
                IBluetoothLEAdvertisementWatcher2* w2 = query<IBluetoothLEAdvertisementWatcher2>(watcher, kIidWatcher2);
                if (w2) {
                    w2->put_AllowExtendedAdvertisements(1);
                    w2->Release();
                }
                on_received = new Delegate(iids().received, [](IInspectable*, void* args) {
                    on_advertisement(static_cast<IInspectable*>(args));
                });
                on_stopped = new Delegate(iids().stopped, [](IInspectable*, void* args_obj) {
                    auto* args = query<IBluetoothLEAdvertisementWatcherStoppedEventArgs>(
                        static_cast<IInspectable*>(args_obj), kIidStoppedArgs);
                    int error = 0;
                    if (args) {
                        args->get_Error(&error);
                        args->Release();
                    }
                    bool running;
                    {
                        std::lock_guard<std::mutex> l(g_scan_lock);
                        running = g_scan_running;
                        if (error) g_scan_running = false;
                    }
                    // Stopped by itself (the radio went off): the scan is
                    // over, and the app is told so.
                    if (error && running) {
                        ScanEvent e;
                        e.error = error;
                        push_scan_event(std::move(e));
                    }
                });
                if (FAILED(watcher->add_Received(on_received, &received_token)) ||
                    FAILED(watcher->add_Stopped(on_stopped, &stopped_token))) {
                    release(on_received);
                    release(on_stopped);
                    release(watcher);
                }
            }
            if (!watcher) {
                std::fprintf(stderr, "[win.apk] bluetooth: the LE advertisement watcher is not available\n");
                answer = 3;  // SCAN_FAILED_INTERNAL_ERROR
            }
        }
        if (watcher) {
            {
                std::lock_guard<std::mutex> l(g_scan_lock);
                g_scan_running = true;
            }
            const HRESULT hr = watcher->Start();
            int status = 0;
            watcher->get_Status(&status);
            if (FAILED(hr) || status == 4 /* aborted */) {
                answer = 3;
                std::lock_guard<std::mutex> l(g_scan_lock);
                g_scan_running = false;
            }
        }
        std::lock_guard<std::mutex> l(g_scan_lock);
        g_scan_started = answer;
        g_scan_wake.notify_all();
    }
    if (watcher) {
        watcher->Stop();
        watcher->remove_Received(received_token);
        watcher->remove_Stopped(stopped_token);
        release(on_received);
        release(on_stopped);
        release(watcher);
    }
}

jboolean JNICALL Bt_leAvailable(JNIEnv*, jclass) {
    // The API exists on every Windows 10; the radio is HostRadios' question.
    // Asked on a thread of ours: a factory call needs the apartment set up,
    // and a Java thread's is left as it is.
    static const bool available = [] {
        bool ok = false;
        std::thread([&ok] {
            combase().init_thread();
            IBluetoothLEDeviceStatics* statics = combase().factory<IBluetoothLEDeviceStatics>(
                L"Windows.Devices.Bluetooth.BluetoothLEDevice", kIidLeDeviceStatics);
            ok = statics != nullptr;
            release(statics);
        }).join();
        return ok;
    }();
    return available ? JNI_TRUE : JNI_FALSE;
}

// 0 when the scan runs, else a ScanCallback.SCAN_FAILED_* code.
jint JNICALL Bt_scanStart(JNIEnv*, jclass) {
    std::unique_lock<std::mutex> l(g_scan_lock);
    if (!g_scan_thread.joinable()) g_scan_thread = std::thread(scan_worker);
    g_scan_events.clear();
    g_scan_started = -1;
    g_scan_command = ScanCommand::kStart;
    g_scan_wake.notify_all();
    g_scan_wake.wait_for(l, std::chrono::seconds(10), [] { return g_scan_started != -1; });
    return g_scan_started == -1 ? 3 : g_scan_started;
}

void JNICALL Bt_scanStop(JNIEnv*, jclass) {
    std::unique_lock<std::mutex> l(g_scan_lock);
    if (!g_scan_thread.joinable()) return;
    g_scan_command = ScanCommand::kStop;
    g_scan_wake.notify_all();
    g_scan_wake.wait_for(l, std::chrono::seconds(5), [] { return !g_scan_running; });
    g_scan_events.clear();
}

// The next advertisement: {String address, int[] {rssi, txPower, connectable,
// legacy}, byte[] record, String name}; an error that ended the scan as
// {null, int[] {error}, null, null}; null when `timeout_ms` passed without one.
jobjectArray JNICALL Bt_scanNext(JNIEnv* env, jclass, jint timeout_ms) {
    ScanEvent e;
    {
        std::unique_lock<std::mutex> l(g_scan_lock);
        if (!g_scan_wake.wait_for(l, std::chrono::milliseconds(timeout_ms), [] { return !g_scan_events.empty(); })) {
            return nullptr;
        }
        e = std::move(g_scan_events.front());
        g_scan_events.pop_front();
    }
    jclass object_class = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(4, object_class, nullptr);
    env->DeleteLocalRef(object_class);
    if (!out) return nullptr;
    if (e.error) {
        jintArray ints = env->NewIntArray(1);
        const jint v = e.error;
        env->SetIntArrayRegion(ints, 0, 1, &v);
        env->SetObjectArrayElement(out, 1, ints);
        env->DeleteLocalRef(ints);
        return out;
    }
    jstring address = env->NewStringUTF(bt::address_text(e.address).c_str());
    env->SetObjectArrayElement(out, 0, address);
    env->DeleteLocalRef(address);
    jintArray ints = env->NewIntArray(4);
    const jint v[4] = {e.rssi, e.tx_power, e.connectable ? 1 : 0, e.legacy ? 1 : 0};
    env->SetIntArrayRegion(ints, 0, 4, v);
    env->SetObjectArrayElement(out, 1, ints);
    env->DeleteLocalRef(ints);
    jbyteArray record = to_java_bytes(env, e.record);
    env->SetObjectArrayElement(out, 2, record);
    env->DeleteLocalRef(record);
    if (!e.name.empty()) {
        jstring name = env->NewStringUTF(e.name.c_str());
        env->SetObjectArrayElement(out, 3, name);
        env->DeleteLocalRef(name);
    }
    return out;
}

// ---- GATT connections ---------------------------------------------------------------------

// BluetoothGatt's events, as HostBluetooth.GATT_* numbers them.
enum GattEvent {
    kEvConnection = 1,     // status, newState (0 disconnected, 2 connected)
    kEvServices = 2,       // status; tree
    kEvCharRead = 3,       // status, characteristic; value
    kEvCharWrite = 4,      // status, characteristic
    kEvCharChanged = 5,    // characteristic; value
    kEvDescRead = 6,       // status, characteristic, descriptor; value
    kEvDescWrite = 7,      // status, characteristic, descriptor
    kEvMtu = 8,            // status, mtu
    kEvClosed = 9,         // the last event: the connection is gone
};

struct Event {
    int kind = 0, status = 0, a = 0, b = 0;
    std::vector<uint8_t> value;
    std::vector<std::string> tree;
};

enum class Op { kConnect, kDiscover, kRead, kWrite, kNotify, kMtu, kDisconnect, kClose };

struct Command {
    Op op;
    int characteristic = 0;  // attribute handles
    int descriptor = 0;
    int arg = 0;             // write: with response; notify: CCCD value (0 off, 1 notify, 2 indicate); mtu: wanted
    std::vector<uint8_t> value;
};

struct DescriptorEntry {
    IGattDescriptor* descriptor = nullptr;
    bt::Guid uuid;
    int handle = 0;
};

struct CharacteristicEntry {
    IGattCharacteristic* characteristic = nullptr;
    bt::Guid uuid;
    int handle = 0;
    UINT32 properties = 0;
    std::vector<DescriptorEntry> descriptors;
    Delegate* on_changed = nullptr;
    EventToken changed_token{};
};

struct ServiceEntry {
    IGattDeviceService* service = nullptr;
    bt::Guid uuid;
    int handle = 0;
    std::vector<CharacteristicEntry> characteristics;
};

struct Connection {
    uint64_t address = 0;
    bool auto_connect = false;
    // For the event handlers, which run on the thread pool and may still be
    // in flight when the connection is let go.
    std::weak_ptr<Connection> self;
    std::mutex lock;
    std::condition_variable wake;
    std::deque<Command> commands;
    std::deque<Event> events;
    bool finished = false;  // the worker is done (after kClose)
    std::thread worker;
    // The worker's own, touched only on its thread (and in the value-changed
    // handler, which only reads the handle it was given).
    IBluetoothLEDevice* device = nullptr;
    IBluetoothLEDevice3* device3 = nullptr;
    Delegate* on_status = nullptr;
    EventToken status_token{};
    bool connected = false;
    std::vector<ServiceEntry> services;
    IVectorView<IGattDeviceService*>* service_list = nullptr;  // from the connecting query, for discoverServices
};

std::mutex g_gatt_lock;
std::map<jlong, std::shared_ptr<Connection>> g_connections;
jlong g_next_connection = 1;

std::shared_ptr<Connection> connection(jlong h) {
    std::lock_guard<std::mutex> l(g_gatt_lock);
    auto it = g_connections.find(h);
    return it == g_connections.end() ? nullptr : it->second;
}

void push_event(Connection& c, Event e) {
    std::lock_guard<std::mutex> l(c.lock);
    c.events.push_back(std::move(e));
    c.wake.notify_all();
}

void post(Connection& c, Command cmd) {
    std::lock_guard<std::mutex> l(c.lock);
    c.commands.push_back(std::move(cmd));
    c.wake.notify_all();
}

CharacteristicEntry* find_characteristic(Connection& c, int handle) {
    for (ServiceEntry& s : c.services) {
        for (CharacteristicEntry& ch : s.characteristics) {
            if (ch.handle == handle) return &ch;
        }
    }
    return nullptr;
}

DescriptorEntry* find_descriptor(CharacteristicEntry& ch, int handle) {
    for (DescriptorEntry& d : ch.descriptors) {
        if (d.handle == handle) return &d;
    }
    return nullptr;
}

// The discovered tree's objects (not the service list the connection holds).
void release_entries(Connection& c) {
    for (ServiceEntry& s : c.services) {
        for (CharacteristicEntry& ch : s.characteristics) {
            if (ch.on_changed) {
                ch.characteristic->remove_ValueChanged(ch.changed_token);
                release(ch.on_changed);
            }
            for (DescriptorEntry& d : ch.descriptors) release(d.descriptor);
            release(ch.characteristic);
        }
        release(s.service);
    }
    c.services.clear();
}

void release_services(Connection& c) {
    release_entries(c);
    release(c.service_list);
}

// Drops the link: Windows keeps it only while the objects are held.
void release_device(Connection& c) {
    release_services(c);
    if (c.device && c.on_status) c.device->remove_ConnectionStatusChanged(c.status_token);
    release(c.on_status);
    release(c.device3);
    release(c.device);
    c.connected = false;
}

// One connection attempt: the device object, then the services (uncached, so
// the stack actually connects). True when the link is up.
bool connect_once(Connection& c) {
    IBluetoothLEDeviceStatics* statics = combase().factory<IBluetoothLEDeviceStatics>(
        L"Windows.Devices.Bluetooth.BluetoothLEDevice", kIidLeDeviceStatics);
    if (!statics) return false;
    IAsyncOp<IBluetoothLEDevice*>* op = nullptr;
    if (FAILED(statics->FromBluetoothAddressAsync(c.address, &op))) op = nullptr;
    release(statics);
    c.device = await_result<IBluetoothLEDevice>(op, iids().device_done, kTimeoutOp);
    if (!c.device) return false;  // never seen by Windows: not in range (it is found by a scan first)
    c.device3 = query<IBluetoothLEDevice3>(c.device, kIidLeDevice3);
    if (!c.device3) {
        release_device(c);
        return false;
    }
    std::weak_ptr<Connection> self = c.self;
    c.on_status = new Delegate(iids().connection_changed, [self](IInspectable* sender, void*) {
        IBluetoothLEDevice* dev = query<IBluetoothLEDevice>(sender, kIidLeDevice);
        int status = 1;
        if (dev) {
            dev->get_ConnectionStatus(&status);
            dev->Release();
        }
        // A link lost: the worker tears the objects down and tells the app
        // (it serializes with whatever operation was in flight).
        std::shared_ptr<Connection> conn = self.lock();
        if (status == 0 && conn) post(*conn, Command{Op::kDisconnect, 0, 0, 1, {}});
    });
    if (FAILED(c.device->add_ConnectionStatusChanged(c.on_status, &c.status_token))) release(c.on_status);
    IAsyncOp<IGattDeviceServicesResult*>* services_op = nullptr;
    if (FAILED(c.device3->GetGattServicesWithCacheModeAsync(kCacheUncached, &services_op))) services_op = nullptr;
    IGattDeviceServicesResult* result =
        await_result<IGattDeviceServicesResult>(services_op, iids().services_done, kTimeoutConnect);
    int status = -1;
    if (result) {
        result->get_Status(&status);
        if (status == 0) result->get_Services(&c.service_list);
        result->Release();
    }
    if (status != 0) {
        release_device(c);
        return false;
    }
    c.connected = true;
    return true;
}

void do_connect(Connection& c) {
    for (;;) {
        if (connect_once(c)) {
            push_event(c, Event{kEvConnection, kGattSuccess, 2, 0, {}, {}});
            return;
        }
        if (!c.auto_connect) break;
        // autoConnect: keep trying until the device appears or the app gives
        // up (a disconnect or close arrives).
        std::unique_lock<std::mutex> l(c.lock);
        if (c.wake.wait_for(l, std::chrono::seconds(3), [&] { return !c.commands.empty(); })) break;
    }
    push_event(c, Event{kEvConnection, kGattError, 0, 0, {}, {}});
}

// The services, their characteristics and descriptors, as the tree
// HostBluetooth parses: "S" uuid handle, "C" uuid handle properties, "D" uuid
// handle, in order.
void do_discover(Connection& c) {
    if (!c.connected || !c.service_list) {
        push_event(c, Event{kEvServices, kGattError, 0, 0, {}, {}});
        return;
    }
    release_entries(c);
    std::vector<std::string> tree;
    UINT32 n = 0;
    c.service_list->get_Size(&n);
    for (UINT32 i = 0; i < n; ++i) {
        ServiceEntry s;
        if (FAILED(c.service_list->GetAt(i, &s.service)) || !s.service) continue;
        GUID uuid{};
        UINT16 handle = 0;
        s.service->get_Uuid(&uuid);
        s.service->get_AttributeHandle(&handle);
        s.uuid = from_guid(uuid);
        s.handle = handle;
        tree.push_back("S");
        tree.push_back(bt::guid_text(s.uuid));
        tree.push_back(std::to_string(s.handle));
        IGattDeviceService3* s3 = query<IGattDeviceService3>(s.service, kIidService3);
        IVectorView<IGattCharacteristic*>* chars = nullptr;
        if (s3) {
            IAsyncOp<IGattCharacteristicsResult*>* op = nullptr;
            if (FAILED(s3->GetCharacteristicsAsync(&op))) op = nullptr;
            IGattCharacteristicsResult* r =
                await_result<IGattCharacteristicsResult>(op, iids().characteristics_done, kTimeoutOp);
            int status = -1;
            if (r) {
                r->get_Status(&status);
                if (status == 0) r->get_Characteristics(&chars);
                r->Release();
            }
            s3->Release();
        }
        UINT32 nc = 0;
        if (chars) chars->get_Size(&nc);
        for (UINT32 k = 0; k < nc; ++k) {
            CharacteristicEntry ch;
            if (FAILED(chars->GetAt(k, &ch.characteristic)) || !ch.characteristic) continue;
            GUID cuuid{};
            UINT16 chandle = 0;
            ch.characteristic->get_Uuid(&cuuid);
            ch.characteristic->get_AttributeHandle(&chandle);
            ch.characteristic->get_CharacteristicProperties(&ch.properties);
            ch.uuid = from_guid(cuuid);
            ch.handle = chandle;
            tree.push_back("C");
            tree.push_back(bt::guid_text(ch.uuid));
            tree.push_back(std::to_string(ch.handle));
            // Windows' property bits are the Bluetooth ones, which Android's
            // PROPERTY_* mirror (broadcast 1, read 2, ... extended props 128).
            tree.push_back(std::to_string(ch.properties & 0xFF));
            IGattCharacteristic3* c3 = query<IGattCharacteristic3>(ch.characteristic, kIidCharacteristic3);
            IVectorView<IGattDescriptor*>* descs = nullptr;
            if (c3) {
                IAsyncOp<IGattDescriptorsResult*>* op = nullptr;
                if (FAILED(c3->GetDescriptorsAsync(&op))) op = nullptr;
                IGattDescriptorsResult* r =
                    await_result<IGattDescriptorsResult>(op, iids().descriptors_done, kTimeoutOp);
                int status = -1;
                if (r) {
                    r->get_Status(&status);
                    if (status == 0) r->get_Descriptors(&descs);
                    r->Release();
                }
                c3->Release();
            }
            UINT32 nd = 0;
            if (descs) descs->get_Size(&nd);
            bool has_cccd = false;
            for (UINT32 j = 0; j < nd; ++j) {
                DescriptorEntry d;
                if (FAILED(descs->GetAt(j, &d.descriptor)) || !d.descriptor) continue;
                GUID duuid{};
                UINT16 dhandle = 0;
                d.descriptor->get_Uuid(&duuid);
                d.descriptor->get_AttributeHandle(&dhandle);
                d.uuid = from_guid(duuid);
                d.handle = dhandle;
                if (d.uuid == bt::bluetooth_uuid(0x2902)) has_cccd = true;
                tree.push_back("D");
                tree.push_back(bt::guid_text(d.uuid));
                tree.push_back(std::to_string(d.handle));
                ch.descriptors.push_back(d);
            }
            release(descs);
            // Windows lists no CCCD for a notifying characteristic (it owns
            // that descriptor); Android apps write it, so one is shown, with
            // the characteristic's own handle as its id (no attribute has it).
            if (!has_cccd && (ch.properties & 0x30)) {
                DescriptorEntry d;
                d.uuid = bt::bluetooth_uuid(0x2902);
                d.handle = ch.handle;
                tree.push_back("D");
                tree.push_back(bt::guid_text(d.uuid));
                tree.push_back(std::to_string(d.handle));
                ch.descriptors.push_back(d);
            }
            s.characteristics.push_back(std::move(ch));
        }
        release(chars);
        c.services.push_back(std::move(s));
    }
    Event e{kEvServices, kGattSuccess, 0, 0, {}, {}};
    e.tree = std::move(tree);
    push_event(c, std::move(e));
}

void do_read(Connection& c, const Command& cmd) {
    CharacteristicEntry* ch = c.connected ? find_characteristic(c, cmd.characteristic) : nullptr;
    const int kind = cmd.descriptor ? kEvDescRead : kEvCharRead;
    if (!ch) {
        push_event(c, Event{kind, kGattError, cmd.characteristic, cmd.descriptor, {}, {}});
        return;
    }
    int status = kGattError;
    std::vector<uint8_t> value;
    if (cmd.descriptor) {
        DescriptorEntry* d = find_descriptor(*ch, cmd.descriptor);
        if (d && !d->descriptor) {
            // The CCCD Windows keeps itself.
            IAsyncOp<IGattReadCccdResult*>* op = nullptr;
            if (FAILED(ch->characteristic->ReadClientCharacteristicConfigurationDescriptorAsync(&op))) op = nullptr;
            IGattReadCccdResult* r = await_result<IGattReadCccdResult>(op, iids().cccd_read_done, kTimeoutOp);
            if (r) {
                int cs = -1, cccd = 0;
                r->get_Status(&cs);
                r->get_ClientCharacteristicConfigurationDescriptor(&cccd);
                status = gatt_status(cs, nullptr);
                value = {static_cast<uint8_t>(cccd), 0};
                r->Release();
            }
        } else if (d) {
            IAsyncOp<IGattReadResult*>* op = nullptr;
            if (FAILED(d->descriptor->ReadValueWithCacheModeAsync(kCacheUncached, &op))) op = nullptr;
            IGattReadResult* r = await_result<IGattReadResult>(op, iids().read_done, kTimeoutOp);
            if (r) {
                int cs = -1;
                r->get_Status(&cs);
                IGattReadResult2* r2 = query<IGattReadResult2>(r, kIidReadResult2);
                IReference<BYTE>* err = nullptr;
                if (r2) r2->get_ProtocolError(&err);
                status = gatt_status(cs, err);
                release(err);
                release(r2);
                IBuffer* b = nullptr;
                if (status == kGattSuccess && SUCCEEDED(r->get_Value(&b))) value = buffer_bytes(b);
                release(b);
                r->Release();
            }
        }
    } else {
        IAsyncOp<IGattReadResult*>* op = nullptr;
        if (FAILED(ch->characteristic->ReadValueWithCacheModeAsync(kCacheUncached, &op))) op = nullptr;
        IGattReadResult* r = await_result<IGattReadResult>(op, iids().read_done, kTimeoutOp);
        if (r) {
            int cs = -1;
            r->get_Status(&cs);
            IGattReadResult2* r2 = query<IGattReadResult2>(r, kIidReadResult2);
            IReference<BYTE>* err = nullptr;
            if (r2) r2->get_ProtocolError(&err);
            status = gatt_status(cs, err);
            release(err);
            release(r2);
            IBuffer* b = nullptr;
            if (status == kGattSuccess && SUCCEEDED(r->get_Value(&b))) value = buffer_bytes(b);
            release(b);
            r->Release();
        }
    }
    Event e{kind, status, cmd.characteristic, cmd.descriptor, {}, {}};
    e.value = std::move(value);
    push_event(c, std::move(e));
}

int write_status(IGattWriteResult* r) {
    if (!r) return kGattError;
    int cs = -1;
    IReference<BYTE>* err = nullptr;
    r->get_Status(&cs);
    r->get_ProtocolError(&err);
    const int status = gatt_status(cs, err);
    release(err);
    r->Release();
    return status;
}

void do_write(Connection& c, const Command& cmd) {
    CharacteristicEntry* ch = c.connected ? find_characteristic(c, cmd.characteristic) : nullptr;
    const int kind = cmd.descriptor ? kEvDescWrite : kEvCharWrite;
    if (!ch) {
        push_event(c, Event{kind, kGattError, cmd.characteristic, cmd.descriptor, {}, {}});
        return;
    }
    int status = kGattError;
    if (cmd.descriptor) {
        DescriptorEntry* d = find_descriptor(*ch, cmd.descriptor);
        if (d && d->uuid == bt::bluetooth_uuid(0x2902)) {
            // The client characteristic configuration goes through the
            // dedicated call: it is the only way Windows subscribes.
            const int cccd = cmd.value.empty() ? 0 : (cmd.value[0] & 0x03);
            IGattCharacteristic3* c3 = query<IGattCharacteristic3>(ch->characteristic, kIidCharacteristic3);
            if (c3) {
                IAsyncOp<IGattWriteResult*>* op = nullptr;
                if (FAILED(c3->WriteClientCharacteristicConfigurationDescriptorWithResultAsync(cccd, &op))) {
                    op = nullptr;
                }
                status = write_status(await_result<IGattWriteResult>(op, iids().write_done, kTimeoutOp));
                c3->Release();
            } else {
                IAsyncOp<int>* op = nullptr;
                if (FAILED(ch->characteristic->WriteClientCharacteristicConfigurationDescriptorAsync(cccd, &op))) {
                    op = nullptr;
                }
                status = gatt_status(await_int(op, iids().status_done, kTimeoutOp), nullptr);
            }
        } else if (d && d->descriptor) {
            IBuffer* b = make_buffer(cmd.value);
            IGattDescriptor2* d2 = query<IGattDescriptor2>(d->descriptor, kIidDescriptor2);
            if (b && d2) {
                IAsyncOp<IGattWriteResult*>* op = nullptr;
                if (FAILED(d2->WriteValueWithResultAsync(b, &op))) op = nullptr;
                status = write_status(await_result<IGattWriteResult>(op, iids().write_done, kTimeoutOp));
            } else if (b) {
                IAsyncOp<int>* op = nullptr;
                if (FAILED(d->descriptor->WriteValueAsync(b, &op))) op = nullptr;
                status = gatt_status(await_int(op, iids().status_done, kTimeoutOp), nullptr);
            }
            release(d2);
            release(b);
        }
    } else {
        IBuffer* b = make_buffer(cmd.value);
        IGattCharacteristic3* c3 = query<IGattCharacteristic3>(ch->characteristic, kIidCharacteristic3);
        const int option = cmd.arg ? 0 /* with response */ : 1 /* without */;
        if (b && c3) {
            IAsyncOp<IGattWriteResult*>* op = nullptr;
            if (FAILED(c3->WriteValueWithResultAndOptionAsync(b, option, &op))) op = nullptr;
            status = write_status(await_result<IGattWriteResult>(op, iids().write_done, kTimeoutOp));
        } else if (b) {
            IAsyncOp<int>* op = nullptr;
            if (FAILED(ch->characteristic->WriteValueWithOptionAsync(b, option, &op))) op = nullptr;
            status = gatt_status(await_int(op, iids().status_done, kTimeoutOp), nullptr);
        }
        release(c3);
        release(b);
    }
    push_event(c, Event{kind, status, cmd.characteristic, cmd.descriptor, {}, {}});
}

// setCharacteristicNotification: the value-changed handler comes and goes;
// the subscription itself is the CCCD write (do_write).
void do_notify(Connection& c, const Command& cmd) {
    CharacteristicEntry* ch = c.connected ? find_characteristic(c, cmd.characteristic) : nullptr;
    if (!ch) return;
    if (ch->on_changed) {
        ch->characteristic->remove_ValueChanged(ch->changed_token);
        release(ch->on_changed);
    }
    if (!cmd.arg) return;
    std::weak_ptr<Connection> self = c.self;
    const int handle = ch->handle;
    ch->on_changed = new Delegate(iids().value_changed, [self, handle](IInspectable*, void* args_obj) {
        auto* args = query<IGattValueChangedEventArgs>(static_cast<IInspectable*>(args_obj), kIidValueChangedArgs);
        if (!args) return;
        IBuffer* b = nullptr;
        Event e{kEvCharChanged, kGattSuccess, handle, 0, {}, {}};
        if (SUCCEEDED(args->get_CharacteristicValue(&b))) e.value = buffer_bytes(b);
        release(b);
        args->Release();
        std::shared_ptr<Connection> conn = self.lock();
        if (conn) push_event(*conn, std::move(e));
    });
    if (FAILED(ch->characteristic->add_ValueChanged(ch->on_changed, &ch->changed_token))) release(ch->on_changed);
}

// Windows negotiates the MTU itself; requestMtu answers with what it got.
void do_mtu(Connection& c) {
    int mtu = 23;
    if (c.connected && !c.services.empty()) {
        IGattDeviceService3* s3 = query<IGattDeviceService3>(c.services[0].service, kIidService3);
        IGattSession* session = nullptr;
        UINT16 pdu = 0;
        if (s3 && SUCCEEDED(s3->get_Session(&session)) && session && SUCCEEDED(session->get_MaxPduSize(&pdu)) && pdu) {
            mtu = pdu;
        }
        release(session);
        release(s3);
    }
    push_event(c, Event{kEvMtu, c.connected ? kGattSuccess : kGattError, mtu, 0, {}, {}});
}

void gatt_worker(std::shared_ptr<Connection> c) {
    combase().init_thread();
    for (;;) {
        Command cmd;
        {
            std::unique_lock<std::mutex> l(c->lock);
            c->wake.wait(l, [&] { return !c->commands.empty(); });
            cmd = std::move(c->commands.front());
            c->commands.pop_front();
        }
        switch (cmd.op) {
        case Op::kConnect:
            if (!c->connected) do_connect(*c);
            break;
        case Op::kDiscover: do_discover(*c); break;
        case Op::kRead: do_read(*c, cmd); break;
        case Op::kWrite: do_write(*c, cmd); break;
        case Op::kNotify: do_notify(*c, cmd); break;
        case Op::kMtu: do_mtu(*c); break;
        case Op::kDisconnect: {
            // arg 1: the link was lost (status 8, GATT_CONN_TIMEOUT, what
            // Android reports); 0: the app asked.
            const bool was = c->connected;
            release_device(*c);
            if (was) push_event(*c, Event{kEvConnection, cmd.arg ? 8 : kGattSuccess, 0, 0, {}, {}});
            break;
        }
        case Op::kClose:
            release_device(*c);
            push_event(*c, Event{kEvClosed, 0, 0, 0, {}, {}});
            std::lock_guard<std::mutex> l(c->lock);
            c->finished = true;
            return;
        }
    }
}

jlong JNICALL Bt_gattConnect(JNIEnv* env, jclass, jstring jaddress, jboolean auto_connect) {
    auto c = std::make_shared<Connection>();
    if (!bt::address_parse(jstring_utf8(env, jaddress), &c->address) || !combase().ok) return 0;
    c->auto_connect = auto_connect == JNI_TRUE;
    c->self = c;
    jlong h;
    {
        std::lock_guard<std::mutex> l(g_gatt_lock);
        h = g_next_connection++;
        g_connections[h] = c;
    }
    c->worker = std::thread(gatt_worker, c);
    post(*c, Command{Op::kConnect, 0, 0, 0, {}});
    return h;
}

jboolean JNICALL Bt_gattReconnect(JNIEnv*, jclass, jlong h) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    post(*c, Command{Op::kConnect, 0, 0, 0, {}});
    return JNI_TRUE;
}

void JNICALL Bt_gattDisconnect(JNIEnv*, jclass, jlong h) {
    auto c = connection(h);
    if (c) post(*c, Command{Op::kDisconnect, 0, 0, 0, {}});
}

// The connection stays known until its drain thread fetches kEvClosed
// (nativeGattNext), which is where the worker is joined and the handle let go.
void JNICALL Bt_gattClose(JNIEnv*, jclass, jlong h) {
    auto c = connection(h);
    if (c) post(*c, Command{Op::kClose, 0, 0, 0, {}});
}

jboolean JNICALL Bt_gattDiscover(JNIEnv*, jclass, jlong h) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    post(*c, Command{Op::kDiscover, 0, 0, 0, {}});
    return JNI_TRUE;
}

jboolean JNICALL Bt_gattRead(JNIEnv*, jclass, jlong h, jint characteristic, jint descriptor) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    post(*c, Command{Op::kRead, characteristic, descriptor, 0, {}});
    return JNI_TRUE;
}

jboolean JNICALL Bt_gattWrite(JNIEnv* env, jclass, jlong h, jint characteristic, jint descriptor, jbyteArray value,
                              jboolean with_response) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    Command cmd{Op::kWrite, characteristic, descriptor, with_response == JNI_TRUE ? 1 : 0, {}};
    if (value) {
        const jsize n = env->GetArrayLength(value);
        cmd.value.resize(static_cast<size_t>(n));
        if (n) env->GetByteArrayRegion(value, 0, n, reinterpret_cast<jbyte*>(cmd.value.data()));
    }
    post(*c, std::move(cmd));
    return JNI_TRUE;
}

jboolean JNICALL Bt_gattNotify(JNIEnv*, jclass, jlong h, jint characteristic, jboolean enable) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    post(*c, Command{Op::kNotify, characteristic, 0, enable == JNI_TRUE ? 1 : 0, {}});
    return JNI_TRUE;
}

jboolean JNICALL Bt_gattMtu(JNIEnv*, jclass, jlong h, jint mtu) {
    auto c = connection(h);
    if (!c) return JNI_FALSE;
    post(*c, Command{Op::kMtu, 0, 0, mtu, {}});
    return JNI_TRUE;
}

// The next event of a connection: {int[] {kind, status, a, b}, byte[] value,
// String[] tree}, or null when `timeout_ms` passed. After kEvClosed nothing
// follows and the handle is gone.
jobjectArray JNICALL Bt_gattNext(JNIEnv* env, jclass, jlong h, jint timeout_ms) {
    auto c = connection(h);
    if (!c) return nullptr;
    Event e;
    {
        std::unique_lock<std::mutex> l(c->lock);
        if (!c->wake.wait_for(l, std::chrono::milliseconds(timeout_ms), [&] { return !c->events.empty(); })) {
            return nullptr;
        }
        e = std::move(c->events.front());
        c->events.pop_front();
    }
    if (e.kind == kEvClosed) {
        // The worker pushed this last and is ending; the handle is done with.
        c->worker.join();
        std::lock_guard<std::mutex> l(g_gatt_lock);
        g_connections.erase(h);
    }
    jclass object_class = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(3, object_class, nullptr);
    env->DeleteLocalRef(object_class);
    if (!out) return nullptr;
    jintArray ints = env->NewIntArray(4);
    const jint v[4] = {e.kind, e.status, e.a, e.b};
    env->SetIntArrayRegion(ints, 0, 4, v);
    env->SetObjectArrayElement(out, 0, ints);
    env->DeleteLocalRef(ints);
    if (e.kind == kEvCharRead || e.kind == kEvCharChanged || e.kind == kEvDescRead) {
        jbyteArray value = to_java_bytes(env, e.value);
        env->SetObjectArrayElement(out, 1, value);
        env->DeleteLocalRef(value);
    }
    if (e.kind == kEvServices) {
        jobjectArray tree = to_java_strings(env, e.tree);
        env->SetObjectArrayElement(out, 2, tree);
        env->DeleteLocalRef(tree);
    }
    return out;
}

constexpr const char* kCls = "com.macapk.loader.HostBluetooth";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommAvailable", "()Z", Bt_rfcommAvailable),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommConnect", "(Ljava/lang/String;Ljava/lang/String;Z)J", Bt_rfcommConnect),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommListen", "(Ljava/lang/String;Ljava/lang/String;Z)J", Bt_rfcommListen),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommAccept", "(JI)J", Bt_rfcommAccept),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommRead", "(J[BII)I", Bt_rfcommRead),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommWrite", "(J[BII)V", Bt_rfcommWrite),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommAvailableBytes", "(J)I", Bt_rfcommAvailableBytes),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommMtu", "(J)I", Bt_rfcommMtu),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommRemoteAddress", "(J)Ljava/lang/String;", Bt_rfcommRemoteAddress),
    WINAPK_HOST_NATIVE(kCls, "nativeRfcommClose", "(J)V", Bt_rfcommClose),
    WINAPK_HOST_NATIVE(kCls, "nativeLeAvailable", "()Z", Bt_leAvailable),
    WINAPK_HOST_NATIVE(kCls, "nativeScanStart", "()I", Bt_scanStart),
    WINAPK_HOST_NATIVE(kCls, "nativeScanStop", "()V", Bt_scanStop),
    WINAPK_HOST_NATIVE(kCls, "nativeScanNext", "(I)[Ljava/lang/Object;", Bt_scanNext),
    WINAPK_HOST_NATIVE(kCls, "nativeGattConnect", "(Ljava/lang/String;Z)J", Bt_gattConnect),
    WINAPK_HOST_NATIVE(kCls, "nativeGattReconnect", "(J)Z", Bt_gattReconnect),
    WINAPK_HOST_NATIVE(kCls, "nativeGattDisconnect", "(J)V", Bt_gattDisconnect),
    WINAPK_HOST_NATIVE(kCls, "nativeGattClose", "(J)V", Bt_gattClose),
    WINAPK_HOST_NATIVE(kCls, "nativeGattDiscover", "(J)Z", Bt_gattDiscover),
    WINAPK_HOST_NATIVE(kCls, "nativeGattRead", "(JII)Z", Bt_gattRead),
    WINAPK_HOST_NATIVE(kCls, "nativeGattWrite", "(JII[BZ)Z", Bt_gattWrite),
    WINAPK_HOST_NATIVE(kCls, "nativeGattNotify", "(JIZ)Z", Bt_gattNotify),
    WINAPK_HOST_NATIVE(kCls, "nativeGattMtu", "(JI)Z", Bt_gattMtu),
    WINAPK_HOST_NATIVE(kCls, "nativeGattNext", "(JI)[Ljava/lang/Object;", Bt_gattNext),
};

}  // namespace

HostMethods native_bluetooth_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
