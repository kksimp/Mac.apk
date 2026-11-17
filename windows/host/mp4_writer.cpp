// mp4_writer.cpp -- see mp4_writer.h. ISO/IEC 14496-12 (the boxes), -14 (esds)
// and -15 (avcC).

#include "mp4_writer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>

namespace winapk::mp4 {

namespace {

// Big-endian bytes, with boxes whose size is filled in when they close.
struct Buf {
    std::vector<uint8_t> v;
    void u8(uint32_t x) { v.push_back(static_cast<uint8_t>(x)); }
    void u16(uint32_t x) {
        u8(x >> 8);
        u8(x);
    }
    void u32(uint32_t x) {
        u16(x >> 16);
        u16(x);
    }
    void u64(uint64_t x) {
        u32(static_cast<uint32_t>(x >> 32));
        u32(static_cast<uint32_t>(x));
    }
    void zeros(size_t n) { v.insert(v.end(), n, 0); }
    void bytes(const uint8_t* p, size_t n) { v.insert(v.end(), p, p + n); }
    void fourcc(const char* s) { bytes(reinterpret_cast<const uint8_t*>(s), 4); }
    size_t open(const char* type) {
        const size_t at = v.size();
        u32(0);
        fourcc(type);
        return at;
    }
    // A "full" box: version and flags after the type.
    size_t open(const char* type, uint32_t version, uint32_t flags) {
        const size_t at = open(type);
        u32((version << 24) | flags);
        return at;
    }
    void close(size_t at) {
        const uint32_t n = static_cast<uint32_t>(v.size() - at);
        v[at] = static_cast<uint8_t>(n >> 24);
        v[at + 1] = static_cast<uint8_t>(n >> 16);
        v[at + 2] = static_cast<uint8_t>(n >> 8);
        v[at + 3] = static_cast<uint8_t>(n);
    }
};

// Calls `nal(p, n)` for each NAL unit of an access unit or a csd, which is
// Annex-B (start codes), length-prefixed (four bytes), or one bare NAL.
template <typename F>
void for_each_nal(const uint8_t* d, size_t size, F nal) {
    auto start_code = [&](size_t i) { return i + 3 <= size && d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1; };
    if (size >= 4 && (start_code(0) || (d[0] == 0 && start_code(1)))) {
        size_t i = start_code(0) ? 3 : 4;
        while (i < size) {
            size_t end = i;
            while (end < size && !start_code(end)) ++end;
            size_t stop = end;
            // The zero bytes before a start code are its own (a four-byte
            // code, or trailing_zero_8bits), never the NAL's: a NAL ends on
            // its stop bit.
            if (end < size) {
                while (stop > i && d[stop - 1] == 0) --stop;
            }
            if (stop > i) nal(d + i, stop - i);
            i = end + 3;
        }
        return;
    }
    // Length-prefixed, when the lengths add up to the buffer exactly.
    size_t i = 0;
    bool prefixed = size >= 4;
    while (prefixed && i < size) {
        if (i + 4 > size) {
            prefixed = false;
            break;
        }
        const size_t n = (static_cast<size_t>(d[i]) << 24) | (d[i + 1] << 16) | (d[i + 2] << 8) | d[i + 3];
        if (n == 0 || n > size - i - 4) prefixed = false;
        else i += 4 + n;
    }
    if (prefixed) {
        for (i = 0; i < size;) {
            const size_t n = (static_cast<size_t>(d[i]) << 24) | (d[i + 1] << 16) | (d[i + 2] << 8) | d[i + 3];
            nal(d + i + 4, n);
            i += 4 + n;
        }
        return;
    }
    if (size) nal(d, size);
}

struct Sample {
    uint32_t size;
    int64_t pts_us;
    bool key;
};

struct Chunk {
    uint64_t offset;
    uint32_t samples;
};

struct Track {
    TrackFormat fmt;
    std::vector<uint8_t> sps, pps;  // video, without start codes
    std::vector<uint8_t> asc;       // audio
    std::vector<Sample> samples;
    std::vector<Chunk> chunks;
    uint32_t timescale = 0;
};

const uint32_t kMovieScale = 1000;

int64_t ticks(int64_t us, uint32_t scale) {
    return static_cast<int64_t>(std::llround(static_cast<double>(us) * scale / 1000000.0));
}

int sample_rate_index(int rate) {
    static const int kRates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
    for (int i = 0; i < 13; ++i) {
        if (kRates[i] == rate) return i;
    }
    return -1;
}

// What one track's samples say about time, in the track's own scale.
struct Timing {
    std::vector<uint32_t> deltas;    // decoding time to the next sample
    std::vector<uint32_t> offsets;   // composition minus decoding time; empty when always 0
    uint64_t media_duration = 0;
    uint32_t delay = 0;              // media time of the first picture shown
    int64_t first_us = 0;            // the earliest presentation time
};

Timing timing_of(const Track& t) {
    Timing out;
    const size_t n = t.samples.size();
    std::vector<int64_t> pts(n), order(n);
    for (size_t i = 0; i < n; ++i) pts[i] = order[i] = t.samples[i].pts_us;
    // Samples come in decoding order. Without reordering that is the
    // presentation order too; with B-frames the decoding times are the
    // presentation times sorted, moved back until none is later than its
    // sample's presentation.
    std::sort(order.begin(), order.end());
    int64_t delay_us = 0;
    for (size_t i = 0; i < n; ++i) delay_us = std::max(delay_us, order[i] - pts[i]);
    out.first_us = order[0];
    out.delay = static_cast<uint32_t>(ticks(delay_us, t.timescale));
    std::vector<int64_t> dts(n);
    for (size_t i = 0; i < n; ++i) dts[i] = ticks(order[i] - order[0], t.timescale);
    out.deltas.resize(n);
    for (size_t i = 0; i + 1 < n; ++i) out.deltas[i] = static_cast<uint32_t>(dts[i + 1] - dts[i]);
    // The last sample lasts as long as the one before it; alone, one AAC
    // frame or a thirtieth of a second.
    out.deltas[n - 1] = n > 1 ? out.deltas[n - 2] : t.fmt.video ? t.timescale / 30 : 1024;
    bool reordered = false;
    std::vector<uint32_t> offsets(n);
    for (size_t i = 0; i < n; ++i) {
        const int64_t off = ticks(pts[i] - order[0], t.timescale) + out.delay - dts[i];
        offsets[i] = static_cast<uint32_t>(off < 0 ? 0 : off);
        if (pts[i] != order[i]) reordered = true;
    }
    if (reordered) out.offsets = std::move(offsets);
    else out.delay = 0;
    for (uint32_t d : out.deltas) out.media_duration += d;
    return out;
}

// (count, value) runs: stts and ctts.
void runs(Buf& b, const std::vector<uint32_t>& values) {
    const size_t count_at = b.v.size();
    b.u32(0);
    uint32_t entries = 0;
    for (size_t i = 0; i < values.size();) {
        size_t j = i;
        while (j < values.size() && values[j] == values[i]) ++j;
        b.u32(static_cast<uint32_t>(j - i));
        b.u32(values[i]);
        ++entries;
        i = j;
    }
    Buf n;
    n.u32(entries);
    std::copy(n.v.begin(), n.v.end(), b.v.begin() + static_cast<ptrdiff_t>(count_at));
}

void matrix(Buf& b, int degrees) {
    int32_t a = 0x10000, bb = 0, c = 0, d = 0x10000;
    switch (degrees) {
        case 90: a = 0; bb = 0x10000; c = -0x10000; d = 0; break;
        case 180: a = -0x10000; d = -0x10000; break;
        case 270: a = 0; bb = -0x10000; c = 0x10000; d = 0; break;
        default: break;
    }
    b.u32(static_cast<uint32_t>(a));
    b.u32(static_cast<uint32_t>(bb));
    b.u32(0);
    b.u32(static_cast<uint32_t>(c));
    b.u32(static_cast<uint32_t>(d));
    b.u32(0);
    b.u32(0);
    b.u32(0);
    b.u32(0x40000000);
}

// An MPEG-4 descriptor's tag and length, in the four-byte form.
void descriptor(Buf& b, uint8_t tag, size_t n) {
    b.u8(tag);
    b.u8(0x80);
    b.u8(0x80);
    b.u8(0x80);
    b.u8(static_cast<uint32_t>(n));
}

void sample_entry(Buf& b, const Track& t) {
    if (t.fmt.video) {
        const size_t e = b.open("avc1");
        b.zeros(6);
        b.u16(1);  // data reference
        b.zeros(16);
        b.u16(static_cast<uint32_t>(t.fmt.width));
        b.u16(static_cast<uint32_t>(t.fmt.height));
        b.u32(0x00480000);  // 72 dpi
        b.u32(0x00480000);
        b.u32(0);
        b.u16(1);  // frames per sample
        b.zeros(32);
        b.u16(0x0018);
        b.u16(0xffff);
        const size_t c = b.open("avcC");
        b.u8(1);
        b.u8(t.sps.size() > 1 ? t.sps[1] : 66);
        b.u8(t.sps.size() > 2 ? t.sps[2] : 0);
        b.u8(t.sps.size() > 3 ? t.sps[3] : 30);
        b.u8(0xff);  // four-byte NAL lengths
        b.u8(0xe1);  // one SPS
        b.u16(static_cast<uint32_t>(t.sps.size()));
        b.bytes(t.sps.data(), t.sps.size());
        b.u8(1);
        b.u16(static_cast<uint32_t>(t.pps.size()));
        b.bytes(t.pps.data(), t.pps.size());
        const int profile = t.sps.size() > 1 ? t.sps[1] : 0;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            b.u8(0xfc | 1);  // 4:2:0
            b.u8(0xf8);      // 8-bit luma
            b.u8(0xf8);      // 8-bit chroma
            b.u8(0);         // no SPS extensions
        }
        b.close(c);
        b.close(e);
        return;
    }
    const size_t e = b.open("mp4a");
    b.zeros(6);
    b.u16(1);
    b.zeros(8);
    b.u16(static_cast<uint32_t>(t.fmt.channels));
    b.u16(16);
    b.u32(0);
    b.u32(static_cast<uint32_t>(t.fmt.sample_rate & 0xffff) << 16);
    const size_t s = b.open("esds", 0, 0);
    const size_t asc = t.asc.size();
    descriptor(b, 0x03, 3 + 5 + 13 + 5 + asc + 5 + 1);  // ES_Descriptor
    b.u16(0);
    b.u8(0);
    descriptor(b, 0x04, 13 + 5 + asc);  // DecoderConfigDescriptor
    b.u8(0x40);                         // MPEG-4 audio
    b.u8(0x15);                         // an audio stream
    b.u8(0);
    b.u16(0);  // buffer size: unsaid
    b.u32(static_cast<uint32_t>(t.fmt.bitrate));
    b.u32(static_cast<uint32_t>(t.fmt.bitrate));
    descriptor(b, 0x05, asc);  // DecoderSpecificInfo
    b.bytes(t.asc.data(), asc);
    descriptor(b, 0x06, 1);  // SLConfigDescriptor
    b.u8(2);
    b.close(s);
    b.close(e);
}

}  // namespace

