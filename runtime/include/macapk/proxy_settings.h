#pragma once

#include <string>

namespace macapk {

// Shared by the native settings panel and its persistence regression test.
// Java reads the same per-app file once, before guest code starts.
struct ProxySettings {
    std::string mode = "system";
    std::string host = "127.0.0.1";
    int port = 8080;
};

bool ValidateProxySettings(const ProxySettings& settings, std::string& error);
bool LoadProxySettings(const std::string& android_root, ProxySettings& settings, std::string& error);
bool SaveProxySettings(const std::string& android_root, const ProxySettings& settings, std::string& error);
// Cocoa main-thread action; saving takes effect at the next application launch.
void ShowProxySettings();

} // namespace macapk
