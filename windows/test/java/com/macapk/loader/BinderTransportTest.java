// BinderTransportTest -- cross-process Binder on Windows (windows/host/binder_win.cpp
// under android.os.MacApkBinderTransport), run by winapk-binder-test.exe, which
// binds the natives and calls main. The test starts more copies of that
// program for its other processes.
//
// Transport, parent <-> "service" child:
//   transact and reply; the calling pid is the OS's; the calling uid comes from
//   the RECEIVER's resolver, never from the caller; oneway calls arrive, in
//   order; a binder passed both ways keeps its identity (a callback into the
//   caller while it waits, our own object coming home as itself, one proxy per
//   remote object); a Messenger across processes stamps the sender's uid on the
//   Message (Message.sendingUid) and its replyTo answers back, and a
//   PendingIntent in its Bundle keeps its creator's uid; file descriptors
//   cross as new fds of the same file; death
//   of the far process reaches linkToDeath, then isBinderAlive and transact.
// Broker, "client" child -> "broker" child (com.macapk.loader.SystemServer):
//   a process registering as, or calling as, a package it is not is refused;
//   the package of the process is what its command line (--apk) makes it; the
//   android:exported check of a cross-package resolve; the uid of a process is
//   its package's. The parent, which is no installed app, is refused too.
// Google accounts, "accounts" child with its own GmsStore folder: the broker's
//   account service mirrors GmsStore's accounts into its table (master token as
//   the password, the gaia id as GoogleUserId), sees an account another process
//   signs in after the service started (the Play Store's "Sign in" in its own
//   process, Settings' `--gms signin-window`), with or without being asked, and
//   drops a mirrored account once it is signed out; a row it did not mirror stays.
package com.macapk.loader;

