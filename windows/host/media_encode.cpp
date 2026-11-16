// media_encode.cpp -- see media_encode.h.

#include "media_encode.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <deque>

#include <windows.h>
#include <mfapi.h>
#include <mferror.h>
#include <mfidl.h>
#include <mftransform.h>
#include <strmif.h>

namespace winapk::video {

namespace {

template <typename T>
struct Com {
    T* p = nullptr;
    Com() = default;
    Com(const Com&) = delete;
    Com& operator=(const Com&) = delete;
    ~Com() {
        if (p) p->Release();
    }
    T** operator&() { return &p; }
    T* operator->() { return p; }
    explicit operator bool() const { return p != nullptr; }
};

// As video_decode.cpp's: Media Foundation once for the process, COM on every
// thread that comes here.
bool mf_ready() {
    static const bool started = SUCCEEDED(MFStartup(MF_VERSION, MFSTARTUP_LITE));
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    return started;
}

// ICodecAPI's interface id and property names (strmif.h, codecapi.h), spelled
// out: the headers declare them, but which of them, and which import library,
// defines them differs between toolchains.
const GUID kIidCodecApi = {0x901db4c7, 0x31ce, 0x41a2, {0x85, 0xdc, 0x8f, 0xa0, 0xbf, 0x41, 0xb8, 0xda}};  // IID_ICodecAPI
const GUID kRateControlMode = {0x1c0608e9, 0x370c, 0x4710, {0x8a, 0x58, 0xcb, 0x61, 0x81, 0xc4, 0x24, 0x23}};
const GUID kMeanBitRate = {0xf7222374, 0x2144, 0x4815, {0xb5, 0x50, 0xa3, 0x7f, 0x8e, 0x12, 0xee, 0x52}};
const GUID kGopSize = {0x95f31b26, 0x95a4, 0x41aa, {0x93, 0x03, 0x24, 0x6a, 0x7f, 0xc6, 0xee, 0xf1}};
const GUID kBPictureCount = {0x8d390aac, 0xdc5c, 0x4200, {0xb5, 0x7f, 0x81, 0x4d, 0x04, 0xba, 0xba, 0xb2}};
const GUID kLowLatencyMode = {0x9c27891a, 0xed7a, 0x40e1, {0x88, 0xe8, 0xb2, 0x27, 0x27, 0xa0, 0x24, 0xee}};
const GUID kForceKeyFrame = {0x398c1b98, 0x8353, 0x475a, {0x9e, 0xf2, 0x8f, 0x26, 0x5d, 0x26, 0x03, 0x45}};

void set_u32(ICodecAPI* api, const GUID& name, uint32_t value) {
    if (!api) return;
    VARIANT v{};
    v.vt = VT_UI4;
    v.ulVal = value;
    api->SetValue(&name, &v);  // an encoder without the property keeps its default
}

void set_bool(ICodecAPI* api, const GUID& name, bool value) {
    if (!api) return;
    VARIANT v{};
    v.vt = VT_BOOL;
    v.boolVal = value ? VARIANT_TRUE : VARIANT_FALSE;
    api->SetValue(&name, &v);
}

// The first synchronous encoder MFT whose output is `subtype`.
bool create_encoder(bool video, const GUID& subtype, IMFTransform** out) {
    MFT_REGISTER_TYPE_INFO info{video ? MFMediaType_Video : MFMediaType_Audio, subtype};
    IMFActivate** acts = nullptr;
    UINT32 n = 0;
    if (FAILED(MFTEnumEx(video ? MFT_CATEGORY_VIDEO_ENCODER : MFT_CATEGORY_AUDIO_ENCODER,
                         MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_SORTANDFILTER, nullptr, &info, &acts, &n)) ||
        !acts) {
        return false;
    }
    bool ok = false;
    for (UINT32 i = 0; i < n; ++i) {
        if (!ok && SUCCEEDED(acts[i]->ActivateObject(IID_PPV_ARGS(out)))) ok = true;
        acts[i]->Release();
    }
    CoTaskMemFree(acts);
    return ok;
}

// Calls nal(p, n) for each NAL unit of an Annex-B buffer.
template <typename F>
void for_each_nal(const uint8_t* d, size_t size, F nal) {
    auto code_at = [&](size_t i) { return i + 3 <= size && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1; };
    size_t i = 0;
    while (i < size && !code_at(i)) ++i;
    while (i < size) {
        const size_t begin = i + 3;
        size_t end = begin;
        while (end < size && !code_at(end)) ++end;
        size_t stop = end;
        if (end < size) {
            while (stop > begin && d[stop - 1] == 0) --stop;  // the next code's leading zero
        }
        if (stop > begin) nal(d + begin, stop - begin);
        i = end;
    }
}

// A sample's bytes.
bool bytes_of(IMFSample* sample, std::vector<uint8_t>* out) {
    Com<IMFMediaBuffer> buf;
    BYTE* p = nullptr;
    DWORD len = 0;
    if (FAILED(sample->ConvertToContiguousBuffer(&buf)) || FAILED(buf->Lock(&p, nullptr, &len))) return false;
    out->assign(p, p + len);
    buf->Unlock();
    return true;
}

// ARGB ints to NV12, limited range, with the matrix decoders assume for a
// stream that names none: BT.709 from 720 lines up, BT.601 below.
void argb_to_nv12(const uint32_t* argb, int w, int h, uint8_t* out) {
    const bool hd = h >= 720;
    const int yr = hd ? 47 : 66, yg = hd ? 157 : 129, yb = hd ? 16 : 25;
    const int ur = hd ? -26 : -38, ug = hd ? -87 : -74, ub = 112;
    const int vr = 112, vg = hd ? -102 : -94, vb = hd ? -10 : -18;
    uint8_t* uv = out + static_cast<size_t>(w) * h;
    for (int row = 0; row < h; ++row) {
        const uint32_t* src = argb + static_cast<size_t>(row) * w;
        uint8_t* y = out + static_cast<size_t>(row) * w;
        for (int col = 0; col < w; ++col) {
            const int r = (src[col] >> 16) & 0xff, g = (src[col] >> 8) & 0xff, b = src[col] & 0xff;
            y[col] = static_cast<uint8_t>(((yr * r + yg * g + yb * b + 128) >> 8) + 16);
        }
    }
    for (int row = 0; row + 1 < h; row += 2) {
        const uint32_t* a = argb + static_cast<size_t>(row) * w;
        const uint32_t* b2 = a + w;
        uint8_t* dst = uv + static_cast<size_t>(row / 2) * w;
        for (int col = 0; col + 1 < w; col += 2) {
            // The four pixels a chroma sample covers, averaged.
            int r = 0, g = 0, b = 0;
            for (const uint32_t px : {a[col], a[col + 1], b2[col], b2[col + 1]}) {
                r += (px >> 16) & 0xff;
                g += (px >> 8) & 0xff;
                b += px & 0xff;
            }
            r = (r + 2) >> 2;
            g = (g + 2) >> 2;
            b = (b + 2) >> 2;
            dst[col] = static_cast<uint8_t>(((ur * r + ug * g + ub * b + 128) >> 8) + 128);
            dst[col + 1] = static_cast<uint8_t>(((vr * r + vg * g + vb * b + 128) >> 8) + 128);
        }
    }
}

// What both encoders do with their MFT: push a sample in, take samples out.
struct Transform {
    Com<IMFTransform> mft;
    DWORD out_size = 0, out_align = 0;
    bool provides_samples = false;

