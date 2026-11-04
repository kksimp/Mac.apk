#include "macapk/proxy_settings.h"

#import <AppKit/AppKit.h>
#include <algorithm>
#include <arpa/inet.h>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <set>
#include <unistd.h>
#include <vector>

namespace {
std::string Trim(std::string s) {
    const auto first = s.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = s.find_last_not_of(" \t\r\n");
    return s.substr(first, last - first + 1);
}
std::string HostValue(std::string s) {
    s = Trim(s);
    if (s.size() > 2 && s.front() == '[' && s.back() == ']') s = s.substr(1, s.size() - 2);
    return s;
}
bool ParsePort(const std::string& text, int& port) {
    if (text.empty() || text.size() > 5) return false;
    int value = 0;
    for (char c : text) {
        if (c < '0' || c > '9') return false;
        value = value * 10 + (c - '0');
    }
    if (value < 1 || value > 65535) return false;
    port = value;
    return true;
}
NSString* Text(const std::string& s) { return [NSString stringWithUTF8String:s.c_str()] ?: @""; }
void ShowError(NSString* message, const std::string& detail) {
    NSAlert* alert = [[NSAlert alloc] init];
    alert.messageText = message;
    alert.informativeText = Text(detail);
    [alert addButtonWithTitle:@"OK"];
    [alert runModal];
}
} // namespace

@interface MacApkProxyFields : NSObject
@property(strong) NSPopUpButton* mode;
@property(strong) NSTextField* host;
@property(strong) NSTextField* port;
- (void)modeChanged:(id)sender;
@end
@implementation MacApkProxyFields
- (void)modeChanged:(id)sender {
    BOOL manual = self.mode.indexOfSelectedItem >= 2;
    self.host.enabled = manual;
    self.port.enabled = manual;
}
@end

namespace macapk {

bool ValidateProxySettings(const ProxySettings& settings, std::string& error) {
    error.clear();
    if (settings.mode != "system" && settings.mode != "direct" && settings.mode != "http" && settings.mode != "socks5") {
        error = "Choose a valid proxy mode.";
        return false;
    }
    if (settings.mode == "system" || settings.mode == "direct") return true;
    const std::string host = HostValue(settings.host);
    if (host.empty() || host.size() > 253) {
        error = "Enter the proxy server host name or IP address.";
        return false;
    }
    if (host.find(':') != std::string::npos) {
        in6_addr address{};
        if (inet_pton(AF_INET6, host.c_str(), &address) != 1) {
            error = "The IPv6 address is not valid.";
            return false;
        }
    } else {
        for (unsigned char c : host) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) {
                error = "Enter only the address, for example 127.0.0.1. Do not include a scheme, port or account.";
                return false;
            }
        }
    }
    if (settings.port < 1 || settings.port > 65535) {
        error = "The port must be an integer from 1 to 65535.";
        return false;
    }
    return true;
}

bool LoadProxySettings(const std::string& android_root, ProxySettings& settings, std::string& error) {
    settings = ProxySettings{};
    error.clear();
    if (android_root.empty()) { error = "Cannot determine this app's data directory."; return false; }
    const auto file = std::filesystem::path(android_root) / ".macapk/proxy.properties";
    std::error_code ec;
    const bool exists = std::filesystem::exists(file, ec);
    if (ec) { error = "Cannot read the proxy configuration file."; return false; }
    if (!exists) return true;
    if (std::filesystem::file_size(file, ec) > 8192 || ec) {
        error = "The proxy configuration file is invalid or too large.";
        return false;
    }
    std::ifstream input(file);
    if (!input) { error = "Cannot read the proxy configuration file."; return false; }
    std::string line;
    std::string port_text;
    std::set<std::string> keys;
    while (std::getline(input, line)) {
        for (unsigned char c : line) {
            if ((c < 32 && c != '\t' && c != '\r') || c > 126) {
                error = "The proxy configuration supports only ASCII addresses and settings.";
                return false;
            }
        }
        line = Trim(line);
        if (line.empty() || line.front() == '#' || line.front() == '!') continue;
        const auto equal = line.find('=');
        if (equal == std::string::npos) { error = "The proxy configuration file format is invalid."; return false; }
        const std::string key = Trim(line.substr(0, equal));
        const std::string value = Trim(line.substr(equal + 1));
        if (!keys.insert(key).second) { error = "The proxy configuration file repeats a setting."; return false; }
        if (key == "mode") settings.mode = value;
        else if (key == "host") settings.host = value;
        else if (key == "port") port_text = value;
        else { error = "The proxy configuration file contains an unknown setting."; return false; }
    }
    if (input.bad() || !keys.count("mode")) { error = "The proxy configuration file is incomplete."; return false; }
    if ((settings.mode == "http" || settings.mode == "socks5") && (!keys.count("host") || !keys.count("port"))) {
        error = "Enter both the proxy host and port.";
        return false;
    }
    if ((settings.mode == "http" || settings.mode == "socks5") && !ParsePort(port_text, settings.port)) {
        error = "The port in the proxy configuration is not valid.";
        return false;
    }
    settings.host = HostValue(settings.host);
    return ValidateProxySettings(settings, error);
}

