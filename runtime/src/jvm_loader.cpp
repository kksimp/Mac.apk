// jvm_loader.cpp -- spin up HotSpot in-process.
//
// Direct call to JNI_CreateJavaVM via the libjvm we link against (see
// CMakeLists.txt). No fork/exec dance, no separate java(1) -- everything
// stays in one process so the Cocoa main loop and the JVM share the
// same main thread.

#include "macapk/jvm_loader.h"
#include "macapk/jit_wx_guard.h"

#include <CommonCrypto/CommonDigest.h>
#include <dlfcn.h>
#include <sys/stat.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <mach/mach.h>
#include <mach/mach_vm.h>   // mach_vm_region: find a free guest arena band below 16 GiB
#include <sys/mman.h>

namespace macapk {

namespace {

std::string JoinClasspath(const std::vector<std::string>& entries) {
    std::string out = "-Djava.class.path=";
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i) out.push_back(':');
        out.append(entries[i]);
    }
    return out;
}

// The collector the option list selects: every -XX:+Use<X>GC switches <X> on
// and a later -XX:-Use<X>GC switches it off again, in order, which is how
// MACAPK_JVM_OPTS="-XX:-UseSerialGC -XX:+UseShenandoahGC" replaces our default.
// "" when not exactly one survives (the JVM then picks ergonomically or refuses).
std::string SelectedCollector(const std::vector<std::string>& opts) {
    std::vector<std::string> on;
    for (const auto& o : opts) {
        if (o.size() <= 10 || o.compare(0, 4, "-XX:") != 0 || o.compare(5, 3, "Use") != 0 ||
            o.compare(o.size() - 2, 2, "GC") != 0 || (o[4] != '+' && o[4] != '-')) continue;
        const std::string name = o.substr(8, o.size() - 10);
        // Collectors only: -XX:+UseMaximumCompactionOnSystemGC also ends in GC.
        if (name != "Serial" && name != "Parallel" && name != "G1" && name != "Shenandoah" &&
            name != "Z" && name != "Epsilon") continue;
        for (size_t i = 0; i < on.size(); ++i) if (on[i] == name) { on.erase(on.begin() + i); break; }
        if (o[4] == '+') on.push_back(name);
    }
    return on.size() == 1 ? on[0] : std::string();
}

