// native_asset.h -- the NDK's AAssetManager (libandroid) for an app's native
// code: assets read through the app's AssetManager.

#pragma once

namespace winapk::host {

// The guest-callable AAssetManager_*, AAsset_* and AAssetDir_* functions, by
// name; null for other names.
void* native_asset_resolve(const char* name);

}  // namespace winapk::host