bool SaveProxySettings(const std::string& android_root, const ProxySettings& settings, std::string& error) {
    if (!ValidateProxySettings(settings, error)) return false;
    if (android_root.empty()) { error = "Cannot determine this app's data directory."; return false; }
    const auto directory = std::filesystem::path(android_root) / ".macapk";
    std::error_code ec;
    std::filesystem::create_directories(directory, ec);
    if (ec) { error = "Cannot create the proxy configuration directory."; return false; }
    const bool manual = settings.mode == "http" || settings.mode == "socks5";
    const std::string body = "mode=" + settings.mode + "\n" +
        (manual ? "host=" + HostValue(settings.host) + "\nport=" + std::to_string(settings.port) + "\n" : "");
    const std::string file = (directory / "proxy.properties").string();
    const std::string pattern = file + ".XXXXXX";
    std::vector<char> temp(pattern.begin(), pattern.end()); temp.push_back('\0');
    const int fd = mkstemp(temp.data()); // atomically created with mode 0600
    if (fd < 0) { error = "Cannot write the proxy configuration."; return false; }
    size_t written = 0;
    bool ok = true;
    while (written < body.size()) {
        const ssize_t n = write(fd, body.data() + written, body.size() - written);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) { ok = false; break; }
        written += static_cast<size_t>(n);
    }
    if (ok && fsync(fd) != 0) ok = false;
    if (close(fd) != 0) ok = false;
    if (ok && rename(temp.data(), file.c_str()) != 0) ok = false;
    if (!ok) { unlink(temp.data()); error = "Cannot save the proxy configuration. The previous configuration was kept."; }
    return ok;
}

void ShowProxySettings() {
    const char* root_env = std::getenv("MACAPK_ANDROID_ROOT");
    const std::string root = root_env ? root_env : "";
    ProxySettings settings;
    std::string error;
    if (!LoadProxySettings(root, settings, error)) {
        if (root.empty()) {
            ShowError(@"Cannot read proxy settings", error);
            return;
        }
        ShowError(@"Set the proxy again", error + "\nChoose a connection mode and save. Cancel keeps the existing configuration.");
        settings = ProxySettings{};
    }
    MacApkProxyFields* fields = [[MacApkProxyFields alloc] init];
    NSView* form = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 390, 160)];
    auto label = [form](NSString* value, CGFloat y) {
        NSTextField* text = [NSTextField labelWithString:value];
        text.frame = NSMakeRect(0, y, 78, 24);
        [form addSubview:text];
    };
    label(@"Mode", 128); label(@"Host", 88); label(@"Port", 48);
    fields.mode = [[NSPopUpButton alloc] initWithFrame:NSMakeRect(80, 127, 308, 28) pullsDown:NO];
    [fields.mode addItemsWithTitles:@[@"Use current proxy settings", @"Direct", @"HTTP proxy", @"SOCKS5 proxy"]];
    const std::vector<std::string> modes = {"system", "direct", "http", "socks5"};
    auto found = std::find(modes.begin(), modes.end(), settings.mode);
    [fields.mode selectItemAtIndex:found - modes.begin()];
    fields.mode.target = fields; fields.mode.action = @selector(modeChanged:);
    fields.host = [[NSTextField alloc] initWithFrame:NSMakeRect(82, 88, 304, 24)];
    fields.host.stringValue = Text(settings.host);
    fields.host.placeholderString = @"127.0.0.1";
    fields.port = [[NSTextField alloc] initWithFrame:NSMakeRect(82, 48, 110, 24)];
    fields.port.stringValue = [NSString stringWithFormat:@"%d", settings.port];
    NSTextField* local = [NSTextField wrappingLabelWithString:@"Loopback addresses always connect directly. Proxies that need no account or password are supported."];
    local.frame = NSMakeRect(0, 0, 390, 34);
    local.font = [NSFont systemFontOfSize:11]; local.textColor = [NSColor secondaryLabelColor];
    [form addSubview:fields.mode]; [form addSubview:fields.host]; [form addSubview:fields.port]; [form addSubview:local];
    [fields modeChanged:nil];
    NSAlert* alert = [[NSAlert alloc] init];
    alert.messageText = @"Network Proxy";
    alert.informativeText = @"Applies to this app's Java networking only. Quit and reopen the app after saving for it to take effect.";
    alert.accessoryView = form;
    [alert addButtonWithTitle:@"Save"]; [alert addButtonWithTitle:@"Cancel"];
    while ([alert runModal] == NSAlertFirstButtonReturn) {
        ProxySettings chosen;
        chosen.mode = modes[fields.mode.indexOfSelectedItem];
        chosen.host = Trim(fields.host.stringValue.UTF8String ?: "");
        const bool manual = chosen.mode == "http" || chosen.mode == "socks5";
        if (manual && !ParsePort(Trim(fields.port.stringValue.UTF8String ?: ""), chosen.port)) {
            ShowError(@"Invalid port", "Enter an integer from 1 to 65535.");
            continue;
        }
        if (!SaveProxySettings(root, chosen, error)) {
            ShowError(@"Cannot save proxy settings", error);
            continue;
        }
        NSAlert* saved = [[NSAlert alloc] init];
        saved.messageText = @"Proxy settings saved";
        saved.informativeText = @"Quit and reopen this app for the change to take effect.";
        [saved addButtonWithTitle:@"OK"]; [saved runModal];
        break;
    }
}

} // namespace macapk