    bool read_output_info() {
        MFT_OUTPUT_STREAM_INFO info{};
        if (FAILED(mft->GetOutputStreamInfo(0, &info))) return false;
        provides_samples =
            (info.dwFlags & (MFT_OUTPUT_STREAM_PROVIDES_SAMPLES | MFT_OUTPUT_STREAM_CAN_PROVIDE_SAMPLES)) != 0;
        out_size = info.cbSize;
        out_align = info.cbAlignment;
        return true;
    }

    // Every sample the MFT has ready goes to `take`.
    template <typename F>
    void drain(DWORD fallback_size, F take) {
        for (int changes = 0; changes < 4;) {
            MFT_OUTPUT_DATA_BUFFER ob{};
            Com<IMFSample> own;
            if (!provides_samples) {
                Com<IMFMediaBuffer> mb;
                if (FAILED(MFCreateSample(&own)) ||
                    FAILED(MFCreateAlignedMemoryBuffer(out_size ? out_size : fallback_size,
                                                       out_align ? out_align : MF_16_BYTE_ALIGNMENT, &mb)) ||
                    FAILED(own->AddBuffer(mb.p))) {
                    return;
                }
                ob.pSample = own.p;
            }
            DWORD status = 0;
            const HRESULT hr = mft->ProcessOutput(0, 1, &ob, &status);
            if (ob.pEvents) ob.pEvents->Release();
            if (hr == MF_E_TRANSFORM_STREAM_CHANGE) {
                // The encoder restates its output type: take it and go on.
                if (ob.pSample && !own) ob.pSample->Release();
                Com<IMFMediaType> t;
                if (FAILED(mft->GetOutputAvailableType(0, 0, &t)) || FAILED(mft->SetOutputType(0, t.p, 0)) ||
                    !read_output_info()) {
                    return;
                }
                ++changes;
                continue;
            }
            if (FAILED(hr)) {  // MF_E_TRANSFORM_NEED_MORE_INPUT, or a real error
                if (ob.pSample && !own) ob.pSample->Release();
                return;
            }
            if (ob.pSample) {
                take(ob.pSample);
                if (!own) ob.pSample->Release();
            }
        }
    }

