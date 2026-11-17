// AppMeta -- what the Windows launcher shows for an installed app: its icon
// and its name.
//
// winapk-runtime --install-only calls write() in its own JVM, with the host
// natives bound, after installing the app and converting its code. The icon
// is upstream's: Boot.extractIconOnly renders the launcher icon (adaptive and
// vector ones included) to <app dir>/icon.png, as upstream's --extract-only
// install does for its library. The name is <application android:label>,
// resolved against the app's resources the same way, and goes to
// <app dir>/label.txt (UTF-8); the package name stands in when there is none.
// <app dir>/package.txt names the package, its versionCode and versionName,
// one per line: the launcher's installed-apps registry, which PackageManager
// answers stores from (F-Droid's Install -> Open), is built from it. A fourth
// line is the launched activity's android:screenOrientation (the
// ActivityInfo.SCREEN_ORIENTATION_* number, -1 when it declares none), so the
// library knows before starting the app whether it insists on portrait or
// landscape: such an app opens that way whatever the user picked.
//
// It also verifies the installed APKs' signature (ApkVerification: apksig over
// base.apk and every split), so the verdict is in the install log and in the
// verification cache before the app's first start.

package com.winapk.tools;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import com.macapk.loader.ApkVerification;
import com.macapk.loader.Boot;
import com.macapk.loader.Manifest;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class AppMeta {
    private AppMeta() {}

    public static void write(String apkPath, String appDir) {
        ApkVerification.Result signature = ApkVerification.of(apkPath);
        System.err.println("[win.apk] signature: " + (signature.verified
                ? "verified (scheme v" + signature.scheme + ")"
                : "does NOT verify (" + signature.reason + "); the app is installed, but it reports no signing"
                        + " certificate and is trusted as nothing"));
        Boot.extractIconOnly(apkPath, appDir, null);
        String label = null;
        String pkg = null;
        try {
            Manifest mf = Manifest.parse(new File(""), apkPath);
            pkg = mf.packageName;
            String version = (mf.versionNameRaw != null ? mf.versionNameRaw : "").replace('\n', ' ');
            save(new File(appDir, "package.txt"),
                    pkg + "\n" + mf.versionCode + "\n" + version + "\n" + launchOrientation(mf) + "\n");
            label = resolve(mf.applicationLabel, apkPath, pkg);
        } catch (Throwable t) {
            System.err.println("[win.apk] reading the app's name failed: " + t);
        }
        if (label == null || label.isEmpty()) label = pkg;
        if (label == null || label.isEmpty()) label = new File(apkPath).getName();
        try {
            save(new File(appDir, "label.txt"), label.trim());
        } catch (Exception e) {
            System.err.println("[win.apk] writing label.txt failed: " + e);
        }
    }

    // Always a file of its own. A run of the app from before Boot.CODE_DIR_OURS named these two
    // took them for the app's data, moved them to its data folder and left a link here; written
    // through that link (java.nio's write failed on one: "The path cannot be traversed because it
    // contains an untrusted mount point"), the text would land in the data folder and the launcher would move
    // the link, not the name, beside the APK.
    private static void save(File file, String text) throws java.io.IOException {
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    // The android:screenOrientation of the activity the launcher starts (the
    // real activity's when an <activity-alias> is the launcher entry),
    // SCREEN_ORIENTATION_UNSPECIFIED (-1) when unknown.
    static int launchOrientation(Manifest mf) {
        if (mf.launchableActivity == null || mf.activities == null) return -1;
        Manifest.ActivityEntry e = mf.activities.get(mf.launchableActivity);
        if (e == null) {
            for (Manifest.ActivityEntry x : mf.activities.values()) {
                if (mf.launchableActivity.equals(x.name) || mf.launchableActivity.equals(x.targetActivity)) {
                    e = x;
                    break;
                }
            }
        }
        return e != null ? e.screenOrientation : -1;
    }

    // A literal label stays as it is; "@0x7f..." or "@string/name" is looked up.
    static String resolve(String raw, String apkPath, String pkg) throws Exception {
        if (raw == null || !raw.startsWith("@")) return raw;
        AssetManager assets = new AssetManager(apkPath);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        Configuration cfg = Configuration.macapkPhoneDefaults();
        Resources res = new Resources(assets, metrics, cfg);
        Resources.macapkSetPackageName(pkg);
        String ref = raw.substring(1);
        int id = 0;
        if (ref.startsWith("0x") || ref.startsWith("0X")) {
            id = (int) Long.parseLong(ref.substring(2), 16);
        } else {
            int slash = ref.indexOf('/');
            String type = slash >= 0 ? ref.substring(0, slash) : "string";
            String name = slash >= 0 ? ref.substring(slash + 1) : ref;
            id = res.getIdentifier(name, type, pkg);
        }
        return id != 0 ? res.getString(id) : null;
    }
}
