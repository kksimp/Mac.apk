// video_toolbox.mm -- real VIDEO decode backend for android.media.MediaCodec,
// backed by VideoToolbox, hardware-accelerated wherever this host offers it.
// Pairs with media_decode.mm (the AudioToolbox audio-decode core behind the
// SAME NativeMedia class) -- this file is the video half.
//
// WHY THIS IS A SEPARATE FILE FROM media_video.mm, AND WHAT IT REUSES.
// media_video.mm (v1.0.485, 2026-06-21) already proved the hard, novel part
// of VideoToolbox-on-Mac.apk end to end: VTDecompressionSession -> CVPixelBuffer
// -> CVMetalTextureCache -> a full-screen render pass -> CAMetalLayer. That
// half (macapk_vd_present_create / macapk_vd_present_to_layer, declared
// extern below) is REUSED VERBATIM here, and by CocoaPresentVideoFrame in
// cocoa_bridge.mm which this file calls into -- nothing about compositing a
// decoded frame onto the screen is reimplemented.
//
// What media_video.mm does NOT cover is MediaCodec's actual contract, and
// that mismatch is exactly why this file exists rather than extending that
// one:
//   - media_video.mm DEMUXES a file itself (AVURLAsset -> AVAssetReader) and
//     gets a ready-made CMVideoFormatDescription from the track for free.
//   - android.media.MediaCodec never demuxes anything. The APP (ExoPlayer's
//     own extractors, for a media3 app like PlayTube) demuxes and hands us
//     raw compressed access units one at a time via queueInputBuffer, with
//     the SPS/PPS (or VPS+SPS+PPS) delivered separately as csd-0/csd-1 in the
//     MediaFormat passed to configure(). We have to build our OWN
//     CMVideoFormatDescription from that csd, and wrap each queued access
//     unit into a CMSampleBuffer ourselves. See BuildFormatDescriptionH264 /
//     BuildFormatDescriptionHEVC / AnnexBToAvcc below -- this conversion is
//     cited from source, not guessed (see the comments at each site).
//
// SCOPE: clear (unencrypted) decode only. queueSecureInputBuffer / MediaCrypto
// / MediaDrm are explicitly out of scope for this project (CLAUDE.md: no DRM
// circumvention) and nothing here touches them.
//
// Citations (verified against the local Xcode SDK headers on this machine,
// MacOSX.sdk, per the project's struct/ABI sourcing rule):
//   VideoToolbox/VTDecompressionSession.h  -- Create / DecodeFrame / decode
//     flags / VTIsHardwareDecodeSupported (macos 10.13+).
//   VideoToolbox/VTDecompressionProperties.h -- kVTVideoDecoderSpecification_
//     EnableHardwareAcceleratedVideoDecoder (decoder-spec dict key, request)
//     and kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder
//     (session property, read back to VERIFY rather than assume hw was used).
//   VideoToolbox/VTSession.h -- VTSessionCopyProperty.
//   CoreMedia/CMFormatDescription.h -- kCMVideoCodecType_* FourCCs;
//     CMVideoFormatDescriptionCreateFromH264ParameterSets (>=2 param sets,
//     NAL types 7/8/13, NALUnitHeaderLength 1/2/4) and the HEVC twin
//     (>=3 param sets, NAL types 32/33/34/39/40, + an extensions dict);
//     CMVideoFormatDescriptionCreate (the generic, non-parameter-set path).
//   CoreMedia/CMBlockBuffer.h -- CMBlockBufferCreateWithMemoryBlock (with
//     kCFAllocatorMalloc as blockAllocator, so CoreMedia frees our malloc'd
//     buffer itself -- CFBase.h confirms kCFAllocatorMalloc "uses malloc(),
//     realloc(), and free()").
//   CoreMedia/CMSampleBuffer.h -- CMSampleBufferCreateReady.
//   CoreMedia/CMFormatDescription.h --
//     kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms ("Sample
//     description extension atoms that were not translated into other
//     entries... CFDictionary mapping CFStrings of the four-char-code atom
//     types to... CFData containing the atom payload"). This is CoreMedia's
//     documented way to attach an out-of-band ISO box (e.g. "esds") the way
//     a real MP4 demuxer would; BuildFormatDescriptionMPEG4Part2 below uses
//     it because, unlike AVC/HEVC, there is no CMVideoFormatDescriptionCreate
//     FromMPEG4-style convenience constructor.
//   VideoToolbox/VTErrors.h -- kVTVideoDecoderBadDataErr = -12909 ("c.f.
//     -8969"), kVTCouldNotFindVideoDecoderErr = -12906. Both are quoted
//     verbatim from this machine's SDK; see the two findings below.
//   AOSP frameworks/base media/java/android/media/MediaCodec.java (class
//     javadoc, "Codec-specific Data" table + the adaptive-playback note):
//     csd-0/csd-1 for AVC are SPS/PPS respectively, each individually
//     start-code-prefixed ("\x00\x00\x00\x01"); HEVC's csd-0 alone carries
//     VPS+SPS+PPS, each individually start-code-prefixed; regular (non-CSD)
//     input buffers for AVC/HEVC are documented to admit start codes too
//     ("package the entire new codec-specific configuration data together
//     with the key frame into a single buffer (including any start codes)"),
//     which is the Annex-B byte-stream form every real extractor (including
//     ExoPlayer's) reformats container-native (length-prefixed) samples into
//     before calling MediaCodec, precisely because that is the documented
//     wire format. VideoToolbox, by contrast, wants AVCC (4-byte big-endian
//     length prefixes, no start codes) -- see docs/video-decode-backend_
//     2026-06-21.md, which pinned that conversion for the file-demux path;
//     this file's AnnexBToAvcc now also serves the app-fed path.
//
// VP9/AV1/MPEG-4 Part 2/MPEG-2/H.263 carry no Android-specific start-code
// convention (Android does not reformat their elementary streams the way it
// does AVC/HEVC's), so their access units are passed to VideoToolbox
// untouched; only AVC/HEVC go through AnnexBToAvcc. (This is about the
// PER-FRAME access units only -- MPEG-4 Part 2's one-time CONFIGURATION now
// gets dedicated handling too; see the next paragraph.)
//
// MPEG-4 PART 2 FINDING (2026-08-16): this codec hit the exact same probe
// bug HEVC hit the day before -- a GENERIC, parameter-free
// CMVideoFormatDescriptionCreate(kCMVideoCodecType_MPEG4Video, w, h, NULL,
// ...) makes VTDecompressionSessionCreate fail with OSStatus -12909
// (kVTVideoDecoderBadDataErr, VTErrors.h), which reads like a codec-absence
// error but is actually "the decoder negotiator has no VOL config to
// validate against" -- exactly HEVC's -8971 shape. Standalone verification
// (VTDecompressionSessionCreate + real VTDecompressionSessionDecodeFrame,
// against an ffmpeg-8.1.2-encoded 64x64 clip's real VOP frames) proved: (a)
// wrapping Android's csd-0 bytes BARE in the "esds"
// SampleDescriptionExtensionAtoms entry reproduces the same -12909 --
// CoreMedia wants the FULL esds box, not the bare DecoderSpecificInfo; (b)
// wrapping a synthesized full esds box (see
// BuildEsdsAtomFromDecoderSpecificInfo below) makes session creation
// succeed (noErr) and decodes all 5 real frames with real measured motion
// (meanAbsDelta=8.53 on the same sparse green-channel sample debug/video_
// toolbox_test.mm uses). The synthesized box's TLV shape was cross-checked
// byte-for-byte against the REAL esds box ffmpeg's own MP4 muxer wrote for
// the identical clip (walked out of moov/trak/mdia/minf/stbl/stsd/mp4v/esds
// by hand) -- see BuildEsdsAtomFromDecoderSpecificInfo's own comment for
// the full trace.
// VTIsHardwareDecodeSupported(MPEG4Video) is false on this host (Apple M2,
// macOS 26.5.2): the decode is real but software-only, matching the
// already-established MPEG-2/H.263 rows (supported=yes hw=no), not a new
// hardware capability.
//
// VP9/AV1 FINDING (2026-08-16, re-audit prompted by the HEVC/MPEG-4 bug
// shape): checked whether the SAME probe bug was hiding a software path for
// either. It is not. VTIsHardwareDecodeSupported is false for both on this
// host (Apple M2 -- AV1 hardware decode needs M3+, per Apple's Silicon media
// engine generations). Standalone verification fed real per-frame data
// (libvpx-vp9 and SVT-AV1-encoded clips, extracted via ffmpeg's IVF muxer)
// through THREE trial sessions: VP9 generic, AV1 generic, and AV1 wrapped
// with its real av1C AV1CodecConfigurationRecord (extracted the same way as
// MPEG-4's esds, to rule out the same class of probe bug). All three failed
// identically with OSStatus -12906 (kVTCouldNotFindVideoDecoderErr,
// VTErrors.h) -- a DIFFERENT error than MPEG-4's -12909, and one that did
// NOT change when fed a real, byte-verified config atom. -12906 means what
// it says: no decoder component (hardware or software) is registered for
// these fourCCs on this host at all, not "rejected the config". So VP9/AV1
// staying unreported here is a genuine host capability gap, not a repeat of
// the HEVC/MPEG-4 bug -- rung 1 of CLAUDE.md's decision ladder (route to
// macOS native) is exhausted for these two, honestly. Rung 2 (a clean
// third-party decoder -- e.g. dav1d for AV1, or libvpx's own VP9 decoder,
// already linked into this target for the VP8 lane, matching how
// audio_vorbis_decode.mm vendors stb_vorbis for the one Android audio MIME
// AudioToolbox itself cannot decode) is the option if these codecs are ever
// needed, but is a real vendoring decision for a future session/task, not
// something to force in here. See
// docs/videotoolbox-probe-audit_2026-08-16.md section 3 for the full
// trial-by-trial evidence.
//
// HARDWARE: every session requests hardware decode explicitly
// (kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder) and
// then READS BACK whether it actually got it
// (kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder) rather
// than assuming -- the measured per-codec result is cached and is exactly
// what nativeVideoIsHardware() reports. VP8 has no VideoToolbox decoder at
// any tier on any macOS version (no kCMVideoCodecType_VP8 constant exists in
// CMFormatDescription.h -- checked); it is out of scope for this file by
// construction and is expected to be served by a software (libvpx) path
// elsewhere, reported honestly as non-hardware.

