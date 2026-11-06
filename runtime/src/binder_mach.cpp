// binder_mach.cpp -- cross-process android.os.Binder over Mach ports
// (libmacapk-binder.dylib; JNI for android.os.MacApkBinderTransport).
//
// Each Mac.apk app runs in its own process (owner decision 2026-10-30), and a
// Binder must reach across them the way it does on a device. Mach maps onto the
// binder driver nearly one-to-one, and the KERNEL does the handle translation:
//
//   local Binder           a RECEIVE right this process owns (in g_portset)
//   BinderProxy            a SEND right
//   flat_binder_object     a mach_msg_port_descriptor_t (moved/copied, renamed
//                          for the receiver by the kernel)
//   transact()             mach_msg_overwrite(SEND|RCV) with a send-once reply
//   FLAG_ONEWAY            send with no reply port
//   linkToDeath            MACH_NOTIFY_DEAD_NAME on the send right
//   calling pid            the receive AUDIT trailer (kernel-provided)
//
// Every primitive was measured on this Mac first (debug/machipc_spike); the
// design and the measurements are in docs/cross-process-binder-over-mach_
// 2026-10-30.md. This file is the wire and the thread pool; which Java object a
// port stands for is decided in Java (MacApkBinderTransport), so the identity
// tables live in exactly one place.
//
// WIRE (request and reply have the same shape):
//   mach_msg_header_t (msgh_id = kMsgId; COMPLEX when there are descriptors)
//   mach_msg_body_t
//   nObj port descriptors, one per entry of the Parcel's object table, in table
//     order (a null entry is MACH_PORT_NULL), so the far side rebuilds the
//     table with the SAME indices and the Parcel bytes need no rewriting
//   [one OOL descriptor carrying the Parcel bytes when dataLen > kInlineMax]
//   WireHead, int32 offsets[nOff], uint8 kinds[nObj] (padded to 4),
//   [the Parcel bytes, padded to 4, when not OOL]
// Object kinds: 0 null, 1 binder (a port right), 2 ParcelFileDescriptor and
// 3 raw FileDescriptor (both an fd, sent as a FILEPORT -- the receiver gets a
// new fd for the same open file, as the binder driver's BINDER_TYPE_FD does).
//
// None of the APIs used here is deprecated in the macOS 27 SDK; all predate the
// 26.4 floor. Rendezvous is bootstrap_check_in / bootstrap_look_up on a
// launchd-declared name (MachServices), never bootstrap_register.

#include <jni.h>
#include <mach/mach.h>
#include <mach/notify.h>
#include <servers/bootstrap.h>
#include <bsm/libbsm.h>
#include <pthread.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <atomic>

// libSystem (what XPC itself uses to pass fds): a Mach send right that holds a
// kernel reference to an open file; makefd installs a new fd for it.
extern "C" int fileport_makeport(int fd, mach_port_t* port);
extern "C" int fileport_makefd(mach_port_t port);

namespace {

constexpr mach_msg_id_t kMsgId = 0x4d41424e;   // 'MABN'
constexpr uint32_t kMagic = 0x4d41424e;
constexpr uint32_t kInlineMax = 16 * 1024;
constexpr size_t kRcvBuf = 64 * 1024;

// AOSP status_t values (system/core/libutils/include/utils/Errors.h).
constexpr int32_t NO_ERROR = 0;
constexpr int32_t UNKNOWN_TRANSACTION = -74;           // -EBADMSG
constexpr int32_t DEAD_OBJECT = -32;                   // -EPIPE
constexpr int32_t FAILED_TRANSACTION = (int32_t)0x80000002;
constexpr int32_t BAD_VALUE = -22;                     // -EINVAL

struct WireHead {
    uint32_t magic;
    int32_t code;
    int32_t flags;
    int32_t status;
    int32_t senderUid;
    uint32_t dataLen;
    uint32_t nObj;
    uint32_t nOff;
    uint32_t ool;
};

JavaVM* g_vm = nullptr;
jclass g_transport = nullptr;        // global ref, android/os/MacApkBinderTransport
jmethodID g_dispatch = nullptr;
jmethodID g_onDead = nullptr;
mach_port_t g_portset = MACH_PORT_NULL;
mach_port_t g_notify = MACH_PORT_NULL;
pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
std::atomic<int> g_threads{0};

inline size_t pad4(size_t n) { return (n + 3) & ~size_t(3); }

bool ensure_portset() {
    pthread_mutex_lock(&g_lock);
    kern_return_t kr = KERN_SUCCESS;
    if (g_portset == MACH_PORT_NULL) {
        kr = mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_PORT_SET, &g_portset);
        if (kr == KERN_SUCCESS)
            kr = mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &g_notify);
        if (kr == KERN_SUCCESS)
            kr = mach_port_move_member(mach_task_self(), g_notify, g_portset);
    }
    pthread_mutex_unlock(&g_lock);
    return kr == KERN_SUCCESS;
}

