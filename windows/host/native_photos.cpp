// native_photos.cpp -- MediaStoreProvider's photo library on Windows: the
// user's Pictures and Videos folders, where upstream (photos_bridge.mm) reads
// the macOS Photos library. A desktop program needs no permission for them,
// so the status is "authorized" and nothing is ever prompted.
//
// An asset's id is its full path. Records are MediaStoreProvider's nine
// tab-separated fields: id, media type (1 image, 3 video), created and
// modified (ms), width, height, duration (ms, 0 unknown), name, MIME type.
// Thumbnails come from the shell (IShellItemImageFactory), which covers
// videos too, and are written as JPEG.

#include "host_natives.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>
#include <vector>

#include <windows.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <wincodec.h>

namespace winapk::host {

namespace {

constexpr jint kAuthorized = 3;

template <typename T>
struct Com {
    T* p = nullptr;
    ~Com() {
        if (p) p->Release();
    }
    T** operator&() { return &p; }
    T* operator->() { return p; }
};

struct ComScope {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    ~ComScope() {
        if (SUCCEEDED(hr)) CoUninitialize();
    }
};

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}
std::wstring wide(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* c = env->GetStringChars(s, nullptr);
    std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return w;
}

std::wstring known_folder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    std::wstring out;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) out = p;
    CoTaskMemFree(p);
    return out;
}

struct Kind {
    const wchar_t* ext;
    bool image;
    const char* mime;
};
const Kind kKinds[] = {
    {L".jpg", true, "image/jpeg"},  {L".jpeg", true, "image/jpeg"}, {L".png", true, "image/png"},
    {L".gif", true, "image/gif"},   {L".webp", true, "image/webp"}, {L".bmp", true, "image/bmp"},
    {L".heic", true, "image/heic"}, {L".heif", true, "image/heif"}, {L".mp4", false, "video/mp4"},
    {L".m4v", false, "video/mp4"},  {L".mov", false, "video/quicktime"}, {L".mkv", false, "video/x-matroska"},
    {L".webm", false, "video/webm"}, {L".3gp", false, "video/3gpp"}, {L".avi", false, "video/x-msvideo"},
};
const Kind* kind_of(const std::wstring& name) {
    const size_t dot = name.find_last_of(L'.');
    if (dot == std::wstring::npos) return nullptr;
    const std::wstring ext = name.substr(dot);
    for (const Kind& k : kKinds) {
        if (_wcsicmp(ext.c_str(), k.ext) == 0) return &k;
    }
    return nullptr;
}

int64_t unix_ms(const FILETIME& t) {
    ULARGE_INTEGER u;
    u.LowPart = t.dwLowDateTime;
    u.HighPart = t.dwHighDateTime;
    return static_cast<int64_t>(u.QuadPart / 10000ULL) - 11644473600000LL;
}

struct Asset {
    std::wstring path, name;
    const Kind* kind;
    int64_t created, modified, size;
};

