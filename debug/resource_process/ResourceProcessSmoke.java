import android.app.ActivityThread;
import android.app.Application;
import android.content.res.Resources;
import com.macapk.loader.ResourceIndex;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Standalone regressions. Each mode runs in a fresh JVM; no native runtime required. */
public class ResourceProcessSmoke {
    public static final class GuestApplication extends Application {
        final String constructorProcess = Application.getProcessName();
        final String constructorPackage = ActivityThread.currentPackageName();
    }

    private static int id(String value) {
        return (int) Long.parseLong(value.replaceFirst("^0[xX]", ""), 16);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void process(boolean legacy) throws Exception {
        String expected = "com.example.constructor";
        GuestApplication app;
        if (legacy) {
            // Boot publishes the parsed process before directly constructing the app.
            Field field = com.macapk.loader.Boot.class.getDeclaredField("sProcessName");
            field.setAccessible(true);
            field.set(null, expected);
            app = new GuestApplication();
        } else {
            app = (GuestApplication) ActivityThread.currentActivityThread().macapkBindApplication(
                expected, GuestApplication.class.getName(), null,
                ResourceProcessSmoke.class.getClassLoader());
        }
        check(expected.equals(app.constructorProcess), "constructor process=" + app.constructorProcess);
        check(expected.equals(app.constructorPackage), "constructor package=" + app.constructorPackage);
        System.out.println("PASS: constructor identity (" + (legacy ? "Boot" : "ActivityThread") + ")");
    }

    private static void frameworkInteger() throws Exception {
        // An app with no resources cannot accidentally satisfy a framework lookup
        // from the app map. No proprietary APK is needed for this regression.
        Path apk = Files.createTempFile("empty-resource-app-", ".apk");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(apk))) { }
            ResourceIndex index = new ResourceIndex(null, apk.toString());
            Resources.macapkSetResourceIndex(index);
            Resources system = Resources.getSystem();
            int rid = system.getIdentifier("config_cursorWindowSize", "integer", "android");
            int kib = system.getInteger(rid);
            check(rid != 0 && kib == 2048, "bundled platform CursorWindow size: id=" + rid + " KiB=" + kib);
            check(Integer.valueOf(kib).equals(index.integerForId(rid)), "integer ID lookup disagrees");
            int stringId = system.getIdentifier("ok", "string", "android");
            check(stringId != 0 && index.integerForId(stringId) == null, "string coerced to integer");
            check(index.integerForId(0x01ffffff) == null, "unknown framework ID resolved");
            System.out.println("PASS: framework CursorWindow capacity=" + (kib * 1024) + " bytes");
        } finally {
            Files.deleteIfExists(apk);
        }
    }

    private static void path(String[] a) throws Exception {
        // path APK RESOURCE_ID ZIP_ENTRY EXPECTED_ROOT_TAG
        ResourceIndex index = new ResourceIndex(null, a[1]);
        int rid = id(a[2]);
        check(a[3].equals(index.pathFor(rid)), "wrong ZIP path: " + index.pathFor(rid));
        try (InputStream in = index.openRaw(rid)) {
            check(in != null, "existing optimized file missing from index");
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            check(xml.contains(a[4]), "wrong layout root");
        }
        System.out.println("PASS: optimized ZIP resource path");
    }

    @SuppressWarnings("unchecked")
    private static void theme(String[] a) throws Exception {
        // theme APK STYLE_ID ATTR_ID EXPECTED_VALUE
        ResourceIndex index = new ResourceIndex(null, a[1]);
        Field f = ResourceIndex.class.getDeclaredField("idToTypeName");
        f.setAccessible(true);
        Map<Integer, String[]> names = (Map<Integer, String[]>) f.get(index);
        String[] attr = names.get(id(a[3]));
        check(attr != null && "attr".equals(attr[0]), "attribute ID missing");
        String style = index.styleNameForIdRefPublic("@0x" + Integer.toHexString(id(a[2])));
        String value = index.resolveAttrInThemeExact(style, attr[1]);
        check(a[4].equals(value), "theme attribute expected=" + a[4] + " actual=" + value);
        System.out.println("PASS: collapsed-name style inheritance");
    }

    private static void xml(String[] a) throws Exception {
        // xml APK LAYOUT_ID ELEMENT_TAG EXPECTED_WIDTH
        ResourceIndex index = new ResourceIndex(null, a[1]);
        try (InputStream in = index.openRaw(id(a[2]))) {
            check(in != null, "compiled XML resource missing");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document doc = factory.newDocumentBuilder().parse(in);
            Element element = (Element) doc.getElementsByTagName(a[3]).item(0);
            check(element != null, "element missing: " + a[3]);
            String ns = "http://schemas.android.com/apk/res/android";
            check(!element.getAttributeNS(ns, "id").isEmpty(), "compiled android:id lost");
            check(a[4].equals(element.getAttributeNS(ns, "layout_width")), "compiled layout_width lost");
        }
        System.out.println("PASS: compiled attribute identity and namespace");
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("mode: process | legacy | integer | path | theme | xml");
        switch (args[0]) {
            case "process": process(false); break;
            case "legacy": process(true); break;
            case "integer": frameworkInteger(); break;
            case "path": path(args); break;
            case "theme": theme(args); break;
            case "xml": xml(args); break;
            default: throw new IllegalArgumentException("unknown mode: " + args[0]);
        }
    }
}
