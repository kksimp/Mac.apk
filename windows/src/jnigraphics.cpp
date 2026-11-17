// jnigraphics.cpp -- see jnigraphics.h.
//
// The NDK's bitmap API lets native code read and write a Bitmap's pixels
// directly. Win.apk's android.graphics.Bitmap (runtime/java) is upstream's, so
// the implementation is upstream's as well: the libjnigraphics section of
// runtime/translator/src/bionic_shim.cpp, cut out at build time
// (cmake/extract_bitmap.cmake) and compiled unchanged. Its own comment has the
// details. In short, a Bitmap's pixels are a Java int[] of 0xAARRGGBB words
// whatever its Config, so getInfo reports RGBA_8888 with stride = width * 4;
// lockPixels hands the guest a buffer of its own in a device's byte order
// (R, G, B, A, premultiplied for a premultiplied bitmap), and unlockPixels
// merges the guest's writes back into the int[].
//
// What this file adds is the boundary: System V entry points, and the guest's
// JNIEnv swapped for the host one it wraps (jobject handles are HotSpot's on
// both sides). AndroidBitmapInfo has the same layout on both sides, so it is
// filled in place:
//
//   AndroidBitmapInfo   Android x86_64       Windows x64
//   width                0  uint32_t          0  uint32_t
//   height               4  uint32_t          4  uint32_t
//   stride               8  uint32_t          8  uint32_t
//   format              12  int32_t          12  int32_t
//   flags               16  uint32_t         16  uint32_t
//   sizeof              20                   20

#include "winapk/jnigraphics.h"

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

#include <jni.h>

#include "bitmap_upstream.inc"

#include "winapk/guest_abi.h"
#include "winapk/guest_jni.h"

namespace winapk {

namespace {

static_assert(sizeof(MacapkAndroidBitmapInfo) == 20 && offsetof(MacapkAndroidBitmapInfo, format) == 12 &&
                  offsetof(MacapkAndroidBitmapInfo, flags) == 16,
              "AndroidBitmapInfo (android/bitmap.h), as drawn above");

JNIEnv* host_env(JNIEnv* guest) { return guest ? host_env_of(guest) : nullptr; }

WINAPK_GUEST_ABI int g_AndroidBitmap_getInfo(JNIEnv* env, jobject bitmap, MacapkAndroidBitmapInfo* info) {
    return __macapk_arm64_AndroidBitmap_getInfo(host_env(env), bitmap, info);
}
WINAPK_GUEST_ABI int g_AndroidBitmap_lockPixels(JNIEnv* env, jobject bitmap, void** pixels) {
    return __macapk_arm64_AndroidBitmap_lockPixels(host_env(env), bitmap, pixels);
}
WINAPK_GUEST_ABI int g_AndroidBitmap_unlockPixels(JNIEnv* env, jobject bitmap) {
    return __macapk_arm64_AndroidBitmap_unlockPixels(host_env(env), bitmap);
}

}  // namespace

void* jnigraphics_resolve(const char* name) {
    if (std::strcmp(name, "AndroidBitmap_getInfo") == 0) return reinterpret_cast<void*>(&g_AndroidBitmap_getInfo);
    if (std::strcmp(name, "AndroidBitmap_lockPixels") == 0) {
        return reinterpret_cast<void*>(&g_AndroidBitmap_lockPixels);
    }
    if (std::strcmp(name, "AndroidBitmap_unlockPixels") == 0) {
        return reinterpret_cast<void*>(&g_AndroidBitmap_unlockPixels);
    }
    return nullptr;
}

}  // namespace winapk
