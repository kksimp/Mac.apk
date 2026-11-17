// native_asset.cpp -- the NDK's AAssetManager for an app's native code.
//
// Upstream (bionic_shim.cpp, "AAssetManager_* family") indexes the installed
// APKs and maps stored entries straight out of them, keeping a JNI route
// (com.macapk.loader.NativeAssetBridge.openAsset, AssetManager.list) for names
// the index lacks. Here the JNI route is the only one for now: an asset is read
// whole into memory through the app's own AssetManager. Large assets cost a
// copy; a native APK index is the upgrade path.

#include "native_asset.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <unordered_map>
#include <vector>

#include <fcntl.h>
#include <io.h>
#include <windows.h>

#include <jni.h>

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

namespace winapk::host {

namespace {

struct Asset {
    std::vector<uint8_t> bytes;
    size_t pos = 0;
};

struct AssetDir {
    std::vector<std::string> names;
    size_t next = 0;
};

// The calling thread's host JNIEnv; guest threads are attached on first use.
JNIEnv* env_here() {
    JNIEnv* guest = winapk::guest_env_for_current_thread();
    return guest ? winapk::host_env_of(guest) : nullptr;
}

bool clear(JNIEnv* env) {
    if (!env->ExceptionCheck()) return false;
    env->ExceptionClear();
    return true;
}

// Every AAssetManager is the app's own (upstream returns a sentinel too).
char g_manager = 0;

WINAPK_GUEST_ABI void* AAssetManager_fromJava(JNIEnv*, jobject) { return &g_manager; }

WINAPK_GUEST_ABI Asset* AAssetManager_open(void*, const char* name, int /*mode*/) {
    JNIEnv* env = name ? env_here() : nullptr;
    if (!env) return nullptr;
    jclass cls = env->FindClass("com/macapk/loader/NativeAssetBridge");
    jmethodID open = cls ? env->GetStaticMethodID(cls, "openAsset", "(Ljava/lang/String;)[B") : nullptr;
    if (clear(env) || !open) {
        if (cls) env->DeleteLocalRef(cls);
        return nullptr;
    }
    jstring jname = env->NewStringUTF(name);
    auto bytes = static_cast<jbyteArray>(env->CallStaticObjectMethod(cls, open, jname));
    env->DeleteLocalRef(jname);
    env->DeleteLocalRef(cls);
    if (clear(env) || !bytes) return nullptr;
    auto* a = new Asset;
    a->bytes.resize(static_cast<size_t>(env->GetArrayLength(bytes)));
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(a->bytes.size()), reinterpret_cast<jbyte*>(a->bytes.data()));
    env->DeleteLocalRef(bytes);
    return a;
}

// The regular files directly in assets/<dir>, sorted as on a device
// (AssetManager.list keeps zip order).
WINAPK_GUEST_ABI AssetDir* AAssetManager_openDir(void*, const char* dir) {
    JNIEnv* env = env_here();
    if (!env) return nullptr;
    jclass app_cls = env->FindClass("android/app/Application");
    jmethodID instance =
        app_cls ? env->GetStaticMethodID(app_cls, "getInstance", "()Landroid/app/Application;") : nullptr;
    jobject app = instance ? env->CallStaticObjectMethod(app_cls, instance) : nullptr;
    jmethodID get_assets =
        app ? env->GetMethodID(app_cls, "getAssets", "()Landroid/content/res/AssetManager;") : nullptr;
    jobject manager = get_assets ? env->CallObjectMethod(app, get_assets) : nullptr;
    jclass manager_cls = manager ? env->GetObjectClass(manager) : nullptr;
    jmethodID list =
        manager_cls ? env->GetMethodID(manager_cls, "list", "(Ljava/lang/String;)[Ljava/lang/String;") : nullptr;
    jobjectArray names = nullptr;
    if (list) {
        jstring jdir = env->NewStringUTF(dir ? dir : "");
        names = static_cast<jobjectArray>(env->CallObjectMethod(manager, list, jdir));
        env->DeleteLocalRef(jdir);
    }
    const bool failed = clear(env);
    AssetDir* d = nullptr;
    if (!failed && names) {
        d = new AssetDir;
        const jsize n = env->GetArrayLength(names);
        for (jsize i = 0; i < n; ++i) {
            auto s = static_cast<jstring>(env->GetObjectArrayElement(names, i));
            if (!s) continue;
            if (const char* c = env->GetStringUTFChars(s, nullptr)) {
                d->names.emplace_back(c);
                env->ReleaseStringUTFChars(s, c);
            }
            env->DeleteLocalRef(s);
        }
        std::sort(d->names.begin(), d->names.end());
    }
    for (jobject o : {static_cast<jobject>(names), static_cast<jobject>(manager_cls), manager, app,
                      static_cast<jobject>(app_cls)}) {
        if (o) env->DeleteLocalRef(o);
    }
    return d;
}

WINAPK_GUEST_ABI const char* AAssetDir_getNextFileName(AssetDir* d) {
    return d && d->next < d->names.size() ? d->names[d->next++].c_str() : nullptr;
}
WINAPK_GUEST_ABI void AAssetDir_rewind(AssetDir* d) {
    if (d) d->next = 0;
}
WINAPK_GUEST_ABI void AAssetDir_close(AssetDir* d) { delete d; }

WINAPK_GUEST_ABI const void* AAsset_getBuffer(Asset* a) { return a ? a->bytes.data() : nullptr; }
WINAPK_GUEST_ABI int64_t AAsset_getLength(Asset* a) { return a ? static_cast<int64_t>(a->bytes.size()) : 0; }
WINAPK_GUEST_ABI int64_t AAsset_getRemainingLength(Asset* a) {
    return a ? static_cast<int64_t>(a->bytes.size() - a->pos) : 0;
}

WINAPK_GUEST_ABI int AAsset_read(Asset* a, void* buf, size_t count) {
    if (!a || !buf) return -1;
    const size_t n = std::min(count, a->bytes.size() - a->pos);
    std::memcpy(buf, a->bytes.data() + a->pos, n);
    a->pos += n;
    return static_cast<int>(n);
}

WINAPK_GUEST_ABI int64_t AAsset_seek(Asset* a, int64_t offset, int whence) {
    if (!a) return -1;
    int64_t base = 0;
    switch (whence) {
        case 0: base = 0; break;                                         // SEEK_SET
        case 1: base = static_cast<int64_t>(a->pos); break;              // SEEK_CUR
        case 2: base = static_cast<int64_t>(a->bytes.size()); break;     // SEEK_END
        default: return -1;
    }
    const int64_t to = base + offset;
    if (to < 0 || to > static_cast<int64_t>(a->bytes.size())) return -1;
    a->pos = static_cast<size_t>(to);
    return to;
}

WINAPK_GUEST_ABI void AAsset_close(Asset* a) { delete a; }
WINAPK_GUEST_ABI int AAsset_isAllocated(Asset*) { return 1; }

// A descriptor on a temporary copy, deleted when the app closes it: on a
// device this is the APK's own descriptor for an uncompressed entry. Guest
// descriptors are CRT descriptors.
WINAPK_GUEST_ABI int AAsset_openFileDescriptor(Asset* a, int64_t* start, int64_t* length) {
    if (!a) return -1;
    wchar_t dir[MAX_PATH], path[MAX_PATH];
    if (!GetTempPathW(MAX_PATH, dir) || !GetTempFileNameW(dir, L"apk", 0, path)) return -1;
    HANDLE h = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                           CREATE_ALWAYS, FILE_ATTRIBUTE_TEMPORARY | FILE_FLAG_DELETE_ON_CLOSE, nullptr);
    if (h == INVALID_HANDLE_VALUE) return -1;
    DWORD done = 0;
    size_t written = 0;
    while (written < a->bytes.size()) {
        const DWORD chunk = static_cast<DWORD>(std::min<size_t>(a->bytes.size() - written, 1u << 30));
        if (!WriteFile(h, a->bytes.data() + written, chunk, &done, nullptr) || done == 0) {
            CloseHandle(h);
            return -1;
        }
        written += done;
    }
    SetFilePointer(h, 0, nullptr, FILE_BEGIN);
    const int fd = _open_osfhandle(reinterpret_cast<intptr_t>(h), _O_BINARY | _O_RDONLY);
    if (fd < 0) {
        CloseHandle(h);
        return -1;
    }
    if (start) *start = 0;
    if (length) *length = static_cast<int64_t>(a->bytes.size());
    return fd;
}