    template <typename F>
    bool push(IMFSample* sample, DWORD fallback_size, F take) {
        HRESULT hr = mft->ProcessInput(0, sample, 0);
        for (int tries = 0; hr == MF_E_NOTACCEPTING && tries < 8; ++tries) {
            drain(fallback_size, take);  // full: make room, then offer it again
            hr = mft->ProcessInput(0, sample, 0);
        }
        if (FAILED(hr)) return false;
        drain(fallback_size, take);
        return true;
    }

    template <typename F>
    void end(DWORD fallback_size, F take) {
        mft->ProcessMessage(MFT_MESSAGE_NOTIFY_END_OF_STREAM, 0);
        mft->ProcessMessage(MFT_MESSAGE_COMMAND_DRAIN, 0);
        drain(fallback_size, take);
    }
};

std::string names_of(bool video, const GUID& subtype) {
    MFT_REGISTER_TYPE_INFO info{video ? MFMediaType_Video : MFMediaType_Audio, subtype};
    IMFActivate** acts = nullptr;
    UINT32 n = 0;
    std::string out;
    if (FAILED(MFTEnumEx(video ? MFT_CATEGORY_VIDEO_ENCODER : MFT_CATEGORY_AUDIO_ENCODER,
                         MFT_ENUM_FLAG_SYNCMFT | MFT_ENUM_FLAG_ASYNCMFT | MFT_ENUM_FLAG_HARDWARE |
                             MFT_ENUM_FLAG_SORTANDFILTER,
                         nullptr, &info, &acts, &n)) ||
        !acts) {
        return "none";
    }
    for (UINT32 i = 0; i < n; ++i) {
        WCHAR* name = nullptr;
        UINT32 len = 0, flags = 0;
        if (SUCCEEDED(acts[i]->GetAllocatedString(MFT_FRIENDLY_NAME_Attribute, &name, &len)) && name) {
            if (!out.empty()) out += ", ";
            for (UINT32 c = 0; c < len; ++c) out.push_back(name[c] < 0x80 ? static_cast<char>(name[c]) : '?');
            CoTaskMemFree(name);
            acts[i]->GetUINT32(MF_TRANSFORM_FLAGS_Attribute, &flags);
            out += (flags & MFT_ENUM_FLAG_SYNCMFT) ? " (synchronous)" : " (asynchronous or hardware: not used)";
        }
        acts[i]->Release();
    }
    CoTaskMemFree(acts);
    return out.empty() ? "none" : out;
}

}  // namespace

bool video_encoder_available() {
    static const bool found = [] {
        Com<IMFTransform> mft;
        return mf_ready() && create_encoder(true, MFVideoFormat_H264, &mft);
    }();
    return found;
}

bool audio_encoder_available() {
    static const bool found = [] {
        Com<IMFTransform> mft;
        return mf_ready() && create_encoder(false, MFAudioFormat_AAC, &mft);
    }();
    return found;
}

std::string encoder_report() {
    if (!mf_ready()) return "Media Foundation did not start";
    return "H.264 encoders: " + names_of(true, MFVideoFormat_H264) + "; AAC encoders: " +
           names_of(false, MFAudioFormat_AAC);
}

// ---- VideoEncoder ------------------------------------------------------------------------

struct VideoEncoder::Impl {
    Transform tr;
    Com<ICodecAPI> api;
    VideoEncoderConfig cfg;
    LONGLONG frame_duration = 333333;  // 100 ns units
    bool config_sent = false;
    std::deque<Encoded> ready;
    std::deque<int64_t> times;  // the pictures in flight, should an output sample come without its time

