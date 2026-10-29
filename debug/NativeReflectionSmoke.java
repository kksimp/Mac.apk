import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.*;
import javax.net.ssl.*;
import com.macapk.loader.*;

/** Offline integration test, run on the bundled, Android-patched JVM. */
public class NativeReflectionSmoke {
    static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    public static void main(String[] args)throws Exception {
        System.load(Path.of(args[0]).toAbsolutePath().toString());
        try { Class.forName("sun.misc.Unsafe").getMethod("staticFieldBase",Field.class);throw new AssertionError("Expected Android Unsafe surface"); }
        catch(NoSuchMethodException expected){}
        try(AppClassLoader loader=new AppClassLoader(new URL[]{Path.of(args[1]).toUri().toURL()},NativeReflectionSmoke.class.getClassLoader(),null,Path.of(args[1]).getParent().toFile(),null,null)) {
            check(Boolean.TRUE.equals(loader.loadClass("FieldsFixture").getMethod("check").invoke(null)),"real JNI field access");
        }
        Field host=String.class.getDeclaredField("CASE_INSENSITIVE_ORDER");host.setAccessible(true);
        Method write=ArtReflection.class.getDeclaredMethod("writeStatic",Field.class,Object.class);write.setAccessible(true);
        try {write.invoke(null,host,null);throw new AssertionError("JNI host field access");}
        catch(InvocationTargetException ex){check(ex.getCause() instanceof IllegalAccessException,"JNI denies host field");}
        System.out.println("native-reflection: PASS (Android-patched JVM, rewritten app field initialization, native host-field denial; no network)");
    }
}
