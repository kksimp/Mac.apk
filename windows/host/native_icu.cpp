// native_icu.cpp -- com.macapk.icu.HostIcu on Windows: number-skeleton
// formatting (units and the like, which the JDK has no CLDR data for) through
// the ICU that Windows ships as icu.dll (Windows 10 1903 and later), as
// upstream's icu_host_bridge.cpp uses macOS's libicucore. Same engine and data
// family as Android's android.icu. icu.dll exports the C API without version
// suffixes, so the functions are looked up by their plain names.
//
// Errors follow HostIcu's contract: an ICU failure throws
// IllegalArgumentException(u_errorName), and no icu.dll throws
// UnsupportedOperationException, never a made-up string.
//
// Also android.icu.util.LocaleData (measurement system, paper size, CLDR
// version, exemplar sets, delimiters, the locale display pattern) through
// ICU4C's ulocdata_* API, the C twin of the ICU4J class Android ships, as
// upstream's icu_host_bridge.cpp does over libicucore. A missing supplemental
// value throws MissingResourceException, as ICU4J does.

#include "host_natives.h"

#include <cstdint>
#include <string>
#include <vector>

#include <windows.h>

namespace winapk::host {

namespace {

using UErrorCode = int;
struct Icu {
    void* (*open)(const char16_t*, int32_t, const char*, UErrorCode*);
    void* (*open_result)(UErrorCode*);
    void (*format_double)(const void*, double, void*, UErrorCode*);
    void (*format_decimal)(const void*, const char*, int32_t, void*, UErrorCode*);
    int32_t (*to_string)(const void*, char16_t*, int32_t, UErrorCode*);
    void (*close)(void*);
    void (*close_result)(void*);
    const char* (*error_name)(UErrorCode);
};

HMODULE icu_module() {
    static HMODULE m = LoadLibraryExW(L"icu.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32);
    return m;
}

const Icu* icu() {
    static const Icu* api = []() -> const Icu* {
        HMODULE m = icu_module();
        if (!m) return nullptr;
        static Icu a;
        auto get = [m](const char* name) { return GetProcAddress(m, name); };
        a.open = reinterpret_cast<decltype(a.open)>(get("unumf_openForSkeletonAndLocale"));
        a.open_result = reinterpret_cast<decltype(a.open_result)>(get("unumf_openResult"));
        a.format_double = reinterpret_cast<decltype(a.format_double)>(get("unumf_formatDouble"));
        a.format_decimal = reinterpret_cast<decltype(a.format_decimal)>(get("unumf_formatDecimal"));
        a.to_string = reinterpret_cast<decltype(a.to_string)>(get("unumf_resultToString"));
        a.close = reinterpret_cast<decltype(a.close)>(get("unumf_close"));
        a.close_result = reinterpret_cast<decltype(a.close_result)>(get("unumf_closeResult"));
        a.error_name = reinterpret_cast<decltype(a.error_name)>(get("u_errorName"));
        const bool complete = a.open && a.open_result && a.format_double && a.format_decimal && a.to_string &&
                              a.close && a.close_result && a.error_name;
        return complete ? &a : nullptr;
    }();
    return api;
}

void throw_java(JNIEnv* env, const char* cls, const char* message) {
    if (jclass c = env->FindClass(cls)) env->ThrowNew(c, message);
}

// Formats with `skeleton` in `locale`; `format` fills the result.
template <typename F>
jstring format(JNIEnv* env, jstring skeleton, jstring locale, F fill) {
    const Icu* api = icu();
    if (!api) {
        throw_java(env, "java/lang/UnsupportedOperationException", "Windows' ICU (icu.dll) is not available");
        return nullptr;
    }
    if (!skeleton || !locale) {
        throw_java(env, "java/lang/IllegalArgumentException", "U_ILLEGAL_ARGUMENT_ERROR");
        return nullptr;
    }
    const jchar* sk = env->GetStringChars(skeleton, nullptr);
    const std::u16string skel(reinterpret_cast<const char16_t*>(sk), static_cast<size_t>(env->GetStringLength(skeleton)));
    env->ReleaseStringChars(skeleton, sk);
    const char* loc = env->GetStringUTFChars(locale, nullptr);
    const std::string locale_id = loc ? loc : "";
    if (loc) env->ReleaseStringUTFChars(locale, loc);

    UErrorCode ec = 0;
    void* formatter = api->open(skel.data(), static_cast<int32_t>(skel.size()), locale_id.c_str(), &ec);
    void* result = ec <= 0 ? api->open_result(&ec) : nullptr;
    std::u16string text;
    if (ec <= 0) fill(api, formatter, result, &ec);
    if (ec <= 0) {
        const int32_t n = api->to_string(result, nullptr, 0, &ec);
        if (ec == 15 /* U_BUFFER_OVERFLOW_ERROR */ || ec <= 0) {
            ec = 0;
            text.resize(static_cast<size_t>(n));
            api->to_string(result, text.data(), n, &ec);
            if (ec == -124 /* U_STRING_NOT_TERMINATED_WARNING */) ec = 0;
        }
    }
    if (result) api->close_result(result);
    if (formatter) api->close(formatter);
    if (ec > 0) {
        throw_java(env, "java/lang/IllegalArgumentException", api->error_name(ec));
        return nullptr;
    }
    return env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
}

jstring JNICALL Icu_formatDouble(JNIEnv* env, jclass, jstring skeleton, jstring locale, jdouble value) {
    return format(env, skeleton, locale, [value](const Icu* api, void* f, void* r, UErrorCode* ec) {
        api->format_double(f, value, r, ec);
    });
}
jstring JNICALL Icu_formatDecimal(JNIEnv* env, jclass, jstring skeleton, jstring locale, jstring decimal) {
    if (!decimal) {
        throw_java(env, "java/lang/IllegalArgumentException", "U_ILLEGAL_ARGUMENT_ERROR");
        return nullptr;
    }
    const char* d = env->GetStringUTFChars(decimal, nullptr);
    const std::string number = d ? d : "";
    if (d) env->ReleaseStringUTFChars(decimal, d);
    return format(env, skeleton, locale, [&number](const Icu* api, void* f, void* r, UErrorCode* ec) {
        api->format_decimal(f, number.c_str(), static_cast<int32_t>(number.size()), r, ec);
    });
}

// ---- android.icu.util.LocaleData ------------------------------------------

using ULocaleData = void;
using USet = void;

// Separate from icu() so an icu.dll missing one family never disables the other.
struct LocaleDataApi {
    int32_t (*measurement_system)(const char*, UErrorCode*) = nullptr;
    void (*paper_size)(const char*, int32_t*, int32_t*, UErrorCode*) = nullptr;
    void (*cldr_version)(uint8_t*, UErrorCode*) = nullptr;
    ULocaleData* (*open)(const char*, UErrorCode*) = nullptr;
    void (*close)(ULocaleData*) = nullptr;
    void (*set_no_substitute)(ULocaleData*, int8_t) = nullptr;  // UBool is int8_t
    USet* (*exemplar_set)(ULocaleData*, USet*, uint32_t, int32_t, UErrorCode*) = nullptr;
    int32_t (*delimiter)(ULocaleData*, int32_t, char16_t*, int32_t, UErrorCode*) = nullptr;
    int32_t (*display_pattern)(ULocaleData*, char16_t*, int32_t, UErrorCode*) = nullptr;
    int32_t (*separator)(ULocaleData*, char16_t*, int32_t, UErrorCode*) = nullptr;
    int32_t (*set_item_count)(const USet*) = nullptr;
    int32_t (*set_item)(const USet*, int32_t, int32_t*, int32_t*, char16_t*, int32_t, UErrorCode*) = nullptr;
    void (*set_close)(USet*) = nullptr;
    const char* (*error_name)(UErrorCode) = nullptr;
};

const LocaleDataApi* locale_data_api() {
    static const LocaleDataApi* api = []() -> const LocaleDataApi* {
        HMODULE m = icu_module();
        if (!m) return nullptr;
        static LocaleDataApi a;
        auto get = [m](const char* name) { return GetProcAddress(m, name); };
#define WINAPK_ICU_SYM(field, name) a.field = reinterpret_cast<decltype(a.field)>(get(name))
        WINAPK_ICU_SYM(measurement_system, "ulocdata_getMeasurementSystem");
        WINAPK_ICU_SYM(paper_size, "ulocdata_getPaperSize");
        WINAPK_ICU_SYM(cldr_version, "ulocdata_getCLDRVersion");
        WINAPK_ICU_SYM(open, "ulocdata_open");
        WINAPK_ICU_SYM(close, "ulocdata_close");
        WINAPK_ICU_SYM(set_no_substitute, "ulocdata_setNoSubstitute");
        WINAPK_ICU_SYM(exemplar_set, "ulocdata_getExemplarSet");
        WINAPK_ICU_SYM(delimiter, "ulocdata_getDelimiter");
        WINAPK_ICU_SYM(display_pattern, "ulocdata_getLocaleDisplayPattern");
        WINAPK_ICU_SYM(separator, "ulocdata_getLocaleSeparator");
        WINAPK_ICU_SYM(set_item_count, "uset_getItemCount");
        WINAPK_ICU_SYM(set_item, "uset_getItem");
        WINAPK_ICU_SYM(set_close, "uset_close");
        WINAPK_ICU_SYM(error_name, "u_errorName");
#undef WINAPK_ICU_SYM
        const bool complete = a.measurement_system && a.paper_size && a.cldr_version && a.open && a.close &&
                              a.set_no_substitute && a.exemplar_set && a.delimiter && a.display_pattern &&
                              a.separator && a.set_item_count && a.set_item && a.set_close && a.error_name;
        return complete ? &a : nullptr;
    }();
    return api;
}

const LocaleDataApi* locale_data_or_throw(JNIEnv* env) {
    const LocaleDataApi* api = locale_data_api();
    if (!api) {
        throw_java(env, "java/lang/UnsupportedOperationException",
                   "Windows' ICU (icu.dll ulocdata_*) is not available");
    }
    return api;
}

// ICU4C reads "" as the default locale; ICU4J's ROOT (getName() == "") is the
// root locale. "und" behaves like ICU4J's ROOT: lookups fall back to root and
// region inference runs likely subtags (und -> en_Latn_US).
std::string locale_id(JNIEnv* env, jstring locale) {
    std::string id;
    if (locale) {
        if (const char* c = env->GetStringUTFChars(locale, nullptr)) {
            id = c;
            env->ReleaseStringUTFChars(locale, c);
        }
    }
    return id.empty() ? std::string("und") : id;
}

void throw_missing(JNIEnv* env, const LocaleDataApi& api, const char* what, UErrorCode ec) {
    const std::string message = std::string(what) + ": " + api.error_name(ec);
    throw_java(env, "java/util/MissingResourceException", message.c_str());
}

jint JNICALL Icu_measurementSystem(JNIEnv* env, jclass, jstring locale) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return -1;
    UErrorCode ec = 0;
    const int32_t ms = api->measurement_system(locale_id(env, locale).c_str(), &ec);
    if (ec > 0) {
        throw_missing(env, *api, "ulocdata_getMeasurementSystem", ec);
        return -1;
    }
    return ms;
}

jintArray JNICALL Icu_paperSize(JNIEnv* env, jclass, jstring locale) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return nullptr;
    UErrorCode ec = 0;
    int32_t h = 0, w = 0;
    api->paper_size(locale_id(env, locale).c_str(), &h, &w, &ec);
    if (ec > 0) {
        throw_missing(env, *api, "ulocdata_getPaperSize", ec);
        return nullptr;
    }
    const jint hw[2] = {h, w};
    jintArray out = env->NewIntArray(2);
    if (out) env->SetIntArrayRegion(out, 0, 2, hw);
    return out;
}

jintArray JNICALL Icu_cldrVersion(JNIEnv* env, jclass) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return nullptr;
    uint8_t v[4] = {0, 0, 0, 0};
    UErrorCode ec = 0;
    api->cldr_version(v, &ec);
    if (ec > 0) {
        throw_missing(env, *api, "ulocdata_getCLDRVersion", ec);
        return nullptr;
    }
    const jint vi[4] = {v[0], v[1], v[2], v[3]};
    jintArray out = env->NewIntArray(4);
    if (out) env->SetIntArrayRegion(out, 0, 4, vi);
    return out;
}