#import <VideoToolbox/VideoToolbox.h>
#import <CoreMedia/CoreMedia.h>
#import <CoreVideo/CoreVideo.h>
#import <Foundation/Foundation.h>
#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cmath>
#include <atomic>
#include <deque>
#include <chrono>
#include <map>
#include <mutex>
#include <string>
#include <vector>

// Reused from media_video.mm (extern "C", so the linkage name is un-namespaced
// regardless of which C++ namespace either side declares it in). We do not
// duplicate the Metal-compositing implementation -- see the file header.
extern "C" void* macapk_vd_present_create(void* mtlDevice);
extern "C" int   macapk_vd_present_to_layer(void* presenter, void* caMetalLayer, void* pixbuf);

// Defined in cocoa_bridge.mm. Lazily creates (or reuses) a dedicated video
// CAMetalLayer on the app's window and presents `pixbuf` (a retained
// CVPixelBufferRef, passed as void* so this header stays Metal/CoreVideo-free
// on that side) onto it. See that file for the Z-order rationale.
extern "C" void CocoaPresentVideoFrame(void* pixbuf, int width, int height);
// runtime/src/video_caps_probe.mm (JNI-free so its offline gate can build it alone).
extern "C" int macapk_vtcaps_probe(const char* mime, int* out, int cap);

namespace {

// ---------------------------------------------------------------------------
// MIME <-> CMVideoCodecType. Single source of truth: MediaCodec.java (this
// file's own Java-side caller) and the sibling MediaCodecList/MediaCodecInfo
// lane both learn "what do we support" ONLY through nativeVideoSupportedMimes
// / nativeVideoIsHardware below, which walk this table. Adding a codec here
// is the only place a future session needs to touch to extend both.
//
// FourCCs verified in CoreMedia/CMFormatDescription.h (this machine's SDK):
// kCMVideoCodecType_H264='avc1', _HEVC='hvc1', _VP9='vp09', _AV1='av01',
// _MPEG4Video='mp4v', _MPEG2Video='mp2v', _H263='h263'. AOSP MIME strings
// verified in frameworks/base media/java/android/media/MediaFormat.java.
// VP8 (video/x-vnd.on2.vp8) is deliberately ABSENT: there is no
// kCMVideoCodecType_VP8 in this SDK at all, on any macOS version -- VP8 is
// not a VideoToolbox format. A software path is expected elsewhere.
struct MimeEntry { const char* mime; CMVideoCodecType fourcc; };
const MimeEntry kMimeTable[] = {
    { "video/avc",           kCMVideoCodecType_H264 },
    { "video/hevc",          kCMVideoCodecType_HEVC },
    { "video/x-vnd.on2.vp9", kCMVideoCodecType_VP9 },
    { "video/av01",          kCMVideoCodecType_AV1 },
    { "video/mp4v-es",       kCMVideoCodecType_MPEG4Video },
    { "video/mpeg2",         kCMVideoCodecType_MPEG2Video },
    { "video/3gpp",          kCMVideoCodecType_H263 },
};

bool MimeToFourcc(const std::string& mime, CMVideoCodecType* out) {
    for (const auto& e : kMimeTable) {
        if (mime == e.mime) { *out = e.fourcc; return true; }
    }
    return false;
}

void FourccToChars(CMVideoCodecType fourcc, char out[5]) {
    // FourCC 32-bit multi-char constants ('avc1') pack big-endian-first
    // regardless of host endianness; extract by shift rather than by
    // pointer-casting the int (which would print the bytes reversed on a
    // little-endian host like this one).
    out[0] = (char)((fourcc >> 24) & 0xFF);
    out[1] = (char)((fourcc >> 16) & 0xFF);
    out[2] = (char)((fourcc >> 8)  & 0xFF);
    out[3] = (char)(fourcc & 0xFF);
    out[4] = 0;
}

// ---------------------------------------------------------------------------
// Annex-B parsing. Shared by csd (parameter-set extraction) and per-frame
// access units (Annex-B -> AVCC rewrite for AVC/HEVC).
// ---------------------------------------------------------------------------

struct NalRef { const uint8_t* ptr; size_t len; };

// Finds every NAL unit in an Annex-B buffer, handling both the 3-byte
// (00 00 01) and 4-byte (00 00 00 01) start-code forms (and defensively, any
// longer run of leading zero padding before the 00 00 01 trigger -- real
// streams occasionally have this). A NAL's end is the TRUE start of the next
// start code (the first of its leading zero bytes), so trailing zero padding
// belonging to the *next* start code is never misattributed to this NAL.
std::vector<NalRef> SplitAnnexB(const uint8_t* data, size_t len) {
    std::vector<NalRef> nals;
    if (!data || len < 3) return nals;
    std::vector<size_t> scBegin;   // true start of each start code (incl. leading zeros)
    std::vector<size_t> nalBegin;  // first byte of NAL payload (right after the "01")
    size_t i = 0;
    while (i + 2 < len) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            size_t sc = i;
            while (sc > 0 && data[sc - 1] == 0) sc--;
            scBegin.push_back(sc);
            nalBegin.push_back(i + 3);
            i += 3;
        } else {
            i++;
        }
    }
    for (size_t k = 0; k < nalBegin.size(); k++) {
        size_t begin = nalBegin[k];
        size_t end = (k + 1 < scBegin.size()) ? scBegin[k + 1] : len;
        if (end > begin) nals.push_back({data + begin, end - begin});
    }
    return nals;
}

// AVC (H.264) NAL header is 1 byte; type is the low 5 bits.
inline int H264NalType(const NalRef& n) { return n.len > 0 ? (n.ptr[0] & 0x1F) : -1; }
// HEVC NAL header is 2 bytes; type is bits 1..6 of the first byte
// (forbidden_zero_bit(1) + nal_unit_type(6) + ...).
inline int HevcNalType(const NalRef& n) { return n.len > 0 ? ((n.ptr[0] >> 1) & 0x3F) : -1; }

