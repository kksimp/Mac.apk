// native_image.cpp -- com.macapk.loader.NativeImageCodec on Windows.
//
// BitmapFactory falls back to this when Java's ImageIO cannot read an image.
// The common case is WebP, which aapt has produced for PNG and JPEG drawables
// since Android Studio 2.3, so most of a modern APK's raster assets; then
// HEIC and anything else the platform reads. Upstream uses macOS's ImageIO
// (CGImageSource). Here WebP goes to libwebp, which is always there, and the
// rest to WIC, which reads what Windows has codecs for (HEIC with the HEIF
// extension, WebP too on recent Windows).
//
// Result contract (NativeImageCodec.java): [width, height, then 0xAARRGGBB
// pixels row by row, not premultiplied], or null.

#include "host_natives.h"

#include <cstdint>
#include <cstring>
#include <vector>

#include <windows.h>
#include <wincodec.h>

#include <webp/decode.h>

namespace winapk::host {

namespace {

jintArray make_result(JNIEnv* env, int w, int h, const uint8_t* bgra, size_t stride) {
    const jsize n = 2 + w * h;
    jintArray out = env->NewIntArray(n);
    if (!out) return nullptr;
    const jint dims[2] = {w, h};
    env->SetIntArrayRegion(out, 0, 2, dims);
    // BGRA bytes in memory are 0xAARRGGBB as a little-endian 32-bit value.
    for (int y = 0; y < h; ++y) {
        env->SetIntArrayRegion(out, 2 + y * w, w, reinterpret_cast<const jint*>(bgra + y * stride));
    }
    return out;
}

bool is_webp(const uint8_t* p, size_t n) {
    return n >= 12 && std::memcmp(p, "RIFF", 4) == 0 && std::memcmp(p + 8, "WEBP", 4) == 0;
}

jintArray decode_webp(JNIEnv* env, const uint8_t* p, size_t n) {
    int w = 0, h = 0;
    uint8_t* bgra = WebPDecodeBGRA(p, n, &w, &h);
    if (!bgra) return nullptr;
    jintArray out = make_result(env, w, h, bgra, static_cast<size_t>(w) * 4);
    WebPFree(bgra);
    return out;
}

template <typename T>
struct Com {
    T* p = nullptr;
    ~Com() {
        if (p) p->Release();
    }
    T** operator&() { return &p; }
    T* operator->() { return p; }
};

jintArray decode_wic(JNIEnv* env, const uint8_t* p, size_t n) {
    // The calling thread may already be in an apartment; either kind works.
    const HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    jintArray out = nullptr;
    {
        Com<IWICImagingFactory> factory;
        Com<IWICStream> stream;
        Com<IWICBitmapDecoder> decoder;
        Com<IWICBitmapFrameDecode> frame;
        Com<IWICFormatConverter> converter;
        UINT w = 0, h = 0;
        if (SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                       IID_PPV_ARGS(&factory))) &&
            SUCCEEDED(factory->CreateStream(&stream)) &&
            SUCCEEDED(stream->InitializeFromMemory(const_cast<BYTE*>(p), static_cast<DWORD>(n))) &&
            SUCCEEDED(factory->CreateDecoderFromStream(stream.p, nullptr, WICDecodeMetadataCacheOnDemand, &decoder)) &&
            SUCCEEDED(decoder->GetFrame(0, &frame)) && SUCCEEDED(factory->CreateFormatConverter(&converter)) &&
            SUCCEEDED(converter->Initialize(frame.p, GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone, nullptr,
                                            0.0, WICBitmapPaletteTypeCustom)) &&
            SUCCEEDED(converter->GetSize(&w, &h)) && w > 0 && h > 0 && w < 32768 && h < 32768) {
            std::vector<uint8_t> px(static_cast<size_t>(w) * h * 4);
            if (SUCCEEDED(converter->CopyPixels(nullptr, w * 4, static_cast<UINT>(px.size()), px.data()))) {
                out = make_result(env, static_cast<int>(w), static_cast<int>(h), px.data(), static_cast<size_t>(w) * 4);
            }
        }
    }
    if (SUCCEEDED(init)) CoUninitialize();
    return out;
}

jintArray JNICALL Codec_decode(JNIEnv* env, jclass, jbyteArray data) {
    if (!data) return nullptr;
    const jsize n = env->GetArrayLength(data);
    if (n <= 0) return nullptr;
    std::vector<uint8_t> bytes(static_cast<size_t>(n));
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    if (is_webp(bytes.data(), bytes.size())) {
        if (jintArray r = decode_webp(env, bytes.data(), bytes.size())) return r;
    }
    return decode_wic(env, bytes.data(), bytes.size());
}

constexpr const char* kCls = "com.macapk.loader.NativeImageCodec";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "decode", "([B)[I", Codec_decode),
};

}  // namespace

HostMethods native_image_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
