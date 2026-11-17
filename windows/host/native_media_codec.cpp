// native_media_codec.cpp -- com.macapk.loader.HostMedia: what MediaExtractor,
// MediaMuxer, MediaMetadataRetriever and MediaCodec's encoders stand on. The
// demuxer is video_decode.h's Demuxer, the encoders media_encode.h's, the MP4
// file mp4_writer.h's; none of them knows Java.
//
// Handles are table keys, never pointers: a stale one from Java is a no-op.
// Each object has a lock of its own, so that two codecs never wait for each
// other and a close during a call waits for it.

#include "host_natives.h"

#include <cstdint>
#include <cstdio>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "media_encode.h"
#include "mp4_writer.h"
#include "video_decode.h"
#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

namespace vd = winapk::video;

template <typename T>
struct Slot {
    std::mutex lock;
    std::unique_ptr<T> object;
};

template <typename T>
class Table {
public:
    jlong add(std::unique_ptr<T> object) {
        auto slot = std::make_shared<Slot<T>>();
        slot->object = std::move(object);
        std::lock_guard<std::mutex> hold(lock_);
        const jlong handle = next_++;
        slots_[handle] = std::move(slot);
        return handle;
    }
    std::shared_ptr<Slot<T>> find(jlong handle) {
        std::lock_guard<std::mutex> hold(lock_);
        auto it = slots_.find(handle);
        return it == slots_.end() ? nullptr : it->second;
    }
    void remove(jlong handle) {
        std::shared_ptr<Slot<T>> slot;
        {
            std::lock_guard<std::mutex> hold(lock_);
            auto it = slots_.find(handle);
            if (it == slots_.end()) return;
            slot = std::move(it->second);
            slots_.erase(it);
        }
        std::lock_guard<std::mutex> hold(slot->lock);  // a call in flight ends first
        slot->object.reset();
    }

private:
    std::mutex lock_;
    jlong next_ = 1;
    std::map<jlong, std::shared_ptr<Slot<T>>> slots_;
};

// An encoder of either kind behind one handle.
struct Encoder {
    std::unique_ptr<vd::VideoEncoder> video;
    std::unique_ptr<vd::AudioEncoder> audio;
    int width = 0, height = 0;
};

Table<vd::Demuxer> g_demuxers;
Table<Encoder> g_encoders;
Table<winapk::mp4::Writer> g_writers;

std::vector<uint8_t> bytes_of(JNIEnv* env, jbyteArray a, jint length = -1) {
    std::vector<uint8_t> v;
    if (!a) return v;
    jsize n = env->GetArrayLength(a);
    if (length >= 0 && length < n) n = length;
    if (n <= 0) return v;
    v.resize(static_cast<size_t>(n));
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}

jbyteArray array_of(JNIEnv* env, const std::vector<uint8_t>& v) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(v.size()));
    if (a && !v.empty()) {
        env->SetByteArrayRegion(a, 0, static_cast<jsize>(v.size()), reinterpret_cast<const jbyte*>(v.data()));
    }
    return a;
}

// A path as Java names it (an Android path, a host path or, for a source, an
// http(s) URL) as the host file it is: see native_media_video.cpp.
std::wstring host_path_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::wstring out = p ? winapk::android_host_path(p) : std::wstring();
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

// ---- the demuxer -------------------------------------------------------------------------

jlong JNICALL Demux_open(JNIEnv* env, jclass, jstring path) {
    auto d = vd::Demuxer::open(host_path_of(env, path));
    return d ? g_demuxers.add(std::move(d)) : 0;
}

jint JNICALL Demux_trackCount(JNIEnv*, jclass, jlong h) {
    auto s = g_demuxers.find(h);
    if (!s) return 0;
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object ? s->object->track_count() : 0;
}

// The track as "key=value" lines; MediaExtractor makes the MediaFormat.
jstring JNICALL Demux_trackInfo(JNIEnv* env, jclass, jlong h, jint index) {
    auto s = g_demuxers.find(h);
    if (!s) return nullptr;
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object || index < 0 || index >= s->object->track_count()) return nullptr;
    const vd::Demuxer::Track& t = s->object->track(index);
    char buf[512];
    std::snprintf(buf, sizeof(buf),
                  "mime=%s\nvideo=%d\nwidth=%d\nheight=%d\nrotation=%d\nframe-rate=%.3f\nsample-rate=%d\n"
                  "channel-count=%d\nbitrate=%d\nlanguage=%s\n",
                  t.mime.c_str(), t.video ? 1 : 0, t.width, t.height, t.rotation, t.frame_rate, t.sample_rate,
                  t.channels, t.bitrate, t.language.c_str());
    return env->NewStringUTF(buf);
}