std::string SystemProperty(JNIEnv* env, const char* key) {
    std::string out;
    jclass sys = env->FindClass("java/lang/System");
    jmethodID get = sys ? env->GetStaticMethodID(sys, "getProperty",
                                                 "(Ljava/lang/String;)Ljava/lang/String;") : nullptr;
    jstring k = get ? env->NewStringUTF(key) : nullptr;
    jstring v = k ? static_cast<jstring>(env->CallStaticObjectMethod(sys, get, k)) : nullptr;
    if (v && !env->ExceptionCheck()) {
        if (const char* c = env->GetStringUTFChars(v, nullptr)) {
            out = c;
            env->ReleaseStringUTFChars(v, c);
        }
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (v) env->DeleteLocalRef(v);
    if (k) env->DeleteLocalRef(k);
    if (sys) env->DeleteLocalRef(sys);
    return out;
}

// STARTUP TRIPWIRE (docs/gclocker-critical-release-design_2026-09-25.md 5.3):
// one line per run naming the JNI critical-release semantics this JVM has, so a
// log says which side of the OpenTTD "LZMA error 9" bug it ran on even though
// the JDK itself cannot be env-gated. A guest that stores through a
// GetPrimitiveArrayCritical pointer AFTER releasing it (OpenTTD liblzma
// nativeRead) loses the store on a GCLocker collector up to JDK 24, whose
// last-out release runs the waiting GC (jdk21u gcLocker.cpp jni_unlock ->
// collect(GCCause::_gc_locker)); from JDK 25 the release only decrements
// (JDK-8192647) and the GC request waits in GCLocker::block, as on ART. G1 left
// GCLocker for region pinning in JDK 22 (JEP 423); Shenandoah pins regions and
// ZGC counts criticals, neither collects at release. java.home is printed too:
// an installed app runs the STAGED runtime's jre, not the dev link target.
// JNIEnv::GetVersion is no key: JDK 24 reports JNI_VERSION_24 and still
// collects at release.
void LogCriticalReleaseSemantics(JNIEnv* env, const std::string& gc) {
    const std::string spec = SystemProperty(env, "java.specification.version");
    const int feature = std::atoi(spec.c_str());
    const bool gclocker = gc == "Serial" || gc == "Parallel" || (gc == "G1" && feature < 22);
    const char* sem =
        (gclocker && feature > 0 && feature <= 24)
            ? "COLLECT-AT-RELEASE (GCLocker, JDK<=24): guest stores after ReleasePrimitiveArrayCritical can be lost"
        : gclocker && feature >= 25 ? "decrement-only (JDK-8192647)"
        : (gc == "G1" || gc == "Shenandoah") ? "region-pinning (no GCLocker)"
        : gc == "Z" ? "ZGC critical count (no collect-at-release)"
        : "UNKNOWN (collector or JDK version not determined)";
    std::fprintf(stderr, "[jvm] %s (spec %s) home=%s gc=%s jni-critical-release=%s\n",
                 SystemProperty(env, "java.vm.version").c_str(), spec.c_str(),
                 SystemProperty(env, "java.home").c_str(),
                 gc.empty() ? "ergonomic" : gc.c_str(), sem);
}

// Android resolves the PBE crypto family to BC (Conscrypt has none of it); the
// host's SunJCE would answer first with different semantics (Pocket Casts died on
// "Salt must be 8 bytes long", issue #7). android.security.MacApkProviderPreference
// sets the JDK's jdk.security.provider.preferred property, which is read ONCE when
// the first ProviderList is built -- so it has to run here, before any Java of ours
// can touch a provider. Non-fatal: a JVM without the framework jar keeps the JDK order.
void InstallAndroidProviderPreference(JNIEnv* env) {
    jclass c = env->FindClass("android/security/MacApkProviderPreference");
    jmethodID m = c ? env->GetStaticMethodID(c, "install", "()V") : nullptr;
    if (m) env->CallStaticVoidMethod(c, m);
    if (env->ExceptionCheck()) {
        std::fprintf(stderr, "[jvm] provider preference not installed (PBE stays on SunJCE):\n");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (c) env->DeleteLocalRef(c);
}

// ---- JDK selection + AOT cache (docs/jdk25-move-and-aot-cache_2026-09-26.md) ----
//
// THE JDK IS CHOSEN AT BUILD TIME. This binary LINKS libjvm (runtime/CMakeLists.txt),
// so the JDK is whatever runtime/scripts/build.sh resolved from the ONE setting,
// MACAPK_JDK (debug/jdk25_select.sh). Nothing here can switch it. What this code does
// is read which JDK it actually got -- the libjvm dyld bound, found with dladdr, and
// the release file two levels above it -- because two things depend on that answer
// BEFORE JNI_CreateJavaVM, when no Java property can be asked yet:
//   1. MACAPK_JDK naming a different feature than the linked one is a stale build or a
//      mixed bundle, and deserves one loud line instead of a silent run on the wrong JVM.
//   2. -XX:AOTCache only exists from JDK 24 (JEP 483), and vm_args.ignoreUnrecognized
//      is JNI_FALSE, so passing it to a JDK 21 libjvm makes JNI_CreateJavaVM return
//      JNI_EINVAL -- a hard startup abort (measured; debug/jdk25_gate.sh row AOT21).
// Both only run when MACAPK_JDK or an AOT variable is set, so the default path is the
// pre-change one exactly.
struct LinkedJdk {
    std::string home;      // <home> with lib/server/libjvm.dylib under it
    std::string version;   // JAVA_VERSION from <home>/release, e.g. "25.0.2"
    int feature = 0;       // 25; 0 = unknown
};

LinkedJdk ProbeLinkedJdk() {
    LinkedJdk j;
    Dl_info info{};
    if (!dladdr(reinterpret_cast<const void*>(&JNI_CreateJavaVM), &info) || !info.dli_fname)
        return j;
    std::string p = info.dli_fname;  // <home>/lib/server/libjvm.dylib
    for (int up = 0; up < 3; ++up) {
        const size_t s = p.rfind('/');
        if (s == std::string::npos) return j;
        p.erase(s);
    }
    j.home = p;
    std::ifstream rel(p + "/release");
    std::string line;
    static const char kKey[] = "JAVA_VERSION=\"";
    while (std::getline(rel, line)) {
        if (line.compare(0, sizeof(kKey) - 1, kKey) != 0) continue;
        const size_t b = sizeof(kKey) - 1, e = line.find('"', b);
        j.version = line.substr(b, e == std::string::npos ? std::string::npos : e - b);
        break;
    }
    j.feature = std::atoi(j.version.c_str());
    return j;
}

bool EnvOn(const char* name) {
    const char* v = std::getenv(name);
    return v && v[0] && std::strcmp(v, "0") != 0;
}

uint64_t Fnv1a(uint64_t h, const std::string& s) {
    for (unsigned char c : s) { h ^= c; h *= 1099511628211ULL; }
    h ^= 0xff; h *= 1099511628211ULL;  // field separator, so "ab"+"c" != "a"+"bc"
    return h;
}

// "<size>:<mtime ns>" of a file, "" when it cannot be stat'd. HotSpot validates each
// classpath jar (and the runtime image) by exactly these when it maps an AOT cache,
// so they must be in our key: a key without them would keep serving a cache HotSpot
// then REFUSES after every rebuild, because build.sh restamps every jar entry even
// on a no-op build. That is also why the key does not use build-id.txt's JAVA_ID:
// identical JAVA_ID across a no-op rebuild is its whole point, and HotSpot would
// still reject the cache on the new mtimes.
std::string StatStamp(const std::string& path) {
    struct stat st{};
    if (::stat(path.c_str(), &st) != 0) return std::string();
    char buf[64];
    std::snprintf(buf, sizeof buf, "%lld:%lld", static_cast<long long>(st.st_size),
                  static_cast<long long>(st.st_mtimespec.tv_sec) * 1000000000LL +
                      st.st_mtimespec.tv_nsec);
    return buf;
}

// SHA-256 of a patch-module directory, in the exact form scripts/stage_jre.sh's
// bake step writes into <jre>/lib/macapk-baked-patches: for every regular file,
// sorted by relative path (byte order, = LC_ALL=C sort), feed
// "<relpath>\n<size>\n" then the file's bytes. "" on any read error.
std::string PatchDirDigest(const std::string& dir) {
    namespace fs = std::filesystem;
    std::error_code ec;
    std::vector<std::string> rel;
    for (fs::recursive_directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec)) {
        if (it->is_regular_file(ec)) rel.push_back(fs::relative(it->path(), dir, ec).generic_string());
    }
    if (ec || rel.empty()) return std::string();
    std::sort(rel.begin(), rel.end());
    CC_SHA256_CTX ctx;
    CC_SHA256_Init(&ctx);
    std::vector<char> buf(1 << 16);
    for (const std::string& r : rel) {
        const std::string full = dir + "/" + r;
        std::ifstream in(full, std::ios::binary);
        if (!in) return std::string();
        const std::string head = r + "\n" + std::to_string(fs::file_size(full, ec)) + "\n";
        CC_SHA256_Update(&ctx, head.data(), static_cast<CC_LONG>(head.size()));
        while (in.read(buf.data(), static_cast<std::streamsize>(buf.size())) || in.gcount() > 0)
            CC_SHA256_Update(&ctx, buf.data(), static_cast<CC_LONG>(in.gcount()));
    }
    unsigned char md[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256_Final(md, &ctx);
    std::string hex;
    char two[3];
    for (unsigned char b : md) { std::snprintf(two, sizeof two, "%02x", b); hex += two; }
    return hex;
}

// <module> <sha256> pairs the runtime image says it already carries.
std::vector<std::pair<std::string, std::string>> BakedPatches(const std::string& home) {
    std::vector<std::pair<std::string, std::string>> out;
    std::ifstream in(home + "/lib/macapk-baked-patches");
    std::string mod, dig;
    while (in >> mod) {
        if (mod[0] == '#') { std::getline(in, dig); continue; }
        if (in >> dig) out.emplace_back(mod, dig);
    }
    return out;
}

// Options that change per LAUNCH (app paths, window policy, crash-dump dir, logging)
// and cannot affect whether a cache maps. Everything else -- GC, heap, verification,
// module flags, MACAPK_JVM_OPTS additions -- is in the key, because HotSpot refuses a
// cache recorded under a different collector or verification setting (measured: G1
// at use against a Serial recording maps 0 classes; so does verification ON).
bool PerLaunchOption(const std::string& o) {
    if (o.compare(0, 18, "-Djava.class.path=") == 0) return false;
    return o.compare(0, 2, "-D") == 0 || o.compare(0, 14, "-XX:ErrorFile=") == 0 ||
           o.compare(0, 6, "-Xlog:") == 0;
}

struct AotState {
    bool active = false;     // an AOT option was added to this run's vector
    bool recording = false;  // ...and it was the training (record) one
    std::string path;        // where the cache for this exact configuration lives
};

// MACAPK_AOT_CACHE=1  use the AOT cache for this configuration if it exists.
// MACAPK_AOT_RECORD=<file>  (debug/jdk25_aot.sh train only) record a training run and
//                    let HotSpot create the cache into <file> at DestroyJavaVM
//                    (JEP 514 -XX:AOTCacheOutput; the runtime's own exits skip
//                    DestroyJavaVM, which is why training is a separate process).
// Default OFF until the live A/B lands; unset/0 leaves the option vector untouched.
AotState ApplyJdkSelectionAndAotCache(std::vector<std::string>& opts) {
    AotState st;
    const char* want = std::getenv("MACAPK_JDK");
    const char* record = std::getenv("MACAPK_AOT_RECORD");
    const bool use = EnvOn("MACAPK_AOT_CACHE");
    const bool rec = record && record[0];
    if (!(want && want[0]) && !use && !rec) return st;

    const LinkedJdk jdk = ProbeLinkedJdk();
    if (want && want[0] && jdk.feature > 0 && std::atoi(want) != jdk.feature) {
        std::fprintf(stderr,
            "[jvm] WARN: MACAPK_JDK=%s but this binary links JDK %s (%s). The JDK is chosen when "
            "the runtime is BUILT (MACAPK_JDK=%s runtime/scripts/build.sh); running on %s.\n",
            want, jdk.version.c_str(), jdk.home.c_str(), want, jdk.version.c_str());
    }
    if (!use && !rec) return st;
    const char* mode = rec ? "record" : "use";
    if (jdk.feature < 25) {
        std::fprintf(stderr,
            "[jvm] aot-cache: OFF (%s) -- linked JDK %s has no AOT cache; it needs JDK 25 "
            "(JEP 483 + 514/515). Not passing -XX:AOTCache, which this libjvm would reject "
            "with JNI_EINVAL.\n", mode, jdk.version.empty() ? "unknown" : jdk.version.c_str());
        return st;
    }

    // --patch-module and CDS/AOT are mutually exclusive in HotSpot: ANY patched module
    // disables the archive ("CDS is disabled when the --patch-module option is
    // specified", and for java.base "...when java.base module is patched"; measured on
    // 21.0.11 and 25.0.2). main.cpp patches java.base and jdk.unsupported, so every
    // launch today runs with NO class-data sharing at all. A runtime image that already
    // carries those exact classes (stage_jre.sh MACAPK_JRE_BAKE_PATCHES) lets us drop
    // the flags; the digest check means a patch rebuilt after staging keeps its flag.
    const auto baked = BakedPatches(jdk.home);
    std::vector<std::string> kept;
    std::string blocked;
    for (const std::string& o : opts) {
        static const char kPm[] = "--patch-module=";
        if (o.compare(0, sizeof(kPm) - 1, kPm) != 0) { kept.push_back(o); continue; }
        const std::string spec = o.substr(sizeof(kPm) - 1);  // <module>=<dir>
        const size_t eq = spec.find('=');
        const std::string mod = spec.substr(0, eq);
        const std::string dir = eq == std::string::npos ? std::string() : spec.substr(eq + 1);
        const std::string dig = PatchDirDigest(dir);
        bool ok = false;
        for (const auto& b : baked) ok = ok || (b.first == mod && !dig.empty() && b.second == dig);
        if (ok) {
            std::fprintf(stderr, "[jvm] aot-cache: %s patch is baked into %s (sha256 %.16s) -- "
                         "dropping %s so the cache can map\n", mod.c_str(), jdk.home.c_str(),
                         dig.c_str(), o.c_str());
        } else {
            blocked += (blocked.empty() ? "" : ", ") + mod;
            kept.push_back(o);
        }
    }
    if (!blocked.empty()) {
        std::fprintf(stderr,
            "[jvm] aot-cache: OFF (%s) -- --patch-module for %s is on the command line and HotSpot "
            "disables CDS/AOT whenever a module is patched; bake the patches into the runtime image "
            "(scripts/stage_jre.sh MACAPK_JRE_BAKE_PATCHES=<lib dir>) so the flags can go.\n",
            mode, blocked.c_str());
        return st;
    }
    opts.swap(kept);

    // Key = everything HotSpot validates when it maps the cache, and nothing per-launch.
    uint64_t h = 1469598103934665603ULL;
    h = Fnv1a(h, jdk.version);
    h = Fnv1a(h, jdk.home);
    h = Fnv1a(h, StatStamp(jdk.home + "/lib/modules"));
    for (const std::string& o : opts) {
        if (PerLaunchOption(o)) continue;
        h = Fnv1a(h, o);
        if (o.compare(0, 18, "-Djava.class.path=") == 0) {
            const std::string cp = o.substr(18);
            size_t b = 0;
            while (b <= cp.size()) {
                const size_t e = std::min(cp.find(':', b), cp.size());
                h = Fnv1a(h, StatStamp(cp.substr(b, e - b)));
                b = e + 1;
            }
        }
    }
    std::string dir;
    if (const char* d = std::getenv("MACAPK_AOT_DIR"); d && d[0]) dir = d;
    else if (const char* home = std::getenv("HOME"); home && home[0])
        dir = std::string(home) + "/Library/Caches/Mac.apk/aot";
    if (dir.empty()) {
        std::fprintf(stderr, "[jvm] aot-cache: OFF (%s) -- no $HOME and no MACAPK_AOT_DIR\n", mode);
        return st;
    }
    char name[96];
    std::snprintf(name, sizeof name, "/macapk-jdk%s-%016llx.aot", jdk.version.c_str(),
                  static_cast<unsigned long long>(h));
    st.path = dir + name;

    if (rec) {
        // Two options, appended LAST so the key above is exactly the production key.
        opts.push_back(std::string("-XX:AOTCacheOutput=") + record);
        std::fprintf(stderr, "[jvm] aot-cache: RECORD -> %s (the runtime looks for %s)\n",
                     record, st.path.c_str());
        st.active = st.recording = true;
        return st;
    }
    struct stat sb{};
    if (::stat(st.path.c_str(), &sb) != 0 || sb.st_size == 0) {
        std::fprintf(stderr,
            "[jvm] aot-cache: MISS %s -- no cache for this JDK + classpath + flag set; create one "
            "with debug/jdk25_aot.sh train\n", st.path.c_str());
        return st;
    }
    // AOTMode is left at its default (auto): a cache HotSpot cannot use (a jar it
    // validates changed underneath the key, say) prints an [aot] error and the run
    // continues with normal class loading. Never -XX:AOTMode=on, which aborts instead.
    opts.push_back("-XX:AOTCache=" + st.path);
    std::fprintf(stderr, "[jvm] aot-cache: USING %s (%.1f MB)\n", st.path.c_str(),
                 static_cast<double>(sb.st_size) / (1024.0 * 1024.0));
    st.active = true;
    return st;
}

}  // namespace

void StartJvm(const JvmConfig& cfg, JavaVM** out_vm, JNIEnv** out_env) {
    // We allocate the option strings on the stack (in a vector) and pass
    // pointers to JNI; HotSpot copies them, so they don't need to outlive
    // the call.
    std::vector<std::string> owned_options;
    owned_options.reserve(cfg.extra_options.size() + 4);
    owned_options.push_back(JoinClasspath(cfg.classpath_entries));
    // Reduce HotSpot's signal-handler footprint so Cocoa keeps SIGINT,
    // SIGTERM, SIGQUIT for itself. Required for sane Ctrl-C behavior.
    owned_options.push_back("-Xrs");
    // Required so framework JNI implementations can call System.loadLibrary
    // through the boot ClassLoader. java.library.path defaults to a brew
    // prefix that doesn't exist for us; an empty value is harmless.
    owned_options.push_back("-Djava.library.path=");
    // GC choice: Serial GC. Pre-2025 we ran G1 for low-pause behaviour,
    // but Apple-Silicon HotSpot 21 + our JNI shim has been hitting
    // SIGSEGV in G1ParCopyClosure::do_oop_work during parallel root
    // processing. Root cause is suspected to be a JNI handle block
    // overflow or a bogus oop being deposited in a thread-local handle
    // chain by lifted ARM32 code; whatever the trigger, Serial GC's
    // single-threaded scanner walks the same roots without exploding.
    // The pause penalty is acceptable for the games we support
    // (allocation rates are modest now that AsyncTask network code
    // throws UnknownHostException early). Tiered compilation stays.
    owned_options.push_back("-XX:+UseSerialGC");
    owned_options.push_back("-XX:+TieredCompilation");
    // Heap sizing: low FLOORS, held CEILINGS. Xmx 1 GiB + young ceiling
    // 512 MiB, but the JVM COMMITS only Xms=128 MiB / NewSize=64 MiB up
    // front and grows toward the maxima on demand (Serial GC), then lets
    // MaxHeapFreeRatio uncommit it back after a full GC.
    //
    // HISTORY (the pin this replaced). The four values used to be
    // -Xms1g/-Xmx1g/-XX:NewSize=512m/-XX:MaxNewSize=512m: a young gen
    // PINNED at 512 MiB so the first minor GC was pushed well past
    // first-frame. That was a workaround, not a size decision. Lifted
    // ARM32 libgame code (JNI_OnLoad / static initializers, or a JNI
    // shim handle resolution returning a stale pointer) deposited a
    // bogus oop in the global-ref OopStorage; when SerialGC scanned
    // those roots during the first young GC it hit the bad oop and
    // SIGSEGV'd in RootScanClosure::do_oop ~58 s in, before any frame
    // shipped. Pinning 512 MiB simply delayed the first young GC past
    // the visible boot. The cost was ~1 GiB committed for every app,
    // including native-heavy games whose true Java live set is tens of
    // MiB (measured: MCPE's post-full-GC live set is ~48 MiB + ~28 MiB
    // metaspace -- a ~9:1 waste against the 512 MiB pinned eden).
    //
    // The bad-oop ROOT was fixed in v1.0.523 (the HCR shim was calling
    // DeleteLocalRef on a promoted global -- corrected by consulting
    // GetObjectRefType). v1.0.615 (round-2 RAM reduction) verified the
    // pin is no longer needed: HCR 1.43 forced armeabi-v7a (the ARM32
    // lifter path) with NewSize=64m -- i.e. early/frequent young GCs,
    // the exact historic trigger -- boots, renders past 90 s, and
    // additionally survives repeated forced FULL GCs (which scan the
    // same global JNI-ref OopStorage) AFTER libgame is loaded and
    // running, with zero SIGSEGV / do_oop. So we drop the floors.
    //
    // Generic, not app-specific: Java-heavy guests (Reddit, F-Droid)
    // still grow to the 1 GiB / 512 MiB ceilings on demand; native
    // guests (cocos, Unity, MCPE) stop paying ~240 MiB of resident eden
    // they never touch. The macapk-runtime is an in-process VM on an
    // 8-16 GiB Apple-Silicon Mac; 1 GiB stays a comfortable cap.
    // NOTE: if the ARM32 GC-scan SIGSEGV ever reappears, the correct
    // fix is the JNI-ref bookkeeping (the real layer, see v1.0.523),
    // NOT re-inflating the young gen to hide it.
    owned_options.push_back("-Xms128m");
    owned_options.push_back("-Xmx1g");
    owned_options.push_back("-XX:NewSize=64m");
    owned_options.push_back("-XX:MaxNewSize=512m");
    // Let Serial GC shrink the committed heap after a full GC once the
    // live set drops (default ratios, stated explicitly so the intent
    // to reclaim -- not just cap -- is visible): keep committed heap
    // between 40% and 70% free relative to live.
    owned_options.push_back("-XX:MinHeapFreeRatio=40");
    owned_options.push_back("-XX:MaxHeapFreeRatio=70");
    // Per-thread stack size. CRITICAL for ARM32-lifted code: each lifted
    // function materializes the guest register file as host-stack allocas, so
    // a deep lifted call chain (e.g. stb_image's PNG decode: load ->
    // load_and_postprocess -> load_main -> png_load -> create_png_image ->
    // zlib inflate -> build_huffman, ~15 frames) uses several MiB of HOST
    // stack -- far more than the equivalent native ARM. HotSpot's default
    // thread stack on macOS arm64 is ~512 KiB-1 MiB; a JNI native running such
    // a chain on a background/loader thread (e.g. arc's Pixmap_loadJni decoding
    // a texture off the GL thread) overflows it and the decode silently returns
    // 0 ("Error loading pixmap"). The primordial/main thread (8 MiB) is fine,
    // which is why the failure is thread-dependent. 16 MiB gives ample head
    // room (the decode fits in <8 MiB; the main thread proves it). Generic, not
    // app-specific. Guest pthreads get the same via the pthread_create shim.
    owned_options.push_back("-Xss16m");
    // DNS resolution: previously we forced `jdk.net.hosts.file` at a
    // nonexistent path to make every InetAddress.getAllByName lookup
    // synthesize an UnknownHostException -- the rationale was that
    // games' analytics / ad-config AsyncTasks didn't need real
    // networking and we'd hit historical SIGSEGV inside libnet's
    // Java_java_net_Inet6AddressImpl_lookupAllHostAddr on certain
    // resolver responses.
    //
    // v1.0.105: as of macOS 26 / OpenJDK 21 we no longer see those
    // SIGSEGVs, and F-Droid + every other content-fetching app needs
    // real DNS to function. Removed the redirect. If a game's
    // analytics traffic becomes a problem again, gate the redirect
    // behind an env var (MACAPK_OFFLINE=1) -- never hardcode it.
    // MACAPK_OFFLINE=1 restores the old behavior for users who
    // explicitly want network isolation.
    if (const char* offline = std::getenv("MACAPK_OFFLINE")) {
        if (offline[0] == '1') {
            owned_options.push_back("-Djdk.net.hosts.file=/var/empty/macapk_hosts_does_not_exist");
        }
    }
    // Honour the macOS system proxy / PAC settings (System Settings > Network >
    // Proxies). Without this the JDK ignores them, so guests on corporate-proxy
    // networks cannot reach anything. Android apps get the device proxy from
    // the platform the same way. MACAPK_SYSTEM_PROXIES=0 restores the old
    // direct-connect behaviour.
    if (const char* sp = std::getenv("MACAPK_SYSTEM_PROXIES"); !(sp && sp[0] == '0')) {
        owned_options.push_back("-Djava.net.useSystemProxies=true");
    }
    // We used to also force -Djava.net.preferIPv4Stack=true here. It landed in
    // the same commit as the hosts-file redirect above (same "make networking
    // stop crashing" era) and was never revisited when that redirect was
    // removed in v1.0.105. It does not belong: real Android devices are
    // routinely IPv6-capable (carriers run IPv6-only / 464XLAT networks), and
    // nothing in AOSP forces IPv4-only sockets. The JDK default is false, and
    // the JDK's own networking properties doc states the cost of true: "it will
    // not be possible for the application to communicate with IPv6 only hosts"
    // (docs.oracle.com/en/java/javase/21/docs/api/java.base/java/net/doc-files/
    // net-properties.html). It also hid every IPv6 address from
    // NetworkInterface enumeration. Dropped so guests see the same dual-stack
    // networking Android gives them. If a specific run needs the old behavior,
    // MACAPK_JVM_OPTS="-Djava.net.preferIPv4Stack=true" restores it without a
    // hardcode (see the escape hatch below).
    // Bytecode verification: off. REQUIRED by our DEX->JAR converter
    // (enjarify): enjarify emits bytecode that is intentionally
    // verifier-INVALID but semantically correct -- it has no StackMapTable
    // attributes (which the JVM 7+ verifier requires) and it preserves
    // DEX-legal-but-JVM-unverifiable shapes such as R8's `new T;
    // invokespecial Super.<init>` constructor pattern. Android's Dalvik /
    // ART runtimes never ran a StackMapTable verifier, so this bytecode is
    // well-formed for the platform it was built for -- we're the odd one
    // out. Disabling verification matches Android's behavior and is safe
    // because we're loading code from a single APK we already trust to run.
    //
    // ⚠ USE THE -XX FORM, NOT -Xverify:none. The launcher ALIAS is removed in
    // JDK 27 (JDK-8373481, Fixed, Fix Version 27, resolved 2026-04-28): on the
    // jdk27 branch arguments.cpp keeps only the ":all" / "" and ":remote"
    // arms and the ":none" arm is deleted, so -Xverify:none falls through to
    // is_bad_option(). Because vm_args.ignoreUnrecognized is JNI_FALSE below,
    // that makes JNI_CreateJavaVM return JNI_EINVAL -- a hard startup abort.
    // JDK 27 GA is 2026-09-15.
    //
    // The UNDERLYING VM FLAGS SURVIVE. Both are still
    // product(bool, ..., DIAGNOSTIC) in globals.hpp on the jdk27 branch, and
    // -Xverify:none never did anything except set these two to false. So this
    // is a one-line flag change, NOT a reason to rush gate G8 -- an earlier
    // note in CLAUDE.md claimed JDK 27 was hard-blocked on G8 and that was
    // WRONG; it is corrected there.
    //
    // Verified equivalent on the pinned JDK 21 rather than assumed:
    //   -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote
    //     -XX:-BytecodeVerificationLocal   -> PrintFlagsFinal reports
    //     BytecodeVerificationLocal = false, BytecodeVerificationRemote = false
    //   which is exactly what -Xverify:none produced, minus its deprecation
    //   warning on every launch.
    // UnlockDiagnosticVMOptions only PERMITS diagnostic flags; it changes no
    // behaviour on its own.
    owned_options.push_back("-XX:+UnlockDiagnosticVMOptions");
    owned_options.push_back("-XX:-BytecodeVerificationRemote");
    owned_options.push_back("-XX:-BytecodeVerificationLocal");
    // JEP 472 ("Prepare to Restrict the Use of JNI", JDK 24) makes loading a
    // native library from an unnamed module print a warning under the default
    // --illegal-native-access=warn, and JEP 472 explicitly blesses this flag for
    // embedders using the JNI Invocation API. Harmless on JDK 21 (verified: the
    // flag is accepted, exit 0), silences one stderr line per run on 24/25/26,
    // and is REQUIRED whenever the default flips to deny.
    // ⚠ Our ~900 RegisterNatives entries are NOT affected either way: the
    // native-access check lives only on the automatic-lookup path
    // (NativeLookup::lookup -> ClassLoader.findNative -> ensureNativeAccess),
    // and that lookup is guarded by `if (!method->has_native_function())`, which
    // is already true for anything bound via RegisterNatives. The one warning
    // comes from NativeLoaderShim's System.load.
    owned_options.push_back("--enable-native-access=ALL-UNNAMED");
    // Permit deep reflection into java.io. android.os.ParcelFileDescriptor.getFd()
    // reads java.io.FileDescriptor's private `fd` field reflectively to return the
    // integer fd to callers that take the Java path (Unity's native asset loader
    // reads it via JNI instead, but other guests call getFd() directly). JDK 21
    // strong-encapsulates java.base, so setAccessible(true) needs this opened.
    // Opening a package only permits reflection; it changes no behavior unless
    // code reflects, so it cannot regress non-reflecting guests.
    owned_options.push_back("--add-opens=java.base/java.io=ALL-UNNAMED");
    // Permit deep reflection into java.util, for the SAME reason and with the
    // same (nil) blast radius as the java.io open above. The trigger is AGP's
    // core library desugaring (`coreLibraryDesugaringEnabled true`), which is
    // extremely common in modern APKs: D8 rewrites java.util references to a
    // bundled `j$.util.*` backport, and DesugarCollections' static initializer
    // reflectively grabs java.util.Collections$SynchronizedCollection's private
    // `mutex` field so its synchronizedMap wrappers can share the caller's
    // monitor. Dalvik/ART have no module system, so that setAccessible(true)
    // always succeeds on Android; JDK 21 strong-encapsulates java.base and
    // throws InaccessibleObjectException instead -- which surfaces as an
    // ExceptionInInitializerError inside the guest's Activity CONSTRUCTOR, so
    // the activity never reaches onCreate and the app dies with a blank window.
    // Measured on VLC 3.7.1 (org.videolan.vlc), 2026-07-30:
    //   Caused by: java.lang.reflect.InaccessibleObjectException: Unable to make
    //   field final java.lang.Object java.util.Collections$SynchronizedCollection.mutex
    //   accessible: module java.base does not "opens java.util" to unnamed module
    //     at j$.util.DesugarCollections.<clinit>(SourceFile:104)
    //     at org.videolan.vlc.StartActivity.<init>(StartActivity.kt:93)
    // ONE open is enough: javap of that <clinit> shows exactly four
    // setAccessible(true) sites -- fields `mutex` and `c`, plus a constructor
    // each on Collections$SynchronizedCollection and $SynchronizedList -- and
    // all four targets live in java.util. Its getField/getConstructor helpers
    // swallow NoSuchField/NoSuchMethodException but NOT InaccessibleObject-
    // Exception, which is why the failure is fatal rather than degraded.
    owned_options.push_back("--add-opens=java.base/java.util=ALL-UNNAMED");
    // v1.0.1641: java.lang too. ART has no module system, so an Android app can
    // setAccessible(true) on any java.lang member, and Meta's dextricks does:
    // MultiDexClassLoader.<clinit> reads java.lang.ClassLoader.parent (the
    // v1.0.1623 Reflection overlay made the field VISIBLE; making it ACCESSIBLE
    // needs this open) and rethrows the InaccessibleObjectException as
    // FatalDexError -> System.exit(10) on Instagram's SWPool3 thread. Measured
    // 2026-09-24 on the installed Instagram.app, and in archived logs before it.
    owned_options.push_back("--add-opens=java.base/java.lang=ALL-UNNAMED");
    // Deep reflection into jdk.internal.loader, for exactly one field:
    // NativeLibraries.libraries, the per-ClassLoader map HotSpot walks to bind
    // an unregistered native method. HotSpot walks it in ConcurrentHashMap
    // (path-HASH) order; ART walks its libraries_ SafeMap in path-SORTED order
    // (art runtime/jni/java_vm_ext.cc FindNativeMethodInternal). When two
    // libraries of one loader export the same Java_* symbol the order picks the
    // winner, so dalvik.system.ArtJniLibraryOrder swaps in a sorted map
    // (MACAPK_JNI_ART_LIB_ORDER, default ON). Same nil blast radius as the opens
    // above: it permits reflection and changes nothing for code that does not
    // reflect. docs/jni-native-binding-art-library-order_2026-10-01.md.
    owned_options.push_back("--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED");
    // 2026-09-30: com.macapk.net.AndroidHttpHandlerProvider (runtime/net_spi/)
    // wraps the JDK's OWN http/https URLStreamHandlers to give HttpURLConnection
    // Android's (OkHttp's) rule for a redirected STREAMED request -- follow it as
    // a GET -- where OpenJDK refuses with HttpRetryException and reports a bare
    // 302 (NewPipe: every YouTube VOD). Constructing the built-in handlers needs
    // their packages exported; without these the provider logs once and steps
    // aside (stock JDK behaviour). Gate: debug/hucredirect_gate.sh.
    owned_options.push_back("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED");
    owned_options.push_back("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED");
    if (cfg.verbose) {
        owned_options.push_back("-Xlog:class+load=info");
    }
    for (const auto& opt : cfg.extra_options) {
        owned_options.push_back(opt);
    }
    // MACAPK_JVM_OPTS: space-separated extra JVM options for debugging
    // (e.g. "-Xint", "-XX:TieredStopAtLevel=1", "-XX:CompileCommand=exclude,...").
    // Default-unset -> no change. General-purpose escape hatch for diagnosing
    // JIT/GC/heap issues without rebuilding.
    if (const char* jopts = std::getenv("MACAPK_JVM_OPTS")) {
        std::string s(jopts);
        size_t i = 0;
        while (i < s.size()) {
            while (i < s.size() && s[i] == ' ') ++i;
            size_t j = i;
            while (j < s.size() && s[j] != ' ') ++j;
            if (j > i) owned_options.push_back(s.substr(i, j - i));
            i = j;
        }
    }
    // JDK-selection consistency + the JDK 25 AOT cache. LAST, so the cache key sees
    // the final option vector including MACAPK_JVM_OPTS. No-op unless MACAPK_JDK,
    // MACAPK_AOT_CACHE or MACAPK_AOT_RECORD is set.
    const AotState aot = ApplyJdkSelectionAndAotCache(owned_options);

    std::vector<JavaVMOption> jvm_options(owned_options.size());
    for (size_t i = 0; i < owned_options.size(); ++i) {
        jvm_options[i].optionString = const_cast<char*>(owned_options[i].c_str());
        jvm_options[i].extraInfo    = nullptr;
    }

    JavaVMInitArgs vm_args = {};
    vm_args.version            = JNI_VERSION_21;
    vm_args.nOptions           = static_cast<jint>(jvm_options.size());
    vm_args.options            = jvm_options.data();
    vm_args.ignoreUnrecognized = JNI_FALSE;

    // Reserve the libunity/il2cpp guest-allocator arena region
    // [0x300000000, 0x400000000) (12..16 GiB) BEFORE the JVM starts, so the
    // JVM's ASLR-placed heap / metaspace / compressed-class-space / CDS never
    // land inside it. The libunity arena (bionic_shim.cpp kArenaHint) MAP_FIXEDs
    // into 0x300000000 lazily during the guest's init_array -- long after the
    // JVM heap is populated. Without this reservation the 1 GiB Java heap
    // frequently ASLRs to ~0x315000000 (INSIDE the arena), and the arena's
    // MAP_FIXED then silently destroys live Java heap pages. That manifests two
    // ways depending on which object got clobbered: (1) the flaky
    // moduleEntry.cpp:323 / jni_FindClass crash (a ClassLoader/Module oop was
    // zeroed), and (2) a render hang (an il2cpp pthread_mutex sharing the
    // overlapping pages gets its state word scribbled -> stuck "locked"). We
    // claim the band here with a PROT_NONE placeholder so the JVM allocates
    // elsewhere; the arena's own MAP_FIXED later replaces this placeholder.
    // Guest .so segments map via mmap(nullptr) (arm64_native.cpp) so they pick
    // OS-chosen addresses and are unaffected.
    //
    // macOS 27: the band is no longer always free. The 27.0 dyld shared cache
    // declares sharedRegionSize 0x1b9b14000 from 0x180000000 with a maxSlide of
    // 0x20000000 (read from the cache header), so it ends anywhere between
    // 0x339b14000 and 0x359b14000 -- squarely across 0x300000000. A MAP_FIXED
    // there returns EPERM, the arena then could not be placed at all, and every
    // Unity game froze in libunity's static init (Crossy Road, Subway Surfers on
    // 27.0.1). So when the fixed band is not free, reserve the LARGEST free gap
    // below 16 GiB instead (on 27 that is the space between the end of the shared
    // cache and 0x400000000, 2.5-3 GiB depending on the slide), and tell the
    // shim where it is through MACAPK_UNITY_ARENA_BAND. On 26.x the fixed band is
    // free and nothing changes. docs/unity-arena-macos27-shared-cache_2026-10-03.md
    {
        const uintptr_t kArenaBandBase = 0x300000000ULL;   // == bionic_shim kArenaHint
        const size_t    kArenaBandSize = 0x100000000ULL;   // 4 GiB -> ceiling 0x400000000
        const uintptr_t k16GiB         = 0x400000000ULL;
        const uintptr_t kAlign         = 0x100000ULL;      // 1 MiB
        uintptr_t band_base = 0, band_size = 0;
        // The next region at or above `from` that is really IN USE, as [lo, hi).
        // Recurses into submaps, and counts a PROT_NONE range INSIDE a submap as
        // free: on macOS 27 the dyld shared region is one submap from 0x180000000
        // to 0xFC0000000 whose tail past the cache content is an empty PROT_NONE
        // reservation, which the kernel lets this task map over (only the cache's
        // own pages answer EPERM). A plain mach_vm_region reports that whole
        // submap as one mapped region and would leave no band below 16 GiB.
        auto next_used = [](uintptr_t from, uintptr_t* lo, uintptr_t* hi) -> bool {
            for (int guard = 0; guard < 100000; ++guard) {
                mach_vm_address_t a = from;
                mach_vm_size_t sz = 0;
                natural_t depth = 64;
                vm_region_submap_short_info_data_64_t info;
                mach_msg_type_number_t cnt = VM_REGION_SUBMAP_SHORT_INFO_COUNT_64;
                if (mach_vm_region_recurse(mach_task_self(), &a, &sz, &depth,
                                           reinterpret_cast<vm_region_recurse_info_t>(&info),
                                           &cnt) != KERN_SUCCESS) return false;
                if (depth > 0 && info.protection == VM_PROT_NONE) {   // empty submap tail
                    if (a + sz <= from) return false;
                    from = static_cast<uintptr_t>(a + sz);
                    continue;
                }
                *lo = static_cast<uintptr_t>(a);
                *hi = static_cast<uintptr_t>(a + sz);
                return true;
            }
            return false;
        };
        // Fixed band first, when nothing in use overlaps it (that is every 26.x host).
        {
            uintptr_t lo = 0, hi = 0;
            if (!next_used(kArenaBandBase, &lo, &hi) || lo >= kArenaBandBase + kArenaBandSize) {
                band_base = kArenaBandBase;
                band_size = kArenaBandSize;
            }
        }
        if (!band_base) {
            // Otherwise the largest free gap between __PAGEZERO and 16 GiB.
            uintptr_t cursor = 0x100000000ULL, best_lo = 0, best_hi = 0;
            for (int guard = 0; guard < 100000 && cursor < k16GiB; ++guard) {
                uintptr_t used_lo = k16GiB, used_hi = k16GiB;
                const bool more = next_used(cursor, &used_lo, &used_hi);
                const uintptr_t lo = (cursor + kAlign - 1) & ~(kAlign - 1);
                const uintptr_t hi = std::min<uintptr_t>(more ? used_lo : k16GiB, k16GiB) & ~(kAlign - 1);
                if (hi > lo && hi - lo > best_hi - best_lo) { best_lo = lo; best_hi = hi; }
                if (!more || used_hi <= cursor) break;
                cursor = used_hi;
            }
            if (best_hi - best_lo >= (1ULL << 30)) {           // under 1 GiB is not worth it
                band_base = best_lo;
                band_size = std::min<uintptr_t>(best_hi - best_lo, kArenaBandSize);
            }
        }
        void* rsv = band_base
            ? mmap(reinterpret_cast<void*>(band_base), band_size,
                   PROT_NONE, MAP_ANON | MAP_PRIVATE | MAP_FIXED, -1, 0)
            : MAP_FAILED;
        if (rsv == MAP_FAILED) {
            unsetenv("MACAPK_UNITY_ARENA_BAND");
            std::fprintf(stderr,
                "[macapk] WARN: could not reserve a guest arena band below 16 GiB "
                "(tried %p, %s); JVM heap may collide with the libunity arena\n",
                reinterpret_cast<void*>(band_base ? band_base : kArenaBandBase),
                band_base ? std::strerror(errno) : "no free gap of 1 GiB or more");
        } else {
            char buf[64];
            std::snprintf(buf, sizeof buf, "0x%llx:0x%llx",
                          static_cast<unsigned long long>(band_base),
                          static_cast<unsigned long long>(band_size));
            setenv("MACAPK_UNITY_ARENA_BAND", buf, 1);
            std::fprintf(stderr,
                "[macapk] reserved guest arena band [0x%llx,0x%llx) %.2f GiB "
                "pre-JVM so the Java heap cannot overlap the libunity arena%s\n",
                static_cast<unsigned long long>(band_base),
                static_cast<unsigned long long>(band_base + band_size),
                band_size / 1073741824.0,
                band_base == kArenaBandBase ? "" : " (fixed band occupied: largest free gap)");
        }
    }

    // macOS 27 with hardware TPRO (seen with SIP disabled) resets the JIT
    // write bit inside dlopen, and HotSpot's code-cache writes in create_vm
    // then fault. Must be in place before the VM's first WX switch.
    InstallJvmJitWxGuard();

    *out_vm  = nullptr;
    *out_env = nullptr;
    jint rc = JNI_CreateJavaVM(out_vm, reinterpret_cast<void**>(out_env), &vm_args);
    if (rc != JNI_OK) {
        std::fprintf(stderr,
                     "[macapk] JNI_CreateJavaVM failed (rc=%d). Aborting.\n",
                     static_cast<int>(rc));
        std::abort();
    }
    LogJvmJitWxGuardStats();
    LogCriticalReleaseSemantics(*out_env, SelectedCollector(owned_options));
    InstallAndroidProviderPreference(*out_env);
    if (aot.active && !aot.recording) {
        // Did it MAP? java.vm.info carries "sharing" exactly when an archive (the AOT
        // cache or the JDK's default CDS archive) is in use, so this one line is the
        // live answer to "is the cache doing anything", without -Xlog.
        const std::string info = SystemProperty(*out_env, "java.vm.info");
        std::fprintf(stderr, "[jvm] aot-cache: mapped=%s (java.vm.info=%s)\n",
                     info.find("sharing") != std::string::npos ? "yes" : "NO", info.c_str());
    }
    if (cfg.verbose) {
        std::fprintf(stderr, "[macapk] HotSpot up. classpath=\n");
        for (const auto& cp : cfg.classpath_entries) {
            std::fprintf(stderr, "    %s\n", cp.c_str());
        }
    }
}

void CheckException(JNIEnv* env, const char* where) {
    if (!env->ExceptionCheck()) return;
    std::fprintf(stderr, "[macapk] Java exception at %s:\n", where);
    env->ExceptionDescribe();
    env->ExceptionClear();
    std::abort();
}

}  // namespace macapk
