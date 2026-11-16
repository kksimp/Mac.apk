// ApiCheck: which platform classes, methods and fields do converted apps name that the
// runtime does not have?
//
//   java --enable-preview --source 23 ApiCheck.java <lib dir> <out.tsv> <classes.jar>...
//
// <lib dir> holds android-framework.jar and macapk-loader.jar (and whatever else the runtime
// puts on the class path); each classes.jar is an app as the DEX converter left it
// (%LOCALAPPDATA%\Win.apk\apps\<name>\classes.jar). Every class, method and field reference
// in the apps' constant pools is resolved the way the JVM would: in the class named, then up
// its superclasses and interfaces, through the app's own classes into the platform's. What
// does not resolve would be a NoClassDefFoundError, NoSuchMethodError or NoSuchFieldError
// the moment that code runs. The output lists them by how many apps name each, so the gaps
// that matter to the most apps come first.
//
// Not everything listed is reached: apps guard newer APIs behind Build.VERSION.SDK_INT. And
// not everything missing is listed: reflection and JNI lookups name their targets in strings.
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApiCheck {
    /** What resolution needs of a class. */
    static final class Info {
        String superName;
        List<String> interfaces = new ArrayList<>();
        Set<String> methods = new HashSet<>();   // name + descriptor
        Set<String> fields = new HashSet<>();    // name
        boolean platform;
    }

    static final Info MISSING = new Info();
    static final Map<String, Info> platformCache = new HashMap<>();
    static final List<ZipFile> platformJars = new ArrayList<>();
    static final List<Path> platformDirs = new ArrayList<>();
    static FileSystem jrt;

    static Info parse(byte[] bytes, boolean platform) {
        ClassModel cm = ClassFile.of().parse(bytes);
        Info i = new Info();
        i.platform = platform;
        i.superName = cm.superclass().map(ClassEntry::asInternalName).orElse(null);
        cm.interfaces().forEach(e -> i.interfaces.add(e.asInternalName()));
        cm.methods().forEach(m -> i.methods.add(m.methodName().stringValue() + m.methodType().stringValue()));
        cm.fields().forEach(f -> i.fields.add(f.fieldName().stringValue()));
        return i;
    }

    static Info platformClass(String name) {
        Info cached = platformCache.get(name);
        if (cached != null) return cached;
        Info found = MISSING;
        try {
            byte[] bytes = null;
            for (ZipFile z : platformJars) {
                ZipEntry e = z.getEntry(name + ".class");
                if (e != null) {
                    try (InputStream in = z.getInputStream(e)) { bytes = in.readAllBytes(); }
                    break;
                }
            }
            if (bytes == null) {
                for (Path d : platformDirs) {
                    Path p = d.resolve(name + ".class");
                    if (Files.isRegularFile(p)) { bytes = Files.readAllBytes(p); break; }
                }
            }
            if (bytes == null) {
                int slash = name.lastIndexOf('/');
                if (slash > 0) {
                    String pkg = name.substring(0, slash);
                    Path link = jrt.getPath("/packages", pkg.replace('/', '.'));
                    if (Files.isDirectory(link)) {
                        try (var mods = Files.list(link)) {
                            for (Path m : (Iterable<Path>) mods::iterator) {
                                Path p = jrt.getPath("/modules", m.getFileName().toString(), name + ".class");
                                if (Files.isRegularFile(p)) { bytes = Files.readAllBytes(p); break; }
                            }
                        }
                    }
                }
            }
            if (bytes != null) found = parse(bytes, true);
        } catch (Throwable t) {
            found = MISSING;
        }
        platformCache.put(name, found);
        return found;
    }

    /** One app: its own classes first, then the platform. */
    static final class App {
        final ZipFile jar;
        final Map<String, Info> own = new HashMap<>();
        App(ZipFile jar) { this.jar = jar; }

        Info get(String name) {
            // The platform's class wins, as it does on a device (the boot class path) and in
            // the runtime (AppClassLoader asks its parent first): R8 leaves empty copies of
            // newer platform classes in an app so that old devices can verify its code.
            // ...except the families AppClassLoader gives to the app when it carries them
            // (GMS_CHILD_FIRST_PREFIXES, CHILD_FIRST_MISC_PREFIXES there): libraries no device
            // has on its boot class path.
            if (platformName(name) && !(childFirst(name) && jar.getEntry(name + ".class") != null)) {
                Info p = platformClass(name);
                if (p != MISSING) return p;
            }
            Info i = own.get(name);
            if (i != null) return i;
            ZipEntry e = jar.getEntry(name + ".class");
            if (e != null) {
                try (InputStream in = jar.getInputStream(e)) {
                    i = parse(in.readAllBytes(), false);
                } catch (Throwable t) {
                    i = MISSING;
                }
                own.put(name, i);
                return i;
            }
            return platformName(name) ? MISSING : platformClass(name);
        }

        /** 1 found, 0 not found, -1 cannot tell (a class on the way is itself missing). */
        int find(String cls, String key, boolean method, Set<String> seen, String[] platformOwner) {
            if (cls == null || !seen.add(cls)) return 0;
            Info i = get(cls);
            if (i == MISSING) return -1;
            if (i.platform && platformOwner[0] == null) platformOwner[0] = cls;
            if ((method ? i.methods : i.fields).contains(key)) return 1;
            boolean unknown = false;
            int r = find(i.superName, key, method, seen, platformOwner);
            if (r == 1) return 1;
            unknown |= r == -1;
            for (String itf : i.interfaces) {
                r = find(itf, key, method, seen, platformOwner);
                if (r == 1) return 1;
                unknown |= r == -1;
            }
            return unknown ? -1 : 0;
        }
    }

    static boolean childFirst(String n) {
        return n.startsWith("org/apache/http/") || n.startsWith("com/android/billingclient/")
            || n.startsWith("org/bouncycastle/");
    }

    static boolean platformName(String n) {
        return n.startsWith("android/") || n.startsWith("java/") || n.startsWith("javax/") || n.startsWith("dalvik/")
            || n.startsWith("libcore/") || n.startsWith("org/xmlpull/") || n.startsWith("org/json/")
            || n.startsWith("org/w3c/") || n.startsWith("org/xml/") || n.startsWith("org/apache/http/")
            || n.startsWith("com/android/") || n.startsWith("sun/misc/");
    }

    static String element(String internal) {
        if (!internal.startsWith("[")) return internal;
        int i = internal.lastIndexOf('[') + 1;
        return internal.charAt(i) == 'L' ? internal.substring(i + 1, internal.length() - 1) : null;
    }

    public static void main(String[] args) throws IOException {
        Path lib = Path.of(args[0]);
        try (var files = Files.list(lib)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.toString().endsWith(".jar")) platformJars.add(new ZipFile(p.toFile()));
                else if (Files.isDirectory(p) && p.getFileName().toString().endsWith("_patch")) platformDirs.add(p);
            }
        }
        jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        // what is missing -> the apps naming it
        Map<String, Set<String>> missing = new TreeMap<>();
        for (int a = 2; a < args.length; a++) {
            Path jarPath = Path.of(args[a]);
            String appName = jarPath.getParent().getFileName().toString();
            try (ZipFile z = new ZipFile(jarPath.toFile())) {
                App app = new App(z);
                Set<String> classesSeen = new HashSet<>();
                Set<String> membersSeen = new HashSet<>();
                var entries = z.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (!e.getName().endsWith(".class")) continue;
                    ClassModel cm;
                    try (InputStream in = z.getInputStream(e)) {
                        cm = ClassFile.of().parse(in.readAllBytes());
                    } catch (Throwable t) {
                        continue;
                    }
                    for (PoolEntry pe : cm.constantPool()) {
                        if (pe instanceof ClassEntry ce) {
                            String n = element(ce.asInternalName());
                            if (n == null || !classesSeen.add(n)) continue;
                            if (platformName(n) && app.get(n) == MISSING) {
                                missing.computeIfAbsent("class\t" + n.replace('/', '.'), k -> new TreeSet<>()).add(appName);
                            }
                        } else if (pe instanceof MemberRefEntry me) {
                            String owner = element(me.owner().asInternalName());
                            if (owner == null) continue;
                            boolean method = !(pe instanceof FieldRefEntry);
                            String name = me.name().stringValue();
                            String key = method ? name + me.type().stringValue() : name;
                            if (!membersSeen.add(owner + "." + key)) continue;
                            // invokeExact and friends are made up by the JVM per call site.
                            if (owner.equals("java/lang/invoke/MethodHandle") || owner.equals("java/lang/invoke/VarHandle")) {
                                continue;
                            }
                            String[] platformOwner = new String[1];
                            int r = app.find(owner, key, method, new HashSet<>(), platformOwner);
                            // Nothing but Object above an app's class: its own business (R8 strips
                            // what it proves unreachable), not the platform's.
                            if (r != 0 || platformOwner[0] == null) continue;
                            if (!platformName(owner) && platformOwner[0].equals("java/lang/Object")) continue;
                            // Named on an app class: the member is expected of its first platform ancestor.
                            String shown = platformName(owner) ? owner : platformOwner[0];
                            if (!platformName(shown)) continue;
                            missing.computeIfAbsent((method ? "method\t" : "field\t") + shown.replace('/', '.') + "." + key,
                                                    k -> new TreeSet<>()).add(appName);
                        }
                    }
                }
            } catch (Throwable t) {
                System.err.println("skipped " + jarPath + ": " + t);
            }
            System.err.println("checked " + appName + " (" + missing.size() + " gaps so far)");
        }
        List<Map.Entry<String, Set<String>>> rows = new ArrayList<>(missing.entrySet());
        rows.sort((x, y) -> y.getValue().size() != x.getValue().size()
            ? y.getValue().size() - x.getValue().size() : x.getKey().compareTo(y.getKey()));
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[1]), StandardCharsets.UTF_8))) {
            for (var row : rows) {
                out.println(row.getValue().size() + "\t" + row.getKey() + "\t" + String.join(",", row.getValue()));
            }
        }
        System.err.println(rows.size() + " gaps written to " + args[1]);
    }
}