bool holds_receive(mach_port_t p) {
    mach_port_type_t t = 0;
    return mach_port_type(mach_task_self(), p, &t) == KERN_SUCCESS
        && (t & MACH_PORT_TYPE_RECEIVE);
}

// One outgoing message, built in a byte buffer.
struct Out {
    std::vector<uint8_t> buf;
    mach_msg_header_t* hdr() { return reinterpret_cast<mach_msg_header_t*>(buf.data()); }
};

// Build the message. `ports`/`kinds` are the Parcel's object table; a local
// Binder's port goes as MAKE_SEND (we own its receive right), a proxy's as
// COPY_SEND. Returns false on a malformed table.
bool build(Out& o, int32_t code, int32_t flags, int32_t status, int32_t senderUid,
           const uint8_t* data, uint32_t dataLen,
           const int32_t* offs, uint32_t nOff,
           const int32_t* ports, const int32_t* kinds, uint32_t nObj) {
    const bool ool = dataLen > kInlineMax;
    const uint32_t nDesc = nObj + (ool ? 1 : 0);
    size_t size = sizeof(mach_msg_header_t) + sizeof(mach_msg_body_t)
                + nObj * sizeof(mach_msg_port_descriptor_t)
                + (ool ? sizeof(mach_msg_ool_descriptor_t) : 0)
                + sizeof(WireHead) + nOff * 4 + pad4(nObj) + (ool ? 0 : pad4(dataLen));
    o.buf.assign(size, 0);
    uint8_t* p = o.buf.data();
    auto* h = reinterpret_cast<mach_msg_header_t*>(p);
    h->msgh_size = (mach_msg_size_t)size;
    h->msgh_id = kMsgId;
    if (nDesc) h->msgh_bits |= MACH_MSGH_BITS_COMPLEX;
    p += sizeof(mach_msg_header_t);
    reinterpret_cast<mach_msg_body_t*>(p)->msgh_descriptor_count = nDesc;
    p += sizeof(mach_msg_body_t);
    for (uint32_t i = 0; i < nObj; i++) {
        auto* d = reinterpret_cast<mach_msg_port_descriptor_t*>(p);
        d->type = MACH_MSG_PORT_DESCRIPTOR;
        if (kinds[i] == 2 || kinds[i] == 3) {
            // An fd: a fresh fileport we own, moved into the message.
            mach_port_t fp = MACH_PORT_NULL;
            if (ports[i] < 0 || fileport_makeport(ports[i], &fp) != 0) fp = MACH_PORT_NULL;
            d->name = fp;
            d->disposition = MACH_MSG_TYPE_MOVE_SEND;
        } else {
            mach_port_t port = (kinds[i] == 0) ? MACH_PORT_NULL : (mach_port_t)ports[i];
            d->name = port;
            d->disposition = (port != MACH_PORT_NULL && holds_receive(port))
                           ? MACH_MSG_TYPE_MAKE_SEND : MACH_MSG_TYPE_COPY_SEND;
        }
        p += sizeof(mach_msg_port_descriptor_t);
    }
    if (ool) {
        auto* d = reinterpret_cast<mach_msg_ool_descriptor_t*>(p);
        d->address = const_cast<uint8_t*>(data);
        d->size = dataLen;
        d->deallocate = false;
        d->copy = MACH_MSG_VIRTUAL_COPY;
        d->type = MACH_MSG_OOL_DESCRIPTOR;
        p += sizeof(mach_msg_ool_descriptor_t);
    }
    auto* w = reinterpret_cast<WireHead*>(p);
    *w = WireHead{kMagic, code, flags, status, senderUid, dataLen, nObj, nOff, ool ? 1u : 0u};
    p += sizeof(WireHead);
    if (nOff) memcpy(p, offs, nOff * 4);
    p += nOff * 4;
    for (uint32_t i = 0; i < nObj; i++) p[i] = (uint8_t)kinds[i];
    p += pad4(nObj);
    if (!ool && dataLen) memcpy(p, data, dataLen);
    return true;
}

