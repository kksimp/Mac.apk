// zlib.cpp -- libz.so for guest code, on the host's zlib.
//
// zlib is one of Android's NDK system libraries: apps link against libz.so
// (DT_NEEDED) instead of shipping it, and the linker answers it from the shim.
// Without this module every zlib call reached the "not implemented" stub,
// which returns 0, Z_OK, and does nothing; a decompression loop that reads Z_OK
// as "output buffer full" then grows its buffer until memcpy faults.
//
// The work is done by the real zlib, linked statically (MSYS2's libz.a, zlib
// license). This file is the boundary. Calls arrive System V, and the structs
// differ, because zlib is written in uInt, 32 bits on both sides, and uLong,
// which is 64 bits on Android (LP64) and 32 bits on Windows (LLP64):
//
//   z_stream    Android x86_64 (guest)     Windows x64 (host zlib)
//               offset  size               offset  size
//   next_in        0     8                    0     8
//   avail_in       8     4                    8     4
//   total_in      16     8  uLong            12     4  uLong
//   next_out      24     8                   16     8
//   avail_out     32     4                   24     4
//   total_out     40     8  uLong            28     4  uLong
//   msg           48     8                   32     8
//   state         56     8                   40     8
//   zalloc        64     8                   48     8
//   zfree         72     8                   56     8
//   opaque        80     8                   64     8
//   data_type     88     4                   72     4
//   adler         96     8  uLong            76     4  uLong
//   reserved     104     8  uLong            80     4  uLong
//   sizeof       112                         88
//
// So zlib never sees a guest z_stream. Each guest stream gets a host z_stream
// of its own (ZStream below) from its Init call to its End call, and the
// guest's `state` field points to it. zlib owns that field and applications
// never read it. Every call copies the fields an application exchanges with
// zlib (next/avail/total in and out, msg, data_type, adler) into the host
// stream, runs zlib, and copies them back. The host stream never moves, because
// zlib's state points back at its z_stream and rejects a stream that moved. The
// Init calls check the guest's own terms: stream_size must be 112, and the
// major version must match, as zlib checks.
//
// total_in and total_out are 64-bit in the guest. The guest's counter grows by
// the amount the host's 32-bit counter moved during the call, so it keeps
// counting past 4 GiB as on Android.
//
// zalloc and zfree are guest functions, but zlib calls them with the Microsoft
// ABI. When the guest sets them, the host stream gets host trampolines whose
// opaque is the ZStream. Each trampoline calls the guest's function with the
// guest's opaque.
//
// The rest of the API:
//   gz_header     (inflateGetHeader, deflateSetHeader) has a uLong as well:
//                 time is 8 bytes at offset 8 in the guest and 4 bytes at 4 in
//                 the host, so the later fields move (guest: extra 24,
//                 extra_len 32, name 40, comment 56, done 72, sizeof 80; host:
//                 16, 24, 32, 48, 64, sizeof 72). It is copied like z_stream:
//                 the application's buffers and limits in, the fields inflate
//                 fills in back out.
//   gzFile        points to struct gzFile_s { unsigned have; unsigned char*
//                 next; z_off64_t pos; } at 0, 8 and 16, 24 bytes, on both
//                 sides (MinGW's z_off64_t is long long). The guest's gzgetc()
//                 macro reads the host's gzFile directly. gzopen maps the path
//                 like open() does.
//   z_off_t       is off_t, 64 bits in the guest and 32 bits in the host, so
//                 gzseek/gztell/gzoffset and the *_combine functions use the
//                 host's *64 forms.
//   lengths       compress and uncompress (and their 2 forms) take uLong
//                 lengths. They are rebuilt on the stream API, as zlib's
//                 compress.c and uncompr.c are, with 64-bit lengths.
//                 compressBound and deflateBound are computed in 64 bits.
//   checksums     CRC-32 and Adler-32 values are 32 bits; uLong only widens the
//                 argument and the result.

#include "bionic_internal.h"

#include "winapk/abi_thunk.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include <zlib.h>

// The library has the 64-bit offset forms (z_off64_t is long long on MinGW),
// but zlib.h declares them only where the C library advertises large-file
// support, which the UCRT's headers do not.
extern "C" {
long long ZEXPORT gzseek64(gzFile, long long, int);
long long ZEXPORT gztell64(gzFile);
long long ZEXPORT gzoffset64(gzFile);
uLong ZEXPORT adler32_combine64(uLong, uLong, long long);
uLong ZEXPORT crc32_combine64(uLong, uLong, long long);
uLong ZEXPORT crc32_combine_gen64(long long);
}

