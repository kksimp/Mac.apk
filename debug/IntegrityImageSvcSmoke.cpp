// IntegrityImageSvcSmoke.cpp
//
// <libintegrityimage.dylib> <libintegrityimage.so> <libplainimage.dylib>
//
// What the loader did to the `svc #0` words of two images, read back from the
// loaded guest code. The integrity image carries a marker-delimited region
// whose bytes hold two `svc #0` words that are hashed, never executed, plus one
// ordinary executable `svc #0`; the plain image carries only the ordinary one.
//
// MACAPK_SVC_BRK_SKIP_INTEGRITY selects the loader's behaviour:
//   unset    the image proves it checks its own code: every `svc #0` in it is
//            left as it is
//   regions  only the marker-delimited regions are left as they are
//   0        the old behaviour: every `svc #0` is rewritten
// An image without integrity markers is rewritten in all three, and the
// ordinary syscall answers in all three too: left alone it goes the way any
// un-rewritten `svc #0` goes (the SIGSYS path), rewritten it goes the BRK way.
#include <dlfcn.h>
#include <unistd.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

static const uint32_t kSvc0 = 0xd4000001;        // svc #0
static const uint32_t kSvcBrk = 0xd429a820;      // brk #0x4d41, what the loader rewrites it to
static const uint32_t kMarkStart = 0xFF77F6F1;   // F1 F6 77 FF
static const uint32_t kMarkEnd = 0xF077F6F1;     // F1 F6 77 F0
static int passed = 0, failed = 0;

static void check(const char* what, bool ok, uint32_t actual, uint32_t expected) {
    if (ok) { passed++; printf("PASS  %s\n", what); }
    else { failed++; printf("FAIL  %s: got 0x%08x, expected 0x%08x\n", what, actual, expected); }
}

// The check the fixture image runs over its region: CRC32, initial value
// 0x20190512, reflected polynomial 0xEDB88320, no final XOR.
static uint32_t regionCrc(const uint8_t* p, size_t n) {
    uint32_t crc = 0x20190512u;
    for (size_t i = 0; i < n; i++) {
        crc ^= p[i];
        for (int b = 0; b < 8; b++) {
            crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
        }
    }
    return crc;
}

// The CRC of the region as the file has it, before anything is loaded.
static bool expectedCrc(const char* path, uint32_t* out, size_t* bytes) {
    FILE* f = fopen(path, "rb");
    if (!f) return false;
    std::vector<uint8_t> file;
    uint8_t buf[65536];
    size_t n;
    while ((n = fread(buf, 1, sizeof buf, f)) > 0) file.insert(file.end(), buf, buf + n);
    fclose(f);
    for (size_t i = 0; i + 4 <= file.size(); i += 4) {
        uint32_t w;
        memcpy(&w, &file[i], 4);
        if (w != kMarkStart) continue;
        for (size_t j = i + 4; j + 4 <= file.size() && j < i + 4 + 0x1000; j += 4) {
            memcpy(&w, &file[j], 4);
            if (w != kMarkEnd) continue;
            *bytes = j - (i + 4);
            *out = regionCrc(&file[i + 4], *bytes);
            return true;
        }
    }
    return false;
}

static void* load(const char* path) {
    void* h = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!h) { printf("FAIL  dlopen(%s): %s\n", path, dlerror()); exit(2); }
    return h;
}

int main(int argc, char** argv) {
    if (argc != 4) { fprintf(stderr, "usage: %s <integrity.dylib> <integrity.so> <plain.dylib>\n", argv[0]); return 2; }
    const char* mode = getenv("MACAPK_SVC_BRK_SKIP_INTEGRITY");
    mode = (mode && *mode) ? mode : "(default)";
    const bool skipAll = !strcmp(mode, "(default)");
    const bool skipRegions = !strcmp(mode, "regions");
    const bool skipNone = !strcmp(mode, "0");
    if (!skipAll && !skipRegions && !skipNone) { fprintf(stderr, "unknown MACAPK_SVC_BRK_SKIP_INTEGRITY=%s\n", mode); return 2; }
    printf("== MACAPK_SVC_BRK_SKIP_INTEGRITY=%s\n", mode);

    uint32_t expected = 0;
    size_t bytes = 0;
    if (!expectedCrc(argv[2], &expected, &bytes)) { printf("FAIL  no integrity region found in %s\n", argv[2]); return 2; }
    printf("   the region as shipped: %zu bytes, crc 0x%08x\n", bytes, expected);

    void* integrity = load(argv[1]);
    void* plain = load(argv[3]);
    auto probe = (uint32_t (*)())dlsym(integrity, "integrity_probe");
    auto regionWord = (uint32_t (*)(uint32_t))dlsym(integrity, "integrity_region_word");
    auto integrityPlainWord = (uint32_t (*)())dlsym(integrity, "integrity_plain_svc_word");
    auto plainWord = (uint32_t (*)())dlsym(plain, "plain_svc_word");
    auto plainSyscall = (long (*)())dlsym(integrity, "integrity_plain_syscall");
    if (!probe || !regionWord || !integrityPlainWord || !plainWord || !plainSyscall) {
        printf("FAIL  exports missing: %p %p %p %p %p\n", (void*)probe, (void*)regionWord,
               (void*)integrityPlainWord, (void*)plainWord, (void*)plainSyscall);
        return 2;
    }

    // The region, as the guest code sees itself now.
    const bool regionIntact = !skipNone;       // default and regions: hashed bytes are left alone
    const bool imageSpared = skipAll;          // default: every `svc #0` of this image is left alone
    uint32_t crc = probe();
    check(regionIntact ? "the self-checked region still hashes to what the file shipped"
                       : "the rewrite changes the self-checked region: its hash no longer matches",
          regionIntact ? crc == expected : crc != expected, crc, expected);
    const uint32_t wantInRegion = regionIntact ? kSvc0 : kSvcBrk;
    const int svcWords[2] = {1, 3};           // the two body words that hold `svc #0`
    for (int i : svcWords) {
        uint32_t w = regionWord(i);
        check("a hashed `svc #0` word in the region", w == wantInRegion, w, wantInRegion);
    }
    check("a hashed word that is not an `svc #0` is left alone", regionWord(0) == 0xd2800020, regionWord(0), 0xd2800020);

    // The ordinary, executable `svc #0` of the same image, outside the region.
    const uint32_t wantInImage = imageSpared ? kSvc0 : kSvcBrk;
    uint32_t w = integrityPlainWord();
    check("the executable `svc #0` outside the region of the self-checking image",
          w == wantInImage, w, wantInImage);

    // An image that does not check its own code is rewritten as before.
    w = plainWord();
    check("the executable `svc #0` of an image with no integrity region", w == kSvcBrk, w, kSvcBrk);

    // And the syscall itself still answers, on either path.
    long pid = plainSyscall();
    check("getpid() through the ordinary `svc #0`", pid == (long)getpid(), (uint32_t)pid, (uint32_t)getpid());

    printf("%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