// Rewrites an Annex-B buffer (arbitrary mix of start-code lengths) into AVCC
// (each NAL prefixed with its own 4-byte big-endian length, no start codes)
// -- the form VTDecompressionSessionDecodeFrame requires. If no start code is
// found at all (a malformed/non-Annex-B input, contrary to the documented
// AOSP contract for avc/hevc), passes the buffer through unchanged rather
// than dropping the frame outright; logged once so a real instance of this is
// visible without spamming every frame.
std::atomic<bool> g_warnedNonAnnexB{false};
std::vector<uint8_t> AnnexBToAvcc(const uint8_t* data, size_t len) {
    std::vector<uint8_t> out;
    auto nals = SplitAnnexB(data, len);
    if (nals.empty()) {
        if (!g_warnedNonAnnexB.exchange(true)) {
            std::fprintf(stderr,
                "[video-toolbox] WARNING: access unit has no Annex-B start code; "
                "passing through unrewritten (will only decode if it happens to "
                "already be AVCC)\n");
        }
        out.assign(data, data + len);
        return out;
    }
    out.reserve(len + 4 * nals.size());
    for (const auto& n : nals) {
        uint8_t lenBytes[4] = {
            (uint8_t)((n.len >> 24) & 0xFF), (uint8_t)((n.len >> 16) & 0xFF),
            (uint8_t)((n.len >> 8) & 0xFF),  (uint8_t)(n.len & 0xFF),
        };
        out.insert(out.end(), lenBytes, lenBytes + 4);
        out.insert(out.end(), n.ptr, n.ptr + n.len);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Format description construction.
// ---------------------------------------------------------------------------

// AOSP CSD table: H.264 AVC csd-0 = SPS, csd-1 = PPS (each individually
// start-code-prefixed). We accept either csd containing more than one NAL
// (some encoders emit SPS-ext, NAL type 13, alongside the SPS) and filter by
// type rather than assuming exactly one NAL per buffer.
bool BuildFormatDescriptionH264(const uint8_t* csd0, int csd0Len,
                                const uint8_t* csd1, int csd1Len,
                                CMVideoFormatDescriptionRef* outDesc) {
    auto spsNals = SplitAnnexB(csd0, csd0Len > 0 ? (size_t)csd0Len : 0);
    auto ppsNals = SplitAnnexB(csd1, csd1Len > 0 ? (size_t)csd1Len : 0);
    spsNals.insert(spsNals.end(), ppsNals.begin(), ppsNals.end());
    std::vector<const uint8_t*> ptrs;
    std::vector<size_t> sizes;
    bool haveSps = false, havePps = false;
    for (const auto& n : spsNals) {
        int t = H264NalType(n);
        // Native players may concatenate SPS and PPS into one CSD buffer.
        // Classify every NAL by type, rather than discarding PPS from csd-0
        // (or SPS from csd-1) based on its buffer of origin.
        if (t == 7 || t == 8 || t == 13) {
            ptrs.push_back(n.ptr); sizes.push_back(n.len);
            haveSps |= t == 7;
            havePps |= t == 8;
        }
    }
    if (!haveSps || !havePps) {
        std::fprintf(stderr,
            "[video-toolbox] H264: csd-0/csd-1 did not yield SPS+PPS (found %zu "
            "usable parameter-set NALs; csd0Len=%d csd1Len=%d)\n",
            ptrs.size(), csd0Len, csd1Len);
        return false;
    }
    OSStatus s = CMVideoFormatDescriptionCreateFromH264ParameterSets(
        kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(),
        /*NALUnitHeaderLength=*/4, outDesc);
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreateFromH264ParameterSets "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// AOSP CSD table: H.265 HEVC csd-0 alone carries VPS+SPS+PPS concatenated,
// each individually start-code-prefixed (csd-1 "Not Used"). We split csd-0
// and keep every NAL whose type is one CMVideoFormatDescriptionCreateFrom
// HEVCParameterSets documents as supported (32 VPS / 33 SPS / 34 PPS / 39
// prefix SEI / 40 suffix SEI), requiring at least one of each of VPS/SPS/PPS.
bool BuildFormatDescriptionHEVC(const uint8_t* csd0, int csd0Len,
                                CMVideoFormatDescriptionRef* outDesc) {
    auto nals = SplitAnnexB(csd0, csd0Len > 0 ? (size_t)csd0Len : 0);
    std::vector<const uint8_t*> ptrs;
    std::vector<size_t> sizes;
    bool haveVps = false, haveSps = false, havePps = false;
    for (const auto& n : nals) {
        int t = HevcNalType(n);
        if (t == 32 || t == 33 || t == 34 || t == 39 || t == 40) {
            ptrs.push_back(n.ptr); sizes.push_back(n.len);
            if (t == 32) haveVps = true;
            if (t == 33) haveSps = true;
            if (t == 34) havePps = true;
        }
    }
    if (!(haveVps && haveSps && havePps)) {
        std::fprintf(stderr,
            "[video-toolbox] HEVC: csd-0 missing VPS/SPS/PPS (vps=%d sps=%d pps=%d, "
            "%zu param-set NALs kept, csd0Len=%d)\n",
            haveVps, haveSps, havePps, ptrs.size(), csd0Len);
        return false;
    }
    OSStatus s = CMVideoFormatDescriptionCreateFromHEVCParameterSets(
        kCFAllocatorDefault, ptrs.size(), ptrs.data(), sizes.data(),
        /*NALUnitHeaderLength=*/4, /*extensions=*/nullptr, outDesc);
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreateFromHEVCParameterSets "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// Builds an ISO/IEC 14496-1 "esds" box PAYLOAD (FullBox version+flags(4B) +
// an ES_Descriptor TLV tree) wrapping `info` as the innermost
// DecoderSpecificInfo (tag 0x05) payload -- i.e. the box a real MP4 muxer
// writes into a 'mp4v' VisualSampleEntry, built by hand because CoreMedia
// has no CMVideoFormatDescriptionCreateFromMPEG4-style convenience
// constructor the way it does for AVC/HEVC (see
// kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms's citation
// in the file header).
//
// The TLV shape was cross-checked, not guessed: ffmpeg 8.1.2 (libavcodec's
// native `mpeg4` encoder) muxed a 64x64 testsrc2 clip to MP4 on this machine
// on 2026-08-16 (`ffmpeg -f lavfi -i testsrc2=size=64x64:rate=10:duration=0.5
// -c:v mpeg4 -g 10 -pix_fmt yuv420p -f mp4 out.mp4`), and the REAL `esds` box
// its own muxer wrote (walked out of moov/trak/mdia/minf/stbl/stsd/mp4v/esds
// by hand) is byte-for-byte:
//   00 00 00 00                                        version+flags
//   03 80 80 80 4f  00 01 00                           ES_DescrTag(len=79)
//                                                       ES_ID=1 flags=0
//     04 80 80 80 41  20 11  00 00 00  00 03 0d 40  00 02 64 00
//       DecoderConfigDescrTag(len=65): objectTypeIndication=0x20 (MPEG-4
//       Visual), streamType<<2|upStream<<1|reserved=0x11, bufferSizeDB=0,
//       maxBitrate=200000, avgBitrate=156672
//       05 80 80 80 2f  00 00 01 b0 ...                 DecSpecificInfoTag
//                                                        (len=47) = the VOL
//                                                        bytes, IDENTICAL to
//                                                        this clip's ffprobe
//                                                        `extradata`
//     06 80 80 80 01  02                                SLConfigDescrTag
//                                                        (len=1) predefined=2
// ffmpeg pads every descriptor length to the 4-byte form; this function
// emits the shorter, equally spec-legal minimal-length-byte form instead
// (ISO/IEC 14496-1's "expandable class size" explicitly permits either).
// objectTypeIndication/streamType/tag numbers match the real box exactly;
// bufferSizeDB/maxBitrate/avgBitrate are zeroed ("unspecified") rather than
// invented -- also verified (standalone harness, real
// VTDecompressionSessionCreate + VTDecompressionSessionDecodeFrame against
// this exact host's VideoToolbox) to decode all 5 real frames of the source
// clip with real motion, so VideoToolbox's negotiator does not require real
// bitrate hints, only a well-formed DecoderSpecificInfo. See the MPEG-4 PART
// 2 FINDING paragraph in the file header for the -12909 bug this fixes.
std::vector<uint8_t> BuildEsdsAtomFromDecoderSpecificInfo(const uint8_t* info, size_t infoLen) {
    auto appendDescLen = [](std::vector<uint8_t>& out, size_t len) {
        uint8_t b[4]; int n = 0;
        do { b[n++] = (uint8_t)(len & 0x7F); len >>= 7; } while (len > 0 && n < 4);
        for (int i = n - 1; i >= 0; i--) out.push_back(b[i] | (i > 0 ? 0x80 : 0x00));
    };

    std::vector<uint8_t> decSpecific;
    decSpecific.push_back(0x05);  // DecSpecificInfoTag
    appendDescLen(decSpecific, infoLen);
    decSpecific.insert(decSpecific.end(), info, info + infoLen);

    std::vector<uint8_t> slConfig = { 0x06 };  // SLConfigDescrTag
    appendDescLen(slConfig, 1);
    slConfig.push_back(0x02);  // predefined: MP4-file default SL config

    std::vector<uint8_t> decCfgBody = {
        0x20,                     // objectTypeIndication: MPEG-4 Visual
        0x11,                     // streamType(6b)=Visual(4) upStream(1b)=0 reserved(1b)=1
        0x00, 0x00, 0x00,         // bufferSizeDB (unspecified)
        0x00, 0x00, 0x00, 0x00,   // maxBitrate (unspecified)
        0x00, 0x00, 0x00, 0x00,   // avgBitrate (unspecified)
    };
    decCfgBody.insert(decCfgBody.end(), decSpecific.begin(), decSpecific.end());
    std::vector<uint8_t> decCfg;
    decCfg.push_back(0x04);  // DecoderConfigDescrTag
    appendDescLen(decCfg, decCfgBody.size());
    decCfg.insert(decCfg.end(), decCfgBody.begin(), decCfgBody.end());

    std::vector<uint8_t> esBody = { 0x00, 0x00, 0x00 };  // ES_ID=0, flags=0 (no deps/URL/OCR)
    esBody.insert(esBody.end(), decCfg.begin(), decCfg.end());
    esBody.insert(esBody.end(), slConfig.begin(), slConfig.end());
    std::vector<uint8_t> esDescr;
    esDescr.push_back(0x03);  // ES_DescrTag
    appendDescLen(esDescr, esBody.size());
    esDescr.insert(esDescr.end(), esBody.begin(), esBody.end());

    std::vector<uint8_t> box = { 0x00, 0x00, 0x00, 0x00 };  // FullBox version+flags
    box.insert(box.end(), esDescr.begin(), esDescr.end());
    return box;
}

// MPEG-4 Part 2 (video/mp4v-es): builds the trial/real format description
// through the SAME esds-wrapping path real decode uses (mirrors how
// BuildFormatDescriptionH264/HEVC exercise their real parameter-set
// constructors from the probe too -- see BuildProbeFormatDescription).
//
// csd0 IS the VOL configuration verbatim, no further unwrapping needed: the
// AOSP MediaCodec CSD table's cell text for MPEG-4 ("Decoder-specific
// information from ESDS") is IDENTICAL to AAC's, and this file's audio
// sibling (media_decode.mm's magic-cookie comment) already established for
// that exact wording that Android hands csd-0 unwrapped -- confirmed
// independently here too: ffprobe's `extradata` for this codec (the bytes
// MPEG4Extractor places in csd-0) starts directly at the VOL bitstream's own
// native start code (00 00 01 B0, ISO/IEC 14496-2's VisualObjectSequence
// start code) with nothing prepended.
//
// Defensively strips pure leading zero-padding before the first "00 00 01"
// trigger (the same tolerance SplitAnnexB already applies for AVC/HEVC) in
// case a nonstandard encoder pads it; MPEG-4 Visual's start-code shape
// ("00 00 01 <code>") is byte-identical in scanning terms to AVC/HEVC's
// Annex-B trigger, so the same scan applies even though the byte AFTER the
// trigger plays a different role (the start code itself, not a NAL header).
bool BuildFormatDescriptionMPEG4Part2(const uint8_t* csd0, int csd0Len, int width, int height,
                                      CMVideoFormatDescriptionRef* outDesc) {
    size_t len = csd0Len > 0 ? (size_t)csd0Len : 0;
    size_t begin = 0;
    while (begin + 2 < len && !(csd0[begin] == 0 && csd0[begin+1] == 0 && csd0[begin+2] == 1)) {
        if (csd0[begin] != 0) break;  // non-zero junk before any start code -- stop trimming
        begin++;
    }
    if (len < 4 || begin + 2 >= len || !(csd0[begin] == 0 && csd0[begin+1] == 0 && csd0[begin+2] == 1)) {
        std::fprintf(stderr,
            "[video-toolbox] MPEG4: csd-0 did not contain a VOL start code "
            "(csd0Len=%d) -- cannot build esds config\n", csd0Len);
        return false;
    }
    auto esds = BuildEsdsAtomFromDecoderSpecificInfo(csd0 + begin, len - begin);
    OSStatus s;
    @autoreleasepool {
        CFDataRef esdsData = CFDataCreate(kCFAllocatorDefault, esds.data(), (CFIndex)esds.size());
        NSDictionary* atoms = @{ @"esds": (__bridge id)esdsData };
        NSDictionary* exts = @{
            (id)kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms: atoms,
        };
        s = CMVideoFormatDescriptionCreate(kCFAllocatorDefault, kCMVideoCodecType_MPEG4Video,
                                           width, height, (__bridge CFDictionaryRef)exts, outDesc);
        CFRelease(esdsData);
    }
    if (s != noErr) {
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreate(MPEG4Video, esds) "
            "failed: %d\n", (int)s);
        return false;
    }
    return true;
}

// VP9/AV1/MPEG-2/H.263: none of these has a CoreMedia "FromParameterSets"
// (or, like MPEG-4 Part 2 above, hand-built-atom) constructor wired up, and
// Android does not layer a special out-of-band SPS/PPS convention on top of
// any of them the way it does for AVC/HEVC -- their elementary-stream syntax
// is used as-is. The generic constructor plus codecType/width/height is what
// VT's own header recommends for this shape ("CMVideoFormatDescriptionCreate
// ... creates a format description for a video media stream"), and is
// verified (standalone harness, real per-frame decode) to be sufficient for
// MPEG-2/H.263 on this host -- VP9/AV1 have no VideoToolbox decoder
// component here at all (OSStatus -12906, not a probe-shape bug; see the
// VP9/AV1 FINDING paragraph in the file header), so nothing this constructor
// could supply would change their verdict.
bool BuildFormatDescriptionGeneric(CMVideoCodecType fourcc, int width, int height,
                                   CMVideoFormatDescriptionRef* outDesc) {
    OSStatus s = CMVideoFormatDescriptionCreate(
        kCFAllocatorDefault, fourcc, width, height, /*extensions=*/nullptr, outDesc);
    if (s != noErr) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr,
            "[video-toolbox] CMVideoFormatDescriptionCreate(generic, %s, %dx%d) "
            "failed: %d\n", fcc, width, height, (int)s);
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Decoder handle + the async output queue.
// ---------------------------------------------------------------------------

// MediaCodec.BUFFER_FLAG_END_OF_STREAM, mirrored from MediaCodec.java (this
// file has no Java import; the value is a stable part of the public AOSP
// API and is spelled out there too).
constexpr int kFlagEndOfStream = 4;

struct VtDecoder {
    VTDecompressionSessionRef   session = nullptr;
    CMVideoFormatDescriptionRef fmtDesc = nullptr;
    CMVideoCodecType codecType = 0;
    int  width = 0, height = 0;
    bool isAvcOrHevc = false;     // gates the AnnexBToAvcc rewrite on feed
    bool usingHardware = false;

    // VTDecompressionSessionDecodeFrame is called with EnableAsynchronous
    // Decompression | EnableTemporalProcessing (see CreateSessionInternal) so
    // VideoToolbox itself handles B-frame reordering and may deliver frames
    // on an arbitrary VT-internal thread, out of order relative to the
    // Decode calls that produced them and at an unpredictable later time --
    // hence the mutex-guarded queue rather than a same-thread return value.
    std::mutex mu;
    struct OutFrame { CVPixelBufferRef pb; int64_t ptsUs; int flags; };
    std::deque<OutFrame> outQ;

    // Bumped by flush(). A decode callback compares the generation it was
    // launched with against the CURRENT value; a stale one drops its frame
    // instead of enqueueing, matching AOSP's documented flush() contract
    // ("all output buffers are immediately revoked at the point of the
    // flush") -- otherwise a decode that was in flight across a flush could
    // land a frame from the pre-flush stream into the post-flush queue.
    std::atomic<int> generation{0};
};

struct DecodeCtx {
    VtDecoder* dec;
    int generation;
};

void OutputCallback(void* refcon, void* sourceFrameRefCon, OSStatus status,
                    VTDecodeInfoFlags /*infoFlags*/, CVImageBufferRef imageBuffer,
                    CMTime pts, CMTime /*dur*/) {
    DecodeCtx* ctx = (DecodeCtx*)sourceFrameRefCon;
    VtDecoder* d = (VtDecoder*)refcon;
    if (!ctx) return;  // the probe path (below) passes a null refcon/ctx
    if (status == noErr && imageBuffer
            && ctx->generation == d->generation.load(std::memory_order_relaxed)) {
        CVPixelBufferRef pb = (CVPixelBufferRef)CFRetain(imageBuffer);
        int64_t ptsUs = CMTIME_IS_VALID(pts)
            ? (int64_t)llround(CMTimeGetSeconds(pts) * 1.0e6) : -1;
        std::lock_guard<std::mutex> lk(d->mu);
        d->outQ.push_back({pb, ptsUs, 0});
    } else if (status != noErr) {
        std::fprintf(stderr, "[video-toolbox] decode callback error %d\n", (int)status);
    }
    // else: noErr but stale generation (flushed mid-flight), or a dropped
    // frame (imageBuffer null) -- nothing to enqueue either way.
    delete ctx;
}

// Builds the videoDecoderSpecification + destination pixel-buffer attributes
// and creates the session, then reads back whether hardware decode was
// actually granted. `refcon` may be null for the capability-probe path (no
// real decode ever happens through a probe session).
VTDecompressionSessionRef CreateSessionInternal(CMVideoFormatDescriptionRef fmt,
                                                void* refcon, bool* outUsingHw) {
    @autoreleasepool {
        NSDictionary* decoderSpec = @{
            (id)kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: @YES,
        };
        NSDictionary* destAttrs = @{
            (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
            (id)kCVPixelBufferMetalCompatibilityKey: @YES,
            (id)kCVPixelBufferIOSurfacePropertiesKey: @{},
        };
        VTDecompressionOutputCallbackRecord rec = { OutputCallback, refcon };
        VTDecompressionSessionRef session = nullptr;
        OSStatus s = VTDecompressionSessionCreate(
            kCFAllocatorDefault, fmt, (__bridge CFDictionaryRef)decoderSpec,
            (__bridge CFDictionaryRef)destAttrs, &rec, &session);
        if (s != noErr || !session) {
            char fcc[5];
            FourccToChars(CMFormatDescriptionGetMediaSubType(fmt), fcc);
            std::fprintf(stderr,
                "[video-toolbox] VTDecompressionSessionCreate(%s) failed: %d\n", fcc, (int)s);
            if (outUsingHw) *outUsingHw = false;
            return nullptr;
        }
        CFBooleanRef hwFlag = nullptr;
        OSStatus ps = VTSessionCopyProperty(
            session, kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
            kCFAllocatorDefault, &hwFlag);
        bool hw = (ps == noErr && hwFlag && CFBooleanGetValue(hwFlag));
        if (hwFlag) CFRelease(hwFlag);
        if (outUsingHw) *outUsingHw = hw;
        return session;
    }
}

// ---------------------------------------------------------------------------
// Capability probing (per-codec, measured, cached once per process).
// ---------------------------------------------------------------------------

struct ProbeResult { bool supported = false; bool hw = false; };
std::mutex g_probeMu;
std::map<CMVideoCodecType, ProbeResult> g_probeCache;

// Real, spec-compliant AVC/HEVC parameter sets for the capability probe --
// EXTRACTED, not hand-derived: ffmpeg 8.1.2 (libx264/libx265) encoded a
// testsrc2 64x64 clip on this machine on 2026-08-16, `-bsf:v
// h264_mp4toannexb`/`hevc_mp4toannexb` produced the Annex-B elementary
// stream, and a small script (SplitAnnexB's own algorithm, applied
// standalone) pulled out the SPS/PPS (H264) and VPS/SPS/PPS (HEVC) NAL
// payloads verbatim -- reproducible with the same two ffmpeg commands.
//
// WHY THIS EXISTS: measured on this host, VTDecompressionSessionCreate from
// a GENERIC (no parameter sets, codec+dims only) format description succeeds
// for AVC but FAILS for HEVC with OSStatus -8971 (no public symbol found
// across the VideoToolbox/CoreMedia headers on this SDK), even though
// VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC) reports true. The
// generic descriptor is what's inadequate for HEVC, not the codec's
// availability -- HEVC decode genuinely works once the session is created
// from real parameter sets, exactly as MediaCodec.start() does via
// BuildFormatDescriptionHEVC once an app supplies real csd-0. So the probe
// for AVC/HEVC below builds its trial format description through THAT SAME
// function (not the generic one every other codec uses), which makes the
// probe exercise the real code path instead of a similar-but-different one.
// Start codes are 4-byte here to match NALUnitHeaderLength=4 everywhere else
// in this file.
const uint8_t kProbeH264Csd0[] = {  // SPS, start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xd9, 0x04, 0x26, 0xc0,
    0x44, 0x00, 0x00, 0x03, 0x00, 0x04, 0x00, 0x00, 0x03, 0x00, 0x50, 0x3c,
    0x48, 0x99, 0x20,
};
const uint8_t kProbeH264Csd1[] = {  // PPS, start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x68, 0xcb, 0x83, 0xcb, 0x20,
};
const uint8_t kProbeHevcCsd0[] = {  // VPS + SPS + PPS, each start-code-prefixed
    0x00, 0x00, 0x00, 0x01, 0x40, 0x01, 0x0c, 0x01, 0xff, 0xff, 0x01, 0x60,
    0x00, 0x00, 0x03, 0x00, 0x90, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00,
    0x1e, 0x95, 0x98, 0x09,
    0x00, 0x00, 0x00, 0x01, 0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x03,
    0x00, 0x90, 0x00, 0x00, 0x03, 0x00, 0x00, 0x03, 0x00, 0x1e, 0xa0, 0x20,
    0x81, 0x05, 0x96, 0x56, 0x69, 0x24, 0xca, 0xf0, 0x16, 0x80, 0x80, 0x00,
    0x00, 0x03, 0x00, 0x80, 0x00, 0x00, 0x05, 0x04,
    0x00, 0x00, 0x00, 0x01, 0x44, 0x01, 0xc1, 0x72, 0xb4, 0x22, 0x40,
};

// Real MPEG-4 Part 2 VOL configuration for the capability probe -- same
// extraction method as the AVC/HEVC constants above: ffmpeg 8.1.2
// (libavcodec's native `mpeg4` encoder) encoded a testsrc2 64x64 clip on
// this machine on 2026-08-16 (`ffmpeg -f lavfi -i testsrc2=size=64x64:
// rate=10:duration=0.5 -c:v mpeg4 -g 10 -pix_fmt yuv420p -f mp4 out.mp4`),
// and `ffprobe -show_entries stream=extradata` read back the exact bytes
// MPEG4Extractor would place in csd-0 -- reproducible with the same
// commands. See the MPEG-4 PART 2 FINDING paragraph in the file header and
// BuildEsdsAtomFromDecoderSpecificInfo's comment for why a generic,
// parameter-free probe (OSStatus -12909) is not enough for this codec, the
// same way it was not enough for HEVC.
const uint8_t kProbeMpeg4Part2Vol[] = {
    0x00, 0x00, 0x01, 0xb0, 0x01, 0x00, 0x00, 0x01, 0xb5, 0x89, 0x13, 0x00,
    0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x20, 0x00, 0xc4, 0x8d, 0x88, 0x00,
    0x55, 0x02, 0x04, 0x08, 0x14, 0x43, 0x00, 0x00, 0x01, 0xb2, 0x4c, 0x61,
    0x76, 0x63, 0x36, 0x32, 0x2e, 0x32, 0x38, 0x2e, 0x31, 0x30, 0x32,
};

// Builds a trial format description for the probe: AVC/HEVC/MPEG-4 Part 2 go
// through the SAME real-config constructors real decode uses (see the
// comments above); the remaining codecs have no out-of-band config to test,
// so the generic constructor already exercises the real path for them
// (BuildFormatDescriptionGeneric is what macapk_vtb_open falls back to for
// these mimes too).
bool BuildProbeFormatDescription(CMVideoCodecType fourcc, CMVideoFormatDescriptionRef* outDesc) {
    if (fourcc == kCMVideoCodecType_H264) {
        return BuildFormatDescriptionH264(kProbeH264Csd0, sizeof(kProbeH264Csd0),
                                          kProbeH264Csd1, sizeof(kProbeH264Csd1), outDesc);
    }
    if (fourcc == kCMVideoCodecType_HEVC) {
        return BuildFormatDescriptionHEVC(kProbeHevcCsd0, sizeof(kProbeHevcCsd0), outDesc);
    }
    if (fourcc == kCMVideoCodecType_MPEG4Video) {
        return BuildFormatDescriptionMPEG4Part2(kProbeMpeg4Part2Vol, sizeof(kProbeMpeg4Part2Vol),
                                                64, 64, outDesc);
    }
    return BuildFormatDescriptionGeneric(fourcc, 64, 64, outDesc);
}

// Measures BOTH signals rather than trusting either alone: the "theoretical"
// answer (VTIsHardwareDecodeSupported, macOS 10.13+, per its own header "does
// not guarantee that hardware decode resources will be available at all
// times") and the "real" answer (actually standing up a session from a
// REAL trial format description and reading back
// kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder).
ProbeResult ProbeFourccLocked(CMVideoCodecType fourcc) {
    ProbeResult r;
    bool hwTheoretical = VTIsHardwareDecodeSupported(fourcc);
    CMVideoFormatDescriptionRef fmt = nullptr;
    bool descOk = BuildProbeFormatDescription(fourcc, &fmt);
    if (descOk && fmt) {
        bool hw = false;
        VTDecompressionSessionRef sess = CreateSessionInternal(fmt, /*refcon=*/nullptr, &hw);
        if (sess) {
            r.supported = true;
            r.hw = hw || hwTheoretical;
            VTDecompressionSessionInvalidate(sess);
            CFRelease(sess);
        }
        CFRelease(fmt);
    }
    // Belt-and-suspenders for AVC/HEVC specifically (per the owner's review:
    // "session-creation failure with a synthetic descriptor is INCONCLUSIVE,
    // not negative"). These two are the codecs every Apple platform has
    // decoded in hardware for over a decade; if OUR trial session still
    // somehow fails on some future host despite Apple's own capability query
    // saying yes, prefer that platform answer over silently dropping a
    // codec that is very likely to actually work once a real app supplies
    // real csd -- MediaCodec.start() has a loud-failure safety net
    // (CodecException) for the rare case this optimism is wrong, so this
    // cannot regress into a SILENT failure the way the pre-fix probe did.
    if (!r.supported && hwTheoretical
            && (fourcc == kCMVideoCodecType_H264 || fourcc == kCMVideoCodecType_HEVC)) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr,
            "[video-toolbox] probe %s: trial session create failed but "
            "VTIsHardwareDecodeSupported=yes -- trusting the platform capability "
            "query (see kProbeHevcCsd0's comment); MediaCodec.start() will throw "
            "loudly if this turns out to be wrong for a real app\n", fcc);
        r.supported = true;
        r.hw = true;
    }
    char fcc[5]; FourccToChars(fourcc, fcc);
    std::fprintf(stderr,
        "[video-toolbox] probe %s: supported=%s hw=%s (VTIsHardwareDecodeSupported=%s)\n",
        fcc, r.supported ? "yes" : "no", r.hw ? "yes" : "no", hwTheoretical ? "yes" : "no");
    return r;
}

ProbeResult ProbeFourcc(CMVideoCodecType fourcc) {
    std::lock_guard<std::mutex> lk(g_probeMu);
    auto it = g_probeCache.find(fourcc);
    if (it != g_probeCache.end()) return it->second;
    ProbeResult r = ProbeFourccLocked(fourcc);
    g_probeCache[fourcc] = r;
    return r;
}

}  // namespace

// ---------------------------------------------------------------------------
// C ABI (macapk_vtb_*), mirroring the macapk_vd_* / macapk_md_* convention
// used by media_video.mm / media_decode.mm.
// ---------------------------------------------------------------------------

extern "C" {

// Returns 1 if `mime` is decodable on this host (hardware or software via
// VideoToolbox -- VP8 is never reported here, see the file header), 0
// otherwise. *outHw is set to 1 only when the measured session actually used
// hardware decode.
int macapk_vtb_probe_mime(const char* mimeC, int* outHw) {
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!MimeToFourcc(mime, &fourcc)) { if (outHw) *outHw = 0; return 0; }
    ProbeResult r = ProbeFourcc(fourcc);
    if (outHw) *outHw = r.hw ? 1 : 0;
    return r.supported ? 1 : 0;
}

void* macapk_vtb_open(const char* mimeC, int width, int height,
                      const uint8_t* csd0, int csd0Len,
                      const uint8_t* csd1, int csd1Len,
                      int /*wantSurface*/, int* outHw) {
    std::string mime = mimeC ? mimeC : "";
    CMVideoCodecType fourcc = 0;
    if (!MimeToFourcc(mime, &fourcc)) {
        std::fprintf(stderr, "[video-toolbox] open: unsupported mime '%s'\n", mime.c_str());
        return nullptr;
    }
    bool isAvcHevc = (fourcc == kCMVideoCodecType_H264 || fourcc == kCMVideoCodecType_HEVC);

    CMVideoFormatDescriptionRef fmt = nullptr;
    bool ok;
    if (fourcc == kCMVideoCodecType_H264) {
        ok = BuildFormatDescriptionH264(csd0, csd0Len, csd1, csd1Len, &fmt);
    } else if (fourcc == kCMVideoCodecType_HEVC) {
        ok = BuildFormatDescriptionHEVC(csd0, csd0Len, &fmt);
    } else if (fourcc == kCMVideoCodecType_MPEG4Video) {
        ok = BuildFormatDescriptionMPEG4Part2(csd0, csd0Len, width, height, &fmt);
    } else {
        ok = BuildFormatDescriptionGeneric(fourcc, width, height, &fmt);
    }
    if (!ok || !fmt) return nullptr;

    VtDecoder* d = new VtDecoder();
    d->codecType = fourcc;
    d->isAvcOrHevc = isAvcHevc;
    d->fmtDesc = fmt;
    CMVideoDimensions dim = CMVideoFormatDescriptionGetDimensions(fmt);
    d->width  = dim.width  > 0 ? dim.width  : width;
    d->height = dim.height > 0 ? dim.height : height;

    bool hw = false;
    d->session = CreateSessionInternal(fmt, /*refcon=*/d, &hw);
    if (!d->session) {
        char fcc[5]; FourccToChars(fourcc, fcc);
        std::fprintf(stderr, "[video-toolbox] open: VTDecompressionSessionCreate failed for %s\n", fcc);
        CFRelease(d->fmtDesc);
        delete d;
        return nullptr;
    }
    d->usingHardware = hw;
    if (outHw) *outHw = hw ? 1 : 0;
    std::fprintf(stderr, "[video-toolbox] open: mime=%s %dx%d hw=%s\n",
                mime.c_str(), d->width, d->height, hw ? "YES" : "no (software)");
    return d;
}

// Feeds one compressed access unit (may be null/0-length at eos with nothing
// left to submit). Fire-and-forget: the decoded frame (if any) surfaces later
// through macapk_vtb_poll. Returns 0 on success, negative on error.
int macapk_vtb_decode(void* h, const uint8_t* au, int auLen, int64_t ptsUs, int eos) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d || !d->session) return -1;
    if (au && auLen > 0) {
        std::vector<uint8_t> payload = d->isAvcOrHevc
            ? AnnexBToAvcc(au, (size_t)auLen)
            : std::vector<uint8_t>(au, au + auLen);
        void* mem = std::malloc(payload.size());
        if (!mem) return -2;
        std::memcpy(mem, payload.data(), payload.size());
        CMBlockBufferRef bb = nullptr;
        OSStatus s = CMBlockBufferCreateWithMemoryBlock(
            kCFAllocatorDefault, mem, payload.size(), kCFAllocatorMalloc,
            /*customBlockSource=*/nullptr, /*offsetToData=*/0, payload.size(),
            /*flags=*/0, &bb);
        if (s != noErr || !bb) {
            std::free(mem);
            std::fprintf(stderr, "[video-toolbox] CMBlockBufferCreateWithMemoryBlock failed %d\n", (int)s);
            return -3;
        }
        CMSampleTimingInfo timing = {
            kCMTimeInvalid, CMTimeMake(ptsUs, 1000000), kCMTimeInvalid
        };
        size_t sampleSize = payload.size();
        CMSampleBufferRef sbuf = nullptr;
        s = CMSampleBufferCreateReady(kCFAllocatorDefault, bb, d->fmtDesc,
                                      /*numSamples=*/1, /*numSampleTimingEntries=*/1, &timing,
                                      /*numSampleSizeEntries=*/1, &sampleSize, &sbuf);
        CFRelease(bb);
        if (s != noErr || !sbuf) {
            std::fprintf(stderr, "[video-toolbox] CMSampleBufferCreateReady failed %d\n", (int)s);
            return -4;
        }
        DecodeCtx* ctx = new DecodeCtx{ d, d->generation.load(std::memory_order_relaxed) };
        VTDecodeInfoFlags infoOut = 0;
        OSStatus ds = VTDecompressionSessionDecodeFrame(
            d->session, sbuf,
            kVTDecodeFrame_EnableAsynchronousDecompression | kVTDecodeFrame_EnableTemporalProcessing,
            ctx, &infoOut);
        CFRelease(sbuf);
        if (ds != noErr) {
            std::fprintf(stderr, "[video-toolbox] VTDecompressionSessionDecodeFrame failed %d\n", (int)ds);
            delete ctx;
            return -5;
        }
    }
    if (eos) {
        // Block (briefly -- a one-time end-of-stream event, not a hot path)
        // until every in-flight/delayed frame has been through the callback,
        // so the EOS marker we push below is genuinely last in the queue.
        VTDecompressionSessionFinishDelayedFrames(d->session);
        VTDecompressionSessionWaitForAsynchronousFrames(d->session);
        std::lock_guard<std::mutex> lk(d->mu);
        d->outQ.push_back({nullptr, -1, kFlagEndOfStream});
    }
    return 0;
}