// One ULocaleData for the duration of a call; closed on every path.
struct LocaleData {
    const LocaleDataApi& api;
    ULocaleData* uld = nullptr;
    LocaleData(const LocaleDataApi& a, const std::string& id, bool no_substitute, UErrorCode* ec) : api(a) {
        uld = api.open(id.c_str(), ec);
        if (uld) api.set_no_substitute(uld, no_substitute ? 1 : 0);
    }
    ~LocaleData() {
        if (uld) api.close(uld);
    }
    LocaleData(const LocaleData&) = delete;
    LocaleData& operator=(const LocaleData&) = delete;
};

// One UChar string through `get`, growing the buffer once on
// U_BUFFER_OVERFLOW_ERROR. Null with ec > 0 left for the caller.
template <typename Get>
jstring read_ustring(JNIEnv* env, Get get, UErrorCode* ec) {
    char16_t buf[64];
    int32_t len = get(buf, 64, ec);
    if (*ec == 15 /* U_BUFFER_OVERFLOW_ERROR */ && len > 64) {
        std::vector<char16_t> big(static_cast<size_t>(len) + 1);
        *ec = 0;
        len = get(big.data(), len + 1, ec);
        if (*ec > 0) return nullptr;
        return env->NewString(reinterpret_cast<const jchar*>(big.data()), len);
    }
    if (*ec > 0 || len < 0) return nullptr;
    return env->NewString(reinterpret_cast<const jchar*>(buf), len < 64 ? len : 64);
}