// A received message, parsed. Port rights in `ports` are owned by whoever
// takes them (Java); `free_ool` releases the OOL region.
struct In {
    WireHead head{};
    const uint8_t* data = nullptr;
    void* oolAddr = nullptr;
    mach_msg_size_t oolSize = 0;
    std::vector<int32_t> offs, ports, kinds;
    void free_ool() {
        if (oolAddr) vm_deallocate(mach_task_self(), (vm_address_t)oolAddr, oolSize);
        oolAddr = nullptr;
    }
};

bool parse(mach_msg_header_t* h, In& in) {
    uint8_t* p = reinterpret_cast<uint8_t*>(h) + sizeof(mach_msg_header_t);
    uint8_t* end = reinterpret_cast<uint8_t*>(h) + h->msgh_size;
    std::vector<mach_port_t> portList;
    // build() always writes the mach_msg_body_t; in a non-COMPLEX message it is
    // plain inline bytes (count 0), so skip it either way.
    const bool complex = (h->msgh_bits & MACH_MSGH_BITS_COMPLEX) != 0;
    uint32_t n = complex ? reinterpret_cast<mach_msg_body_t*>(p)->msgh_descriptor_count : 0;
    p += sizeof(mach_msg_body_t);
    {
        for (uint32_t i = 0; i < n; i++) {
            auto* t = reinterpret_cast<mach_msg_type_descriptor_t*>(p);
            if (t->type == MACH_MSG_PORT_DESCRIPTOR) {
                portList.push_back(reinterpret_cast<mach_msg_port_descriptor_t*>(p)->name);
                p += sizeof(mach_msg_port_descriptor_t);
            } else if (t->type == MACH_MSG_OOL_DESCRIPTOR) {
                auto* d = reinterpret_cast<mach_msg_ool_descriptor_t*>(p);
                in.oolAddr = d->address; in.oolSize = d->size;
                p += sizeof(mach_msg_ool_descriptor_t);
            } else {
                return false;
            }
        }
    }
    if (p + sizeof(WireHead) > end) return false;
    memcpy(&in.head, p, sizeof(WireHead));
    p += sizeof(WireHead);
    if (in.head.magic != kMagic || in.head.nObj != portList.size()) return false;
    if (p + in.head.nOff * 4 + pad4(in.head.nObj) > end) return false;
    in.offs.assign(reinterpret_cast<int32_t*>(p), reinterpret_cast<int32_t*>(p) + in.head.nOff);
    p += in.head.nOff * 4;
    in.kinds.resize(in.head.nObj);
    in.ports.resize(in.head.nObj);
    for (uint32_t i = 0; i < in.head.nObj; i++) {
        in.kinds[i] = p[i];
        in.ports[i] = (int32_t)portList[i];
        if (in.kinds[i] == 2 || in.kinds[i] == 3) {
            // A fileport: install an fd for it and drop the port.
            mach_port_t fp = portList[i];
            in.ports[i] = fp != MACH_PORT_NULL ? fileport_makefd(fp) : -1;
            if (fp != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), fp);
        }
    }
    p += pad4(in.head.nObj);
    if (in.head.ool) {
        if (!in.oolAddr || in.oolSize < in.head.dataLen) return false;
        in.data = static_cast<const uint8_t*>(in.oolAddr);
    } else {
        if (p + in.head.dataLen > end) return false;
        in.data = p;
    }
    return true;
}

