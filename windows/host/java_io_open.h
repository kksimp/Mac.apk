// java_io_open.h -- java.io's file opens with FILE_SHARE_DELETE (java_io_open.cpp).
#pragma once

#include <jni.h>

#include <string>

namespace winapk::host {

// Binds FileInputStream, FileOutputStream and RandomAccessFile's open0 to
// opens that let the file be renamed or deleted while open, as on Android.
// False (and a log line) when the JDK's own opens stay.
bool java_io_open_init(JNIEnv* env);

// Where an Android path ("/data/user/0/<package>/x") lives on the host: the
// runtime sets the guest shim's table (winapk::android_host_path); unset, such
// a path is used as it is. Set before java_io_open_init.
using AndroidPathMapper = std::wstring (*)(const char* utf8_path);
void java_io_set_path_mapper(AndroidPathMapper mapper);

}  // namespace winapk::host