// Null when the locale (or, under noSubstitute, its non-root data) has none.
jstring JNICALL Icu_delimiter(JNIEnv* env, jclass, jstring locale, jboolean no_substitute, jint type) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return nullptr;
    UErrorCode ec = 0;
    LocaleData ld(*api, locale_id(env, locale), no_substitute == JNI_TRUE, &ec);
    if (!ld.uld || ec > 0) return nullptr;
    return read_ustring(env, [&](char16_t* b, int32_t cap, UErrorCode* e) {
        return api->delimiter(ld.uld, type, b, cap, e);
    }, &ec);
}

// {int[] start,end pairs, String[] strings}, or null when ICU has no set.
jobjectArray JNICALL Icu_exemplarSet(JNIEnv* env, jclass, jstring locale, jboolean no_substitute, jint options,
                                     jint extype) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return nullptr;
    UErrorCode ec = 0;
    LocaleData ld(*api, locale_id(env, locale), no_substitute == JNI_TRUE, &ec);
    if (!ld.uld || ec > 0) return nullptr;
    USet* set = api->exemplar_set(ld.uld, nullptr, static_cast<uint32_t>(options), extype, &ec);
    if (!set) return nullptr;
    if (ec > 0) {
        api->set_close(set);
        return nullptr;
    }
    std::vector<jint> ranges;
    std::vector<std::u16string> strings;
    const int32_t n = api->set_item_count(set);
    for (int32_t i = 0; i < n; i++) {
        int32_t start = 0, end = 0;
        char16_t sbuf[128];
        UErrorCode e2 = 0;
        const int32_t slen = api->set_item(set, i, &start, &end, sbuf, 128, &e2);
        if (e2 > 0) continue;
        if (slen == 0) {
            ranges.push_back(start);
            ranges.push_back(end);
        } else {
            strings.emplace_back(sbuf, static_cast<size_t>(slen < 128 ? slen : 128));
        }
    }
    api->set_close(set);

    jclass object_class = env->FindClass("java/lang/Object");
    jclass string_class = env->FindClass("java/lang/String");
    if (!object_class || !string_class) return nullptr;
    jobjectArray out = env->NewObjectArray(2, object_class, nullptr);
    jintArray jr = env->NewIntArray(static_cast<jsize>(ranges.size()));
    jobjectArray js = env->NewObjectArray(static_cast<jsize>(strings.size()), string_class, nullptr);
    if (!out || !jr || !js) return nullptr;
    if (!ranges.empty()) env->SetIntArrayRegion(jr, 0, static_cast<jsize>(ranges.size()), ranges.data());
    for (size_t i = 0; i < strings.size(); i++) {
        jstring s = env->NewString(reinterpret_cast<const jchar*>(strings[i].data()),
                                   static_cast<jsize>(strings[i].size()));
        env->SetObjectArrayElement(js, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    env->SetObjectArrayElement(out, 0, jr);
    env->SetObjectArrayElement(out, 1, js);
    return out;
}

// ICU4J reads these from the lang bundle with no noSubstitute check and throws
// MissingResourceException when absent; opened without noSubstitute to match.
jstring locale_display_string(JNIEnv* env, jstring locale, bool separator) {
    const LocaleDataApi* api = locale_data_or_throw(env);
    if (!api) return nullptr;
    UErrorCode ec = 0;
    LocaleData ld(*api, locale_id(env, locale), false, &ec);
    jstring out = nullptr;
    if (ld.uld && ec <= 0) {
        out = read_ustring(env, [&](char16_t* b, int32_t cap, UErrorCode* e) {
            return separator ? api->separator(ld.uld, b, cap, e) : api->display_pattern(ld.uld, b, cap, e);
        }, &ec);
    }
    if (!out && !env->ExceptionCheck()) {
        throw_missing(env, *api, separator ? "ulocdata_getLocaleSeparator" : "ulocdata_getLocaleDisplayPattern",
                      ec);
    }
    return out;
}
jstring JNICALL Icu_localeDisplayPattern(JNIEnv* env, jclass, jstring locale) {
    return locale_display_string(env, locale, false);
}
jstring JNICALL Icu_localeSeparator(JNIEnv* env, jclass, jstring locale) {
    return locale_display_string(env, locale, true);
}

constexpr const char* kCls = "com.macapk.icu.HostIcu";

const HostMethod kMethods[] = {
    WINAPK_HOST_NATIVE(kCls, "nativeFormatDouble", "(Ljava/lang/String;Ljava/lang/String;D)Ljava/lang/String;",
                       Icu_formatDouble),
    WINAPK_HOST_NATIVE(kCls, "nativeFormatDecimal",
                       "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", Icu_formatDecimal),
    WINAPK_HOST_NATIVE(kCls, "nativeMeasurementSystem", "(Ljava/lang/String;)I", Icu_measurementSystem),
    WINAPK_HOST_NATIVE(kCls, "nativePaperSize", "(Ljava/lang/String;)[I", Icu_paperSize),
    WINAPK_HOST_NATIVE(kCls, "nativeCldrVersion", "()[I", Icu_cldrVersion),
    WINAPK_HOST_NATIVE(kCls, "nativeDelimiter", "(Ljava/lang/String;ZI)Ljava/lang/String;", Icu_delimiter),
    WINAPK_HOST_NATIVE(kCls, "nativeExemplarSet", "(Ljava/lang/String;ZII)[Ljava/lang/Object;", Icu_exemplarSet),
    WINAPK_HOST_NATIVE(kCls, "nativeLocaleDisplayPattern", "(Ljava/lang/String;)Ljava/lang/String;",
                       Icu_localeDisplayPattern),
    WINAPK_HOST_NATIVE(kCls, "nativeLocaleSeparator", "(Ljava/lang/String;)Ljava/lang/String;", Icu_localeSeparator),
};

}  // namespace

HostMethods native_icu_methods() { return {kMethods, sizeof(kMethods) / sizeof(kMethods[0])}; }

}  // namespace winapk::host
