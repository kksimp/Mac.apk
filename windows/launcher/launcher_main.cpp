// launcher_main.cpp -- Win.apk.exe, the app library.
//
// The Windows counterpart of upstream's launcher (launcher/*.swift), laid out
// as a Windows 11 app: a navigation pane (Library, Store, Google Play, and
// the Google account and Settings at its foot), the page's title and toolbar
// (Install app) over a grid of the installed
// apps with their icons, and an InfoBar for what just happened. Settings and
// an app's details are pages in the same content layer. The whole window
// takes a dropped APK. The windows are drawn with GDI+ and follow Windows'
// light or dark mode (or the one picked in the settings).
//
// An APK dropped on the window, chosen through Install app, double-clicked in
// Explorer (the launcher registers itself for .apk files) or passed on the
// command line is installed, one at a time, in a small install window:
//
//   1. copied into %LOCALAPPDATA%\Win.apk\library, so the app stays when the
//      download is deleted;
//   2. installed and converted once by winapk-runtime --install-only, which
//      also writes the app's icon and name (apps\<name>\icon.png, label.txt;
//      the name moves next to the APK as library\<name>.label, because the
//      app's first run moves files it does not know out of apps\<name>);
//   3. given a Start menu shortcut (Start > Win.apk Apps) and, unless turned
//      off in the settings, a desktop shortcut, with the app's own icon, that
//      start it through this program.
//
// One process serves it all: another start hands its files to the one
// running (forward_to_running), and an APK from Explorer shows only the
// install window, not the library.
//
// Double-clicking an app runs it (winapk-runtime --apk, without a console)
// behind a splash that stays until the runtime says its window is up
// (WINAPK_READY_EVENT); if the app ends first the splash says so, with the
// log. The app window size and orientation picked in the settings reach the
// runtime as WINAPK_PHONE_SIZE. Apps open in landscape, the shape of a PC
// screen, unless the settings say portrait; an app's details page turns one
// app the other way (library\<name>.orientation), and an app whose manifest
// fixes its launched activity's orientation always gets that one.
//
// The Store tab, next to the library, is F-Droid's catalog (f-droid.org,
// free and open-source apps): its index is fetched in the background and
// kept as store\catalog.tsv, searchable and by category. Install downloads
// the APK, checks it against the index's SHA-256 and installs it as above;
// an app the library already has (by package) is updated in place.
//
//   Win.apk.exe                      the library
//   Win.apk.exe <app.apk> [...]      the install window, for these files
//   Win.apk.exe --run <library apk>  run an installed app (what shortcuts do)
//   Win.apk.exe install --apk <file> --source android [--requestor-label <app>] --json
//   Win.apk.exe uninstall --package <name> --source android [--requestor-label <app>] --json
//   Win.apk.exe runtime-permission | special-access | app-details ... --source android --json
//
// The last two are the install helper, upstream's macapk-installhelper: an app
// that installs apps (F-Droid, through PackageInstaller) hands its APK to
// PackageInstallerBackend, which runs this program. It asks the user, installs
// as above, and keeps installed_apps.json, the installed-apps registry that
// PackageManager answers apps from, so a store sees what it installed. Its
// output keeps the helper's contract: "[helper] progress <fraction> <stage>"
// lines, then one JSON object; exit 0 done, 2 bad arguments, 3 declined,
// 5 failed, 6 not an app. The third line is the permission dialogs (see
// "Permissions" below).

#include <windows.h>
#include <commctrl.h>
#include <dwmapi.h>
#include <gdiplus.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <uxtheme.h>
#include <windowsx.h>
#include <winhttp.h>
#include <richedit.h>
#include <bcrypt.h>
#include <tlhelp32.h>
#include <wincrypt.h>
#include <winternl.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cwctype>
#include <deque>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iterator>
#include <map>
#include <memory>
#include <set>
#include <string>
#include <vector>

#include "app_runs.h"
#include "installs.h"
#include "updates.h"
#include "../host/window_fit.h"
#include "../include/winapk/arch.h"

// The GitHub repository (owner/name) of the releases, issues and project page: CMake's WINAPK_REPO, which CI
// sets to the repository it builds in.
#ifndef WINAPK_REPO
#define WINAPK_REPO "airisu-dev/WinARM.apk"
#endif

namespace fs = std::filesystem;

namespace {

using Gdiplus::Color;
using Gdiplus::Graphics;
using Gdiplus::GraphicsPath;
using Gdiplus::RectF;
using Gdiplus::SizeF;

constexpr UINT kInstalled = WM_APP + 1;       // an install ended (finish_install says how); lParam: new std::wstring (the name)
constexpr UINT kRunExited = WM_APP + 2;       // the app's run ended (finish_run says how); lParam: new std::wstring (the app's name)
constexpr UINT kInstallStarted = WM_APP + 3;  // lParam: new std::wstring (the app's name)
constexpr UINT kRefresh = WM_APP + 4;         // the install helper changed the library
constexpr UINT kStoreCatalog = WM_APP + 5;    // wParam: kCatalogList (lParam: new std::vector<StoreApp>), Done, Failed
constexpr UINT kStoreProgress = WM_APP + 6;   // wParam: KB of the catalog received so far
constexpr UINT kStoreIcon = WM_APP + 7;       // wParam: kIconFailed, Saved, Dropped; lParam: new std::string (package)
constexpr UINT kStoreDownload = WM_APP + 8;   // wParam: percent, lParam: new std::string (package)
constexpr UINT kStoreDownloaded = WM_APP + 9; // wParam: ok, lParam: new Download
constexpr UINT kGmsDone = WM_APP + 10;       // the Google sign-in window closed; wParam: exit code, lParam: new std::wstring (stdout)
constexpr UINT kRunReady = WM_APP + 11;       // the app's window is up; lParam: new std::wstring (the app's name)
constexpr UINT kGmsAccount = WM_APP + 12;     // lParam: new std::wstring (the signed-in account, or empty)
constexpr UINT kUpdateChecked = WM_APP + 13;  // lParam: new UpdateCheck (the check, with its result)
constexpr UINT kUpdateProgress = WM_APP + 14; // wParam: percent of Win.apk-Setup.exe downloaded
constexpr UINT kUpdateDownloaded = WM_APP + 15;  // wParam: ok (complete and checked)
constexpr UINT kSetupChanged = WM_APP + 16;   // the Downloads folder changed while the Google services setup is open
constexpr UINT kSetupChecked = WM_APP + 17;   // lParam: new SetupCheck (winapk-runtime --verify-apk's answer)
constexpr UINT_PTR kInfoBarTimer = 2;  // the InfoBar closes itself
constexpr UINT_PTR kUpdateTimer = 3;   // hourly: is the daily update check due?
constexpr UINT_PTR kSetupScanTimer = 4;  // the Downloads folder settled: look for new files in it
constexpr ULONG_PTR kCopyOpen = 0x57415031;  // WM_COPYDATA from another start of Win.apk.exe (forward_to_running)
constexpr UINT_PTR kAnimTimer = 1;
constexpr int kIdSearch = 100;
enum Cmd { kCmdOpen = 200, kCmdStop, kCmdInfo, kCmdRemove };

HINSTANCE g_inst;
HWND g_main, g_search;
HFONT g_edit_font, g_mono_font;
HBRUSH g_edit_brush, g_code_brush;
float g_dpi = 96;

float px(float v) { return v * g_dpi / 96.0f; }
int ipx(float v) { return static_cast<int>(std::lround(px(v))); }

// ---------------------------------------------------------------------------
// Strings

// `en` with {0}, {1}... replaced by `args`.
std::wstring fmt(const wchar_t* en, std::initializer_list<std::wstring> args) {
    std::wstring s = en;
    size_t i = 0;
    for (const std::wstring& a : args) {
        const std::wstring key = L"{" + std::to_wstring(i++) + L"}";
        const size_t at = s.find(key);
        if (at != std::wstring::npos) s.replace(at, key.size(), a);
    }
    return s;
}

std::wstring app_count(size_t n) { return fmt(n == 1 ? L"{0} app" : L"{0} apps", {std::to_wstring(n)}); }

// ---------------------------------------------------------------------------
// Paths

fs::path exe_path() {
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = GetModuleFileNameW(nullptr, buf, MAX_PATH * 4);
    return fs::path(std::wstring(buf, n));
}
fs::path root() { return exe_path().parent_path(); }
fs::path runtime_exe() { return root() / L"bin" / L"winapk-runtime.exe"; }

fs::path data_dir() {
    wchar_t buf[MAX_PATH * 4];
    const DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", buf, MAX_PATH * 4);
    return fs::path(std::wstring(buf, n)) / L"Win.apk";
}
fs::path library_dir() { return data_dir() / L"library"; }
fs::path app_dir(const std::wstring& name) { return data_dir() / L"apps" / name; }
fs::path logs_dir() { return data_dir() / L"logs"; }
fs::path last_run_log() { return logs_dir() / L"last-run.log"; }
fs::path run_log(const std::wstring& name) { return logs_dir() / (L"run-" + name + L".log"); }
fs::path previous_run_log(const std::wstring& name) { return logs_dir() / (L"run-" + name + L".1.log"); }
fs::path install_log(const std::wstring& name) { return logs_dir() / (L"install-" + name + L".log"); }

fs::path known_folder(REFKNOWNFOLDERID id) {
    PWSTR p = nullptr;
    fs::path out;
    if (SUCCEEDED(SHGetKnownFolderPath(id, 0, nullptr, &p))) out = p;
    CoTaskMemFree(p);
    return out;
}
fs::path start_menu_apps() { return known_folder(FOLDERID_Programs) / L"Win.apk Apps"; }

const wchar_t* const kExtensions[] = {L".apk", L".apkm", L".xapk", L".apks"};

bool is_app_file(const fs::path& p) {
    std::wstring ext = p.extension().wstring();
    for (auto& c : ext) c = static_cast<wchar_t>(towlower(c));
    return std::any_of(std::begin(kExtensions), std::end(kExtensions), [&](const wchar_t* e) { return ext == e; });
}

std::wstring utf8_to_wide(const std::string& s) {
    const int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring w(static_cast<size_t>(n > 0 ? n : 0), L'\0');
    if (n > 0) MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), &w[0], n);
    return w;
}

std::string wide_to_utf8(const std::wstring& w) {
    const int n = WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), nullptr, 0, nullptr, nullptr);
    std::string s(static_cast<size_t>(n > 0 ? n : 0), '\0');
    if (n > 0) WideCharToMultiByte(CP_UTF8, 0, w.data(), static_cast<int>(w.size()), &s[0], n, nullptr, nullptr);
    return s;
}

std::wstring file_name_safe(std::wstring s) {
    for (auto& c : s) {
        if (c < 32 || wcschr(L"<>:\"/\\|?*", c)) c = L'_';
    }
    while (!s.empty() && (s.back() == L'.' || s.back() == L' ')) s.pop_back();
    return s.empty() ? L"App" : s;
}

std::wstring lower(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(&s[0], static_cast<DWORD>(s.size()));
    return s;
}

std::wstring format_bytes(uintmax_t n) {
    const wchar_t* units[] = {L"bytes", L"KB", L"MB", L"GB"};
    double v = static_cast<double>(n);
    int u = 0;
    while (v >= 1024 && u < 3) v /= 1024, ++u;
    wchar_t buf[64];
    swprintf(buf, 64, u == 0 ? L"%.0f %ls" : L"%.1f %ls", v, units[u]);
    return buf;
}

uintmax_t folder_size(const fs::path& dir) {
    uintmax_t total = 0;
    std::error_code ec;
    for (fs::recursive_directory_iterator it(dir, fs::directory_options::skip_permission_denied, ec), end;
         !ec && it != end; it.increment(ec)) {
        if (it->is_regular_file(ec)) total += it->file_size(ec);
    }
    return total;
}

std::wstring long_date(const FILETIME& when) {
    SYSTEMTIME utc, local;
    wchar_t buf[128] = {};
    if (!FileTimeToSystemTime(&when, &utc) || !SystemTimeToTzSpecificLocalTime(nullptr, &utc, &local) ||
        !GetDateFormatEx(LOCALE_NAME_USER_DEFAULT, DATE_LONGDATE, &local, nullptr, buf, 128, nullptr)) {
        return L"unknown";
    }
    return buf;
}

// FILETIME counts 100 ns from 1601, Java's milliseconds from 1970.
constexpr unsigned long long kUnixEpochFiletime = 116444736000000000ull;

std::wstring java_date(long long ms) {
    if (ms <= 0) return L"unknown";
    const unsigned long long t = kUnixEpochFiletime + static_cast<unsigned long long>(ms) * 10000;
    return long_date(FILETIME{static_cast<DWORD>(t), static_cast<DWORD>(t >> 32)});
}

// The version in launcher.rc, read from the program's own resource.
std::wstring version() {
    HRSRC res = FindResourceW(nullptr, MAKEINTRESOURCEW(VS_VERSION_INFO), RT_VERSION);
    HGLOBAL mem = res ? LoadResource(nullptr, res) : nullptr;
    const auto* p = static_cast<const BYTE*>(mem ? LockResource(mem) : nullptr);
    const DWORD size = res ? SizeofResource(nullptr, res) : 0;
    for (DWORD i = 0; p && i + sizeof(VS_FIXEDFILEINFO) <= size; i += 4) {
        const auto* fi = reinterpret_cast<const VS_FIXEDFILEINFO*>(p + i);
        if (fi->dwSignature != 0xFEEF04BD) continue;
        return std::to_wstring(HIWORD(fi->dwFileVersionMS)) + L"." + std::to_wstring(LOWORD(fi->dwFileVersionMS)) +
               L"." + std::to_wstring(HIWORD(fi->dwFileVersionLS));
    }
    return L"0";
}

void open_path(const fs::path& p) {
    std::error_code ec;
    fs::create_directories(p, ec);
    ShellExecuteW(nullptr, L"open", p.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
}

void show_in_explorer(const fs::path& p) {
    const std::wstring args = L"/select,\"" + p.wstring() + L"\"";
    ShellExecuteW(nullptr, L"open", L"explorer.exe", args.c_str(), nullptr, SW_SHOWNORMAL);
}

// Puts `text` on the clipboard.
bool copy_text(HWND owner, const std::wstring& text) {
    HGLOBAL mem = GlobalAlloc(GMEM_MOVEABLE, (text.size() + 1) * sizeof(wchar_t));
    if (!mem) return false;
    memcpy(GlobalLock(mem), text.c_str(), (text.size() + 1) * sizeof(wchar_t));
    GlobalUnlock(mem);
    if (!OpenClipboard(owner)) {
        GlobalFree(mem);
        return false;
    }
    EmptyClipboard();
    const bool ok = SetClipboardData(CF_UNICODETEXT, mem) != nullptr;
    if (!ok) GlobalFree(mem);
    CloseClipboard();
    return ok;
}

// ---------------------------------------------------------------------------
// Settings, in HKCU\Software\Win.apk

constexpr wchar_t kSettingsKey[] = L"Software\\Win.apk";

DWORD setting(const wchar_t* name, DWORD fallback) {
    DWORD v = 0, size = sizeof(v);
    return RegGetValueW(HKEY_CURRENT_USER, kSettingsKey, name, RRF_RT_REG_DWORD, nullptr, &v, &size) == ERROR_SUCCESS
               ? v
               : fallback;
}

void set_setting(const wchar_t* name, DWORD v) {
    RegSetKeyValueW(HKEY_CURRENT_USER, kSettingsKey, name, REG_DWORD, &v, sizeof(v));
}

struct PhonePreset {
    const wchar_t* label;
    int w, h;  // dp; 0 x 0 is the runtime's own choice
};

const PhonePreset kPhonePresets[] = {
    {L"Auto (fits the screen)", 0, 0},
    {L"320 × 480  (small, older apps)", 320, 480},
    {L"360 × 640  (5\" phone)", 360, 640},
    {L"360 × 780  (6.5\" phone)", 360, 780},
    {L"393 × 851  (Pixel 7)", 393, 851},
    {L"412 × 915  (Pixel 6)", 412, 915},
    {L"480 × 800  (6\" HDPI)", 480, 800},
    {L"540 × 960  (large phone)", 540, 960},
    {L"600 × 960  (7\" tablet)", 600, 960},
    {L"800 × 1280  (10\" tablet)", 800, 1280},
};

// The orientation apps open in. The settings' default is landscape, the
// shape of a PC screen: `Landscape` absent means landscape (earlier versions
// opened portrait, so an explicit 0 from them still means portrait). An app can be
// turned the other way on its own, and an app whose manifest fixes the
// orientation of its launched activity gets that whatever was picked.
enum Orientation { kOrientUnset = -1, kPortrait = 0, kLandscape = 1 };

bool landscape_default() { return setting(L"Landscape", 1) != 0; }

std::vector<std::string> package_of(const std::wstring& name);

// The user's choice for one app, library\<name>.orientation ("portrait" or
// "landscape"; beside the app's other library files, as the window size is).
fs::path orientation_file(const std::wstring& name) { return library_dir() / (name + L".orientation"); }

int app_orientation(const std::wstring& name) {
    std::ifstream f(orientation_file(name));
    std::string s;
    f >> s;
    return s == "landscape" ? kLandscape : s == "portrait" ? kPortrait : kOrientUnset;
}

void set_app_orientation(const std::wstring& name, int o) {
    std::error_code ec;
    if (o == kOrientUnset) {
        fs::remove(orientation_file(name), ec);
        return;
    }
    std::ofstream f(orientation_file(name), std::ios::trunc);
    f << (o == kLandscape ? "landscape" : "portrait") << "\n";
}

// The orientation the app's manifest fixes for its launched activity (the
// fourth line of library\<name>.package, AppMeta's ActivityInfo
// screenOrientation): the landscape and portrait families, as the runtime
// reads them (apply_screen_orientation); kOrientUnset when it fixes none, or
// the app was installed before the line was written.
int manifest_orientation(const std::wstring& name) {
    const std::vector<std::string> lines = package_of(name);
    if (lines.size() < 4) return kOrientUnset;
    const int o = std::atoi(lines[3].c_str());
    if (o == 0 || o == 6 || o == 8 || o == 11) return kLandscape;
    if (o == 1 || o == 7 || o == 9 || o == 12) return kPortrait;
    return kOrientUnset;
}

// What the app opens in: what its manifest fixes, else its own choice, else the settings'.
int effective_orientation(const std::wstring& name) {
    if (const int m = manifest_orientation(name); m != kOrientUnset) return m;
    if (const int o = app_orientation(name); o != kOrientUnset) return o;
    return landscape_default() ? kLandscape : kPortrait;
}

// The settings' screen size and the app's orientation, in dp: what the runtime
// started from here is told (apply_runtime_env), and what the starting window
// is sized by. "Auto" leaves whatever WINAPK_PHONE_SIZE the user set
// themselves, turned to the orientation; `name` empty (an install) takes the
// settings' orientation.
void runtime_phone_size(const std::wstring& name, int* pw, int* ph) {
    const DWORD i = setting(L"PhoneSize", 0);
    const bool landscape = (name.empty() ? landscape_default() : effective_orientation(name) == kLandscape);
    int w = 360, h = 800;
    if (i == 0 || i >= std::size(kPhonePresets)) {
        wchar_t own[64];
        if (GetEnvironmentVariableW(L"WINAPK_PHONE_SIZE", own, 64) > 0) {
            int ow = 0, oh = 0;
            wchar_t x = 0;
            if (swscanf(own, L"%d%c%d", &ow, &x, &oh) == 3 && ow > 0 && oh > 0) {
                w = ow;
                h = oh;
            }
        }
    } else {
        w = kPhonePresets[i].w;
        h = kPhonePresets[i].h;
    }
    if (landscape != (w > h)) std::swap(w, h);
    *pw = w;
    *ph = h;
}

// For the runtime started from here: it inherits this process's environment.
void apply_runtime_env(const std::wstring& name) {
    int w = 360, h = 800;
    runtime_phone_size(name, &w, &h);
    SetEnvironmentVariableW(L"WINAPK_PHONE_SIZE", (std::to_wstring(w) + L"x" + std::to_wstring(h)).c_str());
}

// ---------------------------------------------------------------------------
// Windows integration: file types and shortcuts

void set_reg_default(const std::wstring& sub, const std::wstring& value) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, sub.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) ==
        ERROR_SUCCESS) {
        RegSetValueExW(key, nullptr, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
                       static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t)));
        RegCloseKey(key);
    }
}

std::wstring reg_default(const std::wstring& sub) {
    wchar_t buf[256] = {};
    DWORD size = sizeof(buf);
    if (RegGetValueW(HKEY_CURRENT_USER, sub.c_str(), nullptr, RRF_RT_REG_SZ, nullptr, buf, &size) != ERROR_SUCCESS) {
        return L"";
    }
    return buf;
}

bool is_default_handler() { return reg_default(L"Software\\Classes\\.apk") == L"WinApk.Package"; }

// Claims the app file types; without force, only those no other program has.
void register_file_types(bool force) {
    const std::wstring exe = exe_path().wstring();
    set_reg_default(L"Software\\Classes\\WinApk.Package", L"Android app");
    set_reg_default(L"Software\\Classes\\WinApk.Package\\DefaultIcon", L"\"" + exe + L"\",0");
    set_reg_default(L"Software\\Classes\\WinApk.Package\\shell\\open\\command", L"\"" + exe + L"\" \"%1\"");
    for (const wchar_t* ext : kExtensions) {
        const std::wstring key = std::wstring(L"Software\\Classes\\") + ext;
        const std::wstring current = reg_default(key);
        if (force || current.empty() || current == L"WinApk.Package") set_reg_default(key, L"WinApk.Package");
    }
    SHChangeNotify(SHCNE_ASSOCCHANGED, SHCNF_IDLIST, nullptr, nullptr);
}

// The AppUserModelID of an app's process and of its Start menu shortcut: the
// runtime gives its process the id (SetCurrentProcessExplicitAppUserModelID)
// and the shortcut carries it (System.AppUserModel.ID), which is how a classic
// program's notifications (toasts) get the shortcut's name and icon in the
// Action Center, and its window the shortcut's identity on the taskbar.
// Derived from the library name alone; windows/host/native_notifications.cpp
// (app_user_model_id) computes the very same string: keep them in step.
// Letters, digits, '.', '-' and '_' pass; anything else becomes '_' and a hash
// of the whole name is appended, so names that differ only there stay apart;
// at most 128 characters and no spaces, as the id's rules say.
std::wstring app_user_model_id(const std::wstring& name) {
    std::wstring id = L"WinApk.App.";
    bool mangled = false;
    uint64_t hash = 14695981039346656037ULL;  // FNV-1a
    for (wchar_t c : name) {
        hash = (hash ^ static_cast<uint64_t>(c)) * 1099511628211ULL;
        const bool plain = (c >= L'0' && c <= L'9') || (c >= L'A' && c <= L'Z') || (c >= L'a' && c <= L'z') ||
                           c == L'.' || c == L'-' || c == L'_';
        id += plain ? c : L'_';
        mangled = mangled || !plain;
    }
    if (mangled || id.size() > 100) {
        id = id.substr(0, 100) + L'.';
        for (int i = 60; i >= 0; i -= 4) id += L"0123456789abcdef"[(hash >> i) & 0xf];
    }
    return id;
}

bool make_shortcut(const fs::path& lnk, const std::wstring& args, const fs::path& icon, const std::wstring& what,
                   const std::wstring& aumid) {
    IShellLinkW* link = nullptr;
    if (FAILED(CoCreateInstance(CLSID_ShellLink, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&link)))) return false;
    link->SetPath(exe_path().c_str());
    link->SetArguments(args.c_str());
    link->SetWorkingDirectory(root().c_str());
    link->SetDescription(what.c_str());
    if (!icon.empty()) link->SetIconLocation(icon.c_str(), 0);
    if (!aumid.empty()) {
        // System.AppUserModel.ID (propkey.h's PKEY_AppUserModel_ID, spelled out
        // so no property-system library is needed).
        static const PROPERTYKEY kAppUserModelId = {
            {0x9f4c2855, 0x9f79, 0x4b39, {0xa8, 0xd0, 0xe1, 0xd4, 0x2d, 0xe1, 0xd5, 0xf3}}, 5};
        IPropertyStore* props = nullptr;
        if (SUCCEEDED(link->QueryInterface(IID_IPropertyStore, reinterpret_cast<void**>(&props))) && props) {
            PROPVARIANT v{};
            v.vt = VT_LPWSTR;
            v.pwszVal = const_cast<wchar_t*>(aumid.c_str());  // read, not freed: no PropVariantClear
            if (SUCCEEDED(props->SetValue(kAppUserModelId, v))) props->Commit();
            props->Release();
        }
    }
    IPersistFile* file = nullptr;
    bool ok = SUCCEEDED(link->QueryInterface(IID_PPV_ARGS(&file)));
    if (ok) {
        std::error_code ec;
        fs::create_directories(lnk.parent_path(), ec);
        ok = SUCCEEDED(file->Save(lnk.c_str(), TRUE));
        file->Release();
    }
    link->Release();
    return ok;
}

void integrate_with_windows() {
    const fs::path self = known_folder(FOLDERID_Programs) / L"Win.apk.lnk";
    std::error_code ec;
    if (!fs::exists(self, ec)) make_shortcut(self, L"", fs::path(), L"Android apps on Windows", L"");
    register_file_types(false);
}

// ---------------------------------------------------------------------------
// The library

struct App {
    std::wstring name;   // the library file's stem: the key everywhere
    std::wstring label;  // what the user sees
    fs::path apk;
};

fs::path label_file(const std::wstring& name) { return library_dir() / (name + L".label"); }
// The app's package, versionCode and versionName, one per line (AppMeta's package.txt).
fs::path package_file(const std::wstring& name) { return library_dir() / (name + L".package"); }
fs::path shortcut_icon(const std::wstring& name) { return library_dir() / (name + L".ico"); }

std::wstring label_of(const std::wstring& name) {
    std::ifstream f(label_file(name), std::ios::binary);
    std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    std::wstring w = utf8_to_wide(s);
    // One line: a label written with a line end (or edited by hand) would wrap or cut in menus and lists.
    w.erase(0, w.find_first_not_of(L" \t\r\n"));
    if (const size_t end = w.find_first_of(L"\r\n"); end != std::wstring::npos) w.resize(end);
    while (!w.empty() && (w.back() == L' ' || w.back() == L'\t')) w.pop_back();
    return w.empty() ? name : w;
}

std::vector<App> library() {
    std::vector<App> apps;
    std::error_code ec;
    for (const auto& e : fs::directory_iterator(library_dir(), ec)) {
        if (!e.is_regular_file(ec) || !is_app_file(e.path())) continue;
        const std::wstring name = e.path().stem().wstring();
        if (!fs::exists(label_file(name), ec)) continue;  // not installed (yet)
        apps.push_back({name, label_of(name), e.path()});
    }
    std::sort(apps.begin(), apps.end(), [](const App& a, const App& b) {
        return CompareStringEx(LOCALE_NAME_USER_DEFAULT, NORM_IGNORECASE, a.label.c_str(), -1, b.label.c_str(), -1,
                               nullptr, nullptr, 0) == CSTR_LESS_THAN;
    });
    return apps;
}

// Google's Services Framework, Play services and Play Store are system components of Win.apk's Google services,
// not apps the user opens: the library, its tiles and the shortcuts leave them out (installed_apps.json keeps
// them, for the runtime and the system broker); their versions show on the Google services setup page, and
// Google Play in the pane is the Play Store's one way in.
bool system_package(const std::string& pkg) {
    return pkg == "com.google.android.gsf" || pkg == "com.google.android.gms" || pkg == "com.android.vending";
}

bool system_app(const std::wstring& name) {
    const std::vector<std::string> p = package_of(name);
    return !p.empty() && system_package(p[0]);
}

bool write_ico(const fs::path& png, const fs::path& ico) {
    std::ifstream f(png, std::ios::binary);
    std::vector<uint8_t> data((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (data.size() < 24 || data[1] != 'P') return false;
    const uint32_t w = (data[16] << 24) | (data[17] << 16) | (data[18] << 8) | data[19];
    const uint32_t h = (data[20] << 24) | (data[21] << 16) | (data[22] << 8) | data[23];
    std::ofstream o(ico, std::ios::binary | std::ios::trunc);
    auto u16 = [&](uint16_t v) { o.put(static_cast<char>(v & 0xff)).put(static_cast<char>(v >> 8)); };
    auto u32 = [&](uint32_t v) { u16(static_cast<uint16_t>(v & 0xffff)); u16(static_cast<uint16_t>(v >> 16)); };
    u16(0), u16(1), u16(1);
    o.put(static_cast<char>(w >= 256 ? 0 : w)).put(static_cast<char>(h >= 256 ? 0 : h)).put(0).put(0);
    u16(1), u16(32), u32(static_cast<uint32_t>(data.size())), u32(22);
    o.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    return static_cast<bool>(o);
}

std::wstring run_args(const fs::path& apk) { return L"--run \"" + apk.wstring() + L"\""; }

void remove_app_shortcuts(const App& app);

void make_app_shortcuts(const App& app, bool desktop) {
    if (system_app(app.name)) {  // a system component has no shortcut (and loses one an older launcher made)
        remove_app_shortcuts(app);
        return;
    }
    const fs::path ico = shortcut_icon(app.name);
    const bool has_icon = write_ico(app_dir(app.name) / L"icon.png", ico);
    const std::wstring file = file_name_safe(app.label) + L".lnk";
    const std::wstring what = app.label + L" (Android app, Win.apk)";
    const std::wstring aumid = app_user_model_id(app.name);
    make_shortcut(start_menu_apps() / file, run_args(app.apk), has_icon ? ico : fs::path(), what, aumid);
    if (desktop) {
        make_shortcut(known_folder(FOLDERID_Desktop) / file, run_args(app.apk), has_icon ? ico : fs::path(), what,
                      aumid);
    }
}

void remove_app_shortcuts(const App& app) {
    std::error_code ec;
    const std::wstring file = file_name_safe(app.label) + L".lnk";
    fs::remove(start_menu_apps() / file, ec);
    fs::remove(known_folder(FOLDERID_Desktop) / file, ec);
}

// Shortcuts written by an older launcher lack the AppUserModelID, without which
// an app's notifications show no name or icon: once (the ShortcutVersion
// setting), every app's shortcuts are written again. Bump the version when
// what a shortcut holds changes. 2: Google's apps lost theirs.
constexpr DWORD kShortcutVersion = 2;

void refresh_app_shortcuts() {
    if (setting(L"ShortcutVersion", 0) >= kShortcutVersion) return;
    std::error_code ec;
    for (const App& app : library()) {
        const fs::path desktop = known_folder(FOLDERID_Desktop) / (file_name_safe(app.label) + L".lnk");
        make_app_shortcuts(app, fs::exists(desktop, ec));
    }
    set_setting(L"ShortcutVersion", kShortcutVersion);
}

// ---------------------------------------------------------------------------
// Running and installing

// `name` is the app the runtime opens, for its orientation; empty for an install. The runtime's
// stdout and stderr go to `log` (none: NUL). `ready`, when given, is the launch handshake's event
// (win32_window.h, take_launcher_ready_event): the child gets it, and only it and the log, through
// an explicit handle list, as WINAPK_READY_EVENT in an environment block of its own, so two starts
// at once do not see each other's. `extra` are NAME=value variables for this child alone, over the ones
// the launcher has.
HANDLE start_runtime(const std::wstring& args, const fs::path& log, const std::wstring& name = L"",
                     HANDLE ready = nullptr, const std::vector<std::wstring>& extra = {}) {
    std::error_code ec;
    if (!log.empty()) fs::create_directories(log.parent_path(), ec);
    apply_runtime_env(name);
    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE out = CreateFileW(log.empty() ? L"NUL" : log.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE,
                             &sa, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    std::vector<HANDLE> inherit;
    if (out != INVALID_HANDLE_VALUE) inherit.push_back(out);
    if (ready) inherit.push_back(ready);
    SIZE_T attr_size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attr_size);
    std::vector<BYTE> attr_buf(attr_size);
    auto* attrs = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attr_buf.data());
    const bool listed = !inherit.empty() && InitializeProcThreadAttributeList(attrs, 1, 0, &attr_size) &&
                        UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherit.data(),
                                                  inherit.size() * sizeof(HANDLE), nullptr, nullptr);
    STARTUPINFOEXW si{};
    si.StartupInfo.cb = sizeof(si);
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdOutput = si.StartupInfo.hStdError = out;
    if (listed) si.lpAttributeList = attrs;
    // The environment as it is now, plus the event: NUL-separated strings, sorted as Windows keeps them,
    // ending in two NULs.
    std::vector<std::wstring> vars;
    if (wchar_t* block = GetEnvironmentStringsW()) {
        for (const wchar_t* e = block; *e; e += wcslen(e) + 1) {
            if (_wcsnicmp(e, L"WINAPK_READY_EVENT=", 19) == 0) continue;
            const bool replaced = std::any_of(extra.begin(), extra.end(), [e](const std::wstring& x) {
                const size_t eq = x.find(L'=');
                return eq != std::wstring::npos && _wcsnicmp(e, x.c_str(), eq + 1) == 0;
            });
            if (!replaced) vars.emplace_back(e);
        }
        FreeEnvironmentStringsW(block);
    }
    vars.insert(vars.end(), extra.begin(), extra.end());
    if (ready) vars.push_back(L"WINAPK_READY_EVENT=" + std::to_wstring(reinterpret_cast<uintptr_t>(ready)));
    std::stable_sort(vars.begin(), vars.end(),
                     [](const std::wstring& a, const std::wstring& b) { return _wcsicmp(a.c_str(), b.c_str()) < 0; });
    std::wstring env;
    for (const std::wstring& v : vars) env.append(v).push_back(L'\0');
    env.push_back(L'\0');
    std::wstring cmd = L"\"" + runtime_exe().wstring() + L"\" " + args;
    PROCESS_INFORMATION pi{};
    const BOOL ok = CreateProcessW(nullptr, &cmd[0], nullptr, nullptr, TRUE,
                                   CREATE_NO_WINDOW | CREATE_UNICODE_ENVIRONMENT |
                                       (listed ? EXTENDED_STARTUPINFO_PRESENT : 0),
                                   &env[0], root().c_str(), &si.StartupInfo, &pi);
    if (listed) DeleteProcThreadAttributeList(attrs);
    if (out != INVALID_HANDLE_VALUE) CloseHandle(out);
    if (!ok) return nullptr;
    // The app's window may take the foreground from the splash.
    AllowSetForegroundWindow(pi.dwProcessId);
    CloseHandle(pi.hThread);
    return pi.hProcess;
}

DWORD wait_exit(HANDLE process) {
    WaitForSingleObject(process, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(process, &code);
    CloseHandle(process);
    return code;
}

bool app_running(const std::wstring& name);

// Where a run of the app logs: logs\run-<name>.log, the run before it kept as run-<name>.1.log.
// last-run.log is a hard link to the newest, so "the log of the app opened last" stays one file.
// A second start of an app that is running only brings it forward: its log goes nowhere, so
// the running one's stays whole.
fs::path start_run_log(const std::wstring& name) {
    if (app_running(name)) return {};
    const fs::path log = run_log(name);
    std::error_code ec;
    fs::create_directories(logs_dir(), ec);
    MoveFileExW(log.c_str(), previous_run_log(name).c_str(), MOVEFILE_REPLACE_EXISTING);
    HANDLE f = CreateFileW(log.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
    DeleteFileW(last_run_log().c_str());
    CreateHardLinkW(last_run_log().c_str(), log.c_str(), nullptr);
    return log;
}

struct RunJob {
    std::wstring name;
    fs::path apk;
    std::vector<std::wstring> env;  // for this run alone: Google Play's embedding (start_play)
};

// The runs of the apps started from here (app_runs.h: the states, and which thread makes each step).
// g_proc_lock guards the table: shared to read it, exclusive to change it. The functions below are the
// only code that touches it.
SRWLOCK g_proc_lock = SRWLOCK_INIT;
winapk::launcher::RunTable g_runs;
using winapk::launcher::RunEnd;

// The library: the app's run begins. False when it has one already.
bool begin_run(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_proc_lock);
    const bool begun = g_runs.begin(name);
    ReleaseSRWLockExclusive(&g_proc_lock);
    return begun;
}

// Whether the app was started from here and the library has not heard of its end yet.
bool run_active(const std::wstring& name) {
    AcquireSRWLockShared(&g_proc_lock);
    const bool active = g_runs.active(name);
    ReleaseSRWLockShared(&g_proc_lock);
    return active;
}

bool any_runs() {
    AcquireSRWLockShared(&g_proc_lock);
    const bool any = !g_runs.empty();
    ReleaseSRWLockShared(&g_proc_lock);
    return any;
}

std::vector<std::wstring> run_names() {
    AcquireSRWLockShared(&g_proc_lock);
    std::vector<std::wstring> names = g_runs.names();
    ReleaseSRWLockShared(&g_proc_lock);
    return names;
}

// The process id of the app's runtime, 0 when it has none (not started from here, still starting, over).
DWORD run_pid(const std::wstring& name) {
    AcquireSRWLockShared(&g_proc_lock);
    HANDLE p = g_runs.process(name);
    const DWORD pid = p ? GetProcessId(p) : 0;  // under the lock: run_thread closes the handle after ended()
    ReleaseSRWLockShared(&g_proc_lock);
    return pid;
}

// The library, on kRunExited: how the run ended; it is forgotten.
RunEnd finish_run(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_proc_lock);
    const std::optional<RunEnd> end = g_runs.finish(name);
    ReleaseSRWLockExclusive(&g_proc_lock);
    // run_thread posts kRunExited only after ended(), so there is always one; a quiet end otherwise.
    return end ? *end : RunEnd{false, true, true};
}

// Runs the app and reports to the library's window: kRunReady when its window is up (the
// handshake's event), then kRunExited. The splash waits for the first.
DWORD WINAPI run_thread(void* param) {
    std::unique_ptr<RunJob> job(static_cast<RunJob*>(param));
    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE ready = CreateEventW(&sa, TRUE, FALSE, nullptr);
    AcquireSRWLockShared(&g_proc_lock);
    const bool stopped_early = g_runs.stop_requested(job->name);
    ReleaseSRWLockShared(&g_proc_lock);
    HANDLE p = stopped_early ? nullptr
                             : start_runtime(L"--apk \"" + job->apk.wstring() + L"\"", start_run_log(job->name),
                                             job->name, ready, job->env);
    DWORD code = 1;
    AcquireSRWLockExclusive(&g_proc_lock);
    if (!p) {
        g_runs.ended(job->name, false);  // it did not start, or was stopped before it did
    } else if (g_runs.attach(job->name, p)) {
        // Stopped while it was starting (the splash's X): it ends now, before its window comes up.
        TerminateProcess(p, 1);
    }
    ReleaseSRWLockExclusive(&g_proc_lock);
    if (p) {
        HANDLE both[2] = {ready, p};
        // Index 0 wins when both are set: an app that was already running signals, then exits.
        if (ready && WaitForMultipleObjects(2, both, FALSE, INFINITE) == WAIT_OBJECT_0) {
            AcquireSRWLockExclusive(&g_proc_lock);
            g_runs.ready(job->name);
            ReleaseSRWLockExclusive(&g_proc_lock);
            PostMessageW(g_main, kRunReady, 0, reinterpret_cast<LPARAM>(new std::wstring(job->name)));
        }
        WaitForSingleObject(p, INFINITE);
        GetExitCodeProcess(p, &code);
        AcquireSRWLockExclusive(&g_proc_lock);
        g_runs.ended(job->name, code == 0);
        ReleaseSRWLockExclusive(&g_proc_lock);
        CloseHandle(p);
    }
    if (ready) CloseHandle(ready);
    PostMessageW(g_main, kRunExited, 0, reinterpret_cast<LPARAM>(new std::wstring(job->name)));
    return 0;
}

// Stops the app; one still starting (run_thread has no process yet) is stopped as soon as it has one,
// or not started at all. True when there was one to stop: however it ends is then no error.
bool stop_run(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_proc_lock);
    const bool found = g_runs.stop(name, [](HANDLE p) { return TerminateProcess(p, 1) != 0; });
    ReleaseSRWLockExclusive(&g_proc_lock);
    return found;
}

// The run was asked to stop and may be over already (the splash's X), or its runtime was just ended by
// other means on the user's word (Remove, a permission taken away, a newer copy installed over it): its
// end is no error either way.
void expect_run_end(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_proc_lock);
    g_runs.expect_end(name);
    ReleaseSRWLockExclusive(&g_proc_lock);
}

struct InstallJob {
    fs::path source;
    std::wstring name;
    fs::path replaces;  // the app's previous APK, when it is updated from one with another extension
    bool temporary;     // the store's download, removed once it is in the library
};

SRWLOCK g_install_lock = SRWLOCK_INIT;  // one install at a time: each one is a JVM converting the whole app

// The installs queued from here (installs.h: the states, and which thread makes each step).
// g_installs_lock guards the table: shared to read it, exclusive to change it. The functions below,
// install_into_library and install_thread are the only code that touches it.
SRWLOCK g_installs_lock = SRWLOCK_INIT;
winapk::launcher::InstallTable g_installs;
using winapk::launcher::InstallEnd;

// The library: an install is queued. False when one of that name is pending already.
bool begin_install(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_installs_lock);
    const bool begun = g_installs.begin(name);
    ReleaseSRWLockExclusive(&g_installs_lock);
    return begun;
}

// Whether an install of `name` is queued or under way, or over without the library having heard.
bool is_pending(const std::wstring& name) {
    AcquireSRWLockShared(&g_installs_lock);
    const bool pending = g_installs.pending(name);
    ReleaseSRWLockShared(&g_installs_lock);
    return pending;
}

bool any_pending() {
    AcquireSRWLockShared(&g_installs_lock);
    const bool any = !g_installs.empty();
    ReleaseSRWLockShared(&g_installs_lock);
    return any;
}

// The pending installs, in the order they were queued.
std::vector<std::wstring> pending_names() {
    AcquireSRWLockShared(&g_installs_lock);
    std::vector<std::wstring> names = g_installs.names();
    ReleaseSRWLockShared(&g_installs_lock);
    return names;
}

// The install under way, empty when there is none.
std::wstring current_install() {
    AcquireSRWLockShared(&g_installs_lock);
    std::wstring name = g_installs.current();
    ReleaseSRWLockShared(&g_installs_lock);
    return name;
}

// The library, on kInstalled: how the install ended; it is forgotten.
InstallEnd finish_install(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_installs_lock);
    const std::optional<InstallEnd> end = g_installs.finish(name);
    ReleaseSRWLockExclusive(&g_installs_lock);
    // install_thread posts kInstalled only after ended(), so there is always one; a failure otherwise.
    return end ? *end : InstallEnd{};
}

// Cancels the install of `name`: one still waiting does not start, the one under way is stopped and
// undone. One that is being kept already, or is over, stays as it is.
void cancel_install(const std::wstring& name) {
    AcquireSRWLockExclusive(&g_installs_lock);
    g_installs.cancel(name, [](HANDLE p) { TerminateProcess(p, 1); });
    ReleaseSRWLockExclusive(&g_installs_lock);
}

void write_registry();

// Whether the app is running: winapk-runtime holds a named mutex keyed on
// the app's data folder for as long as it runs the app (claim_single_instance
// in host/runtime_main.cpp; the key is made the same way here).
bool app_running(const std::wstring& name) {
    std::error_code ec;
    std::wstring key = fs::absolute(app_dir(name), ec).lexically_normal().wstring();
    if (ec) key = app_dir(name).wstring();
    while (!key.empty() && (key.back() == L'\\' || key.back() == L'/')) key.pop_back();
    for (wchar_t& c : key) c = static_cast<wchar_t>(towlower(c == L'/' ? L'\\' : c));
    uint64_t h = 1469598103934665603ull;  // FNV-1a over the UTF-16 units
    for (const wchar_t c : key) h = (h ^ static_cast<uint16_t>(c)) * 1099511628211ull;
    wchar_t mutex[48];
    swprintf(mutex, 48, L"Local\\WinApk-app-%016llx", static_cast<unsigned long long>(h));
    HANDLE m = OpenMutexW(SYNCHRONIZE, FALSE, mutex);
    if (!m) return false;
    const DWORD w = WaitForSingleObject(m, 0);
    if (w == WAIT_OBJECT_0 || w == WAIT_ABANDONED) ReleaseMutex(m);
    CloseHandle(m);
    return w == WAIT_TIMEOUT;
}

void remove_app_files(const std::wstring& name);
struct GoogleApp;
const GoogleApp* google_app(const std::string& pkg);
namespace appops {
bool stop_library_app(const std::wstring& name);
}

// Google's three apps are not in the library, so the user has no way to close
// them: Play services in particular runs whenever an app or the Play Store uses
// it. Installing a newer copy stops the running one first instead of asking.
bool stop_google_app_for_install(const std::wstring& name) {
    std::string pkg;
    for (const wchar_t c : name) pkg += static_cast<char>(c < 0x80 ? c : '?');
    if (!google_app(pkg) || !appops::stop_library_app(name)) return false;
    expect_run_end(name);  // the Play Store in a window of its own: stopped for its update, not "an error"
    for (int i = 0; i < 100 && app_running(name); ++i) Sleep(100);  // its mutex goes with the process
    return !app_running(name);
}

// An install of `name` is under way in some process (the library's, or an install helper's): a named
// mutex held from the first file moved to the last. Windows lets go of it when its process dies, so a
// mutex that can be had means no install is alive, whatever the files say.
struct InstallClaim {
    HANDLE mutex = nullptr;
    bool held = false;
    // `wait`: for the install before this one to end; else only if it is free now.
    InstallClaim(const std::wstring& name, bool wait) {
        std::wstring key = name;
        for (wchar_t& c : key) c = static_cast<wchar_t>(towlower(c));
        uint64_t h = 1469598103934665603ull;  // FNV-1a over the UTF-16 units
        for (const wchar_t c : key) h = (h ^ static_cast<uint16_t>(c)) * 1099511628211ull;
        wchar_t id[48];
        swprintf(id, 48, L"Local\\WinApk-install-%016llx", static_cast<unsigned long long>(h));
        mutex = CreateMutexW(nullptr, FALSE, id);
        if (!mutex) return;
        const DWORD w = WaitForSingleObject(mutex, wait ? INFINITE : 0);
        held = w == WAIT_OBJECT_0 || w == WAIT_ABANDONED;
    }
    ~InstallClaim() {
        if (held) ReleaseMutex(mutex);
        if (mutex) CloseHandle(mutex);
    }
    InstallClaim(const InstallClaim&) = delete;
    InstallClaim& operator=(const InstallClaim&) = delete;
};

// An update that was cut off (the process killed, the PC switched off) left the app's previous APK
// beside it as <name><ext>.previous, and in its place a new one that may be half copied and was never
// converted. Each such app gets its previous APK (and icon) back, as a failed update does. A new app
// whose install was cut off left its APK in the library with no name beside it, and whatever the install
// had made of it: those go, as after a failed install. An app whose install is alive in another process
// is left to it. True when something was put back or removed.
bool recover_interrupted_installs() {
    bool recovered = false;
    std::error_code ec;
    std::vector<fs::path> kept, files;
    for (const auto& e : fs::directory_iterator(library_dir(), ec)) {
        if (e.path().extension() == L".previous") kept.push_back(e.path());
        else if (e.is_regular_file(ec) && is_app_file(e.path())) files.push_back(e.path());
    }
    for (const fs::path& previous : kept) {
        fs::path target = previous;
        target.replace_extension();  // <name><ext>
        const std::wstring name = target.stem().wstring();
        const InstallClaim claim(name, false);
        if (!claim.held) continue;
        if (MoveFileExW(previous.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING)) recovered = true;
        MoveFileExW((app_dir(name) / L"icon.png.previous").c_str(), (app_dir(name) / L"icon.png").c_str(),
                    MOVEFILE_REPLACE_EXISTING);
    }
    for (const fs::path& file : files) {
        const std::wstring name = file.stem().wstring();
        if (fs::exists(label_file(name), ec)) continue;  // installed
        const InstallClaim claim(name, false);
        if (!claim.held) continue;
        if (fs::remove(file, ec)) recovered = true;
        remove_app_files(name);
    }
    return recovered;
}

// Copies `source` into the library as <name><ext> and installs it there: the
// install, the code conversion, the name, icon and package, the shortcuts.
// `replaces` is the app's previous APK, removed once the new one is in. An
// app that is running is left alone (its files are in use): false, with
// `*running` set. An update that fails or is cancelled puts the previous APK
// back; a new app that does leaves nothing behind.
bool install_into_library(const fs::path& source, const std::wstring& name, const fs::path& replaces,
                          bool* running) {
    *running = app_running(name) && !stop_google_app_for_install(name);
    if (*running) return false;
    const InstallClaim claim(name, true);  // until this returns: see recover_interrupted_installs
    std::error_code ec;
    fs::create_directories(library_dir(), ec);
    const fs::path target = library_dir() / (name + source.extension().wstring());
    const bool update = fs::exists(label_file(name), ec);
    const fs::path kept = target.wstring() + L".previous";
    const bool same = fs::equivalent(source, target, ec);
    const bool backed_up = !same && fs::exists(target, ec) &&
                           MoveFileExW(target.c_str(), kept.c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
    bool ok = same || CopyFileW(source.c_str(), target.c_str(), FALSE) != 0;
    // The install writes the new version's icon first (so that it shows while the code is converted):
    // an update keeps the app's own beside it, to put back if the update does not go through.
    const fs::path icon = app_dir(name) / L"icon.png", icon_kept = app_dir(name) / L"icon.png.previous";
    const bool icon_backed_up = update && CopyFileW(icon.c_str(), icon_kept.c_str(), FALSE) != 0;
    if (ok) {
        HANDLE p = start_runtime(L"--apk \"" + target.wstring() + L"\" --install-only", install_log(name));
        DWORD code = 1;
        if (p) {
            AcquireSRWLockExclusive(&g_installs_lock);
            if (g_installs.attach(name, p)) TerminateProcess(p, 1);  // cancelled while it was starting
            ReleaseSRWLockExclusive(&g_installs_lock);
            WaitForSingleObject(p, INFINITE);
            GetExitCodeProcess(p, &code);
            AcquireSRWLockExclusive(&g_installs_lock);
            g_installs.detach(name);
            ReleaseSRWLockExclusive(&g_installs_lock);
            CloseHandle(p);
        }
        *running = code == 3;  // the app was started meanwhile
        // commit(): kept unless it was cancelled, and from here on a cancel is refused.
        AcquireSRWLockExclusive(&g_installs_lock);
        const bool keep = code == 0 && g_installs.commit(name);
        ReleaseSRWLockExclusive(&g_installs_lock);
        ok = keep &&
             MoveFileExW((app_dir(name) / L"label.txt").c_str(), label_file(name).c_str(),
                         MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED) != 0;
        if (ok) MoveFileExW((app_dir(name) / L"package.txt").c_str(), package_file(name).c_str(),
                            MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED);
    }
    if (icon_backed_up && !ok) MoveFileExW(icon_kept.c_str(), icon.c_str(), MOVEFILE_REPLACE_EXISTING);
    else if (icon_backed_up) fs::remove(icon_kept, ec);
    if (ok) {
        if (backed_up) fs::remove(kept, ec);
        if (!replaces.empty() && replaces != target) fs::remove(replaces, ec);
        make_app_shortcuts({name, label_of(name), target}, setting(L"DesktopShortcut", 1) != 0);
    } else if (!same) {
        fs::remove(target, ec);
        if (backed_up) MoveFileExW(kept.c_str(), target.c_str(), MOVEFILE_REPLACE_EXISTING);
        else if (!update) remove_app_files(name);
    }
    write_registry();
    return ok;
}

DWORD WINAPI install_thread(void* param) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    std::unique_ptr<InstallJob> job(static_cast<InstallJob*>(param));
    AcquireSRWLockExclusive(&g_install_lock);
    bool ok = false, running = false;
    AcquireSRWLockExclusive(&g_installs_lock);
    const bool started = g_installs.start(job->name);  // false: cancelled while it waited
    ReleaseSRWLockExclusive(&g_installs_lock);
    if (started) {
        PostMessageW(g_main, kInstallStarted, 0, reinterpret_cast<LPARAM>(new std::wstring(job->name)));
        ok = install_into_library(job->source, job->name, job->replaces, &running);
    }
    AcquireSRWLockExclusive(&g_installs_lock);
    g_installs.ended(job->name, ok, running);
    ReleaseSRWLockExclusive(&g_installs_lock);
    ReleaseSRWLockExclusive(&g_install_lock);
    std::error_code ec;
    if (job->temporary) fs::remove(job->source, ec);
    PostMessageW(g_main, kInstalled, 0, reinterpret_cast<LPARAM>(new std::wstring(job->name)));
    CoUninitialize();
    return 0;
}

// ---------------------------------------------------------------------------
// The store: F-Droid's catalog, its icons and its APKs

constexpr wchar_t kRepo[] = L"https://f-droid.org/repo/";
// In 100 ns units; f-droid.org publishes a few times a day.
constexpr unsigned long long kCatalogMaxAge = 6ull * 3600 * 10000000;

fs::path store_dir() { return data_dir() / L"store"; }
fs::path catalog_file() { return store_dir() / L"catalog.tsv"; }
fs::path catalog_etag_file() { return store_dir() / L"catalog.etag"; }
fs::path store_icon_file(const std::string& pkg) { return store_dir() / L"icons" / (utf8_to_wide(pkg) + L".png"); }
fs::path downloads_dir() { return store_dir() / L"downloads"; }

struct StoreApp {
    std::string pkg, icon, apk, sha256;  // icon: a URL; apk: the file's name in the repo
    std::wstring name, summary, version, license;
    std::vector<std::wstring> categories;
    long long code = 0, size = 0, updated = 0;  // versionCode, bytes, ms since 1970
};

// A WinHTTP session; `agent` is the User-Agent its requests send.
HINTERNET open_http_session(const wchar_t* agent) {
    HINTERNET s = WinHttpOpen(agent, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME,
                              WINHTTP_NO_PROXY_BYPASS, 0);
    if (!s) {  // before Windows 8.1
        s = WinHttpOpen(agent, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    }
    if (s) {
        DWORD decompress = WINHTTP_DECOMPRESSION_FLAG_ALL;  // the catalog is 70 MB of JSON, a fifth of that gzipped
        WinHttpSetOption(s, WINHTTP_OPTION_DECOMPRESSION, &decompress, sizeof(decompress));
        DWORD per_server = 8;
        WinHttpSetOption(s, WINHTTP_OPTION_MAX_CONNS_PER_SERVER, &per_server, sizeof(per_server));
        WinHttpSetTimeouts(s, 15000, 15000, 30000, 60000);
    }
    return s;
}

// One WinHTTP session for the store, so the icons' requests reuse their
// connections to f-droid.org rather than each making its own.
HINTERNET http_session() {
    static const HINTERNET session = open_http_session(L"Win.apk");
    return session;
}

// GETs `url` into `out`, through <out>.part so a broken download never looks
// whole. With an `etag` the server may answer 304, not modified. `progress`
// gets the bytes so far and the total (0 when unknown); `headers` are more
// request headers ("Name: value", CRLF between); `session` defaults to the
// store's. Returns the HTTP status, 0 when the server could not be reached or
// the body broke off.
int http_get(const std::wstring& url, const fs::path& out, const std::wstring& etag, std::wstring* new_etag,
             const std::function<void(uint64_t, uint64_t)>& progress, const std::wstring& headers = L"",
             HINTERNET session = nullptr) {
    URL_COMPONENTS uc{};
    uc.dwStructSize = sizeof(uc);
    uc.dwHostNameLength = uc.dwUrlPathLength = uc.dwExtraInfoLength = static_cast<DWORD>(-1);
    if (!WinHttpCrackUrl(url.c_str(), 0, 0, &uc)) return 0;
    const std::wstring host(uc.lpszHostName, uc.dwHostNameLength);
    const std::wstring path = std::wstring(uc.lpszUrlPath, uc.dwUrlPathLength) +
                              std::wstring(uc.lpszExtraInfo, uc.dwExtraInfoLength);
    if (!session) session = http_session();
    if (!session) return 0;
    HINTERNET connection = WinHttpConnect(session, host.c_str(), uc.nPort, 0);
    HINTERNET request = connection ? WinHttpOpenRequest(connection, L"GET", path.c_str(), nullptr, WINHTTP_NO_REFERER,
                                                        WINHTTP_DEFAULT_ACCEPT_TYPES,
                                                        uc.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0)
                                   : nullptr;
    int status = 0;
    std::wstring header = etag.empty() ? L"" : L"If-None-Match: " + etag;
    if (!headers.empty()) header += (header.empty() ? L"" : L"\r\n") + headers;
    if (request &&
        WinHttpSendRequest(request, header.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : header.c_str(),
                           header.empty() ? 0 : static_cast<DWORD>(-1), WINHTTP_NO_REQUEST_DATA, 0, 0, 0) &&
        WinHttpReceiveResponse(request, nullptr)) {
        DWORD code = 0, size = sizeof(code);
        WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER,
                            WINHTTP_HEADER_NAME_BY_INDEX, &code, &size, WINHTTP_NO_HEADER_INDEX);
        status = static_cast<int>(code);
        if (status == 200) {
            DWORD total = 0;
            size = sizeof(total);
            WinHttpQueryHeaders(request, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER,
                                WINHTTP_HEADER_NAME_BY_INDEX, &total, &size, WINHTTP_NO_HEADER_INDEX);
            if (new_etag) {
                wchar_t tag[256] = {};
                size = sizeof(tag);
                if (WinHttpQueryHeaders(request, WINHTTP_QUERY_ETAG, WINHTTP_HEADER_NAME_BY_INDEX, tag, &size,
                                        WINHTTP_NO_HEADER_INDEX)) {
                    *new_etag = tag;
                }
            }
            std::error_code ec;
            fs::create_directories(out.parent_path(), ec);
            const fs::path part = out.wstring() + L".part";
            HANDLE f =
                CreateFileW(part.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
            bool ok = f != INVALID_HANDLE_VALUE;
            uint64_t got = 0;
            std::vector<char> buf(1 << 16);
            for (DWORD n = 0; ok; got += n) {
                ok = WinHttpReadData(request, buf.data(), static_cast<DWORD>(buf.size()), &n) != 0;
                if (!ok || n == 0) break;
                DWORD written = 0;
                ok = WriteFile(f, buf.data(), n, &written, nullptr) && written == n;
                if (ok && progress) progress(got + n, total);
            }
            if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
            ok = ok && got > 0 && MoveFileExW(part.c_str(), out.c_str(), MOVEFILE_REPLACE_EXISTING) != 0;
            if (!ok) {
                fs::remove(part, ec);
                status = 0;
            }
        }
    }
    if (request) WinHttpCloseHandle(request);
    if (connection) WinHttpCloseHandle(connection);
    return status;
}

// The file's SHA-256 in lower-case hex, as F-Droid's index gives it.
std::string sha256_of(const fs::path& file) {
    std::string out;
    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_HASH_HANDLE hash = nullptr;
    HANDLE f = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL,
                           nullptr);
    if (f != INVALID_HANDLE_VALUE &&
        BCRYPT_SUCCESS(BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, nullptr, 0)) &&
        BCRYPT_SUCCESS(BCryptCreateHash(alg, &hash, nullptr, 0, nullptr, 0, 0))) {
        std::vector<UCHAR> buf(1 << 16);
        DWORD n = 0;
        bool ok = true;
        while (ok && ReadFile(f, buf.data(), static_cast<DWORD>(buf.size()), &n, nullptr) && n > 0) {
            ok = BCRYPT_SUCCESS(BCryptHashData(hash, buf.data(), n, 0));
        }
        UCHAR digest[32];
        if (ok && BCRYPT_SUCCESS(BCryptFinishHash(hash, digest, sizeof(digest), 0))) {
            for (const UCHAR b : digest) {
                char hex[3];
                std::snprintf(hex, sizeof hex, "%02x", b);
                out += hex;
            }
        }
    }
    if (hash) BCryptDestroyHash(hash);
    if (alg) BCryptCloseAlgorithmProvider(alg, 0);
    if (f != INVALID_HANDLE_VALUE) CloseHandle(f);
    return out;
}

// A pull parser for the one JSON file the store reads, F-Droid's
// index-v1.json: it walks the 70 MB once without building a tree, and the
// callers keep only the fields the store shows.
struct Json {
    const char* p;
    const char* end;
    bool ok = true;

    void ws() {
        while (p < end && (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t')) ++p;
    }
    bool eat(char c) {
        ws();
        if (p >= end || *p != c) return false;
        ++p;
        return true;
    }
    unsigned hex4() {
        unsigned v = 0;
        for (int i = 0; i < 4; ++i, ++p) {
            if (p >= end || !isxdigit(static_cast<unsigned char>(*p))) {
                ok = false;
                return 0;
            }
            const char c = *p;
            v = v * 16 + static_cast<unsigned>(c >= '0' && c <= '9' ? c - '0' : (c | 0x20) - 'a' + 10);
        }
        return v;
    }
    static void put_utf8(std::string* out, unsigned cp) {
        if (cp < 0x80) {
            *out += static_cast<char>(cp);
        } else if (cp < 0x800) {
            *out += static_cast<char>(0xC0 | (cp >> 6));
            *out += static_cast<char>(0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            *out += static_cast<char>(0xE0 | (cp >> 12));
            *out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            *out += static_cast<char>(0x80 | (cp & 0x3F));
        } else {
            *out += static_cast<char>(0xF0 | (cp >> 18));
            *out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
            *out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
            *out += static_cast<char>(0x80 | (cp & 0x3F));
        }
    }
    // A string with its escapes decoded; `out` may be null to skip it.
    void str(std::string* out) {
        if (!eat('"')) {
            ok = false;
            return;
        }
        while (ok && p < end && *p != '"') {
            if (*p != '\\') {
                if (out) *out += *p;
                ++p;
                continue;
            }
            if (++p >= end) break;
            const char e = *p++;
            if (e == 'u') {
                unsigned cp = hex4();
                if (cp >= 0xD800 && cp < 0xDC00 && end - p >= 6 && p[0] == '\\' && p[1] == 'u') {
                    p += 2;
                    const unsigned lo = hex4();
                    cp = lo >= 0xDC00 && lo < 0xE000 ? 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00) : 0xFFFD;
                }
                if (out) put_utf8(out, cp);
            } else if (out) {
                *out += e == 'n' ? '\n' : e == 't' ? '\t' : e == 'r' ? '\r' : e == 'b' ? '\b' : e == 'f' ? '\f' : e;
            }
        }
        if (p >= end) ok = false;
        else ++p;
    }
    // A string, number, true, false or null, as its text.
    void scalar(std::string* out) {
        ws();
        if (p < end && *p == '"') return str(out);
        const char* from = p;
        while (p < end && !strchr(",}] \n\r\t", *p)) ++p;
        if (p == from) ok = false;
        else if (out) out->assign(from, p);
    }
    template <class Each>
    void object(Each each) {
        if (!eat('{')) {
            ok = false;
            return;
        }
        if (eat('}')) return;
        std::string key;
        do {
            key.clear();
            str(&key);
            if (!ok || !eat(':')) {
                ok = false;
                return;
            }
            each(key);
        } while (ok && eat(','));
        if (ok && !eat('}')) ok = false;
    }
    template <class Each>
    void array(Each each) {
        if (!eat('[')) {
            ok = false;
            return;
        }
        if (eat(']')) return;
        do each();
        while (ok && eat(','));
        if (ok && !eat(']')) ok = false;
    }
    void skip() {
        ws();
        if (p < end && *p == '{') object([this](const std::string&) { skip(); });
        else if (p < end && *p == '[') array([this] { skip(); });
        else scalar(nullptr);
    }
    std::vector<std::string> strings() {
        std::vector<std::string> out;
        array([&] {
            out.emplace_back();
            str(&out.back());
        });
        return out;
    }
};

// One APK of an app in the index.
struct IndexApk {
    std::string file, sha256, version;
    long long code = 0, size = 0;
};

// The fields of index-v1.json's app entry the store keeps; the names and
// summaries per language come from `localized` when the entry has none of
// its own.
struct IndexApp {
    std::string pkg, name, summary, icon, license, suggested, updated;
    std::vector<std::string> categories;
};

void read_index_app(Json& j, IndexApp* a) {
    std::string top_icon, any_icon_lang, any_icon;
    std::map<std::string, std::pair<std::string, std::string>> names;  // language: name, summary
    j.object([&](const std::string& k) {
        if (k == "packageName") j.str(&a->pkg);
        else if (k == "name") j.str(&a->name);
        else if (k == "summary") j.str(&a->summary);
        else if (k == "icon") j.str(&top_icon);
        else if (k == "license") j.str(&a->license);
        else if (k == "suggestedVersionCode") j.scalar(&a->suggested);
        else if (k == "lastUpdated") j.scalar(&a->updated);
        else if (k == "categories") a->categories = j.strings();
        else if (k == "localized") {
            j.object([&](const std::string& lang) {
                std::string name, summary, icon;
                j.object([&](const std::string& f) {
                    if (f == "name") j.str(&name);
                    else if (f == "summary") j.str(&summary);
                    else if (f == "icon") j.str(&icon);
                    else j.skip();
                });
                if (!icon.empty() && (any_icon.empty() || lang == "en-US")) any_icon_lang = lang, any_icon = icon;
                names[lang] = {name, summary};
            });
        } else {
            j.skip();
        }
    });
    auto pick = [&](std::string* out, bool summary, std::initializer_list<const char*> langs, bool any) {
        for (const char* lang : langs) {
            const auto it = names.find(lang);
            const std::string& v = it == names.end() ? std::string() : summary ? it->second.second : it->second.first;
            if (out->empty() && !v.empty()) *out = v;
        }
        for (const auto& n : names) {
            if (!any || !out->empty()) break;
            *out = summary ? n.second.second : n.second.first;
        }
    };
    pick(&a->name, false, {"en-US", "en", "en-GB"}, true);
    pick(&a->summary, true, {"en-US", "en", "en-GB"}, true);
    if (!top_icon.empty()) a->icon = wide_to_utf8(kRepo) + "icons-240/" + top_icon;
    else if (!any_icon.empty()) a->icon = wide_to_utf8(kRepo) + a->pkg + "/" + any_icon_lang + "/" + any_icon;
}

// An APK entry; false when it cannot run here (no arm64-v8a code, where it has native code) or has no SHA-256.
bool read_index_apk(Json& j, IndexApk* apk) {
    std::string hash_type, code, size;
    std::vector<std::string> abis;
    j.object([&](const std::string& k) {
        if (k == "apkName") j.str(&apk->file);
        else if (k == "hash") j.str(&apk->sha256);
        else if (k == "hashType") j.str(&hash_type);
        else if (k == "versionName") j.str(&apk->version);
        else if (k == "versionCode") j.scalar(&code);
        else if (k == "size") j.scalar(&size);
        else if (k == "nativecode") abis = j.strings();
        else j.skip();
    });
    apk->code = std::atoll(code.c_str());
    apk->size = std::atoll(size.c_str());
    const bool runs = abis.empty() || std::find(abis.begin(), abis.end(), "arm64-v8a") != abis.end();
    return runs && hash_type == "sha256" && apk->sha256.size() == 64 && !apk->file.empty();
}

std::string tsv_field(std::string s) {
    for (char& c : s) {
        if (c == '\t' || c == '\n' || c == '\r') c = ' ';
    }
    return s;
}

// Its own header, so a catalog another build wrote is fetched again.
constexpr const char* kCatalogHeader = "winapk-catalog 2 arm64";

// Turns index-v1.json into catalog.tsv: a line per app that can run here,
// with the APK the store installs. That is F-Droid's suggested version (the
// newest, unless it marks a newer one as not ready) and, where an app is
// built per ABI, the build for this PC. Returns the number of apps.
size_t convert_index(const fs::path& json_file, const fs::path& out) {
    std::string json;
    {
        std::ifstream f(json_file, std::ios::binary);
        json.assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    }
    Json j{json.data(), json.data() + json.size()};
    std::vector<IndexApp> apps;
    std::map<std::string, std::vector<IndexApk>> apks;
    j.object([&](const std::string& key) {
        if (key == "apps") {
            j.array([&] {
                apps.emplace_back();
                read_index_app(j, &apps.back());
            });
        } else if (key == "packages") {
            j.object([&](const std::string& pkg) {
                std::vector<IndexApk>& list = apks[pkg];
                j.array([&] {
                    IndexApk apk;
                    if (read_index_apk(j, &apk)) list.push_back(apk);
                });
            });
        } else {
            j.skip();
        }
    });
    if (!j.ok) return 0;
    std::string text = std::string(kCatalogHeader) + "\n";
    size_t count = 0;
    for (const IndexApp& a : apps) {
        const auto it = apks.find(a.pkg);
        if (a.pkg.empty() || a.name.empty() || it == apks.end() || it->second.empty()) continue;
        const long long suggested = std::atoll(a.suggested.c_str());
        const IndexApk* best = nullptr;
        for (int pass = 0; pass < 2 && !best; ++pass) {  // the suggested version or older, else any
            for (const IndexApk& apk : it->second) {
                if (pass == 0 && suggested > 0 && apk.code > suggested) continue;
                if (!best || apk.code > best->code) best = &apk;
            }
        }
        std::string cats;
        for (const std::string& c : a.categories) cats += (cats.empty() ? "" : ";") + c;
        const std::string fields[] = {a.pkg, std::to_string(best->code), std::to_string(best->size), a.updated,
                                      best->file, best->sha256, a.icon, best->version, a.license, cats,
                                      a.name, a.summary};
        for (size_t i = 0; i < std::size(fields); ++i) text += (i ? "\t" : "") + tsv_field(fields[i]);
        text += "\n";
        ++count;
    }
    const fs::path tmp = out.wstring() + L".tmp";
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        f << text;
        if (!f) return 0;
    }
    return count && MoveFileExW(tmp.c_str(), out.c_str(), MOVEFILE_REPLACE_EXISTING) ? count : 0;
}

std::vector<StoreApp> read_catalog() {
    std::vector<StoreApp> out;
    std::ifstream f(catalog_file(), std::ios::binary);
    std::string line;
    if (!std::getline(f, line) || line != kCatalogHeader) return out;
    while (std::getline(f, line)) {
        std::vector<std::string> v;
        for (size_t from = 0;;) {
            const size_t tab = line.find('\t', from);
            v.push_back(line.substr(from, tab == std::string::npos ? std::string::npos : tab - from));
            if (tab == std::string::npos) break;
            from = tab + 1;
        }
        if (v.size() != 12) continue;
        StoreApp a;
        a.pkg = v[0];
        a.code = std::atoll(v[1].c_str());
        a.size = std::atoll(v[2].c_str());
        a.updated = std::atoll(v[3].c_str());
        a.apk = v[4];
        a.sha256 = v[5];
        a.icon = v[6];
        a.version = utf8_to_wide(v[7]);
        a.license = utf8_to_wide(v[8]);
        for (size_t from = 0; from < v[9].size();) {
            const size_t semi = (std::min)(v[9].find(';', from), v[9].size());
            a.categories.push_back(utf8_to_wide(v[9].substr(from, semi - from)));
            from = semi + 1;
        }
        a.name = utf8_to_wide(v[10]);
        a.summary = utf8_to_wide(v[11]);
        out.push_back(std::move(a));
    }
    return out;
}

unsigned long long file_age(const fs::path& p) {
    WIN32_FILE_ATTRIBUTE_DATA a{};
    if (!GetFileAttributesExW(p.c_str(), GetFileExInfoStandard, &a)) return ~0ull;
    FILETIME now;
    GetSystemTimeAsFileTime(&now);
    const auto t = [](const FILETIME& f) {
        return (static_cast<unsigned long long>(f.dwHighDateTime) << 32) | f.dwLowDateTime;
    };
    return t(now) > t(a.ftLastWriteTime) ? t(now) - t(a.ftLastWriteTime) : 0;
}

void touch(const fs::path& p) {
    HANDLE f = CreateFileW(p.c_str(), FILE_WRITE_ATTRIBUTES, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    FILETIME now;
    GetSystemTimeAsFileTime(&now);
    SetFileTime(f, nullptr, nullptr, &now);
    CloseHandle(f);
}

enum { kCatalogList, kCatalogDone, kCatalogFailed };

// Shows the catalog kept from last time, then fetches F-Droid's index when
// that is older than kCatalogMaxAge (or `param` asks to), unless the server
// says it has not changed.
DWORD WINAPI catalog_thread(void* param) {
    const bool force = param != nullptr;
    std::error_code ec;
    fs::create_directories(store_dir(), ec);
    // A kept catalog with no app in it (cut short, emptied) is none: it is fetched whole, without its ETag,
    // which would only get "not modified" for the list that is missing.
    std::vector<StoreApp> kept;
    if (fs::exists(catalog_file(), ec)) kept = read_catalog();
    const bool have = !kept.empty();
    if (have) {
        PostMessageW(g_main, kStoreCatalog, kCatalogList,
                     reinterpret_cast<LPARAM>(new std::vector<StoreApp>(std::move(kept))));
    }
    int result = kCatalogDone;
    if (!have || force || file_age(catalog_file()) > kCatalogMaxAge) {
        std::wstring etag, new_etag;
        if (have) {
            std::ifstream f(catalog_etag_file(), std::ios::binary);
            std::string s;
            std::getline(f, s);
            etag = utf8_to_wide(s);
        }
        const fs::path raw = store_dir() / L"index-v1.json";
        ULONGLONG last = 0;
        const int status = http_get(std::wstring(kRepo) + L"index-v1.json", raw, etag, &new_etag,
                                    [&](uint64_t got, uint64_t) {
                                        if (GetTickCount64() - last < 100) return;
                                        last = GetTickCount64();
                                        PostMessageW(g_main, kStoreProgress, static_cast<WPARAM>(got >> 10), 0);
                                    });
        if (status == 304) {
            touch(catalog_file());
        } else if (status == 200 && convert_index(raw, catalog_file())) {
            std::ofstream(catalog_etag_file(), std::ios::binary | std::ios::trunc) << wide_to_utf8(new_etag);
            PostMessageW(g_main, kStoreCatalog, kCatalogList,
                         reinterpret_cast<LPARAM>(new std::vector<StoreApp>(read_catalog())));
        } else {
            result = kCatalogFailed;
        }
        fs::remove(raw, ec);
    }
    PostMessageW(g_main, kStoreCatalog, result, 0);
    return 0;
}

enum { kIconFailed, kIconSaved, kIconDropped };

// Icons are fetched by a few threads from a queue, the newest request first:
// the cards on screen now come before those scrolled past.
SRWLOCK g_icon_lock = SRWLOCK_INIT;
CONDITION_VARIABLE g_icon_wake = CONDITION_VARIABLE_INIT;
std::vector<std::pair<std::string, std::string>> g_icon_queue;  // package, URL
int g_icon_threads = 0;

DWORD WINAPI icon_thread(void*) {
    for (;;) {
        AcquireSRWLockExclusive(&g_icon_lock);
        while (g_icon_queue.empty()) SleepConditionVariableSRW(&g_icon_wake, &g_icon_lock, INFINITE, 0);
        const auto [pkg, url] = g_icon_queue.back();
        g_icon_queue.pop_back();
        ReleaseSRWLockExclusive(&g_icon_lock);
        const bool ok = http_get(utf8_to_wide(url), store_icon_file(pkg), L"", nullptr, {}) == 200;
        PostMessageW(g_main, kStoreIcon, ok ? kIconSaved : kIconFailed, reinterpret_cast<LPARAM>(new std::string(pkg)));
    }
}

void request_icon(const std::string& pkg, const std::string& url) {
    AcquireSRWLockExclusive(&g_icon_lock);
    g_icon_queue.emplace_back(pkg, url);
    if (g_icon_queue.size() > 48) {  // long scrolled past: asked for again if it comes back into view
        PostMessageW(g_main, kStoreIcon, kIconDropped,
                     reinterpret_cast<LPARAM>(new std::string(g_icon_queue.front().first)));
        g_icon_queue.erase(g_icon_queue.begin());
    }
    if (g_icon_threads < 4) {
        if (HANDLE t = CreateThread(nullptr, 0, icon_thread, nullptr, 0, nullptr)) {
            CloseHandle(t);
            ++g_icon_threads;
        }
    }
    ReleaseSRWLockExclusive(&g_icon_lock);
    WakeConditionVariable(&g_icon_wake);
}

struct Download {
    std::string pkg, sha256;
    std::wstring url, label;
    fs::path file;
};

SRWLOCK g_download_lock = SRWLOCK_INIT;  // one download at a time; the installs after them go one at a time too

DWORD WINAPI download_thread(void* param) {
    std::unique_ptr<Download> d(static_cast<Download*>(param));
    AcquireSRWLockExclusive(&g_download_lock);
    int shown = -1;
    const int status = http_get(d->url, d->file, L"", nullptr, [&](uint64_t got, uint64_t total) {
        const int pct = total ? static_cast<int>((std::min)(got * 100 / total, uint64_t{99})) : 0;
        if (pct == shown) return;
        shown = pct;
        PostMessageW(g_main, kStoreDownload, static_cast<WPARAM>(pct),
                     reinterpret_cast<LPARAM>(new std::string(d->pkg)));
    });
    // The APK must be the one F-Droid's index names; anything else is not installed.
    const bool ok = status == 200 && sha256_of(d->file) == d->sha256;
    std::error_code ec;
    if (!ok) fs::remove(d->file, ec);
    ReleaseSRWLockExclusive(&g_download_lock);
    PostMessageW(g_main, kStoreDownloaded, ok, reinterpret_cast<LPARAM>(d.release()));
    return 0;
}

// ---------------------------------------------------------------------------
// Software updates: the newest release on GitHub and its installer
//
// Releases are pre-releases tagged v0.2.0-preview, so /releases/latest (which
// skips them) will not do: the list is read and its highest version taken.
// The request says nothing about the user, and its answer is kept with its ETag:
// a check that finds nothing new costs GitHub a 304, and the library shows the
// last result at start without asking again within the day.

constexpr wchar_t kReleasesUrl[] = L"https://api.github.com/repos/" WINAPK_REPO "/releases?per_page=20";
constexpr wchar_t kReleasesPage[] = L"https://github.com/" WINAPK_REPO "/releases";
constexpr wchar_t kSetupAsset[] = L"Win.apk-Setup-arm64.exe";  // winapk.iss

// A release asset's fit for this PC, 0 for none.
int setup_asset_rank(const std::wstring& name) { return name == kSetupAsset ? 1 : 0; }
constexpr DWORD kUpdateInterval = 24 * 3600;  // seconds between automatic checks

fs::path updates_dir() { return data_dir() / L"updates"; }
fs::path releases_file() { return updates_dir() / L"releases.json"; }
fs::path releases_etag_file() { return updates_dir() / L"releases.etag"; }
// The installer of one version, so that a download kept from an older release is never run for a newer one.
fs::path setup_file(const std::wstring& v) {
    return updates_dir() / (L"Win.apk-Setup-" + file_name_safe(v) + L".exe");
}

// GitHub's API wants a User-Agent; this one names the program and nothing about the user.
HINTERNET updater_session() {
    static const HINTERNET session = open_http_session(L"Win.apk-updater");
    return session;
}

// 0.2.0 of "v0.2.0-preview": the numbers before any -suffix or +build.
std::vector<unsigned> version_numbers(const std::wstring& v) {
    std::vector<unsigned> out;
    size_t i = !v.empty() && (v[0] == L'v' || v[0] == L'V') ? 1 : 0;
    while (i < v.size() && iswdigit(v[i])) {
        unsigned n = 0;
        while (i < v.size() && iswdigit(v[i])) n = n * 10 + static_cast<unsigned>(v[i++] - L'0');
        out.push_back(n);
        if (i >= v.size() || v[i] != L'.') break;
        ++i;
    }
    return out;
}

// <0, 0, >0 as a is older than, the same as or newer than b; missing numbers count as 0 (0.2 = 0.2.0).
int compare_versions(const std::wstring& a, const std::wstring& b) {
    const std::vector<unsigned> x = version_numbers(a), y = version_numbers(b);
    for (size_t i = 0; i < (std::max)(x.size(), y.size()); ++i) {
        const unsigned p = i < x.size() ? x[i] : 0, q = i < y.size() ? y[i] : 0;
        if (p != q) return p < q ? -1 : 1;
    }
    return 0;
}

// Release notes in Markdown as plain text: no headings' #, no ** or `, "• " for list items,
// [text](link) as its text, and no runs of blank lines.
std::string markdown_to_text(const std::string& md) {
    std::string out;
    size_t at = 0;
    int blanks = 0;
    while (at <= md.size()) {
        size_t end = md.find('\n', at);
        if (end == std::string::npos) end = md.size();
        std::string line = md.substr(at, end - at);
        at = end + 1;
        if (!line.empty() && line.back() == '\r') line.pop_back();
        size_t lead = line.find_first_not_of(" \t");
        if (lead == std::string::npos) {
            if (!out.empty() && blanks++ == 0) out += '\n';
            continue;
        }
        blanks = 0;
        std::string indent(lead > 0 ? 2 * ((lead + 1) / 2) : 0, ' ');
        line.erase(0, lead);
        if (line[0] == '#') {
            line.erase(0, line.find_first_not_of('#'));
        } else if ((line[0] == '-' || line[0] == '*' || line[0] == '+') && line.size() > 1 && line[1] == ' ') {
            line = "\xE2\x80\xA2 " + line.substr(2);  // a bullet
        } else if (line.rfind("---", 0) == 0 && line.find_first_not_of('-') == std::string::npos) {
            continue;  // a rule
        }
        std::string plain;
        for (size_t i = 0; i < line.size(); ++i) {
            if (line.compare(i, 2, "**") == 0 || line.compare(i, 2, "__") == 0) {
                ++i;
                continue;
            }
            if (line[i] == '`') continue;
            if (line[i] == '[') {  // [text](url) keeps the text
                const size_t close = line.find("](", i);
                const size_t paren = close == std::string::npos ? close : line.find(')', close);
                if (paren != std::string::npos) {
                    plain += line.substr(i + 1, close - i - 1);
                    i = paren;
                    continue;
                }
            }
            plain += line[i];
        }
        const size_t first = plain.find_first_not_of(' ');
        if (first == std::string::npos) continue;
        if (!out.empty()) out += '\n';
        out += indent + plain.substr(first);
    }
    while (!out.empty() && out.back() == '\n') out.pop_back();
    return out;
}

struct Release {
    std::wstring tag, version, notes, page;  // version: the tag without its v; notes: plain text
    std::wstring url;                        // Win.apk-Setup.exe's download, empty when the release has none
    std::string digest;                      // the asset's "sha256:<hex>", when GitHub gives one
    uint64_t size = 0;                       // the asset's bytes
    FILETIME published{};
};

// "2026-10-01T12:00:00Z" as a FILETIME; zero when it is not one.
FILETIME iso_filetime(const std::string& s) {
    SYSTEMTIME st{};
    FILETIME ft{};
    unsigned y = 0, mo = 0, d = 0, h = 0, mi = 0, se = 0;
    if (std::sscanf(s.c_str(), "%4u-%2u-%2uT%2u:%2u:%2u", &y, &mo, &d, &h, &mi, &se) == 6) {
        st = {static_cast<WORD>(y), static_cast<WORD>(mo), 0, static_cast<WORD>(d),
              static_cast<WORD>(h), static_cast<WORD>(mi), static_cast<WORD>(se), 0};
        if (!SystemTimeToFileTime(&st, &ft)) ft = {};
    }
    return ft;
}

// The newest release in GitHub's list that is not a draft, with its installer asset; `found` says whether there
// is one. Returns whether the list could be read.
bool newest_release(const std::string& json, Release* best, bool* found_out) {
    Json j{json.data(), json.data() + json.size()};
    bool found = false;
    auto text = [&j](std::string* out) {  // a string, or skips a null
        j.ws();
        if (j.p < j.end && *j.p == '"') j.str(out);
        else j.skip();
    };
    j.array([&] {
        Release r;
        bool draft = false;
        int asset_rank = 0;  // setup_asset_rank of the installer taken so far
        j.object([&](const std::string& key) {
            std::string v;
            if (key == "draft") {
                j.scalar(&v);
                draft = v == "true";
            } else if (key == "tag_name") {
                text(&v);
                r.tag = utf8_to_wide(v);
            } else if (key == "body") {
                text(&v);
                r.notes = utf8_to_wide(markdown_to_text(v));
            } else if (key == "html_url") {
                text(&v);
                r.page = utf8_to_wide(v);
            } else if (key == "published_at") {
                text(&v);
                r.published = iso_filetime(v);
            } else if (key == "assets") {
                j.array([&] {
                    std::string name, url, size, digest;
                    j.object([&](const std::string& k) {
                        if (k == "name") text(&name);
                        else if (k == "browser_download_url") text(&url);
                        else if (k == "size") j.scalar(&size);
                        else if (k == "digest") text(&digest);
                        else j.skip();
                    });
                    const int rank = setup_asset_rank(utf8_to_wide(name));
                    if (rank > asset_rank && !url.empty()) {
                        asset_rank = rank;
                        r.url = utf8_to_wide(url);
                        r.size = std::strtoull(size.c_str(), nullptr, 10);
                        r.digest = digest;
                    }
                });
            } else {
                j.skip();
            }
        });
        if (!j.ok || draft || version_numbers(r.tag).empty()) return;
        if (!found || compare_versions(r.tag, best->tag) > 0) {
            r.version = r.tag.substr(r.tag[0] == L'v' || r.tag[0] == L'V' ? 1 : 0);
            *best = std::move(r);
            found = true;
        }
    });
    *found_out = j.ok && found;
    return j.ok;
}

// One check, from the library's thread to the update thread and back (kUpdateChecked): what was asked
// and what came of it (updates.h), and the newest release when one was found.
struct UpdateCheck : winapk::launcher::CheckResult {
    Release latest;
};

DWORD WINAPI update_check_thread(void* param) {
    std::unique_ptr<UpdateCheck> c(static_cast<UpdateCheck*>(param));
    std::error_code ec;
    const bool have = fs::exists(releases_file(), ec);
    bool read = have && !c->fetch;
    if (c->fetch) {
        std::wstring etag, new_etag;
        if (have) {
            std::ifstream f(releases_etag_file(), std::ios::binary);
            std::string s;
            std::getline(f, s);
            etag = utf8_to_wide(s);
        }
        const int status = http_get(kReleasesUrl, releases_file(), etag, &new_etag, {},
                                    L"Accept: application/vnd.github+json", updater_session());
        if (status == 200) {
            std::ofstream(releases_etag_file(), std::ios::binary | std::ios::trunc) << wide_to_utf8(new_etag);
        }
        c->reached = status == 200 || (status == 304 && have);
        read = c->reached || have;  // unreached, the answer kept still shows a newer version found before
    }
    if (read) {
        std::ifstream f(releases_file(), std::ios::binary);
        const std::string json((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        c->ok = newest_release(json, &c->latest, &c->found);
    }
    UpdateCheck* out = c.release();
    if (!PostMessageW(g_main, kUpdateChecked, 0, reinterpret_cast<LPARAM>(out))) delete out;
    return 0;
}

// Downloads the release's Win.apk-Setup.exe to updates\, and keeps it only when it is whole: the size GitHub
// gives, and its SHA-256 when GitHub gives that too.
DWORD WINAPI update_download_thread(void* param) {
    std::unique_ptr<Release> r(static_cast<Release*>(param));
    int shown = -1;
    const fs::path file = setup_file(r->version);
    const int status = http_get(r->url, file, L"", nullptr, [&](uint64_t got, uint64_t total) {
        if (!total) total = r->size;
        const int pct = total ? static_cast<int>((std::min)(got * 100 / total, uint64_t{99})) : 0;
        if (pct == shown) return;
        shown = pct;
        PostMessageW(g_main, kUpdateProgress, static_cast<WPARAM>(pct), 0);
    }, L"", updater_session());
    std::error_code ec;
    bool ok = status == 200 && r->size > 0 && fs::file_size(file, ec) == r->size && !ec;
    if (ok && r->digest.rfind("sha256:", 0) == 0) ok = "sha256:" + sha256_of(file) == r->digest;
    if (!ok) fs::remove(file, ec);
    PostMessageW(g_main, kUpdateDownloaded, ok, 0);
    return 0;
}

// ---------------------------------------------------------------------------
// Theme and drawing
//
// Spacing follows a 4 px scale (4, 8, 12, 16, 24, 32), radii are kRadius*,
// and every colour is one of the theme's tokens, so light and dark stay
// consistent. Everything is in device-independent pixels through px().

constexpr float kRadiusControl = 6, kRadiusCard = 8, kRadiusTile = 10;

struct Theme {
    bool dark;
    Color bg;           // the window: the navigation pane and the status bar
    Color layer;        // the content area, a surface over bg (Windows 11's layer)
    Color card;         // cards and controls on the layer
    Color hover;        // a hovered control or tile
    Color border;       // outlines of cards and controls
    Color divider;      // hairlines inside a card
    Color text, text2;  // primary and secondary text
    Color text3;        // placeholders and disabled text
    Color accent;       // the one accent colour: selection, primary buttons, progress
    Color accent_text;  // text on the accent
    Color danger, success;
};

Color hex(uint32_t v, BYTE a = 255) { return Color(a, (v >> 16) & 255, (v >> 8) & 255, v & 255); }
Color with_alpha(const Color& c, BYTE a) { return Color(a, c.GetR(), c.GetG(), c.GetB()); }
COLORREF colorref(const Color& c) { return RGB(c.GetR(), c.GetG(), c.GetB()); }
Color mix(const Color& a, const Color& b, float t) {
    auto m = [t](BYTE x, BYTE y) { return static_cast<BYTE>(x + (y - x) * t); };
    return Color(m(a.GetA(), b.GetA()), m(a.GetR(), b.GetR()), m(a.GetG(), b.GetG()), m(a.GetB(), b.GetB()));
}
const Color kWhite(255, 255, 255, 255), kBlack(255, 0, 0, 0);

Theme g_t;

bool windows_uses_dark() {
    DWORD v = 1, size = sizeof(v);
    RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                 L"AppsUseLightTheme", RRF_RT_REG_DWORD, nullptr, &v, &size);
    return v == 0;
}

void pick_theme() {
    const DWORD pref = setting(L"Theme", 0);  // 0 as Windows, 1 light, 2 dark
    const bool dark = pref == 2 || (pref == 0 && windows_uses_dark());
    // Windows 11's own neutrals; the accent is the blue of the program's icon,
    // on dark backgrounds the lighter tint Windows uses there, with dark text.
    g_t = dark ? Theme{true,         hex(0x202020), hex(0x272727), hex(0x2D2D2D), hex(0x383838), hex(0x3D3D3D),
                       hex(0x363636), hex(0xFFFFFF), hex(0xC9C9C9), hex(0x8A8A8A), hex(0x4CC2FF), hex(0x0B1A26),
                       hex(0xFF99A4), hex(0x6CCB5F)}
               : Theme{false,        hex(0xF3F3F3), hex(0xFBFBFB), hex(0xFFFFFF), hex(0xEDEDED), hex(0xE1E1E1),
                       hex(0xECECEC), hex(0x1B1B1B), hex(0x5C5C5C), hex(0x9A9A9A), hex(0x1569C7), hex(0xFFFFFF),
                       hex(0xC42B1C), hex(0x0F7B0F)};
}

// Dark title bar and, on Windows 11, a caption the colour of the window.
void theme_window(HWND hwnd) {
    const BOOL dark = g_t.dark;
    DwmSetWindowAttribute(hwnd, 20 /* DWMWA_USE_IMMERSIVE_DARK_MODE */, &dark, sizeof(dark));
    const COLORREF caption = colorref(g_t.bg);
    DwmSetWindowAttribute(hwnd, 35 /* DWMWA_CAPTION_COLOR */, &caption, sizeof(caption));
}

// Popup menus follow the theme through uxtheme's unnamed SetPreferredAppMode
// and FlushMenuThemes (ordinals 135 and 136, Windows 10 1903 and later).
void theme_menus() {
    static HMODULE ux = LoadLibraryExW(L"uxtheme.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    if (!ux) return;
    using SetMode = int(WINAPI*)(int);
    using Flush = void(WINAPI*)();
    auto set_mode = reinterpret_cast<SetMode>(reinterpret_cast<void*>(GetProcAddress(ux, MAKEINTRESOURCEA(135))));
    auto flush = reinterpret_cast<Flush>(reinterpret_cast<void*>(GetProcAddress(ux, MAKEINTRESOURCEA(136))));
    if (set_mode) set_mode(g_t.dark ? 2 : 3);  // force dark, force light
    if (flush) flush();
}

// The type scale: title (page and sheet headings), body, caption, and the
// tiles' labels; glyphs come in the sizes the controls use.
struct Fonts {
    std::unique_ptr<Gdiplus::Font> title, body, body_semi, caption, caption_semi, tile, monogram, monogram_small,
        glyph12, glyph16, glyph24, glyph48, mono;
} g_f;

// The first of `families` that is installed, else the generic one.
std::unique_ptr<Gdiplus::Font> font_from(std::initializer_list<const wchar_t*> families,
                                         const Gdiplus::FontFamily* generic, float size, int style) {
    for (const wchar_t* name : families) {
        Gdiplus::FontFamily family(name);
        if (family.GetLastStatus() == Gdiplus::Ok && family.IsAvailable()) {
            return std::make_unique<Gdiplus::Font>(&family, px(size), style, Gdiplus::UnitPixel);
        }
    }
    return std::make_unique<Gdiplus::Font>(generic, px(size), style, Gdiplus::UnitPixel);
}

std::unique_ptr<Gdiplus::Font> font(float size, bool semibold = false) {
    if (semibold) {
        Gdiplus::FontFamily family(L"Segoe UI Semibold");
        if (family.GetLastStatus() == Gdiplus::Ok && family.IsAvailable()) {
            return std::make_unique<Gdiplus::Font>(&family, px(size), Gdiplus::FontStyleRegular, Gdiplus::UnitPixel);
        }
    }
    return font_from({L"Segoe UI"}, Gdiplus::FontFamily::GenericSansSerif(), size,
                     semibold ? Gdiplus::FontStyleBold : Gdiplus::FontStyleRegular);
}

std::unique_ptr<Gdiplus::Font> glyph_font(float size) {
    return font_from({L"Segoe Fluent Icons", L"Segoe MDL2 Assets"}, Gdiplus::FontFamily::GenericSansSerif(), size,
                     Gdiplus::FontStyleRegular);
}

// Segoe Fluent Icons / MDL2 Assets code points.
constexpr wchar_t kGlyphSettings[] = L"\uE713", kGlyphDownload[] = L"\uE896", kGlyphSearch[] = L"\uE721",
                  kGlyphChevron[] = L"\uE70D", kGlyphCheck[] = L"\uE73E", kGlyphError[] = L"\uE783",
                  kGlyphClock[] = L"\uE823", kGlyphStop[] = L"\uE71A", kGlyphLibrary[] = L"\uE8F1",
                  kGlyphStore[] = L"\uE719", kGlyphAdd[] = L"\uE710", kGlyphRefresh[] = L"\uE72C",
                  kGlyphClear[] = L"\uE894", kGlyphLog[] = L"\uE8A5", kGlyphPackage[] = L"\uE7B8",
                  kGlyphChevronUp[] = L"\uE70E", kGlyphBack[] = L"\uE72B", kGlyphForward[] = L"\uE72A",
                  kGlyphContact[] = L"\uE77B", kGlyphPlay[] = L"\uE768", kGlyphInfo[] = L"\uE946",
                  kGlyphDelete[] = L"\uE74D";

void make_fonts() {
    g_f.title = font(20, true);
    g_f.body = font(14);
    g_f.body_semi = font(14, true);
    g_f.caption = font(12);
    g_f.caption_semi = font(12, true);
    g_f.tile = font(13);
    g_f.monogram = font(30, true);
    g_f.monogram_small = font(26, true);
    g_f.glyph12 = glyph_font(12);
    g_f.glyph16 = glyph_font(16);
    g_f.glyph24 = glyph_font(24);
    g_f.glyph48 = glyph_font(48);
    g_f.mono = font_from({L"Cascadia Mono", L"Consolas"}, Gdiplus::FontFamily::GenericMonospace(), 12,
                         Gdiplus::FontStyleRegular);
    if (g_mono_font) DeleteObject(g_mono_font);
    g_mono_font = CreateFontW(-ipx(13), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                              CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, FIXED_PITCH, L"Consolas");
    if (g_edit_font) DeleteObject(g_edit_font);
    g_edit_font = CreateFontW(-ipx(14), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS,
                              CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, L"Segoe UI");
}

RectF inset(const RectF& r, float dx, float dy) { return RectF(r.X + dx, r.Y + dy, r.Width - 2 * dx, r.Height - 2 * dy); }
bool contains(const RectF& r, float x, float y) { return x >= r.X && y >= r.Y && x < r.GetRight() && y < r.GetBottom(); }

void rounded(GraphicsPath& p, const RectF& r, float rad) {
    rad = (std::min)(rad, (std::min)(r.Width, r.Height) / 2);
    const float d = rad * 2;
    if (d <= 0) {
        p.AddRectangle(r);
        return;
    }
    p.AddArc(r.X, r.Y, d, d, 180, 90);
    p.AddArc(r.GetRight() - d, r.Y, d, d, 270, 90);
    p.AddArc(r.GetRight() - d, r.GetBottom() - d, d, d, 0, 90);
    p.AddArc(r.X, r.GetBottom() - d, d, d, 90, 90);
    p.CloseFigure();
}

void fill_round(Graphics& g, const RectF& r, float rad, const Color& c) {
    GraphicsPath p;
    rounded(p, r, rad);
    Gdiplus::SolidBrush b(c);
    g.FillPath(&b, &p);
}

void stroke_round(Graphics& g, const RectF& r, float rad, const Color& c, float width = 1) {
    GraphicsPath p;
    rounded(p, inset(r, width / 2, width / 2), rad);
    Gdiplus::Pen pen(c, width);
    g.DrawPath(&pen, &p);
}

enum Align { kLeft, kCenter, kRight };

void draw_text(Graphics& g, const std::wstring& s, const Gdiplus::Font* f, const Color& c, const RectF& box,
               Align align = kLeft, bool wrap = false, bool vcenter = true) {
    Gdiplus::StringFormat sf;
    sf.SetAlignment(align == kLeft     ? Gdiplus::StringAlignmentNear
                    : align == kCenter ? Gdiplus::StringAlignmentCenter
                                       : Gdiplus::StringAlignmentFar);
    sf.SetLineAlignment(vcenter ? Gdiplus::StringAlignmentCenter : Gdiplus::StringAlignmentNear);
    // One character (an icon glyph, a monogram, "!") is never trimmed: in a box as wide as its font is
    // high, GDI+'s padding either side would turn it into an ellipsis the icon font has no glyph for (or,
    // in some GDI+, into nothing). Centred, it gets the room either side.
    const bool one = s.size() == 1;
    const RectF r = one && align == kCenter ? inset(box, -f->GetSize(), 0) : box;
    sf.SetTrimming(one    ? Gdiplus::StringTrimmingNone
                   : wrap ? Gdiplus::StringTrimmingEllipsisWord
                          : Gdiplus::StringTrimmingEllipsisCharacter);
    sf.SetFormatFlags(wrap ? Gdiplus::StringFormatFlagsLineLimit
                           : Gdiplus::StringFormatFlagsNoWrap | (one ? Gdiplus::StringFormatFlagsNoClip : 0));
    Gdiplus::SolidBrush b(c);
    g.DrawString(s.c_str(), static_cast<INT>(s.size()), f, r, &sf, &b);
}

void draw_image(Graphics& g, Gdiplus::Image* img, const RectF& r) { g.DrawImage(img, r); }

float text_width(Graphics& g, const std::wstring& s, const Gdiplus::Font* f) {
    RectF out;
    g.MeasureString(s.c_str(), static_cast<INT>(s.size()), f, Gdiplus::PointF(0, 0), &out);
    return out.Width;
}

float text_height(Graphics& g, const std::wstring& s, const Gdiplus::Font* f, float width) {
    Gdiplus::StringFormat sf;
    RectF out;
    g.MeasureString(s.c_str(), static_cast<INT>(s.size()), f, RectF(0, 0, width, 100000), &sf, &out);
    return out.Height;
}

void spinner(Graphics& g, const RectF& r, const Color& c, float width) {
    const float a = static_cast<float>(GetTickCount64() % 900) * 360.0f / 900.0f;
    Gdiplus::Pen track(with_alpha(c, 50), width);
    g.DrawEllipse(&track, r);
    Gdiplus::Pen pen(c, width);
    pen.SetStartCap(Gdiplus::LineCapRound);
    pen.SetEndCap(Gdiplus::LineCapRound);
    g.DrawArc(&pen, r, a, 100);
}

void draw_monogram(Graphics& g, const RectF& r, const std::wstring& label, const Gdiplus::Font* f) {
    GraphicsPath p;
    rounded(p, r, r.Width * 0.22f);
    Gdiplus::LinearGradientBrush b(r, mix(g_t.accent, kWhite, 0.18f), mix(g_t.accent, kBlack, 0.22f),
                                   Gdiplus::LinearGradientModeForwardDiagonal);
    g.FillPath(&b, &p);
    const std::wstring ch(1, label.empty() ? L'?' : static_cast<wchar_t>(towupper(label[0])));
    draw_text(g, ch, f, kWhite, r, kCenter);
}

// A standard, accent (primary) or destructive button; `glyph` goes before the text.
void draw_button(Graphics& g, const RectF& r, const std::wstring& text, bool hot, bool primary, bool destructive,
                 const wchar_t* glyph = nullptr) {
    const Color fill = primary ? (hot ? mix(g_t.accent, g_t.dark ? kWhite : kBlack, 0.1f) : g_t.accent)
                               : (hot ? g_t.hover : g_t.card);
    fill_round(g, r, px(kRadiusControl), fill);
    if (!primary) stroke_round(g, r, px(kRadiusControl), g_t.border);
    const Color ink = primary ? g_t.accent_text : destructive ? g_t.danger : g_t.text;
    const Gdiplus::Font* f = primary ? g_f.body_semi.get() : g_f.body.get();
    if (!glyph) {
        draw_text(g, text, f, ink, r, kCenter);
        return;
    }
    const float tw = text_width(g, text, f), x = r.X + (r.Width - tw - px(22)) / 2;
    draw_text(g, glyph, g_f.glyph12.get(), ink, RectF(x, r.Y, px(16), r.Height), kCenter);
    draw_text(g, text, f, ink, RectF(x + px(22), r.Y, tw + px(4), r.Height));
}

// A square button that is only a glyph (it gets a tooltip).
void draw_icon_button(Graphics& g, const RectF& r, const wchar_t* glyph, bool hot, const Color& ink) {
    if (hot) fill_round(g, r, px(kRadiusControl), g_t.hover);
    draw_text(g, glyph, g_f.glyph16.get(), ink, r, kCenter);
}

// A button's width: its text with room either side, never narrower than Windows' 96.
float button_width(Graphics& g, const std::wstring& text, bool primary) {
    return (std::max)(px(96), text_width(g, text, primary ? g_f.body_semi.get() : g_f.body.get()) + px(32));
}

// Windows' toggle switch.
void draw_switch(Graphics& g, bool on, const RectF& r, bool hot) {
    const Color fill = hot ? mix(g_t.accent, g_t.dark ? kWhite : kBlack, 0.1f) : g_t.accent;
    fill_round(g, r, r.Height / 2, on ? fill : hot ? g_t.hover : g_t.card);
    if (!on) stroke_round(g, r, r.Height / 2, g_t.text2);
    const float k = on ? r.Height - px(8) : r.Height - px(10);
    const float kx = on ? r.GetRight() - k - px(4) : r.X + px(5);
    Gdiplus::SolidBrush knob(on ? g_t.accent_text : g_t.text2);
    g.FillEllipse(&knob, kx, r.Y + (r.Height - k) / 2, k, k);
}

// Windows' check box.
void draw_check_box(Graphics& g, bool on, const RectF& r, bool hot) {
    if (on) {
        fill_round(g, r, px(4), hot ? mix(g_t.accent, g_t.dark ? kWhite : kBlack, 0.1f) : g_t.accent);
        draw_text(g, kGlyphCheck, g_f.glyph12.get(), g_t.accent_text, r, kCenter);
    } else {
        fill_round(g, r, px(4), hot ? g_t.hover : g_t.card);
        stroke_round(g, r, px(4), g_t.text2);
    }
}

using Targets = std::vector<std::pair<RectF, int>>;

// A screen point in an lParam (WM_NCHITTEST's, WM_MOUSEWHEEL's) in the coordinates the window's
// targets and mouse messages use.
// (No window hit-tests its own targets at the moment; the next one that does goes through this.)
[[maybe_unused]] POINT client_point(HWND hwnd, LPARAM screen) {
    POINT pt{GET_X_LPARAM(screen), GET_Y_LPARAM(screen)};
    ScreenToClient(hwnd, &pt);
    return pt;
}

int find_target(const Targets& targets, LPARAM lp) {
    const float x = static_cast<float>(GET_X_LPARAM(lp)), y = static_cast<float>(GET_Y_LPARAM(lp));
    for (const auto& t : targets) {
        if (contains(t.first, x, y)) return t.second;
    }
    return 0;
}

// Hover tracking for the custom-drawn buttons of a window; true when it changed.
bool track_hot(HWND hwnd, LPARAM lp, const Targets& targets, int* hot, bool* tracking) {
    if (!*tracking) {
        TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, hwnd, 0};
        *tracking = TrackMouseEvent(&tme);
    }
    const int now = find_target(targets, lp);
    if (now == *hot) return false;
    *hot = now;
    InvalidateRect(hwnd, nullptr, FALSE);
    return true;
}

// Windows' keyboard focus ring: two strokes, the outer one the text colour.
void draw_focus_ring(Graphics& g, const RectF& r, float rad) {
    stroke_round(g, inset(r, -px(3), -px(3)), rad + px(3), g_t.text, px(2));
    stroke_round(g, inset(r, -px(1), -px(1)), rad + px(1), g_t.layer, px(2));
}

// An indeterminate progress line, as Windows' ProgressBar draws it.
void draw_progress_line(Graphics& g, const RectF& track) {
    fill_round(g, track, track.Height / 2, with_alpha(g_t.accent, 50));
    const float t = static_cast<float>(GetTickCount64() % 1600) / 1600.0f;
    const float seg = track.Width * 0.3f;
    float x0 = track.X + (track.Width + seg) * t - seg, x1 = x0 + seg;
    x0 = (std::max)(x0, track.X);
    x1 = (std::min)(x1, track.GetRight());
    if (x1 > x0) fill_round(g, RectF(x0, track.Y, x1 - x0, track.Height), track.Height / 2, g_t.accent);
}

// Renders a buffered WM_PAINT through `draw(graphics, width, height)`.
template <class Draw>
void buffered_paint(HWND hwnd, Draw draw) {
    PAINTSTRUCT ps;
    HDC dc = BeginPaint(hwnd, &ps);
    RECT rc;
    GetClientRect(hwnd, &rc);
    HDC mem = CreateCompatibleDC(dc);
    HBITMAP bmp = CreateCompatibleBitmap(dc, (std::max)(1L, rc.right), (std::max)(1L, rc.bottom));
    HGDIOBJ old = SelectObject(mem, bmp);
    SetLayout(mem, 0);
    {
        Graphics g(mem);
        g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
        g.SetTextRenderingHint(Gdiplus::TextRenderingHintClearTypeGridFit);
        g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
        g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHalf);
        draw(g, static_cast<float>(rc.right), static_cast<float>(rc.bottom));
    }
    BitBlt(dc, 0, 0, rc.right, rc.bottom, mem, 0, 0, SRCCOPY);
    SelectObject(mem, old);
    DeleteObject(bmp);
    DeleteDC(mem);
    EndPaint(hwnd, &ps);
}

// ---------------------------------------------------------------------------
// State

enum StatusKind { kHint, kInfo, kSuccess, kError };

struct Tile {
    int app;           // index into g_apps, -1 for an app still being installed
    std::wstring job;  // that app's name
};

std::vector<App> g_apps;
std::vector<Tile> g_tiles;                 // what the grid shows: new installs first, then the (filtered) apps
struct Job {
    std::wstring name;
    enum State { kWaiting, kInstalling, kDone, kFailed, kCancelled } state = kWaiting;
    ULONGLONG start = 0, end = 0;
    std::wstring file;    // the file it came from, its name until the app's own is known
    bool update = false;  // the library had the app already
    int phase = 0;        // how far the install log says it got (install_phase)
    std::wstring reason;  // why it failed, in a sentence
    std::wstring label;   // the app's own name once the install has written it (a new app's)
    std::shared_ptr<Gdiplus::Bitmap> icon;  // the app's icon once the install has written it
};
std::vector<Job> g_jobs;                   // this batch of installs, for the install window
std::map<std::wstring, std::wstring> g_job_labels;  // installs that are not in the library yet: their names to show
HWND g_install_wnd, g_log_wnd, g_log_edit, g_tip;
std::map<std::wstring, std::unique_ptr<Gdiplus::Bitmap>> g_icons;  // rounded, at the grid's size; null: no icon
std::unique_ptr<Gdiplus::Bitmap> g_brand;  // the program's icon, for the navigation pane
std::wstring g_selected;
std::wstring g_status;
StatusKind g_status_kind = kHint;
std::wstring g_status_link;          // the InfoBar's action, a link before its close button
std::function<void()> g_status_act;
float g_scroll = 0;
bool g_drag_over = false, g_tracking = false;
bool g_focus_visible = false;  // the selection was reached with the keyboard: it shows a focus ring

// The library and the store share the window's scrolling (the store alone has the search box);
// the view not shown keeps its own query and scroll position here.
enum View { kLibraryView, kStoreView, kSettingsView, kAppView, kStoreAppView, kGmsSetupView, kPlayView } g_view = kLibraryView;
void set_view(View view);
std::map<View, std::wstring> g_queries;  // the search of a grid view not shown
std::map<View, float> g_scrolls;         // and its scroll position
bool g_setup_from_settings = false;      // the Google services setup was opened from Settings (it goes back there)
std::set<std::wstring> g_setup_names;    // installs the setup page started: system components, never a tile
bool google_services_on();
void open_google_play();

struct StoreState {
    std::vector<StoreApp> apps;  // the catalog, the most recently updated first
    std::vector<int> shown;      // what the grid shows: the search and the category applied
    std::wstring category;       // empty: every category
    // The catalog's fetch: not asked for yet (the store was never opened), under way (catalog_thread), or
    // over, well or not. `apps` may hold a list in any of them: the one kept from the last fetch.
    enum class Fetch { kNotStarted, kLoading, kDone, kFailed } fetch = Fetch::kNotStarted;
    bool loading() const { return fetch == Fetch::kLoading; }
    // No list to show yet, and one is on its way (or will be, once the store is opened).
    bool first_load() const { return apps.empty() && (fetch == Fetch::kLoading || fetch == Fetch::kNotStarted); }
    uint64_t got_kb = 0;  // of the catalog, while it loads
    std::map<std::string, std::pair<std::wstring, long long>> installed;  // package: library name, versionCode
    std::map<std::string, int> downloads;                                  // package: percent, -1 waiting
    std::map<std::string, std::unique_ptr<Gdiplus::Bitmap>> icons;         // rounded; null: none
    std::set<std::string> icon_wait;                                       // being fetched
} g_store;

// Google Play in the window (see "Google Play, in the window" below).
struct PlayState {
    HWND panel = nullptr;  // the host panel over the content area; the Play Store's window is its child
    std::wstring name;     // the Play Store's library name, while it runs (or last ran) in the panel
    enum State { kIdle, kStarting, kShown, kClosed, kFailed } state = kIdle;
} g_play;

// What the mouse is over. kHitNav's `tile` is the pane's item: 0 Library, 1 Store, 2 Settings, 3 Google Play.
enum HitKind { kHitNone, kHitNav, kHitAdd, kHitClear, kHitRefresh, kHitTile, kHitThumb, kHitTrack, kHitCategory,
               kHitCard, kHitCardButton, kHitRetry, kHitInfoClose, kHitInfoLink, kHitBack, kHitPage, kHitAccount,
               kHitPlayOpen, kHitPlayLog };
enum NavItem { kNavLibrary, kNavStore, kNavSettings, kNavPlay, kNavCount };
struct Hit {
    HitKind kind = kHitNone;
    int tile = -1;
    bool operator==(const Hit& o) const { return kind == o.kind && tile == o.tile; }
};
Hit g_hot;
bool g_thumb_drag = false;
float g_thumb_y0 = 0, g_thumb_scroll0 = 0;

// The window: a navigation pane (Library, Store, Settings at the bottom) and
// the content layer beside it, with the page's title and toolbar above the
// grid, or a page (Settings, an app's details) in its place.
struct Layout {
    RectF rail, nav[kNavCount], content, title, account, search, clear, add, category, refresh, grid, drop, retry, infobar,
        info_close, info_link,  // info_link: measured when the InfoBar is painted
        play, play_open, play_log;  // Google Play's panel, and its Open and Show log once it closed
    bool compact = false;  // a narrow window: the pane shows icons only
    int cols = 1;
    float tile_w = 0, tile_h = 0, gap = 0, content_h = 0, max_scroll = 0;  // the library's tiles or the store's cards
} g_l;

// The signed-in Google account the toolbar's account button shows; empty when there is none
// (refresh_account asks the runtime in the background).
std::wstring g_account;
void refresh_account();
// The account's picture, round, at the button's size: what the runtime saved at sign-in
// (com.macapk.gms.AccountPhoto), loaded once per account and size. Null: none, the initial shows.
std::unique_ptr<Gdiplus::Bitmap> g_account_photo;
std::wstring g_account_photo_key;  // account + size it was loaded for; cleared to load again

HWND owner() { return g_main; }
void show_logs(const fs::path& log);
void show_install_window();
void fit_install_window();
std::wstring install_failure_reason(const fs::path& log);
void show_library(int show);
void maybe_quit();
struct Splash;

const App* find_app(const std::wstring& name) {
    for (const App& a : g_apps) {
        if (a.name == name) return &a;
    }
    return nullptr;
}

// The picture in `png` at s x s, its corners rounded by `radius` (s / 2: a circle); null if there is none.
std::unique_ptr<Gdiplus::Bitmap> rounded_picture(const fs::path& png, int s, float radius) {
    std::unique_ptr<Gdiplus::Bitmap> out;
    std::error_code ec;
    if (fs::exists(png, ec)) {
        Gdiplus::Bitmap src(png.c_str());
        if (src.GetLastStatus() == Gdiplus::Ok && src.GetWidth() > 0) {
            Gdiplus::Bitmap scaled(s, s, PixelFormat32bppPARGB);
            {
                Graphics g(&scaled);
                g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
                g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
                g.DrawImage(&src, 0, 0, s, s);
            }
            out = std::make_unique<Gdiplus::Bitmap>(s, s, PixelFormat32bppPARGB);
            Graphics g(out.get());
            g.SetSmoothingMode(Gdiplus::SmoothingModeAntiAlias);
            g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
            Gdiplus::TextureBrush brush(&scaled);
            GraphicsPath p;
            rounded(p, RectF(0, 0, static_cast<float>(s), static_cast<float>(s)), radius);
            g.FillPath(&brush, &p);
        }
    }
    return out;
}

// The picture in `png` at s x s, with the icons' rounded corners; null if there is none.
std::unique_ptr<Gdiplus::Bitmap> rounded_icon(const fs::path& png, int s) { return rounded_picture(png, s, s * 0.22f); }

// The program's icon (launcher.rc's PNG) for the pane, at the pane's size.
Gdiplus::Bitmap* brand_icon() {
    if (g_brand) return g_brand.get();
    HRSRC res = FindResourceW(nullptr, MAKEINTRESOURCEW(2), RT_RCDATA);
    HGLOBAL mem = res ? LoadResource(nullptr, res) : nullptr;
    const void* bytes = mem ? LockResource(mem) : nullptr;
    const DWORD size = res ? SizeofResource(nullptr, res) : 0;
    if (!bytes || !size) return nullptr;
    HGLOBAL copy = GlobalAlloc(GMEM_MOVEABLE, size);
    if (!copy) return nullptr;
    memcpy(GlobalLock(copy), bytes, size);
    GlobalUnlock(copy);
    IStream* stream = nullptr;
    if (SUCCEEDED(CreateStreamOnHGlobal(copy, TRUE, &stream))) {  // the stream frees `copy`
        Gdiplus::Bitmap src(stream);
        if (src.GetLastStatus() == Gdiplus::Ok) {
            const int s = ipx(24);
            g_brand = std::make_unique<Gdiplus::Bitmap>(s, s, PixelFormat32bppPARGB);
            Graphics g(g_brand.get());
            g.SetInterpolationMode(Gdiplus::InterpolationModeHighQualityBicubic);
            g.SetPixelOffsetMode(Gdiplus::PixelOffsetModeHighQuality);
            g.DrawImage(&src, 0, 0, s, s);
        }
        stream->Release();
    } else {
        GlobalFree(copy);
    }
    return g_brand.get();
}

Gdiplus::Bitmap* icon_for(const App& app) {
    auto it = g_icons.find(app.name);
    if (it != g_icons.end()) return it->second.get();
    std::unique_ptr<Gdiplus::Bitmap> out = rounded_icon(app_dir(app.name) / L"icon.png", ipx(72));
    Gdiplus::Bitmap* raw = out.get();
    g_icons[app.name] = std::move(out);
    return raw;
}

// An icon with its shadow; the monogram stands in when there is no picture.
void draw_icon(Graphics& g, Gdiplus::Bitmap* bmp, const std::wstring& label, const RectF& r) {
    fill_round(g, RectF(r.X, r.Y + px(2), r.Width, r.Height), r.Width * 0.22f, Color(g_t.dark ? 90 : 30, 0, 0, 0));
    if (bmp) {
        draw_image(g, bmp, r);
    } else {
        draw_monogram(g, r, label,
                      r.Width >= px(72)   ? g_f.monogram.get()
                      : r.Width >= px(56) ? g_f.monogram_small.get()
                                          : g_f.body_semi.get());
    }
}

void draw_app_icon(Graphics& g, const App* app, const std::wstring& label, const RectF& r) {
    draw_icon(g, app ? icon_for(*app) : nullptr, label, r);
}

// What the install of a new app has written of it so far: its icon and name (apps\<name>\icon.png and
// label.txt, which the runtime writes before it converts the code), so that the install shows the real
// ones while it runs, as Android's does. Looked for twice a second, and forgotten with the batch.
struct PendingLook {
    ULONGLONG looked = 0;
    uintmax_t icon_size = 0;  // the file's size at the last look: read once it has stopped growing
    std::shared_ptr<Gdiplus::Bitmap> icon;
    std::wstring label;
};
std::map<std::wstring, PendingLook> g_pending_looks;

const PendingLook& pending_look(const std::wstring& name) {
    PendingLook& look = g_pending_looks[name];
    const ULONGLONG now = GetTickCount64();
    if ((look.icon && !look.label.empty()) || now - look.looked < 500) return look;
    look.looked = now;
    std::error_code ec;
    if (!look.icon) {
        const fs::path png = app_dir(name) / L"icon.png";
        const uintmax_t size = fs::file_size(png, ec);
        if (!ec && size > 0 && size == look.icon_size) look.icon = rounded_icon(png, ipx(72));
        look.icon_size = ec ? 0 : size;
    }
    if (look.label.empty()) {
        std::ifstream f(app_dir(name) / L"label.txt", std::ios::binary);
        std::string s((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
        look.label = utf8_to_wide(s);
        look.label.erase(0, look.label.find_first_not_of(L" \t\r\n"));
        if (const size_t end = look.label.find_first_of(L"\r\n"); end != std::wstring::npos) look.label.resize(end);
        // The library's and the tiles' name for it too, unless the store gave it one.
        if (!look.label.empty()) g_job_labels.emplace(name, look.label);
    }
    return look;
}

// The name an install shows before the app is in the library: the store's
// name for it, else the app's own once its install has written it, else the library name.
std::wstring job_label(const std::wstring& name) {
    if (const App* a = find_app(name)) return a->label;
    const auto it = g_job_labels.find(name);
    return it == g_job_labels.end() ? name : it->second;
}

// Shows a message in the InfoBar; a success or a note closes itself after six seconds. A `link` is an action
// at its end (`act`), and keeps it open until it is used or closed.
void info(const std::wstring& text, StatusKind kind, const std::wstring& link = L"", std::function<void()> act = {}) {
    g_status = text;
    g_status_kind = kind;
    g_status_link = act ? link : L"";
    g_status_act = std::move(act);
    if (!g_main) return;
    if (kind == kError || text.empty() || g_status_act) KillTimer(g_main, kInfoBarTimer);
    else SetTimer(g_main, kInfoBarTimer, 6000, nullptr);
    InvalidateRect(g_main, nullptr, FALSE);
}

// The icon kept from the store, fetched the first time a card asks for it; null meanwhile.
Gdiplus::Bitmap* store_icon(const StoreApp& a) {
    const auto it = g_store.icons.find(a.pkg);
    if (it != g_store.icons.end()) return it->second.get();
    if (g_store.icon_wait.count(a.pkg)) return nullptr;
    std::error_code ec;
    if (a.icon.empty() || fs::exists(store_icon_file(a.pkg), ec)) {
        Gdiplus::Bitmap* raw = (g_store.icons[a.pkg] = rounded_icon(store_icon_file(a.pkg), ipx(56))).get();
        return raw;
    }
    g_store.icon_wait.insert(a.pkg);
    request_icon(a.pkg, a.icon);
    return nullptr;
}

// Which packages the library has, and at which version, for the store's buttons.
void scan_installed() {
    g_store.installed.clear();
    for (const App& app : g_apps) {
        const std::vector<std::string> p = package_of(app.name);
        if (p.empty() || p[0].empty()) continue;
        g_store.installed[p[0]] = {app.name, p.size() > 1 ? std::atoll(p[1].c_str()) : 0};
    }
}

enum Offer { kOfferInstall, kOfferUpdate, kOfferOpen, kOfferDownloading, kOfferInstalling };

// What the store offers for an app: to install it, update it (the library's
// version is older; unknown counts as current) or open it, or its progress.
Offer offer_for(const StoreApp& a, int* percent = nullptr) {
    const auto dl = g_store.downloads.find(a.pkg);
    if (dl != g_store.downloads.end()) {
        if (percent) *percent = dl->second;
        return kOfferDownloading;
    }
    const auto it = g_store.installed.find(a.pkg);
    const std::wstring name = it != g_store.installed.end() ? it->second.first : file_name_safe(utf8_to_wide(a.pkg));
    if (is_pending(name)) return kOfferInstalling;
    if (it == g_store.installed.end()) return kOfferInstall;
    return it->second.second > 0 && a.code > it->second.second ? kOfferUpdate : kOfferOpen;
}

// ---------------------------------------------------------------------------
// Layout

RectF tile_rect(int i) {
    const int col = i % g_l.cols, row = i / g_l.cols;
    return RectF(g_l.grid.X + px(8) + col * (g_l.tile_w + g_l.gap),
                 g_l.grid.Y + px(4) + row * (g_l.tile_h + g_l.gap) - g_scroll, g_l.tile_w, g_l.tile_h);
}

// The store card's button, in its bottom corner.
RectF card_button(const RectF& card) {
    return RectF(card.GetRight() - px(16) - px(104), card.GetBottom() - px(12) - px(32), px(104), px(32));
}

RectF thumb_rect() {
    const RectF& gr = g_l.grid;
    const float h = (std::max)(px(36), gr.Height * gr.Height / g_l.content_h);
    const float y = gr.Y + (gr.Height - h) * (g_l.max_scroll > 0 ? g_scroll / g_l.max_scroll : 0);
    return RectF(gr.GetRight() - px(8), y, px(6), h);
}

void clamp_scroll() { g_scroll = (std::max)(0.0f, (std::min)(g_scroll, g_l.max_scroll)); }

// A text's width outside a paint, for the layout, measured on the window's own DC.
float measure(const std::wstring& s, const Gdiplus::Font* f) {
    if (!f) return 0;
    HDC dc = GetDC(g_main);
    float w = 0;
    {
        Graphics g(dc);
        w = text_width(g, s, f);
    }
    ReleaseDC(g_main, dc);
    return w;
}

void place_play_panel();

void compute_layout() {
    RECT rc;
    GetClientRect(g_main, &rc);
    const float W = static_cast<float>(rc.right), H = static_cast<float>(rc.bottom);
    const bool store = g_view == kStoreView;

    // The pane: the brand row, Library and Store (and Google Play under it while Google services are on),
    // Settings at the bottom.
    g_l.compact = W < px(900);
    const float rail_w = g_l.compact ? px(56) : px(200);
    g_l.rail = RectF(0, 0, rail_w, H);
    const float item_h = px(36), item_w = rail_w - px(16);
    for (int i = 0; i < 2; ++i) g_l.nav[i] = RectF(px(8), px(60) + i * (item_h + px(4)), item_w, item_h);
    g_l.nav[kNavPlay] = google_services_on() ? RectF(px(8), px(60) + 2 * (item_h + px(4)), item_w, item_h) : RectF();
    g_l.nav[kNavSettings] = RectF(px(8), g_l.rail.GetBottom() - px(12) - item_h, item_w, item_h);
    // The Google account above Settings, at the pane's foot, where the Microsoft Store keeps the profile: only
    // its picture, a square the items' height (the address is its tooltip), where the items' glyphs are.
    g_l.account = RectF(g_l.compact ? px(8) + (item_w - item_h) / 2 : px(8), g_l.nav[kNavSettings].Y - px(4) - item_h,
                        item_h, item_h);

    // The content layer: the title block with the toolbar's controls at its end, the grid below.
    g_l.content = RectF(rail_w, 0, W - rail_w, H);
    const float m = px(24), cx = g_l.content.X + m, cw = g_l.content.Width - 2 * m;
    g_l.title = RectF(cx, px(20), cw, px(44));
    const float ch = px(32), cy = g_l.title.Y + (g_l.title.Height - ch) / 2;
    float right = cx + cw;
    if (store) {
        g_l.refresh = RectF(right - ch, cy, ch, ch);
        right = g_l.refresh.X - px(8);
        const float category_w = g_store.apps.empty() ? 0 : (std::min)(px(180), cw * 0.28f);  // none without a catalog
        g_l.category = RectF(right - category_w, cy, category_w, ch);
        if (category_w > 0) right = g_l.category.X - px(8);
    } else {
        const float add_w = measure(L"Install app", g_f.body_semi.get()) + px(50);
        g_l.add = RectF(right - add_w, cy, add_w, ch);
        right = g_l.add.X - px(8);
    }
    // Only the store has a search box: the library is short enough to see whole.
    const float search_w = store ? (std::min)(px(260), (std::max)(px(120), right - cx - px(220))) : 0;
    g_l.search = RectF(right - search_w, cy, search_w, ch);
    g_l.clear = RectF(g_l.search.GetRight() - px(30), cy + px(2), px(28), ch - px(4));
    const float top = g_l.title.GetBottom() + px(16);
    g_l.grid = RectF(cx - px(8), top, cw + px(16), (std::max)(0.0f, g_l.content.GetBottom() - top));

    // The empty library's drop card, and the store's Try again.
    const float dw = (std::min)(px(520), g_l.grid.Width - px(32)), dh = px(200);
    const float dy = g_l.grid.Y + (std::max)(px(8), (g_l.grid.Height - dh) / 2 - px(40));
    g_l.drop = RectF(g_l.grid.X + (g_l.grid.Width - dw) / 2, dy, dw, dh);
    // The InfoBar: a message at the bottom of the content, over what is there.
    const float iw = (std::min)(px(560), cw);
    g_l.infobar = RectF(cx + (cw - iw) / 2, H - px(16) - px(48), iw, px(48));
    g_l.info_close = RectF(g_l.infobar.GetRight() - px(40), g_l.infobar.Y + px(8), px(32), px(32));
    g_l.retry = RectF(g_l.grid.X + g_l.grid.Width / 2 - px(60),
                      g_l.grid.Y + (std::min)(g_l.grid.Height / 2, px(110)) + px(66), px(120), ch);
    // Google Play: the Play Store's own window, a child of the panel under the title, edge to edge in the layer.
    g_l.play = RectF(g_l.content.X + 1, top, g_l.content.Width - 1, (std::max)(1.0f, H - top));
    const bool play_log = g_play.state == PlayState::kFailed;  // Show log beside Open; else Open alone, centred
    g_l.play_open = RectF(g_l.grid.X + g_l.grid.Width / 2 - (play_log ? px(140) : px(80)), g_l.retry.Y, px(160), ch);
    g_l.play_log = RectF(g_l.play_open.GetRight() + px(8), g_l.retry.Y, px(112), ch);
    place_play_panel();

    size_t items = g_tiles.size();
    if (store) {  // cards, as many across as fit at 300 px or more
        g_l.gap = px(8);
        g_l.tile_h = px(112);
        g_l.cols = (std::max)(1, static_cast<int>((g_l.grid.Width - px(26) + g_l.gap) / (px(300) + g_l.gap)));
        g_l.tile_w = (g_l.grid.Width - px(26) - (g_l.cols - 1) * g_l.gap) / g_l.cols;
        items = g_store.shown.size();
    } else {
        g_l.tile_w = px(112);
        g_l.tile_h = px(132);
        g_l.gap = px(8);
        g_l.cols = (std::max)(1, static_cast<int>((g_l.grid.Width - px(26) + g_l.gap) / (g_l.tile_w + g_l.gap)));
    }
    const int rows = (static_cast<int>(items) + g_l.cols - 1) / g_l.cols;
    g_l.content_h = rows ? rows * (g_l.tile_h + g_l.gap) - g_l.gap + px(12) : 0;
    g_l.max_scroll = (std::max)(0.0f, g_l.content_h - g_l.grid.Height);
    clamp_scroll();
    const int edit_h = ipx(20);
    MoveWindow(g_search, static_cast<int>(g_l.search.X + px(32)),
               static_cast<int>(g_l.search.Y + (g_l.search.Height - edit_h) / 2),
               static_cast<int>(g_l.search.Width - px(32) - px(30)), edit_h, TRUE);
}

std::wstring query(View view) {
    if (view != g_view) return g_queries[view];
    wchar_t buf[256] = {};
    GetWindowTextW(g_search, buf, 256);
    return buf;
}

// The store's cards for the search and category: by name first (the exact
// name, those starting with the words, then those containing them), then by summary or
// package; with no search, the most recently updated first.
void rebuild_store() {
    const std::wstring q = lower(query(kStoreView));
    std::vector<std::pair<int, int>> ranked;  // rank, index
    for (size_t i = 0; i < g_store.apps.size(); ++i) {
        const StoreApp& a = g_store.apps[i];
        if (!g_store.category.empty() &&
            std::find(a.categories.begin(), a.categories.end(), g_store.category) == a.categories.end()) {
            continue;
        }
        int rank = 0;
        if (!q.empty()) {
            const std::wstring name = lower(a.name);
            const size_t at = name.find(q);
            if (name == q) rank = 0;
            else if (at == 0) rank = 1;
            else if (at != std::wstring::npos || lower(a.name).find(q) != std::wstring::npos) rank = 2;
            else if (lower(a.summary).find(q) != std::wstring::npos ||
                     lower(utf8_to_wide(a.pkg)).find(q) != std::wstring::npos) rank = 3;
            else continue;
        }
        ranked.emplace_back(rank, static_cast<int>(i));
    }
    std::stable_sort(ranked.begin(), ranked.end(), [](const auto& a, const auto& b) { return a.first < b.first; });
    g_store.shown.clear();
    for (const auto& r : ranked) g_store.shown.push_back(r.second);
}

void rebuild_tiles() {
    g_tiles.clear();
    std::set<std::wstring> shown;
    for (const std::wstring& name : pending_names()) {
        if (!find_app(name) && !g_setup_names.count(name) && shown.insert(name).second) g_tiles.push_back({-1, name});
    }
    for (size_t i = 0; i < g_apps.size(); ++i) {
        if (!system_app(g_apps[i].name)) g_tiles.push_back({static_cast<int>(i), L""});
    }
    rebuild_store();
    compute_layout();
    InvalidateRect(g_main, nullptr, FALSE);
}

// What the library folder holds, in one number that changes when an app is added, removed or renamed.
uint64_t library_stamp() {
    uint64_t h = 1469598103934665603ull;
    std::error_code ec;
    for (const auto& e : fs::directory_iterator(library_dir(), ec)) {
        const uint64_t t = static_cast<uint64_t>(e.last_write_time(ec).time_since_epoch().count());
        for (const wchar_t c : e.path().filename().wstring()) h = (h ^ static_cast<uint16_t>(c)) * 1099511628211ull;
        h = (h ^ t) * 1099511628211ull;
    }
    return h;
}
uint64_t g_library_stamp = 0;

void refresh() {
    g_library_stamp = library_stamp();
    g_apps = library();
    scan_installed();
    g_icons.clear();
    if (!find_app(g_selected)) g_selected.clear();
    rebuild_tiles();
}

int tile_of(const std::wstring& name) {
    for (size_t i = 0; i < g_tiles.size(); ++i) {
        if (g_tiles[i].app >= 0 && g_apps[static_cast<size_t>(g_tiles[i].app)].name == name) return static_cast<int>(i);
    }
    return -1;
}

void ensure_visible(int tile) {
    if (tile < 0) return;
    const RectF r = tile_rect(tile);
    if (r.Y < g_l.grid.Y) g_scroll -= g_l.grid.Y - r.Y + px(4);
    if (r.GetBottom() > g_l.grid.GetBottom()) g_scroll += r.GetBottom() - g_l.grid.GetBottom() + px(4);
    clamp_scroll();
}

void select_app(const std::wstring& name) {
    g_selected = name;
    if (g_view == kLibraryView) ensure_visible(tile_of(name));
    InvalidateRect(g_main, nullptr, FALSE);
}

const App* selected() { return g_selected.empty() ? nullptr : find_app(g_selected); }

// The empty library's drop card is shown when nothing is installed or queued.
bool empty_library() { return g_view == kLibraryView && g_tiles.empty(); }

bool page_view() {
    return g_view == kSettingsView || g_view == kAppView || g_view == kStoreAppView || g_view == kGmsSetupView;
}

Hit hit_test(float x, float y) {
    const bool store = g_view == kStoreView;
    for (int i = 0; i < kNavCount; ++i) {
        if (g_l.nav[i].Width > 0 && contains(g_l.nav[i], x, y)) return {kHitNav, i};
    }
    if (contains(g_l.account, x, y)) return {kHitAccount, -1};
    if (!g_status.empty() && contains(g_l.infobar, x, y)) {
        if (!g_status_link.empty() && contains(g_l.info_link, x, y)) return {kHitInfoLink, -1};
        return {contains(g_l.info_close, x, y) ? kHitInfoClose : kHitNone, -1};
    }
    if (page_view()) {
        if (contains(g_l.content, x, y)) return {kHitPage, -1};
        return {};
    }
    if (g_view == kPlayView) {  // the panel takes the mouse while it is up; once Google Play closed, its buttons
        const bool ended = g_play.state == PlayState::kClosed || g_play.state == PlayState::kFailed;
        if (ended && contains(g_l.play_open, x, y)) return {kHitPlayOpen, -1};
        if (g_play.state == PlayState::kFailed && contains(g_l.play_log, x, y)) return {kHitPlayLog, -1};
        return {};
    }
    if (store && !query(g_view).empty() && contains(g_l.clear, x, y)) return {kHitClear, -1};
    if (!store && contains(g_l.add, x, y)) return {kHitAdd, -1};
    if (store && contains(g_l.refresh, x, y)) return {kHitRefresh, -1};
    if (store && !g_store.apps.empty() && contains(g_l.category, x, y)) return {kHitCategory, -1};
    if (!contains(g_l.grid, x, y)) return {};
    if (empty_library()) return {};
    if (g_l.max_scroll > 0 && x >= g_l.grid.GetRight() - px(12)) {
        return {contains(inset(thumb_rect(), -px(4), 0), x, y) ? kHitThumb : kHitTrack, -1};
    }
    if (store) {
        // Wherever paint_store_grid draws "Try again": no list, and none on its way.
        if (g_store.apps.empty() && !g_store.first_load() && contains(g_l.retry, x, y)) return {kHitRetry, -1};
        const int col = static_cast<int>((x - g_l.grid.X - px(8)) / (g_l.tile_w + g_l.gap));
        const int row = static_cast<int>((y - g_l.grid.Y - px(4) + g_scroll) / (g_l.tile_h + g_l.gap));
        const int i = row * g_l.cols + col;
        if (col < 0 || col >= g_l.cols || row < 0 || i >= static_cast<int>(g_store.shown.size())) return {};
        const RectF r = tile_rect(i);
        if (!contains(r, x, y)) return {};
        const StoreApp& a = g_store.apps[static_cast<size_t>(g_store.shown[static_cast<size_t>(i)])];
        const Offer offer = offer_for(a);
        const bool button = offer == kOfferInstall || offer == kOfferUpdate;
        return {button && contains(card_button(r), x, y) ? kHitCardButton : kHitCard, i};
    }
    for (size_t i = 0; i < g_tiles.size(); ++i) {
        if (contains(tile_rect(static_cast<int>(i)), x, y)) return {kHitTile, static_cast<int>(i)};
    }
    return {};
}

// ---------------------------------------------------------------------------
// Painting the library

void paint_account_button(Graphics& g);

// The navigation pane: the brand, Library and Store, and the Google account and Settings at the bottom.
void paint_rail(Graphics& g) {
    const RectF& r = g_l.rail;
    if (Gdiplus::Bitmap* brand = brand_icon()) {
        const float s = px(24);
        draw_image(g, brand, RectF(g_l.compact ? (r.Width - s) / 2 : px(16), px(16), s, s));
    }
    if (!g_l.compact) {
        draw_text(g, L"Win.apk", g_f.body_semi.get(), g_t.text, RectF(px(48), px(14), r.Width - px(56), px(28)));
    }
    struct Item {
        const wchar_t* glyph;
        const wchar_t* label;
    };
    const Item items[] = {{kGlyphLibrary, L"Library"}, {kGlyphStore, L"Store"}, {kGlyphSettings, L"Settings"},
                          {kGlyphPlay, L"Google Play"}};
    for (int i = 0; i < kNavCount; ++i) {
        const RectF& it = g_l.nav[i];
        if (it.Width <= 0) continue;  // Google Play while Google services are off
        // The setup page belongs to Google Play when that opened it, and to Settings when its switch did.
        const bool setup = g_view == kGmsSetupView;
        const bool on = (i == kNavLibrary && (g_view == kLibraryView || g_view == kAppView)) ||
                        (i == kNavStore && (g_view == kStoreView || g_view == kStoreAppView)) ||
                        (i == kNavSettings && (g_view == kSettingsView || (setup && g_setup_from_settings))) ||
                        (i == kNavPlay && ((setup && !g_setup_from_settings) || g_view == kPlayView));
        const bool hot = g_hot.kind == kHitNav && g_hot.tile == i;
        if (on || hot) fill_round(g, it, px(kRadiusControl), mix(g_t.bg, g_t.text, on ? 0.07f : 0.04f));
        if (on) fill_round(g, RectF(it.X, it.Y + (it.Height - px(16)) / 2, px(3), px(16)), px(1.5f), g_t.accent);
        const Color ink = on || hot ? g_t.text : g_t.text2;
        const float gx = g_l.compact ? it.X + (it.Width - px(16)) / 2 : it.X + px(12);
        draw_text(g, items[i].glyph, g_f.glyph16.get(), ink, RectF(gx, it.Y, px(16), it.Height), kCenter);
        if (!g_l.compact) {
            draw_text(g, items[i].label, on ? g_f.body_semi.get() : g_f.body.get(), ink,
                      RectF(it.X + px(40), it.Y, it.Width - px(48), it.Height));
        }
    }
    paint_account_button(g);
}

// The content layer: a lighter surface with a rounded corner towards the pane, as Windows 11 lays out.
void paint_layer(Graphics& g) {
    const RectF& c = g_l.content;
    const RectF card(c.X, c.Y + px(8), c.Width + px(16), c.Height + px(16));  // its other corners are off-screen
    g.SetClip(c);
    fill_round(g, card, px(kRadiusCard), g_t.layer);
    stroke_round(g, card, px(kRadiusCard), g_t.border);
    g.ResetClip();
}

void paint_search_box(Graphics& g) {
    const bool focused = GetFocus() == g_search;
    fill_round(g, g_l.search, px(kRadiusControl), g_t.card);
    stroke_round(g, g_l.search, px(kRadiusControl), focused ? g_t.accent : g_t.border, focused ? px(1.5f) : 1);
    draw_text(g, kGlyphSearch, g_f.glyph12.get(), g_t.text2,
              RectF(g_l.search.X + px(10), g_l.search.Y, px(16), g_l.search.Height), kCenter);
    if (!query(g_view).empty()) {
        const bool hot = g_hot.kind == kHitClear;
        if (hot) fill_round(g, g_l.clear, px(4), g_t.hover);
        draw_text(g, kGlyphClear, g_f.glyph12.get(), hot ? g_t.text : g_t.text2, g_l.clear, kCenter);
    }
}

fs::path gms_dir();

// Where the runtime keeps the account's picture: com.macapk.gms.AccountPhoto.file, the same name
// (lower case; anything but a-z 0-9 . _ @ - as '_').
fs::path account_photo_path(const std::wstring& account) {
    std::wstring name = L"photo-";
    for (wchar_t ch : account) {
        if (ch >= L'A' && ch <= L'Z') ch = static_cast<wchar_t>(ch - L'A' + L'a');
        const bool ok = (ch >= L'a' && ch <= L'z') || (ch >= L'0' && ch <= L'9') || ch == L'.' || ch == L'_' ||
                        ch == L'@' || ch == L'-';
        name += ok ? ch : L'_';
    }
    return gms_dir() / (name + L".img");
}

Gdiplus::Bitmap* account_photo(int s) {
    const std::wstring key = g_account + L"|" + std::to_wstring(s);
    if (key != g_account_photo_key) {
        g_account_photo_key = key;
        g_account_photo = rounded_picture(account_photo_path(g_account), s, s / 2.0f);
    }
    return g_account_photo.get();
}

// The Google account's item in the pane, above Settings: the account's picture when Google gave one at
// sign-in, round with a thin ring; else its initial in an accent circle; signed out, the person glyph. Only the
// picture: the address (or "not signed in") is its tooltip. It opens Settings, where the account is.
void paint_account_button(Graphics& g) {
    const RectF& it = g_l.account;
    if (it.Width <= 0) return;
    const bool hot = g_hot.kind == kHitAccount;
    if (hot) fill_round(g, it, px(kRadiusControl), mix(g_t.bg, g_t.text, 0.04f));
    const Color ink = hot ? g_t.text : g_t.text2;
    const float s = px(26);
    const RectF c(it.X + (it.Width - s) / 2, it.Y + (it.Height - s) / 2, s, s);
    const int side = static_cast<int>(std::lround(s));
    Gdiplus::Bitmap* photo = g_account.empty() ? nullptr : account_photo(side);
    if (photo) {
        draw_image(g, photo, RectF(c.X, c.Y, static_cast<float>(side), static_cast<float>(side)));
        Gdiplus::Pen ring(hot ? g_t.accent : with_alpha(g_t.text, g_t.dark ? 70 : 45), px(1));
        g.DrawEllipse(&ring, inset(c, px(0.5f), px(0.5f)));
    } else if (!g_account.empty()) {
        Gdiplus::SolidBrush disc(g_t.accent);
        g.FillEllipse(&disc, c);
        const std::wstring initial(1, static_cast<wchar_t>(towupper(g_account[0])));
        draw_text(g, initial, g_f.body_semi.get(), Color(255, 255, 255, 255), c, kCenter);
    } else {
        draw_text(g, kGlyphContact, g_f.glyph16.get(), ink, it, kCenter);
    }
}

// The page's title and what it holds, with the toolbar's controls at the end of the row.
void paint_toolbar(Graphics& g) {
    const RectF& t = g_l.title;
    const bool store = g_view == kStoreView;
    std::wstring sub;
    if (store) {
        if (g_store.first_load()) sub = L"Getting the app catalog from F-Droid…";
        else if (g_store.apps.empty()) sub = L"F-Droid could not be reached";
        else if (g_store.loading()) sub = L"Checking F-Droid for new apps…";
        else sub = app_count(g_store.shown.size()) + L"  ·  f-droid.org";
    } else {
        size_t shown = 0, running = 0;  // the library's own apps: not Google's system components
        for (const Tile& tile : g_tiles) shown += tile.app >= 0;
        for (const std::wstring& r : run_names()) running += !system_app(r);
        sub = shown == 0 ? std::wstring(L"No apps installed yet") : app_count(shown);
        if (running) sub += L"  ·  " + fmt(L"{0} running", {std::to_wstring(running)});
        if (const std::wstring now = current_install(); !now.empty()) {
            sub += L"  ·  " + fmt(L"Installing {0}…", {job_label(now)});
        }
    }
    const float tw = (std::max)(px(60), g_l.search.X - t.X - px(16));
    draw_text(g, store ? L"Store" : L"Library", g_f.title.get(), g_t.text, RectF(t.X, t.Y, tw, px(26)));
    draw_text(g, sub, g_f.caption.get(), g_t.text2, RectF(t.X, t.Y + px(26), tw, px(18)));
    if (store) {
        paint_search_box(g);
        if (!g_store.apps.empty()) {
            const RectF& c = g_l.category;
            const bool hot = g_hot.kind == kHitCategory;
            fill_round(g, c, px(kRadiusControl), hot ? g_t.hover : g_t.card);
            stroke_round(g, c, px(kRadiusControl), g_store.category.empty() ? g_t.border : g_t.accent);
            const std::wstring text = g_store.category.empty() ? L"All categories" : g_store.category;
            draw_text(g, text, g_f.body.get(), g_store.category.empty() ? g_t.text2 : g_t.text,
                      RectF(c.X + px(12), c.Y, c.Width - px(40), c.Height));
            draw_text(g, kGlyphChevron, g_f.glyph12.get(), g_t.text2,
                      RectF(c.GetRight() - px(30), c.Y, px(22), c.Height), kCenter);
        }
        if (g_store.loading() && !g_store.apps.empty()) {
            spinner(g, inset(g_l.refresh, px(8), px(8)), g_t.accent, px(2));
        } else {
            draw_icon_button(g, g_l.refresh, kGlyphRefresh, g_hot.kind == kHitRefresh, g_t.text2);
        }
    } else {
        draw_button(g, g_l.add, L"Install app", g_hot.kind == kHitAdd, true, false, kGlyphAdd);
    }
}

void paint_scrollbar(Graphics& g, bool nearby) {
    if (g_l.max_scroll <= 0) return;
    const bool active = g_thumb_drag || g_hot.kind == kHitThumb || g_hot.kind == kHitTrack;
    fill_round(g, thumb_rect(), px(3), with_alpha(g_t.text2, active ? 200 : nearby ? 120 : 70));
}

void paint_tile(Graphics& g, int i) {
    const RectF r = tile_rect(i);
    if (r.GetBottom() < g_l.grid.Y || r.Y > g_l.grid.GetBottom()) return;
    const Tile& t = g_tiles[static_cast<size_t>(i)];
    const App* app = t.app >= 0 ? &g_apps[static_cast<size_t>(t.app)] : nullptr;
    const bool sel = app && app->name == g_selected;
    const bool hot = g_hot.kind == kHitTile && g_hot.tile == i;
    const bool running = app && run_active(app->name);
    if (sel) {
        fill_round(g, r, px(kRadiusTile), with_alpha(g_t.accent, g_t.dark ? 56 : 30));
        stroke_round(g, r, px(kRadiusTile), with_alpha(g_t.accent, 170));
        if (g_focus_visible && GetFocus() == g_main) draw_focus_ring(g, r, px(kRadiusTile));
    } else if (hot) {
        fill_round(g, r, px(kRadiusTile), g_t.card);
        stroke_round(g, r, px(kRadiusTile), g_t.border);
    }
    const std::wstring label = app ? app->label : job_label(t.job);
    const float s = px(72);
    const RectF ir(r.X + (r.Width - s) / 2, r.Y + px(12), s, s);
    // A new app being installed: its own icon as soon as the install has it, under the veil.
    draw_icon(g, app ? icon_for(*app) : pending_look(t.job).icon.get(), label, ir);
    if (!app || is_pending(app->name)) {
        fill_round(g, ir, s * 0.22f, Color(130, 0, 0, 0));
        spinner(g, inset(ir, s * 0.3f, s * 0.3f), kWhite, px(3));
    }
    if (running) {  // a badge on the icon's corner, ringed in the tile's colour so it stands off the picture
        const float d = px(22);
        const RectF badge(ir.GetRight() - d + px(6), ir.GetBottom() - d + px(6), d, d);
        Gdiplus::SolidBrush ring(sel   ? mix(g_t.layer, g_t.accent, g_t.dark ? 0.22f : 0.12f)
                                 : hot ? g_t.card
                                       : g_t.layer);
        g.FillEllipse(&ring, badge);
        Gdiplus::SolidBrush dot(g_t.success);
        g.FillEllipse(&dot, inset(badge, px(3), px(3)));
        Gdiplus::SolidBrush core(kWhite);
        g.FillEllipse(&core, inset(badge, px(8), px(8)));
    }
    draw_text(g, label, g_f.tile.get(), app ? g_t.text : g_t.text2,
              RectF(r.X + px(6), ir.GetBottom() + px(8), r.Width - px(12), px(36)), kCenter, true, false);
}

// The middle of an empty grid: a glyph, a line and a word of advice.
void paint_empty(Graphics& g, const wchar_t* glyph, const std::wstring& title, const std::wstring& sub) {
    const RectF& gr = g_l.grid;
    const float cy = gr.Y + (std::min)(gr.Height / 2, px(110));
    if (glyph) {
        draw_text(g, glyph, g_f.glyph48.get(), with_alpha(g_t.text2, 150), RectF(gr.X, cy - px(70), gr.Width, px(60)),
                  kCenter);
    }
    draw_text(g, title, g_f.body_semi.get(), g_t.text, RectF(gr.X, cy, gr.Width, px(24)), kCenter);
    draw_text(g, sub, g_f.caption.get(), g_t.text2, RectF(gr.X + px(24), cy + px(24), gr.Width - px(48), px(36)),
              kCenter, true, false);
}

// Nothing installed yet: a card that says how to add the first app (Install app is in the toolbar).
void paint_drop_card(Graphics& g) {
    const RectF& r = g_l.drop;
    fill_round(g, r, px(kRadiusTile), g_t.card);
    GraphicsPath p;
    rounded(p, inset(r, px(1), px(1)), px(kRadiusTile) - 1);
    Gdiplus::Pen pen(with_alpha(g_t.text2, 140), px(1.5f));
    const Gdiplus::REAL dashes[] = {4, 3};
    pen.SetDashPattern(dashes, 2);
    g.DrawPath(&pen, &p);
    const float cy = r.Y + r.Height / 2;
    draw_text(g, kGlyphDownload, g_f.glyph48.get(), with_alpha(g_t.text2, 200), RectF(r.X, cy - px(66), r.Width, px(56)),
              kCenter);
    draw_text(g, L"Drop an Android app here", g_f.body_semi.get(), g_t.text,
              RectF(r.X + px(16), cy + px(2), r.Width - px(32), px(24)), kCenter);
    draw_text(g, L"or choose Install app  ·  .apk .apkm .xapk .apks", g_f.caption.get(), g_t.text2,
              RectF(r.X + px(16), cy + px(26), r.Width - px(32), px(20)), kCenter);
}

void paint_grid(Graphics& g) {
    const RectF& gr = g_l.grid;
    if (g_tiles.empty()) {
        paint_drop_card(g);
        return;
    }
    g.SetClip(gr);
    for (size_t i = 0; i < g_tiles.size(); ++i) paint_tile(g, static_cast<int>(i));
    g.ResetClip();
    if (g_scroll > 0) {
        Gdiplus::Pen line(g_t.border, 1);
        g.DrawLine(&line, gr.X + px(8), gr.Y, gr.GetRight() - px(8), gr.Y);
    }
    paint_scrollbar(g, g_hot.kind == kHitTile);
}

void paint_card(Graphics& g, int i) {
    const RectF r = tile_rect(i);
    if (r.GetBottom() < g_l.grid.Y || r.Y > g_l.grid.GetBottom()) return;
    const StoreApp& a = g_store.apps[static_cast<size_t>(g_store.shown[static_cast<size_t>(i)])];
    const bool hot = (g_hot.kind == kHitCard || g_hot.kind == kHitCardButton) && g_hot.tile == i;
    fill_round(g, r, px(kRadiusCard), hot ? mix(g_t.card, g_t.hover, 0.5f) : g_t.card);
    stroke_round(g, r, px(kRadiusCard), hot ? mix(g_t.border, g_t.accent, 0.4f) : g_t.border);
    const float s = px(56);
    const RectF ir(r.X + px(16), r.Y + px(16), s, s);
    draw_icon(g, store_icon(a), a.name, ir);
    const float tx = ir.GetRight() + px(16), tw = r.GetRight() - px(16) - tx;
    draw_text(g, a.name, g_f.body_semi.get(), g_t.text, RectF(tx, r.Y + px(14), tw, px(22)));
    draw_text(g, a.summary, g_f.caption.get(), g_t.text2, RectF(tx, r.Y + px(37), tw, px(34)), kLeft, true,
              false);
    const RectF b = card_button(r);
    std::wstring meta = a.version;
    if (a.size > 0) meta += (meta.empty() ? L"" : L"  ·  ") + format_bytes(static_cast<uintmax_t>(a.size));
    draw_text(g, meta, g_f.caption.get(), g_t.text2, RectF(r.X + px(16), b.Y, b.X - r.X - px(24), b.Height));
    int percent = 0;
    const bool on_button = g_hot.kind == kHitCardButton && g_hot.tile == i;
    switch (offer_for(a, &percent)) {
        case kOfferInstall: draw_button(g, b, L"Install", on_button, true, false); break;
        case kOfferUpdate: draw_button(g, b, L"Update", on_button, true, false); break;
        case kOfferOpen:  // installed: the library opens it
            draw_text(g, kGlyphCheck, g_f.glyph12.get(), g_t.success, RectF(b.X, b.Y, px(20), b.Height), kCenter);
            draw_text(g, L"Installed", g_f.caption.get(), g_t.text2, RectF(b.X + px(22), b.Y, b.Width - px(22), b.Height));
            break;
        case kOfferDownloading: {
            fill_round(g, b, px(kRadiusControl), with_alpha(g_t.accent, g_t.dark ? 40 : 24));
            if (percent > 0) {
                const RectF bar(b.X, b.Y, b.Width * static_cast<float>(percent) / 100.0f, b.Height);
                g.SetClip(bar, Gdiplus::CombineModeIntersect);
                fill_round(g, b, px(kRadiusControl), with_alpha(g_t.accent, g_t.dark ? 120 : 80));
                g.SetClip(g_l.grid);
            }
            draw_text(g, percent > 0 ? std::to_wstring(percent) + L"%" : std::wstring(L"Waiting"),
                      g_f.caption_semi.get(), g_t.text, b, kCenter);
            break;
        }
        case kOfferInstalling: {
            spinner(g, RectF(b.X + px(10), b.Y + b.Height / 2 - px(7), px(14), px(14)), g_t.accent, px(2));
            draw_text(g, L"Installing…", g_f.caption.get(), g_t.text2,
                      RectF(b.X + px(30), b.Y, b.Width - px(30), b.Height));
            break;
        }
    }
}

void paint_store_grid(Graphics& g) {
    const RectF& gr = g_l.grid;
    if (g_store.shown.empty()) {
        const std::wstring q = query(kStoreView);
        if (g_store.first_load()) {
            const float cy = gr.Y + (std::min)(gr.Height / 2, px(110));
            spinner(g, RectF(gr.X + gr.Width / 2 - px(20), cy - px(58), px(40), px(40)), g_t.accent, px(3.5f));
            wchar_t mb[32];
            swprintf(mb, 32, L"%.1f", static_cast<double>(g_store.got_kb) / 1024.0);
            paint_empty(g, nullptr, L"Getting the app catalog from F-Droid…",
                        fmt(L"{0} MB so far. This happens once; later the store opens at once.", {mb}));
        } else if (g_store.apps.empty()) {
            paint_empty(g, kGlyphError, L"F-Droid could not be reached",
                        L"Check the internet connection, then try again.");
            draw_button(g, g_l.retry, L"Try again", g_hot.kind == kHitRetry, true, false);
        } else {
            paint_empty(g, kGlyphSearch, fmt(L"No apps match “{0}”", {q.empty() ? g_store.category : q}),
                        L"Try other words, or every category.");
        }
        return;
    }
    g.SetClip(gr);
    const int first_row = (std::max)(0, static_cast<int>(g_scroll / (g_l.tile_h + g_l.gap)));
    const int last = (std::min)(static_cast<int>(g_store.shown.size()),
                                (first_row + static_cast<int>(gr.Height / (g_l.tile_h + g_l.gap)) + 2) * g_l.cols);
    for (int i = first_row * g_l.cols; i < last; ++i) paint_card(g, i);
    g.ResetClip();
    if (g_scroll > 0) {
        Gdiplus::Pen line(g_t.border, 1);
        g.DrawLine(&line, gr.X + px(8), gr.Y, gr.GetRight() - px(8), gr.Y);
    }
    paint_scrollbar(g, g_hot.kind == kHitCard || g_hot.kind == kHitCardButton);
}

// While a file is dragged over the window the whole content area is the drop target.
void paint_drop_overlay(Graphics& g) {
    const RectF& c = g_l.content;
    Gdiplus::SolidBrush veil(with_alpha(g_t.layer, 225));
    g.FillRectangle(&veil, c);
    const RectF r = inset(c, px(16), px(16));
    fill_round(g, r, px(kRadiusTile), with_alpha(g_t.accent, g_t.dark ? 40 : 22));
    GraphicsPath p;
    rounded(p, inset(r, px(1), px(1)), px(kRadiusTile) - 1);
    Gdiplus::Pen pen(g_t.accent, px(2));
    const Gdiplus::REAL dashes[] = {4, 3};
    pen.SetDashPattern(dashes, 2);
    g.DrawPath(&pen, &p);
    const float cy = r.Y + r.Height / 2;
    draw_text(g, kGlyphDownload, g_f.glyph48.get(), g_t.accent, RectF(r.X, cy - px(76), r.Width, px(60)), kCenter);
    draw_text(g, L"Drop to install", g_f.title.get(), g_t.text, RectF(r.X, cy - px(8), r.Width, px(30)), kCenter);
    draw_text(g, g_view == kGmsSetupView ? L"Win.apk checks that Google signed it, then installs it"
                                            : L"Win.apk keeps its own copy, so the downloaded file can be deleted",
              g_f.body.get(), g_t.text2, RectF(r.X, cy + px(24), r.Width, px(24)), kCenter);
}

// The InfoBar: what just happened, at the bottom of the content, until it closes itself (a success or a
// note, after a few seconds) or is closed (an error).
void paint_infobar(Graphics& g) {
    if (g_status.empty()) return;
    const RectF& r = g_l.infobar;
    fill_round(g, RectF(r.X, r.Y + px(2), r.Width, r.Height), px(kRadiusCard), Color(g_t.dark ? 90 : 26, 0, 0, 0));
    const Color tint = g_status_kind == kError ? g_t.danger : g_status_kind == kSuccess ? g_t.success : g_t.accent;
    fill_round(g, r, px(kRadiusCard), mix(g_t.card, tint, g_t.dark ? 0.16f : 0.08f));
    stroke_round(g, r, px(kRadiusCard), mix(g_t.border, tint, 0.35f));
    const RectF dot(r.X + px(16), r.Y + (r.Height - px(16)) / 2, px(16), px(16));
    Gdiplus::SolidBrush b(tint);
    g.FillEllipse(&b, dot);
    draw_text(g, g_status_kind == kError ? L"!" : g_status_kind == kSuccess ? kGlyphCheck : L"i",
              g_status_kind == kSuccess ? g_f.glyph12.get() : g_f.caption_semi.get(), kWhite, dot, kCenter);
    float text_w = r.Width - px(96);
    g_l.info_link = RectF();
    if (!g_status_link.empty()) {
        const float lw = text_width(g, g_status_link, g_f.body_semi.get()) + px(24);
        g_l.info_link = RectF(g_l.info_close.X - px(4) - lw, r.Y + px(8), lw, px(32));
        if (g_hot.kind == kHitInfoLink) fill_round(g, g_l.info_link, px(kRadiusControl), g_t.hover);
        draw_text(g, g_status_link, g_f.body_semi.get(), g_t.accent, g_l.info_link, kCenter);
        text_w = g_l.info_link.X - px(8) - (dot.GetRight() + px(12));
    }
    draw_text(g, g_status, g_f.body.get(), g_t.text, RectF(dot.GetRight() + px(12), r.Y, text_w, r.Height));
    const bool hot = g_hot.kind == kHitInfoClose;
    if (hot) fill_round(g, g_l.info_close, px(kRadiusControl), g_t.hover);
    draw_text(g, kGlyphClear, g_f.glyph12.get(), g_t.text2, g_l.info_close, kCenter);
}

void paint_page(Graphics& g);

void paint_play(Graphics& g);

void paint_main(Graphics& g, float, float) {
    g.Clear(g_t.bg);
    paint_rail(g);
    paint_layer(g);
    if (page_view()) {
        paint_page(g);
    } else if (g_view == kPlayView) {
        paint_play(g);
    } else {
        paint_toolbar(g);
        if (g_view == kStoreView) paint_store_grid(g);
        else paint_grid(g);
    }
    if (g_drag_over) paint_drop_overlay(g);
    paint_infobar(g);
}

void update_timer() {
    if (any_pending() || any_runs() || (g_view == kStoreView && g_store.loading()) ||
        g_play.state == PlayState::kStarting) {
        SetTimer(g_main, kAnimTimer, 16, nullptr);
    }
    else KillTimer(g_main, kAnimTimer);
}

// ---------------------------------------------------------------------------
// Dialogs

// The launcher's one dialog, for its own questions and for the ones apps ask: in its theme, light or
// dark (TaskDialog and MessageBox don't follow it). An app's icon and name head it
// when it is about one app; then the question, at most a sentence saying what it means, switches or a
// check box when there is something to pick, and the buttons: the accent one first, as Windows orders
// them, stacked full width for an app's question as Android's are, else right-aligned in a footer.
// Esc and the caption's close are no answer (0).

constexpr UINT kAskTimeoutMs = 300 * 1000;  // an app's question: no answer in five minutes changes nothing

constexpr int kPromptLink = 0x7000;  // the link's target id: past any switch's, and no button uses it

struct Prompt {
    std::wstring app, question, note;
    std::wstring caption;  // the window's title; the app's name when empty
    std::unique_ptr<Gdiplus::Bitmap> icon;
    struct Switch {
        std::wstring title;
        bool on;
        bool check = false;  // a check box under the text, else a switch in a card
    };
    std::vector<Switch> switches;
    std::vector<std::pair<int, std::wstring>> buttons;  // the first is the accent one
    std::wstring link;              // a link under the note, which does `on_link` and leaves the dialog open
    std::function<void()> on_link;
    bool footer = false;   // the buttons in a footer at the end, else stacked full width
    bool timeout = false;  // closes itself after kAskTimeoutMs
    int default_id = 0;    // Enter
    int pressed = 0;       // the answer; 0: closed or timed out
    HWND window = nullptr, near_window = nullptr, owner = nullptr;
    Targets targets;       // ids: a button's own, or 1 + index for a switch
    int hot = 0, focus = 0;
    bool tracking = false;
};

// Lays the prompt out, and draws it when `draw`; returns the height it needs.
float layout_prompt(Graphics& g, Prompt& p, float W, bool draw) {
    if (draw) g.Clear(g_t.layer);
    p.targets.clear();
    const float m = px(24), cw = W - 2 * m;
    float y = px(24);
    if (!p.app.empty()) {
        const RectF ir(m, px(20), px(40), px(40));
        if (draw) {
            draw_icon(g, p.icon.get(), p.app, ir);
            draw_text(g, p.app, g_f.body.get(), g_t.text2, RectF(ir.GetRight() + px(12), ir.Y, cw - px(52), ir.Height));
        }
        y = ir.GetBottom() + px(14);
    }
    const float qh = text_height(g, p.question, g_f.title.get(), cw) + px(2);
    if (draw) draw_text(g, p.question, g_f.title.get(), g_t.text, RectF(m, y, cw, qh), kLeft, true, false);
    y += qh + px(6);
    if (!p.note.empty()) {
        const float nh = text_height(g, p.note, g_f.body.get(), cw) + px(2);
        if (draw) draw_text(g, p.note, g_f.body.get(), g_t.text2, RectF(m, y, cw, nh), kLeft, true, false);
        y += nh;
    }
    if (!p.link.empty()) {
        const RectF r(m, y + px(8), (std::min)(cw, text_width(g, p.link, g_f.body.get()) + px(4)), px(22));
        p.targets.push_back({r, kPromptLink});
        if (draw) {
            const Color ink = p.hot == kPromptLink ? mix(g_t.accent, g_t.text, 0.3f) : g_t.accent;
            draw_text(g, p.link, g_f.body.get(), ink, r);
            if (p.focus == kPromptLink) draw_focus_ring(g, r, px(4));
        }
        y += px(30);
    }
    const bool checks = !p.switches.empty() && p.switches[0].check;
    if (checks) {
        y += px(10);
        for (size_t i = 0; i < p.switches.size(); ++i) {
            const int id = 1 + static_cast<int>(i);
            const float th = (std::max)(px(20), text_height(g, p.switches[i].title, g_f.body.get(), cw - px(30)) + px(2));
            const RectF row(m, y, cw, th + px(8)), box(m, y + px(4), px(20), px(20));
            p.targets.push_back({row, id});
            if (draw) {
                draw_check_box(g, p.switches[i].on, box, p.hot == id);
                draw_text(g, p.switches[i].title, g_f.body.get(), g_t.text, RectF(m + px(30), y + px(4), cw - px(30), th),
                          kLeft, true, false);
                if (p.focus == id) draw_focus_ring(g, box, px(4));
            }
            y += row.Height;
        }
    } else if (!p.switches.empty()) {
        y += px(14);
        const float rh = px(44);
        const RectF card(m, y, cw, rh * p.switches.size());
        if (draw) fill_round(g, card, px(kRadiusCard), g_t.card);
        for (size_t i = 0; i < p.switches.size(); ++i) {
            const RectF r(m, y + i * rh, cw, rh);
            const int id = 1 + static_cast<int>(i);
            p.targets.push_back({r, id});
            if (!draw) continue;
            if (p.hot == id) {
                GraphicsPath clip;
                rounded(clip, card, px(kRadiusCard));
                g.SetClip(&clip);
                Gdiplus::SolidBrush b(g_t.hover);
                g.FillRectangle(&b, r);
                g.ResetClip();
            }
            if (i > 0) {
                Gdiplus::Pen line(g_t.divider, 1);
                g.DrawLine(&line, r.X + px(14), r.Y, r.GetRight() - px(14), r.Y);
            }
            draw_text(g, p.switches[i].title, g_f.body.get(), g_t.text, RectF(r.X + px(14), r.Y, r.Width - px(80), rh));
            const RectF sr(r.GetRight() - px(14) - px(44), r.Y + (rh - px(22)) / 2, px(44), px(22));  // as Settings
            draw_switch(g, p.switches[i].on, sr, p.hot == id);
            if (p.focus == id) draw_focus_ring(g, sr, sr.Height / 2);
        }
        if (draw) stroke_round(g, card, px(kRadiusCard), g_t.border);
        y += card.Height;
    }
    if (p.footer) {
        // As the install window's footer: a band of the window colour, the buttons right-aligned in it.
        y += px(24);
        const float foot = px(64);
        if (draw) {
            Gdiplus::SolidBrush bar(g_t.bg);
            g.FillRectangle(&bar, RectF(0, y, W, foot));
            Gdiplus::Pen line(g_t.border, 1);
            g.DrawLine(&line, 0.0f, y, W, y);
        }
        float total = 0;
        for (size_t i = 0; i < p.buttons.size(); ++i) total += button_width(g, p.buttons[i].second, i == 0) + (i ? px(8) : 0);
        float x = W - m - total;
        for (size_t i = 0; i < p.buttons.size(); ++i) {
            const float bw = button_width(g, p.buttons[i].second, i == 0);
            const RectF r(x, y + (foot - px(32)) / 2, bw, px(32));
            p.targets.push_back({r, p.buttons[i].first});
            if (draw) {
                draw_button(g, r, p.buttons[i].second, p.hot == p.buttons[i].first, i == 0, false);
                if (p.focus == p.buttons[i].first) draw_focus_ring(g, r, px(kRadiusControl));
            }
            x += bw + px(8);
        }
        return y + foot;
    }
    y += px(22);
    for (size_t i = 0; i < p.buttons.size(); ++i) {
        const RectF r(m, y, cw, px(32));
        p.targets.push_back({r, p.buttons[i].first});
        if (draw) {
            draw_button(g, r, p.buttons[i].second, p.hot == p.buttons[i].first, i == 0, false);
            if (p.focus == p.buttons[i].first) draw_focus_ring(g, r, px(kRadiusControl));
        }
        y += px(40);
    }
    return y + px(16);
}

// The owner takes input again before the dialog goes, so Windows hands it the activation.
void close_prompt(Prompt& p) {
    if (p.owner) EnableWindow(p.owner, TRUE);
    if (p.window) DestroyWindow(p.window);
}

void prompt_answer(Prompt& p, int id) {
    if (id <= 0) return;
    if (id == kPromptLink) {
        if (p.on_link) p.on_link();
        return;
    }
    if (id <= static_cast<int>(p.switches.size())) {
        p.switches[static_cast<size_t>(id - 1)].on = !p.switches[static_cast<size_t>(id - 1)].on;
        InvalidateRect(p.window, nullptr, FALSE);
        return;
    }
    p.pressed = id;
    close_prompt(p);
}

LRESULT CALLBACK prompt_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    auto* p = reinterpret_cast<Prompt*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_NCCREATE:
            SetWindowLongPtrW(hwnd, GWLP_USERDATA,
                              reinterpret_cast<LONG_PTR>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams));
            break;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT:
            buffered_paint(hwnd, [&](Graphics& g, float w, float) { layout_prompt(g, *p, w, true); });
            return 0;
        case WM_MOUSEMOVE: track_hot(hwnd, lp, p->targets, &p->hot, &p->tracking); return 0;
        case WM_MOUSELEAVE:
            p->tracking = false;
            p->hot = 0;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT) {
                SetCursor(LoadCursorW(nullptr, p->hot ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_LBUTTONDOWN: p->focus = 0; return 0;
        case WM_LBUTTONUP: prompt_answer(*p, find_target(p->targets, lp)); return 0;
        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) {
                close_prompt(*p);
            } else if (wp == VK_RETURN) {
                prompt_answer(*p, p->focus ? p->focus : p->default_id);
            } else if (wp == VK_SPACE && p->focus) {
                prompt_answer(*p, p->focus);
            } else if (wp == VK_TAB || wp == VK_UP || wp == VK_DOWN || wp == VK_LEFT || wp == VK_RIGHT) {
                std::vector<int> ids;
                for (const auto& t : p->targets) ids.push_back(t.second);
                if (!ids.empty()) {
                    const bool back = wp == VK_UP || (wp == VK_TAB && GetKeyState(VK_SHIFT) < 0) || wp == VK_LEFT;
                    const auto it = std::find(ids.begin(), ids.end(), p->focus);
                    int i = it == ids.end() ? (back ? 0 : -1) : static_cast<int>(it - ids.begin());
                    i = ((back ? i - 1 : i + 1) % static_cast<int>(ids.size()) + static_cast<int>(ids.size())) %
                        static_cast<int>(ids.size());
                    p->focus = ids[static_cast<size_t>(i)];
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;
        case WM_CLOSE: close_prompt(*p); return 0;
        case WM_TIMER: close_prompt(*p); return 0;  // no answer in time: nothing changes
        case WM_DESTROY: p->window = nullptr; return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// Shows `p` centred over `over` (screen coordinates) and returns the button pressed (0: none). With an
// owner the dialog is modal to it; without one it stays above every window, as an app's question must.
int show_prompt(Prompt& p, const RECT& over) {
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{sizeof(wc)};
        wc.lpfnWndProc = prompt_proc;
        wc.hInstance = g_inst;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.hIcon = LoadIconW(g_inst, MAKEINTRESOURCEW(1));
        wc.lpszClassName = L"WinApkPrompt";
        RegisterClassExW(&wc);
        registered = true;
    }
    const int w = ipx(p.footer ? 440 : 400);
    HDC dc = GetDC(nullptr);
    float need = 0;
    {
        Graphics g(dc);
        need = layout_prompt(g, p, static_cast<float>(w), false);
    }
    ReleaseDC(nullptr, dc);
    const DWORD style = WS_POPUP | WS_CAPTION | WS_SYSMENU;
    const DWORD ex = (p.owner ? 0 : WS_EX_TOPMOST);
    RECT frame{0, 0, w, static_cast<LONG>(std::lround(need))};
    AdjustWindowRectEx(&frame, style, FALSE, ex);
    const int fw = frame.right - frame.left, fh = frame.bottom - frame.top;
    const int x = (over.left + over.right - fw) / 2;
    const int y = (std::max)(static_cast<int>(over.top), static_cast<int>((over.top + over.bottom - fh) / 2));
    const std::wstring& caption = p.caption.empty() ? p.app : p.caption;
    p.window = CreateWindowExW(ex, L"WinApkPrompt", caption.c_str(), style, x, y, fw, fh, p.owner, nullptr, g_inst, &p);
    if (!p.window) return 0;
    theme_window(p.window);
    const int corner = 2;  // DWMWCP_ROUND, Windows 11, as the splash and the menus
    DwmSetWindowAttribute(p.window, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, &corner, sizeof(corner));
    if (p.timeout) SetTimer(p.window, 1, kAskTimeoutMs, nullptr);
    if (p.owner) EnableWindow(p.owner, FALSE);
    ShowWindow(p.window, SW_SHOW);
    SetForegroundWindow(p.window);
    MSG m;
    while (p.window) {
        const BOOL got = GetMessageW(&m, nullptr, 0, 0);
        if (got <= 0) {
            if (got == 0) PostQuitMessage(static_cast<int>(m.wParam));  // for the loop that ends the program
            close_prompt(p);
            break;
        }
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return p.pressed;
}

RECT work_area_with_cursor() {
    POINT cursor;
    GetCursorPos(&cursor);
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromPoint(cursor, MONITOR_DEFAULTTOPRIMARY), &mi);
    return mi.rcWork;
}

BOOL CALLBACK find_app_window(HWND w, LPARAM lp) {
    auto* p = reinterpret_cast<Prompt*>(lp);
    DWORD pid = 0;
    GetWindowThreadProcessId(w, &pid);
    if (pid == static_cast<DWORD>(reinterpret_cast<uintptr_t>(p->near_window)) && IsWindowVisible(w) &&
        !GetWindow(w, GW_OWNER)) {
        p->near_window = w;
        return FALSE;
    }
    return TRUE;
}

// An app's question: over the window of process `pid`, not owned by it (that would tie two processes'
// input together) but kept above it.
int run_prompt(Prompt& p, DWORD pid) {
    p.near_window = reinterpret_cast<HWND>(static_cast<uintptr_t>(pid));
    EnumWindows(find_app_window, reinterpret_cast<LPARAM>(&p));
    if (reinterpret_cast<uintptr_t>(p.near_window) == pid) p.near_window = nullptr;  // no window found
    p.owner = nullptr;
    p.timeout = true;
    RECT near_r;
    if (!p.near_window || !GetWindowRect(p.near_window, &near_r)) near_r = work_area_with_cursor();
    return show_prompt(p, near_r);
}

// The launcher's own question, modal to `owner` while that is on screen; else on its own, in front, with
// a taskbar button, where the mouse is (an install from Explorer shows no library window).
int run_dialog(Prompt& p, HWND owner) {
    if (p.caption.empty()) p.caption = L"Win.apk";
    p.footer = true;
    p.owner = owner && IsWindowVisible(owner) && !IsIconic(owner) ? owner : nullptr;
    RECT near_r;
    if (!p.owner || !GetWindowRect(p.owner, &near_r)) near_r = work_area_with_cursor();
    return show_prompt(p, near_r);
}

// Asks before doing `action` (a verb: "Sign out", "Clear library"). Enter is Cancel: these undo nothing.
bool confirm(const std::wstring& main, const std::wstring& content, const wchar_t* action) {
    Prompt p;
    p.question = main;
    p.note = content;
    p.buttons = {{IDOK, action}, {IDCANCEL, L"Cancel"}};
    p.default_id = IDCANCEL;
    return run_dialog(p, owner()) == IDOK;
}


std::string read_tail(const fs::path& p, size_t max_bytes);

// The lines of a log that say why an app failed: its errors, exceptions and the exit.
std::wstring log_summary(const fs::path& log, bool* no_native_code) {
    std::vector<std::wstring> hits;
    const std::wstring raw = utf8_to_wide(read_tail(log, 256 * 1024));
    size_t at = 0;
    while (at < raw.size()) {
        size_t end = raw.find(L'\n', at);
        if (end == std::wstring::npos) end = raw.size();
        std::wstring l = raw.substr(at, end - at);
        at = end + 1;
        if (!l.empty() && l.back() == L'\r') l.pop_back();
        if (l.find(L"not shipped in this APK") != std::wstring::npos) *no_native_code = true;
        const bool why = l.rfind(L"[E]", 0) == 0 || l.rfind(L"[F]", 0) == 0 || l.find(L"FATAL") != std::wstring::npos ||
                         l.find(L"Exception:") != std::wstring::npos || l.find(L"Error:") != std::wstring::npos ||
                         l.find(L"System.exit(") != std::wstring::npos;
        if (!why) continue;
        if (l.size() > 220) l = l.substr(0, 220) + L"\x2026";
        hits.push_back(std::move(l));
    }
    std::wstring out;
    for (size_t i = hits.size() > 12 ? hits.size() - 12 : 0; i < hits.size(); ++i) out += hits[i] + L"\n";
    if (!out.empty()) out.pop_back();
    return out;
}

// An error the user can do nothing about but read: the sentence, and Show log.
// Close and Show log, as the install window's and the splash's failures have them.
void error_with_log(const std::wstring& main, const std::wstring& content, const fs::path& log) {
    Prompt p;
    p.question = main;
    p.note = content;
    p.buttons = {{IDCLOSE, L"Close"}, {IDRETRY, L"Show log"}};
    p.default_id = IDCLOSE;
    if (run_dialog(p, owner()) == IDRETRY) show_logs(log);
}

// ---------------------------------------------------------------------------
// Report a problem: GitHub's "An app does not work" form (.github/ISSUE_TEMPLATE/app_problem.yml) opened in the
// browser with what the launcher knows filled in by the fields' ids, and the logs folder with the app's log (and its
// newest crash dump) selected to drag in. Nothing is uploaded by itself: a log can hold the user's name and paths,
// so what goes in the report is the user's choice. The log stays out of the URL too, which GitHub cuts at about 8 KB.

constexpr wchar_t kIssuesUrl[] = L"https://github.com/" WINAPK_REPO "/issues";

// A query value: UTF-8, everything but the unreserved characters percent-encoded.
std::wstring url_escape(const std::wstring& s) {
    static const wchar_t hex[] = L"0123456789ABCDEF";
    std::wstring out;
    for (const unsigned char c : wide_to_utf8(s)) {
        if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' ||
            c == '.' || c == '~') {
            out += static_cast<wchar_t>(c);
        } else {
            out += L'%';
            out += hex[c >> 4];
            out += hex[c & 15];
        }
    }
    return out;
}

// "Windows 11 23H2 (build 22631), Arm64". RtlGetVersion, since GetVersionEx answers what the manifest claims; 11 is a
// build of 10.0 from 22000 on.
std::wstring windows_version() {
    OSVERSIONINFOW v{};
    v.dwOSVersionInfoSize = sizeof(v);
    using RtlGetVersionFn = LONG(WINAPI*)(OSVERSIONINFOW*);
    if (auto get = reinterpret_cast<RtlGetVersionFn>(
            reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion")))) {
        get(&v);
    }
    std::wstring out = L"Windows ";
    if (v.dwMajorVersion == 10 && v.dwBuildNumber >= 22000) out += L"11";
    else out += std::to_wstring(v.dwMajorVersion) + (v.dwMinorVersion ? L"." + std::to_wstring(v.dwMinorVersion) : L"");
    wchar_t display[64] = {};
    DWORD size = sizeof(display);
    if (RegGetValueW(HKEY_LOCAL_MACHINE, L"SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", L"DisplayVersion",
                     RRF_RT_REG_SZ, nullptr, display, &size) == ERROR_SUCCESS && display[0]) {
        out += L" " + std::wstring(display);
    }
    out += L" (build " + std::to_wstring(v.dwBuildNumber) + L")";
    return out + L", Arm64";
}

// The primary display's adapter, as Device Manager names it.
std::wstring gpu_name() {
    DISPLAY_DEVICEW d{};
    d.cb = sizeof(d);
    for (DWORD i = 0; EnumDisplayDevicesW(nullptr, i, &d, 0); ++i) {
        if (d.StateFlags & DISPLAY_DEVICE_PRIMARY_DEVICE) return d.DeviceString;
        d.cb = sizeof(d);
    }
    return {};
}

// The newest logs\crash-<name>-<time>.dmp, if the runtime wrote one.
fs::path newest_dump(const std::wstring& name) {
    const std::wstring prefix = L"crash-" + name + L"-";
    fs::path best;
    fs::file_time_type best_time{};
    std::error_code ec;
    for (fs::directory_iterator it(logs_dir(), ec), end; !ec && it != end; it.increment(ec)) {
        const std::wstring f = it->path().filename().wstring();
        if (f.rfind(prefix, 0) != 0 || it->path().extension() != L".dmp") continue;
        std::error_code ec2;
        const auto t = fs::last_write_time(it->path(), ec2);
        if (!ec2 && (best.empty() || t > best_time)) {
            best = it->path();
            best_time = t;
        }
    }
    return best;
}

// Explorer on the folder of `files`, with all of them selected (show_in_explorer's /select takes only one).
void select_in_explorer(const std::vector<fs::path>& files) {
    if (files.empty()) return;
    PIDLIST_ABSOLUTE folder = ILCreateFromPathW(files.front().parent_path().c_str());
    std::vector<PIDLIST_ABSOLUTE> items;
    for (const fs::path& f : files)
        if (PIDLIST_ABSOLUTE p = ILCreateFromPathW(f.c_str())) items.push_back(p);
    std::vector<PCUITEMID_CHILD> children;
    for (PIDLIST_ABSOLUTE p : items) children.push_back(ILFindLastID(p));
    if (!folder || children.empty() ||
        FAILED(SHOpenFolderAndSelectItems(folder, static_cast<UINT>(children.size()), children.data(), 0))) {
        show_in_explorer(files.front());
    }
    for (PIDLIST_ABSOLUTE p : items) ILFree(p);
    if (folder) ILFree(folder);
}

// Asks first, with a link to the open reports on the same app so a second one is not opened, then opens the form and
// the logs. `name` is the library name, `log` the log that tells what happened, `what` the sentence the user saw.
void report_problem(HWND parent, const std::wstring& name, const std::wstring& label, const fs::path& log,
                    const std::wstring& what) {
    const std::vector<std::string> pkg = package_of(name);
    const std::wstring package = pkg.empty() ? std::wstring() : utf8_to_wide(pkg[0]);
    const std::wstring app_version = pkg.size() > 2 ? utf8_to_wide(pkg[2]) : std::wstring();

    const std::wstring search = std::wstring(kIssuesUrl) + L"?q=" +
                                url_escape(L"is:issue is:open \"" + (package.empty() ? label : package) + L"\"");
    Prompt p;
    p.question = fmt(L"Report a problem with {0}", {label});
    p.note = L"A GitHub form opens with the version and system details filled in. The log is selected in its "
                L"folder for you to attach; nothing is sent automatically.";
    p.link = L"Check whether it was already reported";
    p.on_link = [search] { ShellExecuteW(nullptr, L"open", search.c_str(), nullptr, nullptr, SW_SHOWNORMAL); };
    p.buttons = {{IDOK, L"Open report"}, {IDCANCEL, L"Cancel"}};
    p.default_id = IDOK;
    if (run_dialog(p, parent) != IDOK) return;

    // The form's own title pattern: "<App name> <version>: <what goes wrong>".
    const std::wstring app_line = label + (app_version.empty() ? L"" : L" " + app_version) +
                                  (package.empty() ? L"" : L" (" + package + L")");
    std::wstring url = std::wstring(kIssuesUrl) + L"/new?template=app_problem.yml";
    url += L"&title=" + url_escape(label + (app_version.empty() ? L"" : L" " + app_version) + L": " + what);
    url += L"&winapk-version=" + url_escape(version());
    url += L"&windows-version=" + url_escape(windows_version());
    if (const std::wstring gpu = gpu_name(); !gpu.empty()) url += L"&gpu=" + url_escape(gpu);
    url += L"&app=" + url_escape(app_line);
    if (!what.empty()) url += L"&what-happens=" + url_escape(what);
    ShellExecuteW(nullptr, L"open", url.c_str(), nullptr, nullptr, SW_SHOWNORMAL);

    std::vector<fs::path> files;
    std::error_code ec;
    if (!log.empty() && fs::exists(log, ec)) files.push_back(log);
    if (const fs::path dump = newest_dump(name); !dump.empty()) files.push_back(dump);
    if (files.empty()) open_path(logs_dir());
    else select_in_explorer(files);
}

std::vector<fs::path> pick_files(bool multiple, const wchar_t* title) {
    std::vector<fs::path> out;
    IFileOpenDialog* dlg = nullptr;
    if (FAILED(CoCreateInstance(CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dlg)))) return out;
    const COMDLG_FILTERSPEC types[] = {{L"Android apps", L"*.apk;*.apkm;*.xapk;*.apks"}, {L"All files", L"*.*"}};
    dlg->SetFileTypes(2, types);
    dlg->SetTitle(title);
    DWORD opts = 0;
    dlg->GetOptions(&opts);
    dlg->SetOptions(opts | FOS_FILEMUSTEXIST | (multiple ? static_cast<DWORD>(FOS_ALLOWMULTISELECT) : 0u));
    IShellItemArray* items = nullptr;
    if (SUCCEEDED(dlg->Show(owner())) && SUCCEEDED(dlg->GetResults(&items))) {
        DWORD n = 0;
        items->GetCount(&n);
        for (DWORD i = 0; i < n; ++i) {
            IShellItem* item = nullptr;
            PWSTR path = nullptr;
            if (SUCCEEDED(items->GetItemAt(i, &item)) && SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &path))) {
                out.emplace_back(path);
            }
            CoTaskMemFree(path);
            if (item) item->Release();
        }
        items->Release();
    }
    dlg->Release();
    return out;
}

// ---------------------------------------------------------------------------
// Actions

// The library name for a file, which names its folders (apps\<name>, library\<name>.apk): the
// package when the file is named after it, as the sites that share APKs name them
// ("com.waze_5.26.5.0-1030744_1arch_2dpi_24lang_<hash>_apkmirror.com.apkm" -> "com.waze"); else
// the file's name up to its version ("WhatsApp_2.24.1" -> "WhatsApp"). An app the library has
// under that package keeps its name, so the file updates it.
std::wstring install_name(const fs::path& source) {
    const std::wstring stem = source.stem().wstring();
    auto letter = [](wchar_t c) { return (c >= L'a' && c <= L'z') || (c >= L'A' && c <= L'Z'); };
    auto digit = [](wchar_t c) { return c >= L'0' && c <= L'9'; };
    // A leading package: dot-separated identifiers, each starting with a letter.
    size_t end = 0;
    int parts = 0;
    while (end < stem.size() && letter(stem[end])) {
        size_t j = end + 1;
        while (j < stem.size() && (letter(stem[j]) || digit(stem[j]) || stem[j] == L'_')) ++j;
        ++parts;
        end = j;
        if (end + 1 < stem.size() && stem[end] == L'.' && letter(stem[end + 1])) ++end;
        else break;
    }
    std::wstring name;
    if (parts >= 2) {
        name = stem.substr(0, end);
        // "waze_5" took the version's first number in: an identifier ends where "_<digit>" starts.
        for (size_t k = name.rfind(L'.') + 1; k + 1 < name.size(); ++k) {
            if (name[k] == L'_' && digit(name[k + 1])) {
                name.resize(k);
                break;
            }
        }
        const wchar_t next = end < stem.size() ? stem[end] : L'_';
        if (!wcschr(L"_- (.", next)) name.clear();  // "com.example.app!" is not a package
    }
    if (name.empty()) {
        name = stem;
        for (size_t k = 1; k + 1 < name.size(); ++k) {
            const bool sep = name[k] == L'_' || name[k] == L' ' || name[k] == L'-';
            const size_t v = k + 1 + ((name[k + 1] == L'v' || name[k + 1] == L'V') ? 1 : 0);
            if (sep && v < name.size() && digit(name[v])) {
                name.resize(k);
                break;
            }
        }
        while (!name.empty() && name.back() == L' ') name.pop_back();
        if (name.empty()) name = stem;
    }
    const auto it = g_store.installed.find(wide_to_utf8(name));
    if (it != g_store.installed.end() && find_app(it->second.first)) return it->second.first;
    return file_name_safe(name);
}

// Queues an install. The store's (`from_store`) installs a download it then
// removes, and shows its progress on the app's card rather than in the
// install window.
void install(const fs::path& source, std::wstring name = L"", fs::path replaces = {}, bool from_store = false) {
    if (name.empty()) name = install_name(source);
    if (is_pending(name)) return;  // the same file twice
    if (!any_pending() && !g_install_wnd) g_jobs.clear();
    begin_install(name);
    Job job{name};
    job.file = source.filename().wstring();
    job.update = find_app(name) != nullptr;
    g_jobs.push_back(job);
    CloseHandle(CreateThread(nullptr, 0, install_thread, new InstallJob{source, name, replaces, from_store}, 0,
                             nullptr));
    update_timer();
    rebuild_tiles();
    if (!from_store || g_install_wnd) show_install_window();
}

void add_from_dialog() {
    for (const fs::path& p : pick_files(true, L"Install an Android app")) install(p);
}

Splash* show_splash(const std::wstring& name, bool compact = false);
HWND start_window(const std::wstring& name);

// Brings the window of an app started from here to the front.
void bring_forward(const std::wstring& name) {
    const DWORD pid = run_pid(name);
    struct Find {
        DWORD pid;
        HWND found;
    } f{pid, nullptr};
    if (pid) {
        EnumWindows([](HWND w, LPARAM lp) -> BOOL {
            auto* f = reinterpret_cast<Find*>(lp);
            DWORD p = 0;
            GetWindowThreadProcessId(w, &p);
            if (p != f->pid || !IsWindowVisible(w) || GetWindow(w, GW_OWNER)) return TRUE;
            f->found = w;
            return FALSE;
        }, reinterpret_cast<LPARAM>(&f));
    }
    if (f.found) {
        if (IsIconic(f.found)) ShowWindow(f.found, SW_RESTORE);
        SetForegroundWindow(f.found);
    }
}

// Opens the app: its splash at once, the runtime on a thread (run_thread), which reports back.
void launch(const App& app) {
    if (is_pending(app.name)) {
        info(fmt(L"{0} is still being installed.", {app.label}), kInfo);
        return;
    }
    if (!begin_run(app.name)) {
        bring_forward(app.name);
        return;
    }
    // The app's window opens over the starting window, and takes its place (host/win32_window.cpp).
    auto* job = new RunJob{app.name, app.apk, {}};
    if (const HWND start = start_window(app.name)) {
        job->env.push_back(L"WINAPK_START_WINDOW=" + std::to_wstring(reinterpret_cast<uintptr_t>(start)));
    }
    CloseHandle(CreateThread(nullptr, 0, run_thread, job, 0, nullptr));
    update_timer();
}

void appops_forget(const std::string& pkg);
std::vector<std::string> package_of(const std::wstring& name);
namespace appops {
bool stop_runtime_of(const fs::path& apk);
}

// Deletes a folder and what is in it without going through a junction or link inside it (the
// shared-storage folders are junctions to the user's own folders): the link goes, not its target.
void remove_tree(const fs::path& dir) {
    const DWORD attrs = GetFileAttributesW(dir.c_str());
    if (attrs == INVALID_FILE_ATTRIBUTES) return;
    if (!(attrs & FILE_ATTRIBUTE_DIRECTORY)) {
        SetFileAttributesW(dir.c_str(), FILE_ATTRIBUTE_NORMAL);
        DeleteFileW(dir.c_str());
        return;
    }
    if (!(attrs & FILE_ATTRIBUTE_REPARSE_POINT)) {
        WIN32_FIND_DATAW fd;
        HANDLE h = FindFirstFileW((dir / L"*").c_str(), &fd);
        if (h != INVALID_HANDLE_VALUE) {
            do {
                if (wcscmp(fd.cFileName, L".") != 0 && wcscmp(fd.cFileName, L"..") != 0) remove_tree(dir / fd.cFileName);
            } while (FindNextFileW(h, &fd));
            FindClose(h);
        }
    }
    RemoveDirectoryW(dir.c_str());
}

// The app's own files: its library entry, converted code and shortcuts' icon. What it saved stays.
void remove_app_files(const std::wstring& name) {
    std::error_code ec;
    fs::remove(label_file(name), ec);
    fs::remove(package_file(name), ec);
    fs::remove(shortcut_icon(name), ec);
    fs::remove(library_dir() / (name + L".window"), ec);
    fs::remove(orientation_file(name), ec);
    remove_tree(app_dir(name));
    fs::remove(data_dir() / L"android" / L"data" / L"app" / (L"." + name + L".installed"), ec);
}

// What the app saved, as Android's "Clear storage" and uninstall take it: its data folder
// (android\data\data\<package>, and the <package>_<version> folders older runs made) and its folders
// in shared storage (Android\data and Android\obb).
void remove_app_data(const std::string& pkg) {
    if (pkg.empty() || pkg.find_first_of("/\\:") != std::string::npos || pkg == "." || pkg == "..") return;
    const std::wstring p = utf8_to_wide(pkg);
    const fs::path android = data_dir() / L"android";
    remove_tree(android / L"data" / L"data" / p);
    std::error_code ec;
    for (const auto& e : fs::directory_iterator(android / L"data" / L"data", ec)) {
        if (e.path().filename().wstring().rfind(p + L"_", 0) == 0) remove_tree(e.path());
    }
    remove_tree(android / L"sdcard" / L"Android" / L"data" / p);
    remove_tree(android / L"sdcard" / L"Android" / L"obb" / p);
}

// Asks before removing: Remove, with a box for what the app saved (off: it stays, so
// installing the app again picks up where it left off).
bool confirm_remove(const App& a, bool* with_data) {
    Prompt p;
    p.caption = L"Win.apk";
    p.question = fmt(L"Remove {0}", {a.label});
    p.note = L"Removes the app and its shortcuts.";
    p.switches = {{L"Also remove accounts and data saved by the app", false, true}};
    p.buttons = {{IDOK, L"Remove"}, {IDCANCEL, L"Cancel"}};
    p.default_id = IDCANCEL;
    if (run_dialog(p, owner()) != IDOK) return false;
    *with_data = p.switches[0].on;
    return true;
}

// Removes the app (asking first when `ask`); `with_data` also deletes what it saved.
bool remove_app(const App& app, bool ask, bool with_data = false) {
    if (ask && !confirm_remove(app, &with_data)) return false;
    const App copy = app;  // `app` may point into g_apps, which refresh() replaces
    std::error_code ec;
    if (app_running(copy.name)) {
        if (!appops::stop_runtime_of(copy.apk)) {
            info(fmt(L"Close {0} first, then remove it.", {copy.label}), kError);
            return false;
        }
        expect_run_end(copy.name);  // started from here: its exit is the removal, not a failure
    }
    // Its permissions go with it, unless another copy in the library is the same package.
    const std::vector<std::string> pkg = package_of(copy.name);
    if (!pkg.empty()) {
        bool other = false;
        for (const App& a : library()) {
            const std::vector<std::string> p = a.name == copy.name ? std::vector<std::string>{} : package_of(a.name);
            other = other || (!p.empty() && p[0] == pkg[0]);
        }
        if (!other) appops_forget(pkg[0]);
        if (!other && with_data) remove_app_data(pkg[0]);
    }
    remove_app_shortcuts(copy);
    fs::remove(copy.apk, ec);
    remove_app_files(copy.name);
    write_registry();
    if (ask) {
        refresh();
        if (g_view == kAppView) set_view(kLibraryView);
        info(fmt(with_data ? L"{0} and what it saved were removed." : L"{0} was removed.", {copy.label}), kInfo);
    }
    return true;
}

// Opens this app in the given orientation from now on (kOrientUnset: the
// settings' again). The window size the user gave it was for the other
// shape, so it goes too, and the app opens at the settings' screen size.
void set_orientation(const App& app, int o) {
    std::error_code ec;
    set_app_orientation(app.name, o);
    fs::remove(library_dir() / (app.name + L".window"), ec);
    const int now = effective_orientation(app.name);
    info(fmt(now == kLandscape ? L"{0} opens in landscape from now on." : L"{0} opens in portrait from now on.",
                   {app.label}),
               kSuccess);
}

// Fetches the catalog: the one kept from last time at once, F-Droid's when
// that is old or `force` asks for it.
void store_start(bool force) {
    if (g_store.loading()) return;
    g_store.fetch = StoreState::Fetch::kLoading;
    g_store.got_kb = 0;
    if (HANDLE t = CreateThread(nullptr, 0, catalog_thread, force ? &g_store : nullptr, 0, nullptr)) CloseHandle(t);
    else g_store.fetch = StoreState::Fetch::kFailed;  // no thread: nothing was fetched
    update_timer();
    InvalidateRect(g_main, nullptr, FALSE);
}

// The card's button: install or update the app (download, check, then the
// library's install).
void store_get(const StoreApp& a) {
    const Offer offer = offer_for(a);
    if (offer != kOfferInstall && offer != kOfferUpdate) return;
    g_store.downloads[a.pkg] = -1;
    CloseHandle(CreateThread(nullptr, 0, download_thread,
                             new Download{a.pkg, a.sha256, std::wstring(kRepo) + utf8_to_wide(a.apk), a.name,
                                          downloads_dir() / utf8_to_wide(a.apk)},
                             0, nullptr));
    info(fmt(L"Downloading {0}…", {a.name}), kInfo);
    InvalidateRect(g_main, nullptr, FALSE);
}

// A download is in and checked: it is installed as the app the library has
// with that package, if any, else as a new one named after the package.
void store_downloaded(const Download& d, bool ok) {
    g_store.downloads.erase(d.pkg);
    if (!ok) {
        info(fmt(L"Downloading {0} failed. Check the internet connection, then try again.", {d.label}), kError);
        return;
    }
    const auto it = g_store.installed.find(d.pkg);
    const App* existing = it == g_store.installed.end() ? nullptr : find_app(it->second.first);
    const std::wstring name = existing ? existing->name : file_name_safe(utf8_to_wide(d.pkg));
    if (!existing) g_job_labels[name] = d.label;
    install(d.file, name, existing ? existing->apk : fs::path(), true);
}

// ---------------------------------------------------------------------------
// Menus
//
// The library's own popup menu, drawn as Windows 11 draws a flyout menu: a rounded card with a
// shadow, a glyph per item, its shortcut at the end, a destructive item in red, and over the
// items, for an app's menu, the app's icon and name. The system menu ignores the theme and draws
// no pictures. It runs modally, as TrackPopupMenu does: the owner keeps the focus and so the
// keyboard, the menu holds the mouse capture, and a click elsewhere or another window closes it.

struct MenuItem {
    int id = 0;  // 0: a separator
    std::wstring text, shortcut;
    const wchar_t* glyph = nullptr;
    bool destructive = false, disabled = false, checked = false;
};

struct PopupMenu {
    std::vector<MenuItem> items;
    const App* app = nullptr;  // a header with its icon and name
    std::wstring title, sub;   // the header's lines
    std::vector<RectF> rects;  // where each item is, in the menu's client area
    float header_h = 0, w = 0, h = 0;
    int hot = -1, result = 0;
    bool done = false;
};
PopupMenu* g_menu = nullptr;

constexpr wchar_t kMenuClass[] = L"WinApkMenu";

bool menu_item_usable(const MenuItem& it) { return it.id != 0 && !it.disabled; }

void lay_out_menu(PopupMenu& m) {
    const float pad = px(4), item_h = px(34), sep_h = px(9);
    float text_w = 0, key_w = 0;
    for (const MenuItem& it : m.items) {
        if (!it.id) continue;
        text_w = (std::max)(text_w, measure(it.text, g_f.body.get()));
        if (!it.shortcut.empty()) key_w = (std::max)(key_w, measure(it.shortcut, g_f.caption.get()));
    }
    m.w = (std::max)(px(200), px(44) + text_w + (key_w > 0 ? px(28) + key_w : 0) + px(16)) + 2 * pad;
    if (!m.title.empty()) {
        const float tw = (std::max)(measure(m.title, g_f.body_semi.get()), measure(m.sub, g_f.caption.get()));
        m.w = (std::min)(px(340), (std::max)(m.w, px(64) + tw + px(16)));
        m.header_h = px(60);
    }
    float y = pad + m.header_h;
    m.rects.clear();
    for (const MenuItem& it : m.items) {
        const float ih = it.id ? item_h : sep_h;
        m.rects.emplace_back(pad, y, m.w - 2 * pad, ih);
        y += ih;
    }
    m.h = y + pad;
}

void paint_menu(Graphics& g, float W, float H) {
    const PopupMenu& m = *g_menu;
    g.Clear(g_t.dark ? Color(255, 44, 44, 44) : Color(255, 249, 249, 249));
    Gdiplus::Pen edge(g_t.border, 1);
    g.DrawRectangle(&edge, RectF(0, 0, W - 1, H - 1));
    if (m.header_h > 0) {
        const float s = px(36);
        const RectF ir(px(14), (m.header_h - s) / 2 + px(2), s, s);
        draw_app_icon(g, m.app, m.title, ir);
        const float tx = ir.GetRight() + px(12), tw = W - tx - px(12);
        draw_text(g, m.title, g_f.body_semi.get(), g_t.text, RectF(tx, ir.Y - px(1), tw, px(20)), kLeft, false, true);
        draw_text(g, m.sub, g_f.caption.get(), g_t.text2, RectF(tx, ir.Y + px(19), tw, px(18)), kLeft, false, true);
        Gdiplus::Pen line(g_t.divider, 1);
        g.DrawLine(&line, 0.0f, m.header_h, W, m.header_h);
    }
    for (size_t i = 0; i < m.items.size(); ++i) {
        const MenuItem& it = m.items[i];
        const RectF& r = m.rects[i];
        if (!it.id) {
            Gdiplus::Pen line(g_t.divider, 1);
            g.DrawLine(&line, r.X + px(8), r.Y + r.Height / 2, r.GetRight() - px(8), r.Y + r.Height / 2);
            continue;
        }
        const bool hot = static_cast<int>(i) == m.hot && !it.disabled;
        if (hot) fill_round(g, r, px(4), g_t.dark ? Color(255, 61, 61, 61) : Color(255, 234, 234, 234));
        const Color ink = it.disabled ? g_t.text3 : it.destructive ? g_t.danger : g_t.text;
        const wchar_t* glyph = it.checked ? kGlyphCheck : it.glyph;
        if (glyph) {
            draw_text(g, glyph, g_f.glyph16.get(), it.checked ? g_t.accent : ink,
                      RectF(r.X + px(10), r.Y, px(20), r.Height), kCenter);
        }
        draw_text(g, it.text, g_f.body.get(), ink, RectF(r.X + px(40), r.Y, r.Width - px(48), r.Height), kLeft,
                  false, true);
        if (!it.shortcut.empty()) {  // at the item's end, sized to it
            const float kw = text_width(g, it.shortcut, g_f.caption.get()) + px(2);
            draw_text(g, it.shortcut, g_f.caption.get(), it.disabled ? g_t.text3 : g_t.text2,
                      RectF(r.GetRight() - px(12) - kw, r.Y, kw, r.Height), kLeft);
        }
    }
}

int menu_item_at(const PopupMenu& m, float x, float y) {
    for (size_t i = 0; i < m.rects.size(); ++i) {
        if (contains(m.rects[i], x, y)) return static_cast<int>(i);
    }
    return -1;
}

LRESULT CALLBACK menu_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    PopupMenu* m = g_menu;
    switch (msg) {
        case WM_MOUSEACTIVATE: return MA_NOACTIVATE;
        case WM_PAINT:
            if (m) buffered_paint(hwnd, paint_menu);
            else ValidateRect(hwnd, nullptr);
            return 0;
        case WM_ERASEBKGND: return 1;
        case WM_MOUSEMOVE:
            if (m) {
                const int hot = menu_item_at(*m, static_cast<float>(GET_X_LPARAM(lp)), static_cast<float>(GET_Y_LPARAM(lp)));
                if (hot != m->hot) {
                    m->hot = hot;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;
        case WM_LBUTTONDOWN:
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN: {
            RECT rc;
            GetClientRect(hwnd, &rc);
            const POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            if (m && !PtInRect(&rc, pt)) m->done = true;  // a click outside closes the menu, as a menu's does
            return 0;
        }
        case WM_LBUTTONUP:
        case WM_RBUTTONUP:
            if (m) {
                const int i = menu_item_at(*m, static_cast<float>(GET_X_LPARAM(lp)), static_cast<float>(GET_Y_LPARAM(lp)));
                if (i >= 0 && menu_item_usable(m->items[static_cast<size_t>(i)])) {
                    m->result = m->items[static_cast<size_t>(i)].id;
                    m->done = true;
                }
            }
            return 0;
        case WM_CAPTURECHANGED:
            if (m && reinterpret_cast<HWND>(lp) != hwnd) m->done = true;
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void menu_move(PopupMenu& m, int step) {
    const int n = static_cast<int>(m.items.size());
    int i = m.hot;
    for (int k = 0; k < n; ++k) {
        i = i < 0 ? (step > 0 ? 0 : n - 1) : (i + step + n) % n;
        if (menu_item_usable(m.items[static_cast<size_t>(i)])) {
            m.hot = i;
            return;
        }
    }
}

// Shows `m` at the screen point `pt` (its top start corner there) and returns the item picked, or 0.
// `below`: the top of the control it opens under, for a menu that does not fit below it.
int run_menu(PopupMenu& m, POINT pt, LONG below = LONG_MIN, bool keyboard = false) {
    static bool registered = false;
    if (!registered) {
        WNDCLASSW wc{};
        wc.style = CS_DROPSHADOW;
        wc.lpfnWndProc = menu_proc;
        wc.hInstance = g_inst;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = kMenuClass;
        registered = RegisterClassW(&wc) != 0;
    }
    lay_out_menu(m);
    const int w = static_cast<int>(std::ceil(m.w)), h = static_cast<int>(std::ceil(m.h));
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST), &mi);
    const RECT& wa = mi.rcWork;
    int x = pt.x, y = pt.y;
    if (x + w > wa.right) x = (std::max)(wa.left, static_cast<LONG>(pt.x - w));
    if (x < wa.left) x = wa.left;
    if (y + h > wa.bottom) y = (std::max)(wa.top, (below != LONG_MIN ? below : pt.y) - h);
    if (keyboard) menu_move(m, 1);
    g_menu = &m;
    HWND hwnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_TOPMOST | WS_EX_NOACTIVATE, kMenuClass,
                                L"", WS_POPUP, x, y, w, h, g_main, nullptr, g_inst, nullptr);
    if (!hwnd) {
        g_menu = nullptr;
        return 0;
    }
    const DWORD round = 2;  // DWMWCP_ROUND: Windows 11 rounds the corners; older Windows ignores it
    DwmSetWindowAttribute(hwnd, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, &round, sizeof round);
    ShowWindow(hwnd, SW_SHOWNOACTIVATE);
    SetCapture(hwnd);
    MSG msg;
    bool quit = false;
    while (!m.done) {
        if (!GetMessageW(&msg, nullptr, 0, 0)) {
            quit = true;
            break;
        }
        if (msg.message == WM_KEYDOWN || msg.message == WM_SYSKEYDOWN) {
            switch (msg.wParam) {
                case VK_ESCAPE:
                case VK_MENU:
                case VK_F10: m.done = true; break;
                case VK_UP: menu_move(m, -1); break;
                case VK_DOWN: menu_move(m, 1); break;
                case VK_HOME: m.hot = -1; menu_move(m, 1); break;
                case VK_END: m.hot = -1; menu_move(m, -1); break;
                case VK_RETURN:
                case VK_SPACE:
                    if (m.hot >= 0 && menu_item_usable(m.items[static_cast<size_t>(m.hot)])) {
                        m.result = m.items[static_cast<size_t>(m.hot)].id;
                        m.done = true;
                    }
                    break;
            }
            InvalidateRect(hwnd, nullptr, FALSE);
            continue;
        }
        if (msg.message == WM_CHAR || msg.message == WM_SYSCHAR || msg.message == WM_KEYUP ||
            msg.message == WM_SYSKEYUP) {
            continue;
        }
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
        if (GetForegroundWindow() != g_main) m.done = true;  // Alt+Tab, another program in front
    }
    if (GetCapture() == hwnd) ReleaseCapture();
    DestroyWindow(hwnd);
    g_menu = nullptr;
    if (quit) PostQuitMessage(static_cast<int>(msg.wParam));
    return m.result;
}

// A choice list under a control: the choices, the current one checked.
int run_choice_menu(const std::vector<std::wstring>& choices, int current, const RectF& under) {
    PopupMenu m;
    for (size_t i = 0; i < choices.size(); ++i) {
        MenuItem it;
        it.id = static_cast<int>(i) + 1;
        it.text = choices[i];
        it.checked = static_cast<int>(i) == current;
        m.items.push_back(it);
    }
    POINT pt{static_cast<LONG>(under.X), static_cast<LONG>(under.GetBottom() + px(4))};
    POINT top{static_cast<LONG>(under.X), static_cast<LONG>(under.Y - px(4))};
    ClientToScreen(g_main, &pt);
    ClientToScreen(g_main, &top);
    return run_menu(m, pt, top.y);
}

// The categories, in a menu of a few columns so that a hundred of them fit.
void choose_category() {
    std::set<std::wstring, bool (*)(const std::wstring&, const std::wstring&)> all(
        [](const std::wstring& a, const std::wstring& b) {
            return CompareStringEx(LOCALE_NAME_USER_DEFAULT, NORM_IGNORECASE, a.c_str(), -1, b.c_str(), -1, nullptr,
                                   nullptr, 0) == CSTR_LESS_THAN;
        });
    for (const StoreApp& a : g_store.apps) all.insert(a.categories.begin(), a.categories.end());
    const std::vector<std::wstring> list(all.begin(), all.end());
    HMENU menu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING | (g_store.category.empty() ? MF_CHECKED : 0), 1, L"All categories");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    const size_t per_column = (std::max)(size_t{20}, (list.size() + 4) / 5);
    for (size_t i = 0; i < list.size(); ++i) {
        const UINT flags = MF_STRING | (list[i] == g_store.category ? MF_CHECKED : 0) |
                           (i > 0 && i % per_column == 0 ? MF_MENUBARBREAK : 0);
        AppendMenuW(menu, flags, i + 2, list[i].c_str());
    }
    POINT pt{static_cast<LONG>(g_l.category.X), static_cast<LONG>(g_l.category.GetBottom() + px(2))};
    ClientToScreen(g_main, &pt);
    const int pick = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_TOPALIGN | TPM_LEFTALIGN, pt.x, pt.y, 0, g_main,
                                    nullptr);
    DestroyMenu(menu);
    if (pick <= 0) return;
    g_store.category = pick == 1 ? L"" : list[static_cast<size_t>(pick - 2)];
    g_scroll = 0;
    rebuild_tiles();
}

// ---------------------------------------------------------------------------
// Pages: Settings, an app's details and a store app's details, drawn in the
// content layer in place of the grid, as rows grouped in cards (Windows 11's
// Settings, upstream's grouped forms).

struct Row {
    enum Kind { kText, kButton, kToggle, kChoice } kind = kText;
    std::wstring title, detail, value;  // value: the button's text, or the text on the right
    bool on = false, destructive = false, primary = false, check = false;
    std::vector<std::wstring> choices;
    int choice = 0;
    std::function<void(int)> act;  // button: act(0), toggle: act(new state), choice: act(index)
    const char* key = nullptr;     // names a row that an event changes in place (update_progress)
};

struct Section {
    std::wstring title;
    std::vector<Row> rows;
    std::wstring footer;
};

struct Page {
    std::wstring title, sub;                   // the heading: Settings, or the app's name and what it is
    std::wstring app;                          // the library app whose icon heads it
    std::function<Gdiplus::Bitmap*()> icon;    // or a store app's icon
    bool back = false;                         // a details page: the arrow goes back to `from`
    View from = kLibraryView;
    std::function<std::vector<Section>()> build;
    std::vector<Section> sections;
    float scroll = 0, content_h = 0;
    struct Target {
        RectF r;
        int section, row;
    };
    std::vector<Target> targets;
    RectF back_r, thumb, view;
    int hot = -1;    // index into targets, kPageBack, kPageThumb
    int focus = -1;  // the target reached with Tab: it shows a focus ring
    bool thumb_drag = false;
    float thumb_y0 = 0, thumb_scroll0 = 0;
    int generation = 0;  // a new page was opened: an action's rebuild is not for it
    int reveal = -1;     // a section to scroll to the top on the next paint
} g_page;

constexpr int kPageBack = -2, kPageThumb = -3;

SizeF control_size(Graphics& g, const Row& row) {
    switch (row.kind) {
        case Row::kButton: return SizeF(text_width(g, row.value, g_f.body_semi.get()) + px(32), px(32));
        case Row::kToggle: return SizeF(px(44), px(22));
        case Row::kChoice: {
            float w = 0;
            for (const auto& c : row.choices) w = (std::max)(w, text_width(g, c, g_f.body.get()));
            return SizeF((std::min)(px(250), w + px(48)), px(32));
        }
        case Row::kText:
            if (row.value.empty()) return SizeF(0, 0);
            return SizeF((std::min)(px(260), text_width(g, row.value, g_f.body.get()) + (row.check ? px(22) : 0)),
                         px(22));
    }
    return SizeF(0, 0);
}

void paint_control(Graphics& g, const Row& row, const RectF& r, bool hot) {
    switch (row.kind) {
        case Row::kButton: draw_button(g, r, row.value, hot, row.primary, row.destructive); break;
        case Row::kToggle: draw_switch(g, row.on, r, hot); break;
        case Row::kChoice: {
            fill_round(g, r, px(kRadiusControl), hot ? g_t.hover : g_t.card);
            stroke_round(g, r, px(kRadiusControl), g_t.border);
            const std::wstring& v = row.choices.empty() ? std::wstring() : row.choices[static_cast<size_t>(row.choice)];
            draw_text(g, v, g_f.body.get(), g_t.text, RectF(r.X + px(12), r.Y, r.Width - px(40), r.Height));
            draw_text(g, kGlyphChevron, g_f.glyph12.get(), g_t.text2,
                      RectF(r.GetRight() - px(30), r.Y, px(22), r.Height), kCenter);
            break;
        }
        case Row::kText: {
            float x = r.X;
            if (row.check) {
                draw_text(g, kGlyphCheck, g_f.glyph16.get(), g_t.success, RectF(x, r.Y, px(18), r.Height), kCenter);
                x += px(22);
            }
            draw_text(g, row.value, g_f.body.get(), g_t.text2, RectF(x, r.Y, r.GetRight() - x, r.Height), kRight);
            break;
        }
    }
}

float page_max_scroll() { return (std::max)(0.0f, g_page.content_h - g_page.view.Height); }

void paint_page(Graphics& g) {
    Page& s = g_page;
    s.targets.clear();
    const RectF& c = g_l.content;
    const float m = c.X + px(28), pad = px(16);
    const float cw = (std::min)(px(860), c.Width - px(56));
    float x = m;
    if (s.back) {
        s.back_r = RectF(m - px(6), px(22), px(36), px(36));
        draw_icon_button(g, s.back_r, kGlyphBack, s.hot == kPageBack, g_t.text);
        if (s.focus == kPageBack) draw_focus_ring(g, s.back_r, px(kRadiusControl));
        x += px(44);
    } else {
        s.back_r = RectF();
    }
    float header = px(64);
    if (!s.app.empty() || s.icon) {
        const RectF ir(x, px(18), px(48), px(48));
        if (s.icon) draw_icon(g, s.icon(), s.title, ir);
        else draw_app_icon(g, find_app(s.app), s.title, ir);
        draw_text(g, s.title, g_f.title.get(), g_t.text, RectF(ir.GetRight() + px(14), px(18), c.GetRight() - ir.GetRight() - px(40), px(28)));
        draw_text(g, s.sub, g_f.caption.get(), g_t.text2, RectF(ir.GetRight() + px(14), px(46), c.GetRight() - ir.GetRight() - px(40), px(20)));
        header = px(82);
    } else {
        draw_text(g, s.title, g_f.title.get(), g_t.text, RectF(x, px(24), cw, px(28)));
    }
    s.view = RectF(c.X, header, c.Width, (std::max)(0.0f, c.GetBottom() - header));
    s.scroll = (std::min)(s.scroll, page_max_scroll());
    g.SetClip(s.view);
    float y = s.view.Y + px(8) - s.scroll;
    float reveal_at = -1;
    for (size_t si = 0; si < s.sections.size(); ++si) {
        const Section& sec = s.sections[si];
        if (static_cast<int>(si) == s.reveal) reveal_at = y + s.scroll - s.view.Y - px(8);
        if (!sec.title.empty()) {
            draw_text(g, sec.title, g_f.body_semi.get(), g_t.text, RectF(m, y, cw, px(22)));
            y += px(28);
        }
        std::vector<float> heights;
        std::vector<SizeF> controls;
        float total = 0;
        for (const Row& row : sec.rows) {
            const SizeF cs = control_size(g, row);
            const float tw = cw - 2 * pad - (cs.Width > 0 ? cs.Width + px(16) : 0);
            float h = px(20);
            if (!row.detail.empty()) h += px(2) + text_height(g, row.detail, g_f.caption.get(), tw);
            heights.push_back((std::max)(px(56), h + px(24)));
            controls.push_back(cs);
            total += heights.back();
        }
        const RectF card(m, y, cw, total);
        fill_round(g, card, px(kRadiusCard), g_t.card);
        float ry = y;
        for (size_t ri = 0; ri < sec.rows.size(); ++ri) {
            const Row& row = sec.rows[ri];
            const SizeF cs = controls[ri];
            const float h = heights[ri];
            const RectF rr(m, ry, cw, h);
            // A switch takes the whole row (its label turns it too); a button or a list only itself.
            const bool interactive = row.kind != Row::kText;
            const int index = static_cast<int>(s.targets.size());
            const bool hot = interactive && s.hot == index, focused = interactive && s.focus == index;
            if (row.kind == Row::kToggle && hot) {
                GraphicsPath clip;
                rounded(clip, card, px(kRadiusCard));
                g.SetClip(&clip, Gdiplus::CombineModeIntersect);
                Gdiplus::SolidBrush b(g_t.hover);
                g.FillRectangle(&b, rr);
                g.SetClip(s.view);
            }
            if (ri > 0) {
                Gdiplus::Pen line(g_t.divider, 1);
                g.DrawLine(&line, m + pad, ry, m + cw - pad, ry);
            }
            const float tw = cw - 2 * pad - (cs.Width > 0 ? cs.Width + px(16) : 0);
            const float dh = row.detail.empty() ? 0 : px(2) + text_height(g, row.detail, g_f.caption.get(), tw);
            const float ty = ry + (h - px(20) - dh) / 2;
            draw_text(g, row.title, g_f.body.get(), row.destructive && row.kind == Row::kText ? g_t.danger : g_t.text,
                      RectF(m + pad, ty, tw, px(20)));
            if (!row.detail.empty()) {
                draw_text(g, row.detail, g_f.caption.get(), g_t.text2, RectF(m + pad, ty + px(22), tw, dh + px(2)),
                          kLeft, true, false);
            }
            if (cs.Width > 0) {
                const RectF cr(m + cw - pad - cs.Width, ry + (h - cs.Height) / 2, cs.Width, cs.Height);
                paint_control(g, row, cr, hot);
                if (focused) draw_focus_ring(g, cr, row.kind == Row::kToggle ? cr.Height / 2 : px(kRadiusControl));
                if (interactive) s.targets.push_back({row.kind == Row::kToggle ? rr : cr, static_cast<int>(si),
                                                      static_cast<int>(ri)});
            }
            ry += h;
        }
        stroke_round(g, card, px(kRadiusCard), g_t.border);
        y = ry + px(8);
        if (!sec.footer.empty()) {
            const float fh = text_height(g, sec.footer, g_f.caption.get(), cw - px(8));
            draw_text(g, sec.footer, g_f.caption.get(), g_t.text2, RectF(m + px(4), y, cw - px(8), fh + px(2)), kLeft,
                      true, false);
            y += fh + px(4);
        }
        y += px(20);
    }
    s.content_h = y + s.scroll - s.view.Y + px(56);  // room for the InfoBar at the end
    g.ResetClip();
    if (s.reveal >= 0) {  // known only now that the page is laid out: painted again there
        s.reveal = -1;
        s.scroll = (std::max)(0.0f, (std::min)(reveal_at, page_max_scroll()));
        InvalidateRect(g_main, nullptr, FALSE);
    }
    if (s.content_h > s.view.Height) {
        const float h = (std::max)(px(36), s.view.Height * s.view.Height / s.content_h);
        const float ty = s.view.Y + px(4) + (s.view.Height - px(8) - h) * s.scroll / page_max_scroll();
        s.thumb = RectF(c.GetRight() - px(10), ty, px(6), h);
        fill_round(g, s.thumb, px(3), with_alpha(g_t.text2, s.thumb_drag || s.hot == kPageThumb ? 200 : 90));
        if (s.scroll > 0) {
            Gdiplus::Pen line(g_t.border, 1);
            g.DrawLine(&line, c.X + px(16), s.view.Y, c.GetRight() - px(16), s.view.Y);
        }
    } else {
        s.thumb = RectF();
    }
}

int page_hit(float x, float y) {
    const Page& s = g_page;
    if (s.back && contains(s.back_r, x, y)) return kPageBack;
    if (!contains(s.view, x, y)) return -1;
    if (s.thumb.Width > 0 && contains(inset(s.thumb, -px(4), 0), x, y)) return kPageThumb;
    for (size_t i = 0; i < s.targets.size(); ++i) {
        if (contains(s.targets[i].r, x, y)) return static_cast<int>(i);
    }
    return -1;
}

void page_scroll(float by) {
    g_page.scroll = (std::max)(0.0f, (std::min)(g_page.scroll + by, page_max_scroll()));
    InvalidateRect(g_main, nullptr, FALSE);
}

// Builds the page again from what is true now (after an action, or when the language changed).
void rebuild_page() {
    if (!page_view() || !g_page.build) return;
    g_page.sections = g_page.build();
    InvalidateRect(g_main, nullptr, FALSE);
}

void page_back() {
    if (g_page.back) set_view(g_page.from);
}

// Activates a target: the back arrow, a button row, a switch, or a list (its menu opens under the control).
void page_click(int hit) {
    Page& s = g_page;
    if (hit == kPageBack) {
        page_back();
        return;
    }
    if (hit < 0 || hit >= static_cast<int>(s.targets.size())) return;
    const Page::Target t = s.targets[static_cast<size_t>(hit)];
    const Row row = s.sections[static_cast<size_t>(t.section)].rows[static_cast<size_t>(t.row)];  // act may rebuild
    if (!row.act) return;
    const int generation = s.generation;
    if (row.kind == Row::kButton) {
        row.act(0);
    } else if (row.kind == Row::kToggle) {
        row.act(!row.on);
    } else if (row.kind == Row::kChoice) {
        const int pick = run_choice_menu(row.choices, row.choice, t.r);
        if (pick > 0) row.act(pick - 1);
    }
    if (g_page.generation == generation) rebuild_page();
}

// Tab moves the keyboard focus through the back arrow and the controls, and brings the one reached into view.
void page_tab(bool back) {
    Page& s = g_page;
    std::vector<int> order;
    if (s.back) order.push_back(kPageBack);
    for (size_t i = 0; i < s.targets.size(); ++i) order.push_back(static_cast<int>(i));
    if (order.empty()) return;
    const auto it = std::find(order.begin(), order.end(), s.focus);
    int i = it == order.end() ? (back ? static_cast<int>(order.size()) : -1) : static_cast<int>(it - order.begin());
    i = back ? i - 1 : i + 1;
    i = (i % static_cast<int>(order.size()) + static_cast<int>(order.size())) % static_cast<int>(order.size());
    s.focus = order[static_cast<size_t>(i)];
    if (s.focus >= 0) {
        const RectF& r = s.targets[static_cast<size_t>(s.focus)].r;
        if (r.Y < s.view.Y + px(8)) page_scroll(r.Y - s.view.Y - px(8));
        else if (r.GetBottom() > s.view.GetBottom() - px(64)) page_scroll(r.GetBottom() - (s.view.GetBottom() - px(64)));
    }
    InvalidateRect(g_main, nullptr, FALSE);
}

// Opens a page in the content layer.
void open_page(View view, Page page) {
    page.generation = g_page.generation + 1;
    g_page = std::move(page);
    g_page.sections = g_page.build();
    set_view(view);
}

Row button_row(std::wstring title, std::wstring detail, std::wstring button, std::function<void()> act,
               bool destructive = false) {
    Row r;
    r.kind = Row::kButton;
    r.title = std::move(title);
    r.detail = std::move(detail);
    r.value = std::move(button);
    r.destructive = destructive;
    r.act = [act](int) { act(); };
    return r;
}

Row text_row(std::wstring title, std::wstring value, std::wstring detail = L"") {
    Row r;
    r.title = std::move(title);
    r.value = std::move(value);
    r.detail = std::move(detail);
    return r;
}

void apply_theme();
// ---- The Google account (upstream's Accounts panel) --------------------------
//
// Apps that sign in with Google, check a Play licence or register for push
// use the account the GMS layer (runtime/java/com/macapk/gms) holds. The
// layer's state is the runtime's (<data>\gms, MACAPK_GMS_DIR): the launcher
// never reads the credentials file, it asks `winapk-runtime --gms` as
// Mac.apk's launcher asks `macapk-runtime --gms`. The exit codes are
// GmsCli.run's contract: 0 done, 1 an ERROR line, 2 cancelled. The one cheap
// answer is the `accounts.present` marker GmsStore keeps next to its file:
// present iff an account is signed in, which decides whether opening Settings
// pays a JVM start for the account list.

fs::path gms_dir() { return data_dir() / L"gms"; }
fs::path gms_log() { return logs_dir() / L"gms.log"; }

bool google_account_present() {
    std::error_code ec;
    return fs::exists(gms_dir() / L"accounts.present", ec);
}

// A failure's reason is an `ERROR\t<message>` line on the runtime's stdout,
// which only the caller reads; the error dialog's Show log opens gms.log,
// which had only stderr, so "PKIX path building failed" was nowhere to be
// seen. Copied there, and only those lines: the rest of stdout holds the
// account's email. Called after the runtime exited, as its stderr handle
// shares no write.
void append_gms_errors(const std::string& out) {
    std::string lines;
    for (size_t at = 0; at < out.size();) {
        size_t end = out.find('\n', at);
        if (end == std::string::npos) end = out.size();
        if (out.compare(at, 6, "ERROR\t") == 0) lines.append(out, at, end - at).append("\n");
        at = end + 1;
    }
    if (lines.empty()) return;
    HANDLE f = CreateFileW(gms_log().c_str(), FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr,
                           OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    DWORD n = 0;
    WriteFile(f, lines.data(), static_cast<DWORD>(lines.size()), &n, nullptr);
    CloseHandle(f);
}

// Runs winapk-runtime <args>, its stdout into *out (UTF-8, as the runtime's
// manifest makes the code page) and its stderr into `log`. The exit code, or
// -1 when it did not start; *raw (if asked) gets stdout's bytes.
int run_runtime_captured(const std::wstring& args, std::wstring* out, const fs::path& log, std::string* raw = nullptr) {
    std::error_code ec;
    fs::create_directories(logs_dir(), ec);
    SECURITY_ATTRIBUTES sa{sizeof(sa), nullptr, TRUE};
    HANDLE read = nullptr, write = nullptr;
    if (!CreatePipe(&read, &write, &sa, 0)) return -1;
    SetHandleInformation(read, HANDLE_FLAG_INHERIT, 0);
    HANDLE err = CreateFileW(log.c_str(), GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_DELETE, &sa, CREATE_ALWAYS,
                             FILE_ATTRIBUTE_NORMAL, nullptr);
    STARTUPINFOW si{};
    si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdOutput = write;
    si.hStdError = err != INVALID_HANDLE_VALUE ? err : write;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    std::wstring cmd = L"\"" + runtime_exe().wstring() + L"\" " + args;
    PROCESS_INFORMATION pi{};
    const BOOL ok = CreateProcessW(nullptr, &cmd[0], nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr,
                                   root().c_str(), &si, &pi);
    CloseHandle(write);
    if (err != INVALID_HANDLE_VALUE) CloseHandle(err);
    std::string bytes;
    if (ok) {
        char buf[4096];
        DWORD n = 0;
        while (ReadFile(read, buf, sizeof buf, &n, nullptr) && n > 0) bytes.append(buf, n);
    }
    CloseHandle(read);
    if (!ok) return -1;
    if (out) *out = utf8_to_wide(bytes);
    if (raw) *raw = bytes;
    CloseHandle(pi.hThread);
    return static_cast<int>(wait_exit(pi.hProcess));
}

// Runs winapk-runtime --gms <args>: stderr into logs\gms.log, with the ERROR lines of stdout.
int run_gms(const std::wstring& args, std::wstring* out) {
    std::string bytes;
    const int code = run_runtime_captured(L"--gms " + args, out, gms_log(), &bytes);
    append_gms_errors(bytes);
    return code;
}

// One argument for the runtime's command line, as CommandLineToArgvW splits it.
std::wstring gms_arg(const std::wstring& a) {
    std::wstring q = L"\"";
    size_t slashes = 0;
    for (wchar_t c : a) {
        if (c == L'\\') {
            ++slashes;
            continue;
        }
        q.append(c == L'"' ? slashes * 2 + 1 : slashes, L'\\');
        slashes = 0;
        q += c;
    }
    q.append(slashes * 2, L'\\');
    return q + L"\"";
}

// The signed-in accounts: `ACCOUNT<tab>email<tab>services` lines from
// `--gms accounts`. Only asked when the marker says there is one.
std::vector<std::wstring> google_accounts() {
    std::vector<std::wstring> out;
    if (!google_account_present()) return out;
    std::wstring text;
    if (run_gms(L"accounts", &text) != 0) return out;
    size_t at = 0;
    while (at < text.size()) {
        size_t end = text.find(L'\n', at);
        if (end == std::wstring::npos) end = text.size();
        std::wstring line = text.substr(at, end - at);
        at = end + 1;
        if (!line.empty() && line.back() == L'\r') line.pop_back();
        if (line.rfind(L"ACCOUNT\t", 0) != 0) continue;
        const size_t tab = line.find(L'\t', 8);
        out.push_back(line.substr(8, tab == std::wstring::npos ? std::wstring::npos : tab - 8));
    }
    return out;
}

// The toolbar's account: none without the marker; else the first account, asked on a thread
// (a JVM start) and posted to the library's window.
DWORD WINAPI account_thread(void*) {
    const std::vector<std::wstring> accounts = google_accounts();
    auto* out = new std::wstring(accounts.empty() ? L"" : accounts[0]);
    if (!PostMessageW(g_main, kGmsAccount, 0, reinterpret_cast<LPARAM>(out))) delete out;
    return 0;
}

void refresh_account() {
    if (!google_account_present()) {
        g_account.clear();
        g_account_photo_key.clear();
        if (g_main) InvalidateRect(g_main, nullptr, FALSE);
        return;
    }
    HANDLE t = CreateThread(nullptr, 0, account_thread, nullptr, 0, nullptr);
    if (t) CloseHandle(t);
}

// The sign-in window is the runtime's (--gms signin-window: Google's own
// page in a WebView2 window; the launcher never sees the password). It takes
// as long as the user takes, so it runs on a thread and reports to the
// library's window, which rebuilds the Settings sheet if it is still open.
bool g_gms_busy = false;

DWORD WINAPI gms_signin_thread(void*) {
    auto* out = new std::wstring();
    const int code = run_gms(L"signin-window", out);
    PostMessageW(g_main, kGmsDone, static_cast<WPARAM>(static_cast<unsigned>(code)), reinterpret_cast<LPARAM>(out));
    return 0;
}

void start_google_signin() {
    if (g_gms_busy) return;
    g_gms_busy = true;
    HANDLE t = CreateThread(nullptr, 0, gms_signin_thread, nullptr, 0, nullptr);
    if (t) CloseHandle(t);
    else g_gms_busy = false;
}

void google_signout(const std::wstring& account) {
    if (!confirm(fmt(L"Sign out of {0}?", {account}),
                 L"The account and its tokens are removed from this PC. Apps that used it will ask you to "
                    L"sign in again.",
                 L"Sign out")) {
        return;
    }
    std::wstring text;
    if (run_gms(L"signout " + gms_arg(account), &text) == 0) {
        info(fmt(L"Signed out of {0}.", {account}), kInfo);
        refresh_account();
    } else {
        error_with_log(L"Could not sign out", L"Google did not answer. Check the internet connection, then try again.", gms_log());
    }
}

bool google_apps_ready();
void show_gms_setup(bool from_settings);

Section google_account_section() {
    Section sec{L"Google account", {}, L""};
    if (g_gms_busy) {
        sec.rows.push_back(text_row(L"Google account", L"Signing in…",
                                    L"Complete the sign-in in the Google window, or close it to cancel."));
    } else {
        const std::vector<std::wstring> accounts = google_accounts();
        g_account = accounts.empty() ? L"" : accounts[0];
        for (const std::wstring& a : accounts) {
            sec.rows.push_back(button_row(a, L"", L"Sign out", [a] { google_signout(a); }, true));
        }
        sec.rows.push_back(button_row(accounts.empty() ? L"Sign in with Google" : L"Add another account",
                                      L"One account for every app: Google sign-in, Play licence checks and "
                                         L"push notifications.",
                                      L"Sign in…", [] { start_google_signin(); }));
    }
    // The real Google Play services, Services Framework and Play Store, installed by the user, running as
    // apps through the system broker (winapk-runtime --system). The runtime reads the value at an app's start.
    Row gms;
    gms.kind = Row::kToggle;
    gms.title = L"Use Google services (experimental)";
    gms.detail = L"Runs the Google Play services, Services Framework and Play Store you install yourself, "
                    L"as apps other apps can reach. Only copies signed by Google are trusted. Takes effect "
                    L"when an app starts.";
    gms.on = google_services_on();
    gms.act = [](int on) {
        set_setting(L"GoogleServices", static_cast<DWORD>(on));
        rebuild_tiles();  // Google Play in the pane comes and goes with it
        if (on && !google_apps_ready()) show_gms_setup(true);
    };
    sec.rows.push_back(gms);
    // The setup's one home is its page; this row only leads there.
    if (gms.on) {
        sec.rows.push_back(button_row(L"Google services setup",
                                      google_apps_ready() ? L"Play services, Services Framework and Play Store "
                                                               L"are installed."
                                                          : L"Install Play services, Services Framework and "
                                                               L"Play Store from their downloads.",
                                      L"Open", [] { show_gms_setup(true); }));
    }
    sec.footer = L"Sign-in takes place on Google's own page, in a separate window; Win.apk never sees your "
                    L"password. The account's tokens are stored only on this PC.";
    return sec;
}


// ---- Google services setup -----------------------------------------------------
//
// Google's own Play services, Services Framework and Play Store, which "Use Google services" runs, are the
// user's to download: Win.apk never fetches or ships them (APKMirror forbids automated downloads, and copies
// published here could be taken down). The page has a row per app with Download, which opens the app's
// APKMirror page in the browser, where APKMirror's own button downloads it. While the page is open the
// launcher watches the Downloads folder (and takes a file dropped on the page); a new app file is checked
// by `winapk-runtime --verify-apk` (com.winapk.tools.ApkCheck: its package, and whether its signature
// verifies and Google signed it, as the runtime checks it before it treats the app as Google's), and only
// one of the three apps that Google signed is installed, through the same install as a dropped APK.

struct GoogleApp {
    const char* pkg;
    const wchar_t* name;
    const wchar_t* url;   // APKMirror's page for the app: the user picks the release and variant there
    const wchar_t* pick;  // which variant to pick, in a sentence
};
const GoogleApp kGoogleApps[] = {
    {"com.google.android.gsf", L"Google Services Framework",
     L"https://www.apkmirror.com/apk/google-inc/google-services-framework/",
     L"Pick the newest version for Android 10 or later."},
    {"com.google.android.gms", L"Google Play services",
     L"https://www.apkmirror.com/apk/google-inc/google-play-services/",
     L"Pick the arm64-v8a variant, nodpi, for Android 10 or later."},
    {"com.android.vending", L"Google Play Store", L"https://www.apkmirror.com/apk/google-inc/google-play-store/",
     L"Pick the nodpi variant, for Android 10 or later."},
};

const GoogleApp* google_app(const std::string& pkg) {
    for (const GoogleApp& a : kGoogleApps) {
        if (pkg == a.pkg) return &a;
    }
    return nullptr;
}

bool google_services_on() { return setting(L"GoogleServices", 0) != 0; }

// The library app that is this package, or null.
const App* app_of_package(const std::string& pkg) {
    const auto it = g_store.installed.find(pkg);
    return it == g_store.installed.end() ? nullptr : find_app(it->second.first);
}

bool google_apps_ready() {
    for (const GoogleApp& a : kGoogleApps) {
        if (!app_of_package(a.pkg)) return false;
    }
    return true;
}

fs::path setup_log() { return logs_dir() / L"google-setup.log"; }

// winapk-runtime --verify-apk's answer for one file.
struct SetupCheck {
    fs::path file;
    bool dropped = false;  // dropped on the page: say why when it is not one of the three
    int code = -1;
    std::string pkg;
    bool verified = false, google = false;
    std::wstring reason;
    std::wstring abis;  // the ABIs its native code is for, comma-separated; empty: none
    int min_sdk = 0;    // its minSdkVersion (0: unknown)
    int sdk = 0;        // the API level Win.apk presents at most (PresentedSdk's ceiling; 0: unknown)
};

struct SetupState {
    fs::path folder;                                   // the Downloads folder being watched
    HANDLE stop = nullptr, thread = nullptr;           // the watch thread and what ends it
    std::map<std::wstring, std::pair<long long, uintmax_t>> seen;  // file: time and size already looked at
    std::deque<std::pair<fs::path, bool>> queue;       // files to check, dropped or not
    std::wstring checking;                             // the file being checked now
    std::map<std::string, std::wstring> installing;    // package: the library name its install has
} g_setup;

DWORD WINAPI setup_watch_thread(void* param) {
    std::unique_ptr<std::pair<fs::path, HANDLE>> p(static_cast<std::pair<fs::path, HANDLE>*>(param));
    HANDLE change = FindFirstChangeNotificationW(p->first.c_str(), FALSE,
                                                 FILE_NOTIFY_CHANGE_FILE_NAME | FILE_NOTIFY_CHANGE_SIZE |
                                                     FILE_NOTIFY_CHANGE_LAST_WRITE);
    if (change == INVALID_HANDLE_VALUE) return 0;
    HANDLE both[2] = {p->second, change};
    while (WaitForMultipleObjects(2, both, FALSE, INFINITE) == WAIT_OBJECT_0 + 1) {
        PostMessageW(g_main, kSetupChanged, 0, 0);
        if (!FindNextChangeNotification(change)) break;
    }
    FindCloseChangeNotification(change);
    return 0;
}

// Looks for app files in the Downloads folder that are new or changed since it was last looked at. The first
// look (`snapshot`) only takes note: what was there before the page opened is the user's to drop.
void setup_scan(bool snapshot) {
    std::error_code ec;
    bool busy = false;  // a file still being written: look again in a moment
    for (fs::directory_iterator it(g_setup.folder, ec), end; !ec && it != end; it.increment(ec)) {
        const fs::path& p = it->path();
        std::error_code fe;
        if (!it->is_regular_file(fe) || !is_app_file(p)) continue;
        const std::pair<long long, uintmax_t> stamp{
            static_cast<long long>(fs::last_write_time(p, fe).time_since_epoch().count()), fs::file_size(p, fe)};
        const auto old = g_setup.seen.find(p.wstring());
        if (old != g_setup.seen.end() && old->second == stamp) continue;
        if (!snapshot) {
            // A browser renames its download into place once it is whole; another program may still be writing.
            HANDLE f = CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
            if (f == INVALID_HANDLE_VALUE) {
                busy = true;
                continue;
            }
            CloseHandle(f);
            g_setup.queue.emplace_back(p, false);
        }
        g_setup.seen[p.wstring()] = stamp;
    }
    if (busy && g_main) SetTimer(g_main, kSetupScanTimer, 2000, nullptr);
}

// Starts or stops watching the Downloads folder: it is watched only while the setup page is shown.
void setup_watch(bool on) {
    if (on == (g_setup.thread != nullptr)) return;
    if (!on) {
        SetEvent(g_setup.stop);
        WaitForSingleObject(g_setup.thread, 2000);
        CloseHandle(g_setup.thread);
        CloseHandle(g_setup.stop);
        g_setup.thread = g_setup.stop = nullptr;
        if (g_main) KillTimer(g_main, kSetupScanTimer);
        return;
    }
    g_setup.folder = known_folder(FOLDERID_Downloads);
    if (g_setup.folder.empty()) return;
    g_setup.seen.clear();
    setup_scan(true);
    g_setup.stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    g_setup.thread = g_setup.stop ? CreateThread(nullptr, 0, setup_watch_thread,
                                                 new std::pair<fs::path, HANDLE>(g_setup.folder, g_setup.stop), 0,
                                                 nullptr)
                                  : nullptr;
    if (!g_setup.thread && g_setup.stop) {
        CloseHandle(g_setup.stop);
        g_setup.stop = nullptr;
    }
}

DWORD WINAPI setup_check_thread(void* param) {
    auto* c = static_cast<SetupCheck*>(param);
    std::wstring out;
    c->code = run_runtime_captured(L"--verify-apk " + gms_arg(c->file.wstring()), &out, setup_log());
    for (size_t at = 0; at < out.size();) {
        size_t end = out.find(L'\n', at);
        if (end == std::wstring::npos) end = out.size();
        std::wstring line = out.substr(at, end - at);
        at = end + 1;
        if (!line.empty() && line.back() == L'\r') line.pop_back();
        const size_t tab = line.find(L'\t');
        if (tab == std::wstring::npos) continue;
        const std::wstring key = line.substr(0, tab), value = line.substr(tab + 1);
        if (key == L"PACKAGE") c->pkg = wide_to_utf8(value);
        else if (key == L"VERIFIED") c->verified = value == L"1";
        else if (key == L"GOOGLE") c->google = value == L"1";
        else if (key == L"REASON") c->reason = value;
        else if (key == L"ABIS") c->abis = value;
        else if (key == L"MINSDK") c->min_sdk = _wtoi(value.c_str());
        else if (key == L"SDK") c->sdk = _wtoi(value.c_str());
    }
    if (!PostMessageW(g_main, kSetupChecked, 0, reinterpret_cast<LPARAM>(c))) delete c;
    return 0;
}

// Checks the next file in the queue, one at a time.
void setup_next() {
    if (!g_setup.checking.empty() || g_setup.queue.empty()) return;
    auto* c = new SetupCheck;
    c->file = g_setup.queue.front().first;
    c->dropped = g_setup.queue.front().second;
    g_setup.queue.pop_front();
    g_setup.checking = c->file.filename().wstring();
    HANDLE t = CreateThread(nullptr, 0, setup_check_thread, c, 0, nullptr);
    if (t) {
        CloseHandle(t);
    } else {
        delete c;
        g_setup.checking.clear();
    }
    if (g_view == kGmsSetupView) rebuild_page();
}

// A file dropped on the setup page.
void setup_offer(const fs::path& file) {
    g_setup.queue.emplace_back(file, true);
    setup_next();
}

// The check's answer: install the file when it is one of the three apps and Google signed it, else say why.
void setup_checked(std::unique_ptr<SetupCheck> c) {
    g_setup.checking.clear();
    if (g_view == kGmsSetupView) rebuild_page();  // no longer "Checking" behind a refusal's dialog
    const GoogleApp* app = google_app(c->pkg);
    const std::wstring file = c->file.filename().wstring();
    if (c->code < 0) {
        error_with_log(L"The file could not be checked", L"Win.apk's runtime did not start.", setup_log());
    } else if (!app) {
        // A download of something else is none of the page's business; a file dropped on it is.
        if (c->dropped) {
            info(fmt(L"{0} is not one of Google's three apps. To add another app, drop it on the library.", {file}),
                 kError);
        }
    } else if (!c->verified) {
        error_with_log(fmt(L"{0} was not installed", {app->name}),
                       fmt(L"The signature of {0} does not verify, so the file may have been changed after it was "
                           L"signed. Download it again from APKMirror.",
                           {file}) +
                           (c->reason.empty() ? L"" : L"\n" + c->reason),
                       setup_log());
    } else if (!c->google) {
        error_with_log(fmt(L"{0} was not installed", {app->name}),
                       fmt(L"{0} is not signed by Google, so Win.apk does not trust it as Google's app. Download "
                           L"it from the APKMirror page that Download opens.",
                           {file}),
                       setup_log());
    } else if (!c->abis.empty() && (L"," + c->abis + L",").find(L",arm64-v8a,") == std::wstring::npos) {
        // No native code for this PC.
        error_with_log(fmt(L"{0} was not installed", {app->name}),
                       fmt(L"{0} has no code for ARM64 processors. On APKMirror, download its arm64-v8a variant.",
                           {file}),
                       setup_log());
    } else if (c->sdk > 0 && c->min_sdk > c->sdk) {
        error_with_log(fmt(L"{0} was not installed", {app->name}),
                       fmt(L"{0} needs API level {1}, and Win.apk presents API level {2}. On APKMirror, download an "
                           L"older version of it.",
                           {file, std::to_wstring(c->min_sdk), std::to_wstring(c->sdk)}),
                       setup_log());
    } else {
        const std::wstring name = install_name(c->file);
        g_setup.installing[c->pkg] = name;
        g_setup_names.insert(name);
        install(c->file, name);
    }
    if (g_view == kGmsSetupView) rebuild_page();
    setup_next();
}

std::vector<Section> gms_setup_sections() {
    std::vector<Section> out;
    const bool ready = google_apps_ready();
    Section state{L"", {}, L""};
    if (ready) {
        Row r = text_row(L"Google services are ready", L"Ready",
                         L"From now on the Play Store keeps itself and Play services up to date, as on a phone.");
        r.check = true;
        state.rows.push_back(r);
    } else {
        // While the page is open the Downloads folder is watched (and a file can be dropped here): each app's row
        // says which file to download, and the files that pass the checks install themselves.
        state.rows.push_back(text_row(L"To use Google Play, install the following apps as described.", L"",
                                      g_setup.checking.empty() ? L"Waiting for the downloads"
                                                               : fmt(L"Checking {0}…", {g_setup.checking})));
    }
    out.push_back(std::move(state));

    Section apps{L"Google's apps", {}, L""};
    for (const GoogleApp& a : kGoogleApps) {
        const App* app = app_of_package(a.pkg);
        const auto inst = g_setup.installing.find(a.pkg);
        if (app) {
            const std::vector<std::string> p = package_of(app->name);
            const std::wstring v = p.size() > 2 && !p[2].empty() ? utf8_to_wide(p[2]) : L"";
            Row r = text_row(a.name, v.empty() ? std::wstring(L"Installed") : fmt(L"Installed, {0}", {v}),
                             utf8_to_wide(a.pkg));
            r.check = true;
            apps.rows.push_back(r);
        } else if (inst != g_setup.installing.end() && is_pending(inst->second)) {
            apps.rows.push_back(text_row(a.name, L"Installing…", utf8_to_wide(a.pkg)));
        } else {
            const wchar_t* url = a.url;
            apps.rows.push_back(button_row(a.name, L"Not installed." + std::wstring(L" ") + a.pick,
                                           L"Download", [url] {
                                               ShellExecuteW(nullptr, L"open", url, nullptr, nullptr, SW_SHOWNORMAL);
                                           }));
        }
    }
    out.push_back(std::move(apps));
    return out;
}

// The setup page: from Settings (its back arrow returns there) or from Google Play in the pane.
void show_gms_setup(bool from_settings) {
    Page p;
    p.title = L"Google services setup";
    p.back = from_settings;
    p.from = kSettingsView;
    p.build = gms_setup_sections;
    g_setup_from_settings = from_settings;
    open_page(kGmsSetupView, std::move(p));
}

// ---- Google Play, in the window ---------------------------------------------------
//
// Google Play in the pane opens the Play Store inside the library window, in the content area as the Store tab
// is, not in a window of its own. The launcher puts a host panel (a child window, play_panel_proc) over the
// content area under the title and starts the runtime with WINAPK_EMBED_PARENT=<the panel's handle> and
// WINAPK_PHONE_SIZE the panel's size in dp; the runtime then makes its window a child of the panel
// (win32_window.cpp, "embedded in the launcher") that fills it and follows its size, so the app lays itself
// out for the area as it does for a resized window. The two processes' input queues are joined, as Windows
// does for any parent and child, so the panel gives the app the keyboard focus when it is shown.
//
// Leaving for another tab hides the panel and the app keeps running, as a phone keeps an app in the
// background; Google Play again shows it at once. Closing the library window stops it. If it exits, the area
// says so, with Open Google Play (and Show log when it never came up). If the runtime could not embed its
// window (an older runtime, or one already running in its own window), the window is a normal one: the
// launcher sees the panel has no child once the app is up, says so and leaves the window as it is.

constexpr wchar_t kPlayPanelClass[] = L"WinApkPlayPanel";

HWND play_window() { return g_play.panel ? GetWindow(g_play.panel, GW_CHILD) : nullptr; }

// The panel's place and visibility: over the content under the title while Google Play is the view and the app
// is starting or up.
void place_play_panel() {
    if (!g_play.panel) return;
    const RectF& r = g_l.play;
    MoveWindow(g_play.panel, static_cast<int>(r.X), static_cast<int>(r.Y), static_cast<int>(r.Width),
               static_cast<int>(r.Height), TRUE);
    const bool show = g_view == kPlayView &&
                      (g_play.state == PlayState::kStarting || g_play.state == PlayState::kShown);
    if (show != (IsWindowVisible(g_play.panel) != FALSE)) ShowWindow(g_play.panel, show ? SW_SHOW : SW_HIDE);
}

// Gives the app the keyboard while Google Play is the view.
void focus_play() {
    if (g_view != kPlayView) return;
    if (HWND w = play_window()) SetFocus(w);
}

// The app's window fills the panel. Asynchronous: a busy app never holds the launcher's thread up.
void fit_play_window() {
    HWND w = play_window();
    if (!w) return;
    RECT rc;
    GetClientRect(g_play.panel, &rc);
    SetWindowPos(w, nullptr, 0, 0, rc.right, rc.bottom, SWP_NOZORDER | SWP_NOACTIVATE | SWP_ASYNCWINDOWPOS);
}

LRESULT CALLBACK play_panel_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_ERASEBKGND: return 1;
        case WM_PAINT:  // under the app's window: the spinner until it is up
            buffered_paint(hwnd, [](Graphics& g, float w, float h) {
                g.Clear(g_t.layer);
                if (g_play.state != PlayState::kStarting) return;
                const float s = px(32), cy = (std::min)(h / 2, px(220));
                spinner(g, RectF((w - s) / 2, cy - px(48), s, s), g_t.accent, px(3));
                draw_text(g, L"Starting Google Play…", g_f.body.get(), g_t.text2, RectF(0, cy, w, px(24)), kCenter);
            });
            return 0;
        case WM_SIZE: fit_play_window(); return 0;
        case WM_PARENTNOTIFY:  // the runtime made its window: it fills the panel at once
            if (LOWORD(wp) == WM_CREATE) fit_play_window();
            return 0;
        case WM_SETFOCUS: focus_play(); return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void ensure_play_panel() {
    if (g_play.panel) return;
    static bool registered = false;
    if (!registered) {
        WNDCLASSEXW wc{sizeof(wc)};
        wc.lpfnWndProc = play_panel_proc;
        wc.hInstance = g_inst;
        wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
        wc.lpszClassName = kPlayPanelClass;
        RegisterClassExW(&wc);
        registered = true;
    }
    g_play.panel = CreateWindowExW(WS_EX_CONTROLPARENT, kPlayPanelClass,
                                   L"Google Play", WS_CHILD | WS_CLIPCHILDREN | WS_CLIPSIBLINGS, 0, 0, 1, 1, g_main,
                                   nullptr, g_inst, nullptr);
}

void start_play(const App& store) {
    ensure_play_panel();
    if (!g_play.panel) {  // no panel: Google Play in its own window, as any app
        launch(store);
        return;
    }
    compute_layout();
    const int w = (std::max)(1, MulDiv(static_cast<int>(g_l.play.Width), 96, static_cast<int>(g_dpi)));
    const int h = (std::max)(1, MulDiv(static_cast<int>(g_l.play.Height), 96, static_cast<int>(g_dpi)));
    g_play.name = store.name;
    g_play.state = PlayState::kStarting;
    begin_run(store.name);  // open_google_play starts it only when it has no run
    auto* job = new RunJob{store.name, store.apk, {}};
    job->env = {L"WINAPK_EMBED_PARENT=" + std::to_wstring(reinterpret_cast<uintptr_t>(g_play.panel)),
                L"WINAPK_PHONE_SIZE=" + std::to_wstring(w) + L"x" + std::to_wstring(h)};
    CloseHandle(CreateThread(nullptr, 0, run_thread, job, 0, nullptr));
    place_play_panel();
    update_timer();
}

// Google Play in the pane: the Play Store in the window once the three apps are installed, else the setup.
void open_google_play() {
    const App* store = app_of_package("com.android.vending");
    if (!store || !google_apps_ready()) {
        show_gms_setup(false);
        return;
    }
    const bool here = g_play.name == store->name &&
                      (g_play.state == PlayState::kStarting || g_play.state == PlayState::kShown);
    if (!here && run_active(store->name)) {  // running in a window of its own
        bring_forward(store->name);
        return;
    }
    set_view(kPlayView);
    if (!here) start_play(*store);
    focus_play();
}

// The app's window is up (kRunReady): in the panel, or, when the runtime did not embed it, on its own.
void play_ready() {
    if (play_window()) {
        g_play.state = PlayState::kShown;
        InvalidateRect(g_play.panel, nullptr, FALSE);
        fit_play_window();
        place_play_panel();
        focus_play();
    } else {
        g_play.state = PlayState::kIdle;
        g_play.name.clear();
        place_play_panel();
        if (g_view == kPlayView) set_view(kLibraryView);
        info(L"Google Play opened in its own window.", kInfo);
    }
    update_timer();
    InvalidateRect(g_main, nullptr, FALSE);
}

// The Play Store's runtime ended (kRunExited); `failed`: before its window came up, and not stopped by the
// launcher (the window closing), which is simply over.
void play_exited(bool failed) {
    g_play.state = failed ? PlayState::kFailed : PlayState::kClosed;
    compute_layout();
    update_timer();
    InvalidateRect(g_main, nullptr, FALSE);
}

// The view under the panel: the title, and once Google Play closed, what happened and Open Google Play.
void paint_play(Graphics& g) {
    const RectF& t = g_l.title;
    std::wstring sub;
    if (g_play.state == PlayState::kStarting) {
        sub = L"Starting Google Play…";
    } else if (const App* store = app_of_package("com.android.vending")) {
        const std::vector<std::string> p = package_of(store->name);
        if (p.size() > 2 && !p[2].empty()) sub = fmt(L"Version {0}", {utf8_to_wide(p[2])});
    }
    draw_text(g, L"Google Play", g_f.title.get(), g_t.text, RectF(t.X, t.Y, t.Width, px(26)));
    draw_text(g, sub, g_f.caption.get(), g_t.text2, RectF(t.X, t.Y + px(26), t.Width, px(18)));
    const bool failed = g_play.state == PlayState::kFailed;
    if (!failed && g_play.state != PlayState::kClosed) return;
    paint_empty(g, kGlyphPlay, failed ? L"Google Play didn't start" : L"Google Play closed", L"");
    draw_button(g, g_l.play_open, L"Open Google Play", g_hot.kind == kHitPlayOpen, true, false);
    if (failed) draw_button(g, g_l.play_log, L"Show log", g_hot.kind == kHitPlayLog, false, false);
}

// ---- Software updates ----------------------------------------------------------
//
// The check and the download run on threads (update_check_thread, update_download_thread) and report to the
// library's window. The Updates section says where things stand, and the InfoBar when a newer version is out.
// Update downloads the release's installer, then runs it and closes the library, so that the installer can
// replace Win.apk.exe and the runtime; with an app running or an install under way it does not start.

// Where the check and the download stand (updates.h: their states and steps). The library's thread only.
using UpdateTracker = winapk::launcher::UpdateTracker<Release>;
UpdateTracker g_update;
size_t g_updates_section = 0;  // the Updates section's index in Settings, for the InfoBar's "What's new"

void show_settings();
void save_window_size();
bool installs_active();

DWORD unix_now() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    const unsigned long long t = (static_cast<unsigned long long>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
    return static_cast<DWORD>((t - kUnixEpochFiletime) / 10000000);
}

FILETIME unix_filetime(DWORD seconds) {
    const unsigned long long t = kUnixEpochFiletime + static_cast<unsigned long long>(seconds) * 10000000;
    return FILETIME{static_cast<DWORD>(t), static_cast<DWORD>(t >> 32)};
}

bool update_available() { return g_update.available(); }

// Installers that are not the newest release's: one that ran, or one left behind.
void remove_old_setups() {
    std::error_code ec;
    const fs::path keep = update_available() ? setup_file(g_update.latest().version) : fs::path();
    for (fs::directory_iterator it(updates_dir(), ec), end; !ec && it != end; it.increment(ec)) {
        const fs::path p = it->path();
        if (p.extension() == L".exe" && p != keep) fs::remove(p, ec);
    }
}

// `fetch` asks GitHub; else only the answer kept from the last check is read (at start, within the day).
void start_update_check(bool fetch, bool manual) {
    if (!g_update.begin_check(fetch)) return;  // one runs already
    auto* c = new UpdateCheck;
    c->fetch = fetch;
    c->manual = manual;
    if (HANDLE t = CreateThread(nullptr, 0, update_check_thread, c, 0, nullptr)) {
        CloseHandle(t);
    } else {
        delete c;
        g_update.abort_check();
    }
}

bool update_check_due() {
    const DWORD last = setting(L"LastUpdateCheck", 0), now = unix_now();
    return now < last || now - last >= kUpdateInterval;
}

// When the library is shown and hourly while it is: the daily check if it is on and due, else (once) the last
// answer, so that Settings and the InfoBar know of a newer version found earlier.
void auto_update_check() {
    static bool read_kept = false;
    if (!g_main || !IsWindowVisible(g_main)) return;
    if (setting(L"AutoUpdate", 1) && update_check_due()) start_update_check(true, false);
    else if (!read_kept) start_update_check(false, false);
    read_kept = true;
}

// The InfoBar's "What's new": Settings, scrolled to Updates.
void show_update() {
    show_settings();
    g_page.reveal = static_cast<int>(g_updates_section);
}

void update_checked(UpdateCheck& c) {
    const bool newer = c.found && compare_versions(c.latest.version, version()) > 0;
    const UpdateTracker::Say say = g_update.checked(c, std::move(c.latest), newer, setting(L"AutoUpdate", 1) != 0);
    if (c.fetch && !g_update.fetch_failed()) set_setting(L"LastUpdateCheck", unix_now());
    if (c.ok && !g_update.downloading()) remove_old_setups();
    switch (say) {
        case UpdateTracker::Say::kNothing: break;
        case UpdateTracker::Say::kCouldNotCheck:
            info(L"Couldn't check for updates. Check the internet connection.", kError);
            break;
        case UpdateTracker::Say::kUpToDate:
            info(fmt(L"Win.apk is up to date (version {0}).", {version()}), kSuccess);
            break;
        case UpdateTracker::Say::kNewVersion:
            info(fmt(L"A new version of Win.apk is available: {0}.", {g_update.latest().version}), kInfo,
                 L"What's new", show_update);
            break;
    }
    if (g_view == kSettingsView) rebuild_page();
}

// Whether the installer may run now, else says why in the InfoBar: it replaces the runtime that running apps
// use and the launcher that installs go through.
bool update_can_start() {
    if (installs_active() || any_pending() || !g_store.downloads.empty()) {
        info(L"Wait for the installs to finish, then update Win.apk.", kError);
        return false;
    }
    std::vector<std::wstring> running;
    for (const App& a : g_apps) {
        if (run_active(a.name) || app_running(a.name)) running.push_back(a.label);
    }
    if (running.size() == 1) {
        info(fmt(L"Close {0} first: the update replaces files it uses.", {running[0]}), kError);
        return false;
    }
    if (!running.empty() || any_runs()) {
        info(L"Close the running apps first: the update replaces files they use.", kError);
        return false;
    }
    return true;
}

// Runs the downloaded installer and closes the library, so that the installer can replace Win.apk.exe; false
// when it did not start.
bool run_installer() {
    if (!update_can_start()) return false;
    const fs::path setup = setup_file(g_update.latest().version);
    const auto r = reinterpret_cast<INT_PTR>(ShellExecuteW(g_main, L"open", setup.c_str(), nullptr, nullptr,
                                                           SW_SHOWNORMAL));
    if (r <= 32) {
        info(L"The installer could not be started.", kError);
        return false;
    }
    save_window_size();
    g_page.build = nullptr;  // the Update button's page_click must not rebuild a page of a window that is gone
    if (g_log_wnd) DestroyWindow(g_log_wnd);
    if (g_install_wnd) DestroyWindow(g_install_wnd);
    DestroyWindow(g_main);  // its WM_DESTROY ends the program
    return true;
}

void start_update() {
    if (g_update.downloading() || !update_available()) return;
    const Release& r = g_update.latest();
    if (r.url.empty()) {  // a release without the installer: its page has what it has
        ShellExecuteW(nullptr, L"open", (r.page.empty() ? kReleasesPage : r.page.c_str()), nullptr, nullptr,
                      SW_SHOWNORMAL);
        return;
    }
    if (!update_can_start()) return;
    std::error_code ec;
    if (fs::file_size(setup_file(r.version), ec) == r.size && !ec) {  // downloaded and checked before
        run_installer();
        return;
    }
    remove_old_setups();
    fs::create_directories(updates_dir(), ec);
    auto* job = new Release(r);
    if (HANDLE t = CreateThread(nullptr, 0, update_download_thread, job, 0, nullptr)) {
        CloseHandle(t);
        g_update.begin_download();
        info(fmt(L"Downloading Win.apk {0}…", {r.version}), kInfo);
    } else {
        delete job;
    }
}

void update_downloaded(bool ok) {
    g_update.downloaded();
    if (ok && run_installer()) return;  // the library is gone
    if (!ok) info(L"Couldn't download the update. Check the internet connection.", kError);
    if (g_view == kSettingsView) rebuild_page();
}

// The Update button shows how far the download is; changed in place, since a rebuild asks the runtime for the
// Google account.
void update_progress(int percent) {
    g_update.progress(percent);
    if (g_view != kSettingsView) return;
    for (Section& sec : g_page.sections) {
        for (Row& row : sec.rows) {
            if (row.key && strcmp(row.key, "update") == 0) {
                row.value = fmt(L"Downloading… {0}%", {std::to_wstring(percent)});
            }
        }
    }
    InvalidateRect(g_main, nullptr, FALSE);
}

// Settings > Updates, and when a newer version is out, what changed in it.
std::vector<Section> update_sections() {
    std::vector<Section> out;
    const bool newer = update_available();
    const Release& r = g_update.latest();
    std::wstring status;
    if (g_update.asking()) status = L"Checking for updates…";
    else if (newer) status = fmt(L"Version {0} is available.", {r.version});
    else if (g_update.fetch_failed()) status = L"Couldn't check for updates. Check the internet connection.";
    else if (const DWORD last = setting(L"LastUpdateCheck", 0)) {
        status = g_update.known() != UpdateTracker::Known::kNothing ? fmt(L"Win.apk is up to date. Last checked {0}.", {long_date(unix_filetime(last))})
                               : fmt(L"Last checked {0}.", {long_date(unix_filetime(last))});
    } else {
        status = L"Not checked yet.";
    }
    Section sec{L"Updates", {}, L""};
    sec.rows.push_back(button_row(fmt(L"Version {0}", {version()}), status,
                                  g_update.asking() ? L"Checking…" : L"Check",
                                  [] { start_update_check(true, true); }));
    Row autoc;
    autoc.kind = Row::kToggle;
    autoc.title = L"Check for updates automatically";
    autoc.detail = L"Once a day, while the library is open.";
    autoc.on = setting(L"AutoUpdate", 1) != 0;
    autoc.act = [](int on) {
        set_setting(L"AutoUpdate", static_cast<DWORD>(on));
        if (on && update_check_due()) start_update_check(true, false);
    };
    sec.rows.push_back(autoc);
    if (newer) {
        const std::wstring button = r.url.empty()           ? L"Open"
                                    : g_update.downloading() ? fmt(L"Downloading… {0}%",
                                                                   {std::to_wstring(g_update.percent())})
                                                             : L"Update";
        Row up = button_row(fmt(L"Win.apk {0}", {r.version}),
                            r.url.empty() ? L"This release has no installer; its page on GitHub has its files."
                                          : L"Win.apk closes and the installer replaces it. Your apps and their "
                                               L"data are kept.",
                            button, [] { start_update(); });
        up.primary = true;
        up.key = "update";
        sec.rows.push_back(up);
    }
    sec.footer = L"The check reads the list of releases on GitHub. Nothing about you or your apps is sent.";
    out.push_back(std::move(sec));
    if (newer) {
        const bool dated = r.published.dwHighDateTime || r.published.dwLowDateTime;
        const std::wstring page = r.page.empty() ? kReleasesPage : r.page;
        out.push_back({fmt(L"What's new in {0}", {r.version}),
                       {text_row(dated ? fmt(L"Released {0}", {long_date(r.published)}) : L"Release notes", L"",
                                 r.notes.empty() ? L"This release has no notes." : r.notes),
                        button_row(L"Release page", L"github.com/" WINAPK_REPO "/releases", L"Open",
                                   [page] {
                                       ShellExecuteW(nullptr, L"open", page.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
                                   })},
                       L""});
    }
    return out;
}

std::vector<Section> settings_sections() {
    std::vector<Section> out;

    Row theme;
    theme.kind = Row::kChoice;
    theme.title = L"Theme";
    theme.choices = {L"Follow Windows", L"Light", L"Dark"};  // the setting's 0, 1, 2
    theme.choice = static_cast<int>((std::min)(setting(L"Theme", 0), 2ul));
    theme.act = [](int i) {
        set_setting(L"Theme", static_cast<DWORD>(i));
        apply_theme();
    };
    out.push_back({L"Appearance", {theme}, L""});

    Row size;
    size.kind = Row::kChoice;
    size.title = L"App window size";
    size.detail = L"The size apps open at. An app whose window you resized opens at its saved size.";
    for (const auto& p : kPhonePresets) size.choices.push_back(p.label);
    size.choice = static_cast<int>(setting(L"PhoneSize", 0));
    if (size.choice >= static_cast<int>(size.choices.size())) size.choice = 0;
    size.act = [](int i) { set_setting(L"PhoneSize", static_cast<DWORD>(i)); };
    Row orient;
    orient.kind = Row::kChoice;
    orient.title = L"Default orientation";
    orient.detail = L"Apps that require an orientation always open in it. You can change it per app on its "
                       L"details page.";
    orient.choices = {L"Portrait", L"Landscape"};
    orient.choice = landscape_default() ? 1 : 0;
    orient.act = [](int i) { set_setting(L"Landscape", static_cast<DWORD>(i)); };
    // Read by the runtime when an app opens (runtime_main.cpp, map_shared_folders).
    Row folders;
    folders.kind = Row::kToggle;
    folders.title = L"Use my Windows folders";
    folders.detail = L"Apps save to and open from your Downloads, Pictures, Documents, Music and Videos.";
    folders.on = setting(L"UserFolders", 1) != 0;
    folders.act = [](int on) { set_setting(L"UserFolders", static_cast<DWORD>(on)); };
    Row desktop;
    desktop.kind = Row::kToggle;
    desktop.title = L"Desktop shortcut for new apps";
    desktop.detail = L"Every app also gets one in Start > Win.apk Apps.";
    desktop.on = setting(L"DesktopShortcut", 1) != 0;
    desktop.act = [](int on) { set_setting(L"DesktopShortcut", static_cast<DWORD>(on)); };
    Row types;
    if (is_default_handler()) {
        types = text_row(L"Open .apk, .apkm, .xapk and .apks files with Win.apk", L"Default");
        types.check = true;
    } else {
        types = button_row(L"Open .apk, .apkm, .xapk and .apks files with Win.apk", L"", L"Make default",
                           [] { register_file_types(true); });
    }
    out.push_back({L"Apps", {size, orient, folders, desktop, types}, L""});

    out.push_back(google_account_section());

    std::vector<Row> trouble = {
        button_row(L"Logs",
                   L"Every app's install and run logs, to attach to a bug report. Each app's own is on its "
                      L"details page.",
                   L"Open folder", [] {
                       std::error_code ec;
                       fs::create_directories(logs_dir(), ec);
                       open_path(logs_dir());
                   }),
        button_row(L"App data", L"The files and settings each app has saved.", L"Open folder",
                   [] { open_path(data_dir()); })};
    // The library's own apps; Google's system components stay with Google services.
    std::vector<App> own;
    for (const App& a : g_apps)
        if (!system_app(a.name)) own.push_back(a);
    if (!own.empty()) {
        trouble.push_back(button_row(L"Clear library",
                                     L"Uninstalls every app and removes its shortcuts. App data is kept.",
                                     L"Clear…", [own] {
                                         if (!confirm(L"Clear the library?",
                                                      fmt(L"All {0} apps and their shortcuts will be removed. App "
                                                          L"data is kept.",
                                                          {std::to_wstring(own.size())}),
                                                      L"Clear library")) {
                                             return;
                                         }
                                         for (const App& a : own) remove_app(a, false);
                                         refresh();
                                         info(L"The library was cleared.", kInfo);
                                     },
                                     true));
    }
    out.push_back({L"Troubleshooting", std::move(trouble), L""});

    g_updates_section = out.size();
    for (Section& sec : update_sections()) out.push_back(std::move(sec));

    auto open_url = [](const wchar_t* url) {
        return [url] { ShellExecuteW(nullptr, L"open", url, nullptr, nullptr, SW_SHOWNORMAL); };
    };
    // Credits and licenses: THIRD_PARTY_NOTICES.txt, packaged next to README.txt and LICENSE.txt, which credits
    // Mac.apk (the AGPL's attribution) and every project the package ships; the repository's copy when this is
    // a build without one beside it.
    out.push_back({L"About",
                   {text_row(L"Win.apk", fmt(L"Version {0}", {version()}), L"Android apps on Windows."),
                    button_row(L"Project page", L"github.com/" WINAPK_REPO, L"Open",
                               open_url(L"https://github.com/" WINAPK_REPO)),
                    button_row(L"Credits and licenses",
                               L"Mac.apk, which Win.apk is built on, the other projects it uses, and their "
                                  L"licenses.",
                               L"View", [] {
                                   std::error_code ec;
                                   const fs::path notices = root() / L"THIRD_PARTY_NOTICES.txt";
                                   if (fs::exists(notices, ec)) open_path(notices);
                                   else ShellExecuteW(nullptr, L"open",
                                                      L"https://github.com/" WINAPK_REPO "/blob/main/"
                                                      L"THIRD_PARTY_NOTICES.txt",
                                                      nullptr, nullptr, SW_SHOWNORMAL);
                               })},
                   L""});
    return out;
}

void show_settings() {
    Page p;
    p.title = L"Settings";
    p.build = settings_sections;
    open_page(kSettingsView, std::move(p));
}

std::vector<Row> permission_rows(const App& a);

fs::path desktop_shortcut(const App& a) {
    return known_folder(FOLDERID_Desktop) / (file_name_safe(a.label) + L".lnk");
}

// An app's details: how it opens, its permissions, its shortcut and files, and Stop, Update, Remove.
void show_info(const App& app) {
    const App a = app;
    std::error_code ec;
    const std::wstring apk_size = format_bytes(fs::file_size(a.apk, ec));
    const std::wstring files_size = format_bytes(folder_size(app_dir(a.name)));
    const std::vector<std::string> pkg = package_of(a.name);
    Page p;
    p.app = a.name;
    p.title = a.label;
    if (pkg.size() > 2 && !pkg[2].empty()) p.sub = fmt(L"Version {0}", {utf8_to_wide(pkg[2])}) + L"  ·  ";
    p.sub += pkg.empty() ? a.name : utf8_to_wide(pkg[0]);
    p.back = true;
    p.from = kLibraryView;
    p.build = [a, apk_size, files_size] {
        std::vector<Section> out;
        // The orientation the app opens in: what its manifest fixes, or the user's choice for it over the settings'.
        std::vector<Row> window;
        if (const int fixed = manifest_orientation(a.name); fixed != kOrientUnset) {
            window.push_back(text_row(L"Orientation", fixed == kLandscape ? L"Landscape – required by the app"
                                                                                 : L"Portrait – required by the app"));
        } else {
            Row orient;
            orient.kind = Row::kChoice;
            orient.title = L"Orientation";
            orient.detail = landscape_default() ? L"The settings' default is landscape."
                                                   : L"The settings' default is portrait.";
            orient.choices = {L"Settings' default", L"Portrait", L"Landscape"};
            orient.choice = app_orientation(a.name) == kOrientUnset ? 0 : app_orientation(a.name) == kLandscape ? 2 : 1;
            orient.act = [a](int i) { set_orientation(a, i == 0 ? kOrientUnset : i == 2 ? kLandscape : kPortrait); };
            window.push_back(orient);
        }
        // The size the user last gave the app's window (the runtime keeps it as library\<name>.window, in dp).
        if (std::ifstream f(library_dir() / (a.name + L".window")); f) {
            int w = 0, h = 0;
            char x = 0;
            if (f >> w >> x >> h && w > 0 && h > 0) {
                window.push_back(button_row(fmt(L"Window size  ·  {0} × {1} dp", {std::to_wstring(w), std::to_wstring(h)}),
                                            L"The size you last gave the app's window. Reset, it opens at the "
                                               L"app window size in Settings again.",
                                            L"Reset", [a] {
                                                std::error_code ec;
                                                fs::remove(library_dir() / (a.name + L".window"), ec);
                                                info(fmt(L"{0} opens at the app window size in Settings again.", {a.label}),
                                                     kSuccess);
                                            }));
            }
        }
        out.push_back({L"Window", std::move(window), L""});
        if (std::vector<Row> perms = permission_rows(a); !perms.empty())
            out.push_back({L"Permissions", std::move(perms), L""});
        Row desktop;
        desktop.kind = Row::kToggle;
        desktop.title = L"Desktop shortcut";
        desktop.detail = L"The app is in Start > Win.apk Apps either way.";
        std::error_code ec;
        desktop.on = fs::exists(desktop_shortcut(a), ec);
        desktop.act = [a](int on) {
            if (on) {
                make_app_shortcuts(a, true);
            } else {
                std::error_code ec;
                fs::remove(desktop_shortcut(a), ec);
            }
        };
        out.push_back({L"Shortcut", {desktop}, L""});
        // The app's own log: its last run, else its install (the log window's picker has the rest); and a
        // report for a problem met inside the app, which the splash's and the install's errors cannot see.
        {
            std::error_code ec2;
            const fs::path log = fs::exists(run_log(a.name), ec2) ? run_log(a.name) : install_log(a.name);
            out.push_back({L"Troubleshooting",
                           {button_row(L"Log", L"View the app's recent logs", L"Show log",
                                       [log] { show_logs(log); }),
                            button_row(L"Report a problem",
                                       L"Opens a GitHub issue with the app's details and log",
                                       L"Report", [a, log] { report_problem(g_main, a.name, a.label, log, {}); })},
                           L""});
        }
        out.push_back({L"Storage",
                       {button_row(fmt(L"The app's file  ·  {0}", {apk_size}), a.apk.wstring(), L"Show",
                                   [a] { show_in_explorer(a.apk); }),
                        button_row(fmt(L"Run files  ·  {0}", {files_size}),
                                   fmt(L"What Win.apk prepared to run the app: {0}", {app_dir(a.name).wstring()}),
                                   L"Open", [a] { open_path(app_dir(a.name)); })},
                       L""});
        std::vector<Row> manage;
        if (run_active(a.name) || app_running(a.name)) {
            manage.push_back(button_row(L"Stop", L"Closes the app at once, as Force stop does on a phone.",
                                        L"Stop", [a] {
                                            if (!stop_run(a.name)) appops::stop_runtime_of(a.apk);
                                        }));
        }
        // No "Update from a file": opening or dropping a newer file of the app already installs over it.
        manage.push_back(button_row(L"Remove", L"Removes the app and its shortcuts. You can also remove what "
                                                     L"it saved.",
                                    L"Remove…",
                                    [a] { remove_app(a, true); }, true));
        out.push_back({L"Manage", std::move(manage), L""});
        return out;
    };
    open_page(kAppView, std::move(p));
}

// A store app's details. Install and Update are the card's button; this says where the app stands.
void show_store_info(const StoreApp& app) {
    const StoreApp a = app;  // the catalog may be replaced while the page is open
    Page p;
    p.title = a.name;
    p.sub = a.summary;
    p.icon = [a] { return store_icon(a); };
    p.back = true;
    p.from = kStoreView;
    p.build = [a] {
        std::wstring cats;
        for (const std::wstring& c : a.categories) cats += (cats.empty() ? L"" : L", ") + c;
        std::wstring state;
        int percent = 0;
        switch (offer_for(a, &percent)) {
            case kOfferInstall: state = L"Not installed"; break;
            case kOfferUpdate: state = L"A newer version is out"; break;
            case kOfferOpen: state = L"Installed"; break;
            case kOfferDownloading: state = L"Downloading…"; break;
            case kOfferInstalling: state = L"Installing…"; break;
        }
        std::vector<Section> out;
        out.push_back({L"Details",
                       {text_row(L"In your library", state), text_row(L"Version", a.version),
                        text_row(L"Size", format_bytes(static_cast<uintmax_t>(a.size))),
                        text_row(L"Updated", java_date(a.updated)), text_row(L"License", a.license),
                        text_row(L"Categories", cats), text_row(L"Package", utf8_to_wide(a.pkg))},
                       L""});
        const std::wstring page = L"https://f-droid.org/packages/" + utf8_to_wide(a.pkg) + L"/";
        out.push_back({L"",
                       {button_row(L"F-Droid page", L"The app's description, screenshots and source code.",
                                   L"Open",
                                   [page] {
                                       ShellExecuteW(nullptr, L"open", page.c_str(), nullptr, nullptr, SW_SHOWNORMAL);
                                   })},
                       L""});
        return out;
    };
    open_page(kStoreAppView, std::move(p));
}

// ---------------------------------------------------------------------------
// Shared by the install and log windows

Color code_bg() { return g_t.dark ? hex(0x141418) : hex(0xFAFAFC); }

// The end of a log, read while the runtime may still be writing it.
std::string read_tail(const fs::path& p, size_t max_bytes) {
    HANDLE f = CreateFileW(p.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return {};
    LARGE_INTEGER size{};
    GetFileSizeEx(f, &size);
    LARGE_INTEGER start{};
    start.QuadPart = (std::max)(0LL, static_cast<long long>(size.QuadPart) - static_cast<long long>(max_bytes));
    SetFilePointerEx(f, start, nullptr, FILE_BEGIN);
    std::string out(static_cast<size_t>(size.QuadPart - start.QuadPart), '\0');
    DWORD got = 0;
    if (!out.empty()) ReadFile(f, &out[0], static_cast<DWORD>(out.size()), &got, nullptr);
    out.resize(got);
    CloseHandle(f);
    if (start.QuadPart > 0) out.erase(0, out.find('\n') == std::string::npos ? 0 : out.find('\n') + 1);
    return out;
}

std::vector<std::wstring> last_lines(const fs::path& p, size_t n) {
    std::vector<std::wstring> lines(1);
    for (wchar_t c : utf8_to_wide(read_tail(p, 16384))) {
        if (c == L'\n') lines.emplace_back();
        else if (c != L'\r') lines.back() += c == L'\t' ? L' ' : c;
    }
    while (!lines.empty() && lines.back().empty()) lines.pop_back();
    if (lines.size() > n) lines.erase(lines.begin(), lines.end() - static_cast<long>(n));
    return lines;
}


// A window of its own: over the library when it is shown, and owned by it (no taskbar button of
// its own); else in the middle of the screen with the mouse, with a taskbar button.
HWND open_tool_window(const wchar_t* cls, const wchar_t* title, int w, int h,
                      DWORD style = WS_OVERLAPPEDWINDOW & ~WS_MAXIMIZEBOX) {
    const bool over_library = g_main && IsWindowVisible(g_main) && !IsIconic(g_main);
    RECT mr;
    if (over_library) {
        GetWindowRect(g_main, &mr);
    } else {
        POINT cursor;
        GetCursorPos(&cursor);
        MONITORINFO mi{sizeof(mi)};
        GetMonitorInfoW(MonitorFromPoint(cursor, MONITOR_DEFAULTTOPRIMARY), &mi);
        mr = mi.rcWork;
    }
    const int x = mr.left + (mr.right - mr.left - w) / 2, y = mr.top + (std::max)(0L, (mr.bottom - mr.top - h) / 2);
    HWND hwnd = CreateWindowExW(0, cls, title, style, x, y, w, h, over_library ? g_main : nullptr,
                                nullptr, g_inst, nullptr);
    if (hwnd) {
        theme_window(hwnd);
        ShowWindow(hwnd, SW_SHOW);
        SetForegroundWindow(hwnd);
    }
    return hwnd;
}

// ---------------------------------------------------------------------------
// The install window: a small card with the app's icon and name, a moving bar
// and one line of plain status; the queue under it when several files came;
// the log behind "Show details". At the end: Open, Close, Show in library; on
// a failure the reason, Show log and Copy details. Opening an APK from
// Explorer shows only this window (wWinMain).

struct InstallView {
    Targets targets;
    int hot = 0;
    bool tracking = false;
    bool details = false;          // the log is shown: Show details, for this window only
    std::wstring log_name;         // whose log the details show; empty: the one being installed
    std::vector<std::wstring> log;
    ULONGLONG log_read = 0;
    float need_h = 0;              // the height the last paint wanted, for fit_install_window
} g_iv;

enum { kActClose = 1, kActCancel, kActOpen, kActDetails, kActCopy, kActShowLog, kActLibrary, kActReport,
       kActRowOpen = 1000, kActRowLog = 2000 };
constexpr UINT kFitInstall = WM_APP + 20;

// How far an install got, from the lines the runtime writes, in the order it works (runtime_main.cpp):
// 0 starting, 1 installing, 2 reading the name and icon, 3 converting the code, the long part.
int install_phase(const fs::path& log) {
    const std::string tail = read_tail(log, 128 * 1024);
    if (tail.find("converting the app's code") != std::string::npos) return 3;
    if (tail.find("host natives:") != std::string::npos) return 2;
    if (tail.find("installing the app (once per install)") != std::string::npos) return 1;
    return 0;
}

const wchar_t* phase_text(int phase) {
    switch (phase) {
        case 1: return L"Installing…";
        case 2: return L"Getting the app's name and icon…";
        case 3: return L"Converting the app's code. This happens once.";
        default: return L"Preparing…";
    }
}

std::wstring install_failure_reason(const fs::path& log) {
    bool no_native_code = false;
    log_summary(log, &no_native_code);
    if (no_native_code) {
        return L"This file has no code for PCs. Install the full bundle (.apkm, .xapk or .apks) that includes "
                  L"the arm64-v8a part.";
    }
    std::error_code ec;
    if (!fs::exists(runtime_exe(), ec)) return L"Win.apk's runtime is missing. Install Win.apk again.";
    return L"Win.apk could not convert this app. See the log for details.";
}

// The name to show for an install: the app's own once known, else the file's.
std::wstring job_title(const Job& j) {
    if (find_app(j.name) || g_job_labels.count(j.name)) return job_label(j.name);
    if (!j.label.empty()) return j.label;
    return j.file.empty() ? j.name : j.file;
}

Gdiplus::Bitmap* job_icon(Job& j) {
    if (j.icon) return j.icon.get();
    if (const App* a = find_app(j.name)) return icon_for(*a);
    return nullptr;
}

bool installs_active() {
    for (const Job& j : g_jobs)
        if (j.state == Job::kWaiting || j.state == Job::kInstalling) return true;
    return false;
}

// The job the card is about: the one being installed, else the only one, else none (a summary).
Job* hero_job() {
    for (Job& j : g_jobs)
        if (j.state == Job::kInstalling) return &j;
    for (Job& j : g_jobs)
        if (j.state == Job::kWaiting) return &j;
    return g_jobs.size() == 1 ? &g_jobs[0] : nullptr;
}

// An app's icon at `r`; a neutral tile with a package glyph while it has none.
void draw_job_icon(Graphics& g, Job& j, const RectF& r) {
    if (Gdiplus::Bitmap* bmp = job_icon(j)) {
        draw_icon(g, bmp, job_title(j), r);
        return;
    }
    fill_round(g, r, r.Width * 0.22f, g_t.hover);
    draw_text(g, kGlyphPackage, r.Width >= px(56) ? g_f.glyph24.get() : g_f.glyph16.get(), g_t.text3, r, kCenter);
}

void draw_badge(Graphics& g, const RectF& icon, bool ok, float size) {
    const RectF b(icon.GetRight() - size * 0.75f, icon.GetBottom() - size * 0.75f, size, size);
    Gdiplus::SolidBrush ring(g_t.layer), dot(ok ? g_t.success : g_t.danger);
    g.FillEllipse(&ring, inset(b, -px(2), -px(2)));
    g.FillEllipse(&dot, b);
    if (ok) draw_text(g, kGlyphCheck, g_f.glyph12.get(), kWhite, b, kCenter);
    else draw_text(g, L"!", g_f.body_semi.get(), kWhite, b, kCenter);
}

// A text link: accent text with a chevron, for the window's secondary ways on.
float draw_link(Graphics& g, const std::wstring& text, float x, float y, float h, bool hot, int act, Align align = kLeft) {
    const float w = text_width(g, text, g_f.body.get()) + px(4);
    const RectF r(align == kRight ? x - w : x, y, w, h);
    draw_text(g, text, g_f.body.get(), hot ? mix(g_t.accent, g_t.text, 0.3f) : g_t.accent, r);
    g_iv.targets.push_back({r, act});
    return r.Width;
}

void paint_install(Graphics& g, float W, float H) {
    g.Clear(g_t.layer);  // as the dialog: the layer, and a footer band of the window colour
    g_iv.targets.clear();
    const float m = px(24), foot = px(64), cw = W - 2 * m;
    int done = 0, failed = 0, cancelled_n = 0, shown_done = 0;  // shown_done: in the library (not Google's)
    for (const Job& j : g_jobs) {
        done += j.state == Job::kDone;
        shown_done += j.state == Job::kDone && !system_app(j.name);
        failed += j.state == Job::kFailed;
        cancelled_n += j.state == Job::kCancelled;
    }
    const int total = static_cast<int>(g_jobs.size());
    const bool active = installs_active();
    Job* hero = hero_job();

    // The card: the job's icon, name and status, or the summary of a finished batch.
    float y = px(20);
    const RectF ir(m, y, px(64), px(64));
    const float tx = m + px(80), tw = cw - px(80);
    std::wstring title, sub;
    Color sub_ink = g_t.text2;
    if (hero) {
        draw_job_icon(g, *hero, ir);
        const std::wstring name = job_title(*hero);
        switch (hero->state) {
            case Job::kWaiting:
            case Job::kInstalling:
                title = name;
                sub = phase_text(hero->phase);
                break;
            case Job::kDone:
                title = fmt(L"{0} is ready", {name});
                sub = system_app(hero->name)                ? L"It's part of Google services."
                         : setting(L"DesktopShortcut", 1) != 0 ? L"It's in the Start menu and on the desktop."
                                                               : L"It's in the Start menu.";
                draw_badge(g, ir, true, px(24));
                break;
            case Job::kFailed:
                title = fmt(L"Couldn't install {0}", {name});
                sub = hero->reason;
                draw_badge(g, ir, false, px(24));
                break;
            case Job::kCancelled:
                title = fmt(L"{0} was not installed", {name});
                sub = hero->update ? L"The install was cancelled. The app stays as it was."
                                      : L"The install was cancelled.";
                break;
        }
    } else {
        const bool ok = failed == 0;
        fill_round(g, ir, px(32), with_alpha(ok ? g_t.success : g_t.danger, g_t.dark ? 60 : 30));
        draw_text(g, ok ? kGlyphCheck : L"!", ok ? g_f.glyph24.get() : g_f.title.get(), ok ? g_t.success : g_t.danger,
                  ir, kCenter);
        title = failed == 0 ? fmt(L"{0} apps are ready", {std::to_wstring(done)})
                            : fmt(L"{0} of {1} apps are installed", {std::to_wstring(done), std::to_wstring(total)});
        sub = failed == 0 ? L"They're in the Start menu." : L"The reason for each failure is shown below.";
    }
    draw_text(g, title, g_f.title.get(), g_t.text, RectF(tx, y + px(2), tw, px(28)));
    const float sh = (std::max)(px(18), text_height(g, sub, g_f.caption.get(), tw) + px(2));
    draw_text(g, sub, g_f.caption.get(), sub_ink, RectF(tx, y + px(32), tw, sh), kLeft, true, false);
    float hero_bottom = y + px(32) + sh;
    // A failed install's Report a problem, under its reason: the footer's width is taken by its buttons.
    if (hero && hero->state == Job::kFailed && !active) {
        draw_link(g, L"Report a problem", tx, hero_bottom + px(2), px(22), g_iv.hot == kActReport, kActReport);
        hero_bottom += px(24);
    }
    if (hero && (hero->state == Job::kWaiting || hero->state == Job::kInstalling)) {
        draw_progress_line(g, RectF(tx, hero_bottom + px(8), tw, px(4)));
        hero_bottom += px(12);
    }
    y = (std::max)(ir.GetBottom(), hero_bottom) + px(16);

    // The others in this batch, the newest last; at most five.
    std::vector<int> rows;
    for (int i = 0; i < total; ++i)
        if (&g_jobs[static_cast<size_t>(i)] != hero) rows.push_back(i);
    if (rows.size() > 5) rows.erase(rows.begin(), rows.end() - 5);
    if (!rows.empty() && total > 1) {
        const float rh = px(48);
        const RectF card(m, y, cw, rh * rows.size());
        fill_round(g, card, px(kRadiusCard), g_t.card);
        for (size_t k = 0; k < rows.size(); ++k) {
            Job& j = g_jobs[static_cast<size_t>(rows[k])];
            const RectF r(m, y + k * rh, cw, rh);
            if (k > 0) {
                Gdiplus::Pen line(g_t.divider, 1);
                g.DrawLine(&line, r.X + px(56), r.Y, r.GetRight() - px(12), r.Y);
            }
            draw_job_icon(g, j, RectF(r.X + px(12), r.Y + px(8), px(32), px(32)));
            std::wstring state;
            Color ink = g_t.text2;
            switch (j.state) {
                case Job::kWaiting: state = L"Waiting"; break;
                case Job::kInstalling: state = phase_text(j.phase); break;
                case Job::kDone: state = L"Ready"; break;
                case Job::kFailed: state = L"Couldn't install"; ink = g_t.danger; break;
                case Job::kCancelled: state = L"Cancelled"; break;
            }
            const float rx = r.X + px(56), rw = r.Width - px(56) - px(110);
            draw_text(g, job_title(j), g_f.body_semi.get(), g_t.text, RectF(rx, r.Y + px(6), rw, px(20)));
            draw_text(g, state, g_f.caption.get(), ink, RectF(rx, r.Y + px(25), rw, px(17)));
            const float right = r.GetRight() - px(14);
            if (j.state == Job::kDone && !system_app(j.name)) {
                draw_link(g, L"Open", right, r.Y, rh, g_iv.hot == kActRowOpen + rows[k], kActRowOpen + rows[k], kRight);
            } else if (j.state == Job::kFailed) {
                draw_link(g, L"Show log", right, r.Y, rh, g_iv.hot == kActRowLog + rows[k], kActRowLog + rows[k],
                          kRight);
            } else if (j.state == Job::kWaiting) {
                draw_text(g, kGlyphClock, g_f.glyph16.get(), g_t.text3, RectF(right - px(20), r.Y, px(20), rh), kCenter);
            } else if (j.state == Job::kInstalling) {
                spinner(g, RectF(right - px(18), r.Y + (rh - px(16)) / 2, px(16), px(16)), g_t.accent, px(2));
            }
        }
        stroke_round(g, card, px(kRadiusCard), g_t.border);
        y += card.Height + px(12);
    }

    // Show details: the log of the install the card is about, or the one asked for.
    const bool any_failed = failed > 0;
    const std::wstring details_text = g_iv.details ? L"Hide details" : L"Show details";
    {
        const float lw = text_width(g, details_text, g_f.body.get()) + px(20);
        const RectF r(m, y, lw, px(24));
        draw_text(g, g_iv.details ? kGlyphChevronUp : kGlyphChevron, g_f.glyph12.get(),
                  g_iv.hot == kActDetails ? g_t.text : g_t.text2, RectF(r.X, r.Y, px(14), r.Height), kCenter);
        draw_text(g, details_text, g_f.body.get(), g_iv.hot == kActDetails ? g_t.text : g_t.text2,
                  RectF(r.X + px(18), r.Y, lw, r.Height));
        g_iv.targets.push_back({r, kActDetails});
        y += px(30);
    }
    if (g_iv.details) {
        const float lh = px(16);
        const RectF box(m, y, cw, lh * 10 + px(16));
        fill_round(g, box, px(kRadiusControl), code_bg());
        stroke_round(g, box, px(kRadiusControl), g_t.border);
        if (g_iv.log.empty()) {
            draw_text(g, L"Starting…", g_f.caption.get(), g_t.text2, box, kCenter);
        } else {
            const size_t from = g_iv.log.size() > 10 ? g_iv.log.size() - 10 : 0;
            float ly = box.Y + px(8);
            for (size_t i = from; i < g_iv.log.size(); ++i, ly += lh) {
                const std::wstring& l = g_iv.log[i];
                const bool bad = l.find(L"rror") != std::wstring::npos || l.find(L"xception") != std::wstring::npos;
                draw_text(g, l, g_f.mono.get(), bad ? g_t.danger : g_t.text2,
                          RectF(box.X + px(10), ly, box.Width - px(20), lh), kLeft);
            }
        }
        y += box.Height + px(12);
    }
    g_iv.need_h = y + px(4) + foot;

    // The footer's buttons, right-aligned; the default one first, as Windows' dialogs order them.
    const float fy = H - foot;
    Gdiplus::SolidBrush bar(g_t.bg);
    g.FillRectangle(&bar, RectF(0, fy, W, foot));
    Gdiplus::Pen line(g_t.border, 1);
    g.DrawLine(&line, 0.0f, fy, W, fy);
    float right = W - m;
    const float by = fy + (foot - px(32)) / 2;
    auto button = [&](const std::wstring& text, int act, bool primary) {
        const float bw = button_width(g, text, primary);
        const RectF r(right - bw, by, bw, px(32));
        draw_button(g, r, text, g_iv.hot == act, primary, false);
        g_iv.targets.push_back({r, act});
        right -= bw + px(8);
    };
    if (active) {
        button(L"Cancel", kActCancel, false);
    } else {
        const bool single_ok = hero && hero->state == Job::kDone && !system_app(hero->name);
        if (any_failed && hero && hero->state == Job::kFailed) {
            button(L"Copy details", kActCopy, false);
            button(L"Show log", kActShowLog, false);
        }
        button(L"Close", kActClose, !single_ok);
        if (single_ok) button(L"Open", kActOpen, true);
        if (shown_done > 0) draw_link(g, L"Show in library", m, fy, foot, g_iv.hot == kActLibrary, kActLibrary);
    }
}

void fit_install_window() {
    if (g_install_wnd) PostMessageW(g_install_wnd, kFitInstall, 0, 0);
}

// The window's title: what it is doing, for the taskbar.
std::wstring install_title() {
    const Job* hero = hero_job();
    int done = 0;
    for (const Job& j : g_jobs) done += j.state != Job::kWaiting && j.state != Job::kInstalling;
    if (installs_active()) {
        if (g_jobs.size() > 1) {
            return fmt(L"Installing {0} of {1}", {std::to_wstring((std::min)(done + 1, static_cast<int>(g_jobs.size()))),
                                                  std::to_wstring(g_jobs.size())});
        }
        return fmt(L"Installing {0}", {hero ? job_title(*hero) : std::wstring()});
    }
    return hero ? job_title(*hero) : std::wstring(L"Win.apk");
}

// Copy details: what a bug report needs, the reason and the end of the log.
void copy_install_details(HWND hwnd, const Job& j) {
    std::wstring text = L"Win.apk " + version() + L"\r\n" + fmt(L"Couldn't install {0}", {job_title(j)}) + L"\r\n" +
                        j.reason + L"\r\n\r\n";
    for (const std::wstring& l : last_lines(install_log(j.name), 200)) text += l + L"\r\n";
    copy_text(hwnd, text);
}

bool ask_stop_installing(HWND hwnd) {
    Prompt p;
    p.question = L"Stop installing?";
    p.note = L"What was not installed yet is left out. An app that was being updated stays as it was.";
    p.buttons = {{IDOK, L"Stop installing"}, {IDCANCEL, L"Keep installing"}};
    p.default_id = IDCANCEL;
    return run_dialog(p, hwnd) == IDOK;
}

void cancel_all_installs() {
    for (const Job& j : g_jobs) {
        if (j.state == Job::kWaiting || j.state == Job::kInstalling) cancel_install(j.name);
    }
}

void install_action(HWND hwnd, int act) {
    if (act == kActClose) {
        DestroyWindow(hwnd);
    } else if (act == kActCancel) {
        if (ask_stop_installing(hwnd)) cancel_all_installs();
    } else if (act == kActOpen) {
        const Job* hero = hero_job();
        const App* a = hero ? find_app(hero->name) : nullptr;
        if (a) {
            launch(*a);  // first: with the library hidden, closing this window last must not end the process
            DestroyWindow(hwnd);
        }
    } else if (act == kActDetails) {
        g_iv.details = !g_iv.details;
        g_iv.log_read = 0;
        fit_install_window();
    } else if (act == kActShowLog) {
        g_iv.details = true;
        if (const Job* hero = hero_job()) g_iv.log_name = hero->name;
        g_iv.log_read = 0;
        fit_install_window();
    } else if (act == kActCopy) {
        if (const Job* hero = hero_job()) copy_install_details(hwnd, *hero);
    } else if (act == kActReport) {
        if (const Job* hero = hero_job()) {
            const Job j = *hero;  // the dialog is modal: the jobs can change under it
            report_problem(hwnd, j.name, job_title(j), install_log(j.name),
                           fmt(L"Couldn't install {0}", {job_title(j)}) + L". " + j.reason);
        }
    } else if (act == kActLibrary) {
        std::wstring pick;
        for (const Job& j : g_jobs)
            if (j.state == Job::kDone && !system_app(j.name)) pick = j.name;
        show_library(SW_SHOWDEFAULT);
        set_view(kLibraryView);
        if (!pick.empty()) select_app(pick);
        DestroyWindow(hwnd);
    } else if (act >= kActRowLog) {
        const size_t i = static_cast<size_t>(act - kActRowLog);
        if (i < g_jobs.size()) {
            g_iv.details = true;
            g_iv.log_name = g_jobs[i].name;
            g_iv.log_read = 0;
            fit_install_window();
        }
    } else if (act >= kActRowOpen) {
        const size_t i = static_cast<size_t>(act - kActRowOpen);
        if (i < g_jobs.size() && !system_app(g_jobs[i].name)) {
            if (const App* a = find_app(g_jobs[i].name)) launch(*a);
        }
    }
    InvalidateRect(hwnd, nullptr, FALSE);
}

// Every 400 ms: the install's step and icon from what it has written, and the log when it is shown.
void poll_install(HWND hwnd) {
    for (Job& j : g_jobs) {
        if (j.state != Job::kInstalling) continue;
        j.phase = (std::max)(j.phase, install_phase(install_log(j.name)));
        if (!find_app(j.name)) {  // a new app: what its install has written of it so far
            const PendingLook& look = pending_look(j.name);
            if (!j.icon) j.icon = look.icon;
            if (j.label.empty()) j.label = look.label;
        }
    }
    if (g_iv.details) {
        std::wstring who = g_iv.log_name;
        if (who.empty() || installs_active()) {
            if (const Job* hero = hero_job()) who = hero->name;
        }
        for (auto it = g_jobs.rbegin(); who.empty() && it != g_jobs.rend(); ++it) {  // a finished batch: the last one's
            if (it->state != Job::kWaiting) who = it->name;
        }
        g_iv.log = who.empty() ? std::vector<std::wstring>{} : last_lines(install_log(who), 40);
    }
    g_iv.log_read = GetTickCount64();
    SetWindowTextW(hwnd, install_title().c_str());
}

LRESULT CALLBACK install_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE:
            SetTimer(hwnd, 1, 33, nullptr);
            return 0;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: {
            RECT rc;
            GetClientRect(hwnd, &rc);
            buffered_paint(hwnd, paint_install);
            if (std::fabs(g_iv.need_h - static_cast<float>(rc.bottom)) > 1) fit_install_window();
            return 0;
        }
        case kFitInstall: {
            RECT wr, rc;
            GetWindowRect(hwnd, &wr);
            GetClientRect(hwnd, &rc);
            const int want = static_cast<int>(std::lround(g_iv.need_h));
            if (want > 0 && want != rc.bottom) {
                SetWindowPos(hwnd, nullptr, 0, 0, wr.right - wr.left, (wr.bottom - wr.top) - rc.bottom + want,
                             SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
            }
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_TIMER: {
            const bool active = installs_active();
            if (GetTickCount64() - g_iv.log_read > 400) poll_install(hwnd);
            if (active) InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_MOUSEMOVE: track_hot(hwnd, lp, g_iv.targets, &g_iv.hot, &g_iv.tracking); return 0;
        case WM_MOUSELEAVE:
            g_iv.tracking = false;
            g_iv.hot = 0;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT) {
                SetCursor(LoadCursorW(nullptr, g_iv.hot ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_LBUTTONUP: install_action(hwnd, find_target(g_iv.targets, lp)); return 0;
        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) {
                install_action(hwnd, installs_active() ? kActCancel : kActClose);
            } else if (wp == VK_RETURN && !installs_active()) {
                const Job* hero = hero_job();
                install_action(hwnd, hero && hero->state == Job::kDone ? kActOpen : kActClose);
            }
            return 0;
        case WM_CLOSE:
            if (installs_active()) {
                if (ask_stop_installing(hwnd)) cancel_all_installs();
                return 0;  // the window stays to show the result
            }
            break;
        case WM_DESTROY:
            g_install_wnd = nullptr;
            g_iv = InstallView{};
            if (!any_pending()) g_jobs.clear();
            maybe_quit();
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

void show_install_window() {
    if (g_install_wnd) {
        poll_install(g_install_wnd);
        fit_install_window();
        if (IsIconic(g_install_wnd)) ShowWindow(g_install_wnd, SW_RESTORE);
        SetForegroundWindow(g_install_wnd);
        return;
    }
    g_iv.details = false;  // collapsed each time: the log is for when something needs a look
    g_install_wnd = open_tool_window(L"WinApkInstall", install_title().c_str(), ipx(440), ipx(200),
                                     WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX);
    if (g_install_wnd) {
        poll_install(g_install_wnd);
        fit_install_window();
    }
}

// ---------------------------------------------------------------------------
// The log window: which log, search, Copy and Open folder, and the log itself
// in a read-only RichEdit (Windows' own msftedit.dll) that follows the end as
// the runtime writes it, with error lines and search matches marked.

struct LogView {
    fs::path path;
    uintmax_t read = 0;           // bytes of the file shown so far (whole lines)
    bool missing = false;
    HWND search = nullptr;
    std::wstring query;
    std::vector<std::pair<LONG, LONG>> matches;  // character ranges
    int current = -1;
    Targets targets;
    int hot = 0;
    bool tracking = false;
} g_lv;

enum { kLogPick = 1, kLogCopy, kLogFolder };
constexpr int kIdLogSearch = 101;

// What the picker calls a log: the app and which log, in words.
std::wstring log_title(const fs::path& p) {
    const std::wstring stem = p.stem().wstring();
    auto ends = [&](const std::wstring& suffix) {
        return stem.size() > suffix.size() && stem.compare(stem.size() - suffix.size(), suffix.size(), suffix) == 0;
    };
    if (stem.rfind(L"run-", 0) == 0) {
        if (ends(L".1")) return fmt(L"{0}  ·  run before", {label_of(stem.substr(4, stem.size() - 6))});
        return fmt(L"{0}  ·  last run", {label_of(stem.substr(4))});
    }
    if (stem.rfind(L"install-", 0) == 0 && stem != L"install-helper") {
        return fmt(L"{0}  ·  install", {label_of(stem.substr(8))});
    }
    if (stem == L"gms") return L"Google sign-in";
    if (stem == L"google-setup") return L"Google services setup";
    if (stem == L"install-helper") return L"Installs asked for by apps";
    return stem;
}

// The logs, the newest first; last-run.log is a second name for one of them, so it is left out.
std::vector<fs::path> log_files() {
    std::vector<std::pair<fs::file_time_type, fs::path>> found;
    std::error_code ec;
    for (const auto& e : fs::directory_iterator(logs_dir(), ec)) {
        if (e.path().extension() == L".log" && e.path().filename() != L"last-run.log")
            found.push_back({e.last_write_time(ec), e.path()});
    }
    std::sort(found.begin(), found.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    std::vector<fs::path> out;
    for (auto& f : found) out.push_back(std::move(f.second));
    return out;
}

bool error_line(const std::wstring& l) {
    return l.find(L"rror") != std::wstring::npos || l.find(L"xception") != std::wstring::npos ||
           l.rfind(L"[E]", 0) == 0 || l.rfind(L"[F]", 0) == 0 || l.find(L"FATAL") != std::wstring::npos;
}

void log_format(LONG from, LONG to, COLORREF color, bool back, COLORREF back_color = 0) {
    CHARRANGE cr{from, to};
    SendMessageW(g_log_edit, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&cr));
    CHARFORMAT2W cf{};
    cf.cbSize = sizeof(cf);
    if (back) {
        cf.dwMask = CFM_BACKCOLOR;
        cf.crBackColor = back_color;
        if (back_color == CLR_DEFAULT) {
            cf.dwEffects = CFE_AUTOBACKCOLOR;
            cf.crBackColor = 0;
        }
    } else {
        cf.dwMask = CFM_COLOR;
        cf.crTextColor = color;
    }
    SendMessageW(g_log_edit, EM_SETCHARFORMAT, SCF_SELECTION, reinterpret_cast<LPARAM>(&cf));
}

// Marks every match of the search and selects the current one.
void log_mark_matches(bool keep_current) {
    SendMessageW(g_log_edit, WM_SETREDRAW, FALSE, 0);
    CHARRANGE keep{};
    SendMessageW(g_log_edit, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&keep));
    for (const auto& m : g_lv.matches) log_format(m.first, m.second, 0, true, CLR_DEFAULT);
    g_lv.matches.clear();
    if (!g_lv.query.empty()) {
        FINDTEXTEXW ft{};
        ft.chrg = {0, -1};
        ft.lpstrText = g_lv.query.c_str();
        while (g_lv.matches.size() < 5000) {
            const LRESULT at = SendMessageW(g_log_edit, EM_FINDTEXTEXW, FR_DOWN, reinterpret_cast<LPARAM>(&ft));
            if (at < 0) break;
            g_lv.matches.push_back({ft.chrgText.cpMin, ft.chrgText.cpMax});
            ft.chrg.cpMin = ft.chrgText.cpMax;
        }
        const COLORREF hl = g_t.dark ? RGB(110, 90, 20) : RGB(255, 230, 140);
        for (const auto& m : g_lv.matches) log_format(m.first, m.second, 0, true, hl);
    }
    if (!keep_current || g_lv.current >= static_cast<int>(g_lv.matches.size())) g_lv.current = g_lv.matches.empty() ? -1 : 0;
    SendMessageW(g_log_edit, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&keep));
    SendMessageW(g_log_edit, WM_SETREDRAW, TRUE, 0);
    InvalidateRect(g_log_edit, nullptr, TRUE);
    InvalidateRect(g_log_wnd, nullptr, FALSE);
}

void log_go_to_match(int step) {
    if (g_lv.matches.empty()) return;
    const int n = static_cast<int>(g_lv.matches.size());
    g_lv.current = ((g_lv.current < 0 ? 0 : g_lv.current + step) % n + n) % n;
    const auto& m = g_lv.matches[static_cast<size_t>(g_lv.current)];
    CHARRANGE cr{m.first, m.second};
    SendMessageW(g_log_edit, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&cr));
    SendMessageW(g_log_edit, EM_SCROLLCARET, 0, 0);
    InvalidateRect(g_log_wnd, nullptr, FALSE);
}

// Reads what the file gained since last time (the whole file when `force`, or when it was started
// again and shrank), appends it and marks its error lines. The view stays at the end if it was there.
void load_log(bool force) {
    std::error_code ec;
    const bool exists = fs::exists(g_lv.path, ec);
    const uintmax_t size = exists ? fs::file_size(g_lv.path, ec) : 0;
    if (!force && exists == !g_lv.missing && size == g_lv.read) return;
    if (force || size < g_lv.read || !exists || g_lv.missing) {
        SetWindowTextW(g_log_edit, L"");
        g_lv.read = 0;
        g_lv.matches.clear();
    }
    g_lv.missing = !exists;
    if (!exists) {
        SetWindowTextW(g_log_edit, L"This log is written the next time the app is opened or installed.");
        InvalidateRect(g_log_wnd, nullptr, FALSE);
        return;
    }
    // At most the last megabyte at first; then only whole new lines.
    const uintmax_t from = g_lv.read == 0 && size > (1u << 20) ? size - (1u << 20) : g_lv.read;
    std::string bytes;
    HANDLE f = CreateFileW(g_lv.path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (f == INVALID_HANDLE_VALUE) return;
    LARGE_INTEGER at{};
    at.QuadPart = static_cast<LONGLONG>(from);
    SetFilePointerEx(f, at, nullptr, FILE_BEGIN);
    bytes.resize(static_cast<size_t>(size - from));
    DWORD got = 0;
    if (!bytes.empty()) ReadFile(f, &bytes[0], static_cast<DWORD>(bytes.size()), &got, nullptr);
    CloseHandle(f);
    bytes.resize(got);
    const size_t last_nl = bytes.rfind('\n');
    if (last_nl == std::string::npos) return;  // no whole line yet
    bytes.resize(last_nl + 1);
    if (g_lv.read == 0 && from > 0) bytes.erase(0, bytes.find('\n') + 1);  // the first line was cut
    g_lv.read = from + last_nl + 1;
    std::wstring text;
    const std::wstring raw = utf8_to_wide(bytes);
    text.reserve(raw.size());
    for (wchar_t c : raw) {
        if (c != L'\r') text += c == L'\n' ? L'\r' : c;  // RichEdit's line end
    }

    SCROLLINFO si{sizeof(si), SIF_ALL};
    GetScrollInfo(g_log_edit, SB_VERT, &si);
    const bool at_end = force || si.nPos + static_cast<int>(si.nPage) >= si.nMax - 2;
    SendMessageW(g_log_edit, WM_SETREDRAW, FALSE, 0);
    CHARRANGE keep{};
    SendMessageW(g_log_edit, EM_EXGETSEL, 0, reinterpret_cast<LPARAM>(&keep));
    const LONG start = GetWindowTextLengthW(g_log_edit);
    CHARRANGE end{start, start};
    SendMessageW(g_log_edit, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&end));
    SendMessageW(g_log_edit, EM_REPLACESEL, FALSE, reinterpret_cast<LPARAM>(text.c_str()));
    // The new lines that report errors, in the error colour.
    LONG pos = start;
    size_t line_at = 0;
    while (line_at < text.size()) {
        size_t nl = text.find(L'\r', line_at);
        if (nl == std::wstring::npos) nl = text.size();
        if (error_line(text.substr(line_at, nl - line_at)))
            log_format(pos, pos + static_cast<LONG>(nl - line_at), colorref(g_t.danger), false);
        pos += static_cast<LONG>(nl - line_at + 1);
        line_at = nl + 1;
    }
    SendMessageW(g_log_edit, EM_EXSETSEL, 0, reinterpret_cast<LPARAM>(&keep));
    SendMessageW(g_log_edit, WM_SETREDRAW, TRUE, 0);
    if (!g_lv.query.empty()) log_mark_matches(true);
    if (at_end) {
        SendMessageW(g_log_edit, WM_VSCROLL, SB_BOTTOM, 0);
    }
    InvalidateRect(g_log_edit, nullptr, TRUE);
    InvalidateRect(g_log_wnd, nullptr, FALSE);
}

constexpr float kLogTools = 56;

void paint_logs(Graphics& g, float W, float H) {
    g.Clear(g_t.layer);
    g_lv.targets.clear();
    const float m = px(16), ch = px(32), cy = (px(kLogTools) - ch) / 2;
    // The picker: which log.
    const RectF pick(m, cy, (std::min)(px(280), W * 0.34f), ch);
    fill_round(g, pick, px(kRadiusControl), g_lv.hot == kLogPick ? g_t.hover : g_t.card);
    stroke_round(g, pick, px(kRadiusControl), g_t.border);
    draw_text(g, log_title(g_lv.path), g_f.body.get(), g_t.text, RectF(pick.X + px(12), pick.Y, pick.Width - px(40), ch));
    draw_text(g, kGlyphChevron, g_f.glyph12.get(), g_t.text2, RectF(pick.GetRight() - px(30), pick.Y, px(22), ch),
              kCenter);
    g_lv.targets.push_back({pick, kLogPick});
    // Copy and Open folder at the end.
    float x = W - m;
    for (auto [label, act] : {std::pair<const wchar_t*, int>{L"Open folder", kLogFolder}, {L"Copy", kLogCopy}}) {
        const float w = text_width(g, label, g_f.body.get()) + px(32);
        x -= w;
        const RectF r(x, cy, w, ch);
        draw_button(g, r, label, g_lv.hot == act, false, false);
        g_lv.targets.push_back({r, act});
        x -= px(8);
    }
    // The search box between them (the edit is a child; this is its frame), with the match count.
    const RectF box(pick.GetRight() + px(12), cy, (std::max)(px(80), x - px(4) - pick.GetRight() - px(12)), ch);
    const bool focused = GetFocus() == g_lv.search;
    fill_round(g, box, px(kRadiusControl), g_t.card);
    stroke_round(g, box, px(kRadiusControl), focused ? g_t.accent : g_t.border, focused ? px(1.5f) : 1);
    draw_text(g, kGlyphSearch, g_f.glyph12.get(), g_t.text2, RectF(box.X + px(10), box.Y, px(16), ch), kCenter);
    if (!g_lv.query.empty()) {
        const std::wstring count = g_lv.matches.empty()
                                       ? std::wstring(L"No matches")
                                       : fmt(L"{0} of {1}", {std::to_wstring(g_lv.current + 1),
                                                             std::to_wstring(g_lv.matches.size())});
        draw_text(g, count, g_f.caption.get(), g_t.text2, RectF(box.GetRight() - px(96), box.Y, px(88), ch), kRight);
    }
    Gdiplus::Pen line(g_t.border, 1);
    g.DrawLine(&line, 0.0f, px(kLogTools), W, px(kLogTools));
    (void)H;
}

void layout_logs(HWND hwnd) {
    RECT rc;
    GetClientRect(hwnd, &rc);
    const int top = ipx(kLogTools) + 1;
    MoveWindow(g_log_edit, 0, top, rc.right, (std::max)(0L, rc.bottom - top), TRUE);
    RECT text;  // some room around the text
    GetClientRect(g_log_edit, &text);
    InflateRect(&text, -ipx(12), -ipx(8));
    SendMessageW(g_log_edit, EM_SETRECT, 0, reinterpret_cast<LPARAM>(&text));
    // The search edit sits inside the frame paint_logs draws: after the picker, before the buttons.
    HDC dc = GetDC(hwnd);
    float buttons = 0;
    {
        Graphics g(dc);
        for (const wchar_t* label : {L"Open folder", L"Copy"}) buttons += text_width(g, label, g_f.body.get()) + px(40);
    }
    ReleaseDC(hwnd, dc);
    const float W = static_cast<float>(rc.right), m = px(16);
    const float pick_r = m + (std::min)(px(280), W * 0.34f);
    const float bx = pick_r + px(12), bw = (std::max)(px(80), W - m - buttons - px(4) - bx);
    const int eh = ipx(20);
    MoveWindow(g_lv.search, static_cast<int>(bx + px(32)), static_cast<int>((px(kLogTools) - eh) / 2),
               static_cast<int>(bw - px(32) - px(100)), eh, TRUE);
}

LRESULT CALLBACK log_search_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp, UINT_PTR, DWORD_PTR) {
    if (msg == WM_KEYDOWN) {
        const bool shift = GetKeyState(VK_SHIFT) < 0;
        if (wp == VK_RETURN || wp == VK_F3) {
            log_go_to_match(shift ? -1 : 1);
            return 0;
        }
        if (wp == VK_ESCAPE) {
            if (GetWindowTextLengthW(hwnd) > 0) SetWindowTextW(hwnd, L"");
            else DestroyWindow(g_log_wnd);
            return 0;
        }
    }
    if (msg == WM_CHAR && (wp == VK_RETURN || wp == VK_ESCAPE)) return 0;  // no beep
    return DefSubclassProc(hwnd, msg, wp, lp);
}

void log_action(HWND hwnd, int act) {
    if (act == kLogPick) {
        const auto files = log_files();
        if (files.empty()) return;
        HMENU menu = CreatePopupMenu();
        for (size_t i = 0; i < files.size() && i < 40; ++i) {
            AppendMenuW(menu, MF_STRING | (files[i] == g_lv.path ? MF_CHECKED : 0), i + 1, log_title(files[i]).c_str());
        }
        const RectF& r = g_lv.targets[0].first;
        POINT pt{static_cast<LONG>(r.X), static_cast<LONG>(r.GetBottom() + px(2))};
        ClientToScreen(hwnd, &pt);
        const int pick = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_LEFTALIGN, pt.x, pt.y, 0, hwnd, nullptr);
        DestroyMenu(menu);
        if (pick > 0) {
            g_lv.path = files[static_cast<size_t>(pick - 1)];
            load_log(true);
        }
    } else if (act == kLogCopy) {
        const int len = GetWindowTextLengthW(g_log_edit);
        std::wstring text(static_cast<size_t>(len) + 1, L'\0');
        GetWindowTextW(g_log_edit, &text[0], len + 1);
        text.resize(static_cast<size_t>(len));
        std::wstring crlf;
        crlf.reserve(text.size() + text.size() / 32);
        for (wchar_t c : text) {
            if (c == L'\r') crlf += L"\r\n";
            else crlf += c;
        }
        copy_text(hwnd, crlf);
    } else if (act == kLogFolder) {
        std::error_code ec;
        if (fs::exists(g_lv.path, ec)) show_in_explorer(g_lv.path);
        else open_path(logs_dir());
    }
}

LRESULT CALLBACK log_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE: {
            static HMODULE richedit = LoadLibraryW(L"Msftedit.dll");
            (void)richedit;
            g_log_edit = CreateWindowExW(WS_EX_LTRREADING | WS_EX_LEFT | WS_EX_RIGHTSCROLLBAR, MSFTEDIT_CLASS, L"",
                                         WS_CHILD | WS_VISIBLE | WS_VSCROLL | WS_HSCROLL | ES_LEFT | ES_MULTILINE |
                                             ES_READONLY | ES_AUTOVSCROLL | ES_AUTOHSCROLL | ES_NOHIDESEL,
                                         0, 0, 0, 0, hwnd, nullptr, g_inst, nullptr);
            SendMessageW(g_log_edit, EM_EXLIMITTEXT, 0, 64 << 20);
            SendMessageW(g_log_edit, EM_SETBKGNDCOLOR, 0, colorref(code_bg()));
            CHARFORMAT2W cf{};
            cf.cbSize = sizeof(cf);
            cf.dwMask = CFM_FACE | CFM_SIZE | CFM_COLOR;
            cf.yHeight = 200;  // 10 pt, in twips
            cf.crTextColor = colorref(g_t.text);
            Gdiplus::FontFamily cascadia(L"Cascadia Mono");
            wcscpy(cf.szFaceName, cascadia.GetLastStatus() == Gdiplus::Ok && cascadia.IsAvailable() ? L"Cascadia Mono"
                                                                                                   : L"Consolas");
            SendMessageW(g_log_edit, EM_SETCHARFORMAT, SCF_ALL, reinterpret_cast<LPARAM>(&cf));
            SendMessageW(g_log_edit, EM_SETCHARFORMAT, SCF_DEFAULT, reinterpret_cast<LPARAM>(&cf));
            SetWindowTheme(g_log_edit, g_t.dark ? L"DarkMode_Explorer" : L"Explorer", nullptr);
            g_lv.search = CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL,
                                          0, 0, 0, 0, hwnd, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kIdLogSearch)),
                                          g_inst, nullptr);
            SendMessageW(g_lv.search, WM_SETFONT, reinterpret_cast<WPARAM>(g_edit_font), FALSE);
            SendMessageW(g_lv.search, EM_SETCUEBANNER, FALSE, reinterpret_cast<LPARAM>(L"Search the log"));
            SetWindowSubclass(g_lv.search, log_search_proc, 1, 0);
            SetTimer(hwnd, 1, 700, nullptr);
            return 0;
        }
        case WM_SIZE: layout_logs(hwnd); InvalidateRect(hwnd, nullptr, FALSE); return 0;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: buffered_paint(hwnd, paint_logs); return 0;
        case WM_CTLCOLOREDIT:
            SetTextColor(reinterpret_cast<HDC>(wp), colorref(g_t.text));
            SetBkColor(reinterpret_cast<HDC>(wp), colorref(g_t.card));
            return reinterpret_cast<LRESULT>(g_edit_brush);
        case WM_COMMAND:
            if (LOWORD(wp) == kIdLogSearch) {
                if (HIWORD(wp) == EN_CHANGE) {
                    wchar_t buf[256] = {};
                    GetWindowTextW(g_lv.search, buf, 256);
                    g_lv.query = buf;
                    log_mark_matches(false);
                    if (!g_lv.matches.empty()) log_go_to_match(0);
                } else if (HIWORD(wp) == EN_SETFOCUS || HIWORD(wp) == EN_KILLFOCUS) {
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;
        case WM_TIMER: load_log(false); return 0;
        case WM_MOUSEMOVE: track_hot(hwnd, lp, g_lv.targets, &g_lv.hot, &g_lv.tracking); return 0;
        case WM_MOUSELEAVE:
            g_lv.tracking = false;
            g_lv.hot = 0;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT && reinterpret_cast<HWND>(wp) == hwnd) {
                SetCursor(LoadCursorW(nullptr, g_lv.hot ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_LBUTTONUP: log_action(hwnd, find_target(g_lv.targets, lp)); return 0;
        case WM_ACTIVATE:
            if (LOWORD(wp) != WA_INACTIVE) SetFocus(g_lv.search);
            return 0;
        case WM_GETMINMAXINFO:
            reinterpret_cast<MINMAXINFO*>(lp)->ptMinTrackSize = {ipx(560), ipx(360)};
            return 0;
        case WM_DESTROY:
            g_log_wnd = g_log_edit = nullptr;
            g_lv = LogView{};
            maybe_quit();
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// The log window on `log`; the newest log when none is named.
void show_logs(const fs::path& log) {
    fs::path path = log;
    if (path.empty()) {
        const auto files = log_files();
        path = files.empty() ? last_run_log() : files.front();
    }
    g_lv.path = path;
    if (!g_log_wnd) {
        g_log_wnd = open_tool_window(L"WinApkLogs", L"Log - Win.apk", ipx(860), ipx(620));
        if (!g_log_wnd) return;
        layout_logs(g_log_wnd);
    } else {
        ShowWindow(g_log_wnd, SW_RESTORE);
        SetForegroundWindow(g_log_wnd);
    }
    load_log(true);
}

// ---------------------------------------------------------------------------
// The launch splash: shown the moment an app is opened, as a phone shows one,
// until the app's own window is up (the runtime's ready event, kRunReady).
// If the app ends first it turns into a short error with Show log and Report a problem.

struct Splash {
    std::wstring name;
    HWND hwnd = nullptr;
    ULONGLONG start = 0, phase_read = 0;
    const wchar_t* phase = nullptr;  // what the run log says it is doing; null: starting
    bool failed = false;
    std::wstring error_title, error_text;
    fs::path log;  // Show log's
    float need_h = 0;  // the failed state's height, when its sentence is long
    float need_w = 0;  // and its width, when its title is long ("<a long name> stopped with an error")
    std::unique_ptr<Gdiplus::Bitmap> icon;  // the app's, at the starting window's size
    Targets targets;
    int hot = 0;
    bool tracking = false;
};

std::map<std::wstring, std::unique_ptr<Splash>> g_splashes;
enum { kSplashClose = 1, kSplashLog = 2, kSplashCancel = 3, kSplashReport = 4 };
constexpr UINT kFitSplash = WM_APP + 21;

void maybe_quit();

Splash* splash_of(HWND hwnd) { return reinterpret_cast<Splash*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA)); }

// The step of a start that takes long, from the lines the runtime writes; null when there is none to tell.
const wchar_t* run_phase(const fs::path& log) {
    const std::string tail = read_tail(log, 64 * 1024);
    if (tail.find("converting the app's code") != std::string::npos &&
        tail.find("host natives:") == std::string::npos) {
        return L"Converting the app's code. This happens once.";
    }
    if (tail.find("installing the app (once per install)") != std::string::npos &&
        tail.find("host natives:") == std::string::npos) {
        return L"Getting the app ready. This happens once.";
    }
    return nullptr;
}

// Why the app did not come up, in a sentence: the log's own reason when it has a known one.
std::wstring failure_reason(const fs::path& log, bool clean_exit) {
    bool no_native_code = false;
    log_summary(log, &no_native_code);
    if (no_native_code) {
        return L"This file has no code for PCs. Install the full bundle (.apkm, .xapk or .apks) that includes "
                  L"the arm64-v8a part.";
    }
    return clean_exit ? L"The app closed before it showed a window." : L"The app stopped with an error.";
}

void paint_splash(Splash& s, Graphics& g, float W, float H) {
    g.Clear(g_t.layer);
    s.targets.clear();
    const App* app = find_app(s.name);
    const std::wstring label = app ? app->label : job_label(s.name);
    Gdiplus::Bitmap* icon = s.icon ? s.icon.get() : app ? icon_for(*app) : nullptr;
    const float m = px(24);
    if (!s.failed) {
        // The app's starting window, as Android draws one: its icon in the middle of its own window, and
        // nothing else unless the start takes long. Three dots that brighten in turn say it is alive.
        const float is = (std::min)(px(96), (std::min)(W, H) * 0.4f);
        const RectF ir((W - is) / 2, (H - is) / 2 - px(16), is, is);
        draw_icon(g, icon, label, ir);
        const float t = static_cast<float>(GetTickCount64() % 1200) / 1200.0f, d = px(7), gap = px(8);
        const float x0 = (W - 3 * d - 2 * gap) / 2, y = ir.GetBottom() + px(28);
        for (int i = 0; i < 3; ++i) {
            float phase = t - i * 0.17f;
            phase -= std::floor(phase);
            const float lit = phase < 0.4f ? std::sin(phase / 0.4f * 3.14159f) : 0.0f;
            Gdiplus::SolidBrush b(with_alpha(g_t.accent, static_cast<BYTE>(70 + 185 * lit)));
            g.FillEllipse(&b, x0 + i * (d + gap), y, d, d);
        }
        std::wstring sub = s.phase ? std::wstring(s.phase) : std::wstring();
        if (!s.phase && GetTickCount64() - s.start > 20000) sub = L"Still starting. The first start can take a minute.";
        if (!sub.empty()) {
            draw_text(g, sub, g_f.caption.get(), g_t.text2, RectF(m, y + d + px(14), W - 2 * m, px(40)), kCenter, true,
                      false);
        }
        return;
    }
    // The error, a block of the old splash's size in the middle of the window, taller when its sentence
    // is long (kFitSplash also grows a window too small for it).
    const float block = (std::max)(px(300), s.need_h);
    const float top = (std::max)(0.0f, (H - block) / 2);
    const float is = px(88);
    const RectF ir((W - is) / 2, top + px(40), is, is);
    draw_icon(g, icon, label, ir);
    const RectF badge(ir.GetRight() - px(20), ir.GetBottom() - px(20), px(26), px(26));
    Gdiplus::SolidBrush ring(g_t.layer), dot(g_t.danger);
    g.FillEllipse(&ring, inset(badge, -px(2), -px(2)));
    g.FillEllipse(&dot, badge);
    draw_text(g, L"!", g_f.body_semi.get(), kWhite, badge, kCenter);
    draw_text(g, s.error_title, g_f.title.get(), g_t.text, RectF(m, top + px(144), W - 2 * m, px(30)), kCenter);
    draw_text(g, s.error_text, g_f.caption.get(), g_t.text2, RectF(m, top + px(178), W - 2 * m, px(54)), kCenter, true,
              false);
    // Report a problem as a link under the sentence, as under a failed install's reason.
    const float sh = (std::min)(px(54), text_height(g, s.error_text, g_f.caption.get(), W - 2 * m));
    const std::wstring report = L"Report a problem";
    const float rw = text_width(g, report, g_f.body.get()) + px(4);
    const RectF rep((W - rw) / 2, top + px(178) + sh + px(6), rw, px(22));
    draw_text(g, report, g_f.body.get(), s.hot == kSplashReport ? mix(g_t.accent, g_t.text, 0.3f) : g_t.accent, rep,
              kCenter);
    const float need = (std::max)(px(300), rep.GetBottom() - top + px(16) + px(32) + px(24));
    // As wide as the title, up to 560: a long app name is not cut to an ellipsis.
    const float need_w = (std::min)(px(560), (std::max)(px(300), text_width(g, s.error_title, g_f.title.get()) +
                                                                   2 * m + px(8)));
    // Windows' order and the dialog's widths: Close (the accent one), then Show log.
    const float cw = button_width(g, L"Close", true), lw = button_width(g, L"Show log", false);
    const float by = top + block - px(24) - px(32);
    const RectF close((W - cw - lw - px(8)) / 2, by, cw, px(32)), log(close.GetRight() + px(8), by, lw, px(32));
    draw_button(g, log, L"Show log", s.hot == kSplashLog, false, false);
    draw_button(g, close, L"Close", s.hot == kSplashClose, true, false);
    s.targets.push_back({rep, kSplashReport});
    s.targets.push_back({log, kSplashLog});
    s.targets.push_back({close, kSplashClose});
    if (std::lround(need) != std::lround(s.need_h) || std::lround(need_w) != std::lround(s.need_w)) {
        s.need_h = need;
        s.need_w = need_w;
        PostMessageW(s.hwnd, kFitSplash, 0, 0);
    }
}

void splash_action(Splash& s, int act) {
    if (act == kSplashLog) {
        show_logs(s.log);
        DestroyWindow(s.hwnd);
    } else if (act == kSplashReport) {
        const App* app = find_app(s.name);
        report_problem(s.hwnd, s.name, app ? app->label : job_label(s.name), s.log, s.error_text);
    } else if (act == kSplashClose) {
        DestroyWindow(s.hwnd);
    } else if (act == kSplashCancel) {
        // Whether the runtime is up, still starting or already ending, its exit is now no failure.
        stop_run(s.name);
        expect_run_end(s.name);
        DestroyWindow(s.hwnd);
    }
}

LRESULT CALLBACK splash_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    Splash* s = splash_of(hwnd);
    switch (msg) {
        case WM_NCCREATE:
            SetWindowLongPtrW(hwnd, GWLP_USERDATA,
                              reinterpret_cast<LONG_PTR>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams));
            break;
        case WM_CREATE: SetTimer(hwnd, 1, 40, nullptr); return 0;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT:
            buffered_paint(hwnd, [&](Graphics& g, float w, float h) { paint_splash(*s, g, w, h); });
            return 0;
        case kFitSplash: {  // the error needs a client area of its title's width (300 dp at least) by its own height
            RECT wr, rc;
            GetWindowRect(hwnd, &wr);
            GetClientRect(hwnd, &rc);
            const int want_h = static_cast<int>(std::lround((std::max)(px(300), s->need_h)));
            const int want_w = static_cast<int>(std::lround((std::max)(px(300), s->need_w)));
            const int grow_w = (std::max)(0, want_w - static_cast<int>(rc.right));
            const int grow_h = (std::max)(0, want_h - static_cast<int>(rc.bottom));
            if (grow_w || grow_h) {
                SetWindowPos(hwnd, nullptr, wr.left - grow_w / 2, wr.top - grow_h / 2, wr.right - wr.left + grow_w,
                             wr.bottom - wr.top + grow_h, SWP_NOZORDER | SWP_NOACTIVATE);
            }
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_TIMER:
            if (!s->failed && GetTickCount64() - s->phase_read > 500) {
                s->phase = run_phase(run_log(s->name));
                s->phase_read = GetTickCount64();
            }
            if (!s->failed) InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_NCHITTEST: {  // the app's window to be: its frame, but not one to resize
            const LRESULT hit = DefWindowProcW(hwnd, msg, wp, lp);
            return hit >= HTLEFT && hit <= HTBOTTOMRIGHT ? HTBORDER : hit;
        }
        case WM_SYSCOMMAND:  // nor to maximize: it has the size the app's window opens at
            if ((wp & 0xFFF0) == SC_MAXIMIZE || (wp & 0xFFF0) == SC_SIZE) return 0;
            break;
        case WM_MOUSEMOVE: track_hot(hwnd, lp, s->targets, &s->hot, &s->tracking); return 0;
        case WM_MOUSELEAVE:
            s->tracking = false;
            s->hot = 0;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT) {
                SetCursor(LoadCursorW(nullptr, s->hot ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_LBUTTONUP: splash_action(*s, find_target(s->targets, lp)); return 0;
        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) splash_action(*s, s->failed ? kSplashClose : kSplashCancel);
            else if (wp == VK_RETURN && s->failed) splash_action(*s, kSplashClose);
            return 0;
        case WM_CLOSE:  // its close button, as the app's own: the start is cancelled
            splash_action(*s, s->failed ? kSplashClose : kSplashCancel);
            return 0;
        case WM_DESTROY: {
            const std::wstring name = s->name;
            g_splashes.erase(name);  // frees *s
            maybe_quit();
            return 0;
        }
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// The window's own System.AppUserModel.ID: the taskbar then counts it as the app's (one button with the
// app's window, under the app's shortcut), not as Win.apk's.
void set_window_app_id(HWND hwnd, const std::wstring& aumid) {
    static const PROPERTYKEY kAppUserModelId = {
        {0x9f4c2855, 0x9f79, 0x4b39, {0xa8, 0xd0, 0xe1, 0xd4, 0x2d, 0xe1, 0xd5, 0xf3}}, 5};
    IPropertyStore* props = nullptr;
    if (FAILED(SHGetPropertyStoreForWindow(hwnd, IID_IPropertyStore, reinterpret_cast<void**>(&props))) || !props) {
        return;
    }
    PROPVARIANT v{};
    v.vt = VT_LPWSTR;
    v.pwszVal = const_cast<wchar_t*>(aumid.c_str());  // read, not freed: no PropVariantClear
    if (SUCCEEDED(props->SetValue(kAppUserModelId, v))) props->Commit();
    props->Release();
}

// The outer size of the window the app `name` will open with on a monitor at `dpi` with work area `wa`:
// the runtime's own arithmetic (host/window_fit.h) on the phone size it will be given, so that the
// starting window is the app's window in everything but its content.
SIZE app_window_size(const std::wstring& name, UINT dpi, const RECT& wa) {
    int w = 360, h = 800;
    runtime_phone_size(name, &w, &h);
    // The size the user last gave this app's window comes first, as in the runtime (launch_phone_size).
    if (std::ifstream f(library_dir() / (name + L".window")); f) {
        int sw = 0, sh = 0;
        char x = 0;
        if (f >> sw >> x >> sh && x == 'x' && sw >= 200 && sh >= 200 && sw <= 4000 && sh <= 4000) {
            w = sw;
            h = sh;
        }
    }
    RECT frame{0, 0, 0, 0};
    AdjustWindowRectExForDpi(&frame, WS_OVERLAPPEDWINDOW, FALSE, 0, dpi);
    const int fw = frame.right - frame.left, fh = frame.bottom - frame.top, m = MulDiv(4, static_cast<int>(dpi), 96);
    int cw = 0, ch = 0;
    winapk::host::window_launch_client(w, h, static_cast<int>(dpi), (wa.right - wa.left) - fw - 2 * m,
                                       (wa.bottom - wa.top) - fh - 2 * m, &cw, &ch);
    return {cw + fw, ch + fh};
}

// The starting window for `name`, made now: where the app's window will open and of its size, with the
// app's name and icon on its frame and on the taskbar, so that it is the app's window to the eye from the
// first moment; the runtime opens the real one over it (WINAPK_START_WINDOW) and it goes. `compact`: only
// to say how a run ended, when no start is on show: the old splash's size.
Splash* show_splash(const std::wstring& name, bool compact) {
    if (auto it = g_splashes.find(name); it != g_splashes.end()) return it->second.get();
    auto s = std::make_unique<Splash>();
    s->name = name;
    s->start = GetTickCount64();
    s->log = run_log(name);
    POINT cursor;
    GetCursorPos(&cursor);
    MONITORINFO mi{sizeof(mi)};
    GetMonitorInfoW(MonitorFromPoint(cursor, MONITOR_DEFAULTTOPRIMARY), &mi);
    const RECT& wa = mi.rcWork;
    const App* app = find_app(name);
    // Made on that monitor, so that its DPI is the window's; sized and placed before it is shown.
    HWND hwnd = CreateWindowExW(0, L"WinApkSplash", (app ? app->label : name).c_str(),
                                WS_OVERLAPPEDWINDOW, wa.left, wa.top, ipx(300), ipx(300), nullptr, nullptr, g_inst,
                                s.get());
    if (!hwnd) return nullptr;
    s->hwnd = hwnd;
    const UINT dpi = GetDpiForWindow(hwnd);
    SIZE size;
    if (compact) {
        RECT r{0, 0, MulDiv(300, static_cast<int>(dpi), 96), MulDiv(300, static_cast<int>(dpi), 96)};
        AdjustWindowRectExForDpi(&r, WS_OVERLAPPEDWINDOW, FALSE, 0, dpi);
        size = {r.right - r.left, r.bottom - r.top};
    } else {
        size = app_window_size(name, dpi, wa);
    }
    SetWindowPos(hwnd, nullptr, wa.left + ((wa.right - wa.left) - size.cx) / 2,
                 wa.top + ((wa.bottom - wa.top) - size.cy) / 2, size.cx, size.cy, SWP_NOZORDER | SWP_NOACTIVATE);
    set_window_app_id(hwnd, app_user_model_id(name));
    for (const int which : {ICON_BIG, ICON_SMALL}) {
        const int ip = GetSystemMetrics(which == ICON_BIG ? SM_CXICON : SM_CXSMICON);
        if (auto icon = static_cast<HICON>(LoadImageW(nullptr, shortcut_icon(name).c_str(), IMAGE_ICON, ip, ip,
                                                      LR_LOADFROMFILE))) {
            SendMessageW(hwnd, WM_SETICON, static_cast<WPARAM>(which), reinterpret_cast<LPARAM>(icon));
        }
    }
    s->icon = rounded_icon(app_dir(name) / L"icon.png", ipx(96));
    Splash* raw = s.get();
    g_splashes[name] = std::move(s);
    ShowWindow(hwnd, SW_SHOW);
    UpdateWindow(hwnd);
    SetForegroundWindow(hwnd);
    SetFocus(hwnd);  // Esc cancels
    return raw;
}

// Shows the app's starting window; null when it could not be made.
HWND start_window(const std::wstring& name) {
    const Splash* s = show_splash(name);
    return s ? s->hwnd : nullptr;
}

// The starting window turned into the error: the one still up, else a small new one (an app that failed
// after its window was up).
void splash_failed(const std::wstring& name, const std::wstring& title, const std::wstring& text) {
    Splash* s = show_splash(name, true);
    if (!s) return;
    s->failed = true;
    s->error_title = title;
    s->error_text = text;
    s->log = run_log(name);
    PostMessageW(s->hwnd, kFitSplash, 0, 0);  // a starting window smaller than the error grows to hold it
    InvalidateRect(s->hwnd, nullptr, FALSE);
    SetForegroundWindow(s->hwnd);
}

void close_splash(const std::wstring& name) {
    const auto it = g_splashes.find(name);
    if (it != g_splashes.end() && !it->second->failed) DestroyWindow(it->second->hwnd);
}

// ---------------------------------------------------------------------------
// Drag and drop, through OLE so the drop zone can light up while dragging

std::vector<fs::path> dropped_files(IDataObject* data) {
    std::vector<fs::path> out;
    FORMATETC fe{CF_HDROP, nullptr, DVASPECT_CONTENT, -1, TYMED_HGLOBAL};
    STGMEDIUM m{};
    if (FAILED(data->GetData(&fe, &m))) return out;
    if (auto drop = static_cast<HDROP>(GlobalLock(m.hGlobal))) {
        const UINT n = DragQueryFileW(drop, 0xFFFFFFFF, nullptr, 0);
        for (UINT i = 0; i < n; ++i) {
            wchar_t path[MAX_PATH * 4];
            if (DragQueryFileW(drop, i, path, MAX_PATH * 4) && is_app_file(path)) out.emplace_back(path);
        }
        GlobalUnlock(m.hGlobal);
    }
    ReleaseStgMedium(&m);
    return out;
}

void set_view(View view);

class DropTarget : public IDropTarget {
public:
    virtual ~DropTarget() = default;
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** out) override {
        if (riid == IID_IUnknown || riid == IID_IDropTarget) {
            *out = static_cast<IDropTarget*>(this);
            AddRef();
            return S_OK;
        }
        *out = nullptr;
        return E_NOINTERFACE;
    }
    ULONG STDMETHODCALLTYPE AddRef() override { return static_cast<ULONG>(InterlockedIncrement(&refs_)); }
    ULONG STDMETHODCALLTYPE Release() override {
        const LONG n = InterlockedDecrement(&refs_);
        if (n == 0) delete this;
        return static_cast<ULONG>(n);
    }
    HRESULT STDMETHODCALLTYPE DragEnter(IDataObject* data, DWORD, POINTL, DWORD* effect) override {
        accept_ = !dropped_files(data).empty();
        // The drop overlay is the library's; the Google services setup takes its files itself.
        if (accept_ && g_view != kGmsSetupView) set_view(kLibraryView);
        set_over(accept_);
        *effect = accept_ ? DROPEFFECT_COPY : DROPEFFECT_NONE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE DragOver(DWORD, POINTL, DWORD* effect) override {
        *effect = accept_ ? DROPEFFECT_COPY : DROPEFFECT_NONE;
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE DragLeave() override {
        set_over(false);
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE Drop(IDataObject* data, DWORD, POINTL, DWORD* effect) override {
        set_over(false);
        *effect = accept_ ? DROPEFFECT_COPY : DROPEFFECT_NONE;
        if (accept_) {
            for (const fs::path& p : dropped_files(data)) {
                if (g_view == kGmsSetupView) setup_offer(p);
                else install(p);
            }
        }
        return S_OK;
    }

private:
    static void set_over(bool over) {
        g_drag_over = over;
        InvalidateRect(g_main, nullptr, FALSE);
    }
    LONG refs_ = 1;
    bool accept_ = false;
};

DropTarget* g_drop_target;

// ---------------------------------------------------------------------------
// The library window

void apply_theme() {
    pick_theme();
    theme_window(g_main);
    theme_menus();
    if (g_edit_brush) DeleteObject(g_edit_brush);
    g_edit_brush = CreateSolidBrush(colorref(g_t.card));
    if (g_code_brush) DeleteObject(g_code_brush);
    g_code_brush = CreateSolidBrush(colorref(code_bg()));
    for (HWND w : {g_install_wnd, g_log_wnd}) {
        if (!w) continue;
        theme_window(w);
        InvalidateRect(w, nullptr, FALSE);
    }
    if (g_log_wnd) {  // the log's colours are set in its text: open it again
        const fs::path path = g_lv.path;
        DestroyWindow(g_log_wnd);
        show_logs(path);
    }
    InvalidateRect(g_main, nullptr, FALSE);
    InvalidateRect(g_search, nullptr, TRUE);
}

void command(int id);

// The tile's menu, under the app's icon and name: Open, Stop while it runs, App details, Remove.
// Everything else is on the details page.
void show_context_menu(const App& app, POINT pt, bool keyboard = false) {
    const bool running = run_active(app.name) || app_running(app.name);
    const bool pending = is_pending(app.name);
    PopupMenu m;
    m.app = &app;
    m.title = app.label;
    const std::vector<std::string> pkg = package_of(app.name);
    if (running) m.sub = L"Running";
    else if (pkg.size() > 2 && !pkg[2].empty()) m.sub = fmt(L"Version {0}", {utf8_to_wide(pkg[2])});
    else if (!pkg.empty()) m.sub = utf8_to_wide(pkg[0]);
    auto item = [&](int id, const wchar_t* text, const wchar_t* glyph, std::wstring key = L"") {
        MenuItem it;
        it.id = id;
        it.text = text;
        it.glyph = glyph;
        it.shortcut = std::move(key);
        it.disabled = pending && (id == kCmdOpen || id == kCmdRemove);
        it.destructive = id == kCmdRemove;
        m.items.push_back(it);
    };
    item(kCmdOpen, L"Open", kGlyphPlay, L"Enter");
    if (running) item(kCmdStop, L"Stop", kGlyphStop);
    item(kCmdInfo, L"App details", kGlyphInfo, L"Alt+Enter");
    m.items.push_back({});
    item(kCmdRemove, L"Remove…", kGlyphDelete);
    if (const int pick = run_menu(m, pt, LONG_MIN, keyboard)) command(pick);
}

void command(int id) {
    const App* sel = selected();
    if (!sel) return;
    const App a = *sel;
    switch (id) {
        case kCmdOpen: launch(a); break;
        case kCmdStop:
            if (!stop_run(a.name)) appops::stop_runtime_of(a.apk);
            break;
        case kCmdInfo: show_info(a); break;
        case kCmdRemove: remove_app(a, true); break;
    }
}

// Moves the selection by tiles (dx) and rows (dy) from the keyboard; `to` jumps to a tile instead.
void move_selection(int dx, int dy, int to = -1) {
    int i = to >= 0 ? to : tile_of(g_selected);
    if (to < 0 && i < 0) {
        for (size_t t = 0; t < g_tiles.size() && i < 0; ++t) {
            if (g_tiles[t].app >= 0) i = static_cast<int>(t);
        }
    } else if (to < 0) {
        i += dx + dy * g_l.cols;
        if (dy != 0 && i >= static_cast<int>(g_tiles.size())) i = static_cast<int>(g_tiles.size()) - 1;
    }
    if (i < 0 || i >= static_cast<int>(g_tiles.size()) || g_tiles[static_cast<size_t>(i)].app < 0) return;
    g_focus_visible = true;
    select_app(g_apps[static_cast<size_t>(g_tiles[static_cast<size_t>(i)].app)].name);
}

// What a tooltip says for a control that is only a glyph, or a tile whose name may be cut short.
std::wstring tooltip_text(const Hit& h) {
    switch (h.kind) {
        case kHitNav:
            if (!g_l.compact) return L"";
            return h.tile == kNavLibrary ? L"Library"
                      : h.tile == kNavStore ? L"Store"
                      : h.tile == kNavPlay  ? L"Google Play"
                                            : L"Settings";
        case kHitClear: return L"Clear the search";
        case kHitAccount:
            return g_account.empty() ? std::wstring(L"Google account: not signed in")
                                     : fmt(L"Google account: {0}", {g_account});
        case kHitRefresh: return L"Check F-Droid for new apps";
        case kHitTile: {
            const Tile& t = g_tiles[static_cast<size_t>(h.tile)];
            if (t.app < 0) return fmt(L"Installing {0}…", {job_label(t.job)});
            const App& a = g_apps[static_cast<size_t>(t.app)];
            return run_active(a.name) ? a.label + L"  ·  " + L"Running" : a.label;
        }
        default: return L"";
    }
}

// One tooltip for the window; its text is asked for (TTN_GETDISPINFO) from what the mouse is over.
void create_tooltip() {
    if (g_tip) DestroyWindow(g_tip);
    g_tip = CreateWindowExW(WS_EX_TOPMOST, TOOLTIPS_CLASSW, nullptr,
                            WS_POPUP | TTS_NOPREFIX | TTS_ALWAYSTIP, CW_USEDEFAULT, CW_USEDEFAULT, CW_USEDEFAULT,
                            CW_USEDEFAULT, g_main, nullptr, g_inst, nullptr);
    if (!g_tip) return;
    TOOLINFOW ti{sizeof(ti)};
    ti.uFlags = TTF_SUBCLASS | TTF_IDISHWND;
    ti.hwnd = g_main;
    ti.uId = reinterpret_cast<UINT_PTR>(g_main);
    ti.lpszText = LPSTR_TEXTCALLBACK;
    SendMessageW(g_tip, TTM_ADDTOOLW, 0, reinterpret_cast<LPARAM>(&ti));
    SendMessageW(g_tip, TTM_SETMAXTIPWIDTH, 0, ipx(320));
}

LRESULT CALLBACK search_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp, UINT_PTR, DWORD_PTR) {
    if (msg == WM_KEYDOWN && wp == VK_ESCAPE) {
        SetWindowTextW(hwnd, L"");
        SetFocus(g_main);
        return 0;
    }
    if (msg == WM_KEYDOWN && (wp == VK_DOWN || wp == VK_RETURN)) {
        SetFocus(g_main);
        if (g_view == kLibraryView && !selected()) move_selection(0, 0);
        return 0;
    }
    if (msg == WM_CHAR && (wp == VK_RETURN || wp == VK_ESCAPE)) return 0;  // no beep
    return DefSubclassProc(hwnd, msg, wp, lp);
}

// The search box, made again when the language changes so it takes the new direction.
void create_search() {
    wchar_t text[256] = {};
    if (g_search) {
        GetWindowTextW(g_search, text, 256);
        DestroyWindow(g_search);
    }
    g_search = CreateWindowExW(0, L"EDIT", text, WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL,
                               0, 0, 0, 0, g_main, reinterpret_cast<HMENU>(static_cast<INT_PTR>(kIdSearch)), g_inst,
                               nullptr);
    SendMessageW(g_search, WM_SETFONT, reinterpret_cast<WPARAM>(g_edit_font), FALSE);
    SendMessageW(g_search, EM_SETCUEBANNER, FALSE, reinterpret_cast<LPARAM>(L"Search F-Droid"));
    SetWindowSubclass(g_search, search_proc, 1, 0);
    if (g_view != kStoreView) ShowWindow(g_search, SW_HIDE);
}

bool grid_view(View v) { return v == kLibraryView || v == kStoreView; }

// Shows a view. The library and the store share the scrolling: each keeps its own place. Only the
// store has the search box; the library and a page (Settings, an app's details) hide it.
void set_view(View view) {
    if (view == g_view && grid_view(view)) return;
    if (grid_view(g_view)) {
        g_queries[g_view] = query(g_view);
        g_scrolls[g_view] = g_scroll;
    }
    // Leaving Google Play: the panel hides (compute_layout) and the keyboard comes back to the window.
    if (view != kPlayView && play_window() && GetFocus() == play_window()) SetFocus(g_main);
    g_view = view;
    g_hot = {};
    setup_watch(view == kGmsSetupView);
    if (grid_view(view)) {
        const float scroll = g_scrolls[view];
        SetWindowTextW(g_search, g_queries[view].c_str());  // its EN_CHANGE starts at the top; this view keeps its place
        g_scroll = scroll;
        if (view == kStoreView) {
            ShowWindow(g_search, SW_SHOW);
            InvalidateRect(g_search, nullptr, TRUE);
            if (g_store.fetch == StoreState::Fetch::kNotStarted) store_start(false);
        } else {
            if (GetFocus() == g_search) SetFocus(g_main);
            ShowWindow(g_search, SW_HIDE);
        }
    } else {
        if (GetFocus() == g_search) SetFocus(g_main);
        ShowWindow(g_search, SW_HIDE);
        g_page.scroll = 0;
        g_page.hot = g_page.focus = -1;
    }
    rebuild_tiles();
    update_timer();
}

void set_hot(const Hit& h) {
    if (h == g_hot) return;
    g_hot = h;
    if (g_tip) SendMessageW(g_tip, TTM_POP, 0, 0);  // the next hover asks for the new target's text
    InvalidateRect(g_main, nullptr, FALSE);
}

// The window's size is kept between runs, in DIPs so a DPI change does not grow it.
void save_window_size() {
    WINDOWPLACEMENT wp{sizeof(wp)};
    if (!GetWindowPlacement(g_main, &wp)) return;
    const int dpi = static_cast<int>(g_dpi);
    const RECT& r = wp.rcNormalPosition;
    set_setting(L"WindowWidth", static_cast<DWORD>(MulDiv(r.right - r.left, 96, dpi)));
    set_setting(L"WindowHeight", static_cast<DWORD>(MulDiv(r.bottom - r.top, 96, dpi)));
    set_setting(L"WindowMaximized", wp.showCmd == SW_SHOWMAXIMIZED ? 1 : 0);
}

LRESULT CALLBACK wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_CREATE: {
            g_main = hwnd;
            g_dpi = static_cast<float>(GetDpiForWindow(hwnd));
            make_fonts();
            create_search();
            create_tooltip();
            apply_theme();
            g_drop_target = new DropTarget;
            RegisterDragDrop(hwnd, g_drop_target);
            refresh();
            refresh_account();
            SetTimer(hwnd, kUpdateTimer, 3600 * 1000, nullptr);
            return 0;
        }
        case WM_SIZE:
            compute_layout();
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_DPICHANGED: {
            g_dpi = HIWORD(wp);
            make_fonts();
            SendMessageW(g_search, WM_SETFONT, reinterpret_cast<WPARAM>(g_edit_font), FALSE);
            g_icons.clear();
            g_store.icons.clear();
            g_brand.reset();
            const RECT* r = reinterpret_cast<const RECT*>(lp);
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top,
                         SWP_NOZORDER | SWP_NOACTIVATE);
            compute_layout();
            return 0;
        }
        case WM_SETTINGCHANGE:
            if (lp && wcscmp(reinterpret_cast<const wchar_t*>(lp), L"ImmersiveColorSet") == 0) apply_theme();
            break;
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: buffered_paint(hwnd, paint_main); return 0;
        case WM_CTLCOLOREDIT:
            SetTextColor(reinterpret_cast<HDC>(wp), colorref(g_t.text));
            SetBkColor(reinterpret_cast<HDC>(wp), colorref(g_t.card));
            return reinterpret_cast<LRESULT>(g_edit_brush);
        case WM_NOTIFY:
            if (reinterpret_cast<NMHDR*>(lp)->code == TTN_GETDISPINFOW) {
                static wchar_t text[256];
                const std::wstring tip = tooltip_text(g_hot);
                wcsncpy(text, tip.c_str(), 255);
                text[255] = 0;
                reinterpret_cast<NMTTDISPINFOW*>(lp)->lpszText = text;
                return 0;
            }
            break;
        case WM_COMMAND:
            if (LOWORD(wp) == kIdSearch) {
                if (HIWORD(wp) == EN_CHANGE) {
                    g_scroll = 0;
                    rebuild_tiles();
                } else if (HIWORD(wp) == EN_SETFOCUS || HIWORD(wp) == EN_KILLFOCUS) {
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
                return 0;
            }
            command(LOWORD(wp));
            return 0;
        case WM_MOUSEMOVE: {
            const float x = static_cast<float>(GET_X_LPARAM(lp)), y = static_cast<float>(GET_Y_LPARAM(lp));
            if (!g_tracking) {
                TRACKMOUSEEVENT tme{sizeof(tme), TME_LEAVE, hwnd, 0};
                g_tracking = TrackMouseEvent(&tme);
            }
            if (g_page.thumb_drag && page_view()) {
                const float room = g_page.view.Height - px(8) - g_page.thumb.Height;
                if (room > 0) g_page.scroll = g_page.thumb_scroll0 + (y - g_page.thumb_y0) * page_max_scroll() / room;
                page_scroll(0);
                return 0;
            }
            if (g_thumb_drag) {
                const float room = g_l.grid.Height - thumb_rect().Height;
                if (room > 0) g_scroll = g_thumb_scroll0 + (y - g_thumb_y0) * g_l.max_scroll / room;
                clamp_scroll();
                InvalidateRect(hwnd, nullptr, FALSE);
                return 0;
            }
            const Hit h = hit_test(x, y);
            if (page_view()) {
                const int ph = h.kind == kHitPage ? page_hit(x, y) : -1;
                if (ph != g_page.hot) {
                    g_page.hot = ph;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            set_hot(h);
            return 0;
        }
        case WM_MOUSELEAVE:
            g_tracking = false;
            if (!g_thumb_drag && !g_page.thumb_drag) {
                g_page.hot = -1;
                set_hot({});
            }
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT && reinterpret_cast<HWND>(wp) == hwnd) {
                const bool hand = g_hot.kind == kHitNav || g_hot.kind == kHitAccount || g_hot.kind == kHitAdd || g_hot.kind == kHitClear ||
                                  g_hot.kind == kHitRefresh || g_hot.kind == kHitCategory || g_hot.kind == kHitCard ||
                                  g_hot.kind == kHitCardButton || g_hot.kind == kHitRetry ||
                                  g_hot.kind == kHitInfoClose || g_hot.kind == kHitInfoLink ||
                                  g_hot.kind == kHitPlayOpen || g_hot.kind == kHitPlayLog ||
                                  (g_hot.kind == kHitPage && g_page.hot != -1 && g_page.hot != kPageThumb);
                SetCursor(LoadCursorW(nullptr, hand ? IDC_HAND : IDC_ARROW));
                return TRUE;
            }
            break;
        case WM_TIMER:
            if (wp == kAnimTimer) {
                InvalidateRect(hwnd, nullptr, FALSE);
                if (g_play.panel && g_play.state == PlayState::kStarting) InvalidateRect(g_play.panel, nullptr, FALSE);
            }
            if (wp == kInfoBarTimer) info(L"", kInfo);
            if (wp == kUpdateTimer) auto_update_check();
            if (wp == kSetupScanTimer) {
                KillTimer(hwnd, kSetupScanTimer);
                if (g_view == kGmsSetupView) {
                    setup_scan(false);
                    setup_next();
                }
            }
            return 0;
        case WM_LBUTTONDOWN: {
            SetFocus(hwnd);
            g_focus_visible = false;
            const float x = static_cast<float>(GET_X_LPARAM(lp)), y = static_cast<float>(GET_Y_LPARAM(lp));
            const Hit h = hit_test(x, y);
            switch (h.kind) {
                case kHitNav:
                    if (h.tile == kNavSettings) show_settings();
                    else if (h.tile == kNavPlay) open_google_play();
                    else set_view(h.tile == kNavStore ? kStoreView : kLibraryView);
                    break;
                case kHitAdd: add_from_dialog(); break;
                case kHitPlayOpen: open_google_play(); break;
                case kHitPlayLog: show_logs(run_log(g_play.name)); break;
                case kHitAccount: show_settings(); break;
                case kHitClear:
                    SetWindowTextW(g_search, L"");
                    SetFocus(g_search);
                    break;
                case kHitRefresh:
                case kHitRetry: store_start(true); break;
                case kHitCategory: choose_category(); break;
                case kHitInfoClose: info(L"", kInfo); break;
                case kHitInfoLink: {
                    const std::function<void()> act = g_status_act;
                    info(L"", kInfo);
                    if (act) act();
                    break;
                }
                case kHitPage: {
                    g_page.focus = -1;  // the mouse takes over: no focus ring
                    if (page_hit(x, y) == kPageThumb) {
                        g_page.thumb_drag = true;
                        g_page.thumb_y0 = y;
                        g_page.thumb_scroll0 = g_page.scroll;
                        SetCapture(hwnd);
                    }
                    InvalidateRect(hwnd, nullptr, FALSE);
                    break;
                }
                case kHitCardButton:
                case kHitCard: {
                    // a copy: opening the app or the page runs messages that may replace the catalog
                    const StoreApp a = g_store.apps[static_cast<size_t>(g_store.shown[static_cast<size_t>(h.tile)])];
                    if (h.kind == kHitCardButton) store_get(a);
                    else show_store_info(a);
                    break;
                }
                case kHitThumb:
                    g_thumb_drag = true;
                    g_thumb_y0 = y;
                    g_thumb_scroll0 = g_scroll;
                    SetCapture(hwnd);
                    break;
                case kHitTrack:
                    g_scroll += (y < thumb_rect().Y ? -1 : 1) * g_l.grid.Height * 0.9f;
                    clamp_scroll();
                    InvalidateRect(hwnd, nullptr, FALSE);
                    break;
                case kHitTile: {
                    const Tile& t = g_tiles[static_cast<size_t>(h.tile)];
                    if (t.app >= 0) select_app(g_apps[static_cast<size_t>(t.app)].name);
                    break;
                }
                case kHitNone:
                case kHitBack:
                    g_selected.clear();
                    InvalidateRect(hwnd, nullptr, FALSE);
                    break;
            }
            return 0;
        }
        case WM_LBUTTONUP: {
            const float x = static_cast<float>(GET_X_LPARAM(lp)), y = static_cast<float>(GET_Y_LPARAM(lp));
            if (g_page.thumb_drag) {
                g_page.thumb_drag = false;
                ReleaseCapture();
                InvalidateRect(hwnd, nullptr, FALSE);
                return 0;
            }
            if (g_thumb_drag) {
                g_thumb_drag = false;
                ReleaseCapture();
                set_hot(hit_test(x, y));
                return 0;
            }
            if (page_view() && hit_test(x, y).kind == kHitPage) page_click(page_hit(x, y));
            return 0;
        }
        case WM_XBUTTONUP:  // the mouse's Back button
            if (GET_XBUTTON_WPARAM(wp) == XBUTTON1 && page_view()) page_back();
            return TRUE;
        case WM_LBUTTONDBLCLK: {
            const Hit h = hit_test(static_cast<float>(GET_X_LPARAM(lp)), static_cast<float>(GET_Y_LPARAM(lp)));
            if (h.kind == kHitTile && g_tiles[static_cast<size_t>(h.tile)].app >= 0) {
                launch(g_apps[static_cast<size_t>(g_tiles[static_cast<size_t>(h.tile)].app)]);
            }
            return 0;
        }
        case WM_RBUTTONUP: {
            const Hit h = hit_test(static_cast<float>(GET_X_LPARAM(lp)), static_cast<float>(GET_Y_LPARAM(lp)));
            if (h.kind != kHitTile || g_tiles[static_cast<size_t>(h.tile)].app < 0) return 0;
            const App& app = g_apps[static_cast<size_t>(g_tiles[static_cast<size_t>(h.tile)].app)];
            select_app(app.name);
            POINT pt{GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            ClientToScreen(hwnd, &pt);
            show_context_menu(app, pt);
            return 0;
        }
        case WM_CONTEXTMENU:
            if (GET_X_LPARAM(lp) == -1 && g_view == kLibraryView && selected()) {  // the keyboard's menu key
                const RectF r = tile_rect(tile_of(g_selected));
                POINT pt{static_cast<LONG>(r.X + r.Width / 2), static_cast<LONG>(r.Y + r.Height / 2)};
                ClientToScreen(hwnd, &pt);
                show_context_menu(*selected(), pt, true);
                return 0;
            }
            break;
        case WM_MOUSEWHEEL:
            if (page_view()) {
                page_scroll(-GET_WHEEL_DELTA_WPARAM(wp) / 120.0f * px(60));
                return 0;
            }
            g_scroll -= GET_WHEEL_DELTA_WPARAM(wp) / 120.0f * px(60);
            clamp_scroll();
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_SYSKEYDOWN:
            if (wp == VK_RETURN && g_view == kLibraryView && selected()) {  // Alt+Enter, as Explorer's properties
                show_info(*selected());
                return 0;
            }
            if (wp == VK_LEFT && page_view()) {  // Alt+Left: back
                page_back();
                return 0;
            }
            break;
        case WM_KEYDOWN: {
            const bool ctrl = GetKeyState(VK_CONTROL) < 0;
            if (ctrl && (wp == '1' || wp == '2')) {
                set_view(wp == '2' ? kStoreView : kLibraryView);
                return 0;
            }
            if (ctrl && wp == VK_OEM_COMMA) {
                show_settings();
                return 0;
            }
            if (ctrl && wp == 'L') {
                show_logs({});
                return 0;
            }
            if (ctrl && wp == 'O') {
                add_from_dialog();
                return 0;
            }
            if (page_view()) {
                switch (wp) {
                    case VK_ESCAPE:
                    case VK_BACK: page_back(); return 0;
                    case VK_TAB: page_tab(GetKeyState(VK_SHIFT) < 0); return 0;
                    case VK_RETURN:
                    case VK_SPACE:
                        if (g_page.focus != -1) page_click(g_page.focus);
                        return 0;
                    case VK_UP: page_scroll(-px(48)); return 0;
                    case VK_DOWN: page_scroll(px(48)); return 0;
                    case VK_PRIOR: page_scroll(-g_page.view.Height * 0.9f); return 0;
                    case VK_NEXT: page_scroll(g_page.view.Height * 0.9f); return 0;
                    case VK_HOME: page_scroll(-g_page.scroll); return 0;
                    case VK_END: page_scroll(page_max_scroll()); return 0;
                }
                return 0;
            }
            if (ctrl && wp == 'F' && g_view == kStoreView) {
                SetFocus(g_search);
                return 0;
            }
            if (g_view == kStoreView) {
                switch (wp) {
                    case VK_UP:
                    case VK_DOWN:
                    case VK_PRIOR:
                    case VK_NEXT: {
                        const float page = wp == VK_PRIOR || wp == VK_NEXT ? g_l.grid.Height * 0.9f : px(60);
                        g_scroll += wp == VK_UP || wp == VK_PRIOR ? -page : page;
                        clamp_scroll();
                        InvalidateRect(hwnd, nullptr, FALSE);
                        return 0;
                    }
                    case VK_F5: store_start(true); return 0;
                }
                break;
            }
            switch (wp) {
                case VK_LEFT: move_selection(-1, 0); return 0;
                case VK_RIGHT: move_selection(1, 0); return 0;
                case VK_UP: move_selection(0, -1); return 0;
                case VK_DOWN: move_selection(0, 1); return 0;
                case VK_PRIOR:
                case VK_NEXT: {
                    const int rows = (std::max)(1, static_cast<int>(g_l.grid.Height / (g_l.tile_h + g_l.gap)));
                    move_selection(0, wp == VK_PRIOR ? -rows : rows);
                    return 0;
                }
                case VK_HOME: move_selection(0, 0, 0); return 0;
                case VK_END: move_selection(0, 0, static_cast<int>(g_tiles.size()) - 1); return 0;
                case VK_RETURN: if (const App* a = selected()) launch(*a); return 0;
                case VK_ESCAPE:
                    g_selected.clear();
                    InvalidateRect(hwnd, nullptr, FALSE);
                    return 0;
            }
            break;
        }
        case WM_CHAR:  // typing in the store searches it
            if (wp > 32 && GetKeyState(VK_CONTROL) >= 0 && g_view == kStoreView) {
                SetFocus(g_search);
                SendMessageW(g_search, WM_CHAR, wp, lp);
            }
            return 0;
        case kInstallStarted: {
            std::unique_ptr<std::wstring> name(reinterpret_cast<std::wstring*>(lp));
            for (Job& j : g_jobs) {
                if (j.name == *name && j.state == Job::kWaiting) {
                    j.state = Job::kInstalling;
                    j.start = GetTickCount64();
                    break;
                }
            }
            if (g_install_wnd) InvalidateRect(g_install_wnd, nullptr, FALSE);
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case kInstalled: {
            std::unique_ptr<std::wstring> name(reinterpret_cast<std::wstring*>(lp));
            const InstallEnd end = finish_install(*name);
            const InstallEnd::Kind how = end.kind();
            refresh();
            for (Job& j : g_jobs) {
                if (j.name != *name || (j.state != Job::kInstalling && j.state != Job::kWaiting)) continue;
                j.state = how == InstallEnd::kCancelled ? Job::kCancelled
                          : how == InstallEnd::kDone    ? Job::kDone
                                                        : Job::kFailed;
                j.end = GetTickCount64();
                if (j.state == Job::kFailed) {
                    j.reason = how == InstallEnd::kAppRunning
                                   ? fmt(L"Close {0} first, then install it again.", {job_label(*name)})
                                   : install_failure_reason(install_log(*name));
                }
                break;
            }
            if (g_install_wnd) {
                fit_install_window();
                InvalidateRect(g_install_wnd, nullptr, FALSE);
            }
            update_timer();
            if (how == InstallEnd::kDone) {
                select_app(*name);
                // A store install has no install window: the library says it.
                if (!g_install_wnd) info(fmt(L"{0} is installed.", {label_of(*name)}), kSuccess);
            } else if (how != InstallEnd::kCancelled && !g_install_wnd) {
                info(fmt(L"{0} could not be installed.", {job_label(*name)}), kError);
            }
            if (!any_pending()) {
                g_job_labels.clear();
                g_pending_looks.clear();
            }
            if (g_view == kGmsSetupView) rebuild_page();  // the app's row is ticked
            maybe_quit();
            return 0;
        }
        case kRunReady: {
            std::unique_ptr<std::wstring> name(reinterpret_cast<std::wstring*>(lp));
            if (*name == g_play.name && g_play.state == PlayState::kStarting) {
                play_ready();
            } else {
                bring_forward(*name);  // the app's window, over the starting window, before that one goes
                close_splash(*name);
            }
            return 0;
        }
        case kRunExited: {
            std::unique_ptr<std::wstring> name(reinterpret_cast<std::wstring*>(lp));
            const RunEnd end = finish_run(*name);
            update_timer();
            InvalidateRect(hwnd, nullptr, FALSE);
            const std::wstring label = label_of(*name);
            if (*name == g_play.name &&
                (g_play.state == PlayState::kStarting || g_play.state == PlayState::kShown)) {
                play_exited(end.kind() == RunEnd::kDidNotStart);
            } else {
                switch (end.kind()) {
                    case RunEnd::kQuiet: break;
                    case RunEnd::kStopped: close_splash(*name); break;
                    case RunEnd::kDidNotStart:
                        splash_failed(*name, fmt(L"{0} didn't start", {label}),
                                      failure_reason(run_log(*name), end.clean));
                        break;
                    case RunEnd::kFailed:
                        splash_failed(*name, fmt(L"{0} stopped with an error", {label}),
                                      failure_reason(run_log(*name), false));
                        break;
                }
            }
            maybe_quit();
            return 0;
        }
        case kRefresh:
            refresh();
            if (g_view == kGmsSetupView) rebuild_page();
            return 0;
        case kSetupChanged:
            // A download lands in several steps (a temporary name, then the real one): look once it settles.
            SetTimer(hwnd, kSetupScanTimer, 1000, nullptr);
            return 0;
        case kSetupChecked:
            setup_checked(std::unique_ptr<SetupCheck>(reinterpret_cast<SetupCheck*>(lp)));
            return 0;
        case kGmsDone: {
            std::unique_ptr<std::wstring> out(reinterpret_cast<std::wstring*>(lp));
            g_gms_busy = false;
            const int code = static_cast<int>(static_cast<unsigned>(wp));
            if (code == 0) {
                // "SIGNED_IN<tab>email" is the last line that matters.
                std::wstring email;
                if (const size_t at = out->find(L"SIGNED_IN\t"); at != std::wstring::npos) {
                    email = out->substr(at + 10, out->find_first_of(L"\r\n", at) - at - 10);
                }
                info(email.empty() ? L"Signed in to Google." : fmt(L"Signed in as {0}.", {email}), kSuccess);
            } else if (code == 2) {
                info(L"No account was added.", kInfo);
            }
            refresh_account();
            if (g_view == kSettingsView) rebuild_page();
            if (code != 0 && code != 2) {  // after the page is rebuilt: behind the dialog it no longer says "Signing in…"
                InvalidateRect(hwnd, nullptr, FALSE);
                UpdateWindow(hwnd);
                // The runtime's ERROR line carries the failure's code (a WebView2
                // HRESULT, the page's web error and HTTP status, Google's Error=):
                // shown as is, since a code is what a user can look up or report.
                std::wstring why;
                if (const size_t at = out->find(L"ERROR\t"); at != std::wstring::npos) {
                    why = out->substr(at + 6, out->find_first_of(L"\r\n", at) - at - 6);
                    if (why.size() > 200) why = why.substr(0, 200) + L"…";
                }
                const std::wstring body =
                    why.empty() ? L"Google did not answer. Check the internet connection, then try again."
                                : fmt(L"Google sign-in failed (error: {0}).", {why});
                error_with_log(L"Could not sign in to Google", body, gms_log());
            }
            return 0;
        }
        case kStoreCatalog:
            if (wp == kCatalogList) {
                std::unique_ptr<std::vector<StoreApp>> list(reinterpret_cast<std::vector<StoreApp>*>(lp));
                g_store.apps = std::move(*list);
                std::stable_sort(g_store.apps.begin(), g_store.apps.end(),
                                 [](const StoreApp& a, const StoreApp& b) { return a.updated > b.updated; });
                rebuild_tiles();
            } else {
                const bool failed = wp == kCatalogFailed;
                g_store.fetch = failed ? StoreState::Fetch::kFailed : StoreState::Fetch::kDone;
                if (failed && !g_store.apps.empty() && g_view == kStoreView) {
                    info(L"F-Droid could not be checked for new apps.", kError);
                }
                update_timer();
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case kStoreProgress:
            g_store.got_kb = wp;
            return 0;
        case kStoreIcon: {
            std::unique_ptr<std::string> pkg(reinterpret_cast<std::string*>(lp));
            g_store.icon_wait.erase(*pkg);
            if (wp == kIconFailed) g_store.icons[*pkg] = nullptr;  // the monogram, for this session
            if (g_view == kStoreView) InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case kStoreDownload: {
            std::unique_ptr<std::string> pkg(reinterpret_cast<std::string*>(lp));
            g_store.downloads[*pkg] = static_cast<int>(wp);
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case kStoreDownloaded: {
            std::unique_ptr<Download> d(reinterpret_cast<Download*>(lp));
            store_downloaded(*d, wp != 0);
            return 0;
        }
        case kUpdateChecked: {
            std::unique_ptr<UpdateCheck> c(reinterpret_cast<UpdateCheck*>(lp));
            update_checked(*c);
            return 0;
        }
        case kUpdateProgress: update_progress(static_cast<int>(wp)); return 0;
        case kUpdateDownloaded: update_downloaded(wp != 0); return 0;
        case WM_GETMINMAXINFO:
            reinterpret_cast<MINMAXINFO*>(lp)->ptMinTrackSize = {ipx(680), ipx(520)};
            return 0;
        case WM_COPYDATA: {
            const auto* cds = reinterpret_cast<const COPYDATASTRUCT*>(lp);
            if (cds->dwData != kCopyOpen || cds->cbData < sizeof(wchar_t)) return FALSE;
            const std::wstring text(static_cast<const wchar_t*>(cds->lpData), cds->cbData / sizeof(wchar_t) - 1);
            size_t at = text.find(L'\n');
            while (at != std::wstring::npos) {
                const size_t end = text.find(L'\n', at + 1);
                const fs::path f = text.substr(at + 1, end == std::wstring::npos ? std::wstring::npos : end - at - 1);
                if (is_app_file(f)) install(f);
                at = end;
            }
            if (!text.empty() && text[0] == L'L') show_library(SW_SHOWDEFAULT);
            return TRUE;
        }
        case WM_ACTIVATEAPP:  // another program (an app's store, the install helper) may have changed the library
            if (wp && IsWindowVisible(hwnd) && !any_pending() && library_stamp() != g_library_stamp) refresh();
            // An app may have added the account (its "Sign in with Google") or the user signed out.
            if (wp && google_account_present() != !g_account.empty()) refresh_account();
            break;
        case kGmsAccount: {
            std::unique_ptr<std::wstring> account(reinterpret_cast<std::wstring*>(lp));
            g_account = *account;
            g_account_photo_key.clear();  // a sign-in or sign-out may have changed the picture
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_ACTIVATE:  // back to the window: the keyboard goes to Google Play when it is the view
            if (LOWORD(wp) != WA_INACTIVE && g_view == kPlayView && play_window()) {
                focus_play();
                return 0;
            }
            break;
        case WM_CLOSE:
            save_window_size();
            // Google Play lives in this window: it closes with it.
            if (!g_play.name.empty() && (g_play.state == PlayState::kStarting || g_play.state == PlayState::kShown)) {
                stop_run(g_play.name);
            }
            // Work still under way (an install, a splash) keeps the process; the library only hides.
            if (g_install_wnd || any_pending() || !g_splashes.empty() || g_log_wnd || any_runs()) {
                ShowWindow(hwnd, SW_HIDE);
                maybe_quit();
                return 0;
            }
            break;
        case WM_DESTROY:
            RevokeDragDrop(hwnd);
            if (g_drop_target) g_drop_target->Release();
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// ---------------------------------------------------------------------------
// The installed-apps registry

std::string json_string(const std::string& s) {
    std::string out = "\"";
    for (const unsigned char c : s) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char buf[8];
            std::snprintf(buf, sizeof buf, "\\u%04x", c);
            out += buf;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out + "\"";
}

// The lines of library\<name>.package: package, versionCode, versionName.
std::vector<std::string> package_of(const std::wstring& name) {
    std::ifstream f(package_file(name), std::ios::binary);
    std::vector<std::string> lines;
    for (std::string line; std::getline(f, line);) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        lines.push_back(line);
    }
    return lines;
}

std::string iso_time(const fs::path& p) {
    WIN32_FILE_ATTRIBUTE_DATA a;
    SYSTEMTIME t{};
    if (!GetFileAttributesExW(p.c_str(), GetFileExInfoStandard, &a) || !FileTimeToSystemTime(&a.ftLastWriteTime, &t))
        return "";
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04u-%02u-%02uT%02u:%02u:%02uZ", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute,
                  t.wSecond);
    return buf;
}

// <data>\installed_apps.json, upstream's registry (launcher/InstallCore.swift
// RegistryStore, schema 2) as MacApkPackageManager.InstalledRegistry reads
// it: one row per library app whose package is known. Rewritten whole, to a
// temporary file then renamed, so a reader sees an old or a new file.
void write_registry() {
    std::string out = "{\"schema\":2,\"apps\":[";
    bool first = true;
    std::vector<std::string> seen;  // one row per package, as on a device
    for (const App& app : library()) {
        const std::vector<std::string> p = package_of(app.name);
        if (p.empty() || p[0].empty()) continue;
        if (std::find(seen.begin(), seen.end(), p[0]) != seen.end()) continue;
        seen.push_back(p[0]);
        const std::string when = iso_time(package_file(app.name));
        out += first ? "\n" : ",\n";
        first = false;
        out += "{\"package\":" + json_string(p[0]) + ",\"label\":" + json_string(wide_to_utf8(app.label)) +
               ",\"versionCode\":" + std::to_string(p.size() > 1 ? std::atoll(p[1].c_str()) : 0) +
               ",\"versionName\":" + json_string(p.size() > 2 ? p[2] : "") +
               ",\"apkPath\":" + json_string(wide_to_utf8(app.apk.wstring())) +
               ",\"bundlePath\":" + json_string(wide_to_utf8(app.apk.wstring())) +
               ",\"dirName\":" + json_string(wide_to_utf8(app.name)) + ",\"state\":\"installed\"" +
               ",\"installedAt\":" + json_string(when) + ",\"updatedAt\":" + json_string(when) + "}";
    }
    out += "\n]}\n";
    std::error_code ec;
    fs::create_directories(data_dir(), ec);
    const fs::path file = data_dir() / L"installed_apps.json";
    const fs::path tmp = data_dir() / L"installed_apps.json.tmp";
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        f << out;
        if (!f) return;
    }
    MoveFileExW(tmp.c_str(), file.c_str(), MOVEFILE_REPLACE_EXISTING);
}

// Apps installed before the library recorded packages get theirs read now,
// in the background, so the registry knows them too. (Only the name: the
// version comes with the app's next install.)
DWORD WINAPI backfill_thread(void*) {
    bool changed = false;
    std::error_code ec;
    for (const App& app : library()) {
        if (fs::exists(package_file(app.name), ec)) continue;
        HANDLE p = start_runtime(L"--apk \"" + app.apk.wstring() + L"\" --package-to \"" +
                                     package_file(app.name).wstring() + L"\"",
                                 logs_dir() / L"package-backfill.log");
        if (p && wait_exit(p) == 0) changed = true;
        else fs::remove(package_file(app.name), ec);
    }
    if (changed) {
        write_registry();
        if (g_main) PostMessageW(g_main, kRefresh, 0, 0);  // the store sees these packages as installed
    }
    return 0;
}

// ---------------------------------------------------------------------------
// The install helper

// The helper's output goes where its caller pointed it (a transcript file
// PackageInstallerBackend reads), in UTF-8.
void helper_say(const std::string& line) {
    HANDLE out = GetStdHandle(STD_OUTPUT_HANDLE);
    if (out == nullptr || out == INVALID_HANDLE_VALUE) return;
    const std::string s = line + "\n";
    DWORD n = 0;
    WriteFile(out, s.data(), static_cast<DWORD>(s.size()), &n, nullptr);
}

void helper_progress(double fraction, const char* stage) {
    char buf[64];
    std::snprintf(buf, sizeof buf, "[helper] progress %.2f %s", fraction, stage);
    helper_say(buf);
}

int helper_fail(int code, const char* stage, const std::string& message) {
    helper_say("{\"ok\":false,\"stage\":" + json_string(stage) + ",\"message\":" + json_string(message) + "}");
    return code;
}

// The library app that holds `pkg`, or null.
const App* find_package(const std::vector<App>& apps, const std::string& pkg) {
    for (const App& app : apps) {
        const std::vector<std::string> p = package_of(app.name);
        if (!p.empty() && p[0] == pkg) return &app;
    }
    return nullptr;
}

// Asks the user, as the Mac's helper does before it acts for an app: the launcher's dialog, in front of
// the app, Enter on Cancel. `app` heads it when the question is about one in the library. A no is
// exit 3, which the installer reports as STATUS_FAILURE_ABORTED.
bool helper_consent(const App* app, const std::wstring& question, const std::wstring& note, const std::wstring& action) {
    Prompt p;
    if (app) {
        p.app = app->label;
        p.icon = rounded_icon(app_dir(app->name) / L"icon.png", ipx(40));
    }
    p.caption = L"Win.apk";
    p.question = question;
    p.note = note;
    p.buttons = {{IDOK, action}, {IDCANCEL, L"Cancel"}};
    p.default_id = IDCANCEL;
    return run_dialog(p, nullptr) == IDOK;
}

void notify_library() {
    if (HWND w = FindWindowW(L"WinApkLibrary", nullptr)) PostMessageW(w, kRefresh, 0, 0);
}

namespace appops {
bool caller_is_play_store();
}

int helper_main(int argc, wchar_t** argv) {
    const std::wstring verb = argv[1];
    std::wstring apk, package, source, requestor;
    for (int i = 2; i < argc; ++i) {
        const std::wstring a = argv[i];
        auto value = [&](std::wstring* out) {
            if (i + 1 >= argc) return false;
            *out = argv[++i];
            return true;
        };
        bool ok = true;
        if (a == L"--apk") ok = value(&apk);
        else if (a == L"--package") ok = value(&package);
        else if (a == L"--source") ok = value(&source);
        else if (a == L"--requestor-label") ok = value(&requestor);
        else if (a == L"--json") {}
        else ok = false;
        if (!ok) return helper_fail(2, "args", "unknown argument " + wide_to_utf8(a));
    }
    if (source != L"android") return helper_fail(2, "args", "--source android is required");
    const std::wstring who = requestor.empty() ? L"An app" : requestor;
    const std::vector<App> apps = library();

    if (verb == L"uninstall") {
        const App* app = package.empty() ? nullptr : find_package(apps, wide_to_utf8(package));
        if (!app) return helper_fail(5, "lookup", "not installed: " + wide_to_utf8(package));
        if (!helper_consent(app, fmt(L"Remove {0}?", {app->label}), fmt(L"{0} asks to remove it.", {who}),
                            L"Remove"))
            return helper_fail(3, "consent", "declined");
        remove_app(*app, false);
        notify_library();
        helper_say("{\"ok\":true,\"package\":" + json_string(wide_to_utf8(package)) + ",\"dataRemoved\":\"false\"}");
        return 0;
    }

    std::error_code ec;
    if (apk.empty() || !fs::is_regular_file(apk, ec)) return helper_fail(2, "args", "no --apk file");
    if (!is_app_file(apk)) return helper_fail(6, "invalid-apk", "not an .apk/.apkm/.xapk/.apks");

    // Which app this is decides what it replaces, so it is read first.
    const fs::path pkg_out = data_dir() / L"staging" / (L"package-" + std::to_wstring(GetCurrentProcessId()) + L".txt");
    fs::create_directories(pkg_out.parent_path(), ec);
    HANDLE probe = start_runtime(L"--apk \"" + apk + L"\" --package-to \"" + pkg_out.wstring() + L"\"",
                                 logs_dir() / L"install-helper.log");
    std::string pkg;
    if (probe && wait_exit(probe) == 0) {
        std::ifstream f(pkg_out, std::ios::binary);
        std::getline(f, pkg);
    }
    fs::remove(pkg_out, ec);
    if (pkg.empty()) return helper_fail(6, "invalid-apk", "no package name in the app");
    helper_progress(0.05, "parsed");

    const App* existing = find_package(apps, pkg);
    const bool update = existing != nullptr;
    const std::wstring wpkg = utf8_to_wide(pkg);
    // The Play Store installs as a phone's system installer does, with no question: it holds INSTALL_PACKAGES
    // there, and the user already chose the app in it. Only the real one: the asking runtime runs the
    // library's com.android.vending and Google signed that APK (appops::caller_is_play_store).
    const bool agreed =
        appops::caller_is_play_store() ||
        (update ? helper_consent(existing, fmt(L"Update {0}?", {existing->label}),
                                 fmt(L"{0} asks to update it with the copy it has ({1}).", {who, wpkg}), L"Update")
                : helper_consent(nullptr, fmt(L"Install {0}?", {wpkg}), fmt(L"{0} asks to install it.", {who}),
                                 L"Install"));
    if (!agreed) return helper_fail(3, "consent", "declined");
    helper_progress(0.1, "consented");

    const std::wstring name = update ? existing->name : file_name_safe(wpkg);
    bool running = false;
    if (!install_into_library(apk, name, update ? existing->apk : fs::path(), &running)) {
        if (running) return helper_fail(5, "install", "the app is running; close it, then update it");
        return helper_fail(5, "install", "installing failed; the log is " +
                                             wide_to_utf8(install_log(name).wstring()));
    }
    helper_progress(1.0, "installed");
    notify_library();
    helper_say("{\"ok\":true,\"package\":" + json_string(pkg) + ",\"wasUpdate\":\"" + (update ? "true" : "false") +
               "\"}");
    return 0;
}

// ---------------------------------------------------------------------------
// Permissions: the app-ops store and the permission dialogs
//
// Android asks the user before an app may read shared storage or the location
// (the runtime permissions), or manage all files or media (the special-access
// switches in Settings). The runtime (SpecialAccessHandler) asks through this
// program, as upstream asks through macapk-installhelper
// (launcher/InstallHelperMain.swift RuntimePermission, SpecialAccess and
// app-details):
//
//   Win.apk.exe runtime-permission --source android --level <api> (--prompt <kind> | --auto)
//               --perm <p>... [--member <p>...] [--partial <p>...] --json
//   Win.apk.exe special-access --op <op> --source android --json
//   Win.apk.exe app-details --source android --level <api> [--group <NAME>=<p>,<p>...] [--op <op>...] --json
//
// The app asking is the parent process (winapk-runtime.exe, whose --apk names
// the library file), never an argument. The answer is the user's, in a task
// dialog over the app's window, and is signed into <data>\appops\app-ops.json
// with an ECDSA P-256 key whose private half is kept beside it under DPAPI;
// SpecialAccessStore believes only the entries its public key verifies. The
// payload, the store's layout and the state tokens are upstream's
// (InstallCore.swift AppOpsStore), keyed by package + installedAt, so a
// reinstall starts with no grant. Exit: 0 granted, 2 bad arguments, 3 denied,
// 4 no answer, 5 no identity or the store failed, 6 an --auto grant refused.
//
// The library's Get Info sheet shows the same grants and can change or forget
// them (by "panel", as upstream's control panel).

namespace appops {

const std::string kReadExternal = "android.permission.READ_EXTERNAL_STORAGE";
const std::string kWriteExternal = "android.permission.WRITE_EXTERNAL_STORAGE";
const std::string kMediaLocation = "android.permission.ACCESS_MEDIA_LOCATION";
const std::string kImages = "android.permission.READ_MEDIA_IMAGES";
const std::string kVideo = "android.permission.READ_MEDIA_VIDEO";
const std::string kAudio = "android.permission.READ_MEDIA_AUDIO";
const std::string kUserSelected = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED";
const std::string kFine = "android.permission.ACCESS_FINE_LOCATION";
const std::string kCoarse = "android.permission.ACCESS_COARSE_LOCATION";
const std::string kBackgroundLocation = "android.permission.ACCESS_BACKGROUND_LOCATION";
const std::string kBodySensors = "android.permission.BODY_SENSORS";
const std::string kBodySensorsBackground = "android.permission.BODY_SENSORS_BACKGROUND";
const std::string kAllFiles = "android:manage_external_storage";
const std::string kManageMedia = "android:manage_media";
const std::string kOverlay = "android:system_alert_window";
const std::string kInstallApps = "android:request_install_packages";
const char* const kPayloadTag = "macapk-appops/3";
const char* const kLegacyTag = "macapk-appops/2";

// Every other dangerous permission, with the group it is asked under (the
// prompt's name, RuntimePermissionGroups.promptName) and the API level that
// defines it; the call-log trio is "phone" below API 28. The two "all the
// time" permissions are groups of their own, asked after their foreground
// group. MUST agree with runtime/java/com/macapk/loader/RuntimePermissionGroups.
struct PermRow {
    const char* perm;
    const char* group;
    int since;
};
const PermRow kGroupRows[] = {
    {"android.permission.READ_CONTACTS", "contacts", 1},
    {"android.permission.WRITE_CONTACTS", "contacts", 1},
    {"android.permission.GET_ACCOUNTS", "contacts", 1},
    {"android.permission.READ_CALENDAR", "calendar", 1},
    {"android.permission.WRITE_CALENDAR", "calendar", 1},
    {"android.permission.SEND_SMS", "sms", 1},
    {"android.permission.RECEIVE_SMS", "sms", 1},
    {"android.permission.READ_SMS", "sms", 1},
    {"android.permission.RECEIVE_WAP_PUSH", "sms", 1},
    {"android.permission.RECEIVE_MMS", "sms", 1},
    {"android.permission.READ_CELL_BROADCASTS", "sms", 1},
    {"android.permission.RECORD_AUDIO", "microphone", 1},
    {"android.permission.ACTIVITY_RECOGNITION", "activity_recognition", 29},
    {"android.permission.CAMERA", "camera", 1},
    {"android.permission.READ_CALL_LOG", "call_log", 16},
    {"android.permission.WRITE_CALL_LOG", "call_log", 16},
    {"android.permission.PROCESS_OUTGOING_CALLS", "call_log", 1},
    {"android.permission.READ_PHONE_STATE", "phone", 1},
    {"android.permission.READ_PHONE_NUMBERS", "phone", 26},
    {"android.permission.CALL_PHONE", "phone", 1},
    {"android.permission.ADD_VOICEMAIL", "phone", 14},
    {"android.permission.USE_SIP", "phone", 9},
    {"android.permission.ANSWER_PHONE_CALLS", "phone", 26},
    {"android.permission.ACCEPT_HANDOVER", "phone", 28},
    {"android.permission.BODY_SENSORS", "sensors", 20},
    {"android.permission.BODY_SENSORS_BACKGROUND", "sensors_background", 33},
    {"android.permission.BLUETOOTH_ADVERTISE", "nearby_devices", 31},
    {"android.permission.BLUETOOTH_CONNECT", "nearby_devices", 31},
    {"android.permission.BLUETOOTH_SCAN", "nearby_devices", 31},
    {"android.permission.UWB_RANGING", "nearby_devices", 31},
    {"android.permission.NEARBY_WIFI_DEVICES", "nearby_devices", 33},
    {"android.permission.POST_NOTIFICATIONS", "notifications", 33},
    {"android.permission.ACCESS_BACKGROUND_LOCATION", "location_background", 29},
};

bool is_storage(const std::string& p) {
    return p == kReadExternal || p == kWriteExternal || p == kMediaLocation || p == kImages || p == kVideo ||
           p == kAudio || p == kUserSelected;
}
bool is_location(const std::string& p) { return p == kFine || p == kCoarse; }
const PermRow* group_row(const std::string& p) {
    for (const PermRow& r : kGroupRows)
        if (p == r.perm) return &r;
    return nullptr;
}
// The group `p` is asked under at `level`, or "" when the level does not define it.
std::string group_name(const std::string& p, int level) {
    const PermRow* r = group_row(p);
    if (!r || level < r->since) return "";
    if (std::string(r->group) == "call_log" && level < 28) return "phone";
    return r->group;
}
bool is_group_perm(const std::string& p) { return group_row(p) != nullptr; }
bool is_group_name(const std::string& g) {
    for (const PermRow& r : kGroupRows)
        if (g == r.group) return true;
    return false;
}
// Every runtime permission a dialog can answer.
bool is_runtime_perm(const std::string& p) { return is_storage(p) || is_location(p) || is_group_perm(p); }
// The foreground permissions an "all the time" one depends on.
std::vector<std::string> foreground_of(const std::string& background) {
    if (background == kBackgroundLocation) return {kFine, kCoarse};
    if (background == kBodySensorsBackground) return {kBodySensors};
    return {};
}
bool is_op(const std::string& op) {
    return op == kAllFiles || op == kManageMedia || op == kOverlay || op == kInstallApps;
}
// Settings screens for access this host cannot grant, or has nothing to
// restrict: the dialog informs and records nothing (SpecialAccessHandler's
// INFO_* ops).
bool is_info_op(const std::string& op) { return op.rfind("winapk:", 0) == 0; }
bool is_state(const std::string& s) {
    return s == "granted" || s == "granted_fixed" || s == "denied" || s == "denied_fixed";
}
bool is_granted(const std::string& s) { return s == "granted" || s == "granted_fixed"; }

fs::path dir() { return data_dir() / L"appops"; }
fs::path store_file() { return dir() / L"app-ops.json"; }
fs::path key_file() { return dir() / L"signing-key.p256"; }
fs::path pub_file() { return dir() / L"signing-key.pub"; }

// ---- bytes -------------------------------------------------------------------

using Bytes = std::vector<unsigned char>;

std::string base64(const Bytes& b) {
    static const char* abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for (size_t i = 0; i < b.size(); i += 3) {
        const unsigned v = (b[i] << 16) | ((i + 1 < b.size() ? b[i + 1] : 0) << 8) | (i + 2 < b.size() ? b[i + 2] : 0);
        out += abc[(v >> 18) & 63];
        out += abc[(v >> 12) & 63];
        out += i + 1 < b.size() ? abc[(v >> 6) & 63] : '=';
        out += i + 2 < b.size() ? abc[v & 63] : '=';
    }
    return out;
}

bool unbase64(const std::string& s, Bytes* out) {
    out->clear();
    unsigned v = 0;
    int bits = 0;
    for (const char c : s) {
        int d;
        if (c >= 'A' && c <= 'Z') d = c - 'A';
        else if (c >= 'a' && c <= 'z') d = c - 'a' + 26;
        else if (c >= '0' && c <= '9') d = c - '0' + 52;
        else if (c == '+') d = 62;
        else if (c == '/') d = 63;
        else if (c == '=' || c == '\n' || c == '\r' || c == ' ') continue;
        else return false;
        v = (v << 6) | static_cast<unsigned>(d);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out->push_back(static_cast<unsigned char>((v >> bits) & 0xFF));
        }
    }
    return true;
}

bool read_bytes(const fs::path& p, Bytes* out) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    out->assign(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
    return true;
}

// Written to a temporary file and renamed over the old one, so a reader sees
// the old file or the new one.
bool write_bytes(const fs::path& p, const void* data, size_t n) {
    const fs::path tmp = p.parent_path() / (L"." + p.filename().wstring() + L".tmp." +
                                            std::to_wstring(GetCurrentProcessId()));
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        f.write(static_cast<const char*>(data), static_cast<std::streamsize>(n));
        if (!f) return false;
    }
    if (MoveFileExW(tmp.c_str(), p.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) return true;
    std::error_code ec;
    fs::remove(tmp, ec);
    return false;
}

// ---- the key -------------------------------------------------------------------

// A P-256 key pair from BCrypt: the private half for signing, kept in
// signing-key.p256 as a BCRYPT_ECCPRIVATE_BLOB under DPAPI (this Windows user
// only); the public half beside it as base64 of an X.509 SubjectPublicKeyInfo,
// which is what SpecialAccessStore.loadPublicKey reads.
struct Key {
    BCRYPT_ALG_HANDLE alg = nullptr;
    BCRYPT_KEY_HANDLE key = nullptr;
    Key() { BCryptOpenAlgorithmProvider(&alg, BCRYPT_ECDSA_P256_ALGORITHM, nullptr, 0); }
    ~Key() {
        if (key) BCryptDestroyKey(key);
        if (alg) BCryptCloseAlgorithmProvider(alg, 0);
    }
    Key(const Key&) = delete;
    Key& operator=(const Key&) = delete;

    Bytes blob(const wchar_t* type) const {
        ULONG n = 0;
        if (!key || BCryptExportKey(key, nullptr, type, nullptr, 0, &n, 0) != 0) return {};
        Bytes b(n);
        if (BCryptExportKey(key, nullptr, type, b.data(), n, &n, 0) != 0) return {};
        b.resize(n);
        return b;
    }
    // The SubjectPublicKeyInfo: the fixed id-ecPublicKey / prime256v1 header,
    // then the uncompressed point 04 || X || Y.
    Bytes spki() const {
        const Bytes pub = blob(BCRYPT_ECCPUBLIC_BLOB);
        if (pub.size() != sizeof(BCRYPT_ECCKEY_BLOB) + 64) return {};
        Bytes out = {0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01, 0x06,
                     0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04};
        out.insert(out.end(), pub.begin() + sizeof(BCRYPT_ECCKEY_BLOB), pub.end());
        return out;
    }
};

Bytes sha256(const std::string& data) {
    Bytes h(32);
    BCRYPT_ALG_HANDLE a = nullptr;
    if (BCryptOpenAlgorithmProvider(&a, BCRYPT_SHA256_ALGORITHM, nullptr, 0) != 0) return {};
    const bool ok = BCryptHash(a, nullptr, 0, reinterpret_cast<PUCHAR>(const_cast<char*>(data.data())),
                               static_cast<ULONG>(data.size()), h.data(), 32) == 0;
    BCryptCloseAlgorithmProvider(a, 0);
    return ok ? h : Bytes{};
}

// BCrypt signs as r || s; Java's SHA256withECDSA wants the DER SEQUENCE of two
// INTEGERs, as CryptoKit's derRepresentation gives upstream.
Bytes der_of(const Bytes& rs) {
    auto integer = [](const unsigned char* p) {
        size_t i = 0;
        while (i < 31 && p[i] == 0) ++i;
        Bytes v(p + i, p + 32);
        if (v[0] & 0x80) v.insert(v.begin(), 0);
        v.insert(v.begin(), static_cast<unsigned char>(v.size()));
        v.insert(v.begin(), 0x02);
        return v;
    };
    Bytes r = integer(rs.data()), s = integer(rs.data() + 32);
    Bytes out = {0x30, static_cast<unsigned char>(r.size() + s.size())};
    out.insert(out.end(), r.begin(), r.end());
    out.insert(out.end(), s.begin(), s.end());
    return out;
}

bool rs_of(const Bytes& der, Bytes* rs) {
    rs->assign(64, 0);
    size_t i = 2;
    if (der.size() < 8 || der[0] != 0x30 || der[1] != der.size() - 2) return false;
    for (int part = 0; part < 2; ++part) {
        if (i + 2 > der.size() || der[i] != 0x02) return false;
        size_t n = der[i + 1];
        i += 2;
        if (i + n > der.size() || n == 0) return false;
        const unsigned char* p = der.data() + i;
        while (n > 32 && *p == 0) ++p, --n;
        if (n > 32) return false;
        std::copy(p, p + n, rs->begin() + part * 32 + (32 - n));
        i += der[i - 1];
    }
    return i == der.size();
}

std::string sign(const Key& k, const std::string& payload) {
    const Bytes h = sha256(payload);
    Bytes rs(64);
    ULONG n = 0;
    if (h.empty() || BCryptSignHash(k.key, nullptr, const_cast<PUCHAR>(h.data()), 32, rs.data(), 64, &n, 0) != 0 ||
        n != 64)
        return "";
    return base64(der_of(rs));
}

bool verify(const Key& k, const std::string& payload, const std::string& sig) {
    Bytes der, rs;
    if (!unbase64(sig, &der) || !rs_of(der, &rs)) return false;
    const Bytes h = sha256(payload);
    return !h.empty() && BCryptVerifySignature(k.key, nullptr, const_cast<PUCHAR>(h.data()), 32, rs.data(), 64, 0) == 0;
}

bool load_key(Key* k) {
    Bytes sealed;
    if (!k->alg || !read_bytes(key_file(), &sealed)) return false;
    DATA_BLOB in{static_cast<DWORD>(sealed.size()), sealed.data()}, out{};
    if (!CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &out)) return false;
    const bool ok = BCryptImportKeyPair(k->alg, nullptr, BCRYPT_ECCPRIVATE_BLOB, &k->key, out.pbData, out.cbData, 0) == 0;
    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return ok;
}

// The key, made on first use. A new key cannot verify what the old one signed,
// so the store starts over with it, as upstream's loadOrCreateKey does.
bool load_or_create_key(Key* k, std::string* why) {
    std::error_code ec;
    fs::create_directories(dir(), ec);
    if (!load_key(k)) {
        if (!k->alg || BCryptGenerateKeyPair(k->alg, &k->key, 256, 0) != 0 || BCryptFinalizeKeyPair(k->key, 0) != 0) {
            *why = "could not make a signing key";
            return false;
        }
        Bytes priv = k->blob(BCRYPT_ECCPRIVATE_BLOB);
        DATA_BLOB in{static_cast<DWORD>(priv.size()), priv.data()}, out{};
        const bool sealed = CryptProtectData(&in, L"Win.apk app-ops signing key", nullptr, nullptr, nullptr,
                                             CRYPTPROTECT_UI_FORBIDDEN, &out);
        SecureZeroMemory(priv.data(), priv.size());
        if (!sealed) {
            *why = "could not protect the signing key";
            return false;
        }
        const bool wrote = write_bytes(key_file(), out.pbData, out.cbData);
        LocalFree(out.pbData);
        if (!wrote) {
            *why = "could not write " + wide_to_utf8(key_file().wstring());
            return false;
        }
        if (fs::exists(store_file(), ec)) {
            const std::string empty = "{\n  \"apps\" : {\n\n  },\n  \"version\" : 2\n}\n";
            write_bytes(store_file(), empty.data(), empty.size());
        }
    }
    const std::string pub = base64(k->spki()) + "\n";
    Bytes have;
    if (!read_bytes(pub_file(), &have) || std::string(have.begin(), have.end()) != pub) {
        if (pub.size() < 8 || !write_bytes(pub_file(), pub.data(), pub.size())) {
            *why = "could not write " + wide_to_utf8(pub_file().wstring());
            return false;
        }
    }
    return true;
}

// ---- the store -----------------------------------------------------------------

struct Entry {
    std::string package, installed_at, updated_at, by, sig;
    std::map<std::string, std::string> ops, perms;
    bool has_perms = false;  // an S1 (/2) entry has no "perms" object at all
};
using Store = std::map<std::string, Entry>;  // package@installedAt -> entry

std::string key_of(const std::string& pkg, const std::string& installed_at) { return pkg + "@" + installed_at; }

// SpecialAccessStore.canonicalPayload, byte for byte.
bool payload(const char* tag, const Entry& e, std::string* out) {
    auto bad = [](const std::string& s) { return s.find('\n') != std::string::npos; };
    if (bad(e.package) || bad(e.installed_at) || bad(e.updated_at) || bad(e.by)) return false;
    std::string s = std::string(tag) + "\npackage=" + e.package + "\ninstalledAt=" + e.installed_at + "\n";
    for (const auto& [k, v] : e.ops) {
        if (bad(k) || bad(v)) return false;
        s += "op:" + k + "=" + v + "\n";
    }
    for (const auto& [k, v] : e.perms) {
        if (bad(k) || bad(v)) return false;
        s += "perm:" + k + "=" + v + "\n";
    }
    *out = s + "updatedAt=" + e.updated_at + "\nby=" + e.by + "\n";
    return true;
}

bool genuine(const Key& k, const Entry& e) {
    std::string p;
    if (e.sig.empty()) return false;
    if (payload(kPayloadTag, e, &p) && verify(k, p, e.sig)) return true;
    if (e.has_perms) return false;
    return payload(kLegacyTag, e, &p) && verify(k, p, e.sig);
}

Store read_store() {
    Store out;
    Bytes raw;
    if (!read_bytes(store_file(), &raw)) return out;
    const std::string text(raw.begin(), raw.end());
    Json j{text.data(), text.data() + text.size()};
    std::string version;
    Store apps;
    j.object([&](const std::string& k) {
        if (k == "version") return j.scalar(&version);
        if (k != "apps") return j.skip();
        j.object([&](const std::string& id) {
            Entry e;
            j.object([&](const std::string& f) {
                if (f == "package") j.str(&e.package);
                else if (f == "installedAt") j.str(&e.installed_at);
                else if (f == "updatedAt") j.str(&e.updated_at);
                else if (f == "by") j.str(&e.by);
                else if (f == "sig") j.str(&e.sig);
                else if (f == "ops" || f == "perms") {
                    auto& m = f == "ops" ? e.ops : e.perms;
                    if (f == "perms") e.has_perms = true;
                    j.object([&](const std::string& name) { j.str(&m[name]); });
                } else {
                    j.skip();
                }
            });
            apps[id] = e;
        });
    });
    if (j.ok && version == "2") out = std::move(apps);
    return out;
}

bool write_store(const Store& s) {
    auto map_json = [](const std::map<std::string, std::string>& m) {
        std::string o = "{";
        bool first = true;
        for (const auto& [k, v] : m) {
            o += (first ? "\n        " : ",\n        ") + json_string(k) + " : " + json_string(v);
            first = false;
        }
        return o + (first ? "}" : "\n      }");
    };
    std::string o = "{\n  \"apps\" : {";
    bool first = true;
    for (const auto& [id, e] : s) {
        o += (first ? "\n" : ",\n") + std::string("    ") + json_string(id) + " : {\n";
        first = false;
        o += "      \"by\" : " + json_string(e.by) + ",\n";
        o += "      \"installedAt\" : " + json_string(e.installed_at) + ",\n";
        o += "      \"ops\" : " + map_json(e.ops) + ",\n";
        o += "      \"package\" : " + json_string(e.package) + ",\n";
        if (e.has_perms) o += "      \"perms\" : " + map_json(e.perms) + ",\n";
        o += "      \"sig\" : " + json_string(e.sig) + ",\n";
        o += "      \"updatedAt\" : " + json_string(e.updated_at) + "\n    }";
    }
    o += "\n  },\n  \"version\" : 2\n}\n";
    return write_bytes(store_file(), o.data(), o.size());
}

// One writer at a time, across processes: a lock on app-ops.json.lock.
struct Lock {
    HANDLE h = INVALID_HANDLE_VALUE;
    bool held = false;
    explicit Lock(DWORD timeout_ms) {
        std::error_code ec;
        fs::create_directories(dir(), ec);
        h = CreateFileW((dir() / L"app-ops.json.lock").c_str(), GENERIC_READ | GENERIC_WRITE,
                        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                        FILE_ATTRIBUTE_NORMAL, nullptr);
        if (h == INVALID_HANDLE_VALUE) return;
        for (DWORD waited = 0;; waited += 50) {
            OVERLAPPED o{};
            if (LockFileEx(h, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &o)) {
                held = true;
                return;
            }
            if (waited >= timeout_ms) return;
            Sleep(50);
        }
    }
    ~Lock() {
        if (held) {
            OVERLAPPED o{};
            UnlockFileEx(h, 0, 1, 0, &o);
        }
        if (h != INVALID_HANDLE_VALUE) CloseHandle(h);
    }
};

std::string now_iso() {
    SYSTEMTIME t;
    GetSystemTime(&t);
    char buf[32];
    std::snprintf(buf, sizeof buf, "%04u-%02u-%02uT%02u:%02u:%02uZ", t.wYear, t.wMonth, t.wDay, t.wHour, t.wMinute,
                  t.wSecond);
    return buf;
}

// Who the grant is for: a row of installed_apps.json.
struct Identity {
    std::string package, installed_at, label, dir_name;
};

// The verified entry for `id`, or an empty one (nothing stored, or nothing genuine).
Entry stored(const Identity& id) {
    Key k;
    if (!load_key(&k)) return {};
    const Store s = read_store();
    const auto it = s.find(key_of(id.package, id.installed_at));
    if (it == s.end() || it->second.package != id.package || it->second.installed_at != id.installed_at ||
        !genuine(k, it->second))
        return {};
    return it->second;
}

std::map<std::string, std::string> permissions(const Identity& id) {
    std::map<std::string, std::string> out;
    for (const auto& [p, st] : stored(id).perms)
        if (is_runtime_perm(p) && is_state(st)) out[p] = st;
    return out;
}

// The stored mode of `op` ("allow" or "deny"), or "" when none is.
std::string mode(const Identity& id, const std::string& op) {
    const Entry e = stored(id);
    const auto it = e.ops.find(op);
    return it == e.ops.end() ? "" : it->second;
}

// Records `perms` and `ops` changes for `id`, keeping what a genuine entry
// already holds, and signs the result (upstream's setPermissions / setMode).
bool record(const Identity& id, const std::map<std::string, std::string>& perms,
            const std::map<std::string, std::string>& ops, const std::string& by, std::string* why) {
    for (const auto& [p, st] : perms) {
        if (!is_runtime_perm(p) || !is_state(st)) {
            *why = "refusing to sign " + p + "=" + st;
            return false;
        }
    }
    for (const auto& [op, m] : ops) {
        if (!is_op(op) || (m != "allow" && m != "deny")) {
            *why = "refusing to sign " + op + "=" + m;
            return false;
        }
    }
    Lock lock(10000);
    if (!lock.held) {
        *why = "could not lock the store";
        return false;
    }
    Key k;
    if (!load_or_create_key(&k, why)) return false;
    Store s = read_store();
    const std::string key = key_of(id.package, id.installed_at);
    Entry e;
    if (const auto it = s.find(key); it != s.end() && it->second.package == id.package &&
                                     it->second.installed_at == id.installed_at && genuine(k, it->second))
        e = it->second;
    e.package = id.package;
    e.installed_at = id.installed_at;
    for (const auto& [p, st] : perms) e.perms[p] = st;
    for (const auto& [op, m] : ops) e.ops[op] = m;
    e.has_perms = true;
    e.updated_at = now_iso();
    e.by = by;
    std::string p;
    if (!payload(kPayloadTag, e, &p) || (e.sig = sign(k, p)).empty()) {
        *why = "could not sign the entry for " + key;
        return false;
    }
    s[key] = e;
    if (!write_store(s)) {
        *why = "could not write " + wide_to_utf8(store_file().wstring());
        return false;
    }
    return true;
}

// Removes every grant of `pkg` (the app left the library: a reinstall starts
// with none, as AOSP clears app-ops on uninstall).
void forget(const std::string& pkg) {
    std::error_code ec;
    if (pkg.empty() || !fs::exists(store_file(), ec)) return;
    Lock lock(2000);
    if (!lock.held) return;
    Store s = read_store();
    bool changed = false;
    for (auto it = s.begin(); it != s.end();) {
        if (it->second.package == pkg) {
            it = s.erase(it);
            changed = true;
        } else {
            ++it;
        }
    }
    if (changed) write_store(s);
}

// The registry row of `pkg` (write_registry keeps one per package).
bool identity_of(const std::string& pkg, Identity* id) {
    Bytes raw;
    if (pkg.empty() || !read_bytes(data_dir() / L"installed_apps.json", &raw)) return false;
    const std::string text(raw.begin(), raw.end());
    Json j{text.data(), text.data() + text.size()};
    int found = 0;
    j.object([&](const std::string& k) {
        if (k != "apps") return j.skip();
        j.array([&] {
            Identity row;
            std::string state;  // Json::str appends; a row without one is installed
            j.object([&](const std::string& f) {
                if (f == "package") j.str(&row.package);
                else if (f == "installedAt") j.str(&row.installed_at);
                else if (f == "label") j.str(&row.label);
                else if (f == "dirName") j.str(&row.dir_name);
                else if (f == "state") j.str(&state);
                else j.skip();
            });
            if (row.package == pkg && (state.empty() || state == "installed") && !row.installed_at.empty()) {
                ++found;
                *id = row;
            }
        });
    });
    return j.ok && found == 1;
}

// ---- the caller ----------------------------------------------------------------

struct Caller {
    Identity id;
    std::wstring name;  // the library name, for the icon
    DWORD pid = 0;
};

std::wstring command_line_of(HANDLE process) {
    using Query = LONG(NTAPI*)(HANDLE, ULONG, PVOID, ULONG, PULONG);
    const auto query = reinterpret_cast<Query>(
        reinterpret_cast<void*>(GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "NtQueryInformationProcess")));
    if (!query) return L"";
    std::vector<unsigned char> buf(64 * 1024);
    ULONG n = 0;
    constexpr ULONG kProcessCommandLineInformation = 60;
    if (query(process, kProcessCommandLineInformation, buf.data(), static_cast<ULONG>(buf.size()), &n) != 0)
        return L"";
    const auto* s = reinterpret_cast<const UNICODE_STRING*>(buf.data());
    return s->Buffer ? std::wstring(s->Buffer, s->Length / sizeof(wchar_t)) : L"";
}

// The app's --apk, from a winapk-runtime.exe's command line; empty when it is
// not one.
fs::path apk_of_command_line(const std::wstring& cmd) {
    int argc = 0;
    wchar_t** argv = CommandLineToArgvW(cmd.c_str(), &argc);
    fs::path apk;
    for (int i = 1; argv && i + 1 < argc; ++i)
        if (wcscmp(argv[i], L"--apk") == 0) apk = argv[i + 1];
    if (argv) LocalFree(argv);
    return apk;
}

// Ends the runtime running `apk`, whoever started it (the library window, a
// shortcut's Win.apk.exe --run): Android kills a process whose runtime
// permission or all-files access was taken away, and the app comes back
// clean when opened again. True when one was found.
bool stop_runtime_of(const fs::path& apk) {
    bool stopped = false;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return false;
    PROCESSENTRY32W pe{sizeof(pe)};
    std::error_code ec;
    for (BOOL more = Process32FirstW(snap, &pe); more; more = Process32NextW(snap, &pe)) {
        if (_wcsicmp(pe.szExeFile, L"winapk-runtime.exe") != 0) continue;
        HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
        if (!p) continue;
        const fs::path theirs = apk_of_command_line(command_line_of(p));
        if (!theirs.empty() && fs::equivalent(theirs, apk, ec) && TerminateProcess(p, 1)) stopped = true;
        CloseHandle(p);
    }
    CloseHandle(snap);
    return stopped;
}

// Ends every runtime running the library app `name` (its APK is library\<name>.<ext>), whoever
// started it. True when one was found.
bool stop_library_app(const std::wstring& name) {
    bool stopped = false;
    HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snap == INVALID_HANDLE_VALUE) return false;
    PROCESSENTRY32W pe{sizeof(pe)};
    std::error_code ec;
    for (BOOL more = Process32FirstW(snap, &pe); more; more = Process32NextW(snap, &pe)) {
        if (_wcsicmp(pe.szExeFile, L"winapk-runtime.exe") != 0) continue;
        HANDLE p = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_TERMINATE, FALSE, pe.th32ProcessID);
        if (!p) continue;
        const fs::path theirs = apk_of_command_line(command_line_of(p));
        if (!theirs.empty() && _wcsicmp(theirs.stem().c_str(), name.c_str()) == 0 &&
            fs::equivalent(theirs.parent_path(), library_dir(), ec) && TerminateProcess(p, 1))
            stopped = true;
        CloseHandle(p);
    }
    CloseHandle(snap);
    return stopped;
}

// The app asking: our parent must be our bin\winapk-runtime.exe that started before
// us (not a reused process id), and its --apk a library app whose package has
// one registry row. Nothing the app passes in its arguments is believed.
bool resolve(Caller* c, std::string* why) {
    const DWORD self = GetCurrentProcessId();
    DWORD parent = 0;
    if (HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0); snap != INVALID_HANDLE_VALUE) {
        PROCESSENTRY32W pe{sizeof(pe)};
        for (BOOL more = Process32FirstW(snap, &pe); more; more = Process32NextW(snap, &pe))
            if (pe.th32ProcessID == self) parent = pe.th32ParentProcessID;
        CloseHandle(snap);
    }
    HANDLE p = parent ? OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, parent) : nullptr;
    if (!p) {
        *why = "the parent process cannot be opened";
        return false;
    }
    // The parent must be our own runtime by full path: any same-user program can be named
    // winapk-runtime.exe and pass the library's Play Store APK as its --apk.
    wchar_t image[MAX_PATH * 2];
    DWORD len = MAX_PATH * 2;
    std::error_code image_ec;
    const bool named = QueryFullProcessImageNameW(p, 0, image, &len) &&
                       fs::equivalent(fs::path(image, image + len), runtime_exe(), image_ec) && !image_ec;
    FILETIME pc{}, sc{}, x1, x2, x3;
    const bool older = GetProcessTimes(p, &pc, &x1, &x2, &x3) &&
                       GetProcessTimes(GetCurrentProcess(), &sc, &x1, &x2, &x3) && CompareFileTime(&pc, &sc) <= 0;
    const std::wstring cmd = named && older ? command_line_of(p) : L"";
    CloseHandle(p);
    if (!named || !older) {
        *why = "the parent process is not an app run by Win.apk";
        return false;
    }
    const fs::path apk = apk_of_command_line(cmd);
    std::error_code ec;
    for (const App& app : library()) {
        if (apk.empty() || !fs::equivalent(app.apk, apk, ec)) continue;
        const std::vector<std::string> pkg = package_of(app.name);
        if (pkg.empty() || !identity_of(pkg[0], &c->id)) {
            *why = "no single registry row for " + wide_to_utf8(app.name);
            return false;
        }
        if (c->id.label.empty()) c->id.label = wide_to_utf8(app.label);
        c->name = app.name;
        c->pid = parent;
        return true;
    }
    *why = "the parent runs no library app (" + wide_to_utf8(apk.wstring()) + ")";
    return false;
}

// Whether the app asking is the real Google Play Store: the parent runtime runs the library's
// com.android.vending (resolve), and winapk-runtime --verify-apk says Google signed that APK and it verifies.
bool caller_is_play_store() {
    Caller c;
    std::string why;
    if (!resolve(&c, &why) || c.id.package != "com.android.vending") return false;
    const App* app = find_app(c.name);
    if (!app) return false;
    std::wstring out;
    const fs::path log = logs_dir() / L"install-helper.log";
    if (run_runtime_captured(L"--verify-apk " + gms_arg(app->apk.wstring()), &out, log) != 0) return false;
    bool pkg = false, verified = false, google = false;
    for (size_t at = 0; at < out.size();) {
        size_t end = out.find(L'\n', at);
        if (end == std::wstring::npos) end = out.size();
        std::wstring line = out.substr(at, end - at);
        at = end + 1;
        if (!line.empty() && line.back() == L'\r') line.pop_back();
        if (line == L"PACKAGE\tcom.android.vending") pkg = true;
        else if (line == L"VERIFIED\t1") verified = true;
        else if (line == L"GOOGLE\t1") google = true;
    }
    return pkg && verified && google;
}

// ---- the dialog ----------------------------------------------------------------
//
// Every question an app asks is the launcher's dialog (Prompt, run_prompt): the
// app's icon and name, one question, at most a line saying what it means here,
// switches when there is something to pick, and the buttons. No answer in five
// minutes (upstream's alert timeout) changes nothing.

constexpr int kAnswerAllow = 100, kAnswerDeny = 101, kAnswerSelect = 102;

// A question with buttons (the first is the accent one), as the app `c`: its name and icon head it.
int ask(const Caller& c, const std::wstring& question, const std::wstring& note,
        const std::vector<std::pair<int, std::wstring>>& choices, int default_id,
        std::vector<Prompt::Switch>* switches = nullptr) {
    Prompt p;
    p.app = utf8_to_wide(c.id.label.empty() ? c.id.package : c.id.label);
    p.icon = rounded_icon(app_dir(c.name) / L"icon.png", ipx(40));
    p.question = question;
    p.note = note;
    p.buttons = choices;
    p.default_id = default_id;
    if (switches) p.switches = *switches;
    const int pressed = run_prompt(p, c.pid);
    if (switches) *switches = p.switches;
    return pressed;
}

std::wstring quoted(const Identity& id) { return utf8_to_wide(id.label.empty() ? id.package : id.label); }

// ---- runtime-permission ----------------------------------------------------------

// The group a permission's dialog belongs to (PermissionMapping.kt :94-150):
// the storage family's three, location, or one of kGroupRows'.
std::string group_of(const std::string& p, int level) {
    if (is_location(p)) return "location";
    if (is_group_perm(p)) return group_name(p, level);
    if (p == kReadExternal || p == kWriteExternal) return "storage";
    if (p == kMediaLocation) return level < 33 ? "storage" : "visual";
    if (p == kAudio) return "aural";
    return "visual";
}

// The permissions whose grant means the group is granted; MUST agree with
// SpecialAccessStore.PermModel.groupFullyGranted (the photo-picker prompt is
// off, as upstream's: USER_SELECTED never counts) and GroupModel's (any
// member of the group).
std::set<std::string> anchors(const std::string& g, int level) {
    if (g == "storage")
        return level < 33 ? std::set<std::string>{kReadExternal, kWriteExternal, kMediaLocation}
                          : std::set<std::string>{kReadExternal, kWriteExternal};
    if (g == "aural") return {kAudio};
    if (g == "location") return {kFine, kCoarse};
    if (is_group_name(g)) {
        std::set<std::string> out;
        for (const PermRow& r : kGroupRows)
            if (group_name(r.perm, level) == g) out.insert(r.perm);
        return out;
    }
    return {kImages, kVideo, kMediaLocation};
}

// GrantPermissionsViewModel's silent grant into a group that is already granted.
bool auto_grant_allowed(const std::vector<std::string>& perms, const std::map<std::string, std::string>& cur,
                        int level) {
    if (perms.empty()) return false;
    for (const std::string& p : perms) {
        bool any = false;
        for (const std::string& a : anchors(group_of(p, level), level)) {
            const auto it = cur.find(a);
            any = any || (it != cur.end() && is_granted(it->second));
        }
        if (!any) return false;
    }
    return true;
}

// What an answer writes, only the tokens that change (onPermissionGrantResult,
// GrantPermissionsViewModel.kt :678-770): Allow grants every permission asked
// for; Don't allow denies them, fixed when the group had been answered before;
// "Only approximate" grants COARSE and denies FINE.
std::map<std::string, std::string> changes(int answer, const std::vector<std::string>& perms,
                                           const std::vector<std::string>& members,
                                           const std::vector<std::string>& partial,
                                           const std::map<std::string, std::string>& cur) {
    bool user_set = false;
    for (const auto* list : {&members, &perms})
        for (const std::string& p : *list) user_set = user_set || cur.count(p);
    const std::string denied = user_set ? "denied_fixed" : "denied";
    std::map<std::string, std::string> want;
    for (const std::string& p : perms) {
        const bool part = std::find(partial.begin(), partial.end(), p) != partial.end();
        if (answer == kAnswerAllow) want[p] = "granted";
        else if (answer == kAnswerSelect) want[p] = part ? "granted" : denied;
        else if (answer == kAnswerDeny) want[p] = denied;
    }
    for (auto it = want.begin(); it != want.end();) {
        const auto c = cur.find(it->first);
        it = c != cur.end() && c->second == it->second ? want.erase(it) : std::next(it);
    }
    return want;
}

// The question, worded from the permissions (PermissionController strings.xml).
std::wstring question(const Identity& id, const std::string& prompt, const std::vector<std::string>& perms,
                      int level) {
    const std::wstring who = quoted(id);
    if (prompt == "location") return fmt(L"Allow {0} to access this PC's location?", {who});
    if (prompt == "location_background")
        return fmt(L"Allow {0} to access this PC's location all the time?", {who});
    if (prompt == "camera") return fmt(L"Allow {0} to take pictures and record video?", {who});
    if (prompt == "microphone") return fmt(L"Allow {0} to record audio?", {who});
    if (prompt == "contacts") return fmt(L"Allow {0} to access your contacts?", {who});
    if (prompt == "calendar") return fmt(L"Allow {0} to access your calendar?", {who});
    if (prompt == "phone") return fmt(L"Allow {0} to make and manage phone calls?", {who});
    if (prompt == "call_log") return fmt(L"Allow {0} to access your phone call logs?", {who});
    if (prompt == "sms") return fmt(L"Allow {0} to send and view SMS messages?", {who});
    if (prompt == "notifications") return fmt(L"Allow {0} to send you notifications?", {who});
    if (prompt == "nearby_devices")
        return fmt(L"Allow {0} to find, connect to, and determine the relative position of nearby devices?", {who});
    if (prompt == "activity_recognition") return fmt(L"Allow {0} to access your physical activity?", {who});
    if (prompt == "sensors") return fmt(L"Allow {0} to access sensor data about your vital signs?", {who});
    if (prompt == "sensors_background")
        return fmt(L"Allow {0} to access sensor data about your vital signs all the time?", {who});
    if (prompt == "supergroup_pre_q")
        return fmt(L"Allow {0} to access photos, videos, music, audio, and other files on this PC?", {who});
    std::set<std::string> groups;
    for (const std::string& p : perms) groups.insert(group_of(p, level));
    if (prompt == "supergroup_q_to_s" || groups.size() > 1)
        return fmt(L"Allow {0} to access photos, videos, music, and audio on this PC?", {who});
    const std::string g = groups.empty() ? "storage" : *groups.begin();
    if (g == "aural") return fmt(L"Allow {0} to access music and audio on this PC?", {who});
    if (g == "visual") return fmt(L"Allow {0} to access photos and videos on this PC?", {who});
    return fmt(L"Allow {0} to access photos, media, and files on this PC?", {who});
}

void emit(std::initializer_list<std::pair<const char*, std::string>> fields) {
    std::string o = "{";
    for (const auto& [k, v] : fields) o += (o.size() > 1 ? "," : "") + json_string(k) + ":" + json_string(v);
    helper_say(o + "}");
}

int usage(const std::string& message) {
    emit({{"status", "error"}, {"message", message}});
    return 2;
}

int refused(const std::string& why) {
    emit({{"status", "identity_refused"}, {"message", why}});
    return 5;
}

int runtime_permission(int level, const std::string& prompt, bool automatic, const std::vector<std::string>& perms,
                       const std::vector<std::string>& members, const std::vector<std::string>& partial) {
    if (level < 23) return usage("--level must be the presented API level");
    const bool location = prompt == "location";
    // An --auto request names no prompt: its family is its permissions'.
    const bool group = is_group_name(prompt) || (automatic && !perms.empty() && is_group_perm(perms[0]));
    auto known = [&](const std::string& p) {
        if (location) return is_location(p);
        if (group) return is_group_perm(p) && (automatic || group_name(p, level) == prompt);
        return is_storage(p);
    };
    if (perms.empty() || !std::all_of(perms.begin(), perms.end(), known) ||
        !std::all_of(members.begin(), members.end(), known))
        return usage(location ? "--prompt location takes only location runtime permissions"
                     : group  ? "--perm/--member must name runtime permissions of the --prompt group at --level"
                              : "--perm/--member must name storage runtime permissions");
    for (const std::string& p : partial)
        if (!location || p != kCoarse || std::find(perms.begin(), perms.end(), p) == perms.end())
            return usage("--partial is only ACCESS_COARSE_LOCATION with --prompt location, and one of the --perm values"
                         " (there is no photo picker: Allow means allow all)");
    static const std::set<std::string> offered = {"basic", "supergroup_pre_q", "supergroup_q_to_s", "location"};
    if (!automatic && !offered.count(prompt) && !is_group_name(prompt))
        return usage("--prompt must be basic, supergroup_pre_q, supergroup_q_to_s, location or a permission group"
                     " (or --auto)");
    Caller c;
    std::string why;
    if (!resolve(&c, &why)) return refused(why);
    const std::map<std::string, std::string> cur = permissions(c.id);
    // An "all the time" permission is never granted without its foreground
    // one (LocationGrantBehavior drops it from the request); the runtime
    // checks the same before asking, this is the helper's own reading.
    for (const std::string& p : perms) {
        const std::vector<std::string> fg = foreground_of(p);
        if (fg.empty()) continue;
        const bool any = std::any_of(fg.begin(), fg.end(), [&](const std::string& f) {
            const auto it = cur.find(f);
            return it != cur.end() && is_granted(it->second);
        });
        if (!any) {
            emit({{"status", "refused"}, {"message", p + " needs a granted foreground permission first"}});
            return 6;
        }
    }
    if (automatic) {
        if (!auto_grant_allowed(perms, cur, level)) {
            emit({{"status", "refused"}, {"message", "--auto needs a granted permission in the same group"}});
            return 6;
        }
        std::map<std::string, std::string> grant;
        for (const std::string& p : perms)
            if (!cur.count(p) || cur.at(p) != "granted") grant[p] = "granted";
        if (!grant.empty() && !record(c.id, grant, {}, "helper", &why)) {
            emit({{"status", "error"}, {"message", "could not record the grant: " + why}});
            return 5;
        }
        emit({{"status", "auto_granted"}, {"dirName", c.id.dir_name}});
        return 0;
    }
    // What stands behind the grant on this PC, where it is not what the user would guess.
    const wchar_t* note = nullptr;
    if (location || prompt == "location_background") note = L"Windows' location settings apply too.";
    else if (prompt == "camera") note = L"Windows' camera settings apply too.";
    else if (prompt == "microphone") note = L"Windows' microphone settings apply too.";
    else if (prompt == "notifications") note = L"They show as Windows notifications.";
    else if (prompt == "contacts" || prompt == "calendar" || prompt == "phone" || prompt == "call_log" ||
             prompt == "sms")
        note = L"This PC has none of these for the app to find.";
    else if (prompt == "activity_recognition" || prompt == "sensors" || prompt == "sensors_background")
        note = L"This PC has no such sensors.";
    else if (prompt == "nearby_devices") note = L"These are the Bluetooth devices near this PC.";
    // Location's "Only approximate" is the Precise switch, as Android 12 asks it.
    std::vector<Prompt::Switch> precise;
    if (!partial.empty()) precise.push_back({L"Precise location", true});
    int answer = ask(c, question(c.id, prompt, perms, level), note ? note : L"",
                     {{kAnswerAllow, L"Allow"}, {kAnswerDeny, L"Don't allow"}}, kAnswerDeny, &precise);
    if (answer == kAnswerAllow && !precise.empty() && !precise[0].on) answer = kAnswerSelect;
    if (answer == 0) {
        emit({{"status", "timeout"}, {"message", "the dialog was closed without an answer; nothing changed"}});
        return 4;
    }
    const auto want = changes(answer, perms, members, partial, cur);
    if (!want.empty() && !record(c.id, want, {}, "helper", &why)) {
        emit({{"status", "error"}, {"message", "could not record the answer: " + why}});
        return 5;
    }
    if (answer == kAnswerDeny) {
        emit({{"status", "denied"}, {"dirName", c.id.dir_name}});
        return 3;
    }
    emit({{"status", answer == kAnswerAllow ? "granted" : "partial"}, {"dirName", c.id.dir_name}});
    return 0;
}

// ---- special-access ----------------------------------------------------------------

// A Settings screen for access this host cannot grant (notification access,
// accessibility, usage access, Do Not Disturb) or does not restrict (battery
// optimization, exact alarms, system settings): the dialog says so, with one
// button, and nothing is recorded.
int special_access_info(const Caller& c, const std::string& op) {
    const std::wstring who = quoted(c.id);
    std::wstring main, body;
    if (op == "winapk:notification_listener") {
        main = L"Notification access";
        body = fmt(L"Win.apk can't give {0} notification access. Windows has no feed of other apps' notifications "
                   L"for an Android app to read.",
                   {who});
    } else if (op == "winapk:accessibility") {
        main = L"Accessibility";
        body = fmt(L"Win.apk can't run {0} as an accessibility service. There is no Android accessibility "
                   L"framework on Windows for it to observe or act through.",
                   {who});
    } else if (op == "winapk:usage_access") {
        main = L"Usage access";
        body = fmt(L"Win.apk can't give {0} usage access. Each Android app runs on its own here, so there is no "
                   L"usage history of other apps to read.",
                   {who});
    } else if (op == "winapk:notification_policy") {
        main = L"Do Not Disturb access";
        body = fmt(L"Win.apk has no Do Not Disturb mode for {0} to control.", {who});
    } else if (op == "winapk:battery") {
        main = L"Battery optimization";
        body = fmt(L"Win.apk never limits {0} in the background, so it already runs unrestricted.", {who});
    } else if (op == "winapk:exact_alarm") {
        main = L"Alarms and reminders";
        body = fmt(L"Win.apk lets {0} schedule exact alarms; there is nothing to turn on.", {who});
    } else if (op == "winapk:write_settings") {
        main = L"Modify system settings";
        body = fmt(L"There are no Android system settings for {0} to modify on Windows; it is treated as allowed.",
                   {who});
    } else {
        return usage("unknown --op " + op);
    }
    ask(c, main, body, {{kAnswerDeny, L"OK"}}, kAnswerDeny);
    emit({{"status", "info"}, {"op", op}, {"dirName", c.id.dir_name}});
    return 0;
}

// Settings' "All files access" / "Media management" / "Display over other
// apps" / "Install unknown apps" switch for the app. The default button keeps
// the current state, as upstream's alert's Return does.
int special_access(const std::string& op) {
    if (!is_op(op) && !is_info_op(op))
        return usage("--op must be " + kAllFiles + ", " + kManageMedia + ", " + kOverlay + " or " + kInstallApps);
    Caller c;
    std::string why;
    if (!resolve(&c, &why)) return refused(why);
    if (is_info_op(op)) return special_access_info(c, op);
    const std::string current = mode(c.id, op);
    const bool files = op == kAllFiles;
    std::wstring main, body;
    if (files) {
        main = fmt(L"Allow {0} to manage all files?", {quoted(c.id)});
        body = L"It can read, change and delete every file in your Downloads, Pictures, Documents, Music and "
                  L"Videos.";
    } else if (op == kManageMedia) {
        main = fmt(L"Allow {0} to manage media?", {quoted(c.id)});
        body = L"It can change or delete photos, videos and music that other apps made, without asking you.";
    } else if (op == kOverlay) {
        main = fmt(L"Allow {0} to display over other apps?", {quoted(c.id)});
        body = L"On Windows such windows stay inside the app's own window.";
    } else {
        main = fmt(L"Allow {0} to install other apps?", {quoted(c.id)});
        body = L"Win.apk still asks you before each app it installs.";
    }
    // Enter keeps what is set now.
    const int answer = ask(c, main, body, {{kAnswerAllow, L"Allow"}, {kAnswerDeny, L"Don't allow"}},
                           current == "allow" ? kAnswerAllow : kAnswerDeny);
    if (answer == 0) {
        emit({{"status", "timeout"},
              {"op", op},
              {"message", "the dialog was closed without an answer; nothing changed"}});
        return 4;
    }
    const std::string want = answer == kAnswerAllow ? "allow" : "deny";
    // "Don't allow" writes only to turn an allowed switch off.
    if (want != current && (want == "allow" || current == "allow") && !record(c.id, {}, {{op, want}}, "helper", &why)) {
        emit({{"status", "error"}, {"message", "could not record the answer: " + why}});
        return 5;
    }
    emit({{"status", want == "allow" ? "allowed" : "denied"}, {"op", op}, {"dirName", c.id.dir_name}});
    return want == "allow" ? 0 : 3;
}

// ---- app-details --------------------------------------------------------------------

// One line of the App info dialog: a permission group or a special-access op.
struct Item {
    std::wstring title;
    std::vector<std::string> perms;  // a group's permissions
    std::string op;                  // or an op
    bool on = false, was = false;
};

// A group's name in App info and Get Info (Settings' permission-group labels).
std::wstring group_title(const std::string& name, int level) {
    if (name == "READ_MEDIA_VISUAL") return L"Photos and videos";
    if (name == "READ_MEDIA_AURAL") return L"Music and audio";
    if (name == "STORAGE") return level < 33 ? L"Files and media" : L"Files";
    if (name == "storage-supergroup") return L"Photos, videos, music, and audio";
    if (name == "location") return L"Location";
    if (name == "location_background") return L"Location, all the time";
    if (name == "camera") return L"Camera";
    if (name == "microphone") return L"Microphone";
    if (name == "contacts") return L"Contacts";
    if (name == "calendar") return L"Calendar";
    if (name == "phone") return L"Phone";
    if (name == "call_log") return L"Call logs";
    if (name == "sms") return L"SMS";
    if (name == "notifications") return L"Notifications";
    if (name == "nearby_devices") return L"Nearby devices";
    if (name == "activity_recognition") return L"Physical activity";
    if (name == "sensors") return L"Body sensors";
    if (name == "sensors_background") return L"Body sensors, all the time";
    return utf8_to_wide(name);
}

std::wstring op_title(const std::string& op) {
    if (op == kAllFiles) return L"All files access";
    if (op == kManageMedia) return L"Media management";
    if (op == kOverlay) return L"Display over other apps";
    return L"Install unknown apps";
}

int app_details(int level, const std::vector<std::string>& groups, const std::vector<std::string>& ops) {
    if (level < 23) return usage("--level must be the presented API level");
    Caller c;
    std::string why;
    if (!resolve(&c, &why)) return refused(why);
    const auto cur = permissions(c.id);
    std::vector<Item> items;
    static const std::set<std::string> storage_names = {"STORAGE", "READ_MEDIA_VISUAL", "READ_MEDIA_AURAL",
                                                        "storage-supergroup"};
    for (const std::string& spec : groups) {
        const size_t eq = spec.find('=');
        Item it;
        if (eq == std::string::npos) return usage("bad --group " + spec);
        const std::string name = spec.substr(0, eq);
        for (size_t at = eq + 1; at <= spec.size();) {
            const size_t comma = std::min(spec.find(',', at), spec.size());
            if (comma > at) it.perms.push_back(spec.substr(at, comma - at));
            at = comma + 1;
        }
        // A row's permissions are its group's: the storage names take storage
        // permissions, "location" the two foreground ones, a group of
        // kGroupRows its members at this level.
        auto belongs = [&](const std::string& p) {
            if (storage_names.count(name)) return is_storage(p);
            if (name == "location") return is_location(p);
            return is_group_name(name) && group_name(p, level) == name;
        };
        if (it.perms.empty() || !std::all_of(it.perms.begin(), it.perms.end(), belongs))
            return usage("bad --group " + spec);
        std::set<std::string> anchor;
        for (const std::string& p : it.perms)
            for (const std::string& a : anchors(group_of(p, level), level)) anchor.insert(a);
        for (const std::string& p : it.perms)
            it.on = it.on || (anchor.count(p) && cur.count(p) && is_granted(cur.at(p)));
        it.title = group_title(name, level);
        it.was = it.on;
        items.push_back(it);
    }
    for (const std::string& op : ops) {
        if (!is_op(op)) return usage("bad --op " + op);
        Item it;
        it.op = op;
        it.title = op_title(op);
        it.on = it.was = mode(c.id, op) == "allow";
        items.push_back(it);
    }
    // One switch per item, and Done / Cancel: what changed is written on Done.
    Prompt pr;
    pr.app = utf8_to_wide(c.id.label.empty() ? c.id.package : c.id.label);
    pr.icon = rounded_icon(app_dir(c.name) / L"icon.png", ipx(40));
    pr.question = fmt(L"{0}'s permissions", {pr.app});
    if (items.empty()) {
        pr.note = L"This app has no permissions to set.";
        pr.buttons = {{IDOK, L"OK"}};
    } else {
        pr.note = fmt(L"Turning a permission off closes {0}.", {pr.app});
        for (const Item& it : items) pr.switches.push_back({it.title, it.on});
        pr.buttons = {{IDOK, L"Done"}, {IDCANCEL, L"Cancel"}};
        pr.footer = true;
    }
    pr.default_id = IDOK;
    const int pressed = run_prompt(pr, c.pid);
    for (size_t i = 0; i < items.size() && i < pr.switches.size(); ++i) items[i].on = pr.switches[i].on;
    if (pressed != IDOK || items.empty()) {
        emit({{"status", pressed == IDOK ? "done" : "cancelled"}, {"changed", "no"}});
        return 0;
    }
    std::map<std::string, std::string> perms, opm;
    for (const Item& it : items) {
        if (it.on == it.was) continue;
        if (!it.op.empty()) opm[it.op] = it.on ? "allow" : "deny";
        for (const std::string& p : it.perms) {
            const std::string want = it.on ? "granted" : "denied";
            if (!cur.count(p) || cur.at(p) != want) perms[p] = want;
        }
    }
    if ((!perms.empty() || !opm.empty()) && !record(c.id, perms, opm, "helper", &why)) {
        emit({{"status", "error"}, {"message", "could not record the change: " + why}});
        return 5;
    }
    emit({{"status", "done"}, {"changed", perms.empty() && opm.empty() ? "no" : "yes"}});
    return 0;
}

// ---- the library's side --------------------------------------------------------------

// The grants Get Info shows for an app: each stored permission group and op.
struct Grant {
    std::wstring title;
    std::vector<std::string> perms;
    std::string op;
    bool on = false;
};

std::vector<Grant> grants_of(const Identity& id) {
    const Entry e = stored(id);
    std::vector<Grant> out;
    struct G {
        const wchar_t* title;
        std::vector<std::string> perms;
    };
    std::vector<G> table = {{L"Files and media", {kReadExternal, kWriteExternal}},
                            {L"Photos and videos", {kImages, kVideo, kMediaLocation, kUserSelected}},
                            {L"Music and audio", {kAudio}},
                            {L"Location", {kFine, kCoarse}}};
    // Then every group of kGroupRows, in the table's order of first mention.
    for (const PermRow& r : kGroupRows) {
        G* g = nullptr;
        for (G& have : table)
            if (have.perms.size() && group_row(have.perms[0]) && std::string(group_row(have.perms[0])->group) == r.group)
                g = &have;
        if (!g) {
            table.push_back({L"", {}});
            g = &table.back();
        }
        g->perms.push_back(r.perm);
    }
    for (const G& g : table) {
        Grant gr;
        gr.title = g.title[0] ? g.title : group_title(group_row(g.perms[0])->group, 10000);
        for (const std::string& p : g.perms) {
            const auto it = e.perms.find(p);
            if (it == e.perms.end()) continue;
            gr.perms.push_back(p);
            gr.on = gr.on || is_granted(it->second);
        }
        if (!gr.perms.empty()) out.push_back(gr);
    }
    for (const std::string& op : {kAllFiles, kManageMedia, kOverlay, kInstallApps}) {
        const auto it = e.ops.find(op);
        if (it != e.ops.end()) out.push_back({op_title(op), {}, op, it->second == "allow"});
    }
    return out;
}

// Get Info took a grant away: Android ends a process whose runtime permission
// or all-files access was revoked (killUid, killAppForOpChange), and so does
// this, whoever started the app. Media management, overlays and installing
// have no kill rule on a device.
void revoked(const App& a, const std::string& op) {
    if (op == kManageMedia || op == kOverlay || op == kInstallApps) return;
    if (!stop_runtime_of(a.apk)) return;
    expect_run_end(a.name);  // started from here: the InfoBar says why it ended, no error splash
    info(fmt(L"{0} was stopped, as Android does when a permission is revoked.", {a.label}), kInfo);
}

}  // namespace appops

void appops_forget(const std::string& pkg) { appops::forget(pkg); }

// The Get Info sheet's permissions: each grant as a switch, and a reset that
// forgets them all, so the app asks again.
std::vector<Row> permission_rows(const App& a) {
    std::vector<Row> rows;
    const std::vector<std::string> pkg = package_of(a.name);
    appops::Identity id;
    if (pkg.empty() || !appops::identity_of(pkg[0], &id)) return rows;
    for (const appops::Grant& g : appops::grants_of(id)) {
        Row r;
        r.kind = Row::kToggle;
        r.title = g.title;
        r.on = g.on;
        r.act = [a, id, g](int on) {
            std::map<std::string, std::string> perms, ops;
            for (const std::string& p : g.perms) perms[p] = on ? "granted" : "denied";
            if (!g.op.empty()) ops[g.op] = on ? "allow" : "deny";
            std::string why;
            if (!appops::record(id, perms, ops, "panel", &why))
                info(fmt(L"The permission could not be changed: {0}", {utf8_to_wide(why)}), kError);
            else if (!on && g.on) appops::revoked(a, g.op);
        };
        rows.push_back(r);
    }
    if (!rows.empty()) {
        bool any_on = false;
        for (const Row& r : rows) any_on = any_on || r.on;
        rows.push_back(button_row(L"Reset permissions", L"The app asks again the next time it needs them.",
                                  L"Reset", [a, pkg, any_on] {
                                      appops::forget(pkg[0]);
                                      info(fmt(L"{0} will ask for its permissions again.", {a.label}), kSuccess);
                                      if (any_on) appops::revoked(a, "");
                                  }));
    }
    return rows;
}

int permission_main(int argc, wchar_t** argv) {
    const std::wstring verb = argv[1];
    std::string source, prompt;
    std::vector<std::string> perms, members, partial, groups, ops;
    int level = 0;
    bool automatic = false;
    for (int i = 2; i < argc; ++i) {
        const std::wstring a = argv[i];
        const bool has = i + 1 < argc;
        auto next = [&] { return wide_to_utf8(argv[++i]); };
        if (a == L"--source" && has) source = next();
        else if (a == L"--prompt" && has) prompt = next();
        else if (a == L"--perm" && has) perms.push_back(next());
        else if (a == L"--member" && has) members.push_back(next());
        else if (a == L"--partial" && has) partial.push_back(next());
        else if (a == L"--group" && has) groups.push_back(next());
        else if (a == L"--op" && has) ops.push_back(next());
        else if (a == L"--level" && has) level = std::atoi(next().c_str());
        else if (a == L"--auto") automatic = true;
        else if (a == L"--json") {}
        else return appops::usage("unknown argument " + wide_to_utf8(a));
    }
    // Only an app asks (the runtime passes it as a constant). No flag skips
    // the user's answer.
    if (source != "android")
        return appops::usage(wide_to_utf8(verb) + " is only asked for by an app (--source android)");
    if (verb == L"runtime-permission")
        return appops::runtime_permission(level, prompt, automatic, perms, members, partial);
    if (verb == L"special-access") {
        if (ops.size() != 1) return appops::usage("--op takes one op");
        return appops::special_access(ops[0]);
    }
    return appops::app_details(level, groups, ops);
}

// One Win.apk.exe serves the library, the install window and the splashes: another start hands
// its files (or its wish to see the library) to it in a WM_COPYDATA, as one string of paths
// separated by '\n', led by "L" for "show the library" or "F" for files only.

bool forward_to_running(bool show_library, const std::vector<fs::path>& files) {
    // Explorer starts one process per selected file, all at once: the first takes the mutex and makes the
    // window, the others wait for that window to hand their file over.
    HANDLE mutex = CreateMutexW(nullptr, FALSE, L"Local\\WinApk-launcher");
    const bool first = mutex && GetLastError() != ERROR_ALREADY_EXISTS;
    HWND other = FindWindowW(L"WinApkLibrary", nullptr);
    for (int i = 0; !other && !first && i < 200; ++i) {  // up to 10 s
        Sleep(50);
        other = FindWindowW(L"WinApkLibrary", nullptr);
    }
    if (!other) return false;  // keep the mutex (leaked on purpose) for as long as this process serves
    std::wstring text = show_library ? L"L" : L"F";
    for (const fs::path& f : files) text += L"\n" + fs::absolute(f).wstring();
    DWORD pid = 0;
    GetWindowThreadProcessId(other, &pid);
    AllowSetForegroundWindow(pid);
    COPYDATASTRUCT cds{kCopyOpen, static_cast<DWORD>((text.size() + 1) * sizeof(wchar_t)), &text[0]};
    DWORD_PTR result = 0;
    const bool sent = SendMessageTimeoutW(other, WM_COPYDATA, 0, reinterpret_cast<LPARAM>(&cds), SMTO_ABORTIFHUNG,
                                          10000, &result) && result;
    if (mutex) CloseHandle(mutex);
    return sent;
}

// The library window as it was last left: its size and whether it was maximized.
void show_library(int show) {
    if (IsWindowVisible(g_main)) {
        if (IsIconic(g_main)) ShowWindow(g_main, SW_RESTORE);
    } else {
        ShowWindow(g_main, show == SW_SHOWDEFAULT && setting(L"WindowMaximized", 0) ? SW_SHOWMAXIMIZED
                           : show == SW_SHOWDEFAULT                               ? SW_SHOWNORMAL
                                                                                  : show);
    }
    SetForegroundWindow(g_main);
    auto_update_check();
}

// When the library is not shown, the process lives only for what it is doing: an install window,
// a splash, an app it started (to report how it ended), the log window.
void maybe_quit() {
    if (!g_main || IsWindowVisible(g_main)) return;
    if (g_install_wnd || g_log_wnd || !g_splashes.empty() || any_runs() || any_pending()) return;
    DestroyWindow(g_main);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR, int show) {
    g_inst = inst;
    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    OleInitialize(nullptr);
    int argc = 0;
    wchar_t** argv = CommandLineToArgvW(GetCommandLineW(), &argc);

    // The install helper, for apps that install apps.
    if (argc >= 2 && (wcscmp(argv[1], L"install") == 0 || wcscmp(argv[1], L"uninstall") == 0)) {
        ULONG_PTR gdiplus = 0;  // its question is the launcher's dialog
        Gdiplus::GdiplusStartupInput gsi;
        Gdiplus::GdiplusStartup(&gdiplus, &gsi, nullptr);
        pick_theme();
        g_dpi = static_cast<float>(GetDpiForSystem());
        make_fonts();
        const int code = helper_main(argc, argv);
        LocalFree(argv);
        g_f = Fonts{};
        Gdiplus::GdiplusShutdown(gdiplus);
        OleUninitialize();
        return code;
    }

    // What this start is for: a shortcut's app (--run), files to install, or the library.
    fs::path run_apk;
    std::vector<fs::path> files;
    const bool permission = argc >= 2 && (wcscmp(argv[1], L"runtime-permission") == 0 ||
                                          wcscmp(argv[1], L"special-access") == 0 ||
                                          wcscmp(argv[1], L"app-details") == 0);
    if (argc >= 3 && wcscmp(argv[1], L"--run") == 0) {
        run_apk = argv[2];
    } else if (!permission) {
        for (int i = 1; i < argc; ++i)
            if (is_app_file(argv[i])) files.emplace_back(argv[i]);
        if (forward_to_running(files.empty(), files)) {
            LocalFree(argv);
            OleUninitialize();
            return 0;
        }
    }

    INITCOMMONCONTROLSEX icc{sizeof(icc), ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&icc);
    ULONG_PTR gdiplus = 0;
    Gdiplus::GdiplusStartupInput gsi;
    Gdiplus::GdiplusStartup(&gdiplus, &gsi, nullptr);
    pick_theme();

    // The permission dialogs, for apps that ask for permissions.
    if (permission) {
        g_dpi = static_cast<float>(GetDpiForSystem());
        make_fonts();
        const int code = permission_main(argc, argv);
        LocalFree(argv);
        g_f = Fonts{};
        Gdiplus::GdiplusShutdown(gdiplus);
        OleUninitialize();
        return code;
    }

    // Before anything reads the library: an update cut off the last time (a crash, the power) is undone.
    recover_interrupted_installs();
    if (run_apk.empty()) {
        integrate_with_windows();
        refresh_app_shortcuts();
        write_registry();  // apps installed before the registry existed, or by an older launcher
        if (HANDLE t = CreateThread(nullptr, 0, backfill_thread, nullptr, 0, nullptr)) CloseHandle(t);
    }
    theme_menus();

    WNDCLASSEXW wc{sizeof(wc)};
    wc.style = CS_DBLCLKS;
    wc.lpfnWndProc = wnd_proc;
    wc.hInstance = inst;
    wc.hIcon = LoadIconW(inst, MAKEINTRESOURCEW(1));
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = L"WinApkLibrary";
    RegisterClassExW(&wc);
    WNDCLASSEXW sc{sizeof(sc)};
    sc.hInstance = inst;
    sc.hIcon = wc.hIcon;
    sc.hCursor = wc.hCursor;
    sc.lpfnWndProc = install_proc;
    sc.lpszClassName = L"WinApkInstall";
    RegisterClassExW(&sc);
    sc.lpfnWndProc = log_proc;
    sc.lpszClassName = L"WinApkLogs";
    RegisterClassExW(&sc);
    sc.lpfnWndProc = splash_proc;
    sc.lpszClassName = L"WinApkSplash";
    RegisterClassExW(&sc);
    // The size from last time, within the screen; 980 x 720 the first time. The window is made
    // hidden: an install from Explorer or a shortcut's start shows it only when asked for.
    const int dpi = static_cast<int>(GetDpiForSystem());
    const int w = (std::min)(GetSystemMetrics(SM_CXSCREEN),
                             MulDiv((std::max)(680, static_cast<int>(setting(L"WindowWidth", 980))), dpi, 96));
    const int h = (std::min)(GetSystemMetrics(SM_CYSCREEN),
                             MulDiv((std::max)(520, static_cast<int>(setting(L"WindowHeight", 720))), dpi, 96));
    CreateWindowExW(0, wc.lpszClassName, L"Win.apk", WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN,
                    CW_USEDEFAULT, CW_USEDEFAULT, w, h, nullptr, nullptr, inst, nullptr);

    if (!run_apk.empty()) {
        // A shortcut: the app, with its splash; nothing else unless it fails.
        const std::wstring name = run_apk.stem().wstring();
        const App* app = find_app(name);
        launch(app ? *app : App{name, label_of(name), run_apk});
    } else if (!files.empty()) {
        // Opened from Explorer, or dropped on the program: only the install window.
        for (const fs::path& f : files) install(f);
    } else {
        show_library(show);
    }
    LocalFree(argv);

    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    g_icons.clear();
    g_f = Fonts{};
    Gdiplus::GdiplusShutdown(gdiplus);
    OleUninitialize();
    return 0;
}
