import dalvik.system.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** Run with framework + loader jars. Uses generated code and disposable keys. */
public final class ClassLoaderPathSmoke {
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static Path jar(Path root, String name, String value, String unique) throws Exception {
        Path src = Files.createDirectories(root.resolve(name));
        Files.writeString(src.resolve("Choice.java"), "public class Choice {public static String value(){return \""+value+"\";}}");
        Files.writeString(src.resolve(unique+".java"), "public class "+unique+" {}");
        check(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",src.toString(),src.resolve("Choice.java").toString(),src.resolve(unique+".java").toString()) == 0, "fixture compilation");
        Path jar = root.resolve(name+".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String c : new String[]{"Choice",unique}) { out.putNextEntry(new JarEntry(c+".class")); out.write(Files.readAllBytes(src.resolve(c+".class"))); out.closeEntry(); }
            out.putNextEntry(new JarEntry("order.txt")); out.write(value.getBytes()); out.closeEntry();
        }
        return jar;
    }
    static void loader(Path root) throws Exception {
        Path a=jar(root,"a","old","OldOnly"), b=jar(root,"b","new","NewOnly");
        try (PathClassLoader loader=new PathClassLoader(a.toString(), ClassLoader.getPlatformClassLoader())) {
            Field p=BaseDexClassLoader.class.getDeclaredField("pathList"); p.setAccessible(true);
            Object paths=p.get(loader);
            Field f=paths.getClass().getDeclaredField("dexElements"); f.setAccessible(true);
            Object[] old=(Object[])f.get(paths);
            Method make=paths.getClass().getDeclaredMethod("makeDexElements",List.class,File.class,List.class,ClassLoader.class); make.setAccessible(true);
            List<IOException> errors=new ArrayList<>();
            Object[] added=(Object[])make.invoke(null,List.of(b.toFile()),root.toFile(),errors,loader);
            check(errors.isEmpty() && added.length==1,"prepare element");
            try { loader.loadClass("NewOnly"); throw new AssertionError("prepared element visible before injection"); } catch(ClassNotFoundException expected) {}
            Object[] combined=(Object[])Array.newInstance(old.getClass().getComponentType(),old.length+added.length);
            System.arraycopy(added,0,combined,0,added.length); System.arraycopy(old,0,combined,added.length,old.length);
            f.set(paths,combined);
            check("new".equals(loader.loadClass("Choice").getMethod("value").invoke(null)),"prepend class order");
            check("new".equals(new String(loader.findResource("order.txt").openStream().readAllBytes())),"prepend resource order");
            check(Collections.list(loader.findResources("order.txt")).size()==2,"resource enumeration");
            f.set(paths,added);
            try { loader.loadClass("OldOnly"); throw new AssertionError("removed element still loads"); } catch(ClassNotFoundException expected) {}
            check(loader.loadClass("NewOnly").getClassLoader()==loader,"defining loader identity");
            make.invoke(null,List.of(root.resolve("missing.dex").toFile()),root.toFile(),errors,loader);
            check(errors.size()==1,"bad element reports failure");
        }
        System.out.println("loader: PASS (reflection, visibility, order, removal, identity, error)");
    }
    static void mainResources(Path root) throws Exception {
        Path classes=jar(root,"app-classes","classes","AppOnly");
        Path apk=root.resolve("base.apk"),patch=root.resolve("patch-resources.jar");
        for(Path archive:List.of(apk,patch))try(JarOutputStream out=new JarOutputStream(Files.newOutputStream(archive))){
            out.putNextEntry(new JarEntry("apk-marker.txt"));out.write((archive.equals(apk)?"original":"patch").getBytes());out.closeEntry();
        }
        try(com.macapk.loader.AppClassLoader loader=new com.macapk.loader.AppClassLoader(new java.net.URL[]{classes.toUri().toURL()},ClassLoader.getPlatformClassLoader(),null,root.toFile(),null,apk.toString())) {
            check("original".equals(new String(loader.findResource("apk-marker.txt").openStream().readAllBytes())),"original APK resource available");
            Field pf=BaseDexClassLoader.class.getDeclaredField("pathList");pf.setAccessible(true);Object paths=pf.get(loader);
            Field ef=paths.getClass().getDeclaredField("dexElements");ef.setAccessible(true);Object[] original=(Object[])ef.get(paths);
            Method make=paths.getClass().getDeclaredMethod("makeDexElements",List.class,File.class,List.class,ClassLoader.class);make.setAccessible(true);
            Object[] added=(Object[])make.invoke(null,List.of(patch.toFile()),root.toFile(),new ArrayList<IOException>(),loader);
            Object[] append=(Object[])Array.newInstance(original.getClass().getComponentType(),original.length+added.length);
            System.arraycopy(original,0,append,0,original.length);System.arraycopy(added,0,append,original.length,added.length);ef.set(paths,append);
            check("original".equals(new String(loader.findResource("apk-marker.txt").openStream().readAllBytes())),"appended patch does not outrank original APK resource");
            Object[] prepend=(Object[])Array.newInstance(original.getClass().getComponentType(),append.length);
            System.arraycopy(added,0,prepend,0,added.length);System.arraycopy(original,0,prepend,added.length,original.length);ef.set(paths,prepend);
            check("patch".equals(new String(loader.findResource("apk-marker.txt").openStream().readAllBytes())),"prepended patch outranks original APK resource");
            check(Collections.list(loader.findResources("apk-marker.txt")).size()==2,"main loader enumerates managed resources once");
            ef.set(paths,Array.newInstance(original.getClass().getComponentType(),0));
            check(loader.findResource("apk-marker.txt")==null && !loader.findResources("apk-marker.txt").hasMoreElements(),"removed APK resources do not leak through fallback");
        }
        System.out.println("app-resources: PASS (append/prepend, original archive bytes, enumeration, removal)");
    }
    // Both callers must reach findClass before either defines Choice. This
    // exercises the upstream unlocked delegation with the PR's live path.
    static final class RacingLoader extends PathClassLoader {
        static { registerAsParallelCapable(); }
        final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(2);
        RacingLoader(String path) { super(path, ClassLoader.getPlatformClassLoader()); }
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals("Choice")) {
                try { start.await(10, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError("class loading did not reach both callers", ex); }
            }
            return super.findClass(name);
        }
    }
    static void concurrentMutatedPath(Path root) throws Exception {
        if ("0".equals(System.getenv("MACAPK_UNLOCKED_DELEGATION"))) return;
        Path a=jar(root,"race-original","old","RaceOldOnly"), b=jar(root,"race-patch","new","RaceNewOnly");
        try (RacingLoader loader=new RacingLoader(a.toString())) {
            Field pf=BaseDexClassLoader.class.getDeclaredField("pathList"); pf.setAccessible(true);
            Object paths=pf.get(loader);
            Field ef=paths.getClass().getDeclaredField("dexElements"); ef.setAccessible(true);
            Method make=paths.getClass().getDeclaredMethod("makeDexElements",List.class,File.class,List.class,ClassLoader.class); make.setAccessible(true);
            ef.set(paths,make.invoke(null,List.of(b.toFile()),root.toFile(),new ArrayList<IOException>(),loader));
            var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first=pool.submit(() -> loader.loadClass("Choice"));
                var second=pool.submit(() -> loader.loadClass("Choice"));
                Class<?> winner=first.get(15,java.util.concurrent.TimeUnit.SECONDS);
                check(winner==second.get(15,java.util.concurrent.TimeUnit.SECONDS),"racing lookups return the same class");
                check("new".equals(winner.getMethod("value").invoke(null)),"racing lookup honors the changed path");
            } finally { pool.shutdownNow(); }
        }
        System.out.println("loader-concurrency: PASS (parallel definition on mutated dex path)");
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("macapk-loader-smoke-");
        try { loader(root); mainResources(root); concurrentMutatedPath(root); }
        finally { try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);} }
    }
}
