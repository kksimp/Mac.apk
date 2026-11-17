// jnigraphics.h -- libjnigraphics (the NDK's AndroidBitmap_* API) for guest
// code, on the runtime's android.graphics.Bitmap.

#pragma once

namespace winapk {

// The guest-callable AndroidBitmap_* functions, by name; null for other names.
void* jnigraphics_resolve(const char* name);

}  // namespace winapk
