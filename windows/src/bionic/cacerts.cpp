// cacerts.cpp -- Android's system CA store, filled from the roots Windows
// trusts.
//
// A device keeps its trusted roots as one PEM file per root in
// /system/etc/security/cacerts (and, since Android 14, the updatable copy in
// /apex/com.android.conscrypt/cacerts), each named <subject_hash_old>.<n>:
// OpenSSL's X509_NAME_hash_old, so a hashed-directory lookup finds them. Native
// TLS stacks that carry their own BoringSSL or OpenSSL read that directory,
// because the platform's Java trust manager is out of their reach: Flutter's
// dart:io loads it with SSL_CTX_load_verify_locations, and without it every
// HTTPS request failed with "TlsException: Failed to find root cert cache".
//
// The directory holds the same trust anchors the Java side gets
// (HostTrust.install): the JDK's CAs plus every root Windows trusts. The
// Windows ones matter most: on a PC that is where the root of a filtering
// proxy, a company's TLS inspection or a locally installed CA lives,
// and behind such a proxy no public root would verify anything. Windows' set
// is the Root and third-party AuthRoot stores of the user and the machine,
// group policy and enterprise ones included, less what Windows itself would
// not accept for a TLS server: expired roots, roots in a Disallowed store, and
// roots whose properties disable them or limit them to purposes other than
// server authentication. The JDK's roots cover public CAs Windows has not
// fetched yet (it adds those to AuthRoot on demand).
//
// Upstream builds the same directory from macOS' anchors
// (runtime/translator/src/bionic_shim_cacerts.cpp); this follows its layout.
// The directory is named after a digest of the set, so it is built once per
// distinct set, under <android root>/system/etc/security/cacerts-<digest>: a
// root added to or removed from Windows yields a new one on the next launch.
// It is written under a temporary name and renamed into place, so a
// concurrent app never sees half of it, and older sets are removed.
//
// WINAPK_SYSTEM_CACERTS=0: the paths stay absent.

#include "bionic_internal.h"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <set>
#include <string>
#include <vector>

#include <windows.h>
#include <bcrypt.h>
#include <wincrypt.h>

namespace winapk::bionic {

namespace {

using Der = std::vector<uint8_t>;
using Digest = std::array<uint8_t, 32>;

// ---- DER ---------------------------------------------------------------------

// A view of DER bytes, read one TLV at a time.
struct DerReader {
    const uint8_t* p;
    size_t n;

