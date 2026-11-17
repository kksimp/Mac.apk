// native_media_video.cpp -- video on Windows, for Java: the video and the
// audio-codec natives of com.macapk.loader.NativeMedia (MediaCodec's
// backend; upstream's VideoToolbox, libvpx and AudioToolbox contract, served
// by video_decode.h), and com.macapk.loader.HostVideo, MediaPlayer's video
// player (video_player.h). NativeMedia's demuxer natives (MediaExtractor)
// stay stubs.
//
// Handles are table keys, never pointers: a stale one from Java is a no-op.
// A polled picture is a handle too, alive until nativeVidReleaseFrame.
// Rendering one goes back up to Java (HostVideo.presentDecodedFrame), which
// knows which SurfaceView or SurfaceTexture the codec was given; no native
// plane exists here.

#include "host_natives.h"

#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "video_decode.h"
#include "video_player.h"
#include "winapk/mini_bionic.h"

namespace winapk::host {

namespace {

namespace vd = winapk::video;

std::mutex g_lock;
jlong g_next = 1;
// Shared, not unique: a decode runs outside g_lock (it takes milliseconds,
// and another codec must not wait for it), on a reference of its own.
std::map<jlong, std::shared_ptr<vd::Decoder>> g_decoders;
std::map<jlong, std::shared_ptr<vd::AudioDecoder>> g_audio_decoders;
std::map<jlong, std::shared_ptr<vd::Frame>> g_frames;  // polled, not yet released

std::vector<uint8_t> bytes_of(JNIEnv* env, jbyteArray a) {
    std::vector<uint8_t> v;
    if (!a) return v;
    const jsize n = env->GetArrayLength(a);
    if (n <= 0) return v;
    v.resize(static_cast<size_t>(n));
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return v;
}

std::string utf8_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

std::wstring wide_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* p = env->GetStringChars(s, nullptr);
    const jsize n = env->GetStringLength(s);
    std::wstring out(reinterpret_cast<const wchar_t*>(p), static_cast<size_t>(n));
    env->ReleaseStringChars(s, p);
    return out;
}

// The host file an app's media source names. HostVideo.open hands this native
// a plain file path (an Android path such as "/storage/emulated/0/clip.mp4" or
// a host path) or an http(s) URL; the Android path is mapped to its host file
// (paths.cpp), a URL or host path passes through unchanged (android_host_path
// leaves anything not rooted at a single '/').
std::wstring host_source_of(JNIEnv* env, jstring s) {
    if (!s) return {};
    return winapk::android_host_path(utf8_of(env, s).c_str());
}

// The picture as a Java int[] (ARGB, as Bitmap.setPixels takes it).
jintArray argb_array(JNIEnv* env, const vd::Frame& f) {
    const jsize n = static_cast<jsize>(f.argb.size());
    jintArray a = env->NewIntArray(n);
    if (a) env->SetIntArrayRegion(a, 0, n, reinterpret_cast<const jint*>(f.argb.data()));
    return a;
}

// ---- NativeMedia: the video decoder ----------------------------------------------------

jobjectArray JNICALL Vid_supportedMimes(JNIEnv* env, jclass) {
    const std::vector<std::string>& mimes = vd::supported_mimes();
    jclass str = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(mimes.size()), str, nullptr);
    for (size_t i = 0; i < mimes.size() && out; ++i) {
        jstring s = env->NewStringUTF(mimes[i].c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return out;
}
// The decoders used are the synchronous (software) MFTs; see video_decode.cpp.
jboolean JNICALL Vid_isHardware(JNIEnv*, jclass, jstring) { return JNI_FALSE; }
// No measured profile/level table: MediaCodecList keeps its declared ranges.
jintArray JNICALL Vid_capabilities(JNIEnv*, jclass, jstring) { return nullptr; }

jlong JNICALL Vid_open(JNIEnv* env, jclass, jstring mime, jint w, jint h, jbyteArray csd0, jbyteArray csd1, jboolean) {
    auto dec = vd::Decoder::open(utf8_of(env, mime), w, h, bytes_of(env, csd0), bytes_of(env, csd1));
    if (!dec) return 0;
    std::lock_guard<std::mutex> hold(g_lock);
    const jlong handle = g_next++;
    g_decoders[handle] = std::move(dec);
    return handle;
}

std::shared_ptr<vd::Decoder> decoder_of(jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_decoders.find(h);
    return it == g_decoders.end() ? nullptr : it->second;
}

jint JNICALL Vid_decode(JNIEnv* env, jclass, jlong h, jbyteArray au, jlong pts, jboolean eos) {
    const std::vector<uint8_t> data = bytes_of(env, au);
    auto dec = decoder_of(h);
    if (!dec) return -1;
    return dec->decode(data.empty() ? nullptr : data.data(), data.size(), pts, eos == JNI_TRUE) ? 0 : -1;
}

// {frameHandle, presentationTimeUs, bufferFlags}: a picture, or the one
// end-of-stream marker (handle 0, flags BUFFER_FLAG_END_OF_STREAM), or null.
jlongArray JNICALL Vid_poll(JNIEnv* env, jclass, jlong h) {
    jlong out[3] = {0, 0, 0};
    auto dec = decoder_of(h);
    if (!dec) return nullptr;
    bool eos = false;
    std::shared_ptr<vd::Frame> f = dec->poll(&eos);
    if (f) {
        std::lock_guard<std::mutex> hold(g_lock);
        out[0] = g_next++;
        out[1] = f->pts_us;
        g_frames[out[0]] = std::move(f);
    } else if (eos) {
        out[2] = 4;  // MediaCodec.BUFFER_FLAG_END_OF_STREAM
    } else {
        return nullptr;
    }
    jlongArray a = env->NewLongArray(3);
    if (a) env->SetLongArrayRegion(a, 0, 3, out);
    return a;
}

std::shared_ptr<vd::Frame> frame_of(jlong handle) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_frames.find(handle);
    return it == g_frames.end() ? nullptr : it->second;
}

void JNICALL Vid_render(JNIEnv* env, jclass, jlong handle, jint, jint) {
    std::shared_ptr<vd::Frame> f = frame_of(handle);
    if (!f) return;
    static jclass cls = nullptr;
    static jmethodID present = nullptr;
    if (!cls) {
        jclass local = env->FindClass("com/macapk/loader/HostVideo");
        if (!local) {
            env->ExceptionClear();
            return;
        }
        cls = static_cast<jclass>(env->NewGlobalRef(local));
        present = env->GetStaticMethodID(cls, "presentDecodedFrame", "([III)Z");
        env->DeleteLocalRef(local);
        if (!present) {
            env->ExceptionClear();
            return;
        }
    }
    jintArray px = argb_array(env, *f);
    if (!px) return;
    env->CallStaticBooleanMethod(cls, present, px, f->width, f->height);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    env->DeleteLocalRef(px);
}

void JNICALL Vid_releaseFrame(JNIEnv*, jclass, jlong handle) {
    std::lock_guard<std::mutex> hold(g_lock);
    g_frames.erase(handle);
}

// Packed BGRA bytes (an ARGB int is B, G, R, A in memory), stride = width * 4.
jint JNICALL Vid_frameBgra(JNIEnv* env, jclass, jlong handle, jbyteArray out, jintArray whs) {
    std::shared_ptr<vd::Frame> f = frame_of(handle);
    if (!f || !out || !whs || env->GetArrayLength(whs) < 3) return -1;
    const jint dims[3] = {f->width, f->height, f->width * 4};
    env->SetIntArrayRegion(whs, 0, 3, dims);
    const jsize need = static_cast<jsize>(f->argb.size() * 4);
    if (env->GetArrayLength(out) < need) return -1;
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte*>(f->argb.data()));
    return 0;
}