struct Writer::Impl {
    std::FILE* file = nullptr;
    uint64_t pos = 0;
    uint64_t mdat_at = 0;
    bool three_gp = false;
    bool started = false, failed = false;
    int orientation = 0;
    bool located = false;
    float latitude = 0, longitude = 0;
    int last_track = -1;
    std::vector<Track> tracks;
    std::vector<uint8_t> scratch;

    bool put(const uint8_t* p, size_t n) {
        if (failed || !file) return false;
        if (n && std::fwrite(p, 1, n, file) != n) {
            failed = true;  // a full disk, usually
            return false;
        }
        pos += n;
        return true;
    }
    bool put(const Buf& b) { return put(b.v.data(), b.v.size()); }

    void close_file() {
        if (file) std::fclose(file);
        file = nullptr;
    }

    void track_box(Buf& b, const Track& t, const Timing& tm, uint32_t id, int64_t start_us) {
        const uint64_t start = static_cast<uint64_t>(ticks(start_us, kMovieScale));
        const uint64_t shown = tm.media_duration * kMovieScale / t.timescale;
        const size_t trak = b.open("trak");
        const size_t tkhd = b.open("tkhd", 0, 7);  // enabled, in the movie, in the preview
        b.u32(0);
        b.u32(0);
        b.u32(id);
        b.u32(0);
        b.u32(static_cast<uint32_t>(start + shown));
        b.zeros(8);
        b.u16(0);
        b.u16(0);
        b.u16(t.fmt.video ? 0 : 0x0100);
        b.u16(0);
        matrix(b, t.fmt.video ? orientation : 0);
        b.u32(t.fmt.video ? static_cast<uint32_t>(t.fmt.width) << 16 : 0);
        b.u32(t.fmt.video ? static_cast<uint32_t>(t.fmt.height) << 16 : 0);
        b.close(tkhd);
        if (start > 0 || tm.delay > 0) {
            // The track starts later than the movie, or its first picture is
            // not its first decoded sample.
            const size_t edts = b.open("edts");
            const size_t elst = b.open("elst", 0, 0);
            b.u32(start > 0 ? 2 : 1);
            if (start > 0) {
                b.u32(static_cast<uint32_t>(start));
                b.u32(0xffffffffu);  // an empty edit
                b.u32(0x00010000);
            }
            b.u32(static_cast<uint32_t>(shown));
            b.u32(tm.delay);
            b.u32(0x00010000);
            b.close(elst);
            b.close(edts);
        }
        const size_t mdia = b.open("mdia");
        const size_t mdhd = b.open("mdhd", 0, 0);
        b.u32(0);
        b.u32(0);
        b.u32(t.timescale);
        b.u32(static_cast<uint32_t>(tm.media_duration));
        b.u16(0x55c4);  // "und"
        b.u16(0);
        b.close(mdhd);
        const size_t hdlr = b.open("hdlr", 0, 0);
        b.u32(0);
        b.fourcc(t.fmt.video ? "vide" : "soun");
        b.zeros(12);
        const char* name = t.fmt.video ? "VideoHandle" : "SoundHandle";
        b.bytes(reinterpret_cast<const uint8_t*>(name), std::strlen(name) + 1);
        b.close(hdlr);
        const size_t minf = b.open("minf");
        if (t.fmt.video) {
            const size_t vmhd = b.open("vmhd", 0, 1);
            b.zeros(8);
            b.close(vmhd);
        } else {
            const size_t smhd = b.open("smhd", 0, 0);
            b.zeros(4);
            b.close(smhd);
        }
        const size_t dinf = b.open("dinf");
        const size_t dref = b.open("dref", 0, 0);
        b.u32(1);
        b.close(b.open("url ", 0, 1));  // the samples are in this file
        b.close(dref);
        b.close(dinf);
        const size_t stbl = b.open("stbl");
        const size_t stsd = b.open("stsd", 0, 0);
        b.u32(1);
        sample_entry(b, t);
        b.close(stsd);
        const size_t stts = b.open("stts", 0, 0);
        runs(b, tm.deltas);
        b.close(stts);
        if (!tm.offsets.empty()) {
            const size_t ctts = b.open("ctts", 0, 0);
            runs(b, tm.offsets);
            b.close(ctts);
        }
        size_t keys = 0;
        for (const Sample& s : t.samples) keys += s.key ? 1 : 0;
        // No stss says every sample is a sync sample, which is the truth for
        // audio. A video track gets one whenever some sample is not.
        if (t.fmt.video && keys > 0 && keys < t.samples.size()) {
            const size_t stss = b.open("stss", 0, 0);
            b.u32(static_cast<uint32_t>(keys));
            for (size_t i = 0; i < t.samples.size(); ++i) {
                if (t.samples[i].key) b.u32(static_cast<uint32_t>(i + 1));
            }
            b.close(stss);
        }
        const size_t stsc = b.open("stsc", 0, 0);
        const size_t count_at = b.v.size();
        b.u32(0);
        uint32_t entries = 0;
        for (size_t i = 0; i < t.chunks.size(); ++i) {
            if (i > 0 && t.chunks[i].samples == t.chunks[i - 1].samples) continue;
            b.u32(static_cast<uint32_t>(i + 1));
            b.u32(t.chunks[i].samples);
            b.u32(1);
            ++entries;
        }
        Buf n;
        n.u32(entries);
        std::copy(n.v.begin(), n.v.end(), b.v.begin() + static_cast<ptrdiff_t>(count_at));
        b.close(stsc);
        const size_t stsz = b.open("stsz", 0, 0);
        b.u32(0);
        b.u32(static_cast<uint32_t>(t.samples.size()));
        for (const Sample& s : t.samples) b.u32(s.size);
        b.close(stsz);
        const bool wide = !t.chunks.empty() && t.chunks.back().offset > 0xffffffffull;
        const size_t stco = b.open(wide ? "co64" : "stco", 0, 0);
        b.u32(static_cast<uint32_t>(t.chunks.size()));
        for (const Chunk& c : t.chunks) {
            if (wide) b.u64(c.offset);
            else b.u32(static_cast<uint32_t>(c.offset));
        }
        b.close(stco);
        b.close(stbl);
        b.close(minf);
        b.close(mdia);
        b.close(trak);
    }
};

