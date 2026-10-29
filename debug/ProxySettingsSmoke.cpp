#include "macapk/proxy_settings.h"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sys/stat.h>

int main(int argc, char** argv) {
    assert(argc == 2);
    const std::filesystem::path root(argv[1]);
    std::filesystem::create_directories(root);
    macapk::ProxySettings saved;
    std::string error;
    assert(macapk::LoadProxySettings(root.string(), saved, error));
    assert(saved.mode == "system");
    saved.mode = "http";
    saved.host = "127.0.0.1";
    saved.port = 6789;
    assert(macapk::SaveProxySettings(root.string(), saved, error));
    auto file = root / ".macapk/proxy.properties";
    struct stat st{}; assert(stat(file.c_str(), &st) == 0 && (st.st_mode & 0777) == 0600);
    macapk::ProxySettings loaded;
    assert(macapk::LoadProxySettings(root.string(), loaded, error));
    assert(loaded.mode == "http" && loaded.host == saved.host && loaded.port == saved.port);
    for (const auto& host : {"http://proxy.local", "a\nmode=direct", "user@proxy", "abc:def"}) {
        saved.host = host;
        assert(!macapk::SaveProxySettings(root.string(), saved, error));
        assert(macapk::LoadProxySettings(root.string(), loaded, error) && loaded.host == "127.0.0.1");
    }
    saved.host = "[::1]"; saved.mode = "socks5";
    assert(macapk::SaveProxySettings(root.string(), saved, error));
    assert(macapk::LoadProxySettings(root.string(), loaded, error));
    assert(loaded.host == "::1" && loaded.mode == "socks5");
    saved.port = 65536;
    assert(!macapk::SaveProxySettings(root.string(), saved, error));
    saved.mode = "direct";
    assert(macapk::SaveProxySettings(root.string(), saved, error));
    assert(macapk::LoadProxySettings(root.string(), loaded, error) && loaded.mode == "direct");
    saved.mode = "system";
    assert(macapk::SaveProxySettings(root.string(), saved, error));
    assert(macapk::LoadProxySettings(root.string(), loaded, error) && loaded.mode == "system");
    for (const auto& broken : {"mode=httpp\n", "mode=http\nhost=abc\n", "mode=direct\nmode=http\n", "mode=direct\nunknown=yes\n", "host=abc\n"}) {
        { std::ofstream out(file); out << broken; }
        assert(!macapk::LoadProxySettings(root.string(), loaded, error));
    }
    std::cout << "proxy-settings: PASS (roundtrip, per-app persistence, permissions, invalid input preserves prior config)\n";
}
