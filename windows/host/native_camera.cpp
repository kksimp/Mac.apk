// native_camera.cpp -- com.macapk.loader.CameraBridge on Windows: the PC's
// webcams through Media Foundation (upstream: camera_capture.mm on
// AVFoundation). camera2, android.hardware.Camera, ImageReader and
// SurfaceTexture sit on top of these natives in runtime/java.
//
// Devices are the video capture sources MFEnumDeviceSources lists, each offered
// to the app as a back and a front camera (see kRolesPerDevice). A source
// reader with advanced
// video processing turns whatever the device sends (YUY2, MJPG, NV12...) into
// NV12, which a capture thread copies into a frame of our own: Y rows, then
// interleaved CbCr rows, both `stride` bytes wide. That is the semiplanar
// YUV_420_888 layout CameraBridge describes -- plane 1 (U) and plane 2 (V) are
// views of the same chroma plane one byte apart, pixel stride 2. Owning the
// copy (rather than handing out the locked IMFMediaBuffer) lets a held Image,
// the newest frame for SurfaceTexture and the capture thread each keep a frame
// alive without pinning the reader's buffers.
//
// Frames are full range (JFIF), which is what Android's YUV consumers
// (YuvImage, the common shaders) assume. Media Foundation hands out
// video-range NV12 unless asked otherwise; when the converter will not do the
// expansion itself, the copy does it.
//
// Windows does not prompt a desktop program for the camera: the privacy
// switches (Settings > Privacy > Camera) either let it open the device or not,
// so nativeAuthStatus reads them and nativeRequestAuth has nothing to ask.

#include "host_natives.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cwchar>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mfreadwrite.h>