void walk(const std::wstring& dir, int depth, std::vector<Asset>* out) {
    if (depth > 6 || out->size() > 50000) return;
    WIN32_FIND_DATAW f;
    HANDLE h = FindFirstFileW((dir + L"\\*").c_str(), &f);
    if (h == INVALID_HANDLE_VALUE) return;
    do {
        const std::wstring name = f.cFileName;
        if (name == L"." || name == L".." || name[0] == L'.' ||
            (f.dwFileAttributes & (FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM))) {
            continue;
        }
        const std::wstring path = dir + L"\\" + name;
        if (f.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            if (!(f.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) walk(path, depth + 1, out);
        } else if (const Kind* k = kind_of(name)) {
            out->push_back({path, name, k, unix_ms(f.ftCreationTime), unix_ms(f.ftLastWriteTime),
                            (static_cast<int64_t>(f.nFileSizeHigh) << 32) | f.nFileSizeLow});
        }
    } while (FindNextFileW(h, &f));
    FindClose(h);
}

// The library, re-read at most every few seconds (generation and list are
// both asked for in quick succession).
std::mutex g_lock;
std::vector<Asset> g_assets;
int64_t g_generation = 0;
std::chrono::steady_clock::time_point g_scanned;

const std::vector<Asset>& scan() {
    const auto now = std::chrono::steady_clock::now();
    if (g_generation != 0 && now - g_scanned < std::chrono::seconds(3)) return g_assets;
    ComScope com;
    std::vector<Asset> assets;
    std::wstring pictures = known_folder(FOLDERID_Pictures), videos = known_folder(FOLDERID_Videos);
    // A folder the volume maps (user_folders.h) is already in MediaStore as
    // /sdcard/Pictures or /sdcard/Movies; listing it here as well would show
    // every picture twice, once more under DCIM/Camera.
    wchar_t mapped[32768];
    const DWORD n = GetEnvironmentVariableW(L"WINAPK_USER_FOLDER_TARGETS", mapped, 32768);
    const std::wstring targets = L"|" + std::wstring(mapped, n < 32768 ? n : 0);
    if (targets.find(L"|Pictures=") != std::wstring::npos) pictures.clear();
    if (targets.find(L"|Movies=") != std::wstring::npos) videos.clear();
    if (!pictures.empty()) walk(pictures, 0, &assets);
    if (!videos.empty() && videos != pictures) walk(videos, 0, &assets);
    // The generation changes whenever a file appears, goes or changes.
    uint64_t h = 1469598103934665603ULL;
    for (const Asset& a : assets) {
        for (wchar_t c : a.path) h = (h ^ static_cast<uint64_t>(c)) * 1099511628211ULL;
        h = (h ^ static_cast<uint64_t>(a.modified)) * 1099511628211ULL;
        h = (h ^ static_cast<uint64_t>(a.size)) * 1099511628211ULL;
    }
    g_assets = std::move(assets);
    g_generation = static_cast<int64_t>(h & 0x7fffffffffffffffULL) | 1;
    g_scanned = now;
    return g_assets;
}

void image_size(IWICImagingFactory* factory, const std::wstring& path, UINT* w, UINT* h) {
    Com<IWICBitmapDecoder> decoder;
    Com<IWICBitmapFrameDecode> frame;
    if (SUCCEEDED(factory->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnDemand,
                                                     &decoder)) &&
        SUCCEEDED(decoder->GetFrame(0, &frame))) {
        frame->GetSize(w, h);
    }
}

jint JNICALL Photos_status(JNIEnv*, jclass) { return kAuthorized; }
jboolean JNICALL Photos_canPrompt(JNIEnv*, jclass) { return JNI_FALSE; }
jboolean JNICALL Photos_requestAuth(JNIEnv*, jclass) { return JNI_TRUE; }
jlong JNICALL Photos_generation(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> hold(g_lock);
    scan();
    return g_generation;
}