jbyteArray JNICALL Demux_trackCsd(JNIEnv* env, jclass, jlong h, jint index, jint which) {
    auto s = g_demuxers.find(h);
    if (!s) return nullptr;
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object || index < 0 || index >= s->object->track_count()) return nullptr;
    const vd::Demuxer::Track& t = s->object->track(index);
    const std::vector<uint8_t>& csd = which == 0 ? t.csd0 : t.csd1;
    return csd.empty() ? nullptr : array_of(env, csd);
}

jlong JNICALL Demux_durationUs(JNIEnv*, jclass, jlong h) {
    auto s = g_demuxers.find(h);
    if (!s) return 0;
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object ? s->object->duration_us() : 0;
}

void JNICALL Demux_select(JNIEnv*, jclass, jlong h, jint index, jboolean on) {
    auto s = g_demuxers.find(h);
    if (!s) return;
    std::lock_guard<std::mutex> hold(s->lock);
    if (s->object) s->object->select(index, on == JNI_TRUE);
}

// The next sample's bytes, with meta = {track, presentationTimeUs, sync ? 1 : 0};
// null at the end.
jbyteArray JNICALL Demux_read(JNIEnv* env, jclass, jlong h, jlongArray meta) {
    auto s = g_demuxers.find(h);
    if (!s || !meta || env->GetArrayLength(meta) < 3) return nullptr;
    vd::Demuxer::Sample sample;
    {
        std::lock_guard<std::mutex> hold(s->lock);
        if (!s->object || !s->object->read(&sample)) return nullptr;
    }
    const jlong m[3] = {sample.track, sample.pts_us, sample.key ? 1 : 0};
    env->SetLongArrayRegion(meta, 0, 3, m);
    return array_of(env, sample.data);
}

jboolean JNICALL Demux_seek(JNIEnv*, jclass, jlong h, jlong us, jint mode) {
    auto s = g_demuxers.find(h);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> hold(s->lock);
    const auto m = mode == 1 ? vd::Demuxer::kNextSync : mode == 2 ? vd::Demuxer::kClosestSync : vd::Demuxer::kPreviousSync;
    return s->object && s->object->seek(us, m) ? JNI_TRUE : JNI_FALSE;
}

void JNICALL Demux_close(JNIEnv*, jclass, jlong h) { g_demuxers.remove(h); }

// The decoded picture at or just before `us` (the first one for a negative
// time), as ARGB with dims = {width, height}: MediaMetadataRetriever's frames.
jintArray JNICALL Frame_at(JNIEnv* env, jclass, jstring path, jlong us, jintArray dims) {
    auto src = vd::Source::open(host_path_of(env, path));
    if (!src || !src->has_video() || !dims || env->GetArrayLength(dims) < 2) return nullptr;
    if (us > 0) src->seek(us);
    // The seek lands on a key frame; decode on to the time asked for, within
    // reason (a file with one key frame would decode all of it otherwise).
    std::shared_ptr<vd::Frame> best;
    for (int i = 0; i < 600; ++i) {
        vd::Source::Packet p;
        if (!src->read_video(&p) || p.kind != vd::Source::Packet::kVideo || !p.frame) break;
        if (best && p.pts_us > us) break;
        best = p.frame;
        if (us <= 0 || p.pts_us >= us) break;
    }
    if (!best) return nullptr;
    const jint d[2] = {best->width, best->height};
    env->SetIntArrayRegion(dims, 0, 2, d);
    const jsize n = static_cast<jsize>(best->argb.size());
    jintArray out = env->NewIntArray(n);
    if (out) env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(best->argb.data()));
    return out;
}

// ---- the encoders ------------------------------------------------------------------------

jboolean JNICALL Enc_available(JNIEnv*, jclass, jboolean video) {
    static const bool logged = [] {
        // Once, so that a log tells which encoders this Windows has.
        std::fprintf(stderr, "[media] %s\n", vd::encoder_report().c_str());
        return true;
    }();
    (void)logged;
    return (video == JNI_TRUE ? vd::video_encoder_available() : vd::audio_encoder_available()) ? JNI_TRUE : JNI_FALSE;
}

jlong JNICALL Enc_openVideo(JNIEnv*, jclass, jint w, jint h, jint bitrate, jfloat fps, jfloat key_interval,
                            jint profile, jint mode) {
    vd::VideoEncoderConfig cfg;
    cfg.width = w;
    cfg.height = h;
    cfg.bitrate = bitrate;
    cfg.frame_rate = fps;
    cfg.key_interval_s = key_interval;
    cfg.profile = profile;
    cfg.bitrate_mode = mode;
    auto e = std::make_unique<Encoder>();
    e->video = vd::VideoEncoder::open(cfg);
    e->width = w;
    e->height = h;
    return e->video ? g_encoders.add(std::move(e)) : 0;
}

