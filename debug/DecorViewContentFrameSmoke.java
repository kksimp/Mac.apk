import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.FrameLayout;

/**
 * Regression for ActivityWindow.getDecorView(): the decor it hands out must
 * enclose android.R.id.content, as AOSP's DecorView does.
 *
 * Once an AppCompat sub-decor (or any other content root) replaces the window's
 * decor, that view sits INSIDE the frame which carries android.R.id.content.
 * Code that asks the decor for that id -- ImmersionBar:
 * getDecorView().findViewById(android.R.id.content).getFitsSystemWindows() --
 * then gets null and dies. The window hands out the frame instead when the
 * installed root sits in it.
 *
 * Source-only: runs against an android-framework.jar on a plain JDK, with no
 * runtime. The views are allocated without their constructors (a View's needs
 * an attached Context); only the two fields the contract reads are set: the
 * id and the parent.
 *
 *   debug/check_decorview_content_frame.sh <android-framework.jar>
 *   exit status 0 = every case passed
 */
public class DecorViewContentFrameSmoke {
    static final int CONTENT = 0x01020002;   // android.R.id.content
    static final int APP_ROOT = 0x7f0a0001;
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else    { failed++; System.out.println("FAIL  " + name); }
    }

    /** sun.misc.Unsafe.allocateInstance, reached by name: the type is internal. */
    static MethodHandle allocate;
    static Object allocate(Class<?> c) {
        try { return allocate.invoke(c); }
        catch (Throwable t) { throw new IllegalStateException("allocateInstance(" + c.getName() + ")", t); }
    }

    static void initAllocator() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object theUnsafe = f.get(null);
        MethodHandles.Lookup l = MethodHandles.lookup();
        allocate = l.findVirtual(unsafeClass, "allocateInstance",
                MethodType.methodType(Object.class, Class.class)).bindTo(theUnsafe);
    }

    /** A FrameLayout with an id and a parent, and a usable child list. (Only a
     *  ViewGroup is a ViewParent here, so the static type matters to callers.) */
    static FrameLayout frame(int id, ViewParent parent) throws Exception {
        FrameLayout v = (FrameLayout) allocate(FrameLayout.class);
        v.setId(id);
        set(v, "parent", parent);
        set(v, "children", new java.util.ArrayList<View>());
        return v;
    }

    static void set(Object target, String name, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) { /* up one level */ }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    /** The window's private `decor`: what a previous setContentView installed. */
    static void install(Window w, View decor) throws Exception { set(w, "decor", decor); }


    public static void main(String[] args) throws Exception {
        initAllocator();
        Activity activity = new Activity();
        Window w = activity.getWindow();
        String windowClass = w.getClass().getName();

        // --- Nothing installed yet: asking must not build a decor.
        check("no decor yet: peekDecorView() is null", w.peekDecorView() == null);

        // --- A root with no parent is the decor, as before.
        FrameLayout bare = frame(APP_ROOT, null);
        install(w, bare);
        check("a root with no parent is the decor", w.getDecorView() == bare);
        check("... and peekDecorView() agrees", w.peekDecorView() == bare);

        // --- A root inside a frame that is NOT the content frame: unchanged.
        FrameLayout other = frame(0x7f0a0002, null);
        FrameLayout inOther = frame(APP_ROOT, other);
        install(w, inOther);
        check("a root inside some other frame is still the decor", w.getDecorView() == inOther);

        // --- A root whose parent is not a View at all: unchanged.
        ViewParent notAView = (ViewParent) java.lang.reflect.Proxy.newProxyInstance(
                DecorViewContentFrameSmoke.class.getClassLoader(),
                new Class<?>[] { ViewParent.class }, (p, m, a) -> null);
        FrameLayout underParent = frame(APP_ROOT, notAView);
        install(w, underParent);
        check("a root under a non-View parent is still the decor", w.getDecorView() == underParent);

        // --- The reported case: the root sits in the content frame.
        FrameLayout content = frame(CONTENT, null);
        FrameLayout root = frame(APP_ROOT, content);
        install(w, root);
        View decor = w.getDecorView();
        check("a root inside the content frame: the decor IS that frame", decor == content);
        check("... so getDecorView().findViewById(android.R.id.content) finds it",
                decor != null && decor.findViewById(CONTENT) == content);
        check("... and still finds nothing in the app's own root by that id",
                root.findViewById(CONTENT) == null);
        check("... while peekDecorView() keeps returning the installed root",
                w.peekDecorView() == root);
        check("... and the window class is the real one", windowClass.endsWith("ActivityWindow"));

        // --- A FrameLayout root that already is the content frame is not replaced by a parent.
        FrameLayout selfContent = frame(CONTENT, null);
        install(w, selfContent);
        check("a root that is the content frame itself is the decor", w.getDecorView() == selfContent);

        // --- A content frame that has a parent of its own is still what gets handed out.
        FrameLayout outer = frame(0x7f0a0003, null);
        FrameLayout contentInOuter = frame(CONTENT, outer);
        FrameLayout rootInContent = frame(APP_ROOT, contentInOuter);
        install(w, rootInContent);
        check("the content frame is handed out even when it has a parent itself",
                w.getDecorView() == contentInOuter);

        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