namespace winapk::host {

namespace {

// CameraBridge constants (AOSP's LENS_FACING_FRONT; AVAuthorizationStatus).
constexpr jint kFacingFront = 0, kFacingBack = 1;
constexpr jint kAuthDenied = 2, kAuthAuthorized = 3;
constexpr DWORD kStream = static_cast<DWORD>(MF_SOURCE_READER_FIRST_VIDEO_STREAM);

template <typename T>
struct Com {
    T* p = nullptr;
    Com() = default;
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    ~Com() { reset(); }
    void reset() {
        if (p) p->Release();
        p = nullptr;
    }
    T** operator&() { return &p; }
    T* operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

// Media Foundation objects are free-threaded, but the calls that create them
// need COM on the calling thread, and a Java thread may already be a
// single-threaded apartment (or not initialized at all). Running those calls
// on a short-lived MTA thread of our own sidesteps both.
template <typename F>
void on_mta(F&& fn) {
    std::thread t([&] {
        const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        fn();
        if (SUCCEEDED(co)) CoUninitialize();
    });
    t.join();
}

void mf_startup() {
    static std::once_flag once;
    std::call_once(once, [] { MFStartup(MF_VERSION, MFSTARTUP_LITE); });
}

std::string utf8(const wchar_t* w) {
    if (!w) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return {};
    std::string s(static_cast<size_t>(n - 1), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

// The two camera switches a desktop program is subject to: "Camera access"
// (the device-wide one, per machine or per user) and "Let desktop apps access
// your camera" (NonPackaged). Either one set to Deny makes the device fail to
// open, so it is reported as denied up front instead.
bool camera_allowed() {
    const wchar_t* base =
        L"Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\webcam";
    const wchar_t* desktop =
        L"Software\\Microsoft\\Windows\\CurrentVersion\\CapabilityAccessManager\\ConsentStore\\webcam\\NonPackaged";
    const std::pair<HKEY, const wchar_t*> keys[] = {
        {HKEY_LOCAL_MACHINE, base}, {HKEY_CURRENT_USER, base}, {HKEY_CURRENT_USER, desktop}};
    for (const auto& [root, key] : keys) {
        wchar_t value[32] = {};
        DWORD size = sizeof(value);
        if (RegGetValueW(root, key, L"Value", RRF_RT_REG_SZ, nullptr, value, &size) == ERROR_SUCCESS &&
            lstrcmpiW(value, L"Deny") == 0) {
            return false;
        }
    }
    return true;
}

// ---- devices ----------------------------------------------------------------

struct Format {
    int width = 0, height = 0;
    double min_fps = 0, max_fps = 0;
};

struct Device {
    std::wstring link;  // symbolic link: stable, unique, what MFCreateDeviceSource opens
    std::string id, name;
    bool probed = false;
    std::vector<Format> formats;  // distinct sizes, largest first
};

std::mutex g_devices_lock;
std::vector<Device> g_devices;
std::chrono::steady_clock::time_point g_enumerated_at;

// The device subtypes the source reader's converters are known to turn into
// NV12. Anything else (H.264 webcams' second stream, depth, IR) is not offered.
bool convertible(const GUID& subtype) {
    for (const GUID& g : {MFVideoFormat_NV12, MFVideoFormat_YUY2, MFVideoFormat_MJPG, MFVideoFormat_I420,
                          MFVideoFormat_IYUV, MFVideoFormat_YV12, MFVideoFormat_UYVY, MFVideoFormat_RGB24,
                          MFVideoFormat_RGB32}) {
        if (subtype == g) return true;
    }
    return false;
}

// Lower is better at equal size and rate: the formats needing no decode.
int subtype_rank(const GUID& subtype) {
    if (subtype == MFVideoFormat_NV12) return 0;
    if (subtype == MFVideoFormat_YUY2) return 1;
    if (subtype == MFVideoFormat_MJPG) return 3;
    return 2;
}

double type_fps(IMFMediaType* t, const GUID& key) {
    UINT32 num = 0, den = 0;
    if (FAILED(MFGetAttributeRatio(t, key, &num, &den)) || den == 0) return 0;
    return static_cast<double>(num) / den;
}

// Opens the device as a media source and a source reader over it. The reader
// converts to NV12 (MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING inserts
// the MJPG decoder and the color converter as needed).
bool open_reader(const std::wstring& link, Com<IMFMediaSource>& source, Com<IMFSourceReader>& reader) {
    mf_startup();
    Com<IMFAttributes> attrs;
    if (FAILED(MFCreateAttributes(&attrs, 2)) ||
        FAILED(attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE, MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID)) ||
        FAILED(attrs->SetString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, link.c_str())) ||
        FAILED(MFCreateDeviceSource(attrs.p, &source))) {
        return false;
    }
    Com<IMFAttributes> rattrs;
    if (FAILED(MFCreateAttributes(&rattrs, 2)) ||
        FAILED(rattrs->SetUINT32(MF_SOURCE_READER_ENABLE_ADVANCED_VIDEO_PROCESSING, TRUE)) ||
        FAILED(rattrs->SetUINT32(MF_SOURCE_READER_DISCONNECT_MEDIASOURCE_ON_SHUTDOWN, TRUE)) ||
        FAILED(MFCreateSourceReaderFromMediaSource(source.p, rattrs.p, &reader))) {
        source->Shutdown();
        source.reset();
        return false;
    }
    return true;
}

// The sizes and frame-rate ranges the device advertises. Opening the device is
// the only way Media Foundation lists them, so it is done once per device, on
// demand, and also serves as open()'s proof that the device can be opened.
std::vector<Format> probe_formats(const std::wstring& link) {
    std::vector<Format> out;
    on_mta([&] {
        Com<IMFMediaSource> source;
        Com<IMFSourceReader> reader;
        if (!open_reader(link, source, reader)) return;
        for (DWORD i = 0;; ++i) {
            Com<IMFMediaType> t;
            if (FAILED(reader->GetNativeMediaType(kStream, i, &t))) break;
            GUID subtype{};
            UINT32 w = 0, h = 0;
            if (FAILED(t->GetGUID(MF_MT_SUBTYPE, &subtype)) || !convertible(subtype) ||
                FAILED(MFGetAttributeSize(t.p, MF_MT_FRAME_SIZE, &w, &h)) || w == 0 || h == 0 || (w | h) & 1) {
                continue;
            }
            const double fps = type_fps(t.p, MF_MT_FRAME_RATE);
            double lo = type_fps(t.p, MF_MT_FRAME_RATE_RANGE_MIN), hi = type_fps(t.p, MF_MT_FRAME_RATE_RANGE_MAX);
            if (lo <= 0) lo = fps;
            if (hi <= 0) hi = fps;
            auto it = std::find_if(out.begin(), out.end(), [&](const Format& f) {
                return f.width == static_cast<int>(w) && f.height == static_cast<int>(h);
            });
            if (it == out.end()) {
                out.push_back({static_cast<int>(w), static_cast<int>(h), lo, hi});
            } else {
                it->min_fps = std::min(it->min_fps, lo);
                it->max_fps = std::max(it->max_fps, hi);
            }
        }
        reader.reset();
        source->Shutdown();
    });
    std::sort(out.begin(), out.end(), [](const Format& a, const Format& b) {
        return static_cast<long long>(a.width) * a.height > static_cast<long long>(b.width) * b.height;
    });
    return out;
}

// Re-lists the devices (at most once a second: camera2 asks for the count on
// nearly every call). Devices still present keep their probed formats.
void refresh_devices(bool force) {
    std::lock_guard<std::mutex> hold(g_devices_lock);
    const auto now = std::chrono::steady_clock::now();
    if (!force && g_enumerated_at.time_since_epoch().count() != 0 && now - g_enumerated_at < std::chrono::seconds(1)) {
        return;
    }
    g_enumerated_at = now;
    std::vector<Device> found;
    on_mta([&] {
        mf_startup();
        Com<IMFAttributes> attrs;
        IMFActivate** list = nullptr;
        UINT32 count = 0;
        if (FAILED(MFCreateAttributes(&attrs, 1)) ||
            FAILED(attrs->SetGUID(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE,
                                  MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_GUID)) ||
            FAILED(MFEnumDeviceSources(attrs.p, &list, &count))) {
            return;
        }
        for (UINT32 i = 0; i < count; ++i) {
            wchar_t* link = nullptr;
            wchar_t* name = nullptr;
            UINT32 len = 0;
            if (SUCCEEDED(list[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_SOURCE_TYPE_VIDCAP_SYMBOLIC_LINK, &link,
                                                      &len))) {
                list[i]->GetAllocatedString(MF_DEVSOURCE_ATTRIBUTE_FRIENDLY_NAME, &name, &len);
                Device d;
                d.link = link;
                d.id = utf8(link);
                d.name = name ? utf8(name) : "Camera";
                found.push_back(std::move(d));
            }
            CoTaskMemFree(link);
            CoTaskMemFree(name);
            list[i]->Release();
        }
        CoTaskMemFree(list);
    });
    for (Device& d : found) {
        for (Device& old : g_devices) {
            if (old.link == d.link) {
                d.probed = old.probed;
                d.formats = std::move(old.formats);
            }
        }
    }
    g_devices = std::move(found);
}

// Every webcam is offered twice: camera 2k faces BACK and 2k+1 faces FRONT,
// both streaming the same device. A phone has both and apps are written for
// that: QR scanners and CameraX's DEFAULT_BACK_CAMERA ask for the back lens
// (Camera.open() returns null without one), selfie and video-call screens for
// the front. A PC's single webcam serves both roles; Windows shares the device
// between the two if an app opens both.
constexpr int kRolesPerDevice = 2;

// The webcam behind camera `index`, or -1. Under g_devices_lock.
int physical_index(int index) {
    return index >= 0 && static_cast<size_t>(index / kRolesPerDevice) < g_devices.size() ? index / kRolesPerDevice
                                                                                            : -1;
}

// A copy of the webcam behind camera `index`, its formats probed. False if
// there is no such camera.
bool device_at(int index, Device* out) {
    std::lock_guard<std::mutex> hold(g_devices_lock);
    const int phys = physical_index(index);
    if (phys < 0) return false;
    Device& d = g_devices[static_cast<size_t>(phys)];
    if (!d.probed) {
        d.formats = probe_formats(d.link);
        // A device that failed to open (in use exclusively, unplugged) is
        // probed again next time rather than remembered as format-less.
        d.probed = !d.formats.empty();
    }
    *out = d;
    return true;
}

bool format_at(int device, int format, Format* out) {
    Device d;
    if (!device_at(device, &d) || format < 0 || static_cast<size_t>(format) >= d.formats.size()) return false;
    *out = d.formats[static_cast<size_t>(format)];
    return true;
}

// ---- sessions -----------------------------------------------------------------

// One NV12 frame: `height` rows of Y, then height/2 rows of CbCr pairs, all
// `stride` bytes apart. Always full range.
struct Frame {
    std::vector<uint8_t> data;
    int width = 0, height = 0, stride = 0;
    int64_t pts = 0;
    int64_t number = 0;  // set when acquired
};

int64_t now_ns() {
    // steady_clock is QueryPerformanceCounter here, the clock System.nanoTime
    // reads, so timestamps are at least comparable with Java's.
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// Video range (Y 16..235, C 16..240) to full range, by table.
struct RangeTables {
    uint8_t luma[256], chroma[256];
    RangeTables() {
        for (int v = 0; v < 256; ++v) {
            luma[v] = static_cast<uint8_t>(std::clamp((v - 16) * 255 / 219, 0, 255));
            chroma[v] = static_cast<uint8_t>(std::clamp(128 + (v - 128) * 255 / 224, 0, 255));
        }
    }
};
const RangeTables& range_tables() {
    static const RangeTables t;
    return t;
}

class Session {
public:
    Session(int device, std::wstring link, std::vector<Format> formats)
        : device_(device), link_(std::move(link)), formats_(std::move(formats)) {
        choose(1280, 720, 30);
    }
    ~Session() { stop(); }

    int device() const { return device_; }

    // Picks the advertised size closest in area to the request and clamps the
    // rate into its range -- what the app gets, even when it asked for a size
    // the camera does not have.
    bool configure(int w, int h, int fps) {
        if (formats_.empty()) return false;
        const bool was_running = running();
        if (was_running) stop();
        choose(w, h, fps);
        return was_running ? start() : true;
    }
    int width() {
        std::lock_guard<std::mutex> hold(lock_);
        return width_;
    }
    int height() {
        std::lock_guard<std::mutex> hold(lock_);
        return height_;
    }

    // Starts the capture thread and waits for the device to open (or fail).
    bool start() {
        std::lock_guard<std::mutex> guard(run_lock_);
        if (thread_.joinable()) return true;
        {
            std::lock_guard<std::mutex> hold(state_lock_);
            opened_ = failed_ = done_ = false;
        }
        stopping_ = false;
        thread_ = std::thread([this] { run(); });
        std::unique_lock<std::mutex> wait(state_lock_);
        state_cv_.wait_for(wait, std::chrono::seconds(10), [this] { return opened_ || failed_; });
        const bool ok = opened_;
        wait.unlock();
        if (!ok) stop_locked();
        return ok;
    }

    void stop() {
        std::lock_guard<std::mutex> guard(run_lock_);
        stop_locked();
    }

    bool running() {
        std::lock_guard<std::mutex> guard(run_lock_);
        return thread_.joinable();
    }

    int64_t delivered() const { return delivered_.load(); }

    // ---- frame handoff (ImageReader.acquireLatestImage / Image.close) ----
    bool acquire() {
        std::lock_guard<std::mutex> hold(lock_);
        if (held_ || !pending_) return false;
        held_ = std::move(pending_);
        held_->number = ++acquired_;
        return true;
    }
    void release() {
        std::lock_guard<std::mutex> hold(lock_);
        held_.reset();
    }
    std::shared_ptr<Frame> held() {
        std::lock_guard<std::mutex> hold(lock_);
        return held_;
    }
    std::shared_ptr<Frame> latest() {
        std::lock_guard<std::mutex> hold(lock_);
        return latest_;
    }

private:
    void choose(int w, int h, int fps) {
        const long long want = static_cast<long long>(std::max(w, 1)) * std::max(h, 1);
        const Format* best = nullptr;
        for (const Format& f : formats_) {
            const long long delta = std::llabs(static_cast<long long>(f.width) * f.height - want);
            if (!best || delta < std::llabs(static_cast<long long>(best->width) * best->height - want)) best = &f;
        }
        std::lock_guard<std::mutex> hold(lock_);
        if (!best) return;
        width_ = best->width;
        height_ = best->height;
        fps_ = fps > 0 ? std::clamp(static_cast<double>(fps), best->min_fps, std::max(best->min_fps, best->max_fps))
                       : best->max_fps;
    }

    void stop_locked() {
        if (!thread_.joinable()) return;
        stopping_ = true;
        // ReadSample blocks until the next frame, normally a frame time. A
        // camera that stops delivering would block it for good, so after a
        // grace period the source is shut down, which fails the read.
        {
            std::unique_lock<std::mutex> wait(state_lock_);
            if (!state_cv_.wait_for(wait, std::chrono::seconds(2), [this] { return done_; }) && source_) {
                source_->Shutdown();
            }
        }
        thread_.join();
        // A stopped camera must not keep serving its last frame as if live.
        std::lock_guard<std::mutex> hold(lock_);
        pending_.reset();
        latest_.reset();
    }

    void signal(bool opened) {
        std::lock_guard<std::mutex> hold(state_lock_);
        (opened ? opened_ : failed_) = true;
        state_cv_.notify_all();
    }

    // The device type matching the chosen size whose rate is the lowest at or
    // above the wanted one (else the highest), preferring formats that need no
    // decode.
    bool select_native_type(IMFSourceReader* reader, int w, int h, double fps) {
        Com<IMFMediaType> best;
        double best_fps = 0;
        int best_rank = 0;
        for (DWORD i = 0;; ++i) {
            Com<IMFMediaType> t;
            if (FAILED(reader->GetNativeMediaType(kStream, i, &t))) break;
            GUID subtype{};
            UINT32 tw = 0, th = 0;
            if (FAILED(t->GetGUID(MF_MT_SUBTYPE, &subtype)) || !convertible(subtype) ||
                FAILED(MFGetAttributeSize(t.p, MF_MT_FRAME_SIZE, &tw, &th)) || static_cast<int>(tw) != w ||
                static_cast<int>(th) != h) {
                continue;
            }
            const double f = type_fps(t.p, MF_MT_FRAME_RATE);
            const int rank = subtype_rank(subtype);
            bool better;
            if (!best) {
                better = true;
            } else if ((f >= fps - 0.5) != (best_fps >= fps - 0.5)) {
                better = f >= fps - 0.5;  // reaching the wanted rate beats not reaching it
            } else if (f != best_fps) {
                better = f >= fps - 0.5 ? f < best_fps : f > best_fps;
            } else {
                better = rank < best_rank;
            }
            if (better) {
                best.reset();
                best.p = t.p;
                t.p = nullptr;
                best_fps = f;
                best_rank = rank;
            }
        }
        return best && SUCCEEDED(reader->SetCurrentMediaType(kStream, nullptr, best.p));
    }

    void run() {
        const HRESULT co = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        int w, h;
        double fps;
        {
            std::lock_guard<std::mutex> hold(lock_);
            w = width_;
            h = height_;
            fps = fps_;
        }
        Com<IMFMediaSource> source;
        Com<IMFSourceReader> reader;
        Com<IMFMediaType> out;
        bool ok = open_reader(link_, source, reader);
        if (ok) {
            std::lock_guard<std::mutex> hold(state_lock_);
            source_ = source.p;
        }
        ok = ok && SUCCEEDED(reader->SetStreamSelection(static_cast<DWORD>(MF_SOURCE_READER_ALL_STREAMS), FALSE)) &&
             SUCCEEDED(reader->SetStreamSelection(kStream, TRUE)) && select_native_type(reader.p, w, h, fps) &&
             SUCCEEDED(MFCreateMediaType(&out)) && SUCCEEDED(out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) &&
             SUCCEEDED(out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12)) &&
             SUCCEEDED(MFSetAttributeSize(out.p, MF_MT_FRAME_SIZE, w, h)) &&
             SUCCEEDED(out->SetUINT32(MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_0_255)) &&
             SUCCEEDED(reader->SetCurrentMediaType(kStream, nullptr, out.p));
        int stride = 0;
        bool video_range = true;
        if (ok) ok = read_output_type(reader.p, &w, &h, &stride, &video_range);
        std::fprintf(stderr, "[camera] start %ls: %s %dx%d @%.0ffps%s\n", device_name().c_str(),
                     ok ? "streaming" : "FAILED", w, h, fps, ok && video_range ? " (expanding video range)" : "");
        std::fflush(stderr);
        signal(ok);
        while (ok && !stopping_) {
            DWORD index = 0, flags = 0;
            LONGLONG ts = 0;
            Com<IMFSample> sample;
            if (FAILED(reader->ReadSample(kStream, 0, &index, &flags, &ts, &sample)) ||
                (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_ENDOFSTREAM))) {
                if (!stopping_) std::fprintf(stderr, "[camera] capture ended (device lost or stopped)\n");
                break;
            }
            if ((flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) &&
                !read_output_type(reader.p, &w, &h, &stride, &video_range)) {
                break;
            }
            if (sample) deliver(sample.p, w, h, stride, video_range);
        }
        reader.reset();
        {
            std::lock_guard<std::mutex> hold(state_lock_);
            source_ = nullptr;
        }
        if (source) source->Shutdown();
        source.reset();
        {
            std::lock_guard<std::mutex> hold(state_lock_);
            done_ = true;
            state_cv_.notify_all();
        }
        if (SUCCEEDED(co)) CoUninitialize();
    }

