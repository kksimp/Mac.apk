// com.macapk.gms.GmsCli -- drive the GMS layer from a shell, with no APK, no
// window, no applock and no runtime binary.
//
// WHY A SEPARATE ENTRY POINT. Every part of this layer is testable without a
// guest app: checkin needs no account, auth needs no app, and a licence check
// needs only a package name and a version code. Making the first proof of the
// vertical go through "launch an APK and watch a log" would couple three
// independent failure domains and make a wire-format bug look like a rendering
// bug. This runs the SAME classes the runtime runs, on the JVM, in a second.
//
// ⚠ TOKENS ARE NEVER PRINTED IN FULL. Every command that touches one prints a
// short prefix and a length. A terminal scrollback gets pasted into bug reports;
// a googleplay-scoped bearer token in one is a live credential.
//
// RUN: debug/gms.sh <command> [args]
package com.macapk.gms;

public final class GmsCli {

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * The real entry point, returning an exit code instead of calling
     * System.exit. The runtime's `--gms` mode calls THIS over JNI: it has an
     * AppKit run loop and WebView XPC children behind it, so it wants to decide
     * for itself how to leave the process.
     *
     * EXIT CODES ARE A CONTRACT (the launcher's Accounts panel reads them):
     *   0  success
     *   1  failure -- an `ERROR\t<message>` line was printed
     *   2  the user cancelled, or the arguments were wrong
     */
    public static int run(String[] args) {
        if (args.length == 0) { usage(); return 2; }
        // Before any HTTPS call: Boot.run does this for an app's process, but
        // `--gms` boots no app, and without the roots Windows trusts every
        // call to Google behind a filtering proxy failed with "PKIX path building failed".
        try { com.macapk.loader.HostTrust.install(); } catch (Throwable ignored) {}
        String cmd = args[0];
        try {
            switch (cmd) {
                case "status":   status();  return 0;
                case "selftest": return selfTest();
                case "checkin":  checkin();  return 0;
                case "signin-url": signinUrl(); return 0;
                case "signin":   return signin(arg(args, 1, "<oauth_token>"));
                case "signin-window": return signinWindow();
                case "signin-master": return signinMaster();
                case "accounts": return accounts();
                case "gcmtoken": return gcmtoken(arg(args, 1, "<package>"),
                                                 arg(args, 2, "<senderId>"),
                                                 args.length > 3 ? args[3] : null);
                case "gcmseam": return gcmseam(arg(args, 1, "<package>"),
                                               arg(args, 2, "<senderId>"));
                case "mcslogin": return mcslogin();
                case "token":    return token(arg(args, 1, "<scope>"));
                case "idtoken":  return idtoken(arg(args, 1, "<package>"),
                                                arg(args, 2, "<certSha1>"),
                                                arg(args, 3, "<serverClientId>"),
                                                args.length > 4 && "--consent".equals(args[4]));
                case "license":  return license(arg(args, 1, "<package>"),
                                                Integer.parseInt(arg(args, 2, "<versionCode>")));
                case "signout":  return signout(args.length > 1 ? args[1] : null);
                default: usage(); return 2;
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
            return 2;
        } catch (Throwable t) {
            System.out.println("ERROR\t" + t);
            t.printStackTrace();
            return 1;
        }
    }

    private static void usage() {
        System.err.println(
            "usage: debug/gms.sh <command>\n"
          + "  status                  what the store knows (secrets redacted)\n"
          + "  selftest                platform certificate + protobuf codec self-check\n"
          + "  checkin                 register this device -> android_id (no account needed)\n"
          + "  signin-url              print the Google sign-in URL and what to copy back\n"
          + "  signin-window           open Google's sign-in page in a window (needs the runtime)\n"
          + "  accounts                machine-readable: ACCOUNT<tab>email<tab>services per line\n"
          + "  gcmtoken <pkg> <sender> [certSha1]   register for FCM push -> a real token\n"
          + "  mcslogin                connect+login to Google's MCS push server (framing+auth test)\n"
          + "  signin <oauth_token>    exchange a WebView oauth_token for a master token\n"
          + "  signin-master           add an account from its e-mail and master token, read as two\n"
          + "                          lines from stdin (never arguments: a command line is visible\n"
          + "                          to every process); for tests and for moving an account\n"
          + "  token <scope>           fetch/print a scoped bearer token (redacted)\n"
          + "  idtoken <pkg> <certSha1> <serverClientId> [--consent]\n"
          + "                          ask /auth for a Google sign-in ID token for <pkg> on its OWN\n"
          + "                          identity. Without --consent it sends no has_permission, so\n"
          + "                          Google stores no grant and answers what it would need.\n"
          + "                          Prints response KEYS and JWT claim NAMES only, never values.\n"
          + "  license <pkg> <vc>      run a Play-Licensing check for a package\n"
          + "  signout                 forget the account and every cached token\n"
          + "\n"
          + "The layer's flag (MACAPK_GMS_PRESENT) gates what GUESTS see; these\n"
          + "commands work regardless, because they are the operator's own tools.");
    }

    // ===================================================================

    private static void status() {
        System.out.println("gms dir        : " + GmsStore.dir());
        long id = GmsStore.androidId();
        System.out.println("android_id     : " + (id == 0 ? "(not registered)"
                : Long.toHexString(id) + "  (unsigned " + Long.toUnsignedString(id) + ")"));
        System.out.println("security token : " + (GmsStore.securityToken() != 0 ? "present" : "-"));
        long last = GmsStore.lastCheckinMs();
        System.out.println("last checkin   : " + (last == 0 ? "-" : new java.util.Date(last)));
        java.util.List<String> accounts = GmsStore.accountNames();
        System.out.println("accounts       : " + (accounts.isEmpty() ? "(none)" : ""));
        for (String a : accounts) {
            System.out.println("  " + GoogleAuthenticator.redact(a)
                + "   master=" + redact(GmsStore.masterToken(a))
                + "  services=" + GmsStore.accountData(a, "services"));
            for (String scope : GmsStore.scopesFor(a)) {
                System.out.println("      scope " + scope);
                System.out.println("        token " + redact(GmsStore.authTokenRaw(a, scope))
                    + (GmsStore.authToken(a, scope) == null ? "  [EXPIRED]" : "  [valid]"));
            }
        }
        System.out.println("identity we present to Google:");
        System.out.println("  app        = " + envOr("MACAPK_GMS_AUTH_APP", "com.android.vending"));
        System.out.println("  client_sig = " + envOr("MACAPK_GMS_AUTH_SIG",
                                                     GoogleAuthClient.platformSigSha1())
                                             + "   (our platform cert, never Google's)");
        System.out.println("layer flag     : MACAPK_GMS_PRESENT="
            + (System.getenv("MACAPK_GMS_PRESENT") == null ? "(unset -> OFF)"
                                                           : System.getenv("MACAPK_GMS_PRESENT")));
    }

    private static int selfTest() {
        System.out.println("platform certificate: " + PlatformSignature.selfTest());
        System.out.println("client_sig (SHA-1)  : " + GoogleAuthClient.platformSigSha1());

        // Protobuf round-trip: a nested tree with each wire type, read back by
        // path(). If this is wrong every wire format above it is wrong too, and
        // the failure would otherwise surface as an unexplained HTTP 400.
        byte[] inner = Protobuf.writer().string(3, "hello").int32(1, 42).toByteArray();
        byte[] outer = Protobuf.writer()
                .bytes(7, inner)
                .fixed64(9, 0x0123456789ABCDEFL)
                .int64(2, 300)
                .toByteArray();
        String s = Protobuf.pathString(outer, 7, 3);
        long fixed = Protobuf.scalar(outer, 9, -1);
        long varint = Protobuf.scalar(outer, 2, -1);
        boolean ok = "hello".equals(s) && fixed == 0x0123456789ABCDEFL && varint == 300;
        System.out.println("protobuf codec      : " + (ok ? "OK" : "FAIL")
            + "  (path->\"" + s + "\", fixed64=" + Long.toHexString(fixed)
            + ", varint=" + varint + ")");

        // The URL encoder has to match Android's Uri.encode, not URLEncoder --
        // the scope string is the case that matters.
        String enc = GoogleAuthClient.uriEncode(GoogleAuthenticator.SCOPE_GOOGLEPLAY);
        String want = "oauth2%3Ahttps%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgoogleplay";
        System.out.println("uri encoder         : " + (want.equals(enc) ? "OK" : "FAIL got " + enc));

        // The response parser must split on the FIRST '=' only: base64 tokens
        // carry '=' padding, and splitting on every one truncates them.
        java.util.Map<String, String> p = GoogleAuthClient.parse("Auth=abc==\nExpiry=123\nnoequals\n");
        boolean parseOk = "abc==".equals(p.get("Auth")) && "123".equals(p.get("Expiry"))
                       && p.size() == 2;
        System.out.println("auth parser         : " + (parseOk ? "OK" : "FAIL " + p));

        return (!ok || !parseOk || !want.equals(enc)) ? 1 : 0;
    }

    private static void checkin() throws Exception {
        System.out.println("checking in as: " + android.os.Build.FINGERPRINT);
        long id = CheckinClient.checkin();
        System.out.println("OK android_id = " + Long.toHexString(id));
    }

    /**
     * The one step Mac.apk cannot take for the user.
     *
     * Signing in means typing a Google password, and that is the user's to type,
     * into Google's own page, in a browser they control. Mac.apk never sees the
     * password and never wants to: what comes back out of the flow is an
     * `oauth_token` cookie, which is all the token exchange needs.
     */
    private static void signinUrl() {
        System.out.println("1. Open this URL in a browser and sign in to the Google account");
        System.out.println("   that owns the apps you want licensed:\n");
        System.out.println("   https://accounts.google.com/EmbeddedSetup\n");
        System.out.println("2. When the flow finishes (the page goes blank or says you can close it),");
        System.out.println("   read the value of the `oauth_token` cookie for accounts.google.com.");
        System.out.println("   In Chrome: DevTools (Cmd-Option-I) -> Application -> Cookies ->");
        System.out.println("   https://accounts.google.com -> the row named `oauth_token`.\n");
        System.out.println("3. Hand it back:  debug/gms.sh signin '<that value>'\n");
        System.out.println("Notes:");
        System.out.println("  * The token is short-lived -- use it within a few minutes.");
        System.out.println("  * It is exchanged ONCE for a long-lived master token and is then");
        System.out.println("    useless; the master token is stored 0600 under");
        System.out.println("    " + GmsStore.dir());
        System.out.println("  * Mac.apk never sees, stores or transmits your password.");
        System.out.println("  * `debug/gms.sh signout` removes the account and every cached token.");
    }

    private static int signin(String oauthToken) {
        // Accept either the bare token or the whole `oauth_token=...` cookie
        // line, because copying a cookie out of a browser gives you the latter
        // and silently signing in with the wrong string is a confusing failure.
        String t = oauthToken.trim();
        if (t.startsWith("oauth_token=")) t = t.substring("oauth_token=".length());
        String email = GoogleAuthClient.exchangeOAuthToken(t);
        if (email == null) {
            System.out.println("ERROR\t" + GoogleAuthClient.lastError());
            return 1;
        }
        System.out.println("SIGNED_IN\t" + email);
        System.out.println("services : " + GmsStore.accountData(email, "services"));
        return 0;
    }

    /**
     * An account whose master token the caller already holds (a test account, or
     * one moved from another device): what `signin` stores after its exchange,
     * without the browser half. The token is checked with Google first, so a
     * wrong or revoked one is an error rather than a stored account that fails
     * later. Read from stdin so it never appears in a process list.
     */
    private static int signinMaster() throws java.io.IOException {
        java.io.BufferedReader in = new java.io.BufferedReader(
                new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
        String email = in.readLine(), master = in.readLine();
        if (email == null || master == null || email.trim().isEmpty() || master.trim().isEmpty()) {
            System.out.println("ERROR\texpected two lines on stdin: the e-mail address, then the master token");
            return 2;
        }
        String signedIn = GoogleAuthClient.signInWithMasterToken(email.trim(), master.trim());
        if (signedIn == null) {
            System.out.println("ERROR\t" + GoogleAuthClient.lastError());
            return 1;
        }
        System.out.println("SIGNED_IN\t" + GoogleAuthenticator.redact(signedIn));
        return 0;
    }

    /**
     * Open Google's own sign-in page in a window and complete the exchange.
     * This is what the launcher's Accounts panel spawns, and it is the path a
     * normal user takes -- nobody should have to paste a cookie out of DevTools.
     *
     * The password is typed into Google's page inside a WKWebView that this
     * process does not read; all we take out of it is the oauth_token cookie the
     * finished flow leaves behind. See runtime/src/gms_signin.mm.
     */
    private static int signinWindow() {
        if (!NativeSignIn.available()) {
            System.out.println("ERROR\tno window is available in this process"
                + " -- run this through the Mac.apk runtime"
                + " (macapk-runtime --gms signin-window), not a bare JVM");
            return 1;
        }
        String email = NativeSignIn.signInInteractive();
        if (email == null) {
            // The window's own failure (a WebView2 or page error code) first:
            // with one there was no token, so no exchange ran.
            String why = NativeSignIn.lastWindowError();
            if (why == null) why = GoogleAuthClient.lastError();
            if (why == null) return 2;                    // the user closed the window
            System.out.println("ERROR\t" + why);
            return 1;
        }
        System.out.println("SIGNED_IN\t" + email);
        // A device that has an account but has never registered cannot make a
        // licence call, and the failure would surface much later as a confusing
        // "could not contact server". Register now, while someone is watching.
        try { CheckinClient.ensureAndroidId(); } catch (Throwable ignored) {}
        return 0;
    }

    /** Prove the MCS receive channel's hard part: connect to mtalk.google.com,
     *  send the LoginRequest authed with the checkin creds, and confirm Google
     *  answers a LoginResponse with no error. That validates the TLS socket, the
     *  frame encoding, and the auth -- everything except delivering an actual
     *  push into a guest app (which needs a bootable FCM app). */
    private static int mcslogin() {
        System.out.println("connecting to mtalk.google.com and logging in...");
        String err = com.macapk.gms.McsClient.testLogin();
        if (err == null) {
            System.out.println("MCS LOGIN OK -- Google accepted the checkin credentials over the"
                + " persistent channel; the socket, framing and auth all work");
            return 0;
        }
        System.out.println("ERROR\tMCS login failed: " + err);
        return 1;
    }

    /** Drive the FCM register SEAM end to end without a guest app: send the SDK's
     *  exact Message to PushRegisterService over a Messenger and confirm the
     *  reply carries a real registration_id. This proves the bound-Messenger
     *  round-trip + doRegister + GcmRegisterClient, the piece a guest app's
     *  getToken() rides on but which a full app launch cannot easily reach. */
    private static int gcmseam(String pkg, String sender) throws Exception {
        com.macapk.gms.PushRegisterService svc = new com.macapk.gms.PushRegisterService();
        svc.onCreate();
        android.os.IBinder binder = svc.onBind(
                new android.content.Intent("com.google.android.c2dm.intent.REGISTER"));
        if (binder == null) { System.out.println("ERROR\tonBind returned null"); return 1; }
        android.os.Messenger service = new android.os.Messenger(binder);

        android.os.HandlerThread ht = new android.os.HandlerThread("probe-reply");
        ht.start();
        final android.os.Bundle[] got = new android.os.Bundle[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        android.os.Messenger client = new android.os.Messenger(
                new android.os.Handler(ht.getLooper(), m -> {
                    got[0] = m.getData();
                    latch.countDown();
                    return true;
                }));

        android.os.Message req = android.os.Message.obtain();
        req.what = 1; req.arg1 = 4242; req.replyTo = client;
        android.os.Bundle data = new android.os.Bundle();
        data.putString("pkg", pkg);
        android.os.Bundle inner = new android.os.Bundle();
        inner.putString("sender", sender);
        inner.putString("X-scope", "*");
        inner.putString("X-subtype", sender);
        data.putBundle("data", inner);
        req.setData(data);

        System.out.println("sending REGISTER Message{what=1,arg1=4242} to PushRegisterService...");
        service.send(req);

        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            System.out.println("ERROR\tno reply within 30s -- the seam is broken (this is the hang shape)");
            return 1;
        }
        android.os.Bundle outer = got[0];
        android.os.Bundle result = (outer == null) ? null : outer.getBundle("data");
        String regId = (result == null) ? null : result.getString("registration_id");
        String error = (result == null) ? null : result.getString("error");
        System.out.println("reply received: registration_id=" + redact(regId)
                + "  error=" + error);
        svc.onDestroy();
        if (regId != null && !regId.isEmpty()) {
            System.out.println("SEAM PASS: the bound-Messenger round-trip delivered a real token");
            return 0;
        }
        System.out.println("SEAM " + (error != null ? "returned error " + error
                : "FAIL: no token and no error"));
        return 1;
    }

    /** Register an app for FCM push and print the resulting token (redacted).
     *  A wire test for the registration half: needs only the checkin creds, no
     *  account. `certSha1` is the app's signing cert SHA-1; omitted, we send
     *  none (Google may or may not require it -- the response says which). */
    private static int gcmtoken(String pkg, String sender, String certSha1) {
        com.macapk.gms.GcmRegisterClient.Result r =
                com.macapk.gms.GcmRegisterClient.register(pkg, sender, certSha1, 1, 30);
        System.out.println("package : " + pkg + "  sender=" + sender
                + (certSha1 != null ? "  cert=" + certSha1 : "  (no cert)"));
        System.out.println("result  : " + r);
        if (r.ok()) System.out.println("token   : " + redact(r.token));
        return r.ok() ? 0 : 1;
    }

    /** One line per account, tab-separated, for the launcher to parse. Zero
     *  lines and exit 0 is the correct answer for "nobody is signed in" -- an
     *  error code there would make an ordinary state look like a malfunction. */
    private static int accounts() {
        for (String a : GmsStore.accountNames()) {
            String services = GmsStore.accountData(a, "services");
            System.out.println("ACCOUNT\t" + a + "\t" + (services == null ? "" : services));
        }
        // Reconcile the launcher's cheap "signed in?" sentinel while we are here.
        // A pure read never calls save(), so a store written by a version that
        // predates the sentinel would otherwise leave it stale until the next
        // mutation; the launcher's panel-open probe backfills it this way.
        GmsStore.syncAccountSentinel();
        return 0;
    }

    private static int token(String scope) {
        String account = GmsStore.primaryAccount();
        if (account == null) {
            System.out.println("ERROR\tno account -- sign in first");
            return 1;
        }
        String tok = GoogleAuthClient.fetchScopedToken(account, scope);
        if (tok == null) {
            System.out.println("ERROR\t" + GoogleAuthClient.lastError());
            return 1;
        }
        System.out.println("OK scope=" + scope + " token=" + redact(tok));
        return 0;
    }

    private static int idtoken(String pkg, String certSha1, String clientId, boolean consent) {
        String account = GmsStore.primaryAccount();
        if (account == null) {
            System.out.println("ERROR\tno account -- sign in first");
            return 1;
        }
        java.util.Map<String, String> r = GoogleAuthClient.fetchAppToken(account,
                "audience:server:client_id:" + clientId, pkg, certSha1, true, true, consent, null);
        System.out.println("app       : " + pkg + " client_sig=" + certSha1 + " consent=" + consent);
        if (r == null) {
            System.out.println("ERROR\t" + GoogleAuthClient.lastError());
            return 1;
        }
        System.out.println("keys      : " + new java.util.TreeSet<>(r.keySet()));
        System.out.println("issueAdvice: " + r.get("issueAdvice"));
        String auth = r.get("Auth");
        if (auth == null) {
            System.out.println("NO-TOKEN  (Google wants: " + r.get("issueAdvice") + ")");
            return 1;
        }
        String[] parts = auth.split("\\.");
        String claims = "(not a JWT)";
        if (parts.length >= 2) {
            try {
                String payload = new String(java.util.Base64.getUrlDecoder().decode(pad(parts[1])), "UTF-8");
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([a-z_]+)\"\\s*:").matcher(payload);
                while (m.find()) names.add(m.group(1));
                claims = names.toString();
            } catch (Throwable t) { claims = "(undecodable)"; }
        }
        System.out.println("OK id token " + auth.length() + " chars, claim names " + claims);
        return 0;
    }

    private static int license(String pkg, int versionCode) {
        PlayLicenseClient.Result r = PlayLicenseClient.check(pkg, versionCode);
        System.out.println("package   : " + pkg + " versionCode=" + versionCode);
        System.out.println("result    : " + r);
        if (r.jwt != null) {
            System.out.println("jwt       : " + r.jwt.length() + " chars, "
                + describeJwt(r.jwt));
        }
        return r.responseCode == PlayLicenseClient.LICENSED ? 0 : 1;
    }

    /** @param which one account, or null for all of them. */
    private static int signout(String which) {
        java.util.List<String> targets = (which == null || which.isEmpty())
                ? GmsStore.accountNames()
                : java.util.Collections.singletonList(which);
        for (String a : targets) {
            GmsStore.removeAccount(a);
            System.out.println("removed " + GoogleAuthenticator.redact(a));
        }
        // The checkin identity is deliberately KEPT. Signing out of an account
        // is not the same as un-registering the device, and a device that
        // re-registers gets a NEW android_id -- which to Google looks like a
        // different device every time someone signs out and back in.
        System.out.println("done (this device stays registered)");
        return 0;
    }

    // ===================================================================

    /** Decode a JWS's header and payload for display WITHOUT verifying it. We do
     *  not verify because we cannot and must not: the signature is Google's, the
     *  app holds the public key, and our job is to relay the token untouched.
     *  This is a human-readable echo, never a check. */
    private static String describeJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return "(not a three-part JWS)";
            java.util.Base64.Decoder d = java.util.Base64.getUrlDecoder();
            String header  = new String(d.decode(pad(parts[0])), "UTF-8");
            String payload = new String(d.decode(pad(parts[1])), "UTF-8");
            return "header=" + header + " payload=" + payload;
        } catch (Throwable t) {
            return "(undecodable: " + t + ")";
        }
    }

    private static String pad(String b64) {
        int rem = b64.length() % 4;
        return rem == 0 ? b64 : b64 + "====".substring(rem);
    }

    private static String redact(String secret) {
        if (secret == null) return "(none)";
        if (secret.length() <= 8) return "***";
        return secret.substring(0, 6) + "..." + "[" + secret.length() + " chars]";
    }

    private static String arg(String[] a, int i, String what) {
        if (a.length <= i) throw new IllegalArgumentException("missing " + what);
        return a[i];
    }

    private static String envOr(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