jlong JNICALL Enc_openAudio(JNIEnv*, jclass, jint rate, jint channels, jint bitrate) {
    auto e = std::make_unique<Encoder>();
    e->audio = vd::AudioEncoder::open(rate, channels, bitrate);
    return e->audio ? g_encoders.add(std::move(e)) : 0;
}

// layout: 0 NV12, 1 I420 (media_encode.h's Pixels).
jboolean JNICALL Enc_video(JNIEnv* env, jclass, jlong h, jbyteArray yuv, jint layout, jlong pts, jboolean key) {
    auto s = g_encoders.find(h);
    if (!s) return JNI_FALSE;
    const std::vector<uint8_t> data = bytes_of(env, yuv);
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object || !s->object->video) return JNI_FALSE;
    return s->object->video->encode(data.data(), data.size(), layout == 1 ? vd::Pixels::kI420 : vd::Pixels::kNv12, pts,
                                    key == JNI_TRUE)
               ? JNI_TRUE
               : JNI_FALSE;
}

jboolean JNICALL Enc_videoArgb(JNIEnv* env, jclass, jlong h, jintArray argb, jlong pts, jboolean key) {
    auto s = g_encoders.find(h);
    if (!s || !argb) return JNI_FALSE;
    std::vector<uint32_t> px(static_cast<size_t>(env->GetArrayLength(argb)));
    if (!px.empty()) env->GetIntArrayRegion(argb, 0, static_cast<jsize>(px.size()), reinterpret_cast<jint*>(px.data()));
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object || !s->object->video) return JNI_FALSE;
    return s->object->video->encode(px.data(), px.size() * 4, vd::Pixels::kArgb, pts, key == JNI_TRUE) ? JNI_TRUE
                                                                                                         : JNI_FALSE;
}

// 16-bit little-endian PCM, the first `length` bytes.
jboolean JNICALL Enc_audio(JNIEnv* env, jclass, jlong h, jbyteArray pcm, jint length, jlong pts) {
    auto s = g_encoders.find(h);
    if (!s) return JNI_FALSE;
    const std::vector<uint8_t> data = bytes_of(env, pcm, length);
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object || !s->object->audio) return JNI_FALSE;
    return s->object->audio->encode(reinterpret_cast<const int16_t*>(data.data()), data.size() / 2, pts) ? JNI_TRUE
                                                                                                          : JNI_FALSE;
}

void JNICALL Enc_finish(JNIEnv*, jclass, jlong h) {
    auto s = g_encoders.find(h);
    if (!s) return;
    std::lock_guard<std::mutex> hold(s->lock);
    if (!s->object) return;
    if (s->object->video) s->object->video->finish();
    if (s->object->audio) s->object->audio->finish();
}

// The next output buffer, with meta = {presentationTimeUs, MediaCodec's buffer
// flags}; null when there is none yet.
jbyteArray JNICALL Enc_poll(JNIEnv* env, jclass, jlong h, jlongArray meta) {
    auto s = g_encoders.find(h);
    if (!s || !meta || env->GetArrayLength(meta) < 2) return nullptr;
    vd::Encoded out;
    {
        std::lock_guard<std::mutex> hold(s->lock);
        if (!s->object) return nullptr;
        const bool got = s->object->video ? s->object->video->poll(&out) : s->object->audio->poll(&out);
        if (!got) return nullptr;
    }
    const jlong m[2] = {out.pts_us, (out.key ? 1 : 0) | (out.config ? 2 : 0)};
    env->SetLongArrayRegion(meta, 0, 2, m);
    return array_of(env, out.data);
}

void JNICALL Enc_setBitrate(JNIEnv*, jclass, jlong h, jint bps) {
    auto s = g_encoders.find(h);
    if (!s) return;
    std::lock_guard<std::mutex> hold(s->lock);
    if (s->object && s->object->video) s->object->video->set_bitrate(bps);
}

void JNICALL Enc_close(JNIEnv*, jclass, jlong h) { g_encoders.remove(h); }

// ---- the MP4 writer ----------------------------------------------------------------------

jlong JNICALL Mux_open(JNIEnv* env, jclass, jstring path, jboolean three_gp) {
    auto w = winapk::mp4::Writer::create(host_path_of(env, path), three_gp == JNI_TRUE);
    return w ? g_writers.add(std::move(w)) : 0;
}

jint JNICALL Mux_addTrack(JNIEnv* env, jclass, jlong h, jboolean video, jint width, jint height, jint rate,
                          jint channels, jint bitrate, jbyteArray csd0, jbyteArray csd1) {
    auto s = g_writers.find(h);
    if (!s) return -1;
    winapk::mp4::TrackFormat f;
    f.video = video == JNI_TRUE;
    f.width = width;
    f.height = height;
    f.sample_rate = rate;
    f.channels = channels;
    f.bitrate = bitrate;
    f.csd0 = bytes_of(env, csd0);
    f.csd1 = bytes_of(env, csd1);
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object ? s->object->add_track(f) : -1;
}

