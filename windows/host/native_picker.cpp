// native_picker.cpp -- com.macapk.loader.DocumentPickerHandler's host panel on
// Windows: the system file dialog (IFileOpenDialog / IFileSaveDialog), as
// upstream's document_picker.mm shows NSOpenPanel. Asynchronous like the Mac
// one: the dialog runs on its own thread, owned by the app window, and the
// answer goes back through DocumentPickerHandler.macapkOnPicked(id, paths),
// null for a cancel.

#include "host_natives.h"

#include <string>
#include <thread>
#include <vector>

#include <windows.h>
#include <shobjidl.h>

#include "win32_window.h"

namespace winapk::host {

namespace {

constexpr int kModeOpen = 0, kModeSave = 1, kModeTree = 2;

std::wstring wide(JNIEnv* env, jstring s) {
    if (!s) return {};
    const jchar* c = env->GetStringChars(s, nullptr);
    std::wstring w(reinterpret_cast<const wchar_t*>(c), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, c);
    return w;
}

// File-type filters for MIME types: a family wildcard becomes the common
// extensions of that family; anything unknown leaves the dialog unfiltered.
std::wstring patterns_for(const std::wstring& mime) {
    struct Entry {
        const wchar_t* mime;
        const wchar_t* patterns;
    };
    static const Entry kTable[] = {
        {L"image/*", L"*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp;*.heic;*.heif"},
        {L"image/png", L"*.png"},
        {L"image/jpeg", L"*.jpg;*.jpeg"},
        {L"image/gif", L"*.gif"},
        {L"image/webp", L"*.webp"},
        {L"video/*", L"*.mp4;*.mkv;*.webm;*.mov;*.avi;*.3gp"},
        {L"audio/*", L"*.mp3;*.ogg;*.wav;*.m4a;*.aac;*.flac;*.opus"},
        {L"text/*", L"*.txt;*.md;*.csv;*.html;*.htm;*.xml;*.json"},
        {L"text/plain", L"*.txt"},
        {L"text/markdown", L"*.md;*.markdown"},
        {L"text/csv", L"*.csv"},
        {L"application/pdf", L"*.pdf"},
        {L"application/json", L"*.json"},
        {L"application/zip", L"*.zip"},
        {L"application/vnd.android.package-archive", L"*.apk"},
    };
    for (const Entry& e : kTable) {
        if (mime == e.mime) return e.patterns;
    }
    return {};
}

struct Request {
    JavaVM* vm;
    jclass handler;  // global ref
    int mode;
    bool multiple;
    std::vector<std::wstring> mimes;
    std::wstring name, initial_dir;
    jlong id;
};

std::vector<std::wstring> show(const Request& r) {
    std::vector<std::wstring> paths;
    IFileDialog* dialog = nullptr;
    const bool save = r.mode == kModeSave;
    if (FAILED(CoCreateInstance(save ? CLSID_FileSaveDialog : CLSID_FileOpenDialog, nullptr, CLSCTX_INPROC_SERVER,
                                save ? IID_IFileSaveDialog : IID_IFileOpenDialog, reinterpret_cast<void**>(&dialog)))) {
        return paths;
    }
    FILEOPENDIALOGOPTIONS opts = 0;
    dialog->GetOptions(&opts);
    opts |= FOS_FORCEFILESYSTEM;
    if (r.mode == kModeTree) opts |= FOS_PICKFOLDERS;
    if (r.multiple && r.mode == kModeOpen) opts |= FOS_ALLOWMULTISELECT;
    dialog->SetOptions(opts);

    // One filter with every known pattern, then "All files".
    std::wstring all;
    for (const std::wstring& m : r.mimes) {
        const std::wstring p = patterns_for(m);
        if (!p.empty()) all += (all.empty() ? L"" : L";") + p;
    }
    if (r.mode != kModeTree && !all.empty()) {
        const COMDLG_FILTERSPEC specs[] = {{L"Supported files", all.c_str()}, {L"All files", L"*.*"}};
        dialog->SetFileTypes(2, specs);
    }
    if (!r.name.empty()) dialog->SetFileName(r.name.c_str());
    if (!r.initial_dir.empty()) {
        IShellItem* folder = nullptr;
        if (SUCCEEDED(SHCreateItemFromParsingName(r.initial_dir.c_str(), nullptr, IID_PPV_ARGS(&folder)))) {
            dialog->SetFolder(folder);
            folder->Release();
        }
    }

    if (SUCCEEDED(dialog->Show(static_cast<HWND>(window_handle())))) {
        auto add = [&](IShellItem* item) {
            PWSTR p = nullptr;
            if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &p))) {
                paths.emplace_back(p);
                CoTaskMemFree(p);
            }
        };
        IFileOpenDialog* open = nullptr;
        IShellItemArray* items = nullptr;
        if (!save && SUCCEEDED(dialog->QueryInterface(IID_PPV_ARGS(&open))) && SUCCEEDED(open->GetResults(&items))) {
            DWORD n = 0;
            items->GetCount(&n);
            for (DWORD i = 0; i < n; ++i) {
                IShellItem* item = nullptr;
                if (SUCCEEDED(items->GetItemAt(i, &item))) {
                    add(item);
                    item->Release();
                }
            }
            items->Release();
        } else {
            IShellItem* item = nullptr;
            if (SUCCEEDED(dialog->GetResult(&item))) {
                add(item);
                item->Release();
            }
        }
        if (open) open->Release();
    }
    dialog->Release();
    return paths;
}