    DWORD frame_bytes() const { return static_cast<DWORD>(cfg.width) * cfg.height * 3 / 2; }

    void send_config(const std::vector<uint8_t>& sps, const std::vector<uint8_t>& pps) {
        if (config_sent || sps.empty() || pps.empty()) return;
        static const uint8_t kCode[4] = {0, 0, 0, 1};
        Encoded e;
        e.config = true;
        e.data.insert(e.data.end(), kCode, kCode + 4);
        e.data.insert(e.data.end(), sps.begin(), sps.end());
        e.data.insert(e.data.end(), kCode, kCode + 4);
        e.data.insert(e.data.end(), pps.begin(), pps.end());
        ready.push_back(std::move(e));
        config_sent = true;
    }

    // The SPS and PPS of an Annex-B buffer, if it has both.
    void config_from(const std::vector<uint8_t>& data) {
        std::vector<uint8_t> sps, pps;
        for_each_nal(data.data(), data.size(), [&](const uint8_t* p, size_t n) {
            const int type = p[0] & 0x1f;
            if (type == 7 && sps.empty()) sps.assign(p, p + n);
            if (type == 8 && pps.empty()) pps.assign(p, p + n);
        });
        send_config(sps, pps);
    }

    void take(IMFSample* sample) {
        Encoded e;
        if (!bytes_of(sample, &e.data) || e.data.empty()) return;
        LONGLONG t = 0;
        if (SUCCEEDED(sample->GetSampleTime(&t))) e.pts_us = t / 10;
        else if (!times.empty()) e.pts_us = times.front();
        if (!times.empty()) times.pop_front();
        for_each_nal(e.data.data(), e.data.size(), [&](const uint8_t* p, size_t) {
            if ((p[0] & 0x1f) == 5) e.key = true;
        });
        // The encoder writes its parameter sets ahead of each IDR picture;
        // the app is owed them once, on their own, before the first frame.
        if (!config_sent) config_from(e.data);
        ready.push_back(std::move(e));
    }
};

VideoEncoder::VideoEncoder(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
VideoEncoder::~VideoEncoder() = default;

std::unique_ptr<VideoEncoder> VideoEncoder::open(const VideoEncoderConfig& config) {
    if (config.width < 2 || config.height < 2 || (config.width & 1) || (config.height & 1) || !mf_ready()) {
        return nullptr;
    }
    auto impl = std::make_unique<Impl>();
    Impl& e = *impl;
    e.cfg = config;
    if (e.cfg.frame_rate < 1) e.cfg.frame_rate = 30;
    if (e.cfg.bitrate <= 0) e.cfg.bitrate = config.width * config.height * 4;  // some 3.7 Mbit/s at 720p
    if (!create_encoder(true, MFVideoFormat_H264, &e.tr.mft)) return nullptr;
    const UINT32 fps_num = static_cast<UINT32>(e.cfg.frame_rate * 1000 + 0.5), fps_den = 1000;
    e.frame_duration = static_cast<LONGLONG>(10000000.0 / e.cfg.frame_rate);

    // The properties first: the encoder reads them when its types are set.
    e.tr.mft->QueryInterface(kIidCodecApi, reinterpret_cast<void**>(&e.api.p));
    set_u32(e.api.p, kRateControlMode, e.cfg.bitrate_mode == 2 ? 0 /* CBR */ : 2 /* unconstrained VBR */);
    set_u32(e.api.p, kMeanBitRate, static_cast<uint32_t>(e.cfg.bitrate));
    const double gop = e.cfg.key_interval_s < 0 ? 100000.0 : e.cfg.key_interval_s * e.cfg.frame_rate;
    set_u32(e.api.p, kGopSize, gop < 1 ? 1 : static_cast<uint32_t>(gop + 0.5));
    // One access unit out for each picture in, in the order they went in: no
    // B-frames and no look-ahead, which is what MediaCodec's callers assume
    // of an encoder they feed in real time.
    set_u32(e.api.p, kBPictureCount, 0);
    set_bool(e.api.p, kLowLatencyMode, true);

    Com<IMFMediaType> out, in;
    const UINT32 profile = e.cfg.profile == 1 ? 66 : e.cfg.profile == 2 ? 77 : e.cfg.profile == 8 ? 100 : 0;
    if (FAILED(MFCreateMediaType(&out)) || FAILED(out->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) ||
        FAILED(out->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264)) ||
        FAILED(out->SetUINT32(MF_MT_AVG_BITRATE, static_cast<UINT32>(e.cfg.bitrate))) ||
        FAILED(out->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive)) ||
        FAILED(MFSetAttributeSize(out.p, MF_MT_FRAME_SIZE, config.width, config.height)) ||
        FAILED(MFSetAttributeRatio(out.p, MF_MT_FRAME_RATE, fps_num, fps_den)) ||
        FAILED(MFSetAttributeRatio(out.p, MF_MT_PIXEL_ASPECT_RATIO, 1, 1))) {
        return nullptr;
    }
    if (profile) out->SetUINT32(MF_MT_MPEG2_PROFILE, profile);
    if (FAILED(e.tr.mft->SetOutputType(0, out.p, 0))) {
        // A profile this encoder does not have: its own choice instead.
        out->DeleteItem(MF_MT_MPEG2_PROFILE);
        if (FAILED(e.tr.mft->SetOutputType(0, out.p, 0))) return nullptr;
    }
    if (FAILED(MFCreateMediaType(&in)) || FAILED(in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video)) ||
        FAILED(in->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_NV12)) ||
        FAILED(in->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive)) ||
        FAILED(MFSetAttributeSize(in.p, MF_MT_FRAME_SIZE, config.width, config.height)) ||
        FAILED(MFSetAttributeRatio(in.p, MF_MT_FRAME_RATE, fps_num, fps_den)) ||
        FAILED(MFSetAttributeRatio(in.p, MF_MT_PIXEL_ASPECT_RATIO, 1, 1)) ||
        FAILED(e.tr.mft->SetInputType(0, in.p, 0)) || !e.tr.read_output_info()) {
        return nullptr;
    }
    e.tr.mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    e.tr.mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);
    // The parameter sets, when the encoder already states them in its type
    // (else they are taken from its first access unit).
    Com<IMFMediaType> cur;
    UINT32 n = 0;
    if (SUCCEEDED(e.tr.mft->GetOutputCurrentType(0, &cur)) &&
        SUCCEEDED(cur->GetBlobSize(MF_MT_MPEG_SEQUENCE_HEADER, &n)) && n) {
        std::vector<uint8_t> header(n);
        if (SUCCEEDED(cur->GetBlob(MF_MT_MPEG_SEQUENCE_HEADER, header.data(), n, nullptr))) e.config_from(header);
    }
    return std::unique_ptr<VideoEncoder>(new VideoEncoder(std::move(impl)));
}