void JNICALL Mux_setOrientation(JNIEnv*, jclass, jlong h, jint degrees) {
    auto s = g_writers.find(h);
    if (!s) return;
    std::lock_guard<std::mutex> hold(s->lock);
    if (s->object) s->object->set_orientation(degrees);
}

void JNICALL Mux_setLocation(JNIEnv*, jclass, jlong h, jfloat latitude, jfloat longitude) {
    auto s = g_writers.find(h);
    if (!s) return;
    std::lock_guard<std::mutex> hold(s->lock);
    if (s->object) s->object->set_location(latitude, longitude);
}

jboolean JNICALL Mux_start(JNIEnv*, jclass, jlong h) {
    auto s = g_writers.find(h);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object && s->object->start() ? JNI_TRUE : JNI_FALSE;
}

// flags are MediaCodec's: 1 key frame, 2 codec config.
jboolean JNICALL Mux_write(JNIEnv* env, jclass, jlong h, jint track, jbyteArray data, jint length, jlong pts,
                           jint flags) {
    auto s = g_writers.find(h);
    if (!s) return JNI_FALSE;
    const std::vector<uint8_t> bytes = bytes_of(env, data, length);
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object && s->object->write(track, bytes.data(), bytes.size(), pts, (flags & 1) != 0, (flags & 2) != 0)
               ? JNI_TRUE
               : JNI_FALSE;
}

jboolean JNICALL Mux_finish(JNIEnv*, jclass, jlong h) {
    auto s = g_writers.find(h);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object && s->object->finish() ? JNI_TRUE : JNI_FALSE;
}

jlong JNICALL Mux_size(JNIEnv*, jclass, jlong h) {
    auto s = g_writers.find(h);
    if (!s) return 0;
    std::lock_guard<std::mutex> hold(s->lock);
    return s->object ? s->object->size() : 0;
}

void JNICALL Mux_close(JNIEnv*, jclass, jlong h) { g_writers.remove(h); }

constexpr const char* kClass = "com.macapk.loader.HostMedia";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxOpen", "(Ljava/lang/String;)J", Demux_open),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxTrackCount", "(J)I", Demux_trackCount),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxTrackInfo", "(JI)Ljava/lang/String;", Demux_trackInfo),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxTrackCsd", "(JII)[B", Demux_trackCsd),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxDurationUs", "(J)J", Demux_durationUs),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxSelect", "(JIZ)V", Demux_select),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxRead", "(J[J)[B", Demux_read),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxSeek", "(JJI)Z", Demux_seek),
    WINAPK_HOST_NATIVE(kClass, "nativeDemuxClose", "(J)V", Demux_close),
    WINAPK_HOST_NATIVE(kClass, "nativeFrameAt", "(Ljava/lang/String;J[I)[I", Frame_at),
    WINAPK_HOST_NATIVE(kClass, "nativeEncAvailable", "(Z)Z", Enc_available),
    WINAPK_HOST_NATIVE(kClass, "nativeEncOpenVideo", "(IIIFFII)J", Enc_openVideo),
    WINAPK_HOST_NATIVE(kClass, "nativeEncOpenAudio", "(III)J", Enc_openAudio),
    WINAPK_HOST_NATIVE(kClass, "nativeEncVideo", "(J[BIJZ)Z", Enc_video),
    WINAPK_HOST_NATIVE(kClass, "nativeEncVideoArgb", "(J[IJZ)Z", Enc_videoArgb),
    WINAPK_HOST_NATIVE(kClass, "nativeEncAudio", "(J[BIJ)Z", Enc_audio),
    WINAPK_HOST_NATIVE(kClass, "nativeEncFinish", "(J)V", Enc_finish),
    WINAPK_HOST_NATIVE(kClass, "nativeEncPoll", "(J[J)[B", Enc_poll),
    WINAPK_HOST_NATIVE(kClass, "nativeEncSetBitrate", "(JI)V", Enc_setBitrate),
    WINAPK_HOST_NATIVE(kClass, "nativeEncClose", "(J)V", Enc_close),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxOpen", "(Ljava/lang/String;Z)J", Mux_open),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxAddTrack", "(JZIIIII[B[B)I", Mux_addTrack),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxSetOrientation", "(JI)V", Mux_setOrientation),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxSetLocation", "(JFF)V", Mux_setLocation),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxStart", "(J)Z", Mux_start),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxWrite", "(JI[BIJI)Z", Mux_write),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxFinish", "(J)Z", Mux_finish),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxSize", "(J)J", Mux_size),
    WINAPK_HOST_NATIVE(kClass, "nativeMuxClose", "(J)V", Mux_close),
};

}  // namespace

HostMethods native_media_codec_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
