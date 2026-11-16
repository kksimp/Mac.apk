// native_media.h -- libmediandk for an app's native code (only the
// AMEDIAFORMAT_KEY_* data symbols so far; the media API is not ported).

#pragma once

namespace winapk::host {

// The address of the AMEDIAFORMAT_KEY_* variable `name`; null for other names.
void* native_media_resolve(const char* name);

}  // namespace winapk::host