// Release the send rights a message carried when nobody took them.
void drop_ports(const In& in) {
    for (int32_t pt : in.ports)
        if (pt) mach_port_deallocate(mach_task_self(), (mach_port_t)pt);
}

jintArray toJ(JNIEnv* env, const std::vector<int32_t>& v) {
    jintArray a = env->NewIntArray((jsize)v.size());
    if (a && !v.empty()) env->SetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
    return a;
}

std::vector<int32_t> fromJ(JNIEnv* env, jintArray a) {
    std::vector<int32_t> v;
    if (!a) return v;
    v.resize(env->GetArrayLength(a));
    if (!v.empty()) env->GetIntArrayRegion(a, 0, (jsize)v.size(), v.data());
    return v;
}

// Receive into a growable buffer. `opts` must include MACH_RCV_MSG.
kern_return_t receive(std::vector<uint8_t>& buf, mach_port_t from, mach_msg_option_t extra) {
    const mach_msg_option_t trailer = MACH_RCV_TRAILER_TYPE(MACH_MSG_TRAILER_FORMAT_0)
                                    | MACH_RCV_TRAILER_ELEMENTS(MACH_RCV_TRAILER_AUDIT);
    for (;;) {
        auto* h = reinterpret_cast<mach_msg_header_t*>(buf.data());
        kern_return_t kr = mach_msg(h, MACH_RCV_MSG | MACH_RCV_LARGE | trailer | extra, 0,
                                    (mach_msg_size_t)buf.size(), from,
                                    MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
        if (kr == MACH_RCV_TOO_LARGE) {
            buf.resize(h->msgh_size + MAX_TRAILER_SIZE + 64);
            continue;
        }
        return kr;
    }
}

pid_t sender_pid(mach_msg_header_t* h) {
    auto* t = reinterpret_cast<mach_msg_audit_trailer_t*>(
        reinterpret_cast<uint8_t*>(h) + round_msg(h->msgh_size));
    return audit_token_to_pid(t->msgh_audit);
}

void* loop(void*) {
    JNIEnv* env = nullptr;
    char name[32];
    snprintf(name, sizeof name, "binder:%d", g_threads.fetch_add(1) + 1);
    JavaVMAttachArgs args{JNI_VERSION_1_8, name, nullptr};
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), &args) != JNI_OK)
        return nullptr;
    std::vector<uint8_t> buf(kRcvBuf);
    for (;;) {
        kern_return_t kr = receive(buf, g_portset, 0);
        if (kr != KERN_SUCCESS) {
            fprintf(stderr, "[binder] receive failed: %s\n", mach_error_string(kr));
            continue;
        }
        auto* h = reinterpret_cast<mach_msg_header_t*>(buf.data());
        if (h->msgh_id == MACH_NOTIFY_DEAD_NAME) {
            mach_port_t dead = reinterpret_cast<mach_dead_name_notification_t*>(h)->not_port;
            env->CallStaticVoidMethod(g_transport, g_onDead, (jint)dead);
            if (env->ExceptionCheck()) env->ExceptionClear();
            // The notification carries a user reference on the dead name.
            mach_port_deallocate(mach_task_self(), dead);
            continue;
        }
        if (h->msgh_id != kMsgId) {
            mach_msg_destroy(h);
            continue;
        }
        In in;
        if (!parse(h, in)) {
            fprintf(stderr, "[binder] malformed message dropped\n");
            mach_msg_destroy(h);
            continue;
        }
        const mach_port_t target = h->msgh_local_port;
        const mach_port_t reply = h->msgh_remote_port;   // send-once, or NULL (oneway)
        const pid_t pid = sender_pid(h);

        jbyteArray jdata = env->NewByteArray((jsize)in.head.dataLen);
        if (jdata && in.head.dataLen)
            env->SetByteArrayRegion(jdata, 0, (jsize)in.head.dataLen,
                                    reinterpret_cast<const jbyte*>(in.data));
        in.free_ool();
        jobjectArray r = (jobjectArray)env->CallStaticObjectMethod(
            g_transport, g_dispatch, (jint)target, (jint)in.head.code, (jint)in.head.flags,
            jdata, toJ(env, in.offs), toJ(env, in.ports), toJ(env, in.kinds),
            (jint)pid, (jint)in.head.senderUid);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            r = nullptr;
        }
        if (reply != MACH_PORT_NULL) {
            int32_t status = FAILED_TRANSACTION;
            std::vector<int32_t> offs, ports, kinds;
            std::vector<uint8_t> data;
            if (r) {
                jintArray st = (jintArray)env->GetObjectArrayElement(r, 0);
                std::vector<int32_t> sv = fromJ(env, st);
                if (!sv.empty()) status = sv[0];
                jbyteArray d = (jbyteArray)env->GetObjectArrayElement(r, 1);
                if (d) {
                    data.resize(env->GetArrayLength(d));
                    if (!data.empty())
                        env->GetByteArrayRegion(d, 0, (jsize)data.size(),
                                                reinterpret_cast<jbyte*>(data.data()));
                }
                offs = fromJ(env, (jintArray)env->GetObjectArrayElement(r, 2));
                ports = fromJ(env, (jintArray)env->GetObjectArrayElement(r, 3));
                kinds = fromJ(env, (jintArray)env->GetObjectArrayElement(r, 4));
            }
            Out o;
            build(o, 0, 0, status, 0, data.data(), (uint32_t)data.size(),
                  offs.data(), (uint32_t)offs.size(), ports.data(), kinds.data(),
                  (uint32_t)ports.size());
            mach_msg_header_t* oh = o.hdr();
            oh->msgh_bits |= MACH_MSGH_BITS(MACH_MSG_TYPE_MOVE_SEND_ONCE, 0);
            oh->msgh_remote_port = reply;
            kr = mach_msg(oh, MACH_SEND_MSG, oh->msgh_size, 0, MACH_PORT_NULL,
                          MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
            if (kr != KERN_SUCCESS) {
                // The caller died mid-call: release what the reply would have
                // carried, as the binder driver drops an undeliverable reply.
                mach_msg_destroy(oh);
            }
        }
        env->DeleteLocalRef(jdata);
        if (r) env->DeleteLocalRef(r);
    }
    return nullptr;
}