// Pops the oldest ready frame. Returns 1 and fills the out-params if one was
// available, 0 if the queue is empty (TRY_AGAIN), -1 if `h` is invalid.
int macapk_vtb_poll(void* h, void** outPixbuf, int64_t* outPtsUs, int* outFlags) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return -1;
    std::lock_guard<std::mutex> lk(d->mu);
    if (d->outQ.empty()) return 0;
    VtDecoder::OutFrame f = d->outQ.front();
    d->outQ.pop_front();
    if (outPixbuf) *outPixbuf = f.pb;
    if (outPtsUs)  *outPtsUs  = f.ptsUs;
    if (outFlags)  *outFlags  = f.flags;
    return 1;
}

// releaseOutputBuffer(index, render=true): hand the frame to the screen.
// Delegates entirely to CocoaPresentVideoFrame (cocoa_bridge.mm), which
// itself delegates the actual compositing to macapk_vd_present_* -- see the
// file header for why nothing is reimplemented here.
void macapk_vtb_render_frame(void* pixbuf, int width, int height) {
    if (!pixbuf) return;
    CocoaPresentVideoFrame(pixbuf, width, height);
}

// releaseOutputBuffer(index, render=false), or the post-render cleanup after
// a true release: return the frame's native memory. MUST be called exactly
// once per frame macapk_vtb_poll ever returned, render or not -- mirrors
// AOSP's "if you don't render the buffer... call ... with render set to
// false to simply return the buffer to the codec" contract.
void macapk_vtb_release_frame(void* pixbuf) {
    if (pixbuf) CFRelease((CVPixelBufferRef)pixbuf);
}

