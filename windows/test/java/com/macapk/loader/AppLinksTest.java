// AppLinksTest -- Android App Link verification without a fetch at start
// (com.macapk.loader.AppLinks): a domain is checked only when a sign-in's
// redirect needs it, one domain at a time, and its verdict is cached per APK.
//
//   java -cp android-framework.jar;macapk-loader.jar;<classes> com.macapk.loader.AppLinksTest <scratch dir>
//
// No network: the fetch, the clock and the cache folder are the class's test
// seams. Checks: DomainVerificationManager's answer (stateMapFor) fetches
// nothing and reports NONE until a domain is checked; a sign-in check fetches
// only the domain covering its host (a wildcard host against its parent);
// the verdict is reused from memory and from disk in a "new process"; a
// verdict lasts VERIFY_TTL_MS, an unreachable site FAIL_TTL_MS; a new APK
// (versionCode / update time / certificate) drops the file; assetlinks.json
// must name the package and one of its certificates.
// Prints PASS/FAIL per check and "ALL PASSED" at the end; exits 1 on a failure.
package com.macapk.loader;

import android.content.pm.verify.domain.DomainVerificationUserState;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AppLinksTest {
    static int failures;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok) failures++;
    }

    static final String PKG = "com.example.maps";
    static final String FP = "AA:BB:CC";
    static final Set<String> FPS = new HashSet<>(Collections.singletonList(FP));

    static String grantFor(String pkg, String fp) {
        return "[{\"relation\":[\"" + AppLinks.RELATION + "\"],\"target\":{\"namespace\":\"android_app\","
                + "\"package_name\":\"" + pkg + "\",\"sha256_cert_fingerprints\":[\"" + fp + "\"]}}]";
    }

    static final List<String> fetched = Collections.synchronizedList(new ArrayList<>());
    static volatile Map<String, String> sites;  // domain -> body; absent = unreachable (null)
    static long now = 1_000_000_000_000L;

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "applinks-test");
        dir.mkdirs();
        for (File f : dir.listFiles()) f.delete();
        AppLinks.cacheDirOverride = dir;
        AppLinks.clock = () -> now;
        AppLinks.fetcher = d -> {
            fetched.add(d);
            Map<String, String> s = sites;
            return s.containsKey(d) ? s.get(d) : null;
        };
        sites = new java.util.HashMap<>();
        sites.put("auth.example.com", grantFor(PKG, FP));
        sites.put("example.com", grantFor(PKG, FP));
        sites.put("other.example.org", grantFor("com.someone.else", FP));
        sites.put("maps.example.de", AppLinks.NO_FILE);  // a 404 or a redirect

        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < 300; i++) hosts.add("www.example.c" + i);
        hosts.addAll(Arrays.asList("auth.example.com", "*.example.com", "other.example.org", "maps.example.de",
                "down.example.net"));
        AppLinks.initForTest(PKG, hosts, FPS, "7/123/" + FP);

        // DomainVerificationManager at start: every host NONE, nothing fetched.
        Map<String, Integer> st = AppLinks.stateMapFor(PKG);
        check("stateMapFor lists every declared host", st != null && st.size() == hosts.size());
        check("stateMapFor: unchecked hosts are NONE",
                st != null && st.values().stream().allMatch(v -> v == DomainVerificationUserState.DOMAIN_STATE_NONE));
        check("stateMapFor fetches nothing", fetched.isEmpty());
        check("stateMapFor: another package is null", AppLinks.stateMapFor("com.other") == null);
        check("no cache file before a check", !AppLinks.cacheFile(PKG).exists());

        // A sign-in redirect to auth.example.com: only the covering domains.
        check("auth.example.com verified", AppLinks.hostVerifiedLazily("auth.example.com", 5000));
        check("only auth.example.com fetched", fetched.equals(Collections.singletonList("auth.example.com")));
        fetched.clear();
        check("again: from memory", AppLinks.hostVerifiedLazily("auth.example.com", 5000) && fetched.isEmpty());
        check("wildcard host checks the parent domain",
                AppLinks.hostVerifiedLazily("login.example.com", 5000)
                        && fetched.equals(Collections.singletonList("example.com")));
        fetched.clear();
        check("undeclared host: not verified, nothing fetched",
                !AppLinks.hostVerifiedLazily("evil.example.net", 5000) && fetched.isEmpty());
        check("another package's statement does not verify",
                !AppLinks.hostVerifiedLazily("other.example.org", 5000));
        check("no assetlinks.json (404/redirect) does not verify", !AppLinks.hostVerifiedLazily("maps.example.de", 5000));
        check("unreachable site does not verify", !AppLinks.hostVerifiedLazily("down.example.net", 5000));

        st = AppLinks.stateMapFor(PKG);
        check("stateMapFor: checked domain VERIFIED",
                st.get("auth.example.com") == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
                        && st.get("*.example.com") == DomainVerificationUserState.DOMAIN_STATE_VERIFIED);
        check("stateMapFor: refused domain NONE",
                st.get("other.example.org") == DomainVerificationUserState.DOMAIN_STATE_NONE
                        && st.get("www.example.c0") == DomainVerificationUserState.DOMAIN_STATE_NONE);

        File cf = AppLinks.cacheFile(PKG);
        check("cache file written", cf.isFile());
        System.out.println("cache file: " + cf.length() + " bytes");
        check("cache file small (< 1 KB)", cf.length() < 1024);

        // A new process with the same APK: verdicts come from disk.
        fetched.clear();
        AppLinks.initForTest(PKG, hosts, FPS, "7/123/" + FP);
        check("next start: verified from disk, no fetch",
                AppLinks.hostVerifiedLazily("auth.example.com", 5000) && fetched.isEmpty());
        check("next start: refusal from disk, no fetch",
                !AppLinks.hostVerifiedLazily("maps.example.de", 5000) && fetched.isEmpty());

        // An unreachable site is asked again after FAIL_TTL_MS, not before.
        now += AppLinks.FAIL_TTL_MS - 1;
        check("unreachable: not retried within the hour",
                !AppLinks.hostVerifiedLazily("down.example.net", 5000) && fetched.isEmpty());
        now += 2;
        sites.put("down.example.net", grantFor(PKG, FP));
        check("unreachable: retried after the hour, now verified",
                AppLinks.hostVerifiedLazily("down.example.net", 5000)
                        && fetched.equals(Collections.singletonList("down.example.net")));
        fetched.clear();

        // A verdict lasts VERIFY_TTL_MS.
        now += AppLinks.VERIFY_TTL_MS + 1;
        sites.put("auth.example.com", AppLinks.NO_FILE);
        check("after the TTL the domain is checked again (and now refused)",
                !AppLinks.verifyDomainNow("auth.example.com", 5000)
                        && fetched.equals(Collections.singletonList("auth.example.com")));
        fetched.clear();

        // An updated APK: the cache file is dropped.
        sites.put("auth.example.com", grantFor(PKG, FP));
        AppLinks.initForTest(PKG, hosts, FPS, "8/456/" + FP);
        check("new APK: nothing known", AppLinks.stateMapFor(PKG).values().stream()
                .allMatch(v -> v == DomainVerificationUserState.DOMAIN_STATE_NONE));
        check("new APK: checked again",
                AppLinks.hostVerifiedLazily("auth.example.com", 5000)
                        && fetched.equals(Collections.singletonList("auth.example.com")));
        fetched.clear();

        // A different certificate does not match the statement.
        AppLinks.initForTest(PKG, hosts, new HashSet<>(Collections.singletonList("DD:EE")), "8/456/DD:EE");
        check("other certificate: not verified", !AppLinks.hostVerifiedLazily("auth.example.com", 5000));
        // No certificate at all: refused without a fetch.
        fetched.clear();
        AppLinks.initForTest(PKG, hosts, Collections.<String>emptySet(), "8/456/");
        check("no certificate: refused without a fetch",
                !AppLinks.hostVerifiedLazily("example.com", 5000) && fetched.isEmpty());

        // The sign-in decision asks only for the redirect's host.
        List<String> asked = new ArrayList<>();
        String p = AppLinks.signInReturnPrefix(
                "https://idp.example/authorize?redirect_uri=https%3A%2F%2Fauth.example.com%2Fcb%3Fx%3D1",
                h -> { asked.add(h); return true; }, PKG, null);
        check("sign-in: return prefix of a verified redirect", "https://auth.example.com/cb".equals(p));
        check("sign-in: asks about the redirect host only", asked.equals(Collections.singletonList("auth.example.com")));
        check("sign-in: unverified redirect goes to the browser", AppLinks.signInReturnPrefix(
                "https://idp.example/authorize?redirect_uri=https%3A%2F%2Fauth.example.com%2Fcb",
                h -> false, PKG, null) == null);
        check("sign-in: no redirect_uri asks nothing", AppLinks.signInReturnPrefix(
                "https://idp.example/page", h -> { throw new AssertionError(); }, PKG, null) == null);

        // No declared host: DomainVerificationManager's null.
        AppLinks.initForTest(PKG, Collections.<String>emptyList(), FPS, "1/1/" + FP);
        check("no autoVerify hosts: stateMapFor null", AppLinks.stateMapFor(PKG) == null);

        if (failures == 0) {
            System.out.println("ALL PASSED");
        } else {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
    }
}
