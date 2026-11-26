package com.macapk.dex;

/**
 * Ordinary native-load repointing and per-job option isolation.
 *
 * Run after debug/build_java_only.sh:
 *   javac -cp runtime/build/lib/macapk-loader.jar -d /tmp/native-load-test debug/NativeLoadRepointSmoke.java
 *   java -cp /tmp/native-load-test:runtime/build/lib/macapk-loader.jar com.macapk.dex.NativeLoadRepointSmoke
 */
public final class NativeLoadRepointSmoke {
    private static final String SYSTEM = "java/lang/System";
    private static final String RUNTIME = "java/lang/Runtime";
    private static final String STRING = "Ljava/lang/String;";
    private static final String LOAD_DESC = "(" + STRING + ")V";
    private static final String NAME_DESC = "(" + STRING + ")" + STRING;

    public static void main(String[] args) {
        String host = args.length == 0 ? HostDefaults.REDIRECT_NATIVE_LOAD : args[0];
        require(host.equals(Options.nativeLoadRedirect), "initial native-load default");
        Object[] defaults = Options.snapshot();
        try {
            assertOrdinaryMappings(host);
            noTarget(true, false, SYSTEM, "loadLibrary", LOAD_DESC);
            noTarget(false, true, RUNTIME, "loadLibrary", LOAD_DESC);
            noTarget(false, false, RUNTIME, "load", LOAD_DESC);
            noTarget(true, false, SYSTEM, "load", "()V");
            noTarget(true, false, "dev/fixture/System", "load", LOAD_DESC);

            // Native loading stays independent of the process-exit option.
            Options.exitRedirect = null;
            assertOrdinaryMappings(host);
            noTarget(true, false, SYSTEM, "exit", "(I)V");

            // A standalone converter can opt out entirely.
            Options.nativeLoadRedirect = null;
            noTarget(true, false, SYSTEM, "mapLibraryName", NAME_DESC);
            noTarget(true, false, SYSTEM, "load", LOAD_DESC);
            noTarget(false, true, RUNTIME, "load", LOAD_DESC);

            // A CLI job may choose its own target, but must not leak the
            // option into the next job in the shared converter process.
            Object[] disabled = Options.snapshot();
            String custom = "dev/fixture/CustomLoad";
            Main.parseArgs(new String[]{"--redirect-native-load", custom, "input.dex", "out.jar"});
            assertOrdinaryMappings(custom);
            Options.restore(disabled);
            noTarget(true, false, SYSTEM, "load", LOAD_DESC);
        } finally {
            Options.restore(defaults);
        }
        assertOrdinaryMappings(host);
        System.out.println("PASS: native-load repointings, opt-out, CLI and option isolation");
    }

    private static void assertOrdinaryMappings(String target) {
        check(target, "mapLibraryName", NAME_DESC,
                GuestRepoint.lookup(true, false, SYSTEM, "mapLibraryName", NAME_DESC));
        check(target, "load", LOAD_DESC,
                GuestRepoint.lookup(true, false, SYSTEM, "load", LOAD_DESC));
        check(target, "runtimeLoad", "(Ljava/lang/Runtime;" + STRING + ")V",
                GuestRepoint.lookup(false, true, RUNTIME, "load", LOAD_DESC));
    }

    private static void noTarget(boolean isStatic, boolean isVirtual,
                                 String owner, String name, String desc) {
        require(GuestRepoint.lookup(isStatic, isVirtual, owner, name, desc) == null,
                "unexpected mapping for " + owner + "." + name + desc);
    }

    private static void check(String owner, String name, String desc, GuestRepoint.Target target) {
        require(target != null, "missing mapping for " + name);
        require(owner.equals(target.owner) && name.equals(target.name) && desc.equals(target.desc),
                "wrong mapping for " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