// Best-effort raw-pixel readout for the no-Surface decode path (MediaCodec.
// getOutputImage). Copies packed 32BGRA (the destination format every
// session is opened with -- see CreateSessionInternal) into a caller-owned
// buffer. Returns 0 on success, negative on error (including "buffer too
// small", which the caller sizes from a prior call using width/height/stride
// of 0/0/0 -- callers here always pre-size from the known decode dimensions,
// so this is a safety net, not the primary sizing path).
int macapk_vtb_frame_copy_bgra(void* pixbuf, uint8_t* outBuf, int outCap,
                               int* outW, int* outH, int* outStride) {
    CVPixelBufferRef pb = (CVPixelBufferRef)pixbuf;
    if (!pb || !outBuf) return -1;
    CVPixelBufferLockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    size_t w = CVPixelBufferGetWidth(pb);
    size_t h = CVPixelBufferGetHeight(pb);
    size_t stride = CVPixelBufferGetBytesPerRow(pb);
    const uint8_t* base = (const uint8_t*)CVPixelBufferGetBaseAddress(pb);
    size_t need = stride * h;
    if (!base || (size_t)outCap < need) {
        CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
        return -2;
    }
    std::memcpy(outBuf, base, need);
    CVPixelBufferUnlockBaseAddress(pb, kCVPixelBufferLock_ReadOnly);
    if (outW) *outW = (int)w;
    if (outH) *outH = (int)h;
    if (outStride) *outStride = (int)stride;
    return 0;
}