Writer::Writer(std::unique_ptr<Impl> impl) : impl_(std::move(impl)) {}
Writer::~Writer() { impl_->close_file(); }

std::unique_ptr<Writer> Writer::create(const std::wstring& path, bool three_gp) {
    auto impl = std::make_unique<Impl>();
#ifdef _WIN32
    impl->file = _wfopen(path.c_str(), L"wb");
#else
    impl->file = std::fopen(std::string(path.begin(), path.end()).c_str(), "wb");
#endif
    if (!impl->file) return nullptr;
    impl->three_gp = three_gp;
    return std::unique_ptr<Writer>(new Writer(std::move(impl)));
}

int Writer::add_track(const TrackFormat& format) {
    Impl& w = *impl_;
    if (w.started) return -1;
    Track t;
    t.fmt = format;
    if (format.video) {
        if (format.width <= 0 || format.height <= 0) return -1;
        t.timescale = 90000;
        auto take = [&](const uint8_t* p, size_t n) {
            const int type = p[0] & 0x1f;
            if (type == 7 && t.sps.empty()) t.sps.assign(p, p + n);
            if (type == 8 && t.pps.empty()) t.pps.assign(p, p + n);
        };
        for_each_nal(format.csd0.data(), format.csd0.size(), take);
        for_each_nal(format.csd1.data(), format.csd1.size(), take);
    } else {
        if (format.sample_rate <= 0 || format.channels <= 0) return -1;
        t.timescale = static_cast<uint32_t>(format.sample_rate);
        t.asc = format.csd0;
    }
    w.tracks.push_back(std::move(t));
    return static_cast<int>(w.tracks.size()) - 1;
}