    std::wstring device_name() {
        std::lock_guard<std::mutex> hold(g_devices_lock);
        for (const Device& d : g_devices) {
            if (d.link == link_) {
                const int n = MultiByteToWideChar(CP_UTF8, 0, d.name.c_str(), -1, nullptr, 0);
                std::wstring w(static_cast<size_t>(std::max(n, 1)), L'\0');
                MultiByteToWideChar(CP_UTF8, 0, d.name.c_str(), -1, &w[0], n);
                w.resize(wcslen(w.c_str()));
                return w;
            }
        }
        return L"camera";
    }

    bool read_output_type(IMFSourceReader* reader, int* w, int* h, int* stride, bool* video_range) {
        Com<IMFMediaType> t;
        UINT32 tw = 0, th = 0;
        if (FAILED(reader->GetCurrentMediaType(kStream, &t)) ||
            FAILED(MFGetAttributeSize(t.p, MF_MT_FRAME_SIZE, &tw, &th)) || tw == 0 || th == 0) {
            return false;
        }
        *w = static_cast<int>(tw);
        *h = static_cast<int>(th);
        // NV12 rows are top-down; the default stride is the converter's row
        // pitch when it reports one.
        UINT32 s = 0;
        *stride = SUCCEEDED(t->GetUINT32(MF_MT_DEFAULT_STRIDE, &s)) && static_cast<INT32>(s) >= *w
                      ? static_cast<int>(s)
                      : *w;
        *video_range = MFGetAttributeUINT32(t.p, MF_MT_VIDEO_NOMINAL_RANGE, MFNominalRange_16_235) !=
                       MFNominalRange_0_255;
        std::lock_guard<std::mutex> hold(lock_);
        width_ = *w;
        height_ = *h;
        return true;
    }

