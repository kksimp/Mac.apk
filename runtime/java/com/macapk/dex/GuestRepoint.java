package com.macapk.dex;

/**
 * The converter's one table of app call sites that can be redirected to a
 * host-supplied class. Both families are OPT-IN and OFF by default.
 *
 * A re-point swaps the method reference of an invoke for a static on the
 * configured class, with the receiver (if any) prepended to the descriptor. The
 * operand stack the DEX proto describes is untouched, so frames, registers and
 * the move-result that follows are unaffected. Two families live here:
 *
 * 1. sun.misc.Unsafe accessors -> Options.unsafeRedirect, same name, receiver
 *    prepended (see {@link UnsafeRepoint} for the table and for why code that
 *    walks ART's object layout through Unsafe needs it on HotSpot).
 *
 * 2. Process exit -> Options.exitRedirect:
 *      System.exit(int)   [invoke-static]   -> systemExit(I)V
 *      Runtime.exit(int)  [invoke-virtual]  -> runtimeExit(Ljava/lang/Runtime;I)V
 *      Runtime.halt(int)  [invoke-virtual]  -> runtimeHalt(Ljava/lang/Runtime;I)V
 *    WHY. On a device these end the process by SIGKILL-equivalent means and
 *    the ActivityManager relaunches it if an activity start is pending -- the
 *    basis of every "restart myself" idiom (JakeWharton's ProcessPhoenix is the
 *    one Aurora Store and NewPipe ship). On HotSpot the same calls end the
 *    WHOLE VM: Runtime.exit runs shutdown hooks, then vm_exit and libc exit(),
 *    whose atexit handlers and C++ static destructors run while the VM is
 *    going down -- and a native destructor that re-enters JNI at that point
 *    blocks forever in JavaThread::block_if_vm_exited, so the "exit" can hang
 *    instead. A host that runs the converted app inside a JVM it owns routes
 *    these calls to its own class, which can then play the ActivityManager's
 *    part (relaunch on a pending start, or end only the app). Kotlin's
 *    exitProcess() inlines to System.exit, so it is covered.
 *
 * 3. Native library naming and loading -> Options.nativeLoadRedirect:
 *      System.mapLibraryName(String) [invoke-static]  -> mapLibraryName(String)String
 *      System.load(String)           [invoke-static]  -> load(String)V
 *      Runtime.load(String)          [invoke-virtual] -> runtimeLoad(Runtime,String)V
 *    WHY. On a device mapLibraryName answers "lib" + name + ".so" and load()
 *    takes the path of such a file. A library loader that bypasses
 *    System.loadLibrary (Facebook's SoLoader is the common one) asks
 *    mapLibraryName for the name, looks for that file in directories it manages
 *    (including ones it fills itself by unpacking an archive at run time) and
 *    calls load() with the path it found. A host JVM answers mapLibraryName in
 *    its own platform's naming, so the lookup misses the very files the app just
 *    wrote. Routing the three calls to the host lets it answer with Android's
 *    name and handle load() of such a file itself.
 *
 * Only the input dex is ever routed: the converter never sees framework or
 * library classes that are not in it.
 */
public final class GuestRepoint {
    private GuestRepoint() {}

    /** The re-pointed target, or null when this call keeps its direct binding. */
    public static final class Target {
        public final String owner;
        public final String name;
        public final String desc;
        Target(String owner, String name, String desc) {
            this.owner = owner; this.name = name; this.desc = desc;
        }
    }

    /**
     * @param isStatic  true for invoke-static, false for invoke-virtual (other
     *                  families -- direct/super/interface -- are never re-pointed)
     */
    public static Target lookup(boolean isStatic, boolean isVirtual,
                                String owner, String name, String desc) {
        String exitTarget = Options.exitRedirect;
        if (isVirtual) {
            if (UnsafeRepoint.OWNER.equals(owner)) {
                String d = UnsafeRepoint.repoint(name, desc);
                return d == null ? null : new Target(Options.unsafeRedirect, name, d);
            }
            String nativeTarget = Options.nativeLoadRedirect;
            if (nativeTarget != null && "java/lang/Runtime".equals(owner)
                    && "load".equals(name) && "(Ljava/lang/String;)V".equals(desc))
                return new Target(nativeTarget, "runtimeLoad", "(Ljava/lang/Runtime;Ljava/lang/String;)V");
            if (exitTarget == null) return null;
            if ("java/lang/Runtime".equals(owner) && "(I)V".equals(desc)) {
                if ("exit".equals(name)) return new Target(exitTarget, "runtimeExit", "(Ljava/lang/Runtime;I)V");
                if ("halt".equals(name)) return new Target(exitTarget, "runtimeHalt", "(Ljava/lang/Runtime;I)V");
            }
            return null;
        }
        String nativeLoad = Options.nativeLoadRedirect;
        if (isStatic && nativeLoad != null && "java/lang/System".equals(owner)) {
            if ("mapLibraryName".equals(name) && "(Ljava/lang/String;)Ljava/lang/String;".equals(desc))
                return new Target(nativeLoad, "mapLibraryName", desc);
            if ("load".equals(name) && "(Ljava/lang/String;)V".equals(desc))
                return new Target(nativeLoad, "load", desc);
        }
        if (isStatic && exitTarget != null) {
            if ("java/lang/System".equals(owner) && "exit".equals(name) && "(I)V".equals(desc))
                return new Target(exitTarget, "systemExit", "(I)V");
        }
        return null;
    }
}