void Writer::set_orientation(int degrees) { impl_->orientation = ((degrees % 360) + 360) % 360; }

void Writer::set_location(float latitude, float longitude) {
    impl_->located = true;
    impl_->latitude = latitude;
    impl_->longitude = longitude;
}

bool Writer::start() {
    Impl& w = *impl_;
    if (w.started || w.tracks.empty()) return false;
    Buf b;
    const size_t ftyp = b.open("ftyp");
    b.fourcc(w.three_gp ? "3gp4" : "mp42");
    b.u32(0);
    b.fourcc(w.three_gp ? "3gp4" : "isom");
    b.fourcc(w.three_gp ? "isom" : "mp42");
    b.close(ftyp);
    // The 64-bit form of the size, so that a recording may pass 4 GB; the
    // size itself is written by finish().
    w.mdat_at = b.v.size();
    b.u32(1);
    b.fourcc("mdat");
    b.u64(0);
    w.started = w.put(b);
    return w.started;
}

bool Writer::write(int track, const uint8_t* data, size_t size, int64_t pts_us, bool key, bool config) {
    Impl& w = *impl_;
    if (!w.started || track < 0 || track >= static_cast<int>(w.tracks.size()) || !data) return false;
    Track& t = w.tracks[static_cast<size_t>(track)];
    const uint8_t* payload = data;
    size_t n = size;
    if (t.fmt.video) {
        // Length-prefixed NAL units, as avc1 stores them, without the
        // parameter sets (they live in avcC) and the access unit delimiters.
        w.scratch.clear();
        for_each_nal(data, size, [&](const uint8_t* p, size_t len) {
            const int type = p[0] & 0x1f;
            if (type == 7 || type == 8) {
                std::vector<uint8_t>& set = type == 7 ? t.sps : t.pps;
                if (set.empty()) set.assign(p, p + len);
                return;
            }
            if (type == 9) return;
            if (type == 5) key = true;
            const uint8_t head[4] = {static_cast<uint8_t>(len >> 24), static_cast<uint8_t>(len >> 16),
                                     static_cast<uint8_t>(len >> 8), static_cast<uint8_t>(len)};
            w.scratch.insert(w.scratch.end(), head, head + 4);
            w.scratch.insert(w.scratch.end(), p, p + len);
        });
        payload = w.scratch.data();
        n = w.scratch.size();
    } else {
        if (config && t.asc.empty()) t.asc.assign(data, data + size);
        key = true;
    }
    if (config || n == 0) return !w.failed;
    const uint64_t at = w.pos;
    if (!w.put(payload, n)) return false;
    // Samples of one track written one after another are one chunk.
    if (w.last_track == track && !t.chunks.empty()) ++t.chunks.back().samples;
    else t.chunks.push_back({at, 1});
    w.last_track = track;
    t.samples.push_back({static_cast<uint32_t>(n), pts_us, key});
    return true;
}