bool VideoEncoder::encode(const void* pixels, size_t bytes, Pixels layout, int64_t pts_us, bool key) {
    Impl& e = *impl_;
    const int w = e.cfg.width, h = e.cfg.height;
    const size_t luma = static_cast<size_t>(w) * h, need = layout == Pixels::kArgb ? luma * 4 : luma * 3 / 2;
    if (!pixels || bytes < need) return false;
    Com<IMFSample> sample;
    Com<IMFMediaBuffer> mb;
    BYTE* p = nullptr;
    if (FAILED(MFCreateSample(&sample)) || FAILED(MFCreateMemoryBuffer(e.frame_bytes(), &mb)) ||
        FAILED(mb->Lock(&p, nullptr, nullptr))) {
        return false;
    }
    const uint8_t* src = static_cast<const uint8_t*>(pixels);
    switch (layout) {
        case Pixels::kNv12: std::memcpy(p, src, luma * 3 / 2); break;
        case Pixels::kI420: {
            std::memcpy(p, src, luma);
            const uint8_t* u = src + luma;
            const uint8_t* v = u + luma / 4;
            uint8_t* uv = p + luma;
            for (size_t i = 0; i < luma / 4; ++i) {
                uv[i * 2] = u[i];
                uv[i * 2 + 1] = v[i];
            }
            break;
        }
        case Pixels::kArgb: argb_to_nv12(static_cast<const uint32_t*>(pixels), w, h, p); break;
    }
    mb->Unlock();
    mb->SetCurrentLength(e.frame_bytes());
    sample->AddBuffer(mb.p);
    sample->SetSampleTime(pts_us * 10);
    sample->SetSampleDuration(e.frame_duration);
    if (key) set_u32(e.api.p, kForceKeyFrame, 1);
    e.times.push_back(pts_us);
    return e.tr.push(sample.p, e.frame_bytes(), [&](IMFSample* s) { e.take(s); });
}