    // A frame buffer nobody else holds, from a small pool, or a new one.
    std::shared_ptr<Frame> free_frame() {
        std::lock_guard<std::mutex> hold(lock_);
        for (auto& f : pool_) {
            if (f.use_count() == 1) return f;
        }
        pool_.push_back(std::make_shared<Frame>());
        if (pool_.size() > 6) pool_.erase(pool_.begin());  // only reached when consumers keep many alive
        return pool_.back();
    }

    void deliver(IMFSample* sample, int w, int h, int stride, bool video_range) {
        Com<IMFMediaBuffer> buffer;
        if (FAILED(sample->GetBufferByIndex(0, &buffer))) return;
        Com<IMF2DBuffer> twod;
        BYTE* src = nullptr;
        LONG pitch = 0;
        DWORD len = 0;
        bool locked2d = false;
        if (SUCCEEDED(buffer->QueryInterface(IID_PPV_ARGS(&twod))) && SUCCEEDED(twod->Lock2D(&src, &pitch))) {
            locked2d = true;
        } else if (SUCCEEDED(buffer->Lock(&src, nullptr, &len))) {
            pitch = stride;
            if (static_cast<size_t>(len) < static_cast<size_t>(pitch) * h * 3 / 2) {
                buffer->Unlock();
                return;
            }
        } else {
            return;
        }
        if (pitch < w) {  // bottom-up or malformed: NV12 never is
            if (locked2d) twod->Unlock2D(); else buffer->Unlock();
            return;
        }
        std::shared_ptr<Frame> f = free_frame();
        f->width = w;
        f->height = h;
        f->stride = w;
        f->data.resize(static_cast<size_t>(w) * h * 3 / 2);
        const uint8_t* y = src;
        const uint8_t* c = src + static_cast<size_t>(pitch) * h;
        uint8_t* dy = f->data.data();
        uint8_t* dc = dy + static_cast<size_t>(w) * h;
        const RangeTables& lut = range_tables();
        for (int row = 0; row < h; ++row) {
            const uint8_t* s = y + static_cast<size_t>(row) * pitch;
            uint8_t* d = dy + static_cast<size_t>(row) * w;
            if (video_range) {
                for (int i = 0; i < w; ++i) d[i] = lut.luma[s[i]];
            } else {
                std::memcpy(d, s, static_cast<size_t>(w));
            }
        }
        for (int row = 0; row < h / 2; ++row) {
            const uint8_t* s = c + static_cast<size_t>(row) * pitch;
            uint8_t* d = dc + static_cast<size_t>(row) * w;
            if (video_range) {
                for (int i = 0; i < w; ++i) d[i] = lut.chroma[s[i]];
            } else {
                std::memcpy(d, s, static_cast<size_t>(w));
            }
        }
        if (locked2d) twod->Unlock2D(); else buffer->Unlock();
        f->pts = now_ns();
        f->number = 0;
        {
            std::lock_guard<std::mutex> hold(lock_);
            pending_ = f;  // latest wins: an undrained older frame is dropped
            latest_ = f;
        }
        if (delivered_.fetch_add(1) == 0) {
            std::fprintf(stderr, "[camera] first frame %dx%d\n", w, h);
            std::fflush(stderr);
        }
    }