jobjectArray JNICALL Photos_list(JNIEnv* env, jclass, jboolean images, jboolean videos) {
    std::vector<Asset> assets;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        assets = scan();
    }
    ComScope com;
    Com<IWICImagingFactory> factory;
    CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory));
    std::vector<std::string> rows;
    for (const Asset& a : assets) {
        if (a.kind->image ? !images : !videos) continue;
        UINT w = 0, h = 0;
        if (a.kind->image && factory.p) image_size(factory.p, a.path, &w, &h);
        char nums[128];
        std::snprintf(nums, sizeof(nums), "\t%d\t%lld\t%lld\t%u\t%u\t0\t", a.kind->image ? 1 : 3,
                      static_cast<long long>(a.created), static_cast<long long>(a.modified), w, h);
        rows.push_back(utf8(a.path) + nums + utf8(a.name) + "\t" + a.kind->mime);
    }
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(rows.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; out && i < rows.size(); ++i) {
        jstring s = env->NewStringUTF(rows[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}

jstring JNICALL Photos_export(JNIEnv* env, jclass, jstring id, jstring path) {
    if (CopyFileW(wide(env, id).c_str(), wide(env, path).c_str(), FALSE)) return nullptr;
    char err[64];
    std::snprintf(err, sizeof(err), "CopyFile failed: %lu", GetLastError());
    return env->NewStringUTF(err);
}

// The shell's thumbnail of the asset, fitted into w x h, as a JPEG file.
jboolean JNICALL Photos_thumbnail(JNIEnv* env, jclass, jstring id, jint w, jint h, jstring path) {
    if (w <= 0 || h <= 0) return JNI_FALSE;
    ComScope com;
    Com<IShellItemImageFactory> item;
    HBITMAP bmp = nullptr;
    if (FAILED(SHCreateItemFromParsingName(wide(env, id).c_str(), nullptr, IID_PPV_ARGS(&item))) ||
        FAILED(item->GetImage(SIZE{w, h}, SIIGBF_RESIZETOFIT | SIIGBF_BIGGERSIZEOK, &bmp))) {
        return JNI_FALSE;
    }
    bool ok = false;
    {
        Com<IWICImagingFactory> factory;
        Com<IWICBitmap> bitmap;
        Com<IWICStream> stream;
        Com<IWICBitmapEncoder> encoder;
        Com<IWICBitmapFrameEncode> frame;
        Com<IWICFormatConverter> rgb;
        UINT bw = 0, bh = 0;
        WICPixelFormatGUID format = GUID_WICPixelFormat24bppBGR;
        ok = SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory))) &&
             SUCCEEDED(factory->CreateBitmapFromHBITMAP(bmp, nullptr, WICBitmapIgnoreAlpha, &bitmap)) &&
             SUCCEEDED(bitmap->GetSize(&bw, &bh)) && SUCCEEDED(factory->CreateFormatConverter(&rgb)) &&
             SUCCEEDED(rgb->Initialize(bitmap.p, GUID_WICPixelFormat24bppBGR, WICBitmapDitherTypeNone, nullptr, 0.0,
                                       WICBitmapPaletteTypeCustom)) &&
             SUCCEEDED(factory->CreateStream(&stream)) &&
             SUCCEEDED(stream->InitializeFromFilename(wide(env, path).c_str(), GENERIC_WRITE)) &&
             SUCCEEDED(factory->CreateEncoder(GUID_ContainerFormatJpeg, nullptr, &encoder)) &&
             SUCCEEDED(encoder->Initialize(stream.p, WICBitmapEncoderNoCache)) &&
             SUCCEEDED(encoder->CreateNewFrame(&frame, nullptr)) && SUCCEEDED(frame->Initialize(nullptr)) &&
             SUCCEEDED(frame->SetSize(bw, bh)) && SUCCEEDED(frame->SetPixelFormat(&format)) &&
             IsEqualGUID(format, GUID_WICPixelFormat24bppBGR) && SUCCEEDED(frame->WriteSource(rgb.p, nullptr)) &&
             SUCCEEDED(frame->Commit()) && SUCCEEDED(encoder->Commit());
    }
    DeleteObject(bmp);
    return ok ? JNI_TRUE : JNI_FALSE;
}

constexpr const char* kCls = "android.provider.MediaStoreProvider";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativePhotosStatus", "()I", Photos_status),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosCanPrompt", "()Z", Photos_canPrompt),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosRequestAuth", "()Z", Photos_requestAuth),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosGeneration", "()J", Photos_generation),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosList", "(ZZ)[Ljava/lang/String;", Photos_list),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosExport", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
                       Photos_export),
    WINAPK_HOST_NATIVE(kCls, "nativePhotosThumbnail", "(Ljava/lang/String;IILjava/lang/String;)Z", Photos_thumbnail),
};

}  // namespace

HostMethods native_photos_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