void JNICALL Vid_flush(JNIEnv*, jclass, jlong h) {
    if (auto dec = decoder_of(h)) dec->flush();
}

void JNICALL Vid_close(JNIEnv*, jclass, jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    g_decoders.erase(h);
}

// ---- NativeMedia: the audio decoder ----------------------------------------------------

jlong JNICALL Dec_open(JNIEnv* env, jclass, jint format_id, jint rate, jint channels, jbyteArray csd0) {
    auto dec = vd::AudioDecoder::open(format_id, rate, channels, bytes_of(env, csd0));
    if (!dec) return 0;
    std::lock_guard<std::mutex> hold(g_lock);
    const jlong handle = g_next++;
    g_audio_decoders[handle] = std::move(dec);
    return handle;
}

std::shared_ptr<vd::AudioDecoder> audio_decoder_of(jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_audio_decoders.find(h);
    return it == g_audio_decoders.end() ? nullptr : it->second;
}

jbyteArray JNICALL Dec_decode(JNIEnv* env, jclass, jlong h, jbyteArray au, jboolean eos) {
    const std::vector<uint8_t> data = bytes_of(env, au);
    auto dec = audio_decoder_of(h);
    if (!dec) return nullptr;
    const std::vector<int16_t> pcm = dec->decode(data.empty() ? nullptr : data.data(), data.size(), eos == JNI_TRUE);
    const jsize n = static_cast<jsize>(pcm.size() * 2);
    jbyteArray out = env->NewByteArray(n);
    if (out && n) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(pcm.data()));
    return out;
}

jint JNICALL Dec_outSampleRate(JNIEnv*, jclass, jlong h) {
    auto dec = audio_decoder_of(h);
    return dec ? dec->sample_rate() : 0;
}