void run(Request r) {
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
    const std::vector<std::wstring> paths = show(r);
    CoUninitialize();

    JNIEnv* env = nullptr;
    if (r.vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return;
    jobjectArray result = nullptr;
    if (!paths.empty()) {
        result = env->NewObjectArray(static_cast<jsize>(paths.size()), env->FindClass("java/lang/String"), nullptr);
        for (size_t i = 0; result && i < paths.size(); ++i) {
            jstring s = env->NewString(reinterpret_cast<const jchar*>(paths[i].data()), static_cast<jsize>(paths[i].size()));
            env->SetObjectArrayElement(result, static_cast<jsize>(i), s);
            env->DeleteLocalRef(s);
        }
    }
    jmethodID done = env->GetStaticMethodID(r.handler, "macapkOnPicked", "(J[Ljava/lang/String;)V");
    if (done) env->CallStaticVoidMethod(r.handler, done, r.id, result);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteGlobalRef(r.handler);
    r.vm->DetachCurrentThread();
}

jboolean start(JNIEnv* env, jclass cls, int mode, jboolean multiple, jobjectArray mimes, jstring name, jstring dir,
               jlong id) {
    Request r{};
    if (env->GetJavaVM(&r.vm) != JNI_OK) return JNI_FALSE;
    r.handler = static_cast<jclass>(env->NewGlobalRef(cls));
    r.mode = mode;
    r.multiple = multiple == JNI_TRUE;
    const jsize n = mimes ? env->GetArrayLength(mimes) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto m = static_cast<jstring>(env->GetObjectArrayElement(mimes, i));
        r.mimes.push_back(wide(env, m));
        env->DeleteLocalRef(m);
    }
    r.name = wide(env, name);
    r.initial_dir = wide(env, dir);
    r.id = id;
    std::thread(run, std::move(r)).detach();
    return JNI_TRUE;
}

jboolean JNICALL Picker_pick(JNIEnv* env, jclass cls, jboolean save, jboolean multiple, jobjectArray mimes,
                             jstring name, jlong id) {
    return start(env, cls, save ? kModeSave : kModeOpen, multiple, mimes, name, nullptr, id);
}
jboolean JNICALL Picker_pickEx(JNIEnv* env, jclass cls, jint mode, jboolean multiple, jobjectArray mimes,
                               jstring name, jstring dir, jlong id) {
    return start(env, cls, mode, multiple, mimes, name, dir, id);
}

constexpr const char* kCls = "com.macapk.loader.DocumentPickerHandler";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativePick", "(ZZ[Ljava/lang/String;Ljava/lang/String;J)Z", Picker_pick),
    WINAPK_HOST_NATIVE(kCls, "nativePickEx", "(IZ[Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)Z",
                       Picker_pickEx),
};

}  // namespace

HostMethods native_picker_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
