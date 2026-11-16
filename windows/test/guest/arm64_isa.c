// arm64_isa.c -- pure functions for the arm64 differential test.
//
// The same file is compiled twice: for aarch64-linux-android (the guest
// library test/arm64_test.cpp runs natively) and for the host
// (into the test itself). Every function must give the same result both ways,
// so the host build is the reference. Nothing here calls anything: these
// functions exercise the CPU only, not the host bridge.
//
// -O2 and -O3 builds of this file use most of what clang emits for ordinary
// C: the integer and bitfield instructions, conditional selects, all load and
// store forms, jump tables, 128-bit multiplies, scalar FP and conversions,
// and auto-vectorized NEON loops (integer arithmetic, compares, min/max,
// widening, narrowing, shifts, reductions, permutes, loads/stores of
// structures).

#include <stdint.h>

// Arm fuses a*b+c into one rounding (FMADD) where x86 without FMA cannot;
// both builds must round the same way to be comparable.
#pragma STDC FP_CONTRACT OFF

#ifndef ISA_EXPORT
#define ISA_EXPORT __attribute__((visibility("default"), noinline))
#endif

typedef uint64_t u64;
typedef int64_t i64;
typedef uint32_t u32;
typedef int32_t i32;
typedef uint16_t u16;
typedef int16_t i16;
typedef uint8_t u8;
typedef int8_t i8;

// ---- integer: u64 f(u64, u64, u64) --------------------------------------------

ISA_EXPORT u64 isa_arith(u64 a, u64 b, u64 c) {
    return (a + b * 3) ^ (c - a) ^ ((a << 7) | (b >> 3)) ^ (u64)((i64)c >> 5);
}

ISA_EXPORT u64 isa_arith32(u64 a, u64 b, u64 c) {
    u32 x = (u32)a, y = (u32)b, z = (u32)c;
    u32 r = x * 2654435761u + (y >> (z & 31)) - (u32)((i32)z >> (x & 31)) + (y << (x & 31));
    return (u64)r | ((u64)(x < y) << 32) | ((u64)((i32)x < (i32)y) << 33);
}

ISA_EXPORT u64 isa_divide(u64 a, u64 b, u64 c) {
    u64 r = 0;
    if (b) r += a / b + a % b;
    if ((i64)c != 0 && !((i64)a == INT64_MIN && (i64)c == -1)) r += (u64)((i64)a / (i64)c) + (u64)((i64)a % (i64)c);
    u32 x = (u32)a, y = (u32)c;
    if (y) r += x / y;
    if ((i32)y != 0 && !((i32)x == INT32_MIN && (i32)y == -1)) r ^= (u64)(i64)((i32)x / (i32)y);
    return r;
}

ISA_EXPORT u64 isa_mulhi(u64 a, u64 b, u64 c) {
    unsigned __int128 u = (unsigned __int128)a * b;
    __int128 s = (__int128)(i64)b * (i64)c;
    return (u64)(u >> 64) ^ (u64)u ^ (u64)(s >> 64) ^ (u64)((i64)(i32)a * (i64)(i32)c) ^
           (u64)((u64)(u32)b * (u32)c);
}

ISA_EXPORT u64 isa_bits(u64 a, u64 b, u64 c) {
    u64 r = 0;
    r += a ? (u64)__builtin_clzll(a) : 64;
    r += b ? (u64)__builtin_ctzll(b) : 64;
    r += (u64)__builtin_popcountll(c);
    r ^= __builtin_bswap64(a) + __builtin_bswap32((u32)b) + __builtin_bswap16((u16)c);
    r ^= (a >> 13) & 0x7ff;             // UBFX
    r ^= (u64)(((i64)(b << 20)) >> 44); // SBFX
    r ^= (c & ~(u64)0xff00) | ((a & 0xff) << 8);  // BFI
    r ^= (a >> 17) | (b << 47);         // EXTR
    r ^= __builtin_rotateleft64(c, (unsigned)(a & 63));
    u32 x = (u32)a;
    r += x ? (u64)__builtin_clz(x) : 32;
    r += (u64)__builtin_clrsbll((i64)b) + (u64)__builtin_clrsb((i32)c);
    return r;
}