pthread_key_t g_replyKey;
pthread_once_t g_replyOnce = PTHREAD_ONCE_INIT;

void reply_key_init() {
    pthread_key_create(&g_replyKey, [](void* v) {
        mach_port_t p = (mach_port_t)(uintptr_t)v;
        if (p) mach_port_mod_refs(mach_task_self(), p, MACH_PORT_RIGHT_RECEIVE, -1);
    });
}

mach_port_t thread_reply_port() {
    pthread_once(&g_replyOnce, reply_key_init);
    mach_port_t p = (mach_port_t)(uintptr_t)pthread_getspecific(g_replyKey);
    if (!p) {
        if (mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &p) != KERN_SUCCESS)
            return MACH_PORT_NULL;
        pthread_setspecific(g_replyKey, (void*)(uintptr_t)p);
    }
    return p;
}

jobjectArray result(JNIEnv* env, int32_t status, const In* in) {
    jclass objCls = env->FindClass("java/lang/Object");
    jobjectArray r = env->NewObjectArray(5, objCls, nullptr);
    std::vector<int32_t> st{status};
    env->SetObjectArrayElement(r, 0, toJ(env, st));
    if (in) {
        jbyteArray d = env->NewByteArray((jsize)in->head.dataLen);
        if (in->head.dataLen)
            env->SetByteArrayRegion(d, 0, (jsize)in->head.dataLen,
                                    reinterpret_cast<const jbyte*>(in->data));
        env->SetObjectArrayElement(r, 1, d);
        env->SetObjectArrayElement(r, 2, toJ(env, in->offs));
        env->SetObjectArrayElement(r, 3, toJ(env, in->ports));
        env->SetObjectArrayElement(r, 4, toJ(env, in->kinds));
    }
    return r;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_8;
}