bool Writer::finish() {
    Impl& w = *impl_;
    if (!w.started || !w.file) {
        w.close_file();
        return false;
    }
    w.started = false;
    const uint64_t end = w.pos;
    std::vector<Timing> timings(w.tracks.size());
    int64_t first_us = 0;
    bool any = false;
    for (size_t i = 0; i < w.tracks.size(); ++i) {
        Track& t = w.tracks[i];
        if (t.samples.empty()) continue;
        if (!t.fmt.video && t.asc.empty()) {
            // AAC-LC at the track's rate, when the app gave no csd.
            const int idx = sample_rate_index(t.fmt.sample_rate);
            if (idx >= 0) {
                t.asc = {static_cast<uint8_t>((2 << 3) | (idx >> 1)),
                         static_cast<uint8_t>(((idx & 1) << 7) | (t.fmt.channels << 3))};
            }
        }
        timings[i] = timing_of(t);
        first_us = any ? std::min(first_us, timings[i].first_us) : timings[i].first_us;
        any = true;
    }
    Buf b;
    const size_t moov = b.open("moov");
    uint64_t duration = 0;
    for (size_t i = 0; i < w.tracks.size(); ++i) {
        if (w.tracks[i].samples.empty()) continue;
        duration = std::max(duration, static_cast<uint64_t>(ticks(timings[i].first_us - first_us, kMovieScale)) +
                                          timings[i].media_duration * kMovieScale / w.tracks[i].timescale);
    }
    const uint32_t now = static_cast<uint32_t>(static_cast<uint64_t>(std::time(nullptr)) + 2082844800ull);  // since 1904
    const size_t mvhd = b.open("mvhd", 0, 0);
    b.u32(now);
    b.u32(now);
    b.u32(kMovieScale);
    b.u32(static_cast<uint32_t>(duration));
    b.u32(0x00010000);
    b.u16(0x0100);
    b.zeros(10);
    matrix(b, 0);
    b.zeros(24);
    b.u32(static_cast<uint32_t>(w.tracks.size() + 1));
    b.close(mvhd);
    for (size_t i = 0; i < w.tracks.size(); ++i) {
        if (w.tracks[i].samples.empty()) continue;
        w.track_box(b, w.tracks[i], timings[i], static_cast<uint32_t>(i + 1), timings[i].first_us - first_us);
    }
    if (w.located) {
        // Android's own form: ISO 6709, "+37.4220-122.0840/".
        char text[40];
        const int n = std::snprintf(text, sizeof(text), "%+08.4f%+09.4f/", w.latitude, w.longitude);
        const size_t udta = b.open("udta");
        const size_t xyz = b.open("\xa9xyz");
        b.u16(static_cast<uint32_t>(n));
        b.u16(0x15c7);
        b.bytes(reinterpret_cast<const uint8_t*>(text), static_cast<size_t>(n));
        b.close(xyz);
        b.close(udta);
    }
    b.close(moov);
    bool ok = any && w.put(b);
    Buf size;
    size.u64(end - w.mdat_at);
#ifdef _WIN32
    ok = ok && _fseeki64(w.file, static_cast<long long>(w.mdat_at + 8), SEEK_SET) == 0;
#else
    ok = ok && fseeko(w.file, static_cast<off_t>(w.mdat_at + 8), SEEK_SET) == 0;
#endif
    ok = ok && std::fwrite(size.v.data(), 1, 8, w.file) == 8;
    ok = std::fclose(w.file) == 0 && ok;
    w.file = nullptr;
    return ok;
}

int64_t Writer::size() const { return static_cast<int64_t>(impl_->pos); }

}  // namespace winapk::mp4
