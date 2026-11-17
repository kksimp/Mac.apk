// FileSeparatorRewriteTest -- checks ClassFileRewriter's Pattern S: guest code
// that reads java.io.File.separatorChar / File.separator gets Android's '/'
// on a Windows host, while the host's own classes keep the JDK's values.
//
//   javac -cp <runtime>/lib/macapk-loader.jar -d out windows/test/java/FileSeparatorRewriteTest.java
//   java  -cp "<runtime>/lib/macapk-loader.jar;out" FileSeparatorRewriteTest [off]
//
// "off": the run has MACAPK_ANDROID_FILE_SEPARATOR=0 set, and guest code
// must then see the JDK's values. Exits 0 when every check passes.

import com.macapk.loader.ClassFileRewriter;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.regex.Pattern;

public final class FileSeparatorRewriteTest {

    /** Stands for app code: what ReLinker and friends do with the fields. */
    public static final class Guest {
        public static char separatorChar() { return File.separatorChar; }
        public static String separator() { return File.separator; }
        // ReLinker's ApkLibraryInstaller: the pattern Maccabi died on.
        public static boolean relinkerMatches(String entry, String lib) {
            Pattern p = Pattern.compile("lib" + File.separatorChar + "([^\\" + File.separatorChar + "]*)"
                    + File.separatorChar + lib);
            return p.matcher(entry).matches();
        }
        // A read after a switch, which a scan that stops at switches misses.
        public static String afterSwitch(int k) {
            String s;
            switch (k) {
                case 1: s = "a"; break;
                case 2: s = "b"; break;
                case 50: s = "c"; break;
                default: s = "d";
            }
            return s + File.separator;
        }
        // A path built with the separator still opens.
        public static String roundTrip(String dir) throws Exception {
            File f = new File(dir + File.separator + "sep-test.txt");
            Files.writeString(f.toPath(), "ok");
            String back = Files.readString(new File(dir + File.separatorChar + "sep-test.txt").toPath());
            f.delete();
            return back;
        }
        public static String pathSeparator() { return File.pathSeparator; }
    }

    /** Defines Guest from (rewritten) bytes in a loader of its own. */
    static final class GuestLoader extends ClassLoader {
        private final byte[] bytes;
        GuestLoader(byte[] bytes) {
            super(FileSeparatorRewriteTest.class.getClassLoader());
            this.bytes = bytes;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals(Guest.class.getName())) {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = defineClass(name, bytes, 0, bytes.length);
                    return c;
                }
            }
            return super.loadClass(name, resolve);
        }
    }

    static int failures = 0;

    static void check(String what, Object got, Object want) {
        boolean ok = want.equals(got);
        System.out.println((ok ? "ok   " : "FAIL ") + what + ": " + got + (ok ? "" : " (want " + want + ")"));
        if (!ok) failures++;
    }

    static Object call(Class<?> c, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = c.getMethod(name, types);
        return m.invoke(null, args);
    }

    public static void main(String[] args) throws Exception {
        boolean off = args.length > 0 && args[0].equals("off");
        boolean windows = System.mapLibraryName("x").endsWith(".dll");
        String res = Guest.class.getName().replace('.', '/') + ".class";
        byte[] raw;
        try (InputStream in = FileSeparatorRewriteTest.class.getClassLoader().getResourceAsStream(res)) {
            raw = in.readAllBytes();
        }
        ClassFileRewriter.Result r = ClassFileRewriter.maybeRewrite(raw.clone(), Guest.class.getName());
        boolean expectRewrite = windows && !off;
        System.out.println("host: " + (windows ? "Windows" : "not Windows") + ", File.separatorChar '"
                + File.separatorChar + "', switch " + (off ? "off" : "on") + "; rewriter: " + r.reason);
        check("rewritten", r.rewritten, expectRewrite);

        Class<?> g = new GuestLoader(r.bytes).loadClass(Guest.class.getName());
        String sep = expectRewrite ? "/" : File.separator;
        check("guest separatorChar", call(g, "separatorChar", new Class<?>[0]), sep.charAt(0));
        check("guest separator", call(g, "separator", new Class<?>[0]), sep);
        check("guest separator after a switch", call(g, "afterSwitch", new Class<?>[] {int.class}, 50), "c" + sep);
        check("guest pathSeparator untouched", call(g, "pathSeparator", new Class<?>[0]), File.pathSeparator);
        if (expectRewrite || !windows) {
            check("ReLinker pattern matches a zip entry",
                    call(g, "relinkerMatches", new Class<?>[] {String.class, String.class},
                            "lib/arm64-v8a/libflutter.so", "libflutter.so"), true);
        }
        String dir = System.getProperty("java.io.tmpdir");
        check("guest path with the separator opens", call(g, "roundTrip", new Class<?>[] {String.class}, dir), "ok");

        // The host: the same class, not passed through the rewriter, and the
        // JDK's own fields.
        check("host class separatorChar", Guest.separatorChar(), File.separatorChar);
        check("host class separator", Guest.separator(), File.separator);
        check("JDK separator unchanged", File.separator, windows ? "\\" : "/");

        System.out.println(failures == 0 ? "FileSeparatorRewriteTest: OK" : "FileSeparatorRewriteTest: " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