ISA_EXPORT u64 isa_bitrev(u64 a, u64 b, u64 c) {
    (void)c;
    return __builtin_bitreverse64(a) ^ __builtin_bitreverse32((u32)b);
}

ISA_EXPORT u64 isa_select(u64 a, u64 b, u64 c) {
    u64 r = 0;
    r += a < b ? a : b;
    r += (i64)a > (i64)c ? 1 : 0;
    r += a == c ? 7 : b + 1;
    r += (a & 4) ? ~b : -c;
    r += ((i64)b <= 0 && c != 3) ? 100 : 200;   // CCMP
    r += (a > 5 || b < 9) ? c : a;
    i64 sa = (i64)a;
    r += (u64)(sa < 0 ? -sa : sa);
    r += (u64)((i32)b < (i32)c ? (i32)b : (i32)c);
    return r;
}

ISA_EXPORT u64 isa_carry(u64 a, u64 b, u64 c) {
    unsigned __int128 x = ((unsigned __int128)a << 64) | b;
    unsigned __int128 y = ((unsigned __int128)c << 64) | (a ^ b);
    unsigned __int128 s = x + y, d = x - y;
    return (u64)(s >> 64) ^ (u64)s ^ (u64)(d >> 64) ^ (u64)d ^ (u64)(x < y);
}

ISA_EXPORT u64 isa_overflow(u64 a, u64 b, u64 c) {
    i64 r1;
    i32 r2;
    u64 r3;
    u64 r = 0;
    r += __builtin_add_overflow((i64)a, (i64)b, &r1) ? 1 : 0;
    r += __builtin_mul_overflow((i32)b, (i32)c, &r2) ? 2 : 0;
    r += __builtin_sub_overflow(a, c, &r3) ? 4 : 0;
    return r + (u64)r1 + (u64)r2 + r3;
}

static u64 jump_table(u64 k, u64 v) {
    switch (k & 15) {
        case 0: return v + 1;
        case 1: return v * 3;
        case 2: return v ^ 0x55;
        case 3: return v - 9;
        case 4: return v << 2;
        case 5: return v >> 1;
        case 6: return ~v;
        case 7: return v & 0xf0f0;
        case 8: return v | 0x1234;
        case 9: return v * v;
        case 10: return v + 1000;
        case 11: return 7;
        default: return v / 3;
    }
}

ISA_EXPORT u64 isa_switch(u64 a, u64 b, u64 c) {
    u64 r = 0;
    for (u64 i = 0; i < 20; ++i) r += jump_table(a + i, b ^ (c * i));
    return r;
}

ISA_EXPORT u64 isa_loop(u64 a, u64 b, u64 c) {
    u64 h = 14695981039346656037ull;
    for (u64 i = 0; i < (c & 255) + 1; ++i) {
        h ^= (a >> (i & 63)) & 0xff;
        h *= 1099511628211ull;
        h += b;
    }
    return h;
}

// Loads and stores of every width, signed and unsigned, through the stack.
ISA_EXPORT u64 isa_memory(u64 a, u64 b, u64 c) {
    volatile u8 buf[64];
    for (int i = 0; i < 64; ++i) buf[i] = (u8)(a * (u64)i + b);
    volatile i8* sb = (volatile i8*)buf;
    volatile u16* h = (volatile u16*)buf;
    volatile i16* sh = (volatile i16*)buf;
    volatile u32* w = (volatile u32*)buf;
    volatile i32* sw = (volatile i32*)buf;
    volatile u64* d = (volatile u64*)buf;
    u64 r = 0;
    unsigned k = (unsigned)(c & 7);
    r += buf[k] + (u64)(i64)sb[k + 3] + h[k] + (u64)(i64)sh[k + 1] + w[k] + (u64)(i64)sw[k / 2 + 1] + d[k / 2];
    d[1] = a;
    w[5] = (u32)b;
    h[13] = (u16)c;
    buf[40] = (u8)(a >> 8);
    r ^= d[1] + w[5] + h[13] + buf[40];
    // unaligned
    u64 un;
    __builtin_memcpy(&un, (const u8*)buf + 3, 8);
    return r ^ un;
}

