// native_wallpaper.cpp -- com.macapk.loader.monet.HostWallpaper on Windows:
// the desktop wallpaper as the seed for Material You's dynamic colours, as
// upstream's wallpaper_bridge.mm takes it from the Mac desktop picture.
//
// Windows' placement styles map onto the NSImageScaling contract the Java
// side speaks: Fill and Span are proportional with clipping, Fit is
// proportional without, Stretch is axes-independent, Center and Tile are the
// picture at its own size.

#include "host_natives.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include <windows.h>
#include <wincodec.h>

namespace winapk::host {

namespace {

std::string utf8(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}
std::wstring wide(const std::string& s) {
    if (s.empty()) return {};
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<size_t>(n), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &w[0], n);
    return w;
}

std::wstring desktop_value(const wchar_t* name) {
    wchar_t buf[64] = {};
    DWORD size = sizeof(buf);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Control Panel\\Desktop", name, RRF_RT_REG_SZ, nullptr, buf, &size) !=
        ERROR_SUCCESS) {
        return {};
    }
    return buf;
}

// UTF-8 "path\tscaling\tallowClipping\tfillArgb\tscreenPxW\tscreenPxH", or null.
jbyteArray JNICALL Wallpaper_desktopInfo(JNIEnv* env, jclass) {
    wchar_t path[MAX_PATH] = {};
    if (!SystemParametersInfoW(SPI_GETDESKWALLPAPER, MAX_PATH, path, 0) || !path[0]) return nullptr;
    const int style = _wtoi(desktop_value(L"WallpaperStyle").c_str());
    const bool tile = desktop_value(L"TileWallpaper") == L"1";
    int scaling = 3, clip = 1;  // Fill (10) and Span (22)
    if (tile || style == 0) scaling = 2, clip = 0;  // Center, Tile
    else if (style == 2) scaling = 1, clip = 0;     // Stretch
    else if (style == 6) scaling = 3, clip = 0;     // Fit
    const COLORREF bg = GetSysColor(COLOR_DESKTOP);
    const unsigned argb = 0xff000000u | (GetRValue(bg) << 16) | (GetGValue(bg) << 8) | GetBValue(bg);
    char tail[96];
    std::snprintf(tail, sizeof(tail), "\t%d\t%d\t%d\t%d\t%d", scaling, clip, static_cast<int>(argb),
                  GetSystemMetrics(SM_CXSCREEN), GetSystemMetrics(SM_CYSCREEN));
    const std::string s = utf8(path) + tail;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
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

bool decode_bgra(const std::wstring& path, UINT* w, UINT* h, std::vector<uint32_t>* px) {
    const HRESULT init = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    bool ok = false;
    {
        Com<IWICImagingFactory> factory;
        Com<IWICBitmapDecoder> decoder;
        Com<IWICBitmapFrameDecode> frame;
        Com<IWICFormatConverter> converter;
        if (SUCCEEDED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory))) &&
            SUCCEEDED(factory->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ,
                                                         WICDecodeMetadataCacheOnDemand, &decoder)) &&
            SUCCEEDED(decoder->GetFrame(0, &frame)) && SUCCEEDED(factory->CreateFormatConverter(&converter)) &&
            SUCCEEDED(converter->Initialize(frame.p, GUID_WICPixelFormat32bppBGRA, WICBitmapDitherTypeNone, nullptr, 0.0,
                                            WICBitmapPaletteTypeCustom)) &&
            SUCCEEDED(converter->GetSize(w, h)) && *w > 0 && *h > 0 &&
            static_cast<uint64_t>(*w) * *h <= 8192ull * 8192ull) {
            px->resize(static_cast<size_t>(*w) * *h);
            ok = SUCCEEDED(converter->CopyPixels(nullptr, *w * 4, static_cast<UINT>(px->size() * 4),
                                                  reinterpret_cast<BYTE*>(px->data())));
        }
    }
    if (SUCCEEDED(init)) CoUninitialize();
    return ok;
}

// The desktop as the screen shows it (sw x sh), nearest-sampled to ow x oh,
// as 0xAARRGGBB. Placement as in wallpaper_bridge.mm's place_on_screen.
jintArray JNICALL Wallpaper_render(JNIEnv* env, jclass, jbyteArray jpath, jint scaling, jint allow_clipping,
                                   jint fill, jint sw, jint sh, jint ow, jint oh) {
    if (!jpath || sw <= 0 || sh <= 0 || ow <= 0 || oh <= 0 || static_cast<int64_t>(ow) * oh > 8192LL * 8192LL) {
        return nullptr;
    }
    std::string path(static_cast<size_t>(env->GetArrayLength(jpath)), '\0');
    env->GetByteArrayRegion(jpath, 0, static_cast<jsize>(path.size()), reinterpret_cast<jbyte*>(&path[0]));
    UINT iw = 0, ih = 0;
    std::vector<uint32_t> img;
    if (!decode_bgra(wide(path), &iw, &ih, &img)) return nullptr;

    double w = iw, h = ih;
    switch (scaling) {
        case 1: w = sw, h = sh; break;  // stretch
        case 2: break;                  // own size, centred
        case 0: {                       // shrink only
            const double s = std::min(sw / static_cast<double>(iw), sh / static_cast<double>(ih));
            if (s < 1.0) w = iw * s, h = ih * s;
            break;
        }
        default: {
            const double s = allow_clipping ? std::max(sw / static_cast<double>(iw), sh / static_cast<double>(ih))
                                            : std::min(sw / static_cast<double>(iw), sh / static_cast<double>(ih));
            w = iw * s, h = ih * s;
            break;
        }
    }
    const double x0 = (sw - w) / 2.0, y0 = (sh - h) / 2.0;
    std::vector<jint> out(static_cast<size_t>(ow) * oh);
    for (jint oy = 0; oy < oh; ++oy) {
        const double sy = (oy + 0.5) * sh / oh;
        for (jint ox = 0; ox < ow; ++ox) {
            const double sx = (ox + 0.5) * sw / ow;
            jint v = fill;
            if (sx >= x0 && sx < x0 + w && sy >= y0 && sy < y0 + h) {
                const UINT ix = std::min<UINT>(iw - 1, static_cast<UINT>((sx - x0) * iw / w));
                const UINT iy = std::min<UINT>(ih - 1, static_cast<UINT>((sy - y0) * ih / h));
                v = static_cast<jint>(img[static_cast<size_t>(iy) * iw + ix]);  // BGRA bytes = 0xAARRGGBB
            }
            out[static_cast<size_t>(oy) * ow + ox] = v;
        }
    }
    jintArray r = env->NewIntArray(static_cast<jsize>(out.size()));
    if (r) env->SetIntArrayRegion(r, 0, static_cast<jsize>(out.size()), out.data());
    return r;
}

constexpr const char* kCls = "com.macapk.loader.monet.HostWallpaper";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeDesktopInfo", "()[B", Wallpaper_desktopInfo),
    WINAPK_HOST_NATIVE(kCls, "nativeRender", "([BIIIIIII)[I", Wallpaper_render),
};

}  // namespace

HostMethods native_wallpaper_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