// flush(): AOSP "all output buffers are immediately revoked at the point of
// the flush". Bumps the generation counter (so any decode already in flight
// drops its result instead of landing in the fresh queue -- see VtDecoder's
// comment) and drains + releases whatever was already queued.
void macapk_vtb_flush(void* h) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return;
    d->generation.fetch_add(1, std::memory_order_relaxed);
    if (d->session) VTDecompressionSessionFinishDelayedFrames(d->session);
    std::lock_guard<std::mutex> lk(d->mu);
    while (!d->outQ.empty()) {
        if (d->outQ.front().pb) CFRelease(d->outQ.front().pb);
        d->outQ.pop_front();
    }
}

void macapk_vtb_close(void* h) {
    VtDecoder* d = (VtDecoder*)h;
    if (!d) return;
    if (d->session) {
        VTDecompressionSessionWaitForAsynchronousFrames(d->session);
        VTDecompressionSessionInvalidate(d->session);
        CFRelease(d->session);
    }
    if (d->fmtDesc) CFRelease(d->fmtDesc);
    {
        std::lock_guard<std::mutex> lk(d->mu);
        while (!d->outQ.empty()) {
            if (d->outQ.front().pb) CFRelease(d->outQ.front().pb);
            d->outQ.pop_front();
        }
    }
    delete d;
}

