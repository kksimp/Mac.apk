// native_aaudio.h -- libaaudio for an app's native code, on audio_engine.h.

#pragma once

namespace winapk::host {

// The guest-callable AAudio entry points, by name; null for other names.
void* native_aaudio_resolve(const char* name);

}  // namespace winapk::host
