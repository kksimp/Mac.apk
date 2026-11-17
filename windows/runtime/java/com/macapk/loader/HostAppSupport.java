// com.macapk.loader.HostAppSupport -- where the runtime keeps the small files
// every app process shares (the installer of record, the per-package uids, the
// system broker's state).
//
// On the Mac that is ~/Library/Application Support/Mac.apk. On Windows (Win.apk)
// it is the host's data folder, %LOCALAPPDATA%\Win.apk: the parent of the android
// root the host pins with -Dmacapk.android.root before any guest code runs, the
// same folder SpecialAccessStore reads the app-ops store and installed_apps.json
// from. Upstream spells the Mac path at each use; Win.apk routes those uses here
// so nothing lands in a "Library" folder under the Windows user profile.
package com.macapk.loader;

import java.io.File;

public final class HostAppSupport {
    private HostAppSupport() {}

    public static boolean windowsHost() { return File.separatorChar == '\\'; }

    /** The folder itself. */
    public static File dir() {
        if (windowsHost()) return SpecialAccessStore.windowsDataDir();
        return new File(SharedStorage.hostHome(), "Library/Application Support/Mac.apk");
    }

    /** A file in it; `relative` uses '/' and is resolved on the host. */
    public static File file(String relative) {
        return new File(dir(), relative.replace('/', File.separatorChar));
    }
}