import android.content.ComponentName;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.MacApkBinderTransport;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BinderTransportTest {
    static int failures;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failures++;
    }

    static int myPid() { return (int) ProcessHandle.current().pid(); }

    /** A file or folder of this process in the temp folder. Not createTempFile:
     *  that seeds SecureRandom, which under Wine reaches a function Wine lacks. */
    static File scratch(String name) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "winapk-binder-" + myPid());
        dir.mkdirs();
        dir.deleteOnExit();
        File f = new File(dir, name);
        if (name.indexOf('.') < 0) f.mkdirs();
        f.deleteOnExit();
        return f;
    }

    static List<String> self(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ProcessHandle.current().info().command().orElseThrow());
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(BinderTransportTest.class.getName());
        for (String a : args) cmd.add(a);
        return cmd;
    }

    static IBinder waitFor(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            IBinder b = MacApkBinderTransport.lookUp(name);
            if (b != null) return b;
            Thread.sleep(200);
        }
        return null;
    }

    public static void main(String[] args) throws Exception {
        int rc;
        if (args.length == 0) rc = parent();
        else if (args[0].equals("service")) rc = service(args[1]);
        else if (args[0].equals("broker")) rc = broker(args);
        else if (args[0].equals("client")) rc = client(args);
        else if (args[0].equals("accounts")) rc = accounts();
        else if (args[0].equals("gms-write")) rc = gmsWrite(args);
        else rc = 2;
        System.out.flush();
        System.exit(rc);
    }

    // ---- the transport ---------------------------------------------------------------

    static final int ECHO = 1, ONEWAY = 2, COUNT = 3, CALLBACK = 4, SAME = 5, FD = 6, DIE = 7, RESOLVER = 8,
            HOME = 9, MESSENGER = 10;

    /** A Handler on a new thread running its own Looper. */
    static Handler looperThread(String name, Handler.Callback cb) throws InterruptedException {
        final Handler[] h = new Handler[1];
        CountDownLatch ready = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            Looper.prepare();
            h[0] = new Handler(Looper.myLooper(), cb);
            ready.countDown();
            Looper.loop();
        }, name);
        t.setDaemon(true);
        t.start();
        ready.await(20, TimeUnit.SECONDS);
        return h[0];
    }

    /** The child's own object, handed out twice per SAME call. */
    static final Binder CHILD_OBJECT = new Binder();

    static int service(String name) throws Exception {
        final List<Integer> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        Binder svc = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                try {
                    switch (code) {
                        case ECHO:
                            reply.writeString(data.readString() + "!");
                            reply.writeInt(data.readInt() + 1);
                            reply.writeInt(Binder.getCallingPid());
                            reply.writeInt(Binder.getCallingUid());
                            return true;
                        case ONEWAY:
                            seen.add(data.readInt());
                            return true;
                        case COUNT: {
                            boolean inOrder = true;
                            synchronized (seen) {
                                for (int i = 0; i < seen.size(); i++) inOrder &= seen.get(i) == i;
                            }
                            reply.writeInt(seen.size());
                            reply.writeInt(inOrder ? 1 : 0);
                            return true;
                        }
                        case CALLBACK: {
                            IBinder cb = data.readStrongBinder();
                            Parcel d = Parcel.obtain(), r = Parcel.obtain();
                            d.writeInt(7);
                            cb.transact(10, d, r, 0);   // into the caller, while it waits on us
                            reply.writeInt(r.readInt());
                            reply.writeStrongBinder(cb);  // and back home
                            return true;
                        }
                        case SAME:
                            reply.writeStrongBinder(CHILD_OBJECT);
                            reply.writeStrongBinder(CHILD_OBJECT);
                            return true;
                        case MESSENGER: {
                            // As Google Play services' GCM registrar: the uid that
                            // sent the Message and the creator uid of the
                            // PendingIntent ("app") it carries, answered on replyTo.
                            Handler h = looperThread("child-messenger", msg -> {
                                try {
                                    Bundle b = msg.getData();
                                    b.setClassLoader(PendingIntent.class.getClassLoader());
                                    PendingIntent app = b.getParcelable("app");
                                    Message out = Message.obtain();
                                    out.what = msg.what;
                                    out.arg1 = msg.sendingUid;
                                    out.arg2 = app != null ? app.getCreatorUid() : -2;
                                    msg.replyTo.send(out);
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                                return true;
                            });
                            reply.writeStrongBinder(new Messenger(h).getBinder());
                            return true;
                        }
                        case HOME:
                            reply.writeInt(data.readStrongBinder() == CHILD_OBJECT ? 1 : 0);
                            return true;
                        case FD: {
                            ParcelFileDescriptor in = data.readFileDescriptor();
                            String text;
                            try (FileInputStream s = new FileInputStream(in.getFileDescriptor())) {
                                text = new String(s.readAllBytes(), StandardCharsets.UTF_8);
                            }
                            reply.writeString(text);
                            File mine = scratch("child.txt");
                            Files.writeString(mine.toPath(), "written by the child");
                            ParcelFileDescriptor out = ParcelFileDescriptor.open(mine,
                                    ParcelFileDescriptor.MODE_READ_ONLY);
                            reply.writeFileDescriptor(out.getFileDescriptor());
                            return true;
                        }
                        case RESOLVER:
                            final int uid = data.readInt();
                            MacApkBinderTransport.setUidResolver(pid -> uid);
                            return true;
                        case DIE:
                            Runtime.getRuntime().halt(0);
                            return true;
                        default:
                            return false;
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        MacApkBinderTransport.publish(name, svc);
        Thread.sleep(Long.MAX_VALUE);
        return 0;
    }

    static int parent() throws Exception {
        System.out.println("Win.apk cross-process Binder (named pipes)");
        String name = "winapk-test-" + myPid() + "-svc";
        Process child = new ProcessBuilder(self("service", name)).inheritIO().start();
        IBinder svc = waitFor(name);
        check("look up the published service", svc != null, "");
        if (svc == null) { child.destroy(); return 1; }

        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        d.writeString("hello");
        d.writeInt(41);
        boolean ok = svc.transact(ECHO, d, r, 0);
        String s = r.readString();
        int n = r.readInt(), pid = r.readInt(), uid = r.readInt();
        check("transact and reply", ok && "hello!".equals(s) && n == 42, s + " " + n);
        check("the calling pid is the caller's, from the OS", pid == myPid(), pid + " vs " + myPid());
        check("no resolver: the calling uid is nobody's (9999), not one the caller sent", uid == 9999, "" + uid);

        d = Parcel.obtain(); r = Parcel.obtain();
        d.writeInt(12345);
        svc.transact(RESOLVER, d, r, 0);
        d = Parcel.obtain(); r = Parcel.obtain();
        d.writeString("x"); d.writeInt(0);
        svc.transact(ECHO, d, r, 0);
        r.readString(); r.readInt(); r.readInt();
        uid = r.readInt();
        check("the calling uid is the receiver's answer for that pid", uid == 12345, "" + uid);

        for (int i = 0; i < 50; i++) {
            d = Parcel.obtain();
            d.writeInt(i);
            svc.transact(ONEWAY, d, null, IBinder.FLAG_ONEWAY);
        }
        // Oneway calls are ordered among themselves, not against a later
        // synchronous call, so COUNT may overtake the last ones: ask until all arrive.
        int count = 0, inOrder = 0;
        for (long until = System.nanoTime() + 5_000_000_000L; ; Thread.sleep(20)) {
            d = Parcel.obtain(); r = Parcel.obtain();
            svc.transact(COUNT, d, r, 0);
            count = r.readInt();
            inOrder = r.readInt();
            if (count >= 50 || System.nanoTime() > until) break;
        }
        check("50 oneway calls arrive, in order", count == 50 && inOrder == 1, count + " in order " + inOrder);

        final int[] cbPid = { -1 };
        Binder cb = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                cbPid[0] = Binder.getCallingPid();
                int x = data.readInt();
                reply.writeInt(x * x);
                return true;
            }
        };
        d = Parcel.obtain(); r = Parcel.obtain();
        d.writeStrongBinder(cb);
        svc.transact(CALLBACK, d, r, 0);
        int sq = r.readInt();
        IBinder back = r.readStrongBinder();
        check("a callback into the caller while it waits (nested call)", sq == 49, "" + sq);
        check("the callback's calling pid is the child's", cbPid[0] == child.pid(), cbPid[0] + " vs " + child.pid());
        check("our own binder comes home as the same object", back == cb, String.valueOf(back));

        d = Parcel.obtain(); r = Parcel.obtain();
        svc.transact(SAME, d, r, 0);
        IBinder a = r.readStrongBinder(), b = r.readStrongBinder();
        d = Parcel.obtain(); r = Parcel.obtain();
        svc.transact(SAME, d, r, 0);
        IBinder c = r.readStrongBinder();
        check("one proxy per remote object, within and across calls", a != null && a == b && a == c,
                a + " " + b + " " + c);
        check("a remote object is not taken for a local one", !(a instanceof Binder), String.valueOf(a));
        d = Parcel.obtain(); r = Parcel.obtain();
        d.writeStrongBinder(a);
        svc.transact(HOME, d, r, 0);
        check("a proxy sent back is the owner's own object there", r.readInt() == 1, "");

        // The child's resolver answers 12345 for us (RESOLVER above); ours is unset,
        // so the child's reply comes from nobody's uid (9999).
        d = Parcel.obtain(); r = Parcel.obtain();
        svc.transact(MESSENGER, d, r, 0);
        IBinder mb = r.readStrongBinder();
        final int[] got = { -1, -1, -1, -1 };
        CountDownLatch answered = new CountDownLatch(1);
        Handler mine = looperThread("parent-messenger", msg -> {
            got[0] = msg.what; got[1] = msg.arg1; got[2] = msg.arg2; got[3] = msg.sendingUid;
            answered.countDown();
            return true;
        });
        Message m = Message.obtain();
        m.what = 77;
        m.replyTo = new Messenger(mine);
        Bundle data = new Bundle();
        data.putParcelable("app", PendingIntent.getBroadcast(null, 0, new Intent("com.example.NOTHING"),
                PendingIntent.FLAG_IMMUTABLE));
        m.setData(data);
        boolean sent = false;
        if (mb != null) { new Messenger(mb).send(m); sent = true; }
        boolean replied = sent && answered.await(20, TimeUnit.SECONDS);
        check("a Message to a Messenger in another process is answered on its replyTo",
                replied && got[0] == 77, got[0] + "");
        check("the receiver sees the sender's uid in Message.sendingUid", got[1] == 12345, "" + got[1]);
        check("a PendingIntent in the Message keeps its creator's uid", got[2] == android.os.Process.myUid(),
                got[2] + " vs " + android.os.Process.myUid());
        check("the reply carries its own sender's uid", got[3] == 9999, "" + got[3]);

        File f = scratch("parent.txt");
        Files.writeString(f.toPath(), "written by the parent \u05e9\u05dc\u05d5\u05dd");
        ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        d = Parcel.obtain(); r = Parcel.obtain();
        d.writeFileDescriptor(pfd.getFileDescriptor());
        svc.transact(FD, d, r, 0);
        String readThere = r.readString();
        check("an fd crosses and is read in the other process", "written by the parent \u05e9\u05dc\u05d5\u05dd".equals(readThere),
                String.valueOf(readThere));
        ParcelFileDescriptor fromChild = r.readFileDescriptor();
        String readHere = null;
        if (fromChild != null) {
            try (FileInputStream in = new FileInputStream(fromChild.getFileDescriptor())) {
                readHere = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        check("an fd in a reply arrives as a new fd here", "written by the child".equals(readHere),
                String.valueOf(readHere));

        CountDownLatch died = new CountDownLatch(1);
        svc.linkToDeath(() -> { died.countDown(); }, 0);
        d = Parcel.obtain();
        svc.transact(DIE, d, null, IBinder.FLAG_ONEWAY);
        boolean heard = died.await(20, TimeUnit.SECONDS);
        child.waitFor(20, TimeUnit.SECONDS);
        check("linkToDeath hears the far process exit", heard, "");
        check("isBinderAlive is false after", !svc.isBinderAlive(), "");
        boolean deadThrown = false;
        try {
            svc.transact(ECHO, Parcel.obtain(), Parcel.obtain(), 0);
        } catch (DeadObjectException e) {
            deadThrown = true;
        }
        check("a call to a dead process throws DeadObjectException", deadThrown, "");

        brokerChecks();
        googleAccountChecks();
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        return failures == 0 ? 0 : 1;
    }

    // ---- the broker ------------------------------------------------------------------

    static final String GOOD = "com.example.good", VICTIM = "com.example.victim";

    static String manifest(String pkg) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"" + pkg + "\">\n"
            + "  <application>\n"
            + "    <service android:name=\"" + pkg + ".Exported\" android:exported=\"true\">\n"
            + "      <intent-filter><action android:name=\"com.example.BIND\"/></intent-filter>\n"
            + "    </service>\n"
            + "    <service android:name=\"" + pkg + ".Private\" android:exported=\"false\"/>\n"
            + "  </application>\n"
            + "</manifest>\n";
    }

    static void brokerChecks() throws Exception {
        File dir = scratch("broker");
        File root = new File(dir, "android");
        root.mkdirs();
        File good = new File(dir, "good.apk"), victim = new File(dir, "victim.apk");
        Files.writeString(good.toPath(), "not a real apk");
        Files.writeString(victim.toPath(), "not a real apk");
        Files.writeString(new File(dir, "good.xml").toPath(), manifest(GOOD));
        Files.writeString(new File(dir, "victim.xml").toPath(), manifest(VICTIM));
        String name = "winapk-test-" + myPid() + "-system";

        ProcessBuilder bp = new ProcessBuilder(self("broker", name, dir.getPath())).inheritIO();
        bp.environment().put("WINAPK_TEST_ANDROID_ROOT", root.getPath());
        Process broker = bp.start();
        IBinder sys = waitFor(name);
        check("the broker is published", sys != null, "");
        if (sys == null) { broker.destroy(); return; }

        // The parent is no installed app (its command line names no APK).
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeString(GOOD);
        d.writeTypedObject(new Intent().setComponent(new ComponentName(VICTIM, VICTIM + ".Exported")), 0);
        sys.transact(SystemServer.TX_RESOLVE, d, r, 0);
        boolean refused = false;
        try { r.readException(); } catch (SecurityException e) { refused = true; }
        check("a process that is no installed app is refused, whatever package it names", refused, "");

        ProcessBuilder cp = new ProcessBuilder(self("client", name, "--apk", good.getPath())).inheritIO();
        cp.environment().put("WINAPK_TEST_ANDROID_ROOT", root.getPath());
        Process client = cp.start();
        boolean done = client.waitFor(120, TimeUnit.SECONDS);
        int rc = done ? client.exitValue() : -1;
        check("the client's broker checks (above) passed", rc == 0, "exit " + rc);
        broker.destroy();
    }

    static int broker(String[] args) throws Exception {
        String name = args[1];
        File dir = new File(args[2]);
        SystemServer s = new SystemServer();
        java.lang.reflect.Field f = SystemServer.class.getDeclaredField("packages");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, SystemServer.PackageRecord> packages =
                (java.util.Map<String, SystemServer.PackageRecord>) f.get(s);
        packages.put(GOOD, new SystemServer.PackageRecord(GOOD, new File(dir, "good.apk").getPath(),
                Manifest.parse(new File(dir, "good.xml"), null)));
        packages.put(VICTIM, new SystemServer.PackageRecord(VICTIM, new File(dir, "victim.apk").getPath(),
                Manifest.parse(new File(dir, "victim.xml"), null)));
        MacApkBinderTransport.setUidResolver(s::uidOfPid);
        MacApkBinderTransport.publish(name, s.new Stub());
        Thread.sleep(Long.MAX_VALUE);
        return 0;
    }

    static boolean refused(IBinder sys, int code, Parcel d) throws Exception {
        Parcel r = Parcel.obtain();
        sys.transact(code, d, r, 0);
        try { r.readException(); } catch (SecurityException e) { return true; }
        return false;
    }

    static int resolve(IBinder sys, String caller, String pkg, String cls) throws Exception {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeString(caller);
        d.writeTypedObject(new Intent().setComponent(new ComponentName(pkg, cls)), 0);
        sys.transact(SystemServer.TX_RESOLVE, d, r, 0);
        r.readException();
        return r.readInt();
    }

    static int client(String[] args) throws Exception {
        System.out.println("broker checks (client pid " + myPid() + ", --apk " + args[3] + ")");
        IBinder sys = waitFor(args[1]);
        if (sys == null) { System.out.println("  [FAIL] no broker"); return 1; }

        Parcel d = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeString(VICTIM);
        d.writeStrongBinder(new Binder());
        d.writeString(new File(new File(args[3]).getParentFile(), "victim.apk").getPath());
        d.writeString(VICTIM);
        check("registering as another package is refused", refused(sys, SystemServer.TX_REGISTER, d), "");

        d = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeString(VICTIM);
        d.writeTypedObject(new Intent().setComponent(new ComponentName(VICTIM, VICTIM + ".Private")), 0);
        check("calling as another package is refused", refused(sys, SystemServer.TX_RESOLVE, d), "");

        d = Parcel.obtain();
        Parcel r = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeString(GOOD);
        d.writeStrongBinder(new Binder());
        d.writeString("C:\\somewhere\\else.apk");   // ignored: the broker uses the installed APK
        d.writeString(GOOD);
        sys.transact(SystemServer.TX_REGISTER, d, r, 0);
        boolean registered;
        int brokerPid = -1;
        try { r.readException(); brokerPid = r.readInt(); registered = true; }
        catch (SecurityException e) { registered = false; }
        check("registering as the package its command line names is accepted", registered && brokerPid > 0,
                "broker pid " + brokerPid);

        check("a non-exported service of another package does not resolve for us",
                resolve(sys, GOOD, VICTIM, VICTIM + ".Private") == SystemServer.RESOLVE_NOT_EXPORTED, "");
        check("an exported one does",
                resolve(sys, GOOD, VICTIM, VICTIM + ".Exported") == SystemServer.RESOLVE_OK, "");
        check("our own non-exported service does",
                resolve(sys, GOOD, GOOD, GOOD + ".Private") == SystemServer.RESOLVE_OK, "");

        d = Parcel.obtain();
        r = Parcel.obtain();
        d.writeInterfaceToken(SystemServer.DESCRIPTOR);
        d.writeInt(myPid());
        sys.transact(SystemServer.TX_UID_OF_PID, d, r, 0);
        r.readException();
        int uid = r.readInt();
        int expected = AppUids.uidFor(GOOD, null);
        check("our uid is our package's", uid >= AppUids.FIRST && uid == expected, uid + " vs " + expected);
        return failures == 0 ? 0 : 1;
    }

    // ---- Google accounts -------------------------------------------------------------

    static final String GOOGLE = "com.google";

    static void googleAccountChecks() throws Exception {
        File dir = scratch("accounts");
        File root = new File(dir, "android");
        root.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(self("accounts")).inheritIO();
        pb.environment().put("WINAPK_TEST_ANDROID_ROOT", root.getPath());
        pb.environment().put("MACAPK_GMS_DIR", new File(dir, "gms").getPath());
        pb.environment().remove("MACAPK_GMS_PRESENT");
        Process p = pb.start();
        boolean done = p.waitFor(180, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        int rc = done ? p.exitValue() : -1;
        check("the Google account checks (above) passed", rc == 0, "exit " + rc);
    }

    /** Another process writing GmsStore, as a sign-in in an app or in Settings does. */
    static boolean gmsWriter(String op, String email) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(self("gms-write", op, email)).inheritIO();
        Process p = pb.start();
        return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
    }

    static int gmsWrite(String[] args) {
        String email = args[2];
        if (args[1].equals("add")) {
            com.macapk.gms.GmsStore.putAccount(email, "master-" + email);
            com.macapk.gms.GmsStore.putAccountData(email, "accountId", "4242" + email.length());
        } else {
            com.macapk.gms.GmsStore.removeAccount(email);
        }
        return 0;
    }

    static int accounts() throws Exception {
        System.out.println("Google account checks (pid " + myPid() + ")");
        final String a = "first@example.com", b = "second@example.com", c = "third@example.com";
        final String other = "kept@example.com";
        check("first account written", gmsWriter("add", a), "");
        File storeFile = new File(new File(System.getenv("MACAPK_GMS_DIR")).getParentFile(), "accounts_ce.properties");
        com.macapk.accounts.AccountStore store = com.macapk.accounts.AccountStore.at(storeFile);
        // A com.google row the service did not mirror (added the way Play services' own
        // sign-in would add one): a sign-out in Win.apk must leave it alone.
        store.add(other, GOOGLE, "pw", null);
        AccountsService svc = new AccountsService(new SystemServer(), storeFile);
        check("an account signed in before the service started is mirrored", store.exists(a, GOOGLE), "");
        check("its master token is the row's password", ("master-" + a).equals(store.password(a, GOOGLE)),
                String.valueOf(store.password(a, GOOGLE) != null));
        check("its gaia id is GoogleUserId", ("4242" + a.length()).equals(store.userData(a, GOOGLE, "GoogleUserId")),
                String.valueOf(store.userData(a, GOOGLE, "GoogleUserId")));
        check("a second sync changes nothing", !svc.syncGoogleAccounts(), "");

        check("second account written by another process", gmsWriter("add", b), "");
        boolean changed = svc.syncGoogleAccounts();
        check("an account signed in by another process after the start is mirrored",
                changed && store.exists(b, GOOGLE), "changed=" + changed);
        check("the first account is still there", store.exists(a, GOOGLE), "");

        check("first account signed out by another process", gmsWriter("remove", a), "");
        changed = svc.syncGoogleAccounts();
        check("an account signed out elsewhere leaves the table", changed && !store.exists(a, GOOGLE),
                "changed=" + changed);
        check("an account Win.apk did not mirror stays", store.exists(other, GOOGLE), "");
        check("the other account stays", store.exists(b, GOOGLE), "");

        // Nobody asks: the service follows the store's file by itself.
        check("third account written by another process", gmsWriter("add", c), "");
        long deadline = System.currentTimeMillis() + 20_000;
        while (!store.exists(c, GOOGLE) && System.currentTimeMillis() < deadline) Thread.sleep(200);
        check("a sign-in elsewhere is mirrored without a query", store.exists(c, GOOGLE), "");
        return failures == 0 ? 0 : 1;
    }
}