    const int device_;
    const std::wstring link_;
    const std::vector<Format> formats_;

    std::mutex lock_;  // geometry and frame slots
    int width_ = 0, height_ = 0;
    double fps_ = 30;
    std::shared_ptr<Frame> pending_, latest_, held_;
    std::vector<std::shared_ptr<Frame>> pool_;
    int64_t acquired_ = 0;
    std::atomic<int64_t> delivered_{0};

    std::mutex run_lock_;  // start/stop
    std::thread thread_;
    std::atomic<bool> stopping_{false};
    std::mutex state_lock_;
    std::condition_variable state_cv_;
    bool opened_ = false, failed_ = false, done_ = false;
    IMFMediaSource* source_ = nullptr;  // the capture thread's, for stop's shutdown; under state_lock_
};

std::mutex g_sessions_lock;
jlong g_next = 1;  // positive: CameraBridge reserves negative handles for its virtual camera
std::map<jlong, std::shared_ptr<Session>> g_sessions;

std::shared_ptr<Session> session(jlong h) {
    std::lock_guard<std::mutex> hold(g_sessions_lock);
    auto it = g_sessions.find(h);
    return it == g_sessions.end() ? nullptr : it->second;
}

// ---- JNI ------------------------------------------------------------------------

// No camera is reported while the privacy switches deny access: the device
// could not be opened anyway (CameraBridge then offers its virtual camera).
jint JNICALL Cam_deviceCount(JNIEnv*, jclass) {
    if (!camera_allowed()) {
        static std::atomic<bool> told{false};
        if (!told.exchange(true)) {
            std::fprintf(stderr, "[camera] camera access is off in Windows privacy settings; no camera reported\n");
        }
        return 0;
    }
    refresh_devices(false);
    std::lock_guard<std::mutex> hold(g_devices_lock);
    static std::atomic<int> reported{-1};
    if (reported.exchange(static_cast<int>(g_devices.size())) != static_cast<int>(g_devices.size())) {
        std::fprintf(stderr, "[camera] %zu webcam(s), each offered as a back and a front camera\n", g_devices.size());
    }
    return static_cast<jint>(g_devices.size() * kRolesPerDevice);
}

// The id and name carry the role, so the two cameras of one webcam stay
// distinct (camera2 caches characteristics by id).
jstring device_string(JNIEnv* env, jint index, std::string Device::*field) {
    std::lock_guard<std::mutex> hold(g_devices_lock);
    const int phys = physical_index(index);
    if (phys < 0) return nullptr;
    const bool back = index % kRolesPerDevice == 0;
    const bool id = field == &Device::id;
    const std::string s = g_devices[static_cast<size_t>(phys)].*field +
                          (id ? (back ? "#back" : "#front") : (back ? " (back)" : " (front)"));
    return env->NewStringUTF(s.c_str());
}
jstring JNICALL Cam_deviceId(JNIEnv* env, jclass, jint index) { return device_string(env, index, &Device::id); }
jstring JNICALL Cam_deviceName(JNIEnv* env, jclass, jint index) { return device_string(env, index, &Device::name); }

jint JNICALL Cam_deviceFacing(JNIEnv*, jclass, jint index) {
    std::lock_guard<std::mutex> hold(g_devices_lock);
    if (physical_index(index) < 0) return -1;
    return index % kRolesPerDevice == 0 ? kFacingBack : kFacingFront;
}
// The sensor does not turn with the screen, so no preview rotation is implied.
jint JNICALL Cam_sensorOrientation(JNIEnv*, jclass, jint) { return 0; }

jint JNICALL Cam_formatCount(JNIEnv*, jclass, jint index) {
    Device d;
    return device_at(index, &d) ? static_cast<jint>(d.formats.size()) : 0;
}
jint JNICALL Cam_formatWidth(JNIEnv*, jclass, jint d, jint f) {
    Format fm;
    return format_at(d, f, &fm) ? fm.width : 0;
}
jint JNICALL Cam_formatHeight(JNIEnv*, jclass, jint d, jint f) {
    Format fm;
    return format_at(d, f, &fm) ? fm.height : 0;
}
jdouble JNICALL Cam_formatMinFps(JNIEnv*, jclass, jint d, jint f) {
    Format fm;
    return format_at(d, f, &fm) ? fm.min_fps : 0;
}
jdouble JNICALL Cam_formatMaxFps(JNIEnv*, jclass, jint d, jint f) {
    Format fm;
    return format_at(d, f, &fm) ? fm.max_fps : 0;
}

jint JNICALL Cam_authStatus(JNIEnv*, jclass) { return camera_allowed() ? kAuthAuthorized : kAuthDenied; }
void JNICALL Cam_requestAuth(JNIEnv*, jclass) {}  // nothing to ask: see the file comment

// Opening proves the device opens (the format probe opens it), so a session is
// only handed out for a camera that can actually stream.
jlong JNICALL Cam_open(JNIEnv*, jclass, jint index) {
    if (!camera_allowed()) return 0;
    Device d;
    if (!device_at(index, &d) || d.formats.empty()) {
        std::fprintf(stderr, "[camera] open(%d): no such camera, or it could not be opened\n", index);
        return 0;
    }
    auto s = std::make_shared<Session>(index, d.link, d.formats);
    std::fprintf(stderr, "[camera] open(%d) %s: %zu sizes\n", index, d.name.c_str(), d.formats.size());
    std::lock_guard<std::mutex> hold(g_sessions_lock);
    const jlong h = g_next++;
    g_sessions[h] = s;
    return h;
}
jboolean JNICALL Cam_configure(JNIEnv*, jclass, jlong h, jint w, jint hgt, jint fps) {
    auto s = session(h);
    return s && s->configure(w, hgt, fps) ? JNI_TRUE : JNI_FALSE;
}
jint JNICALL Cam_width(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    return s ? s->width() : 0;
}
jint JNICALL Cam_height(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    return s ? s->height() : 0;
}
jboolean JNICALL Cam_start(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    return s && s->start() ? JNI_TRUE : JNI_FALSE;
}
void JNICALL Cam_stop(JNIEnv*, jclass, jlong h) {
    if (auto s = session(h)) s->stop();
}
void JNICALL Cam_close(JNIEnv*, jclass, jlong h) {
    std::shared_ptr<Session> s;
    {
        std::lock_guard<std::mutex> hold(g_sessions_lock);
        auto it = g_sessions.find(h);
        if (it == g_sessions.end()) return;
        s = it->second;
        g_sessions.erase(it);
    }
    s->release();
    s->stop();
}
jlong JNICALL Cam_framesDelivered(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    return s ? s->delivered() : 0;
}

jboolean JNICALL Cam_acquire(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    return s && s->acquire() ? JNI_TRUE : JNI_FALSE;
}
void JNICALL Cam_release(JNIEnv*, jclass, jlong h) {
    if (auto s = session(h)) s->release();
}

// Reachable bytes of a plane, as Android sizes an Image.Plane buffer: up to the
// last sample of the last row, so the V view (one byte into the chroma plane)
// never runs past the frame.
jobject JNICALL Cam_planeBuffer(JNIEnv* env, jclass, jlong h, jint plane) {
    auto s = session(h);
    auto f = s ? s->held() : nullptr;
    if (!f || plane < 0 || plane > 2) return nullptr;
    uint8_t* base = f->data.data();
    const size_t luma = static_cast<size_t>(f->stride) * f->height;
    if (plane == 0) return env->NewDirectByteBuffer(base, static_cast<jlong>(luma));
    const size_t rows = static_cast<size_t>(f->height) / 2, pairs = static_cast<size_t>(f->width) / 2;
    if (rows == 0 || pairs == 0) return nullptr;
    const size_t extent = static_cast<size_t>(f->stride) * (rows - 1) + 2 * (pairs - 1) + 1;
    // The buffer stays valid until release(): the session's held reference
    // keeps the frame, which is CameraBridge's Image.close() contract.
    return env->NewDirectByteBuffer(base + luma + (plane == 2 ? 1 : 0), static_cast<jlong>(extent));
}
jint JNICALL Cam_rowStride(JNIEnv*, jclass, jlong h, jint plane) {
    auto s = session(h);
    auto f = s ? s->held() : nullptr;
    return f && plane >= 0 && plane <= 2 ? f->stride : 0;
}
jint JNICALL Cam_pixelStride(JNIEnv*, jclass, jlong h, jint plane) {
    auto s = session(h);
    auto f = s ? s->held() : nullptr;
    if (!f || plane < 0 || plane > 2) return 0;
    return plane == 0 ? 1 : 2;
}
jlong JNICALL Cam_frameTimestamp(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    auto f = s ? s->held() : nullptr;
    return f ? f->pts : 0;
}
jlong JNICALL Cam_frameNumber(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    auto f = s ? s->held() : nullptr;
    return f ? f->number : 0;
}

jlong JNICALL Cam_latestTimestamp(JNIEnv*, jclass, jlong h) {
    auto s = session(h);
    auto f = s ? s->latest() : nullptr;
    return f ? std::max<jlong>(f->pts, 1) : 0;
}

// Full-range BT.601 (JFIF) NV12 -> RGB in 8.8 fixed point, row 0 = top row;
// the same math as upstream's latest_frame_convert for '420f'.
template <bool kArgb>
void convert(const Frame& f, void* dst) {
    const uint8_t* yb = f.data.data();
    const uint8_t* cb = yb + static_cast<size_t>(f.stride) * f.height;
    for (int row = 0; row < f.height; ++row) {
        const uint8_t* yp = yb + static_cast<size_t>(row) * f.stride;
        const uint8_t* cp = cb + static_cast<size_t>(row >> 1) * f.stride;
        for (int col = 0; col < f.width; ++col) {
            const int y = yp[col];
            const int d = cp[col & ~1] - 128;  // Cb first: NV12
            const int e = cp[(col & ~1) + 1] - 128;
            const int r = std::clamp(y + ((359 * e + 128) >> 8), 0, 255);
            const int g = std::clamp(y - ((88 * d + 183 * e + 128) >> 8), 0, 255);
            const int b = std::clamp(y + ((454 * d + 128) >> 8), 0, 255);
            const size_t i = static_cast<size_t>(row) * f.width + col;
            if constexpr (kArgb) {
                static_cast<uint32_t*>(dst)[i] = 0xFF000000u | (r << 16) | (g << 8) | b;
            } else {
                uint8_t* o = static_cast<uint8_t*>(dst) + i * 4;
                o[0] = static_cast<uint8_t>(r);
                o[1] = static_cast<uint8_t>(g);
                o[2] = static_cast<uint8_t>(b);
                o[3] = 0xFF;
            }
        }
    }
}

void set_dims(JNIEnv* env, jintArray dims, const Frame& f) {
    if (!dims || env->GetArrayLength(dims) < 2) return;
    const jint d[2] = {f.width, f.height};
    env->SetIntArrayRegion(dims, 0, 2, d);
}

// dims is set whenever a frame exists, so a caller whose buffer is too small
// can grow it and retry.
jlong JNICALL Cam_copyLatestRgba(JNIEnv* env, jclass, jlong h, jobject dst, jintArray dims) {
    auto s = session(h);
    auto f = s ? s->latest() : nullptr;
    if (!f || !dst) return 0;
    set_dims(env, dims, *f);
    void* addr = env->GetDirectBufferAddress(dst);
    const jlong cap = env->GetDirectBufferCapacity(dst);
    if (!addr || cap < static_cast<jlong>(f->width) * f->height * 4) return 0;
    convert<false>(*f, addr);
    return std::max<jlong>(f->pts, 1);
}

// The frame is held by reference before the array is pinned, so nothing inside
// the critical region can block or allocate.
jlong JNICALL Cam_copyLatestArgb(JNIEnv* env, jclass, jlong h, jintArray dst, jintArray dims) {
    auto s = session(h);
    auto f = s ? s->latest() : nullptr;
    if (!f || !dst) return 0;
    set_dims(env, dims, *f);
    if (env->GetArrayLength(dst) < static_cast<jsize>(f->width) * f->height) return 0;
    void* out = env->GetPrimitiveArrayCritical(dst, nullptr);
    if (!out) return 0;
    convert<true>(*f, out);
    env->ReleasePrimitiveArrayCritical(dst, out, 0);
    return std::max<jlong>(f->pts, 1);
}

constexpr const char* kCls = "com.macapk.loader.CameraBridge";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeDeviceCount", "()I", Cam_deviceCount),
    WINAPK_HOST_NATIVE(kCls, "nativeDeviceId", "(I)Ljava/lang/String;", Cam_deviceId),
    WINAPK_HOST_NATIVE(kCls, "nativeDeviceName", "(I)Ljava/lang/String;", Cam_deviceName),
    WINAPK_HOST_NATIVE(kCls, "nativeDeviceFacing", "(I)I", Cam_deviceFacing),
    WINAPK_HOST_NATIVE(kCls, "nativeDeviceSensorOrientation", "(I)I", Cam_sensorOrientation),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatCount", "(I)I", Cam_formatCount),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatWidth", "(II)I", Cam_formatWidth),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatHeight", "(II)I", Cam_formatHeight),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatMinFps", "(II)D", Cam_formatMinFps),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatMaxFps", "(II)D", Cam_formatMaxFps),
    WINAPK_HOST_NATIVE(kCls, "nativeAuthStatus", "()I", Cam_authStatus),
    WINAPK_HOST_NATIVE(kCls, "nativeRequestAuth", "()V", Cam_requestAuth),
    WINAPK_HOST_NATIVE(kCls, "nativeOpen", "(I)J", Cam_open),
    WINAPK_HOST_NATIVE(kCls, "nativeConfigure", "(JIII)Z", Cam_configure),
    WINAPK_HOST_NATIVE(kCls, "nativeWidth", "(J)I", Cam_width),
    WINAPK_HOST_NATIVE(kCls, "nativeHeight", "(J)I", Cam_height),
    WINAPK_HOST_NATIVE(kCls, "nativeStart", "(J)Z", Cam_start),
    WINAPK_HOST_NATIVE(kCls, "nativeStop", "(J)V", Cam_stop),
    WINAPK_HOST_NATIVE(kCls, "nativeClose", "(J)V", Cam_close),
    WINAPK_HOST_NATIVE(kCls, "nativeFramesDelivered", "(J)J", Cam_framesDelivered),
    WINAPK_HOST_NATIVE(kCls, "nativeAcquire", "(J)Z", Cam_acquire),
    WINAPK_HOST_NATIVE(kCls, "nativeRelease", "(J)V", Cam_release),
    WINAPK_HOST_NATIVE(kCls, "nativePlaneBuffer", "(JI)Ljava/nio/ByteBuffer;", Cam_planeBuffer),
    WINAPK_HOST_NATIVE(kCls, "nativeRowStride", "(JI)I", Cam_rowStride),
    WINAPK_HOST_NATIVE(kCls, "nativePixelStride", "(JI)I", Cam_pixelStride),
    WINAPK_HOST_NATIVE(kCls, "nativeFrameTimestamp", "(J)J", Cam_frameTimestamp),
    WINAPK_HOST_NATIVE(kCls, "nativeFrameNumber", "(J)J", Cam_frameNumber),
    WINAPK_HOST_NATIVE(kCls, "nativeLatestTimestamp", "(J)J", Cam_latestTimestamp),
    WINAPK_HOST_NATIVE(kCls, "nativeCopyLatestRgba", "(JLjava/nio/ByteBuffer;[I)J", Cam_copyLatestRgba),
    WINAPK_HOST_NATIVE(kCls, "nativeCopyLatestArgb", "(J[I[I)J", Cam_copyLatestArgb),
};

}  // namespace

HostMethods native_camera_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
