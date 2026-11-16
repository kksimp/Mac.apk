// native_opensles.h -- libOpenSLES for an app's native code (not ported yet:
// the engine reports itself unsupported).

#pragma once

namespace winapk::host {

// The guest-callable OpenSL ES entry points, by name; null for other names.
void* native_opensles_resolve(const char* name);

}  // namespace winapk::host
