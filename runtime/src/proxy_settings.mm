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
    [alert addButtonWithTitle:@"好"];
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
        error = "请选择有效的代理方式。";
        return false;
    }
    if (settings.mode == "system" || settings.mode == "direct") return true;
    const std::string host = HostValue(settings.host);
    if (host.empty() || host.size() > 253) {
        error = "请输入代理服务器的主机名或 IP 地址。";
        return false;
    }
    if (host.find(':') != std::string::npos) {
        in6_addr address{};
        if (inet_pton(AF_INET6, host.c_str(), &address) != 1) {
            error = "IPv6 地址格式无效。";
            return false;
        }
    } else {
        for (unsigned char c : host) {
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
                  (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_')) {
                error = "主机名只需填写地址，例如 127.0.0.1；请勿包含协议、端口或账号。";
                return false;
            }
        }
    }
    if (settings.port < 1 || settings.port > 65535) {
        error = "端口须为 1 到 65535 之间的整数。";
        return false;
    }
    return true;
}

bool LoadProxySettings(const std::string& android_root, ProxySettings& settings, std::string& error) {
    settings = ProxySettings{};
    error.clear();
    if (android_root.empty()) { error = "无法确定此应用的数据目录。"; return false; }
    const auto file = std::filesystem::path(android_root) / ".macapk/proxy.properties";
    std::error_code ec;
    const bool exists = std::filesystem::exists(file, ec);
    if (ec) { error = "无法读取代理配置文件。"; return false; }
    if (!exists) return true;
    if (std::filesystem::file_size(file, ec) > 8192 || ec) {
        error = "代理配置文件无效或过大。";
        return false;
    }
    std::ifstream input(file);
    if (!input) { error = "无法读取代理配置文件。"; return false; }
    std::string line;
    std::string port_text;
    std::set<std::string> keys;
    while (std::getline(input, line)) {
        for (unsigned char c : line) {
            if ((c < 32 && c != '\t' && c != '\r') || c > 126) {
                error = "代理配置只支持 ASCII 格式的地址与设置。";
                return false;
            }
        }
        line = Trim(line);
        if (line.empty() || line.front() == '#' || line.front() == '!') continue;
        const auto equal = line.find('=');
        if (equal == std::string::npos) { error = "代理配置文件格式无效。"; return false; }
        const std::string key = Trim(line.substr(0, equal));
        const std::string value = Trim(line.substr(equal + 1));
        if (!keys.insert(key).second) { error = "代理配置文件包含重复设置。"; return false; }
        if (key == "mode") settings.mode = value;
        else if (key == "host") settings.host = value;
        else if (key == "port") port_text = value;
        else { error = "代理配置文件包含未知设置。"; return false; }
    }
    if (input.bad() || !keys.count("mode")) { error = "代理配置文件不完整。"; return false; }
    if ((settings.mode == "http" || settings.mode == "socks5") && (!keys.count("host") || !keys.count("port"))) {
        error = "请同时填写代理主机和端口。";
        return false;
    }
    if ((settings.mode == "http" || settings.mode == "socks5") && !ParsePort(port_text, settings.port)) {
        error = "代理配置中的端口无效。";
        return false;
    }
    settings.host = HostValue(settings.host);
    return ValidateProxySettings(settings, error);
}

bool SaveProxySettings(const std::string& android_root, const ProxySettings& settings, std::string& error) {
    if (!ValidateProxySettings(settings, error)) return false;
    if (android_root.empty()) { error = "无法确定此应用的数据目录。"; return false; }
    const auto directory = std::filesystem::path(android_root) / ".macapk";
    std::error_code ec;
    std::filesystem::create_directories(directory, ec);
    if (ec) { error = "无法创建代理配置目录。"; return false; }
    const bool manual = settings.mode == "http" || settings.mode == "socks5";
    const std::string body = "mode=" + settings.mode + "\n" +
        (manual ? "host=" + HostValue(settings.host) + "\nport=" + std::to_string(settings.port) + "\n" : "");
    const std::string file = (directory / "proxy.properties").string();
    const std::string pattern = file + ".XXXXXX";
    std::vector<char> temp(pattern.begin(), pattern.end()); temp.push_back('\0');
    const int fd = mkstemp(temp.data()); // atomically created with mode 0600
    if (fd < 0) { error = "无法写入代理配置。"; return false; }
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
    if (!ok) { unlink(temp.data()); error = "无法保存代理配置；原有配置已保留。"; }
    return ok;
}

