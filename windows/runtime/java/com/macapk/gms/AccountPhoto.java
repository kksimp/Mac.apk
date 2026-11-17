// com.macapk.gms.AccountPhoto -- the signed-in account's picture, on disk.
//
// The launcher's account button shows it, as a browser's profile button shows
// the profile's picture. The sign-in window finds its URL while the Google
// session is still open (NativeSignIn.signInPhotoUrl; the session is
// in-private and gone with the window), and this saves the picture next to
// the GMS state as `photo-<account>.img`, the name the launcher reads
// (account_photo_path in launcher_main.cpp: the two must agree). Removing the
// account removes it.
//
// Best effort throughout: no picture leaves the button on the account's
// initial, which is what it showed before. Only Google's own picture host is
// fetched, and only an image GDI+ decodes (PNG, JPEG, GIF) is kept.
package com.macapk.gms;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class AccountPhoto {

    /** The size asked for, in pixels: the button is 26 DIPs, so this covers 400%. */
    private static final int SIZE = 128;
    private static final int MAX_BYTES = 1 << 20;

    /** The account-data key holding the picture's URL, which GoogleSignInBroker
     *  hands apps as GoogleSignInAccount.photoUrl. */
    static final String URL_KEY = "photoUrl";

    private AccountPhoto() {}

    /** Where the account's picture is. Lower case, and anything but
     *  [a-z0-9._@-] as '_', the same rule as the launcher's. */
    static File file(String account) {
        StringBuilder b = new StringBuilder("photo-");
        for (char c : account.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '@' || c == '-';
            b.append(ok ? c : '_');
        }
        return new File(GmsStore.dir(), b.append(".img").toString());
    }

    /** Fetch the picture at {@code url} (from the sign-in window) and keep it
     *  for {@code account}. Never throws. */
    public static void save(String account, String url) {
        if (account == null || url == null || !isGooglePhotoUrl(url)) return;
        try { GmsStore.putAccountData(account, URL_KEY, url); } catch (Throwable ignored) {}
        try {
            Map<String, String> h = new LinkedHashMap<>();
            // Not WebP, which GDI+ cannot decode.
            h.put("Accept", "image/png,image/jpeg;q=0.9,image/gif;q=0.5");
            GmsHttp.Response r = GmsHttp.get(sized(url), h);
            byte[] img = r.body;
            if (!r.ok() || img == null || img.length == 0 || img.length > MAX_BYTES || !isImage(img)) {
                log("no picture for " + GoogleAuthenticator.redact(account) + " (HTTP " + r.status
                    + ", " + (img == null ? 0 : img.length) + " bytes)");
                return;
            }
            File dest = file(account);
            File tmp = new File(dest.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(img); }
            java.nio.file.Files.move(tmp.toPath(), dest.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log("saved the picture of " + GoogleAuthenticator.redact(account));
        } catch (Throwable t) {
            log("no picture for " + GoogleAuthenticator.redact(account) + " (" + t + ")");
        }
    }

    /** Keep {@code url} as the account's picture if none is on disk yet. The
     *  sign-in window finds the picture only while its session is open, and not
     *  always, so the launcher's account button could stay a letter; an app's
     *  Google sign-in later carries the same picture in its ID token (the
     *  `picture` claim). Off the caller's thread; never throws. */
    public static void saveIfMissing(final String account, final String url) {
        if (account == null || url == null || file(account).isFile()) return;
        Thread t = new Thread(() -> save(account, url), "gms-account-photo");
        t.setDaemon(true);
        t.start();
    }

    public static void delete(String account) {
        if (account == null) return;
        try { file(account).delete(); } catch (Throwable ignored) {}
        try { GmsStore.putAccountData(account, URL_KEY, null); } catch (Throwable ignored) {}
    }

    /** https://lh<n>.googleusercontent.com/... and nothing else. */
    static boolean isGooglePhotoUrl(String url) {
        if (!url.startsWith("https://lh")) return false;
        int slash = url.indexOf('/', 8);
        if (slash < 0) return false;
        String host = url.substring(8, slash);
        return host.endsWith(".googleusercontent.com") && host.indexOf('@') < 0 && host.indexOf(':') < 0;
    }

    /** The URL at SIZE x SIZE, square: Google's picture URLs carry their size
     *  as `=s48-c` at the end or as a `/s48-c/` path segment. */
    static String sized(String url) {
        String s = "s" + SIZE + "-c";
        String out = url.replaceFirst("=s\\d+(-[a-z0-9-]*)?$", "=" + s);
        if (!out.equals(url)) return out;
        return url.replaceFirst("/s\\d+(-[a-z0-9-]*)?/([^/]+)$", "/" + s + "/$2");
    }

    /** PNG, JPEG or GIF, by their first bytes. */
    static boolean isImage(byte[] b) {
        if (b.length < 4) return false;
        boolean png = (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpeg = (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8;
        boolean gif = b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        return png || jpeg || gif;
    }

    private static void log(String m) {
        try { com.macapk.loader.NativeLog.i("Mac.apk.GMS", "photo: " + m); }
        catch (Throwable t) { System.err.println("[photo] " + m); }
    }
}
