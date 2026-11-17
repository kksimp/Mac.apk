// bionic_test.cpp -- winapk-bionic-test: the Bionic shim (src/bionic/) from the
// guest's side (test/guest/bionic_guest.c).
//
//   winapk-bionic-test <libwinapk_bionic_guest.so> <scratch dir>
//
// The guest library is loaded through the guest linker with the shim as its
// libc, so every call is a real AAPCS64 call into the shim with Bionic's
// layouts. An import the shim does not provide fails the load and is listed.
//
// Exit code: the number of failed checks.

#include "winapk/arm64_guest.h"
#include "winapk/guest_abi.h"
#include "winapk/linker.h"
#include "winapk/mini_bionic.h"

#include <cstdio>
#include <string>

#include <windows.h>
#include <wincrypt.h>

namespace {

// "<subject_hash_old> <first base64 line>" of a root in the user's Root store
// that Windows accepts for TLS (in date, no purposes set on it), for the guest
// to find in /system/etc/security/cacerts. Computed with CryptoAPI, apart from
// the shim's own DER reading. Empty if the store has none.
std::string windows_root_for_guest() {
    std::string out;
    HCERTSTORE store = CertOpenSystemStoreW(0, L"ROOT");
    if (!store) return out;
    for (PCCERT_CONTEXT c = nullptr; out.empty() && (c = CertEnumCertificatesInStore(store, c));) {
        DWORD size = 0;
        if (CertVerifyTimeValidity(nullptr, c->pCertInfo) != 0 ||
            CertGetEnhancedKeyUsage(c, CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG, nullptr, &size)) {
            continue;
        }
        BYTE md5[16];
        DWORD n = sizeof md5;
        if (!CryptHashCertificate2(L"MD5", 0, nullptr, c->pCertInfo->Subject.pbData, c->pCertInfo->Subject.cbData,
                                   md5, &n)) {
            continue;
        }
        DWORD len = 0;
        const DWORD flags = CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF;
        if (!CryptBinaryToStringA(c->pbCertEncoded, c->cbCertEncoded, flags, nullptr, &len)) continue;
        std::string b64(len, ' ');
        if (!CryptBinaryToStringA(c->pbCertEncoded, c->cbCertEncoded, flags, &b64[0], &len)) continue;
        char hash[16];
        std::snprintf(hash, sizeof hash, "%02x%02x%02x%02x", md5[3], md5[2], md5[1], md5[0]);
        out = std::string(hash) + " " + b64.substr(0, 64);
        CertFreeCertificateContext(c);
    }
    CertCloseStore(store, 0);
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) {
        std::fprintf(stderr, "usage: winapk-bionic-test <libwinapk_bionic_guest.so> <scratch dir>\n");
        return 2;
    }
    // As in the runtime: a bad descriptor from the guest must fail with errno, not fast-fail the process.
    _set_invalid_parameter_handler([](const wchar_t*, const wchar_t*, const wchar_t*, unsigned, uintptr_t) {});
    winapk::LinkerConfig config;
    config.host = winapk::mini_bionic_resolve;
    winapk::GuestLinker linker(config);
    std::string error;
    void* lib = linker.open(argv[1], winapk::GuestLinker::kRtldNow, &error);
    if (!lib) {
        std::printf("FAIL: cannot load the guest: %s\n", error.c_str());
        return 1;
    }
    using TestFn = int(WINAPK_GUEST_ABI*)(const char*);
    auto test = reinterpret_cast<TestFn>(linker.lookup(lib, "bionic_test", nullptr, &error));
    if (!test) {
        std::printf("FAIL: the guest has no bionic_test\n");
        return 1;
    }
    // Guest "/x" paths resolve under the scratch directory, as under the
    // android root in the runtime.
    winapk::set_android_root(argv[2]);
    const std::string root = windows_root_for_guest();
    if (!root.empty()) winapk::bionic_setenv("WINAPK_TEST_CACERT", root.c_str(), 1);
    std::setvbuf(stdout, nullptr, _IONBF, 0);  // keep the output if the guest crashes
    winapk::arm64::enter_thread();
    const int failures = test(argv[2]);
    std::fflush(stdout);
    std::printf("\n%s: %d failure(s)\n", failures ? "FAILED" : "OK", failures);
    return failures;
}
