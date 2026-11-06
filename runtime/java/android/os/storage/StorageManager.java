// android.os.storage.StorageManager -- storage volume registry.
// F-Droid's NearbyViewBinder probes storage to find writable repo
// destinations, and AOSP's own documented idiom for consuming this API
// is `getStorageVolumes().get(0)` -- both @NonNull, since a real device
// (even one with no SD card) always has at least a primary volume.
// v1.0.98 originally returned Collections.emptyList()/null on the claim
// "we have no external-storage concept on macOS", which is stale:
// android.os.Environment.getExternalStorageDirectory() already resolves
// a real, mounted, writable directory (<android_root>/sdcard) that this
// class's primary volume describes.
//
// Source (fetched 2026-07-22, quoted verbatim):
// frameworks/base/core/java/android/os/storage/StorageManager.java
//   public @NonNull List<StorageVolume> getStorageVolumes() { ... }
//   public @NonNull StorageVolume getPrimaryStorageVolume() {
//       return getVolumeList(mContext.getUserId(), ...)[0];
//   }
//   public static final UUID UUID_DEFAULT =
//       UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");
//
// We model exactly one volume (primary, non-removable) since macOS has
// no SD-card-style removable-volume concept. StorageVolume itself
// (runtime/java/android/os/storage/StorageVolume.java) now carries the
// AOSP accessors this note used to call the missing half --
// isPrimary()/getState()/getUuid()/getDirectory()/getOwner()/
// getMediaStoreVolumeName() plus Parcelable -- backed by Environment's real
// directory and MEDIA_MOUNTED state.
package android.os.storage;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class StorageManager {
    /** AOSP: fixed sentinel meaning "default/primary internal storage",
     *  used across the storage / app.usage APIs (StorageStatsManager's
     *  UUID param, etc). Exact literal from AOSP's declaration. */
    public static final UUID UUID_DEFAULT =
            UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69");

    /** The one storage volume Mac.apk models. A shared instance so a
     *  caller comparing getPrimaryStorageVolume() against
     *  getStorageVolumes().get(0) (AOSP's own documented idiom) sees the
     *  same object, matching "the primary shared storage device" being a
     *  single stable identity on a real device. */
    private static final StorageVolume PRIMARY_VOLUME = new StorageVolume();

    public StorageManager() {}

    public List<StorageVolume> getStorageVolumes() {
        return Collections.singletonList(PRIMARY_VOLUME);
    }
    public StorageVolume getStorageVolume(java.io.File file) {
        return (file != null) ? PRIMARY_VOLUME : null;
    }
    public StorageVolume getPrimaryStorageVolume() {
        return PRIMARY_VOLUME;
    }
    /** AOSP: "Return the UUID that uniquely identifies the storage volume that
     *  hosts the given path" -- it returns a real UUID or throws IOException,
     *  and NEVER returns null. Callers written against that contract do not
     *  null-check, because a real device never gives them the chance.
     *
     *  ⚠ MEASURED 2026-08-17: returning null here broke every app that checks
     *  free space before downloading. Aurora Store's DownloadWorker does the
     *  standard AndroidX-era `getAllocatableBytes(getUuidForPath(dir))` dance
     *  behind an SDK_INT >= 26 gate our SDK 29 always satisfies, and died with
     *  `NullPointerException: Cannot invoke "Object.getClass()" because
     *  "<local10>" is null` BEFORE downloading a single byte -- reproduced on
     *  two unrelated free apps, so it is a generic framework gap and not an
     *  Aurora quirk. The correct value was already declared two lines above
     *  this method the whole time.
     *
     *  Mac.apk models exactly one volume (PRIMARY_VOLUME), so every path on it
     *  is UUID_DEFAULT. */
    public java.util.UUID getUuidForPath(java.io.File path) {
        return UUID_DEFAULT;
    }

    /** AOSP: "Return the maximum number of new bytes that your app can allocate
     *  for itself", including cached data the OS could clear on demand.
     *
     *  ⚠ Returning 0 meant "the disk is full" to every caller. An app that
     *  politely checks before downloading would refuse to start, and the
     *  failure looks like the app being broken rather than the framework
     *  lying. Report the host's REAL free space -- java.io.File.getUsableSpace()
     *  is the bytes actually available to this process on the volume backing
     *  the Android root, which is the honest answer and needs no native code.
     *  We deliberately do NOT add a reclaimable-cache allowance on top: AOSP
     *  may return more than the raw free space because it can evict caches,
     *  and over-reporting would just move the failure to the write itself. */
    public long getAllocatableBytes(java.util.UUID storageUuid) {
        try {
            java.io.File root = PRIMARY_VOLUME.getDirectory();
            if (root == null) return 0L;
            long usable = root.getUsableSpace();
            return usable > 0L ? usable : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** AOSP @AllocateFlags constants (hidden in core StorageManager). Finsky's
     *  InstallerV2 passes these to the flagged allocateBytes / getAllocatableBytes
     *  overloads; in AOSP they only relax how much of the volume's RESERVE the
     *  allocation may consume. Mac.apk holds no reserve back (getAllocatableBytes
     *  reports raw usable space), so the flags never change our answer -- we
     *  declare them for signature/field parity so Finsky's calls resolve instead
     *  of throwing NoSuchFieldError. Values mirror AOSP. */
    public static final int FLAG_ALLOCATE_AGGRESSIVE = 1 << 0;
    public static final int FLAG_ALLOCATE_DEFY_ALL_RESERVE = 1 << 1;
    public static final int FLAG_ALLOCATE_DEFY_HALF_RESERVE = 1 << 2;
    public static final int FLAG_ALLOCATE_NON_AGGRESSIVE = 1 << 3;
    public static final int FLAG_ALLOCATE_CACHE_ONLY = 1 << 4;

    /** AOSP getAllocatableBytes(UUID, @AllocateFlags int) -- the flagged variant
     *  privileged callers (the Play Store) use. The flags only relax the reserve
     *  AOSP withholds; we withhold none, so the answer matches the 1-arg form. */
    public long getAllocatableBytes(java.util.UUID storageUuid, int flags) {
        return getAllocatableBytes(storageUuid);
    }

    /** AOSP: "Allocate the requested number of bytes for your application to
     *  use", clearing cached data as needed, and throwing IOException when it
     *  cannot be satisfied. We have no cache-eviction model, so this succeeds
     *  when the space genuinely exists and reports failure when it does not --
     *  rather than silently succeeding and letting the write fail later, which
     *  is the harder failure to diagnose. */
    public void allocateBytes(java.util.UUID storageUuid, long bytes)
            throws java.io.IOException {
        allocateBytes(storageUuid, bytes, 0);
    }

    /** AOSP allocateBytes(UUID, @BytesLong long, @AllocateFlags int) -- the hidden
     *  SystemApi overload Finsky's InstallerV2 reserve-space step calls. Its
     *  absence is why every Play Store install died with NoSuchMethodError ->
     *  InstallerException Status 6400 ("Can't install app"): the reserve step
     *  threw before any download. Same behavior as the 2-arg form (succeed when
     *  the space genuinely exists, else IOException); flags only relax AOSP's
     *  reserve, which we do not hold, so they do not change our answer. */
    public void allocateBytes(java.util.UUID storageUuid, long bytes, int flags)
            throws java.io.IOException {
        if (bytes <= 0L) return;
        long avail = getAllocatableBytes(storageUuid);
        if (avail < bytes) {
            throw new java.io.IOException(
                    "requested " + bytes + " bytes, only " + avail + " available");
        }
    }
    public boolean isAllocationSupported(java.io.FileDescriptor fd) { return false; }

    /** AOSP getCacheQuotaBytes (API 26): the cache quota for the app on a volume.
     *  Mac.apk imposes no per-app cache quota; report the volume's free space. */
    public long getCacheQuotaBytes(java.util.UUID storageUuid) { return Long.MAX_VALUE; }

    /** AOSP: @NonNull. Returns the shared PRIMARY_VOLUME rather than a fresh
     *  instance, so a caller comparing the result against
     *  getPrimaryStorageVolume() or getStorageVolumes().get(0) sees the same
     *  identity -- Mac.apk models exactly one volume, and handing back a new
     *  object each call made == fail for no reason a device would. */
    public android.os.storage.StorageVolume getStorageVolume(android.net.Uri uri) {
        return PRIMARY_VOLUME;
    }

    // ---- AOSP public constants (core/java/android/os/storage/StorageManager.java)
    public static final String ACTION_MANAGE_STORAGE = "android.os.storage.action.MANAGE_STORAGE";
    public static final String ACTION_CLEAR_APP_CACHE =
            "android.os.storage.action.CLEAR_APP_CACHE";
    public static final String EXTRA_UUID = "android.os.storage.extra.UUID";
    public static final String EXTRA_REQUESTED_BYTES =
            "android.os.storage.extra.REQUESTED_BYTES";

    /** AOSP :1363 / :1379 -- the same volume list widened to include shared
     *  profiles / recently-seen volumes. With one user, no profiles and no
     *  removable media, all three lists are the same list, which is exactly
     *  what a device with only emulated primary storage reports. */
    public List<StorageVolume> getStorageVolumesIncludingSharedProfiles() {
        return getStorageVolumes();
    }
    public List<StorageVolume> getRecentStorageVolumes() {
        return getStorageVolumes();
    }

    /** AOSP :2146 -- "the size of the cache data owned by the calling app on
     *  the given storage volume". Mac.apk keeps the app's cache under its data
     *  dir, so this is measurable rather than guessable: walk it. */
    public long getCacheSizeBytes(java.util.UUID storageUuid) throws java.io.IOException {
        java.io.File root = PRIMARY_VOLUME.getDirectory();
        if (root == null) return 0L;
        java.io.File cache = new java.io.File(root, "cache");
        return du(cache);
    }
    private static long du(java.io.File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        java.io.File[] kids = f.listFiles();
        if (kids == null) return 0L;
        long total = 0L;
        for (java.io.File k : kids) total += du(k);
        return total;
    }

    // ---- Cache behaviour flags (AOSP :2744-:2782). AOSP stores them as
    // EXTENDED ATTRIBUTES on the directory ("user.cache_group" /
    // "user.cache_tombstone") and the OS's cache-clearing pass reads them
    // back. Mac.apk has no cache-clearing pass at all, so nothing would ever
    // ACT on either flag -- but the read-back is still observable, and AOSP's
    // own contract is that isCacheBehaviorX() returns what setCacheBehaviorX()
    // last set. Keeping that pairing honest costs a map; answering a constant
    // false would contradict the caller's own write.
    private final java.util.Map<String, Boolean> mCacheGroup =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Boolean> mCacheTombstone =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(java.io.File path) throws java.io.IOException {
        if (path == null) throw new java.io.IOException("null path");
        return path.getCanonicalPath();
    }

    public void setCacheBehaviorGroup(java.io.File path, boolean group)
            throws java.io.IOException {
        mCacheGroup.put(key(path), group);
    }
    public boolean isCacheBehaviorGroup(java.io.File path) throws java.io.IOException {
        return Boolean.TRUE.equals(mCacheGroup.get(key(path)));
    }
    public void setCacheBehaviorTombstone(java.io.File path, boolean tombstone)
            throws java.io.IOException {
        mCacheTombstone.put(key(path), tombstone);
    }
    public boolean isCacheBehaviorTombstone(java.io.File path) throws java.io.IOException {
        return Boolean.TRUE.equals(mCacheTombstone.get(key(path)));
    }

    /** AOSP :2850 -- filesystem checkpointing (used by the update engine to
     *  roll back a failed OTA). APFS has snapshots, but nothing in Mac.apk
     *  drives them and there is no OTA to roll back, so the device-accurate
     *  answer is "this build does not support checkpointing". */
    public boolean isCheckpointSupported() { return false; }

    /** AOSP :1748 -- whether the given file lives on encrypted storage. AOSP
     *  answers isEncrypted() (i.e. ro.crypto.state) for anything under
     *  getDataDirectory(), true under the expand directory, false elsewhere.
     *  Mac.apk's tree sits on whatever the host volume is; we do not read the
     *  host's FileVault state (and a per-file answer derived from it would be
     *  a guess), so report false -- the same answer a device with an
     *  unencrypted userdata partition gives. */
    public boolean isEncrypted(java.io.File file) { return false; }

    /** AOSP :811 -- consistent with mountObb() above, which always fails:
     *  nothing is ever mounted here. The null check is AOSP's. */
    public boolean isObbMounted(String rawPath) {
        if (rawPath == null) throw new NullPointerException("rawPath cannot be null");
        return false;
    }

    /** AOSP :792 -- unmount an OBB, reporting through the listener. Since
     *  isObbMounted() is always false there is nothing to unmount; answer the
     *  listener immediately (AOSP guarantees the listener always gets a final
     *  answer) with ERROR_NOT_MOUNTED and return false ("not queued"), the
     *  same shape mountObb() uses above. Argument checks are AOSP's. */
    public boolean unmountObb(String rawPath, boolean force, OnObbStateChangeListener listener) {
        if (rawPath == null) throw new NullPointerException("rawPath cannot be null");
        if (listener == null) throw new NullPointerException("listener cannot be null");
        listener.onObbStateChange(rawPath, OnObbStateChangeListener.ERROR_NOT_MOUNTED);
        return false;
    }

    /** AOSP :753 -- a PendingIntent that launches the given package's
     *  "manage space" activity, @Nullable and documented to be null when the
     *  package declares no such activity. Mac.apk hosts exactly one app and
     *  resolves no other package's manifest, so there is no manage-space
     *  activity to point at. */
    public android.app.PendingIntent getManageSpaceActivityIntent(String packageName,
                                                                  int requestCode) {
        return null;
    }

    /** AOSP :1989 -- returns a FUSE-backed descriptor whose every read/write
     *  is routed to the supplied ProxyFileDescriptorCallback.
     *
     *  There is no way to honour this on macOS. The mechanism AOSP uses is a
     *  kernel FUSE mount (AppFuse) that the platform owns; macOS ships no
     *  in-kernel FUSE, the third-party one is a kext we cannot bundle, and
     *  nothing in pure Java can produce a file descriptor whose random-access
     *  reads call back into this process. The tempting shortcut -- ask the
     *  callback for its size, pull the whole thing into a temp file and hand
     *  back a descriptor on that -- is NOT this method: writes would not reach
     *  the callback, onRelease would never fire, and a multi-gigabyte callback
     *  would be materialised eagerly. So we take AOSP's own declared failure
     *  channel and throw IOException, which callers must already handle. */
    public android.os.ParcelFileDescriptor openProxyFileDescriptor(int mode,
            android.os.ProxyFileDescriptorCallback callback, android.os.Handler handler)
            throws java.io.IOException {
        if (callback == null) throw new NullPointerException("callback cannot be null");
        throw new java.io.IOException(
                "proxy file descriptors are not supported by the Mac.apk runtime");
    }

    /** AOSP :593 -- notified when a StorageVolume's state changes. */
    public static class StorageVolumeCallback {
        public void onStateChanged(StorageVolume volume) { }
    }

    // AOSP :617/:637 register against StorageManagerService's volume events.
    // Mac.apk models one permanently MEDIA_MOUNTED volume that can never
    // change state, so a registered callback is correct in never firing. The
    // registration itself is still tracked so unregister actually unregisters
    // (a caller that re-registers the same object must not accumulate).
    private final java.util.Map<StorageVolumeCallback, java.util.concurrent.Executor>
            mVolumeCallbacks = new java.util.concurrent.ConcurrentHashMap<>();

    public void registerStorageVolumeCallback(java.util.concurrent.Executor executor,
                                              StorageVolumeCallback callback) {
        java.util.Objects.requireNonNull(executor);
        java.util.Objects.requireNonNull(callback);
        mVolumeCallbacks.put(callback, executor);
    }

    public void unregisterStorageVolumeCallback(StorageVolumeCallback callback) {
        java.util.Objects.requireNonNull(callback);
        mVolumeCallbacks.remove(callback);
    }

    // --- OBB (Opaque Binary Blob) expansion-file support --------------
    // openttd ships its game data as an OBB and calls mountObb() then
    // getMountedObbPath() to read it back. AOSP mounts an OBB by loop-
    // mounting its filesystem image through vold, and notifies the caller
    // asynchronously via the listener once that binder round-trip lands
    // (frameworks/base/core/java/android/os/storage/StorageManager.java,
    // fetched 2026-08-12, android13-release). macOS has no loop-mount /
    // vold equivalent for Mac.apk to drive, so there is no way to actually
    // honor a mount.
    //
    // DESIGN DECISION: mountObb() must not accept the request and then go
    // silent -- AOSP's contract is that the listener always gets a final
    // answer, and an app that blocks (or shows a spinner) waiting for that
    // answer would hang forever against a listener we never call. Instead
    // this validates arguments exactly like AOSP (NullPointerException for
    // a null path/listener, IllegalArgumentException for a non-null key --
    // encrypted OBBs are unsupported in AOSP itself, not a Mac.apk-specific
    // restriction), then immediately answers with ERROR_COULD_NOT_MOUNT and
    // returns false ("not queued"), because there is genuinely no backend
    // to queue it to. ERROR_COULD_NOT_MOUNT is a real member of AOSP's own
    // status-code set (not an invented one) and the one whose name matches
    // our situation most precisely -- unlike e.g. ERROR_PERMISSION_DENIED,
    // which would assert a fact (an ownership/signature mismatch) we never
    // checked. Answering both signals (return value and callback) in the
    // same call means nothing downstream can be left waiting.
    public boolean mountObb(String rawPath, String key, OnObbStateChangeListener listener) {
        if (rawPath == null) throw new NullPointerException("rawPath cannot be null");
        if (key != null) {
            throw new IllegalArgumentException("mounting encrypted OBBs is no longer supported");
        }
        if (listener == null) throw new NullPointerException("listener cannot be null");
        listener.onObbStateChange(rawPath, OnObbStateChangeListener.ERROR_COULD_NOT_MOUNT);
        return false;
    }

    // Consistent with mountObb() above always failing: nothing is ever
    // mounted, so AOSP's documented "or null if not mounted" branch is the
    // only one Mac.apk can ever take.
    public String getMountedObbPath(String rawPath) {
        if (rawPath == null) throw new NullPointerException("rawPath cannot be null");
        return null;
    }
}