void VideoEncoder::finish() {
    Impl& e = *impl_;
    e.tr.end(e.frame_bytes(), [&](IMFSample* s) { e.take(s); });
}

bool VideoEncoder::poll(Encoded* out) {
    Impl& e = *impl_;
    if (e.ready.empty()) return false;
    *out = std::move(e.ready.front());
    e.ready.pop_front();
    return true;
}

void VideoEncoder::set_bitrate(int bits_per_second) {
    if (bits_per_second > 0) set_u32(impl_->api.p, kMeanBitRate, static_cast<uint32_t>(bits_per_second));
}

// ---- AudioEncoder ------------------------------------------------------------------------

struct AudioEncoder::Impl {
    Transform tr;
    int rate = 0, channels = 0, bitrate = 0;
    int64_t frames_out = 0;
    int64_t first_pts = 0;
    bool have_first = false;
    std::deque<Encoded> ready;

    void take(IMFSample* sample) {
        Encoded e;
        if (!bytes_of(sample, &e.data) || e.data.empty()) return;
        // Each output sample is one AAC frame of 1024 PCM frames; its time
        // counts on from the first input's.
        e.pts_us = first_pts + frames_out * 1024 * 1000000 / rate;
        e.key = true;
        ++frames_out;
        ready.push_back(std::move(e));
    }
};