jint JNICALL Dec_outChannels(JNIEnv*, jclass, jlong h) {
    auto dec = audio_decoder_of(h);
    return dec ? dec->channels() : 0;
}

void JNICALL Dec_close(JNIEnv*, jclass, jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    g_audio_decoders.erase(h);
}

// ---- HostVideo: MediaPlayer's video player ---------------------------------------------

struct PlayerSlot {
    std::shared_ptr<vd::Player> player;
    std::shared_ptr<vd::Frame> held;  // a picture the Java array was too small for
};
std::map<jlong, PlayerSlot> g_players;

std::shared_ptr<vd::Player> player_of(jlong h) {
    std::lock_guard<std::mutex> hold(g_lock);
    auto it = g_players.find(h);
    return it == g_players.end() ? nullptr : it->second.player;
}

// VP8 and VP9 come from Media Foundation here, when its codec packs are
// installed, not from a decoder built into the runtime.
jboolean JNICALL Hv_vpxBuiltIn(JNIEnv*, jclass) { return JNI_FALSE; }

// A path or URL, else the bytes; 0 when it cannot be opened or has no video
// track (the caller then plays it as sound alone).
jlong JNICALL Hv_open(JNIEnv* env, jclass, jstring path, jbyteArray data) {
    std::unique_ptr<vd::Source> src;
    if (path && env->GetStringLength(path) > 0) src = vd::Source::open(host_source_of(env, path));
    else src = vd::Source::open(bytes_of(env, data));
    auto player = vd::Player::open(std::move(src));
    if (!player) return 0;
    std::lock_guard<std::mutex> hold(g_lock);
    const jlong handle = g_next++;
    g_players[handle].player = std::move(player);
    return handle;
}

jint JNICALL Hv_width(JNIEnv*, jclass, jlong h) {
    auto p = player_of(h);
    return p ? p->width() : 0;
}
jint JNICALL Hv_height(JNIEnv*, jclass, jlong h) {
    auto p = player_of(h);
    return p ? p->height() : 0;
}
jint JNICALL Hv_durationMs(JNIEnv*, jclass, jlong h) {
    auto p = player_of(h);
    return p ? p->duration_ms() : 0;
}
void JNICALL Hv_start(JNIEnv*, jclass, jlong h) {
    if (auto p = player_of(h)) p->start();
}
void JNICALL Hv_pause(JNIEnv*, jclass, jlong h) {
    if (auto p = player_of(h)) p->pause();
}
void JNICALL Hv_stop(JNIEnv*, jclass, jlong h) {
    if (auto p = player_of(h)) p->stop();
}
void JNICALL Hv_seek(JNIEnv*, jclass, jlong h, jint ms) {
    if (auto p = player_of(h)) p->seek(ms);
}
void JNICALL Hv_setLooping(JNIEnv*, jclass, jlong h, jboolean loop) {
    if (auto p = player_of(h)) p->set_looping(loop == JNI_TRUE);
}
void JNICALL Hv_setVolume(JNIEnv*, jclass, jlong h, jfloat l, jfloat r) {
    if (auto p = player_of(h)) p->set_volume(l, r);
}
jint JNICALL Hv_positionMs(JNIEnv*, jclass, jlong h) {
    auto p = player_of(h);
    return p ? p->position_ms() : 0;
}
jboolean JNICALL Hv_isPlaying(JNIEnv*, jclass, jlong h) {
    auto p = player_of(h);
    return p && p->playing() ? JNI_TRUE : JNI_FALSE;
}

// Waits up to timeoutMs for the next picture and copies it into `out`, its
// size into dims[0..1]. 1: copied; 2: `out` is too small for it (dims say
// how big; the picture waits for the next call); 0: nothing yet; -1: the
// player is released, the caller's thread can end.
jint JNICALL Hv_awaitFrame(JNIEnv* env, jclass, jlong h, jintArray out, jintArray dims, jint timeout_ms) {
    std::shared_ptr<vd::Player> p;
    std::shared_ptr<vd::Frame> f;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_players.find(h);
        if (it == g_players.end()) return -1;
        p = it->second.player;
        f = std::move(it->second.held);
        it->second.held.reset();
    }
    if (!f) {
        bool released = false;
        f = p->await_frame(timeout_ms, &released);
        if (released) return -1;
        if (!f) return 0;
    }
    const jint d[2] = {f->width, f->height};
    if (dims && env->GetArrayLength(dims) >= 2) env->SetIntArrayRegion(dims, 0, 2, d);
    const jsize n = static_cast<jsize>(f->argb.size());
    if (!out || env->GetArrayLength(out) < n) {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_players.find(h);
        if (it != g_players.end()) it->second.held = std::move(f);
        return 2;
    }
    env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint*>(f->argb.data()));
    return 1;
}