struct Pair {
    u64 x, y;
    u32 z;
    u16 w;
    u8 v;
};

static __attribute__((noinline)) struct Pair make_pair(u64 a, u64 b) {
    struct Pair p = {a, b, (u32)(a ^ b), (u16)a, (u8)b};
    return p;
}

ISA_EXPORT u64 isa_struct(u64 a, u64 b, u64 c) {
    struct Pair arr[4];
    for (int i = 0; i < 4; ++i) arr[i] = make_pair(a + (u64)i, b * (u64)i + c);
    struct Pair t = arr[c & 3];
    arr[(c + 1) & 3] = t;
    u64 r = 0;
    for (int i = 0; i < 4; ++i) r += arr[i].x * 3 + arr[i].y + arr[i].z + arr[i].w + arr[i].v;
    return r;
}

static __attribute__((noinline)) u64 many(u64 a, u64 b, u64 c, u64 d, u64 e, u64 f, u64 g, u64 h, u64 i, u64 j,
                                          double x, double y) {
    return a + 2 * b + 3 * c + 4 * d + 5 * e + 6 * f + 7 * g + 8 * h + 9 * i + 10 * j + (u64)(i64)(x * 4 - y);
}

ISA_EXPORT u64 isa_calls(u64 a, u64 b, u64 c) {
    return many(a, b, c, a ^ b, b ^ c, a + c, 7, 8, 9, 10, (double)(i64)(a & 0xffff), 0.5);
}

static u64 fib(u64 n) { return n < 2 ? n : fib(n - 1) + fib(n - 2); }

ISA_EXPORT u64 isa_recursion(u64 a, u64 b, u64 c) {
    (void)b;
    (void)c;
    return fib(a % 18);
}

