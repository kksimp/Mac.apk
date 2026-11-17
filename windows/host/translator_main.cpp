// translator_main.cpp -- macapk-translator.exe, the Windows side of upstream's
// translator command line (runtime/translator/src/main.cpp):
//
//     macapk-translator --in <lib.so> --out <host library> [--shim-dir <dir>]
//                       [--load-bias <hex>]
//
// Upstream's class loader runs it once per app library and System.load()s the
// output. On the Mac it wraps the ELF in a Mach-O. Here nothing needs
// translating: arm64 code runs as it is, loaded by the runtime's ELF linker.
// The output is a copy of winapk-guest-stub.dll (next to this program) plus
// <out>.source naming the .so (UTF-8); see host/guest_libs.h. The other
// options are accepted and ignored.

#include <cstdio>
#include <string>

#include <windows.h>

int wmain(int argc, wchar_t** argv) {
    std::wstring in, out;
    for (int i = 1; i + 1 < argc; ++i) {
        const std::wstring a = argv[i];
        if (a == L"--in") in = argv[++i];
        else if (a == L"--out") out = argv[++i];
    }
    if (in.empty() || out.empty()) {
        std::fprintf(stderr, "usage: macapk-translator --in <lib.so> --out <host library>\n");
        return 2;
    }
    wchar_t self[32768];
    const DWORD n = GetModuleFileNameW(nullptr, self, sizeof(self) / sizeof(self[0]));
    std::wstring stub(self, n);
    stub = stub.substr(0, stub.find_last_of(L"\\/") + 1) + L"winapk-guest-stub.dll";
    if (!CopyFileW(stub.c_str(), out.c_str(), FALSE)) {
        // The output may be there already and in use: the library was translated before and
        // is loaded, here or in another process of the app (React Native's SoLoader asks
        // again in its recovery step). A loaded DLL cannot be overwritten (error 32), and
        // need not be: every output is the same stub. Only a missing or different file fails.
        const DWORD error = GetLastError();
        WIN32_FILE_ATTRIBUTE_DATA have{}, want{};
        const bool same = (error == ERROR_SHARING_VIOLATION || error == ERROR_ACCESS_DENIED ||
                           error == ERROR_USER_MAPPED_FILE) &&
                          GetFileAttributesExW(out.c_str(), GetFileExInfoStandard, &have) &&
                          GetFileAttributesExW(stub.c_str(), GetFileExInfoStandard, &want) &&
                          have.nFileSizeLow == want.nFileSizeLow && have.nFileSizeHigh == want.nFileSizeHigh;
        if (!same) {
            std::fprintf(stderr, "macapk-translator: cannot copy the stub DLL (error %lu)\n", error);
            return 1;
        }
    }
    const int len = WideCharToMultiByte(CP_UTF8, 0, in.c_str(), static_cast<int>(in.size()), nullptr, 0, nullptr,
                                        nullptr);
    std::string utf8(static_cast<size_t>(len > 0 ? len : 0), '\0');
    if (len > 0) WideCharToMultiByte(CP_UTF8, 0, in.c_str(), static_cast<int>(in.size()), &utf8[0], len, nullptr, nullptr);
    FILE* f = _wfopen((out + L".source").c_str(), L"wb");
    if (!f || std::fwrite(utf8.data(), 1, utf8.size(), f) != utf8.size()) {
        if (f) std::fclose(f);
        std::fprintf(stderr, "macapk-translator: cannot write %ls.source\n", out.c_str());
        return 1;
    }
    std::fclose(f);
    return 0;
}