#define WINAPK_ASSET(fn) {#fn, reinterpret_cast<void*>(&fn)}
const std::unordered_map<std::string, void*> kSymbols = {
    WINAPK_ASSET(AAssetManager_fromJava),  WINAPK_ASSET(AAssetManager_open),
    WINAPK_ASSET(AAssetManager_openDir),   WINAPK_ASSET(AAssetDir_getNextFileName),
    WINAPK_ASSET(AAssetDir_rewind),        WINAPK_ASSET(AAssetDir_close),
    WINAPK_ASSET(AAsset_getBuffer),        WINAPK_ASSET(AAsset_getLength),
    WINAPK_ASSET(AAsset_getRemainingLength), WINAPK_ASSET(AAsset_read),
    WINAPK_ASSET(AAsset_seek),             WINAPK_ASSET(AAsset_close),
    WINAPK_ASSET(AAsset_isAllocated),      WINAPK_ASSET(AAsset_openFileDescriptor),
    // off_t is 64 bits on LP64, so the 64-bit names are the same functions.
    {"AAsset_getLength64", reinterpret_cast<void*>(&AAsset_getLength)},
    {"AAsset_getRemainingLength64", reinterpret_cast<void*>(&AAsset_getRemainingLength)},
    {"AAsset_seek64", reinterpret_cast<void*>(&AAsset_seek)},
    {"AAsset_openFileDescriptor64", reinterpret_cast<void*>(&AAsset_openFileDescriptor)},
};

}  // namespace

void* native_asset_resolve(const char* name) {
    auto it = kSymbols.find(name);
    return it == kSymbols.end() ? nullptr : it->second;
}

}  // namespace winapk::host