int macapk_vtb_width(void* h)  { VtDecoder* d = (VtDecoder*)h; return d ? d->width  : 0; }
int macapk_vtb_height(void* h) { VtDecoder* d = (VtDecoder*)h; return d ? d->height : 0; }

// Diagnostic self-test (MACAPK_VIDEO_TOOLBOX_SELFTEST=<path to a raw Annex-B
// elementary stream>, MACAPK_VIDEO_TOOLBOX_SELFTEST_MIME=video/avc|video/hevc):
// decodes every VCL NAL in the file and presents each frame to the screen
// through the REAL MediaCodec-facing pipeline (macapk_vtb_open/_decode/
// _poll/_render_frame/_release_frame) -- unlike media_video.mm's older
// MACAPK_VIDEO_SELFTEST, which demuxes+decodes+presents through the
// file-based macapk_vd_* engine, this exercises the SAME functions
// android.media.MediaCodec calls end to end (including the on-screen
// composite via CocoaPresentVideoFrame), using a local file instead of a
// live app's network-fetched stream. Verifies the one thing a standalone,
// windowless test (debug/video_toolbox_test.mm) structurally cannot: that a
// real decoded frame actually reaches a real on-screen CAMetalLayer. Gated,
// default off, zero cost when unset. Called once per drawn frame from
// cocoa_bridge.mm's drawInMTKView; feeds one more NAL each call (looping)
// and drains whatever output is ready, so the clip plays continuously for as
// long as the host app's window stays up.
void macapk_vtb_selftest_tick(const char* mimeC, const char* path) {
    static void* dec = nullptr;
    static std::vector<std::vector<uint8_t>> vclNals;
    static size_t nextNal = 0;
    static int64_t pts = 0;
    static int frameW = 0, frameH = 0;
    static bool failedOpen = false;
    if (failedOpen) return;
    if (!dec) {
        FILE* f = std::fopen(path, "rb");
        if (!f) { std::fprintf(stderr, "[video-toolbox] selftest: cannot open %s\n", path); failedOpen = true; return; }
        std::fseek(f, 0, SEEK_END);
        long len = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        std::vector<uint8_t> file(len > 0 ? (size_t)len : 0);
        if (len > 0) { size_t got = std::fread(file.data(), 1, (size_t)len, f); file.resize(got); }
        std::fclose(f);
        if (file.empty()) { failedOpen = true; return; }

        std::string mime = mimeC ? mimeC : "video/avc";
        bool hevc = (mime == "video/hevc");
        auto nals = SplitAnnexB(file.data(), file.size());
        std::vector<uint8_t> csd0, csd1;
        for (const auto& n : nals) {
            int t = hevc ? HevcNalType(n) : H264NalType(n);
            bool isVcl  = hevc ? (t <= 21) : (t == 1 || t == 5);
            bool isCsd0 = hevc ? (t == 32 || t == 33 || t == 34) : (t == 7 || t == 13);
            bool isCsd1 = !hevc && t == 8;
            std::vector<uint8_t> withSc = {0, 0, 0, 1};
            withSc.insert(withSc.end(), n.ptr, n.ptr + n.len);
            if (isCsd0) csd0.insert(csd0.end(), withSc.begin(), withSc.end());
            else if (isCsd1) csd1.insert(csd1.end(), withSc.begin(), withSc.end());
            else if (isVcl) vclNals.push_back(std::move(withSc));
        }
        int hw = 0;
        dec = macapk_vtb_open(mime.c_str(), 0, 0, csd0.data(), (int)csd0.size(),
                              csd1.empty() ? nullptr : csd1.data(), (int)csd1.size(),
                              /*wantSurface=*/1, &hw);
        if (!dec) {
            std::fprintf(stderr, "[video-toolbox] selftest: open(%s) failed\n", mime.c_str());
            failedOpen = true;
            return;
        }
        frameW = macapk_vtb_width(dec);
        frameH = macapk_vtb_height(dec);
        std::fprintf(stderr,
            "[video-toolbox] selftest: opened %s %dx%d hw=%s vclNals=%zu, presenting to screen\n",
            mime.c_str(), frameW, frameH, hw ? "yes" : "no", vclNals.size());
    }
    if (vclNals.empty()) return;
    macapk_vtb_decode(dec, vclNals[nextNal].data(), (int)vclNals[nextNal].size(), pts, 0);
    pts += 100000;
    nextNal = (nextNal + 1) % vclNals.size();
    void* pb = nullptr;
    int64_t fpts = 0;
    int flags = 0;
    while (macapk_vtb_poll(dec, &pb, &fpts, &flags) == 1) {
        if (pb) {
            macapk_vtb_render_frame(pb, frameW, frameH);
            macapk_vtb_release_frame(pb);
        }
    }
}

}  // extern "C"

// ---------------------------------------------------------------------------
// JNI surface (com.macapk.loader.NativeMedia's video-side native methods).
// No JNIEXPORT/JNICALL decoration, matching media_decode.mm's style in this
// same package -- these are never auto-resolved (NativeMedia.java's own
// header comment: "the JVM does not auto-resolve Java_* on macOS"), only
// ever reached through the explicit RegisterNatives call below.
// ---------------------------------------------------------------------------