// Register the Java upcalls once (the class the natives belong to).
JNIEXPORT jboolean JNICALL
Java_android_os_MacApkBinderTransport_nativeInit(JNIEnv* env, jclass cls) {
    if (!g_transport) {
        g_transport = (jclass)env->NewGlobalRef(cls);
        g_dispatch = env->GetStaticMethodID(cls, "dispatch",
            "(III[B[I[I[III)[Ljava/lang/Object;");
        g_onDead = env->GetStaticMethodID(cls, "onDeadName", "(I)V");
        if (!g_dispatch || !g_onDead) return JNI_FALSE;
    }
    return ensure_portset() ? JNI_TRUE : JNI_FALSE;
}

// A new receive right (a local Binder's port) with one send right, served by
// the thread pool.
JNIEXPORT jint JNICALL
Java_android_os_MacApkBinderTransport_nativeCreatePort(JNIEnv*, jclass) {
    if (!ensure_portset()) return 0;
    mach_port_t p = MACH_PORT_NULL;
    if (mach_port_allocate(mach_task_self(), MACH_PORT_RIGHT_RECEIVE, &p) != KERN_SUCCESS)
        return 0;
    if (mach_port_move_member(mach_task_self(), p, g_portset) != KERN_SUCCESS) {
        mach_port_mod_refs(mach_task_self(), p, MACH_PORT_RIGHT_RECEIVE, -1);
        return 0;
    }
    return (jint)p;
}

// Claim a launchd-declared name (MachServices) and serve it. Negative = the
// kern_return_t / bootstrap error, so Java can say WHY.
JNIEXPORT jint JNICALL
Java_android_os_MacApkBinderTransport_nativeCheckIn(JNIEnv* env, jclass, jstring jname) {
    if (!ensure_portset()) return -1;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    mach_port_t p = MACH_PORT_NULL;
    kern_return_t kr = bootstrap_check_in(bootstrap_port, name, &p);
    env->ReleaseStringUTFChars(jname, name);
    if (kr != KERN_SUCCESS) return -(jint)kr;
    if (mach_port_move_member(mach_task_self(), p, g_portset) != KERN_SUCCESS) return -2;
    return (jint)p;
}

JNIEXPORT jint JNICALL
Java_android_os_MacApkBinderTransport_nativeLookUp(JNIEnv* env, jclass, jstring jname) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    mach_port_t p = MACH_PORT_NULL;
    kern_return_t kr = bootstrap_look_up(bootstrap_port, name, &p);
    env->ReleaseStringUTFChars(jname, name);
    return kr == KERN_SUCCESS ? (jint)p : 0;
}

JNIEXPORT void JNICALL
Java_android_os_MacApkBinderTransport_nativeStartThreads(JNIEnv*, jclass, jint n) {
    for (int i = 0; i < n; i++) {
        pthread_t t;
        pthread_attr_t a;
        pthread_attr_init(&a);
        pthread_attr_setdetachstate(&a, PTHREAD_CREATE_DETACHED);
        pthread_attr_setstacksize(&a, 1 << 20);
        pthread_create(&t, &a, loop, nullptr);
        pthread_attr_destroy(&a);
    }
}