// Atomics: LDXR/STXR loops (or LSE with -march=armv8.1-a), acquire/release.
ISA_EXPORT u64 isa_atomics(u64 a, u64 b, u64 c) {
    static u64 g64;
    static u32 g32;
    static u16 g16;
    static u8 g8;
    __atomic_store_n(&g64, a, __ATOMIC_RELEASE);
    __atomic_store_n(&g32, (u32)b, __ATOMIC_SEQ_CST);
    __atomic_store_n(&g16, (u16)c, __ATOMIC_RELAXED);
    __atomic_store_n(&g8, (u8)a, __ATOMIC_RELAXED);
    u64 r = __atomic_fetch_add(&g64, b, __ATOMIC_SEQ_CST);
    r += __atomic_fetch_sub(&g32, (u32)c, __ATOMIC_ACQ_REL);
    r += __atomic_fetch_or(&g16, (u16)a, __ATOMIC_SEQ_CST);
    r += __atomic_fetch_and(&g8, (u8)b, __ATOMIC_SEQ_CST);
    r += __atomic_exchange_n(&g64, c, __ATOMIC_SEQ_CST);
    u64 expected = c;
    r += __atomic_compare_exchange_n(&g64, &expected, a + 1, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 1 : 0;
    u32 e32 = 12345;
    r += __atomic_compare_exchange_n(&g32, &e32, 7, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST) ? 10 : 20;
    r += e32;
    r ^= __atomic_load_n(&g64, __ATOMIC_ACQUIRE) + __atomic_load_n(&g32, __ATOMIC_ACQUIRE) +
         __atomic_load_n(&g16, __ATOMIC_ACQUIRE) + __atomic_load_n(&g8, __ATOMIC_ACQUIRE);
    return r;
}

ISA_EXPORT u64 isa_atomic128(u64 a, u64 b, u64 c) {
    static unsigned __int128 g;
    __atomic_store_n(&g, ((unsigned __int128)a << 64) | b, __ATOMIC_SEQ_CST);
    unsigned __int128 e = ((unsigned __int128)a << 64) | b;
    int ok = __atomic_compare_exchange_n(&g, &e, (unsigned __int128)c * 3, 0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    unsigned __int128 v = __atomic_load_n(&g, __ATOMIC_SEQ_CST);
    return (u64)v ^ (u64)(v >> 64) ^ (u64)ok;
}

// ---- floating point: u64 f(u64, u64, u64) on bit patterns -----------------------

static double as_double(u64 v) {
    double d;
    __builtin_memcpy(&d, &v, 8);
    return d;
}
static u64 double_bits(double d) {
    u64 v;
    __builtin_memcpy(&v, &d, 8);
    return v;
}
static u64 float_bits(float f) {
    u32 v;
    __builtin_memcpy(&v, &f, 4);
    return v;
}

// Inputs are turned into ordinary finite values first, so the test checks
// arithmetic, not NaN payloads (x86 and Arm differ there by design).
static double finite(u64 v) { return (double)(i64)(v % 2000001) / 1000.0 - 1000.0; }

ISA_EXPORT u64 isa_double(u64 a, u64 b, u64 c) {
    double x = finite(a), y = finite(b), z = finite(c);
    double r = x * y + z;
    r += x / (y == 0 ? 1 : y);
    r -= __builtin_sqrt(__builtin_fabs(z));
    r += __builtin_fma(x, y, z);
    r += x < y ? x : y;
    r += __builtin_fmax(x, z) + __builtin_fmin(y, z);
    r += -x;
    return double_bits(r) ^ (u64)(x > y) ^ ((u64)(x == z) << 1);
}

ISA_EXPORT u64 isa_float(u64 a, u64 b, u64 c) {
    float x = (float)finite(a), y = (float)finite(b), z = (float)finite(c);
    float r = x * y - z;
    r += x / (y == 0 ? 1.0f : y);
    r += __builtin_sqrtf(__builtin_fabsf(x));
    r += __builtin_fmaf(y, z, x);
    r += __builtin_fmaxf(x, y);
    return float_bits(r) ^ (u64)(x >= z);
}

ISA_EXPORT u64 isa_convert(u64 a, u64 b, u64 c) {
    double x = finite(a) * 1000.5, y = finite(b) * 1e15;
    u64 r = 0;
    r += (u64)(i64)x;
    r += (u64)(i32)x;
    r += (u64)(u32)(x < 0 ? -x : x);
    r += (u64)(y < 0 ? -y : y);
    r += (u64)(i64)(float)x;
    r ^= double_bits((double)(i64)c) ^ double_bits((double)c) ^ float_bits((float)(i32)a) ^ float_bits((float)(u32)b);
    r ^= double_bits((double)(float)x) ^ float_bits((float)y);
    r += (u64)(i64)__builtin_round(x) + (u64)(i64)__builtin_floor(x) + (u64)(i64)__builtin_ceil(x) +
         (u64)(i64)__builtin_trunc(x);
    r += (u64)__builtin_lround(x * 0.25);
    return r;
}

ISA_EXPORT u64 isa_fcompare(u64 a, u64 b, u64 c) {
    double x = finite(a), y = finite(b);
    float fx = (float)finite(c);
    u64 r = 0;
    r += x < y;
    r += (x <= y) << 1;
    r += (x == y) << 2;
    r += (x != y) << 3;
    r += (x > y) << 4;
    r += (fx > 0.5f) << 5;
    r += (fx < -2.0f) << 6;
    r += ((x > 0 && y > 0) || fx == 0) << 7;
    double m = x > y ? x : y;
    return r ^ double_bits(m);
}

#ifdef __aarch64__
// FCVT to and from half precision.
static u16 to_half(float f) {
    _Float16 h = (_Float16)f;
    u16 b;
    __builtin_memcpy(&b, &h, 2);
    return b;
}
static float from_half(u16 b) {
    _Float16 h;
    __builtin_memcpy(&h, &b, 2);
    return (float)h;
}
#else
// The host reference, in software: _Float16 conversions are not reliable in
// every x86 toolchain (mingw's libgcc and clang disagree on their ABI).
// Normal, finite values only, which is what isa_half passes.
static u16 to_half(float f) {
    u32 b;
    __builtin_memcpy(&b, &f, 4);
    u32 sign = (b >> 16) & 0x8000, frac = b & 0x7fffff;
    i32 e = (i32)((b >> 23) & 0xff) - 127 + 15;
    if (f == 0) return (u16)sign;
    if (e <= 0) {  // subnormal half
        frac |= 0x800000;
        u32 shift = (u32)(14 - e), half = frac >> shift, rem = frac & ((1u << shift) - 1), mid = 1u << (shift - 1);
        if (rem > mid || (rem == mid && (half & 1))) ++half;
        return (u16)(sign | half);
    }
    u32 half = ((u32)e << 10) | (frac >> 13), rem = frac & 0x1fff;
    if (rem > 0x1000 || (rem == 0x1000 && (half & 1))) ++half;
    return (u16)(sign | half);
}
static float from_half(u16 h) {
    u32 sign = (u32)(h & 0x8000) << 16, e = (h >> 10) & 0x1f, frac = h & 0x3ff, out;
    if (e == 0 && frac == 0) {
        out = sign;
    } else if (e == 0) {
        e = 127 - 15 + 1;
        while (!(frac & 0x400)) {
            frac <<= 1;
            --e;
        }
        out = sign | (e << 23) | ((frac & 0x3ff) << 13);
    } else {
        out = sign | ((e - 15 + 127) << 23) | (frac << 13);
    }
    float f;
    __builtin_memcpy(&f, &out, 4);
    return f;
}
#endif

ISA_EXPORT u64 isa_half(u64 a, u64 b, u64 c) {
    u16 h = to_half((float)finite(a));
    u16 tiny = to_half((float)finite(b) * 1e-7f);  // subnormal halves
    float back = from_half(h) + from_half(tiny) * (float)(i64)(c & 3);
    return float_bits(back) ^ ((u64)h << 32) ^ ((u64)tiny << 48);
}

// ---- vectorizable loops (NEON at -O2/-O3) ---------------------------------------

#define N 67  // not a multiple of the vector width: exercises the scalar tail too

static void fill_u8(u8* p, u64 seed) {
    for (int i = 0; i < N; ++i) {
        seed = seed * 6364136223846793005ull + 1442695040888963407ull;
        p[i] = (u8)(seed >> 56);
    }
}

ISA_EXPORT u64 isa_vec_bytes(u64 a, u64 b, u64 c) {
    u8 x[N], y[N], z[N];
    fill_u8(x, a);
    fill_u8(y, b);
    for (int i = 0; i < N; ++i) z[i] = (u8)(x[i] + y[i]);
    u64 r = 0;
    for (int i = 0; i < N; ++i) z[i] = (u8)((z[i] > y[i] ? z[i] : y[i]) ^ (x[i] < 128 ? 0x5a : x[i]));
    for (int i = 0; i < N; ++i) r += z[i];
    for (int i = 0; i < N; ++i) r += (u64)(x[i] == (u8)c);
    u32 s = 0;
    for (int i = 0; i < N; ++i) s += (u32)x[i] * y[i];
    return r ^ ((u64)s << 20);
}

ISA_EXPORT u64 isa_vec_i16(u64 a, u64 b, u64 c) {
    i16 x[N], y[N];
    for (int i = 0; i < N; ++i) {
        x[i] = (i16)(a * (u64)(i + 1) >> 7);
        y[i] = (i16)(b ^ ((u64)i * c));
    }
    i32 acc = 0;
    for (int i = 0; i < N; ++i) {
        i16 v = (i16)(x[i] * 3 - y[i]);
        v = v < 0 ? (i16)-v : v;
        x[i] = (i16)(v >> 2);
    }
    for (int i = 0; i < N; ++i) acc += (i32)x[i] * y[i];
    i16 mx = -32768, mn = 32767;
    for (int i = 0; i < N; ++i) {
        mx = x[i] > mx ? x[i] : mx;
        mn = y[i] < mn ? y[i] : mn;
    }
    return (u64)(u32)acc ^ ((u64)(u16)mx << 32) ^ ((u64)(u16)mn << 48);
}

ISA_EXPORT u64 isa_vec_i32(u64 a, u64 b, u64 c) {
    i32 x[N], y[N];
    u32 z[N];
    for (int i = 0; i < N; ++i) {
        x[i] = (i32)(a * (u64)i * 2654435761u);
        y[i] = (i32)(b + (u64)i * c);
    }
    for (int i = 0; i < N; ++i) z[i] = (u32)(x[i] >> 3) + ((u32)y[i] << 2) + (u32)(x[i] * y[i]);
    for (int i = 0; i < N; ++i) z[i] = x[i] < y[i] ? z[i] : ~z[i];
    u64 r = 0;
    for (int i = 0; i < N; ++i) r += z[i];
    i64 wide = 0;
    for (int i = 0; i < N; ++i) wide += (i64)x[i] * (i64)y[i];
    u32 mx = 0;
    for (int i = 0; i < N; ++i) mx = z[i] > mx ? z[i] : mx;
    return r ^ (u64)wide ^ ((u64)mx << 7);
}

ISA_EXPORT u64 isa_vec_i64(u64 a, u64 b, u64 c) {
    u64 x[N];
    for (int i = 0; i < N; ++i) x[i] = a * (u64)i + (b ^ (u64)i);
    for (int i = 0; i < N; ++i) x[i] = (x[i] >> 7) ^ (x[i] << 3) ^ c;
    u64 r = 0;
    for (int i = 0; i < N; ++i) r += x[i] + (x[i] > c ? 1 : 0);
    return r;
}

ISA_EXPORT u64 isa_vec_float(u64 a, u64 b, u64 c) {
    float x[N], y[N];
    for (int i = 0; i < N; ++i) {
        x[i] = (float)finite(a + (u64)i * 977);
        y[i] = (float)finite(b ^ ((u64)i * 131));
    }
    for (int i = 0; i < N; ++i) x[i] = x[i] * 0.5f + y[i] * (float)(i64)(c & 7);
    for (int i = 0; i < N; ++i) y[i] = x[i] > y[i] ? x[i] : y[i] - 1.0f;
    i32 iv[N];
    for (int i = 0; i < N; ++i) iv[i] = (i32)y[i];
    u64 r = 0;
    for (int i = 0; i < N; ++i) r += (u64)(i64)iv[i];
    for (int i = 0; i < N; ++i) r ^= float_bits(x[i]) << (i & 31);
    return r;
}

ISA_EXPORT u64 isa_vec_double(u64 a, u64 b, u64 c) {
    double x[N];
    for (int i = 0; i < N; ++i) x[i] = finite(a * (u64)(i + 3)) * finite(b + (u64)i);
    for (int i = 0; i < N; ++i) x[i] = x[i] < 0 ? -x[i] : x[i] / 3.0;
    u64 r = c;
    for (int i = 0; i < N; ++i) r ^= double_bits(x[i]) + (u64)i;
    return r;
}

// Interleaved data: LD2/LD3/LD4-style structure loads when vectorized.
ISA_EXPORT u64 isa_vec_rgba(u64 a, u64 b, u64 c) {
    u8 px[N * 4];
    for (int i = 0; i < N * 4; ++i) px[i] = (u8)(a * (u64)i + b);
    u8 gray[N];
    for (int i = 0; i < N; ++i)
        gray[i] = (u8)((px[4 * i] * 77u + px[4 * i + 1] * 150u + px[4 * i + 2] * 29u) >> 8);
    for (int i = 0; i < N; ++i) {
        u8 t = px[4 * i];
        px[4 * i] = px[4 * i + 2];
        px[4 * i + 2] = t;
        px[4 * i + 3] = (u8)c;
    }
    u64 r = 0;
    for (int i = 0; i < N; ++i) r = r * 31 + gray[i];
    for (int i = 0; i < N * 4; ++i) r += (u64)px[i] << (i & 15);
    return r;
}

ISA_EXPORT u64 isa_vec_widen(u64 a, u64 b, u64 c) {
    u8 x[N];
    i16 y[N];
    fill_u8(x, a ^ c);
    for (int i = 0; i < N; ++i) y[i] = (i16)((i16)x[i] * 9 - 1000);
    i32 z[N];
    for (int i = 0; i < N; ++i) z[i] = (i32)y[i] * (i32)(i16)b;
    u8 back[N];
    for (int i = 0; i < N; ++i) back[i] = (u8)(z[i] >> 4);
    u16 sat[N];
    for (int i = 0; i < N; ++i) {
        i32 v = z[i] >> 2;
        sat[i] = (u16)(v < 0 ? 0 : v > 65535 ? 65535 : v);
    }
    u64 r = 0;
    for (int i = 0; i < N; ++i) r = r * 33 + back[i] + sat[i];
    return r;
}

ISA_EXPORT u64 isa_memset_copy(u64 a, u64 b, u64 c) {
    u64 src[24], dst[24];
    for (int i = 0; i < 24; ++i) src[i] = a + (u64)i * b;
    __builtin_memset(dst, (int)(c & 0xff), sizeof(dst));
    unsigned n = (unsigned)(c % 24);
    for (unsigned i = 0; i < n; ++i) dst[i] = src[i];
    u64 r = 0;
    for (int i = 0; i < 24; ++i) r = r * 3 + dst[i];
    return r;
}

// A longer workload for the speed comparison between the engines (not in
// ISA_TESTS): a mix of multiplies, shifts, loads and stores.
ISA_EXPORT u64 isa_bench(u64 iterations, u64 seed, u64 c) {
    u32 table[64];
    for (int i = 0; i < 64; ++i) table[i] = (u32)(seed * (u64)(i + 1) + c);
    u64 h = seed;
    for (u64 i = 0; i < iterations; ++i) {
        h = h * 6364136223846793005ull + 1442695040888963407ull;
        table[i & 63] ^= (u32)(h >> 33);
        h ^= table[(i * 7) & 63];
    }
    return h;
}

// Calls to what LLVM's machine outliner makes: functions outside the AAPCS64
// that return flags (`cmp`, `ret`, and a `b.cond` after the call: Flutter's
// stack check), read the caller's flags, or set a register the caller keeps.
#if defined(__aarch64__)
__asm__(".text\n"
        ".p2align 2\n"
        "isa_outlined_cmp:\n"
        "    cmp x0, x1\n"
        "    ret\n"
        "isa_outlined_csel:\n"
        "    csel x0, x2, x3, lo\n"
        "    ret\n"
        "isa_outlined_x19:\n"
        "    add x19, x0, x2\n"
        "    ret\n");
ISA_EXPORT u64 isa_outlined(u64 a, u64 b, u64 c) {
    register u64 x0 __asm__("x0") = a;
    register u64 x1 __asm__("x1") = b;
    register u64 x2 __asm__("x2") = c;
    register u64 x3 __asm__("x3") = a ^ c;
    __asm__ volatile("stp x19, x30, [sp, #-16]!\n"
                     "bl isa_outlined_cmp\n"
                     "cset x4, lo\n"
                     "cmp x2, x0\n"
                     "bl isa_outlined_csel\n"
                     "bl isa_outlined_x19\n"
                     "add x0, x19, x4, lsl #1\n"
                     "ldp x19, x30, [sp], #16\n"
                     : "+r"(x0)
                     : "r"(x1), "r"(x2), "r"(x3)
                     : "x4", "x30", "cc", "memory");
    return x0;
}
#else
ISA_EXPORT u64 isa_outlined(u64 a, u64 b, u64 c) {
    const u64 below = a < b;
    return (c < a ? c : a ^ c) + c + (below << 1);
}
#endif

// The list the test walks (name, function).
#define ISA_TESTS(X)       \
    X(isa_arith)           \
    X(isa_arith32)         \
    X(isa_divide)          \
    X(isa_mulhi)           \
    X(isa_bits)            \
    X(isa_bitrev)          \
    X(isa_select)          \
    X(isa_carry)           \
    X(isa_overflow)        \
    X(isa_switch)          \
    X(isa_loop)            \
    X(isa_memory)          \
    X(isa_struct)          \
    X(isa_calls)           \
    X(isa_outlined)        \
    X(isa_recursion)       \
    X(isa_atomics)         \
    X(isa_atomic128)       \
    X(isa_double)          \
    X(isa_float)           \
    X(isa_convert)         \
    X(isa_fcompare)        \
    X(isa_half)            \
    X(isa_vec_bytes)       \
    X(isa_vec_i16)         \
    X(isa_vec_i32)         \
    X(isa_vec_i64)         \
    X(isa_vec_float)       \
    X(isa_vec_double)      \
    X(isa_vec_rgba)        \
    X(isa_vec_widen)       \
    X(isa_memset_copy)