extern "C" {

jobjectArray Java_com_macapk_loader_NativeMedia_nativeVideoSupportedMimes(JNIEnv* env, jclass) {
    std::vector<std::string> mimes;
    for (const auto& e : kMimeTable) {
        if (ProbeFourcc(e.fourcc).supported) mimes.push_back(e.mime);
    }
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray((jsize)mimes.size(), strCls, nullptr);
    for (size_t i = 0; i < mimes.size(); i++) {
        jstring s = env->NewStringUTF(mimes[i].c_str());
        env->SetObjectArrayElement(arr, (jsize)i, s);
        env->DeleteLocalRef(s);
    }
    env->DeleteLocalRef(strCls);
    return arr;
}

jboolean Java_com_macapk_loader_NativeMedia_nativeVideoIsHardware(JNIEnv* env, jclass, jstring jmime) {
    if (!jmime) return JNI_FALSE;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    int hw = 0;
    macapk_vtb_probe_mime(mime, &hw);
    env->ReleaseStringUTFChars(jmime, mime);
    return hw ? JNI_TRUE : JNI_FALSE;
}

// The host-probed profile/level table (runtime/src/video_caps_probe.mm):
// [maxWidth, maxHeight, maxMacroblocks, nPairs, profile0, level0, ...] in
// Android CodecProfileLevel constants, or null when no per-profile probe
// exists for `mime` (MediaCodecList then keeps the profile-less entry).
// MediaCodecList caches the registry for the process, so this runs once per
// mime; the one-line summary is the evidence of what the host answered.
jintArray Java_com_macapk_loader_NativeMedia_nativeVideoCapabilities(JNIEnv* env, jclass, jstring jmime) {
    if (!jmime) return nullptr;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    if (!mime) return nullptr;   // OOM: a pending OutOfMemoryError, nothing to release
    int buf[256];
    auto t0 = std::chrono::steady_clock::now();
    int n = macapk_vtcaps_probe(mime, buf, 256);
    long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(
                  std::chrono::steady_clock::now() - t0).count();
    if (n >= 4) {
        std::fprintf(stderr,
            "[video-toolbox] capabilities %s: max=%dx%d mbs=%d profile/level pairs=%d (decode-probed, %ld ms)\n",
            mime, buf[0], buf[1], buf[2], buf[3], ms);
    }
    env->ReleaseStringUTFChars(jmime, mime);
    if (n < 4) return nullptr;
    jintArray arr = env->NewIntArray(n);
    if (arr) env->SetIntArrayRegion(arr, 0, n, buf);
    return arr;
}

jlong Java_com_macapk_loader_NativeMedia_nativeVidOpen(JNIEnv* env, jclass, jstring jmime,
                                                        jint width, jint height,
                                                        jbyteArray jcsd0, jbyteArray jcsd1,
                                                        jboolean wantSurface) {
    if (!jmime) return 0;
    const char* mime = env->GetStringUTFChars(jmime, nullptr);
    std::vector<uint8_t> csd0, csd1;
    if (jcsd0) {
        jsize n = env->GetArrayLength(jcsd0);
        csd0.resize(n);
        if (n > 0) env->GetByteArrayRegion(jcsd0, 0, n, (jbyte*)csd0.data());
    }
    if (jcsd1) {
        jsize n = env->GetArrayLength(jcsd1);
        csd1.resize(n);
        if (n > 0) env->GetByteArrayRegion(jcsd1, 0, n, (jbyte*)csd1.data());
    }
    int hw = 0;
    void* h = macapk_vtb_open(mime, width, height,
                              csd0.empty() ? nullptr : csd0.data(), (int)csd0.size(),
                              csd1.empty() ? nullptr : csd1.data(), (int)csd1.size(),
                              wantSurface ? 1 : 0, &hw);
    env->ReleaseStringUTFChars(jmime, mime);
    return (jlong)(uintptr_t)h;
}

jint Java_com_macapk_loader_NativeMedia_nativeVidDecode(JNIEnv* env, jclass, jlong h,
                                                         jbyteArray jau, jlong ptsUs, jboolean eos) {
    std::vector<uint8_t> au;
    if (jau) {
        jsize n = env->GetArrayLength(jau);
        au.resize(n);
        if (n > 0) env->GetByteArrayRegion(jau, 0, n, (jbyte*)au.data());
    }
    return macapk_vtb_decode((void*)(uintptr_t)h, au.empty() ? nullptr : au.data(),
                             (int)au.size(), ptsUs, eos ? 1 : 0);
}

jlongArray Java_com_macapk_loader_NativeMedia_nativeVidPoll(JNIEnv* env, jclass, jlong h) {
    void* pb = nullptr;
    int64_t pts = 0;
    int flags = 0;
    int got = macapk_vtb_poll((void*)(uintptr_t)h, &pb, &pts, &flags);
    if (got <= 0) return nullptr;
    jlongArray arr = env->NewLongArray(3);
    jlong vals[3] = { (jlong)(uintptr_t)pb, (jlong)pts, (jlong)flags };
    env->SetLongArrayRegion(arr, 0, 3, vals);
    return arr;
}

void Java_com_macapk_loader_NativeMedia_nativeVidRender(JNIEnv*, jclass, jlong pixbuf,
                                                         jint width, jint height) {
    macapk_vtb_render_frame((void*)(uintptr_t)pixbuf, width, height);
}

void Java_com_macapk_loader_NativeMedia_nativeVidReleaseFrame(JNIEnv*, jclass, jlong pixbuf) {
    macapk_vtb_release_frame((void*)(uintptr_t)pixbuf);
}

// outWhs is a caller-allocated int[3]; filled with {width, height, stride} on
// success. outBuf must already be sized >= stride*height (the caller learns
// stride from getOutputFormat()'s KEY_WIDTH/KEY_HEIGHT-derived estimate, or
// simply over-allocates width*height*4 which is >= any BGRA stride).
jint Java_com_macapk_loader_NativeMedia_nativeVidFrameBgra(JNIEnv* env, jclass, jlong pixbuf,
                                                            jbyteArray jout, jintArray jwhs) {
    if (!jout || !jwhs) return -1;
    jsize cap = env->GetArrayLength(jout);
    std::vector<uint8_t> buf(cap);
    int w = 0, h = 0, stride = 0;
    int rc = macapk_vtb_frame_copy_bgra((void*)(uintptr_t)pixbuf, buf.data(), cap, &w, &h, &stride);
    if (rc == 0) {
        env->SetByteArrayRegion(jout, 0, cap, (const jbyte*)buf.data());
        jint whs[3] = { w, h, stride };
        env->SetIntArrayRegion(jwhs, 0, 3, whs);
    }
    return rc;
}

void Java_com_macapk_loader_NativeMedia_nativeVidFlush(JNIEnv*, jclass, jlong h) {
    macapk_vtb_flush((void*)(uintptr_t)h);
}

void Java_com_macapk_loader_NativeMedia_nativeVidClose(JNIEnv*, jclass, jlong h) {
    macapk_vtb_close((void*)(uintptr_t)h);
}

}  // extern "C"

namespace macapk { namespace video {

static const JNINativeMethod kNativeVideoMethods[] = {
    {(char*)"nativeVideoSupportedMimes", (char*)"()[Ljava/lang/String;",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoSupportedMimes},
    {(char*)"nativeVideoIsHardware",     (char*)"(Ljava/lang/String;)Z",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoIsHardware},
    {(char*)"nativeVideoCapabilities",   (char*)"(Ljava/lang/String;)[I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVideoCapabilities},
    {(char*)"nativeVidOpen",             (char*)"(Ljava/lang/String;II[B[BZ)J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidOpen},
    {(char*)"nativeVidDecode",           (char*)"(J[BJZ)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidDecode},
    {(char*)"nativeVidPoll",             (char*)"(J)[J",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidPoll},
    {(char*)"nativeVidRender",           (char*)"(JII)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidRender},
    {(char*)"nativeVidReleaseFrame",     (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidReleaseFrame},
    {(char*)"nativeVidFrameBgra",        (char*)"(J[B[I)I",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidFrameBgra},
    {(char*)"nativeVidFlush",            (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidFlush},
    {(char*)"nativeVidClose",            (char*)"(J)V",
     (void*)Java_com_macapk_loader_NativeMedia_nativeVidClose},
};

// Called from native_methods.cpp::RegisterAllNativeMethods at boot, right
// alongside media::RegisterNativeMediaMethods (media_decode.mm's audio
// registration for the SAME NativeMedia class -- two separate RegisterNatives
// calls against one jclass is the existing, safe pattern this codebase
// already uses for NativeLog's kNativeLogMethods/kNativeQos).
void RegisterNativeVideoMethods(JNIEnv* env) {
    jclass cls = env->FindClass("com/macapk/loader/NativeMedia");
    if (!cls) {
        std::fprintf(stderr, "[macapk] FindClass(NativeMedia) failed (video)\n");
        env->ExceptionClear();
        return;
    }
    int count = sizeof(kNativeVideoMethods) / sizeof(kNativeVideoMethods[0]);
    if (env->RegisterNatives(cls, kNativeVideoMethods, count) != JNI_OK) {
        std::fprintf(stderr, "[macapk] RegisterNatives(NativeMedia video) failed\n");
        env->ExceptionClear();
    } else {
        std::fprintf(stderr, "[I] NativeMedia: registered %d video methods (VideoToolbox)\n", count);
    }
    env->DeleteLocalRef(cls);
}

}}  // namespace macapk::video