// transact: returns {int[]{status}, byte[] reply, int[] offsets, int[] ports,
// int[] kinds}; for a oneway call only the status slot is filled.
JNIEXPORT jobjectArray JNICALL
Java_android_os_MacApkBinderTransport_nativeTransact(JNIEnv* env, jclass, jint dest,
        jint code, jint flags, jint senderUid, jbyteArray jdata, jintArray joffs,
        jintArray jports, jintArray jkinds) {
    std::vector<uint8_t> data;
    if (jdata) {
        data.resize(env->GetArrayLength(jdata));
        if (!data.empty())
            env->GetByteArrayRegion(jdata, 0, (jsize)data.size(),
                                    reinterpret_cast<jbyte*>(data.data()));
    }
    std::vector<int32_t> offs = fromJ(env, joffs), ports = fromJ(env, jports),
                         kinds = fromJ(env, jkinds);
    if (ports.size() != kinds.size()) return result(env, BAD_VALUE, nullptr);
    Out o;
    build(o, code, flags, 0, senderUid, data.data(), (uint32_t)data.size(),
          offs.data(), (uint32_t)offs.size(), ports.data(), kinds.data(),
          (uint32_t)ports.size());
    mach_msg_header_t* h = o.hdr();
    h->msgh_remote_port = (mach_port_t)dest;
    const bool oneway = (flags & 1) != 0;   // IBinder.FLAG_ONEWAY
    if (oneway) {
        h->msgh_bits |= MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, 0);
        kern_return_t kr = mach_msg(h, MACH_SEND_MSG, h->msgh_size, 0, MACH_PORT_NULL,
                                    MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
        if (kr == MACH_SEND_INVALID_DEST) return result(env, DEAD_OBJECT, nullptr);
        return result(env, kr == KERN_SUCCESS ? NO_ERROR : FAILED_TRANSACTION, nullptr);
    }
    mach_port_t reply = thread_reply_port();
    if (!reply) return result(env, FAILED_TRANSACTION, nullptr);
    h->msgh_local_port = reply;
    h->msgh_bits |= MACH_MSGH_BITS(MACH_MSG_TYPE_COPY_SEND, MACH_MSG_TYPE_MAKE_SEND_ONCE);
    kern_return_t kr = mach_msg(h, MACH_SEND_MSG, h->msgh_size, 0, MACH_PORT_NULL,
                                MACH_MSG_TIMEOUT_NONE, MACH_PORT_NULL);
    if (kr == MACH_SEND_INVALID_DEST) return result(env, DEAD_OBJECT, nullptr);
    if (kr != KERN_SUCCESS) return result(env, FAILED_TRANSACTION, nullptr);
    std::vector<uint8_t> buf(kRcvBuf);
    kr = receive(buf, reply, 0);
    if (kr != KERN_SUCCESS) return result(env, FAILED_TRANSACTION, nullptr);
    auto* rh = reinterpret_cast<mach_msg_header_t*>(buf.data());
    if (rh->msgh_id == MACH_NOTIFY_SEND_ONCE) {
        // The server died holding our reply right: the kernel answered for it.
        return result(env, DEAD_OBJECT, nullptr);
    }
    In in;
    if (rh->msgh_id != kMsgId || !parse(rh, in)) {
        mach_msg_destroy(rh);
        return result(env, FAILED_TRANSACTION, nullptr);
    }
    jobjectArray r = result(env, in.head.status, &in);
    in.free_ool();
    return r;
}

// linkToDeath: ask the kernel for a dead-name notification on a send right.
JNIEXPORT jboolean JNICALL
Java_android_os_MacApkBinderTransport_nativeRequestDeath(JNIEnv*, jclass, jint port) {
    if (!ensure_portset()) return JNI_FALSE;
    mach_port_t prev = MACH_PORT_NULL;
    kern_return_t kr = mach_port_request_notification(mach_task_self(), (mach_port_t)port,
        MACH_NOTIFY_DEAD_NAME, 1, g_notify, MACH_MSG_TYPE_MAKE_SEND_ONCE, &prev);
    if (prev != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), prev);
    return kr == KERN_SUCCESS ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_android_os_MacApkBinderTransport_nativeIsDead(JNIEnv*, jclass, jint port) {
    mach_port_type_t t = 0;
    if (mach_port_type(mach_task_self(), (mach_port_t)port, &t) != KERN_SUCCESS) return JNI_TRUE;
    return (t & MACH_PORT_TYPE_DEAD_NAME) ? JNI_TRUE : JNI_FALSE;
}

// Drop one user reference on a send right / dead name (a proxy that died, or a
// duplicate right that arrived for a port we already have a proxy for).
JNIEXPORT void JNICALL
Java_android_os_MacApkBinderTransport_nativeReleaseSend(JNIEnv*, jclass, jint port) {
    if (port) mach_port_deallocate(mach_task_self(), (mach_port_t)port);
}

}  // extern "C"