void ShowProxySettings() {
    const char* root_env = std::getenv("MACAPK_ANDROID_ROOT");
    const std::string root = root_env ? root_env : "";
    ProxySettings settings;
    std::string error;
    if (!LoadProxySettings(root, settings, error)) {
        if (root.empty()) {
            ShowError(@"无法读取代理设置", error);
            return;
        }
        ShowError(@"请重新设置代理", error + "\n请选择连接方式并保存。取消将保留原有配置。");
        settings = ProxySettings{};
    }
    MacApkProxyFields* fields = [[MacApkProxyFields alloc] init];
    NSView* form = [[NSView alloc] initWithFrame:NSMakeRect(0, 0, 390, 160)];
    auto label = [form](NSString* value, CGFloat y) {
        NSTextField* text = [NSTextField labelWithString:value];
        text.frame = NSMakeRect(0, y, 78, 24);
        [form addSubview:text];
    };
    label(@"连接方式", 128); label(@"主机", 88); label(@"端口", 48);
    fields.mode = [[NSPopUpButton alloc] initWithFrame:NSMakeRect(80, 127, 308, 28) pullsDown:NO];
    [fields.mode addItemsWithTitles:@[@"沿用当前代理设置", @"直连", @"HTTP 代理", @"SOCKS5 代理"]];
    const std::vector<std::string> modes = {"system", "direct", "http", "socks5"};
    auto found = std::find(modes.begin(), modes.end(), settings.mode);
    [fields.mode selectItemAtIndex:found - modes.begin()];
    fields.mode.target = fields; fields.mode.action = @selector(modeChanged:);
    fields.host = [[NSTextField alloc] initWithFrame:NSMakeRect(82, 88, 304, 24)];
    fields.host.stringValue = Text(settings.host);
    fields.host.placeholderString = @"127.0.0.1";
    fields.port = [[NSTextField alloc] initWithFrame:NSMakeRect(82, 48, 110, 24)];
    fields.port.stringValue = [NSString stringWithFormat:@"%d", settings.port];
    NSTextField* local = [NSTextField wrappingLabelWithString:@"直接访问本机地址默认直连。支持无需账号密码的代理。"];
    local.frame = NSMakeRect(0, 0, 390, 34);
    local.font = [NSFont systemFontOfSize:11]; local.textColor = [NSColor secondaryLabelColor];
    [form addSubview:fields.mode]; [form addSubview:fields.host]; [form addSubview:fields.port]; [form addSubview:local];
    [fields modeChanged:nil];
    NSAlert* alert = [[NSAlert alloc] init];
    alert.messageText = @"网络代理";
    alert.informativeText = @"仅用于此应用的 Java 网络连接。保存后，退出并重新打开此应用生效。";
    alert.accessoryView = form;
    [alert addButtonWithTitle:@"保存"]; [alert addButtonWithTitle:@"取消"];
    while ([alert runModal] == NSAlertFirstButtonReturn) {
        ProxySettings chosen;
        chosen.mode = modes[fields.mode.indexOfSelectedItem];
        chosen.host = Trim(fields.host.stringValue.UTF8String ?: "");
        const bool manual = chosen.mode == "http" || chosen.mode == "socks5";
        if (manual && !ParsePort(Trim(fields.port.stringValue.UTF8String ?: ""), chosen.port)) {
            ShowError(@"端口无效", "请输入 1 到 65535 之间的整数。");
            continue;
        }
        if (!SaveProxySettings(root, chosen, error)) {
            ShowError(@"无法保存代理设置", error);
            continue;
        }
        NSAlert* saved = [[NSAlert alloc] init];
        saved.messageText = @"代理设置已保存";
        saved.informativeText = @"退出并重新打开此应用后生效。";
        [saved addButtonWithTitle:@"好"]; [saved runModal];
        break;
    }
}

} // namespace macapk