namespace winapk::bionic {

namespace {

static_assert(sizeof(uInt) == 4 && sizeof(uLong) == 4 && sizeof(z_size_t) == 8 && sizeof(z_crc_t) == 4,
              "Windows zlib's types (LLP64)");
static_assert(sizeof(z_stream) == 88 && offsetof(z_stream, total_in) == 12 && offsetof(z_stream, state) == 40 &&
                  offsetof(z_stream, adler) == 76,
              "Windows zlib's z_stream, as drawn at the top of the file");
static_assert(sizeof(gz_header) == 72 && offsetof(gz_header, extra) == 16 && offsetof(gz_header, done) == 64,
              "Windows zlib's gz_header");
static_assert(offsetof(gzFile_s, next) == 8 && offsetof(gzFile_s, pos) == 16 && sizeof(gzFile_s) == 24,
              "struct gzFile_s, which the guest's gzgetc() macro reads");

using GuestAlloc = void*(WINAPK_GUEST_ABI*)(void* opaque, uInt items, uInt size);
using GuestFree = void(WINAPK_GUEST_ABI*)(void* opaque, void* address);

// Android's z_stream on x86_64.
struct GuestZStream {
    const Bytef* next_in;
    uInt avail_in;
    gulong total_in;
    Bytef* next_out;
    uInt avail_out;
    gulong total_out;
    const char* msg;
    void* state;  // here: the stream's ZStream
    GuestAlloc zalloc;
    GuestFree zfree;
    void* opaque;
    int data_type;
    gulong adler;
    gulong reserved;
};
static_assert(sizeof(GuestZStream) == 112 && offsetof(GuestZStream, total_in) == 16 &&
                  offsetof(GuestZStream, state) == 56 && offsetof(GuestZStream, adler) == 96,
              "Android's z_stream, as drawn at the top of the file");

// Android's gz_header on x86_64.
struct GuestGzHeader {
    int text;
    gulong time;
    int xflags;
    int os;
    Bytef* extra;
    uInt extra_len;
    uInt extra_max;
    Bytef* name;
    uInt name_max;
    Bytef* comment;
    uInt comm_max;
    int hcrc;
    int done;
};
static_assert(sizeof(GuestGzHeader) == 80 && offsetof(GuestGzHeader, extra) == 24 &&
                  offsetof(GuestGzHeader, comment) == 56 && offsetof(GuestGzHeader, done) == 72,
              "Android's gz_header");

// The host side of one guest stream.
struct ZStream {
    z_stream host{};
    GuestZStream* guest = nullptr;
    GuestAlloc alloc = nullptr;  // the guest's zalloc and zfree, if it set them
    GuestFree free = nullptr;
    void* opaque = nullptr;      // what they get as opaque
    GuestGzHeader* header = nullptr;  // from inflateGetHeader or deflateSetHeader
    gz_header host_header{};
    bool header_from_inflate = false;  // inflate fills it in; deflate reads it
};

// zlib's allocator calls, Microsoft ABI, forwarded to the guest's functions.
// zlib's defaults are malloc and free, which are also the guest's.
voidpf host_alloc(voidpf opaque, uInt items, uInt size) {
    auto* s = static_cast<ZStream*>(opaque);
    return s->alloc ? s->alloc(s->opaque, items, size) : std::malloc(size_t{items} * size);
}
void host_free(voidpf opaque, voidpf address) {
    auto* s = static_cast<ZStream*>(opaque);
    if (s->free) {
        s->free(s->opaque, address);
    } else {
        std::free(address);
    }
}

ZStream* new_stream(GuestZStream* g) {
    auto* s = new (std::nothrow) ZStream;
    if (!s) return nullptr;
    s->guest = g;
    if (g->zalloc || g->zfree) {
        s->alloc = g->zalloc;
        s->free = g->zfree;
        // zlib's rule: without a zalloc, opaque is reset to null.
        s->opaque = g->zalloc ? g->opaque : nullptr;
        s->host.zalloc = host_alloc;
        s->host.zfree = host_free;
        s->host.opaque = s;
    }
    return s;
}

// The guest's stream, if it is one this module initialized.
ZStream* stream_of(GuestZStream* g) {
    auto* s = g ? static_cast<ZStream*>(g->state) : nullptr;
    return s && s->guest == g ? s : nullptr;
}

// `all`: every field (deflate reads the whole header). Otherwise only what the
// application gives inflate: its buffers and their sizes.
void header_in(ZStream* s, bool all) {
    const GuestGzHeader* g = s->header;
    gz_header& h = s->host_header;
    h.extra = g->extra;
    h.extra_max = g->extra_max;
    h.name = g->name;
    h.name_max = g->name_max;
    h.comment = g->comment;
    h.comm_max = g->comm_max;
    if (!all) return;
    h.text = g->text;
    h.time = static_cast<uLong>(g->time);
    h.xflags = g->xflags;
    h.os = g->os;
    h.extra_len = g->extra_len;
    h.hcrc = g->hcrc;
    h.done = g->done;
}

// What inflate filled in.
void header_out(ZStream* s) {
    GuestGzHeader* g = s->header;
    const gz_header& h = s->host_header;
    g->text = h.text;
    g->time = h.time;
    g->xflags = h.xflags;
    g->os = h.os;
    g->extra_len = h.extra_len;
    g->hcrc = h.hcrc;
    g->done = h.done;
}

void copy_in(ZStream* s) {
    const GuestZStream* g = s->guest;
    z_stream& h = s->host;
    h.next_in = const_cast<Bytef*>(g->next_in);
    h.avail_in = g->avail_in;
    h.total_in = static_cast<uLong>(g->total_in);
    h.next_out = g->next_out;
    h.avail_out = g->avail_out;
    h.total_out = static_cast<uLong>(g->total_out);
    h.msg = const_cast<char*>(g->msg);
    h.data_type = g->data_type;
    h.adler = static_cast<uLong>(g->adler);
    if (s->header) header_in(s, !s->header_from_inflate);
}

// `reset`: the call set the totals (an Init or a Reset) instead of advancing
// them.
void copy_out(ZStream* s, bool reset) {
    GuestZStream* g = s->guest;
    const z_stream& h = s->host;
    if (reset) {
        g->total_in = h.total_in;
        g->total_out = h.total_out;
    } else {
        // uLong arithmetic: the 32-bit counter's movement, wrap included.
        g->total_in += static_cast<uLong>(h.total_in - static_cast<uLong>(g->total_in));
        g->total_out += static_cast<uLong>(h.total_out - static_cast<uLong>(g->total_out));
    }
    g->next_in = h.next_in;
    g->avail_in = h.avail_in;
    g->next_out = h.next_out;
    g->avail_out = h.avail_out;
    g->msg = h.msg;
    g->data_type = h.data_type;
    g->adler = h.adler;
    if (s->header && s->header_from_inflate) header_out(s);
}

// One zlib call on an initialized stream, its fields copied in and out.
template <typename Call>
int run(GuestZStream* g, Call&& call, bool reset = false) {
    ZStream* s = stream_of(g);
    if (!s) return Z_STREAM_ERROR;
    copy_in(s);
    const int r = call(&s->host);
    copy_out(s, reset);
    return r;
}

// An Init call: the guest's version and size checks, then `init` on a new
// host stream.
template <typename Init>
int start(GuestZStream* g, const char* version, int stream_size, Init&& init) {
    if (!version || version[0] != ZLIB_VERSION[0] || stream_size != static_cast<int>(sizeof(GuestZStream))) {
        return Z_VERSION_ERROR;
    }
    if (!g) return Z_STREAM_ERROR;
    ZStream* s = new_stream(g);
    if (!s) return Z_MEM_ERROR;
    copy_in(s);
    const int r = init(&s->host);
    copy_out(s, true);
    if (r == Z_OK) {
        g->state = s;
    } else {
        g->state = nullptr;
        delete s;
    }
    return r;
}

// An End call. zlib frees its state whatever it returns, except on
// Z_STREAM_ERROR.
template <typename End>
int finish(GuestZStream* g, End&& end) {
    ZStream* s = stream_of(g);
    if (!s) return Z_STREAM_ERROR;
    copy_in(s);
    const int r = end(&s->host);
    if (r == Z_STREAM_ERROR) return r;
    copy_out(s, false);
    g->state = nullptr;
    delete s;
    return r;
}

// deflateCopy/inflateCopy. zlib copies the whole z_stream, then gives the copy
// a state of its own; the guest's copy is made the same way.
template <typename Copy>
int copy_stream(GuestZStream* dest, GuestZStream* source, Copy&& copy) {
    ZStream* src = stream_of(source);
    if (!dest || !src) return Z_STREAM_ERROR;
    copy_in(src);
    auto* d = new (std::nothrow) ZStream;
    if (!d) return Z_MEM_ERROR;
    const int r = copy(&d->host, &src->host);
    if (r != Z_OK) {
        delete d;
        return r;
    }
    d->alloc = src->alloc;
    d->free = src->free;
    d->opaque = src->opaque;
    if (d->host.opaque == src) d->host.opaque = d;
    // The copied state still points at the source's header.
    d->header = src->header;
    d->header_from_inflate = src->header_from_inflate;
    d->host_header = src->host_header;
    if (d->header && d->header_from_inflate) {
        const int done = d->host_header.done;
        ::inflateGetHeader(&d->host, &d->host_header);  // also clears done
        d->host_header.done = done;
    } else if (d->header) {
        ::deflateSetHeader(&d->host, &d->host_header);
    }
    std::memcpy(dest, source, sizeof(GuestZStream));
    dest->state = d;
    d->guest = dest;
    return r;
}

// inflateGetHeader/deflateSetHeader.
template <typename Set>
int set_header(GuestZStream* g, GuestGzHeader* head, bool from_inflate, Set&& set) {
    ZStream* s = stream_of(g);
    if (!s || !head) return Z_STREAM_ERROR;
    GuestGzHeader* const old_header = s->header;
    const bool old_from_inflate = s->header_from_inflate;
    const gz_header old_host_header = s->host_header;
    s->header = head;
    s->header_from_inflate = from_inflate;
    copy_in(s);
    header_in(s, true);
    const int r = set(&s->host, &s->host_header);
    copy_out(s, false);
    if (r != Z_OK) {
        // zlib keeps the header it had (none, or the previous one).
        s->header = old_header;
        s->header_from_inflate = old_from_inflate;
        s->host_header = old_host_header;
    }
    return r;
}

// ---- library information ------------------------------------------------------
WINAPK_GUEST_ABI const char* g_zlibVersion() { return ::zlibVersion(); }

WINAPK_GUEST_ABI gulong g_zlibCompileFlags() {
    // Bits 2-3 and 6-7 are the sizes of uLong and z_off_t; 2 means 64 bits.
    gulong flags = ::zlibCompileFlags();
    flags &= ~gulong{0xcc};
    flags |= (2u << 2) | (2u << 6);
    return flags;
}

WINAPK_GUEST_ABI const char* g_zError(int err) { return ::zError(err); }

// ---- deflate --------------------------------------------------------------------
WINAPK_GUEST_ABI int g_deflateInit_(GuestZStream* g, int level, const char* version, int stream_size) {
    return start(g, version, stream_size, [&](z_stream* h) { return deflateInit(h, level); });
}
WINAPK_GUEST_ABI int g_deflateInit2_(GuestZStream* g, int level, int method, int window_bits, int mem_level,
                                     int strategy, const char* version, int stream_size) {
    return start(g, version, stream_size, [&](z_stream* h) {
        return deflateInit2(h, level, method, window_bits, mem_level, strategy);
    });
}
WINAPK_GUEST_ABI int g_deflate(GuestZStream* g, int flush) {
    return run(g, [&](z_stream* h) { return ::deflate(h, flush); });
}
WINAPK_GUEST_ABI int g_deflateEnd(GuestZStream* g) { return finish(g, ::deflateEnd); }
WINAPK_GUEST_ABI int g_deflateSetDictionary(GuestZStream* g, const Bytef* dict, uInt len) {
    return run(g, [&](z_stream* h) { return ::deflateSetDictionary(h, dict, len); });
}
WINAPK_GUEST_ABI int g_deflateGetDictionary(GuestZStream* g, Bytef* dict, uInt* len) {
    return run(g, [&](z_stream* h) { return ::deflateGetDictionary(h, dict, len); });
}
WINAPK_GUEST_ABI int g_deflateCopy(GuestZStream* dest, GuestZStream* source) {
    return copy_stream(dest, source, ::deflateCopy);
}
WINAPK_GUEST_ABI int g_deflateReset(GuestZStream* g) { return run(g, ::deflateReset, true); }
WINAPK_GUEST_ABI int g_deflateResetKeep(GuestZStream* g) { return run(g, ::deflateResetKeep, true); }
WINAPK_GUEST_ABI int g_deflateParams(GuestZStream* g, int level, int strategy) {
    return run(g, [&](z_stream* h) { return ::deflateParams(h, level, strategy); });
}
WINAPK_GUEST_ABI int g_deflateTune(GuestZStream* g, int good_length, int max_lazy, int nice_length, int max_chain) {
    return run(g, [&](z_stream* h) { return ::deflateTune(h, good_length, max_lazy, nice_length, max_chain); });
}
WINAPK_GUEST_ABI int g_deflatePending(GuestZStream* g, unsigned* pending, int* bits) {
    return run(g, [&](z_stream* h) { return ::deflatePending(h, pending, bits); });
}
WINAPK_GUEST_ABI int g_deflatePrime(GuestZStream* g, int bits, int value) {
    return run(g, [&](z_stream* h) { return ::deflatePrime(h, bits, value); });
}
WINAPK_GUEST_ABI int g_deflateSetHeader(GuestZStream* g, GuestGzHeader* head) {
    return set_header(g, head, false, ::deflateSetHeader);
}

WINAPK_GUEST_ABI gulong g_deflateBound(GuestZStream* g, gulong source_len) {
    // zlib's two conservative bounds, in 64 bits.
    const gulong fixed = source_len + (source_len >> 3) + (source_len >> 8) + (source_len >> 9) + 4;
    const gulong stored = source_len + (source_len >> 5) + (source_len >> 7) + (source_len >> 11) + 7;
    const gulong larger = fixed > stored ? fixed : stored;
    ZStream* s = stream_of(g);
    if (!s) return larger + 6;
    copy_in(s);
    // zlib computes in its 32-bit uLong, which these lengths cannot overflow.
    if (source_len <= 0x0fffffff) return ::deflateBound(&s->host, static_cast<uLong>(source_len));
    // At least the wrapper's length plus 4.
    return larger + ::deflateBound(&s->host, 0);
}

// ---- inflate --------------------------------------------------------------------
WINAPK_GUEST_ABI int g_inflateInit_(GuestZStream* g, const char* version, int stream_size) {
    return start(g, version, stream_size, [](z_stream* h) { return inflateInit(h); });
}
WINAPK_GUEST_ABI int g_inflateInit2_(GuestZStream* g, int window_bits, const char* version, int stream_size) {
    return start(g, version, stream_size, [&](z_stream* h) { return inflateInit2(h, window_bits); });
}
WINAPK_GUEST_ABI int g_inflate(GuestZStream* g, int flush) {
    return run(g, [&](z_stream* h) { return ::inflate(h, flush); });
}
WINAPK_GUEST_ABI int g_inflateEnd(GuestZStream* g) { return finish(g, ::inflateEnd); }
WINAPK_GUEST_ABI int g_inflateSetDictionary(GuestZStream* g, const Bytef* dict, uInt len) {
    return run(g, [&](z_stream* h) { return ::inflateSetDictionary(h, dict, len); });
}
WINAPK_GUEST_ABI int g_inflateGetDictionary(GuestZStream* g, Bytef* dict, uInt* len) {
    return run(g, [&](z_stream* h) { return ::inflateGetDictionary(h, dict, len); });
}
WINAPK_GUEST_ABI int g_inflateSync(GuestZStream* g) { return run(g, ::inflateSync); }
WINAPK_GUEST_ABI int g_inflateSyncPoint(GuestZStream* g) { return run(g, ::inflateSyncPoint); }
WINAPK_GUEST_ABI int g_inflateCopy(GuestZStream* dest, GuestZStream* source) {
    return copy_stream(dest, source, ::inflateCopy);
}
WINAPK_GUEST_ABI int g_inflateReset(GuestZStream* g) { return run(g, ::inflateReset, true); }
WINAPK_GUEST_ABI int g_inflateResetKeep(GuestZStream* g) { return run(g, ::inflateResetKeep, true); }
WINAPK_GUEST_ABI int g_inflateReset2(GuestZStream* g, int window_bits) {
    return run(g, [&](z_stream* h) { return ::inflateReset2(h, window_bits); }, true);
}
WINAPK_GUEST_ABI int g_inflatePrime(GuestZStream* g, int bits, int value) {
    return run(g, [&](z_stream* h) { return ::inflatePrime(h, bits, value); });
}
WINAPK_GUEST_ABI int g_inflateUndermine(GuestZStream* g, int subvert) {
    return run(g, [&](z_stream* h) { return ::inflateUndermine(h, subvert); });
}
WINAPK_GUEST_ABI int g_inflateValidate(GuestZStream* g, int check) {
    return run(g, [&](z_stream* h) { return ::inflateValidate(h, check); });
}
WINAPK_GUEST_ABI int g_inflateGetHeader(GuestZStream* g, GuestGzHeader* head) {
    return set_header(g, head, true, ::inflateGetHeader);
}

// long and unsigned long results: the host's 32-bit values, widened.
WINAPK_GUEST_ABI glong g_inflateMark(GuestZStream* g) {
    ZStream* s = stream_of(g);
    if (!s) return -(glong{1} << 16);
    copy_in(s);
    return ::inflateMark(&s->host);
}
WINAPK_GUEST_ABI gulong g_inflateCodesUsed(GuestZStream* g) {
    ZStream* s = stream_of(g);
    if (!s) return ~gulong{0};
    copy_in(s);
    return ::inflateCodesUsed(&s->host);
}

// inflateBack's input and output functions are guest functions too.
using GuestIn = unsigned(WINAPK_GUEST_ABI*)(void* desc, z_const unsigned char** buf);
using GuestOut = int(WINAPK_GUEST_ABI*)(void* desc, unsigned char* buf, unsigned len);
struct BackCalls {
    GuestIn in;
    void* in_desc;
    GuestOut out;
    void* out_desc;
};
unsigned back_in(void* desc, z_const unsigned char** buf) {
    auto* c = static_cast<BackCalls*>(desc);
    return c->in(c->in_desc, buf);
}
int back_out(void* desc, unsigned char* buf, unsigned len) {
    auto* c = static_cast<BackCalls*>(desc);
    return c->out(c->out_desc, buf, len);
}

WINAPK_GUEST_ABI int g_inflateBackInit_(GuestZStream* g, int window_bits, unsigned char* window, const char* version,
                                        int stream_size) {
    return start(g, version, stream_size, [&](z_stream* h) { return inflateBackInit(h, window_bits, window); });
}
WINAPK_GUEST_ABI int g_inflateBack(GuestZStream* g, GuestIn in, void* in_desc, GuestOut out, void* out_desc) {
    BackCalls calls{in, in_desc, out, out_desc};
    return run(g, [&](z_stream* h) {
        return ::inflateBack(h, in ? back_in : nullptr, &calls, out ? back_out : nullptr, &calls);
    });
}
WINAPK_GUEST_ABI int g_inflateBackEnd(GuestZStream* g) { return finish(g, ::inflateBackEnd); }

// ---- one-call compression ---------------------------------------------------------
// zlib's compress.c and uncompr.c, with the guest's 64-bit lengths: the stream
// is fed at most 4 GiB - 1 at a time, and the lengths are counted here rather
// than in the 32-bit totals.
WINAPK_GUEST_ABI int g_compress2(Bytef* dest, gulong* dest_len, const Bytef* source, gulong source_len, int level) {
    constexpr uInt kMax = ~uInt{0};
    gulong left = *dest_len;
    *dest_len = 0;
    z_stream s{};
    int err = deflateInit(&s, level);
    if (err != Z_OK) return err;
    s.next_out = dest;
    s.next_in = const_cast<Bytef*>(source);
    do {
        if (s.avail_out == 0) {
            s.avail_out = left > kMax ? kMax : static_cast<uInt>(left);
            left -= s.avail_out;
        }
        if (s.avail_in == 0) {
            s.avail_in = source_len > kMax ? kMax : static_cast<uInt>(source_len);
            source_len -= s.avail_in;
        }
        err = ::deflate(&s, source_len ? Z_NO_FLUSH : Z_FINISH);
    } while (err == Z_OK);
    *dest_len = static_cast<gulong>(s.next_out - dest);
    ::deflateEnd(&s);
    return err == Z_STREAM_END ? Z_OK : err;
}

WINAPK_GUEST_ABI int g_compress(Bytef* dest, gulong* dest_len, const Bytef* source, gulong source_len) {
    return g_compress2(dest, dest_len, source, source_len, Z_DEFAULT_COMPRESSION);
}

WINAPK_GUEST_ABI gulong g_compressBound(gulong source_len) {
    return source_len + (source_len >> 12) + (source_len >> 14) + (source_len >> 25) + 13;
}

WINAPK_GUEST_ABI int g_uncompress2(Bytef* dest, gulong* dest_len, const Bytef* source, gulong* source_len) {
    constexpr uInt kMax = ~uInt{0};
    Bytef one[1];  // to tell an incomplete stream when *dest_len is 0
    gulong len = *source_len;
    gulong left;
    if (*dest_len) {
        left = *dest_len;
        *dest_len = 0;
    } else {
        left = 1;
        dest = one;
    }
    z_stream s{};
    s.next_in = const_cast<Bytef*>(source);
    int err = inflateInit(&s);
    if (err != Z_OK) return err;
    s.next_out = dest;
    do {
        if (s.avail_out == 0) {
            s.avail_out = left > kMax ? kMax : static_cast<uInt>(left);
            left -= s.avail_out;
        }
        if (s.avail_in == 0) {
            s.avail_in = len > kMax ? kMax : static_cast<uInt>(len);
            len -= s.avail_in;
        }
        err = ::inflate(&s, Z_NO_FLUSH);
    } while (err == Z_OK);
    *source_len -= len + s.avail_in;
    const gulong produced = static_cast<gulong>(s.next_out - dest);
    if (dest != one) {
        *dest_len = produced;
    } else if (produced && err == Z_BUF_ERROR) {
        left = 1;
    }
    ::inflateEnd(&s);
    if (err == Z_STREAM_END) return Z_OK;
    if (err == Z_NEED_DICT) return Z_DATA_ERROR;
    if (err == Z_BUF_ERROR && left + s.avail_out) return Z_DATA_ERROR;
    return err;
}

WINAPK_GUEST_ABI int g_uncompress(Bytef* dest, gulong* dest_len, const Bytef* source, gulong source_len) {
    return g_uncompress2(dest, dest_len, source, &source_len);
}

// ---- checksums ----------------------------------------------------------------------
WINAPK_GUEST_ABI gulong g_adler32(gulong adler, const Bytef* buf, uInt len) {
    return ::adler32(static_cast<uLong>(adler), buf, len);
}
WINAPK_GUEST_ABI gulong g_adler32_z(gulong adler, const Bytef* buf, size_t len) {
    return ::adler32_z(static_cast<uLong>(adler), buf, len);
}
WINAPK_GUEST_ABI gulong g_adler32_combine(gulong adler1, gulong adler2, int64_t len2) {
    return ::adler32_combine64(static_cast<uLong>(adler1), static_cast<uLong>(adler2), len2);
}
WINAPK_GUEST_ABI gulong g_crc32(gulong crc, const Bytef* buf, uInt len) {
    return ::crc32(static_cast<uLong>(crc), buf, len);
}
WINAPK_GUEST_ABI gulong g_crc32_z(gulong crc, const Bytef* buf, size_t len) {
    return ::crc32_z(static_cast<uLong>(crc), buf, len);
}
WINAPK_GUEST_ABI gulong g_crc32_combine(gulong crc1, gulong crc2, int64_t len2) {
    return ::crc32_combine64(static_cast<uLong>(crc1), static_cast<uLong>(crc2), len2);
}
WINAPK_GUEST_ABI gulong g_crc32_combine_gen(int64_t len2) { return ::crc32_combine_gen64(len2); }
WINAPK_GUEST_ABI gulong g_crc32_combine_op(gulong crc1, gulong crc2, gulong op) {
    return ::crc32_combine_op(static_cast<uLong>(crc1), static_cast<uLong>(crc2), static_cast<uLong>(op));
}
WINAPK_GUEST_ABI const z_crc_t* g_get_crc_table() { return ::get_crc_table(); }

// ---- gzip files -----------------------------------------------------------------------
WINAPK_GUEST_ABI gzFile g_gzopen(const char* path, const char* mode) {
    if (!path || !mode) return nullptr;
    gzFile f = ::gzopen_w(host_path(path).c_str(), mode);
    if (!f) set_errno_from_crt(errno);
    return f;
}
// Guest descriptors for files are the CRT's own (stdio.cpp).
WINAPK_GUEST_ABI gzFile g_gzdopen(int fd, const char* mode) { return ::gzdopen(fd, mode); }
WINAPK_GUEST_ABI int g_gzbuffer(gzFile f, unsigned size) { return ::gzbuffer(f, size); }
WINAPK_GUEST_ABI int g_gzsetparams(gzFile f, int level, int strategy) { return ::gzsetparams(f, level, strategy); }
WINAPK_GUEST_ABI int g_gzread(gzFile f, voidp buf, unsigned len) { return ::gzread(f, buf, len); }
WINAPK_GUEST_ABI size_t g_gzfread(voidp buf, size_t size, size_t n, gzFile f) { return ::gzfread(buf, size, n, f); }
WINAPK_GUEST_ABI int g_gzwrite(gzFile f, voidpc buf, unsigned len) { return ::gzwrite(f, buf, len); }
WINAPK_GUEST_ABI size_t g_gzfwrite(voidpc buf, size_t size, size_t n, gzFile f) {
    return ::gzfwrite(buf, size, n, f);
}
WINAPK_GUEST_ABI int g_gzputs(gzFile f, const char* s) { return ::gzputs(f, s); }
WINAPK_GUEST_ABI char* g_gzgets(gzFile f, char* buf, int len) { return ::gzgets(f, buf, len); }
WINAPK_GUEST_ABI int g_gzputc(gzFile f, int c) { return ::gzputc(f, c); }
WINAPK_GUEST_ABI int g_gzgetc(gzFile f) { return (::gzgetc)(f); }  // the function, not zlib.h's macro
WINAPK_GUEST_ABI int g_gzgetc_(gzFile f) { return ::gzgetc_(f); }
WINAPK_GUEST_ABI int g_gzungetc(int c, gzFile f) { return ::gzungetc(c, f); }
WINAPK_GUEST_ABI int g_gzflush(gzFile f, int flush) { return ::gzflush(f, flush); }
WINAPK_GUEST_ABI int64_t g_gzseek(gzFile f, int64_t offset, int whence) { return ::gzseek64(f, offset, whence); }
WINAPK_GUEST_ABI int g_gzrewind(gzFile f) { return ::gzrewind(f); }
WINAPK_GUEST_ABI int64_t g_gztell(gzFile f) { return ::gztell64(f); }
WINAPK_GUEST_ABI int64_t g_gzoffset(gzFile f) { return ::gzoffset64(f); }
WINAPK_GUEST_ABI int g_gzeof(gzFile f) { return ::gzeof(f); }
WINAPK_GUEST_ABI int g_gzdirect(gzFile f) { return ::gzdirect(f); }
WINAPK_GUEST_ABI int g_gzclose(gzFile f) { return ::gzclose(f); }
WINAPK_GUEST_ABI int g_gzclose_r(gzFile f) { return ::gzclose_r(f); }
WINAPK_GUEST_ABI int g_gzclose_w(gzFile f) { return ::gzclose_w(f); }
WINAPK_GUEST_ABI const char* g_gzerror(gzFile f, int* errnum) { return ::gzerror(f, errnum); }
WINAPK_GUEST_ABI void g_gzclearerr(gzFile f) { ::gzclearerr(f); }

// gzprintf: the guest's arguments are formatted by printf.cpp's engine, then
// written. Like zlib's, the result is the bytes written or a negative error.
WINAPK_GUEST_ABI int g_gzvprintf(gzFile f, const char* fmt, SysVVaList* va) {
    if (!f || !fmt) return Z_STREAM_ERROR;
    const std::string text = format_va(fmt, va);
    if (text.empty()) return 0;
    const int n = ::gzwrite(f, text.data(), static_cast<unsigned>(text.size()));
    if (n > 0) return n;
    int err = Z_STREAM_ERROR;
    ::gzerror(f, &err);
    return err < 0 ? err : Z_STREAM_ERROR;
}
WINAPK_GUEST_ABI int h_gzprintf(gzFile f, const char* fmt, SysVVaList* va) { return g_gzvprintf(f, fmt, va); }

}  // namespace

ShimTable zlib_symbols() {
    static const std::vector<ShimSymbol> symbols = {
        WINAPK_SHIM("zlibVersion", g_zlibVersion),
        WINAPK_SHIM("zlibCompileFlags", g_zlibCompileFlags),
        WINAPK_SHIM("zError", g_zError),
        WINAPK_SHIM("deflateInit_", g_deflateInit_),
        WINAPK_SHIM("deflateInit2_", g_deflateInit2_),
        WINAPK_SHIM("deflate", g_deflate),
        WINAPK_SHIM("deflateEnd", g_deflateEnd),
        WINAPK_SHIM("deflateSetDictionary", g_deflateSetDictionary),
        WINAPK_SHIM("deflateGetDictionary", g_deflateGetDictionary),
        WINAPK_SHIM("deflateCopy", g_deflateCopy),
        WINAPK_SHIM("deflateReset", g_deflateReset),
        WINAPK_SHIM("deflateResetKeep", g_deflateResetKeep),
        WINAPK_SHIM("deflateParams", g_deflateParams),
        WINAPK_SHIM("deflateTune", g_deflateTune),
        WINAPK_SHIM("deflateBound", g_deflateBound),
        WINAPK_SHIM("deflatePending", g_deflatePending),
        WINAPK_SHIM("deflatePrime", g_deflatePrime),
        WINAPK_SHIM("deflateSetHeader", g_deflateSetHeader),
        WINAPK_SHIM("inflateInit_", g_inflateInit_),
        WINAPK_SHIM("inflateInit2_", g_inflateInit2_),
        WINAPK_SHIM("inflate", g_inflate),
        WINAPK_SHIM("inflateEnd", g_inflateEnd),
        WINAPK_SHIM("inflateSetDictionary", g_inflateSetDictionary),
        WINAPK_SHIM("inflateGetDictionary", g_inflateGetDictionary),
        WINAPK_SHIM("inflateSync", g_inflateSync),
        WINAPK_SHIM("inflateSyncPoint", g_inflateSyncPoint),
        WINAPK_SHIM("inflateCopy", g_inflateCopy),
        WINAPK_SHIM("inflateReset", g_inflateReset),
        WINAPK_SHIM("inflateResetKeep", g_inflateResetKeep),
        WINAPK_SHIM("inflateReset2", g_inflateReset2),
        WINAPK_SHIM("inflatePrime", g_inflatePrime),
        WINAPK_SHIM("inflateMark", g_inflateMark),
        WINAPK_SHIM("inflateGetHeader", g_inflateGetHeader),
        WINAPK_SHIM("inflateUndermine", g_inflateUndermine),
        WINAPK_SHIM("inflateValidate", g_inflateValidate),
        WINAPK_SHIM("inflateCodesUsed", g_inflateCodesUsed),
        WINAPK_SHIM("inflateBackInit_", g_inflateBackInit_),
        WINAPK_SHIM("inflateBack", g_inflateBack),
        WINAPK_SHIM("inflateBackEnd", g_inflateBackEnd),
        WINAPK_SHIM("compress", g_compress),
        WINAPK_SHIM("compress2", g_compress2),
        WINAPK_SHIM("compressBound", g_compressBound),
        WINAPK_SHIM("uncompress", g_uncompress),
        WINAPK_SHIM("uncompress2", g_uncompress2),
        WINAPK_SHIM("adler32", g_adler32),
        WINAPK_SHIM("adler32_z", g_adler32_z),
        WINAPK_SHIM("adler32_combine", g_adler32_combine),
        WINAPK_SHIM("adler32_combine64", g_adler32_combine),
        WINAPK_SHIM("crc32", g_crc32),
        WINAPK_SHIM("crc32_z", g_crc32_z),
        WINAPK_SHIM("crc32_combine", g_crc32_combine),
        WINAPK_SHIM("crc32_combine64", g_crc32_combine),
        WINAPK_SHIM("crc32_combine_gen", g_crc32_combine_gen),
        WINAPK_SHIM("crc32_combine_gen64", g_crc32_combine_gen),
        WINAPK_SHIM("crc32_combine_op", g_crc32_combine_op),
        WINAPK_SHIM("get_crc_table", g_get_crc_table),
        // z_off_t is 64 bits in the guest, so the *64 names are the same calls.
        WINAPK_SHIM("gzopen", g_gzopen),
        WINAPK_SHIM("gzopen64", g_gzopen),
        WINAPK_SHIM("gzdopen", g_gzdopen),
        WINAPK_SHIM("gzbuffer", g_gzbuffer),
        WINAPK_SHIM("gzsetparams", g_gzsetparams),
        WINAPK_SHIM("gzread", g_gzread),
        WINAPK_SHIM("gzfread", g_gzfread),
        WINAPK_SHIM("gzwrite", g_gzwrite),
        WINAPK_SHIM("gzfwrite", g_gzfwrite),
        WINAPK_SHIM("gzputs", g_gzputs),
        WINAPK_SHIM("gzgets", g_gzgets),
        WINAPK_SHIM("gzputc", g_gzputc),
        WINAPK_SHIM("gzgetc", g_gzgetc),
        WINAPK_SHIM("gzgetc_", g_gzgetc_),
        WINAPK_SHIM("gzungetc", g_gzungetc),
        WINAPK_SHIM("gzflush", g_gzflush),
        WINAPK_SHIM("gzseek", g_gzseek),
        WINAPK_SHIM("gzseek64", g_gzseek),
        WINAPK_SHIM("gzrewind", g_gzrewind),
        WINAPK_SHIM("gztell", g_gztell),
        WINAPK_SHIM("gztell64", g_gztell),
        WINAPK_SHIM("gzoffset", g_gzoffset),
        WINAPK_SHIM("gzoffset64", g_gzoffset),
        WINAPK_SHIM("gzeof", g_gzeof),
        WINAPK_SHIM("gzdirect", g_gzdirect),
        WINAPK_SHIM("gzclose", g_gzclose),
        WINAPK_SHIM("gzclose_r", g_gzclose_r),
        WINAPK_SHIM("gzclose_w", g_gzclose_w),
        WINAPK_SHIM("gzerror", g_gzerror),
        WINAPK_SHIM("gzclearerr", g_gzclearerr),
        WINAPK_SHIM("gzvprintf", g_gzvprintf),
        {"gzprintf", make_sysv_variadic_entry(2, reinterpret_cast<void*>(&h_gzprintf))},
    };
    return ShimTable{symbols.data(), symbols.size()};
}

}  // namespace winapk::bionic