    // The next TLV: its tag, its contents and (with `whole`) all of it.
    bool next(uint8_t* tag, DerReader* contents, DerReader* whole = nullptr) {
        if (n < 2) return false;
        size_t h = 2, l = p[1];
        if (l & 0x80) {
            const size_t k = l & 0x7f;
            if (k == 0 || k > 4 || n < 2 + k) return false;
            l = 0;
            for (size_t i = 0; i < k; i++) l = (l << 8) | p[2 + i];
            h = 2 + k;
        }
        if (h + l > n) return false;
        *tag = p[0];
        *contents = {p + h, l};
        if (whole) *whole = {p, h + l};
        p += h + l;
        n -= h + l;
        return true;
    }
    // The contents of the next TLV, which must have tag `want`.
    bool expect(uint8_t want, DerReader* contents) {
        uint8_t tag;
        return next(&tag, contents) && tag == want;
    }
};

// The DER encoding of a certificate's subject Name (RFC 5280 4.1:
// Certificate = SEQ { tbsCertificate SEQ { [0] version OPTIONAL, serialNumber,
// signature, issuer, validity, subject, ... }, ... }).
bool der_subject(const Der& cert, DerReader* subject) {
    DerReader all{cert.data(), cert.size()}, c, tbs;
    if (!all.expect(0x30, &c) || !c.expect(0x30, &tbs)) return false;
    if (tbs.n && tbs.p[0] == 0xa0) {
        uint8_t tag;
        DerReader skip;
        tbs.next(&tag, &skip);  // the explicit version
    }
    for (int field = 0; field < 5; field++) {  // serial, signature, issuer, validity, subject
        uint8_t tag;
        DerReader contents;
        if (!tbs.next(&tag, &contents, subject)) return false;
    }
    return true;
}

bool is_oid(const DerReader& oid, std::initializer_list<uint8_t> want) {
    return oid.n == want.size() && std::memcmp(oid.p, want.begin(), oid.n) == 0;
}

// The certificates of a PKCS #12 file with plain (unencrypted) cert bags, the
// form the JDK's lib/security/cacerts has had since JDK 18: PFX = SEQ {
// version, authSafe ContentInfo { data, [0] OCTET STRING { SEQ OF ContentInfo
// { data, [0] OCTET STRING { SEQ OF SafeBag { certBag, [0] CertBag {
// x509Certificate, [0] OCTET STRING <DER> } } } } } } } (RFC 7292). Anything
// else (a JKS store, encrypted bags) yields nothing.
std::vector<Der> pkcs12_certs(const Der& file) {
    static const std::initializer_list<uint8_t> kData = {0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x07, 0x01};
    static const std::initializer_list<uint8_t> kCertBag = {0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x0c, 0x0a, 0x01, 0x03};
    static const std::initializer_list<uint8_t> kX509 = {0x2a, 0x86, 0x48, 0x86, 0xf7, 0x0d, 0x01, 0x09, 0x16, 0x01};
    std::vector<Der> out;
    // A ContentInfo of type data: its octets.
    auto data_of = [](DerReader info, DerReader* octets) {
        DerReader oid, wrapped;
        return info.expect(0x06, &oid) && is_oid(oid, kData) && info.expect(0xa0, &wrapped) &&
               wrapped.expect(0x04, octets);
    };
    DerReader all{file.data(), file.size()}, pfx, version, auth_info, auth_safe, infos;
    if (!all.expect(0x30, &pfx) || !pfx.expect(0x02, &version) || !pfx.expect(0x30, &auth_info) ||
        !data_of(auth_info, &auth_safe) || !auth_safe.expect(0x30, &infos)) {
        return out;
    }
    for (DerReader info; infos.expect(0x30, &info);) {
        DerReader contents, bags;
        if (!data_of(info, &contents) || !contents.expect(0x30, &bags)) continue;
        for (DerReader bag; bags.expect(0x30, &bag);) {
            DerReader bag_id, bag_value, cert_bag, cert_id, cert_value, der;
            if (bag.expect(0x06, &bag_id) && is_oid(bag_id, kCertBag) && bag.expect(0xa0, &bag_value) &&
                bag_value.expect(0x30, &cert_bag) && cert_bag.expect(0x06, &cert_id) && is_oid(cert_id, kX509) &&
                cert_bag.expect(0xa0, &cert_value) && cert_value.expect(0x04, &der)) {
                out.emplace_back(der.p, der.p + der.n);
            }
        }
    }
    return out;
}

// ---- hashes and files ------------------------------------------------------------

bool hash(const wchar_t* algorithm, const uint8_t* data, size_t n, uint8_t* out, DWORD out_len) {
    return CryptHashCertificate2(algorithm, 0, nullptr, data, static_cast<DWORD>(n), out, &out_len) != FALSE;
}

Digest sha256(const uint8_t* data, size_t n) {
    Digest d{};
    hash(BCRYPT_SHA256_ALGORITHM, data, n, d.data(), static_cast<DWORD>(d.size()));
    return d;
}

std::string pem(const Der& der) {
    static const char kB64[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    const size_t n = der.size();
    std::string b64;
    for (size_t i = 0; i < n; i += 3) {
        const uint32_t v = static_cast<uint32_t>(der[i]) << 16 | (i + 1 < n ? static_cast<uint32_t>(der[i + 1]) << 8 : 0) |
                           (i + 2 < n ? static_cast<uint32_t>(der[i + 2]) : 0);
        b64 += kB64[(v >> 18) & 63];
        b64 += kB64[(v >> 12) & 63];
        b64 += i + 1 < n ? kB64[(v >> 6) & 63] : '=';
        b64 += i + 2 < n ? kB64[v & 63] : '=';
    }
    std::string out = "-----BEGIN CERTIFICATE-----\n";
    for (size_t i = 0; i < b64.size(); i += 64) out += b64.substr(i, 64) + "\n";
    out += "-----END CERTIFICATE-----\n";
    return out;
}

bool read_file(const std::wstring& path, Der* out) {
    HANDLE h = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER size;
    bool ok = GetFileSizeEx(h, &size) && size.QuadPart > 0 && size.QuadPart < (16 << 20);
    if (ok) {
        out->resize(static_cast<size_t>(size.QuadPart));
        DWORD got = 0;
        ok = ReadFile(h, out->data(), static_cast<DWORD>(out->size()), &got, nullptr) && got == out->size();
    }
    CloseHandle(h);
    return ok;
}

bool write_file(const std::wstring& path, const std::string& data) {
    HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD put = 0;
    const bool ok = WriteFile(h, data.data(), static_cast<DWORD>(data.size()), &put, nullptr) && put == data.size();
    return CloseHandle(h) && ok;
}

void remove_tree(const std::wstring& dir) {
    WIN32_FIND_DATAW e;
    HANDLE find = FindFirstFileW((dir + L"\\*").c_str(), &e);
    if (find != INVALID_HANDLE_VALUE) {
        do {
            if (!(e.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) DeleteFileW((dir + L"\\" + e.cFileName).c_str());
        } while (FindNextFileW(find, &e));
        FindClose(find);
    }
    RemoveDirectoryW(dir.c_str());
}

bool is_dir(const std::wstring& path) {
    const DWORD a = GetFileAttributesW(path.c_str());
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY);
}

void make_dirs(const std::wstring& path) {
    for (size_t i = 3; i <= path.size(); i++) {
        if (i == path.size() || path[i] == L'\\' || path[i] == L'/') CreateDirectoryW(path.substr(0, i).c_str(), nullptr);
    }
}

// ---- the trusted set -----------------------------------------------------------

// The certificates of one system store; `flags` picks the location
// (CERT_SYSTEM_STORE_CURRENT_USER, ...).
template <typename Fn>
void each_in_store(DWORD flags, const wchar_t* name, Fn fn) {
    HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0,
                                     flags | CERT_STORE_READONLY_FLAG | CERT_STORE_OPEN_EXISTING_FLAG, name);
    if (!store) return;
    for (PCCERT_CONTEXT c = nullptr; (c = CertEnumCertificatesInStore(store, c));) fn(c);
    CertCloseStore(store, 0);
}

constexpr DWORD kLocations[] = {
    CERT_SYSTEM_STORE_CURRENT_USER,
    CERT_SYSTEM_STORE_CURRENT_USER_GROUP_POLICY,
    CERT_SYSTEM_STORE_LOCAL_MACHINE,
    CERT_SYSTEM_STORE_LOCAL_MACHINE_GROUP_POLICY,
    CERT_SYSTEM_STORE_LOCAL_MACHINE_ENTERPRISE,
};

// Whether Windows would accept `c` as the root of a TLS server's chain, apart
// from the Disallowed stores: not expired, and not disabled or limited to
// other purposes by its properties (certmgr's "Edit properties").
bool good_for_tls(PCCERT_CONTEXT c) {
    if (CertVerifyTimeValidity(nullptr, c->pCertInfo) > 0) return false;
    DWORD size = 0;
    if (!CertGetEnhancedKeyUsage(c, CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG, nullptr, &size)) return true;
    std::vector<uint8_t> buf(size);
    auto* usage = reinterpret_cast<PCERT_ENHKEY_USAGE>(buf.data());
    SetLastError(0);
    if (!CertGetEnhancedKeyUsage(c, CERT_FIND_PROP_ONLY_ENHKEY_USAGE_FLAG, usage, &size)) return true;
    // No usages: good for all when the property is absent, for none when it
    // is present but empty ("Disable all purposes").
    if (usage->cUsageIdentifier == 0) return GetLastError() == static_cast<DWORD>(CRYPT_E_NOT_FOUND);
    for (DWORD i = 0; i < usage->cUsageIdentifier; i++) {
        if (std::strcmp(usage->rgpszUsageIdentifier[i], szOID_PKIX_KP_SERVER_AUTH) == 0) return true;
    }
    return false;
}

// The JDK's lib/security/cacerts, for the JDK this process runs (jvm.dll is
// <jdk>\bin\server\jvm.dll); empty outside the runtime.
std::vector<Der> jdk_roots() {
    HMODULE jvm = GetModuleHandleW(L"jvm.dll");
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = jvm ? GetModuleFileNameW(jvm, buf, MAX_PATH * 4) : 0;
    if (n == 0 || n >= MAX_PATH * 4) return {};
    std::wstring home(buf, n);
    for (int up = 0; up < 3; up++) {
        const size_t slash = home.find_last_of(L"\\/");
        if (slash == std::wstring::npos) return {};
        home.resize(slash);
    }
    Der file;
    if (!read_file(home + L"\\lib\\security\\cacerts", &file)) return {};
    return pkcs12_certs(file);
}

struct TrustedSet {
    std::map<Digest, Der> roots;  // by SHA-256, so the order (and the names) do not depend on the stores'
    int from_windows = 0, from_jdk = 0;
};

TrustedSet trusted_set() {
    std::set<Digest> disallowed;
    for (DWORD where : kLocations) {
        each_in_store(where, L"Disallowed", [&](PCCERT_CONTEXT c) {
            disallowed.insert(sha256(c->pbCertEncoded, c->cbCertEncoded));
        });
    }
    TrustedSet set;
    auto add = [&](PCCERT_CONTEXT c, int* count) {
        const Digest d = sha256(c->pbCertEncoded, c->cbCertEncoded);
        if (disallowed.count(d) || set.roots.count(d) || !good_for_tls(c)) return;
        set.roots.emplace(d, Der(c->pbCertEncoded, c->pbCertEncoded + c->cbCertEncoded));
        ++*count;
    };
    for (DWORD where : kLocations) {
        for (const wchar_t* name : {L"Root", L"AuthRoot"}) {
            each_in_store(where, name, [&](PCCERT_CONTEXT c) { add(c, &set.from_windows); });
        }
    }
    for (const Der& der : jdk_roots()) {
        PCCERT_CONTEXT c = CertCreateCertificateContext(X509_ASN_ENCODING, der.data(), static_cast<DWORD>(der.size()));
        if (!c) continue;
        add(c, &set.from_jdk);
        CertFreeCertificateContext(c);
    }
    return set;
}

// ---- the directory ---------------------------------------------------------------

std::string narrow(const std::wstring& w) {
    if (w.empty()) return {};
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n), '\0');
    WideCharToMultiByte(CP_UTF8, 0, w.c_str(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring build_store(const std::wstring& android_root) {
    const TrustedSet set = trusted_set();
    if (set.roots.empty()) return {};

    // The digest names the set; the leading version changes with the layout.
    std::vector<uint8_t> all = {1};
    for (const auto& r : set.roots) all.insert(all.end(), r.first.begin(), r.first.end());
    const Digest sum = sha256(all.data(), all.size());
    wchar_t key[17];
    for (int i = 0; i < 8; i++) swprintf(key + 2 * i, 3, L"%02x", sum[i]);

    const std::wstring base = android_root + L"\\system\\etc\\security";
    const std::wstring dir = base + L"\\cacerts-" + key;
    bool built = false;
    if (!is_dir(dir)) {
        make_dirs(base);
        const std::wstring tmp = dir + L".tmp" + std::to_wstring(GetCurrentProcessId());
        remove_tree(tmp);
        if (!CreateDirectoryW(tmp.c_str(), nullptr)) return {};
        std::map<uint32_t, int> used;
        for (const auto& r : set.roots) {
            DerReader subject;
            uint8_t md5[16];
            if (!der_subject(r.second, &subject) || !hash(BCRYPT_MD5_ALGORITHM, subject.p, subject.n, md5, 16)) continue;
            // X509_NAME_hash_old: the first four bytes of MD5(DER name), little-endian.
            const uint32_t h = md5[0] | md5[1] << 8 | md5[2] << 16 | static_cast<uint32_t>(md5[3]) << 24;
            wchar_t name[32];
            swprintf(name, 32, L"%08x.%d", h, used[h]++);
            write_file(tmp + L"\\" + name, pem(r.second));
        }
        if (!MoveFileExW(tmp.c_str(), dir.c_str(), 0)) {
            remove_tree(tmp);  // another process won the race: use its copy
            if (!is_dir(dir)) return {};
        }
        built = true;
    }
    // Older sets go (one an app still has open stays until the next time).
    WIN32_FIND_DATAW e;
    HANDLE find = FindFirstFileW((base + L"\\cacerts-*").c_str(), &e);
    if (find != INVALID_HANDLE_VALUE) {
        do {
            const std::wstring name = e.cFileName;
            if ((e.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) && name != L"cacerts-" + std::wstring(key) &&
                name.find(L".tmp") == std::wstring::npos) {
                remove_tree(base + L"\\" + name);
            }
        } while (FindNextFileW(find, &e));
        FindClose(find);
    }
    std::fprintf(stderr, "[win.apk] Android CA store: %zu roots, %d that Windows trusts + %d more from the JDK%s (%s)\n",
                 set.roots.size(), set.from_windows, set.from_jdk, built ? ", written" : "", narrow(dir).c_str());
    return dir;
}

}  // namespace

std::wstring android_cacerts_dir(const std::wstring& android_root) {
    static std::once_flag once;
    static std::wstring dir;
    std::call_once(once, [&] {
        const char* e = std::getenv("WINAPK_SYSTEM_CACERTS");
        if ((e && e[0] == '0') || android_root.empty()) return;
        dir = build_store(android_root);
    });
    return dir;
}

}  // namespace winapk::bionic