AudioEncoder::AudioEncoder(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
AudioEncoder::~AudioEncoder() = default;

std::unique_ptr<AudioEncoder> AudioEncoder::open(int sample_rate, int channels, int bitrate) {
    if (sample_rate <= 0 || channels < 1 || channels > 2 || !mf_ready()) return nullptr;
    auto impl = std::make_unique<Impl>();
    Impl& e = *impl;
    e.rate = sample_rate;
    e.channels = channels;
    if (!create_encoder(false, MFAudioFormat_AAC, &e.tr.mft)) return nullptr;
    Com<IMFMediaType> in;
    const UINT32 block = static_cast<UINT32>(channels) * 2;
    if (FAILED(MFCreateMediaType(&in)) || FAILED(in->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio)) ||
        FAILED(in->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_PCM)) ||
        FAILED(in->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16)) ||
        FAILED(in->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, static_cast<UINT32>(sample_rate))) ||
        FAILED(in->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, static_cast<UINT32>(channels))) ||
        FAILED(in->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, block)) ||
        FAILED(in->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, block * static_cast<UINT32>(sample_rate))) ||
        FAILED(in->SetUINT32(MF_MT_ALL_SAMPLES_INDEPENDENT, TRUE)) || FAILED(e.tr.mft->SetInputType(0, in.p, 0))) {
        return nullptr;  // a rate the encoder does not take
    }
    // Of the output types the encoder offers for this input (raw AAC at a few
    // fixed bit rates), the one nearest the bit rate asked for.
    const UINT32 want = static_cast<UINT32>(bitrate > 0 ? bitrate : 128000) / 8;
    Com<IMFMediaType> best;
    UINT32 best_diff = 0xffffffffu;
    for (DWORD i = 0;; ++i) {
        Com<IMFMediaType> t;
        if (FAILED(e.tr.mft->GetOutputAvailableType(0, i, &t))) break;
        UINT32 r = 0, c = 0, bytes = 0, payload = 0;
        t->GetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, &r);
        t->GetUINT32(MF_MT_AUDIO_NUM_CHANNELS, &c);
        t->GetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, &bytes);
        t->GetUINT32(MF_MT_AAC_PAYLOAD_TYPE, &payload);
        if (r != static_cast<UINT32>(sample_rate) || c != static_cast<UINT32>(channels) || payload != 0) continue;
        const UINT32 diff = bytes > want ? bytes - want : want - bytes;
        if (diff < best_diff) {
            best_diff = diff;
            if (best.p) best.p->Release();
            best.p = t.p;
            t.p = nullptr;
            e.bitrate = static_cast<int>(bytes) * 8;
        }
    }
    if (!best || FAILED(e.tr.mft->SetOutputType(0, best.p, 0)) || !e.tr.read_output_info()) return nullptr;
    e.tr.mft->ProcessMessage(MFT_MESSAGE_NOTIFY_BEGIN_STREAMING, 0);
    e.tr.mft->ProcessMessage(MFT_MESSAGE_NOTIFY_START_OF_STREAM, 0);

    // The AudioSpecificConfig: after the 12 bytes of HEAACWAVEINFO in the
    // output type's MF_MT_USER_DATA; AAC-LC's two bytes when it is not there.
    Encoded config;
    config.config = true;
    Com<IMFMediaType> cur;
    UINT32 n = 0;
    if (SUCCEEDED(e.tr.mft->GetOutputCurrentType(0, &cur)) && SUCCEEDED(cur->GetBlobSize(MF_MT_USER_DATA, &n)) &&
        n > 12) {
        std::vector<uint8_t> user(n);
        if (SUCCEEDED(cur->GetBlob(MF_MT_USER_DATA, user.data(), n, nullptr))) {
            config.data.assign(user.begin() + 12, user.end());
        }
    }
    if (config.data.empty()) {
        static const int kRates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                                     22050, 16000, 12000, 11025, 8000,  7350};
        int idx = 4;
        for (int i = 0; i < 13; ++i) {
            if (kRates[i] == sample_rate) idx = i;
        }
        config.data = {static_cast<uint8_t>((2 << 3) | (idx >> 1)),
                       static_cast<uint8_t>(((idx & 1) << 7) | (channels << 3))};
    }
    e.ready.push_back(std::move(config));
    return std::unique_ptr<AudioEncoder>(new AudioEncoder(std::move(impl)));
}

bool AudioEncoder::encode(const int16_t* pcm, size_t count, int64_t pts_us) {
    Impl& e = *impl_;
    if (!pcm || count == 0) return true;
    if (!e.have_first) {
        e.have_first = true;
        e.first_pts = pts_us;
    }
    const DWORD bytes = static_cast<DWORD>(count * 2);
    Com<IMFSample> sample;
    Com<IMFMediaBuffer> mb;
    BYTE* p = nullptr;
    if (FAILED(MFCreateSample(&sample)) || FAILED(MFCreateMemoryBuffer(bytes, &mb)) ||
        FAILED(mb->Lock(&p, nullptr, nullptr))) {
        return false;
    }
    std::memcpy(p, pcm, bytes);
    mb->Unlock();
    mb->SetCurrentLength(bytes);
    sample->AddBuffer(mb.p);
    sample->SetSampleTime(pts_us * 10);
    sample->SetSampleDuration(static_cast<LONGLONG>(count / static_cast<size_t>(e.channels)) * 10000000 / e.rate);
    return e.tr.push(sample.p, 8192, [&](IMFSample* s) { e.take(s); });
}

void AudioEncoder::finish() {
    Impl& e = *impl_;
    e.tr.end(8192, [&](IMFSample* s) { e.take(s); });
}

bool AudioEncoder::poll(Encoded* out) {
    Impl& e = *impl_;
    if (e.ready.empty()) return false;
    *out = std::move(e.ready.front());
    e.ready.pop_front();
    return true;
}

int AudioEncoder::bitrate() const { return impl_->bitrate; }

}  // namespace winapk::video