void JNICALL Hv_release(JNIEnv*, jclass, jlong h) {
    std::shared_ptr<vd::Player> p;
    {
        std::lock_guard<std::mutex> hold(g_lock);
        auto it = g_players.find(h);
        if (it == g_players.end()) return;
        p = std::move(it->second.player);
        g_players.erase(it);
    }
    p->release();
}

constexpr const char* kMedia = "com.macapk.loader.NativeMedia";
constexpr const char* kHost = "com.macapk.loader.HostVideo";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kMedia, "nativeVideoSupportedMimes", "()[Ljava/lang/String;", Vid_supportedMimes),
    WINAPK_HOST_NATIVE(kMedia, "nativeVideoIsHardware", "(Ljava/lang/String;)Z", Vid_isHardware),
    WINAPK_HOST_NATIVE(kMedia, "nativeVideoCapabilities", "(Ljava/lang/String;)[I", Vid_capabilities),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidOpen", "(Ljava/lang/String;II[B[BZ)J", Vid_open),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidDecode", "(J[BJZ)I", Vid_decode),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidPoll", "(J)[J", Vid_poll),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidRender", "(JII)V", Vid_render),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidReleaseFrame", "(J)V", Vid_releaseFrame),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidFrameBgra", "(J[B[I)I", Vid_frameBgra),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidFlush", "(J)V", Vid_flush),
    WINAPK_HOST_NATIVE(kMedia, "nativeVidClose", "(J)V", Vid_close),
    // Upstream decodes VP8 and VP9 with libvpx behind separate natives of
    // the same shape; here they are Media Foundation decoders like the rest.
    WINAPK_HOST_NATIVE(kMedia, "nativeVp8Open", "(Ljava/lang/String;II[B[BZ)J", Vid_open),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp8Decode", "(J[BJZ)I", Vid_decode),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp8Poll", "(J)[J", Vid_poll),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp8Flush", "(J)V", Vid_flush),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp8Close", "(J)V", Vid_close),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp9Open", "(Ljava/lang/String;II[B[BZ)J", Vid_open),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp9Decode", "(J[BJZ)I", Vid_decode),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp9Poll", "(J)[J", Vid_poll),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp9Flush", "(J)V", Vid_flush),
    WINAPK_HOST_NATIVE(kMedia, "nativeVp9Close", "(J)V", Vid_close),
    WINAPK_HOST_NATIVE(kMedia, "nativeDecOpen", "(III[B)J", Dec_open),
    WINAPK_HOST_NATIVE(kMedia, "nativeDecDecode", "(J[BZ)[B", Dec_decode),
    WINAPK_HOST_NATIVE(kMedia, "nativeDecOutSampleRate", "(J)I", Dec_outSampleRate),
    WINAPK_HOST_NATIVE(kMedia, "nativeDecOutChannels", "(J)I", Dec_outChannels),
    WINAPK_HOST_NATIVE(kMedia, "nativeDecClose", "(J)V", Dec_close),
    WINAPK_HOST_NATIVE(kHost, "nativeVpxBuiltIn", "()Z", Hv_vpxBuiltIn),
    WINAPK_HOST_NATIVE(kHost, "nativeOpen", "(Ljava/lang/String;[B)J", Hv_open),
    WINAPK_HOST_NATIVE(kHost, "nativeWidth", "(J)I", Hv_width),
    WINAPK_HOST_NATIVE(kHost, "nativeHeight", "(J)I", Hv_height),
    WINAPK_HOST_NATIVE(kHost, "nativeDurationMs", "(J)I", Hv_durationMs),
    WINAPK_HOST_NATIVE(kHost, "nativeStart", "(J)V", Hv_start),
    WINAPK_HOST_NATIVE(kHost, "nativePause", "(J)V", Hv_pause),
    WINAPK_HOST_NATIVE(kHost, "nativeStop", "(J)V", Hv_stop),
    WINAPK_HOST_NATIVE(kHost, "nativeSeek", "(JI)V", Hv_seek),
    WINAPK_HOST_NATIVE(kHost, "nativeSetLooping", "(JZ)V", Hv_setLooping),
    WINAPK_HOST_NATIVE(kHost, "nativeSetVolume", "(JFF)V", Hv_setVolume),
    WINAPK_HOST_NATIVE(kHost, "nativePositionMs", "(J)I", Hv_positionMs),
    WINAPK_HOST_NATIVE(kHost, "nativeIsPlaying", "(J)Z", Hv_isPlaying),
    WINAPK_HOST_NATIVE(kHost, "nativeAwaitFrame", "(J[I[II)I", Hv_awaitFrame),
    WINAPK_HOST_NATIVE(kHost, "nativeRelease", "(J)V", Hv_release),
};

}  // namespace

HostMethods native_media_video_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
